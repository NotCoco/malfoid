package com.malfoid.scenario;

import com.malfoid.additional.AdditionalNumericSnapshot;
import com.malfoid.additional.AdditionalNumericSnapshot.Watch;
import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalSceneContext.Subject;
import com.malfoid.additional.AdditionalSnapshot;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.PersonalOutcomeFact;
import com.malfoid.additional.PersonalOutcomeFact.Payload;
import com.malfoid.additional.SceneKey;
import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioRules.CombatState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/** Section 15 skill rules consume copied, source-verified facts, never game text or client objects. */
final class AdditionalSkillRules
{
	private static final int FIRST = 71, LAST = 105, COUNT = LAST - FIRST + 1;
	private static final long NUMERIC_RULES = ruleMask(92) | ruleMask(93) | ruleMask(102) | ruleMask(104) | ruleMask(105);
	private static final long[] SOURCE_RULES = sourceRuleMasks();
	private final long[] consumedSerial = new long[COUNT], consumedCause = new long[COUNT];
	private final long[] invalidatedAt = new long[COUNT];
	private final long[] sourceInvalidatedAt = new long[SourceKind.values().length];
	private ScenarioSnapshot priorFrame;
	private AdditionalSnapshot prior;
	private boolean priorUsable;
	private SceneKey epochScene;
	private long epochPresence, hardInvalidatedAt = Long.MIN_VALUE, combatInvalidatedAt = Long.MIN_VALUE, nextEdgeSerial = 1;
	private long activeRuleMask;

	AdditionalSkillRules()
	{
		Arrays.fill(invalidatedAt, Long.MIN_VALUE); Arrays.fill(sourceInvalidatedAt, Long.MIN_VALUE);
	}

	List<AdditionalSceneContext> close(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot additional, CombatState combat)
	{
		List<AdditionalSceneContext> out = new ArrayList<>();
		prepareEpoch(f);
		refreshCommonInvalidations(f, batch, additional, combat);
		long evaluations = changedRuleMask(additional) | activeRuleMask;
		if (!batch.hits.isEmpty()) { evaluations |= ruleMask(101); }
		if (priorFrame != null && f.tick == priorFrame.tick)
		{
			numeric(f, additional, combat, evaluations, false, out); return out;
		}
		boolean consecutive = continuous(f, additional);
		if (!frameKnown(f, additional) || batch.localDeath || combat == CombatState.UNKNOWN)
		{
			hardInvalidatedAt = f.tick; consume(additional, batch); remember(f, additional, false); return out;
		}
		if (!consecutive || additional.baseline)
		{
			hardInvalidatedAt = f.tick; consume(additional, batch); remember(f, additional, true); return out;
		}
		if (f.tick - f.presentSinceTick >= 10)
		{
			if (!additional.outcomes.isEmpty()) { outcomes(f, additional, combat, evaluations, out); }
			numeric(f, additional, combat, evaluations, true, out);
			if ((evaluations & ruleMask(101)) != 0) { incomingBlock(f, batch, additional, out); }
		}
		else { numeric(f, additional, combat, evaluations, false, out); }
		consume(additional, batch); remember(f, additional, true);
		return out;
	}

	void setActiveRuleIndices(Collection<Integer> indices)
	{
		long mask = 0;
		for (int id : indices) { mask |= ruleMask(id); }
		activeRuleMask = mask;
	}

	boolean hasPendingWork() { return activeRuleMask != 0; }
	long activeRuleMask() { return activeRuleMask; }
	static long ruleMask(int id) { return owned(id) ? 1L << (id - FIRST) : 0; }
	static long sourceRuleMask(SourceKind source) { return SOURCE_RULES[source.ordinal()]; }

	/** Central dispatch uses this only for commits with no changed skill source or retained skill context. */
	void advanceQuiet(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		prepareEpoch(f);
		if (priorFrame != null && f.tick == priorFrame.tick) { return; }
		boolean known = frameKnown(f, a);
		if (!known || a.baseline || !continuous(f, a)) { hardInvalidatedAt = f.tick; }
		remember(f, a, known);
	}

	private static long changedRuleMask(AdditionalSnapshot a)
	{
		long mask = 0;
		for (SourceKind source : a.changedSources) { mask |= sourceRuleMask(source); }
		return mask;
	}

	private void outcomes(ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat, long evaluations, List<AdditionalSceneContext> out)
	{
		PersonalOutcomeFact[] selected = new PersonalOutcomeFact[COUNT];
		long selectedMask = 0;
		Map<Long, PersonalOutcomeFact> serials = new HashMap<>();
		Map<Long, Integer> causalBranches = new HashMap<>();
		Set<Long> conflictingSerials = new HashSet<>();
		Set<Long> conflictingCauses = new HashSet<>();
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			int id = fact.kind.ruleIndex;
			if ((evaluations & ruleMask(id)) == 0) { continue; }
			PersonalOutcomeFact previous = serials.putIfAbsent(fact.sourceSerial, fact);
			if (previous != null && !sameFact(previous, fact)) { conflictingSerials.add(fact.sourceSerial); }
			int family = family(id), peer = peer(id);
			if (peer != id)
			{
				int branch = 1 << (family == 71 ? 0 : family == 73 ? 2 : 4);
				if (id != family) { branch <<= 1; }
				int branches = causalBranches.getOrDefault(fact.causeSerial, 0) | branch;
				causalBranches.put(fact.causeSerial, branches);
				if ((branches & 3) == 3 || (branches & 12) == 12 || (branches & 48) == 48) { conflictingCauses.add(fact.causeSerial); }
			}
			if (fact.sourceSerial <= consumedSerial[id - FIRST] || fact.causeSerial <= consumedCause[family - FIRST]
				|| !outcomeKnown(fact, f, a) || !channel(id, fact.sourceKind) || !counterTerminal(fact)) { continue; }
			if (selected[id - FIRST] == null || fact.sourceSerial < selected[id - FIRST].sourceSerial)
			{
				selected[id - FIRST] = fact;
				selectedMask |= ruleMask(id);
			}
		}
		while (selectedMask != 0)
		{
			int index = Long.numberOfTrailingZeros(selectedMask); selectedMask &= selectedMask - 1;
			PersonalOutcomeFact fact = selected[index];
			if (conflictingSerials.contains(fact.sourceSerial) || conflictingCauses.contains(fact.causeSerial)) { continue; }
			int id = fact.kind.ruleIndex;
			if (!sourceGateSupported(id) || noCombat(id) && combat != CombatState.NO_CONFIRMED_EVIDENCE) { continue; }
			long claims = id == 91 ? AdditionalSceneContext.CLAIM_SCENE_EDGE : AdditionalSceneContext.CLAIM_REPORT;
			if (id == 96 || id == 99) { claims |= AdditionalSceneContext.CLAIM_COUNTER | AdditionalSceneContext.CLAIM_COMPLETION; }
			out.add(new AdditionalSceneContext(id, fact.sourceKind, fact.sourceSerial, fact.causeSerial,
				fact.occurrenceTick, fact.originalDeadline, fact.scene, fact.presenceEpoch, subject(id), claims, fact.payload));
		}
	}

	private boolean outcomeKnown(PersonalOutcomeFact fact, ScenarioSnapshot f, AdditionalSnapshot a)
	{
		if (fact.kind.ruleIndex == 91)
		{
			return fact.sourceKind == SourceKind.GROUND_ITEM_SPAWN && fact.witnessed && fact.sourceVerified
				&& !fact.personal && !fact.localAttributionKnown && !fact.completionKnown
				&& a.sourceKnown(fact.sourceKind) && fact.scene.equals(a.scene) && fact.presenceEpoch == a.presenceEpoch
				&& fact.occurrenceTick == f.tick && fact.occurrenceTick - f.presentSinceTick >= 10
				&& fact.originalDeadline - fact.occurrenceTick == 12 && markPayloadKnown(fact.payload, f);
		}
		return fact.personal && fact.freshAt(f.tick, a.scene, a.presenceEpoch) && a.sourceKnown(fact.sourceKind)
			&& fact.occurrenceTick == f.tick && fact.occurrenceTick - f.presentSinceTick >= 10
			&& fact.originalDeadline - fact.occurrenceTick == 12;
	}

	private static boolean markPayloadKnown(Payload payload, ScenarioSnapshot f)
	{
		return payload.definitionId == ItemID.GRACE && payload.entitySerial > 0 && payload.currentLive
			&& payload.position != null && payload.position.plane == f.player.plane && f.player.distance(payload.position) <= 5
			&& payload.displayText.isEmpty() && payload.activity == ActivityKind.UNKNOWN && payload.value == -1;
	}
	private static boolean counterTerminal(PersonalOutcomeFact fact)
	{
		return fact.kind.ruleIndex != 96 && fact.kind.ruleIndex != 99 || fact.completionKnown
			&& fact.payload.secondaryValue >= 0 && fact.payload.value > fact.payload.secondaryValue;
	}

	private void numeric(ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat, long evaluations,
		boolean offer, List<AdditionalSceneContext> out)
	{
		AdditionalNumericSnapshot n = a.numeric, p = prior == null ? null : prior.numeric;
		long mask = evaluations & NUMERIC_RULES;
		while (mask != 0)
		{
			int id = FIRST + Long.numberOfTrailingZeros(mask); mask &= mask - 1;
			if (!currentCondition(id, n)) { invalidatedAt[id - FIRST] = f.tick; }
			if (!offer) { continue; }
			switch (id)
			{
				case 92:
					if (!bothKnown(a, SourceKind.NUMERIC)) { break; }
					int current = n.boosted(Skill.AGILITY), before = p.boosted(Skill.AGILITY), real = n.level(Skill.AGILITY);
					if (current > 0 && before > 0 && real > 0 && p.level(Skill.AGILITY) == real
						&& n.xp(Skill.AGILITY) >= 0 && n.xp(Skill.AGILITY) == p.xp(Skill.AGILITY) && before <= real && current > real)
					{
						edge(f, a, id, SourceKind.NUMERIC, current, before, real, out);
					}
					break;
				case 93:
					if (!bothKnown(a, SourceKind.NUMERIC)) { break; }
					int distance = f.player.distance(priorFrame.player);
					if (combat == CombatState.NO_CONFIRMED_EVIDENCE && p.energy() > 0 && n.energy() == 0 && distance > 0 && distance <= 2)
					{
						edge(f, a, id, SourceKind.NUMERIC, 0, p.energy(), -1, out);
					}
					break;
				case 102:
					if (!bothKnown(a, SourceKind.VARP)) { break; }
					int poison = n.watched(Watch.POISON), priorPoison = p.watched(Watch.POISON);
					if (priorPoison != AdditionalNumericSnapshot.UNKNOWN && priorPoison <= 0 && activePoison(poison))
					{
						edge(f, a, id, SourceKind.VARP, poison, priorPoison, Watch.POISON.id, out);
					}
					break;
				case 104:
					if (!bothKnown(a, SourceKind.NUMERIC)) { break; }
					int prayer = n.boosted(Skill.PRAYER), priorPrayer = p.boosted(Skill.PRAYER), realPrayer = n.level(Skill.PRAYER);
					if (combat == CombatState.CONFIRMED && priorPrayer > 0 && prayer == 0 && realPrayer > 0 && p.level(Skill.PRAYER) == realPrayer)
					{
						edge(f, a, id, SourceKind.NUMERIC, 0, priorPrayer, realPrayer, out);
					}
					break;
				case 105:
					if (bothKnown(a, SourceKind.VARBIT) && p.watched(Watch.ANTIFIRE) > 0 && n.watched(Watch.ANTIFIRE) == 0
						&& p.watched(Watch.SUPER_ANTIFIRE) == 0 && n.watched(Watch.SUPER_ANTIFIRE) == 0)
					{
						edge(f, a, id, SourceKind.VARBIT, 0, p.watched(Watch.ANTIFIRE), Watch.ANTIFIRE.id, out);
					}
					break;
			}
		}
	}

	private boolean bothKnown(AdditionalSnapshot a, SourceKind source) { return a.sourceKnown(source) && prior.sourceKnown(source); }

	private void edge(ScenarioSnapshot f, AdditionalSnapshot a, int id, SourceKind source, int value, int before,
		int definition, List<AdditionalSceneContext> out)
	{
		long serial = nextEdgeSerial++;
		// The two scalar endpoints and fixed definition/real level are copied; they identify no action method.
		Payload payload = new Payload(Branch.UNKNOWN, ActivityKind.UNKNOWN, "", value, before, definition, -1, -1, -1, null, false);
		out.add(new AdditionalSceneContext(id, source, serial, serial, f.tick, f.tick + 12,
			a.scene, a.presenceEpoch, subject(id), AdditionalSceneContext.CLAIM_NUMERIC, payload));
	}

	private void incomingBlock(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a, List<AdditionalSceneContext> out)
	{
		if (batch.hits.isEmpty() || !a.sourceKnown(SourceKind.HITSPLAT)) { return; }
		Hit selected = null;
		Map<Long, Hit> identities = new HashMap<>();
		Set<Long> conflicts = new HashSet<>();
		for (Hit hit : batch.hits)
		{
			Hit previous = identities.putIfAbsent(hit.id, hit);
			if (previous != null && (previous.local != hit.local || previous.type != hit.type
				|| previous.amount != hit.amount || previous.witnessed != hit.witnessed)) { conflicts.add(hit.id); }
		}
		for (Hit hit : batch.hits)
		{
			if (hit.id > consumedSerial[101 - FIRST] && hit.local && hit.witnessed && hit.amount == 0
				&& !conflicts.contains(hit.id)
				&& (hit.type == HitsplatID.BLOCK_ME || hit.type == HitsplatID.BLOCK_OTHER)
				&& (selected == null || hit.id < selected.id)) { selected = hit; }
		}
		if (selected != null)
		{
			Payload payload = new Payload(Branch.UNKNOWN, ActivityKind.UNKNOWN, "", 0, -1, selected.type, -1, -1, -1, null, false);
			out.add(new AdditionalSceneContext(101, SourceKind.HITSPLAT, selected.id, selected.id, f.tick, f.tick + 12,
				a.scene, a.presenceEpoch, Subject.unit(), AdditionalSceneContext.CLAIM_SCENE_EDGE, payload));
		}
	}

	private void consume(AdditionalSnapshot a, ScenarioBatch batch)
	{
		for (Hit hit : batch.hits) { consumedSerial[101 - FIRST] = Math.max(consumedSerial[101 - FIRST], hit.id); }
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			int id = fact.kind.ruleIndex;
			if (!owned(id)) { continue; }
			consumedSerial[id - FIRST] = Math.max(consumedSerial[id - FIRST], fact.sourceSerial);
			int family = family(id);
			consumedCause[family - FIRST] = Math.max(consumedCause[family - FIRST], fact.causeSerial);
		}
	}

	boolean stillValid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{
		return valid(c, f, a, combat, true);
	}

	boolean continuationValid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{
		return valid(c, f, a, combat, false);
	}

	private boolean valid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat, boolean deadline)
	{
		if (!owned(c.ruleIndex) || !sourceGateSupported(c.ruleIndex) || !contextSource(c.ruleIndex, c.sourceKind) || !frameKnown(f, a) || a.baseline
			|| combat == CombatState.UNKNOWN || !c.scene.equals(a.scene)
			|| c.presenceEpoch != a.presenceEpoch || c.occurrenceTick - f.presentSinceTick < 10
			|| c.occurrenceTick > f.tick || c.originalDeadline - c.occurrenceTick != 12
			|| deadline && f.tick >= c.originalDeadline || c.occurrenceTick <= hardInvalidatedAt
			|| c.occurrenceTick <= invalidatedAt[c.ruleIndex - FIRST] || !a.sourceKnown(c.sourceKind)
			|| c.occurrenceTick <= sourceInvalidatedAt[c.sourceKind.ordinal()]
			|| noCombat(c.ruleIndex) && (combat != CombatState.NO_CONFIRMED_EVIDENCE || c.occurrenceTick <= combatInvalidatedAt)) { return false; }
		return (c.ruleIndex != 91 || c.subject.type == Subject.Type.UNIT && c.permittedClaimMask == AdditionalSceneContext.CLAIM_SCENE_EDGE
			&& markPayloadKnown(c.payload, f)) && currentCondition(c.ruleIndex, a.numeric);
	}

	private void refreshCommonInvalidations(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a, CombatState combat)
	{
		if (!frameKnown(f, a) || a.baseline || batch.localDeath || combat == CombatState.UNKNOWN)
		{
			hardInvalidatedAt = f.tick;
		}
		for (SourceKind source : a.unknownSources) { sourceInvalidatedAt[source.ordinal()] = f.tick; }
		if (combat != CombatState.NO_CONFIRMED_EVIDENCE) { combatInvalidatedAt = f.tick; }
	}

	private static boolean currentCondition(int id, AdditionalNumericSnapshot n)
	{
		switch (id)
		{
			case 92: return n.xp(Skill.AGILITY) >= 0 && n.level(Skill.AGILITY) > 0 && n.boosted(Skill.AGILITY) > n.level(Skill.AGILITY);
			case 93: return n.energy() == 0;
			case 102: return activePoison(n.watched(Watch.POISON));
			case 104: return n.level(Skill.PRAYER) > 0 && n.boosted(Skill.PRAYER) == 0;
			case 105: return n.watched(Watch.ANTIFIRE) == 0 && n.watched(Watch.SUPER_ANTIFIRE) == 0;
			default: return true;
		}
	}

	private static boolean activePoison(int value) { return value >= 1 && value <= 100; }

	private boolean continuous(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		return priorUsable && priorFrame != null && prior != null && frameKnown(priorFrame, prior) && frameKnown(f, a)
			&& f.tick == priorFrame.tick + 1 && a.scene.equals(prior.scene) && a.presenceEpoch == prior.presenceEpoch
			&& f.worldViewId == priorFrame.worldViewId && f.player.plane == priorFrame.player.plane;
	}

	private static boolean frameKnown(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		return f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0
			&& f.player != null && f.worldViewId != Integer.MIN_VALUE && f.presentSinceTick >= 0 && a.known
			&& f.tick == a.tick && f.presentSinceTick == a.presenceEpoch
			&& f.sessionGeneration == a.scene.sessionGeneration && f.sceneRevision == a.scene.sceneRevision;
	}

	private void newEpoch(SceneKey key, long presence)
	{
		priorFrame = null; prior = null; priorUsable = false; epochScene = key; epochPresence = presence; hardInvalidatedAt = Long.MIN_VALUE;
		combatInvalidatedAt = Long.MIN_VALUE;
		Arrays.fill(consumedSerial, 0); Arrays.fill(consumedCause, 0);
		Arrays.fill(invalidatedAt, Long.MIN_VALUE); Arrays.fill(sourceInvalidatedAt, Long.MIN_VALUE);
	}

	private void prepareEpoch(ScenarioSnapshot f)
	{
		SceneKey key = new SceneKey(f.sessionGeneration, f.sceneRevision);
		if (!key.equals(epochScene) || f.presentSinceTick != epochPresence) { newEpoch(key, f.presentSinceTick); }
	}

	private static long[] sourceRuleMasks()
	{
		SourceKind[] sources = SourceKind.values(); long[] masks = new long[sources.length];
		for (int id = FIRST; id <= LAST; id++)
		{
			if (!sourceGateSupported(id)) { continue; }
			for (SourceKind source : sources)
			{
				if (contextSource(id, source)) { masks[source.ordinal()] |= ruleMask(id); }
			}
		}
		return masks;
	}

	void reset()
	{
		if (priorFrame != null) { hardInvalidatedAt = priorFrame.tick + 1; }
		priorFrame = null; prior = null; priorUsable = false;
	}

	// Shared prior snapshots are counted by their collector, not again for these references.
	int retainedReducerRecords() { return COUNT; }
	Set<String> retainedCauseKeys() { return Collections.emptySet(); }

	private void remember(ScenarioSnapshot f, AdditionalSnapshot a, boolean usable) { priorFrame = f; prior = a; priorUsable = usable; }
	private static boolean owned(int id) { return id >= FIRST && id <= LAST; }
	private static int family(int id) { return id == 72 ? 71 : id == 74 ? 73 : id == 76 ? 75 : id; }
	private static int peer(int id) { return id == 71 ? 72 : id == 72 ? 71 : id == 73 ? 74 : id == 74 ? 73 : id == 75 ? 76 : id == 76 ? 75 : id; }

	/** Remaining object transitions need a live selected-entity path; M91 borrows the shared pile index. */
	static boolean sourceGateSupported(int id) { return owned(id) && id != 75 && id != 76 && id != 98; }

	private static boolean noCombat(int id)
	{
		return id == 71 || id == 72 || id == 73 || id == 75 || id == 76 || id == 77 || id == 79
			|| id == 80 || id == 81 || id == 83 || id == 91 || id == 93 || id == 98 || id == 100;
	}

	private static Subject subject(int id)
	{
		switch (id)
		{
			case 71: case 72: return Subject.skill(Skill.COOKING);
			case 73: case 74: return Subject.skill(Skill.THIEVING);
			case 75: case 76: case 100: return Subject.skill(Skill.HUNTER);
			case 77: return Subject.skill(Skill.MINING);
			case 79: return Subject.skill(Skill.WOODCUTTING);
			case 81: return Subject.skill(Skill.FISHING);
			default: return Subject.unit();
		}
	}

	private static boolean channel(int id, SourceKind source)
	{
		switch (id)
		{
			case 71: case 72: case 73: case 74: case 78: case 81: case 87: case 88:
				return source == SourceKind.SPAM_MESSAGE;
			case 82: case 83: case 84: case 85: case 86: case 94: case 95: case 96: case 99:
				return source == SourceKind.GAME_MESSAGE;
			case 77: case 79: case 80: case 89: case 90: case 97: case 103:
				return source == SourceKind.SPAM_MESSAGE || source == SourceKind.GAME_MESSAGE;
			case 100:
				return source == SourceKind.SPAM_MESSAGE || source == SourceKind.GAME_MESSAGE || source == SourceKind.MESSAGE_BOX;
			case 75: case 76: case 98: return source == SourceKind.OBJECT_TRANSITION;
			case 91: return source == SourceKind.GROUND_ITEM_SPAWN;
			default: return false;
		}
	}

	private static boolean contextSource(int id, SourceKind source)
	{
		if (id == 92 || id == 93 || id == 104) { return source == SourceKind.NUMERIC; }
		if (id == 101) { return source == SourceKind.HITSPLAT; }
		if (id == 102) { return source == SourceKind.VARP; }
		if (id == 105) { return source == SourceKind.VARBIT; }
		return channel(id, source);
	}

	private static boolean sameFact(PersonalOutcomeFact a, PersonalOutcomeFact b)
	{
		Payload x = a.payload, y = b.payload;
		return a.kind == b.kind && a.sourceKind == b.sourceKind && a.causeSerial == b.causeSerial
			&& a.occurrenceTick == b.occurrenceTick && a.originalDeadline == b.originalDeadline
			&& a.presenceEpoch == b.presenceEpoch && a.scene.equals(b.scene) && a.witnessed == b.witnessed
			&& a.personal == b.personal && a.localAttributionKnown == b.localAttributionKnown
			&& a.sourceVerified == b.sourceVerified && a.fullGrammarVerified == b.fullGrammarVerified && a.completionKnown == b.completionKnown
			&& x.branch == y.branch && x.activity == y.activity && x.displayText.equals(y.displayText)
			&& x.value == y.value && x.secondaryValue == y.secondaryValue && x.definitionId == y.definitionId
			&& x.durationMillis == y.durationMillis && x.entitySerial == y.entitySerial && x.activitySerial == y.activitySerial
			&& x.currentLive == y.currentLive && Objects.equals(x.position, y.position);
	}
}
