package com.malfoid.scenario;

import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalSceneContext.Subject;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.PersonalOutcomeFact.Payload;
import com.malfoid.additional.SceneKey;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pinned personal-visitor and Roots ingress, using copied current leases only. */
public final class AdditionalWorldSensor
{
	private ScenarioSnapshot previous;
	private SceneKey scene;
	private long presence, lastTick = -1, session = -1, actorConsumed, objectConsumed, rootsEpisode;
	private int settleCommits;
	private Truth rootsActive = Truth.UNKNOWN;
	private String handledVisitor;
	private Evidence visitorEvidence, rootsEvidence;

	/** Exact RandomEventPlugin.EVENT_NPCS in the pinned matching1.13.1 source. */
	public static boolean visitorDefinitionSupported(int id)
	{
		switch (id)
		{
			case 307: case 314: case 322: case 326: case 327: case 375: case 376: case 380: case 390:
			case 5426: case 5429: case 5436: case 5437: case 5438: case 5439: case 5440: case 5441:
			case 5510: case 6738: case 6743: case 6744: case 6746: case 6747: case 6748: case 6749:
			case 6750: case 6751: case 6752: case 6753: case 6754: case 6755: case 12551: case 12552: return true;
			default: return false;
		}
	}

	/** ObjectID inherits these two declarations from gameval/ObjectID1.java. */
	public static boolean rootsDefinitionSupported(int id) { return id == 47482 || id == 47483; }

	EnumSet<SourceKind> watchedSources() { return EnumSet.of(SourceKind.ACTOR_INTERACTION, SourceKind.OBJECT_TRANSITION); }
	EnumSet<SourceKind> armedSources()
	{
		EnumSet<SourceKind> sources = EnumSet.noneOf(SourceKind.class);
		if (visitorEvidence != null) { sources.add(SourceKind.ACTOR_INTERACTION); }
		if (rootsEvidence != null) { sources.add(SourceKind.OBJECT_TRANSITION); }
		return sources;
	}
	boolean hasPendingWork() { return visitorEvidence != null || rootsEvidence != null; }
	int retainedReducerRecords() { return 2 + (visitorEvidence == null ? 0 : 1) + (rootsEvidence == null ? 0 : 1); }
	Set<String> retainedCauseKeys()
	{
		Set<String> keys = new HashSet<>();
		if (visitorEvidence != null) { keys.add(visitorEvidence.cause); }
		if (rootsEvidence != null) { keys.add(rootsEvidence.cause); }
		return Collections.unmodifiableSet(keys);
	}

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioBatch batch, WorldSnapshot w,
		CombatState combat, ExpandedScenarioRules.Factory factory)
	{ return close(f, batch, w, combat, factory, watchedSources()); }

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioBatch batch, WorldSnapshot w,
		CombatState combat, ExpandedScenarioRules.Factory factory, Set<SourceKind> dispatch)
	{
		List<SceneCandidate> out = new ArrayList<>();
		updateSession(f);
		if (!complete(f, w) || batch.localDeath || combat == CombatState.UNKNOWN)
		{ consume(w); reset(); return out; }
		boolean continuous = advance(f, w);
		if (!continuous || w.baseline)
		{ consume(w); seed(w); return out; }
		if (visitorEvidence != null && (!current(visitorEvidence, f, w, combat) || f.tick > visitorEvidence.context.originalDeadline + 40))
		{ visitorEvidence = null; }
		if (rootsEvidence != null && (!current(rootsEvidence, f, w, combat) || f.tick > rootsEvidence.context.originalDeadline + 40))
		{ rootsEvidence = null; }
		EnumSet<SourceKind> sources = armedSources(); sources.addAll(dispatch);
		if (sources.contains(SourceKind.OBJECT_TRANSITION)) { roots(f, w, factory, out); }
		if (sources.contains(SourceKind.ACTOR_INTERACTION)) { visitor(f, w, factory, out); }
		return out;
	}

	void advanceQuiet(ScenarioSnapshot f, WorldSnapshot w)
	{
		updateSession(f);
		if (!complete(f, w)) { consume(w); reset(); return; }
		boolean continuous = advance(f, w);
		consume(w);
		if (!continuous || w.baseline) { seed(w); }
		Truth now = w.rootsIndexComplete ? w.rootsActive : Truth.UNKNOWN;
		if (now != rootsActive) { rootsActive = now; rootsEpisode = 0; }
		if (visitorEvidence != null && (!current(visitorEvidence, f, w, CombatState.NO_CONFIRMED_EVIDENCE)
			|| f.tick > visitorEvidence.context.originalDeadline + 40)) { visitorEvidence = null; }
		if (rootsEvidence != null && (!current(rootsEvidence, f, w, CombatState.NO_CONFIRMED_EVIDENCE)
			|| f.tick > rootsEvidence.context.originalDeadline + 40)) { rootsEvidence = null; }
	}

	private void visitor(ScenarioSnapshot f, WorldSnapshot w, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (w.attentionSerial <= actorConsumed) { return; }
		actorConsumed = w.attentionSerial;
		String oldHandled = handledVisitor;
		if (w.firstPersonalAttention) { handledVisitor = w.attentionKey; }
		if (!w.firstPersonalAttention || !w.attentionWitnessed || w.attentionTick < f.presentSinceTick
			|| w.attentionTick > f.tick || f.tick >= w.attentionTick + 20 || settleCommits < 10 || f.tick - f.presentSinceTick < 10
			|| !Objects.equals(w.attentionKey, w.visitorKey) || Objects.equals(oldHandled, w.visitorKey)
			|| w.attentionDefinitionId != w.visitorDefinitionId || !visitorCurrent(f, w)) { return; }
		Payload payload = new Payload(Branch.UNKNOWN, ActivityKind.UNKNOWN, "", -1, -1, -1, -1, -1, -1, null, true);
		AdditionalSceneContext context = new AdditionalSceneContext(150, SourceKind.ACTOR_INTERACTION,
			w.attentionSerial, w.attentionSerial, w.attentionTick, w.attentionTick + 20, w.scene,
			w.presenceEpoch, Subject.unit(), AdditionalSceneContext.CLAIM_SCENE_EDGE, payload);
		String cause = "world:ACTOR_INTERACTION:" + w.attentionSerial;
		Map<String, String> facts = project(context); facts.put("visitorKey", w.visitorKey); facts.put("visitorName", w.visitorName);
		facts.put("visitorDirectedToLocal", "true");
		SceneCandidate candidate = factory.create(f, "M150", 30, (int) (context.originalDeadline - f.tick), cause, facts, false, 4);
		visitorEvidence = new Evidence(candidate.getId(), context, cause, w.visitorKey, w.visitorDefinitionId, w.visitorName);
		out.add(candidate);
	}

	private void roots(ScenarioSnapshot f, WorldSnapshot w, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		boolean fresh = w.rootsSpawnSerial > objectConsumed;
		objectConsumed = Math.max(objectConsumed, w.rootsSpawnSerial);
		Truth before = rootsActive;
		rootsActive = w.rootsIndexComplete ? w.rootsActive : Truth.UNKNOWN;
		if (rootsActive != Truth.TRUE) { rootsEpisode = 0; return; }
		if (before == Truth.TRUE) { return; }
		rootsEpisode = 0;
		if (before != Truth.FALSE || !fresh || !w.rootsSpawnWitnessed || !w.rootsSpawnNearby
			|| !rootsDefinitionSupported(w.rootsSpawnDefinitionId) || w.rootsSpawnTick < f.presentSinceTick
			|| w.rootsSpawnTick > f.tick || f.tick >= w.rootsSpawnTick + 20) { return; }
		rootsEpisode = w.rootsSpawnSerial;
		if (settleCommits < 10 || f.tick - f.presentSinceTick < 10 || !rootsCurrent(f, w)) { return; }
		AdditionalSceneContext context = new AdditionalSceneContext(167, SourceKind.OBJECT_TRANSITION,
			rootsEpisode, rootsEpisode, w.rootsSpawnTick, w.rootsSpawnTick + 20, w.scene,
			w.presenceEpoch, Subject.unit(), AdditionalSceneContext.CLAIM_SCENE_EDGE, Payload.EMPTY);
		String cause = "world:OBJECT_TRANSITION:" + rootsEpisode;
		Map<String, String> facts = project(context); facts.put("worldObjectKind", "RISING_ROOTS"); facts.put("freshWorldObject", "true");
		SceneCandidate candidate = factory.create(f, "M167", 35, (int) (context.originalDeadline - f.tick), cause, facts, false, 4);
		rootsEvidence = new Evidence(candidate.getId(), context, cause, null, -1, ""); out.add(candidate);
	}

	boolean stillValid(SceneCandidate candidate, ScenarioSnapshot f, WorldSnapshot w, CombatState combat)
	{ return f != null && f.tick < candidate.getExpiresTick() && continuationValid(candidate, f, w, combat); }
	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f, WorldSnapshot w, CombatState combat)
	{ Evidence evidence = evidence(candidate); return evidence != null && current(evidence, f, w, combat); }
	AdditionalSceneContext context(SceneCandidate candidate)
	{ Evidence evidence = evidence(candidate); return evidence == null ? null : evidence.context; }
	SourceKind source(SceneCandidate candidate)
	{ AdditionalSceneContext context = context(candidate); return context == null ? SourceKind.UNKNOWN : context.sourceKind; }
	/** A copied negative callback permanently retires the affected token before the next commit. */
	void invalidateVisitor(String key)
	{
		if (visitorEvidence != null && (key == null || key.equals(visitorEvidence.visitorKey))) { visitorEvidence = null; }
	}
	void invalidateRoots()
	{
		rootsEvidence = null; rootsEpisode = 0; rootsActive = Truth.UNKNOWN;
	}
	private Evidence evidence(SceneCandidate candidate)
	{
		if (visitorEvidence != null && visitorEvidence.candidateId == candidate.getId()) { return visitorEvidence; }
		return rootsEvidence != null && rootsEvidence.candidateId == candidate.getId() ? rootsEvidence : null;
	}
	private boolean current(Evidence evidence, ScenarioSnapshot f, WorldSnapshot w, CombatState combat)
	{
		if (!complete(f, w) || w.baseline || combat == CombatState.UNKNOWN || !evidence.context.scene.equals(w.scene)
			|| evidence.context.presenceEpoch != w.presenceEpoch) { return false; }
		if (evidence.context.ruleIndex == 150)
		{
			return visitorCurrent(f, w) && evidence.visitorKey.equals(w.visitorKey)
				&& evidence.definitionId == w.visitorDefinitionId && evidence.name.equals(w.visitorName);
		}
		return rootsEpisode == evidence.context.causeSerial && rootsCurrent(f, w);
	}
	private static boolean visitorCurrent(ScenarioSnapshot f, WorldSnapshot w)
	{
		return w.visitorKnown && w.visitorLive && w.visitorSameView && w.visitorTargetsLocal == Truth.TRUE
			&& w.localTargetsVisitor == Truth.FALSE && key(w.visitorKey) && name(w.visitorName)
			&& visitorDefinitionSupported(w.visitorDefinitionId) && w.visitorPlane == f.player.plane
			&& Math.max(Math.abs((long) w.visitorX - f.player.x), Math.abs((long) w.visitorY - f.player.y)) <= 6;
	}
	private static boolean rootsCurrent(ScenarioSnapshot f, WorldSnapshot w)
	{
		if (!w.rootsIndexComplete || w.rootsActive != Truth.TRUE || !w.rootsAnchorKnown || !w.rootsSameView
			|| !rootsDefinitionSupported(w.rootsAnchorDefinitionId) || w.rootsPlane != f.player.plane
			|| w.rootsMaxX < w.rootsMinX || w.rootsMaxY < w.rootsMinY) { return false; }
		long dx = Math.max(0L, Math.max((long) w.rootsMinX - f.player.x, (long) f.player.x - w.rootsMaxX));
		long dy = Math.max(0L, Math.max((long) w.rootsMinY - f.player.y, (long) f.player.y - w.rootsMaxY));
		return Math.max(dx, dy) <= 6;
	}

	private boolean advance(ScenarioSnapshot f, WorldSnapshot w)
	{
		boolean continuous = previous != null && f.tick == lastTick + 1 && w.scene.equals(scene)
			&& w.presenceEpoch == presence && f.player.distance(previous.player) <= 2;
		if (!continuous) { reset(); }
		previous = f; scene = w.scene; presence = w.presenceEpoch; lastTick = f.tick; settleCommits++;
		return continuous;
	}
	private void updateSession(ScenarioSnapshot f)
	{ if (f != null && session != f.sessionGeneration) { session = f.sessionGeneration; actorConsumed = 0; objectConsumed = 0; } }
	private void consume(WorldSnapshot w)
	{
		if (w == null || w.scene.sessionGeneration != session) { return; }
		actorConsumed = Math.max(actorConsumed, w.attentionSerial); objectConsumed = Math.max(objectConsumed, w.rootsSpawnSerial);
	}
	private void seed(WorldSnapshot w)
	{
		rootsActive = w.rootsIndexComplete ? w.rootsActive : Truth.UNKNOWN; rootsEpisode = 0;
		handledVisitor = w.visitorKnown && w.visitorTargetsLocal == Truth.TRUE ? w.visitorKey : null;
		visitorEvidence = null; rootsEvidence = null;
	}
	void reset()
	{
		previous = null; scene = null; lastTick = -1; settleCommits = 0; rootsActive = Truth.UNKNOWN; rootsEpisode = 0;
		handledVisitor = null; visitorEvidence = null; rootsEvidence = null;
	}
	private static boolean complete(ScenarioSnapshot f, WorldSnapshot w)
	{
		return f != null && w != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0
			&& f.player != null && f.presentSinceTick >= 0 && w.tick == f.tick && w.presenceEpoch == f.presentSinceTick
			&& w.scene.sessionGeneration == f.sessionGeneration && w.scene.sceneRevision == f.sceneRevision;
	}
	private static boolean key(String text) { return text != null && !text.isEmpty() && text.length() <= 64; }
	private static boolean name(String text)
	{
		if (text == null || text.trim().isEmpty() || text.codePointCount(0, text.length()) > 96) { return false; }
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i); if (Character.isISOControl(c) || c == '<' || c == '>' || c == '@') { return false; }
			if (Character.isHighSurrogate(c)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) { return false; } }
			else if (Character.isLowSurrogate(c)) { return false; }
		}
		return true;
	}
	private static Map<String, String> project(AdditionalSceneContext context)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("additionalSourceKind", context.sourceKind.name());
		facts.put("additionalSourceSerial", Long.toString(context.sourceSerial)); facts.put("additionalCauseSerial", Long.toString(context.causeSerial));
		facts.put("additionalClaimMask", Long.toString(context.permittedClaimMask)); facts.put("occurredTick", Long.toString(context.occurrenceTick));
		facts.put("witnessPresenceEpoch", Long.toString(context.presenceEpoch)); return facts;
	}
	private static final class Evidence
	{
		final long candidateId;
		final AdditionalSceneContext context;
		final String cause, visitorKey, name;
		final int definitionId;
		Evidence(long id, AdditionalSceneContext context, String cause, String key, int definitionId, String name)
		{ candidateId = id; this.context = context; this.cause = cause; visitorKey = key; this.definitionId = definitionId; this.name = name; }
	}

	/** Input-only copies; WorldSnapshot flattens these, never retains them. Ambiguous/truncated ingress makes its source unknown. */
	public static final class VisitorCopy
	{
		public final boolean known, live, sameView, firstPersonalAttention, attentionWitnessed;
		public final String key, name, attentionKey;
		public final int definitionId, x, y, plane, attentionDefinitionId;
		public final Truth targetsLocal, localTargetsVisitor;
		public final long attentionSerial, attentionTick;
		/**
		 * firstPersonalAttention means the exact callback guard passed and this spawn has never supplied an earlier personal edge.
		 * The producer preserves that discriminator in its existing NPC identity, including silent baselines/absence.
		 * More than one distinct personal spawn in an interval is ambiguous for this one-lease route: known must be false.
		 */
		public VisitorCopy(boolean known, String key, int definitionId, String name, int x, int y, int plane,
			boolean live, boolean sameView, Truth targetsLocal, Truth localTargetsVisitor, long attentionSerial,
			long attentionTick, String attentionKey, int attentionDefinitionId, boolean firstPersonalAttention, boolean attentionWitnessed)
		{
			this.known = known; this.key = key; this.definitionId = definitionId; this.name = name; this.x = x; this.y = y; this.plane = plane;
			this.live = live; this.sameView = sameView; this.targetsLocal = Objects.requireNonNull(targetsLocal);
			this.localTargetsVisitor = Objects.requireNonNull(localTargetsVisitor); this.attentionSerial = attentionSerial;
			this.attentionTick = attentionTick; this.attentionKey = attentionKey; this.attentionDefinitionId = attentionDefinitionId;
			this.firstPersonalAttention = firstPersonalAttention; this.attentionWitnessed = attentionWitnessed;
		}
		public static VisitorCopy unknown() { return new VisitorCopy(false, null, -1, null, 0, 0, -1, false, false, Truth.UNKNOWN, Truth.UNKNOWN, 0, -1, null, -1, false, false); }
	}
	public static final class RootsCopy
	{
		public final boolean indexComplete, anchorKnown, sameView, spawnWitnessed, spawnNearby;
		public final Truth active;
		public final int anchorDefinitionId, minX, minY, maxX, maxY, plane, spawnDefinitionId;
		public final long spawnSerial, spawnTick;
		/**
		 * indexComplete covers the whole current scene/view's two-definition event index, never a neighborhood inspection cache.
		 * A plugin enabled in an existing world cannot infer completeness from an empty callback map.
		 * spawnNearby requires copied same-view/plane footprint distance<=6 at the witnessed actual GameObjectSpawned callback.
		 * The producer normalizes all same-interval roots to one copied first fresh ingress; phases attach to the current episode.
		 */
		public RootsCopy(boolean indexComplete, Truth active, boolean anchorKnown, boolean sameView, int anchorDefinitionId,
			int minX, int minY, int maxX, int maxY, int plane, long spawnSerial, long spawnTick, int spawnDefinitionId,
			boolean spawnWitnessed, boolean spawnNearby)
		{
			this.indexComplete = indexComplete; this.active = Objects.requireNonNull(active); this.anchorKnown = anchorKnown; this.sameView = sameView;
			this.anchorDefinitionId = anchorDefinitionId; this.minX = minX; this.minY = minY; this.maxX = maxX; this.maxY = maxY; this.plane = plane;
			this.spawnSerial = spawnSerial; this.spawnTick = spawnTick; this.spawnDefinitionId = spawnDefinitionId;
			this.spawnWitnessed = spawnWitnessed; this.spawnNearby = spawnNearby;
		}
		public static RootsCopy unknown() { return new RootsCopy(false, Truth.UNKNOWN, false, false, -1, 0, 0, 0, 0, -1, 0, -1, -1, false, false); }
	}
	/** One flat immutable additional record; current/prior copies count twice in the shared record budget. */
	public static final class WorldSnapshot
	{
		public final long tick, presenceEpoch, attentionSerial, attentionTick, rootsSpawnSerial, rootsSpawnTick;
		public final SceneKey scene;
		public final boolean baseline, visitorKnown, visitorLive, visitorSameView, firstPersonalAttention, attentionWitnessed,
			rootsIndexComplete, rootsAnchorKnown, rootsSameView, rootsSpawnWitnessed, rootsSpawnNearby;
		public final String visitorKey, visitorName, attentionKey;
		public final int visitorDefinitionId, visitorX, visitorY, visitorPlane, attentionDefinitionId,
			rootsAnchorDefinitionId, rootsMinX, rootsMinY, rootsMaxX, rootsMaxY, rootsPlane, rootsSpawnDefinitionId;
		public final Truth visitorTargetsLocal, localTargetsVisitor, rootsActive;
		public WorldSnapshot(long tick, SceneKey scene, long presenceEpoch, boolean baseline, VisitorCopy v, RootsCopy r)
		{
			this.tick = tick; this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch; this.baseline = baseline;
			visitorKnown = v.known; visitorKey = v.key; visitorDefinitionId = v.definitionId; visitorName = v.name;
			visitorX = v.x; visitorY = v.y; visitorPlane = v.plane; visitorLive = v.live; visitorSameView = v.sameView;
			visitorTargetsLocal = v.targetsLocal; localTargetsVisitor = v.localTargetsVisitor; attentionSerial = v.attentionSerial;
			attentionTick = v.attentionTick; attentionKey = v.attentionKey; attentionDefinitionId = v.attentionDefinitionId;
			firstPersonalAttention = v.firstPersonalAttention; attentionWitnessed = v.attentionWitnessed;
			rootsIndexComplete = r.indexComplete; rootsActive = r.active; rootsAnchorKnown = r.anchorKnown; rootsSameView = r.sameView;
			rootsAnchorDefinitionId = r.anchorDefinitionId; rootsMinX = r.minX; rootsMinY = r.minY; rootsMaxX = r.maxX; rootsMaxY = r.maxY; rootsPlane = r.plane;
			rootsSpawnSerial = r.spawnSerial; rootsSpawnTick = r.spawnTick; rootsSpawnDefinitionId = r.spawnDefinitionId;
			rootsSpawnWitnessed = r.spawnWitnessed; rootsSpawnNearby = r.spawnNearby;
		}
		public static WorldSnapshot unknown(long tick, SceneKey scene, long epoch)
		{ return new WorldSnapshot(tick, scene, epoch, true, VisitorCopy.unknown(), RootsCopy.unknown()); }
	}
}
