package com.malfoid.scenario;

import com.malfoid.scenario.PersonalMilestones.Proof;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldData;
import com.malfoid.world.LivingWorldData.SceneryGroup;
import com.malfoid.world.SceneryVisits;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;

/** Production reducer, also used by source-shaped deterministic replays. */
public final class ScenarioRules
{
	public enum CombatState { CONFIRMED, NO_CONFIRMED_EVIDENCE, UNKNOWN }
	private static final Skill[] PRIMARY = {Skill.FISHING, Skill.WOODCUTTING, Skill.MINING};
	private final EnumMap<Skill, Integer> xp = new EnumMap<>(Skill.class);
	private final EnumMap<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private final EnumSet<Skill> levelSeen = EnumSet.noneOf(Skill.class);
	private final Deque<Long> chickenBlocks = new ArrayDeque<>();
	private final Deque<Boolean> chickenWeapons = new ArrayDeque<>();
	private final Deque<Long> chickenIds = new ArrayDeque<>();
	private final Deque<PositionTick> footprint = new ArrayDeque<>();
	private final EnumMap<Skill, Deque<Long>> gains = new EnumMap<>(Skill.class);
	private final Map<String, Long> lootSeen = new LinkedHashMap<>();
	private final Deque<String> inspected = new ArrayDeque<>();
	private final SceneryVisits sceneryVisits = new SceneryVisits();
	private LivingWorldData livingWorldData;
	private BooleanSupplier sceneryStartAllowed = () -> false;
	private final Deque<InspectionCompletionRecord> inspectionCompletions = new ArrayDeque<>();
	private long inspectionPlacementGeneration = -1;
	private final Deque<OutfitRecord> outfits = new ArrayDeque<>();
	private final Deque<OutfitRecord> hatChanges = new ArrayDeque<>();
	private long session = Long.MIN_VALUE, scene = Long.MIN_VALUE, nextId, deathSerial;
	private long deathTick = -1, deathToken, lastDamage = Long.MIN_VALUE / 2, lastCombat = Long.MIN_VALUE / 2;
	private long latestHeavyHitTick = Long.MIN_VALUE / 2;
	private long latestDiscontinuityTick = Long.MIN_VALUE / 2;
	private long outfitRevision, outfitStart, reunionTick = Long.MIN_VALUE / 2, arrivalSerial, arrivalToken;
	private int baselineCommits, sceneCommits, returnCommits, arrivalCommits, outfitEqual, outfitAge, previousOutfitAge;
	private boolean initialized, deathWaiting, deathStarted, deathUserHidden, wasPresent, grindHandled, chickenHandled;
	private boolean arrivalArmed, outfitWitness;
	private int[] outfit, outfitProposal;
	private int[] outfitEpisodeAnchor;
	private long outfitEpisodeStart;
	private String chickenTarget, combatTarget, objectOffered;
	private Integer region;
	private ScenarioSnapshot current, previous;
	private Position olderPlayer;
	private PaintedParent parent;
	private CombatState combat = CombatState.UNKNOWN;
	private final ExpandedScenarioRules expanded = new ExpandedScenarioRules();
	private final CombatExpansionRules combatExpanded = new CombatExpansionRules();
	private final SkillExpansionRules skillExpanded = new SkillExpansionRules();
	private final InventoryExpansionRules inventoryExpanded = new InventoryExpansionRules();
	private final EmoteRules emotes = new EmoteRules();
	private final TravelExpansionRules travelExpanded = new TravelExpansionRules();
	private final TravelContinuationRules travelContinuation = new TravelContinuationRules();
	private final LongRunRules longRun = new LongRunRules();
	private final FearRules fear = new FearRules();
	private boolean following;
	private final QuietOutfitRules quietOutfit = new QuietOutfitRules();
	private Predicate<String> scenarioEnabled = id -> true;
	public void setScenarioEnabled(Predicate<String> predicate) { scenarioEnabled = predicate; }
	public void setLivingWorldData(LivingWorldData data) { livingWorldData = data; }
	public void setSceneryStartAllowed(BooleanSupplier allowed) { sceneryStartAllowed = allowed; }

	public ScenarioRules()
	{
		for (Skill skill : PRIMARY) { gains.put(skill, new ArrayDeque<>()); }
	}

	public List<SceneCandidate> close(ScenarioSnapshot frame, ScenarioBatch batch)
	{
		return close(frame, batch, Collections.emptyList());
	}

	public List<SceneCandidate> close(ScenarioSnapshot frame, ScenarioBatch batch, List<Proof> milestones)
	{
		if (frame.sessionGeneration != session)
		{
			resetSession(); session = frame.sessionGeneration;
		}
		if (frame.sceneRevision != scene)
		{
			resetScene(); scene = frame.sceneRevision;
		}
		if (wasPresent && !frame.present) { presenceLost(); }
		if (!wasPresent && frame.present) { outfit = frame.equipment(); outfitProposal = null; outfitEqual = 0; }
		wasPresent = frame.present;
		if (batch.localDeath) { recordDeath(frame.tick, batch.deathWhileUserHidden); }
		List<SceneCandidate> result = new ArrayList<>();
		List<SkillDelta> deltas = skillDeltas(frame);
		boolean continuous = current != null && frame.tick == current.tick + 1 && frame.valid && current.valid && frame.sceneRevision == current.sceneRevision
			&& frame.player != null && current.player != null && frame.player.distance(current.player) <= 2;
		if (!continuous)
		{
			if (current != null) { latestDiscontinuityTick = frame.tick; sceneCommits = 0; }
			resetContinuity(frame.tick);
		}
		olderPlayer = previous == null ? null : previous.player;
		previous = current; current = frame;
		SceneCandidate fearCandidate = fear.close(frame, this::candidate);
		if (!frame.valid || frame.player == null)
		{
			longRun.close(frame, batch.localDeath, CombatState.UNKNOWN, following, this::candidate);
			sceneCommits = 0; returnCommits = 0; combat = CombatState.UNKNOWN;
			emotes.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate);
			updateSkills(frame); resetWitnessWindows(frame.tick); return Collections.emptyList();
		}
		sceneCommits++;
		if (!initialized)
		{
			longRun.close(frame, batch.localDeath, CombatState.UNKNOWN, following, this::candidate);
			travelExpanded.reset(frame.tick); travelContinuation.reset(frame.tick);
			emotes.close(frame, previous, batch, deltas, CombatState.UNKNOWN, lastDamage, this::candidate);
			baselineCommits++; initialized = baselineCommits >= 2;
			updateSkills(frame); outfit = frame.equipment(); outfitAge = 1;
			if (initialized && frame.present && outfit != null) { outfits.addLast(new OutfitRecord(frame.tick, outfit, false)); }
			updateRegion(frame, false, result); finishPosition(frame);
			return Collections.emptyList();
		}
		if (batch.engagementInterrupted || !sameTarget(previous, frame)) { resetEngagement(); }
		updateCombat(frame, batch);
		if (fearCandidate != null && scenarioEnabled.test(fearCandidate.getScenarioId())) { result.add(fearCandidate); }
		SceneCandidate longRunCandidate = longRun.close(frame, batch.localDeath, combat, following, this::candidate);
		if (longRunCandidate != null && scenarioEnabled.test("F03")) { result.add(longRunCandidate); }
		quietOutfit.close(frame, combat, lastDamage);
		List<SceneCandidate> emoteCandidates = emotes.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate);
		if (batch.localDeath || frame.dead || frame.currentHp <= 0)
		{
			updateSkills(frame); outfit = frame.equipment(); outfitProposal = null;
			resetWitnessWindows(frame.tick); returnCommits = 0; finishPosition(frame);
			return Collections.emptyList();
		}
		if (deathWaiting)
		{
			if (frame.tick > deathTick) { returnCommits++; }
			if (frame.tick - deathTick >= 600) { deathWaiting = false; }
			else if (returnCommits >= 2 && (frame.present || deathUserHidden || frame.userHidden))
			{
				deathWaiting = false; deathStarted = true;
				if (!deathUserHidden && frame.present)
				{
					Map<String, String> facts = regionFacts(frame); facts.put("deathToken", Long.toString(deathToken));
					result.add(candidate(frame, "M02", 110, 20, "death:" + deathToken, facts, false, 15));
				}
			}
		}
		boolean canWitness = frame.present && sceneCommits >= 2;
		if (canWitness)
		{
			if (scenarioEnabled.test("E01")) { result.addAll(emoteCandidates); }
			largeHit(frame, batch, result);
			chicken(frame, batch, result);
			levelUp(frame, batch, deltas, result);
			loot(frame, batch, result);
			grind(frame, deltas, result);
			departure(frame, result);
			inspection(frame, result);
		}
		else { resetWitnessWindows(frame.tick); }
		outfit(frame, canWitness, result);
		updateRegion(frame, canWitness, result);
		if (canWitness)
		{
			List<SceneCandidate> variants = new ArrayList<>(expanded.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			variants.addAll(combatExpanded.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			variants.addAll(skillExpanded.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			variants.addAll(inventoryExpanded.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			variants.addAll(travelExpanded.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			variants.addAll(travelContinuation.close(frame, previous, batch, deltas, combat, lastDamage, this::candidate));
			for (SceneCandidate variant : variants)
			{
				if (scenarioEnabled.test(variant.getScenarioId())) { result.add(variant); }
			}
			if (scenarioEnabled.test("M44"))
			{
				for (int i = 0; i < result.size(); i++) { result.set(i, inventoryExpanded.decorateOutfit(frame, result.get(i), this::candidate)); }
			}
			if (scenarioEnabled.test("M57"))
			{
				for (int i = 0; i < result.size(); i++) { result.set(i, travelExpanded.decorateArrival(frame, result.get(i), this::candidate)); }
			}
			if (scenarioEnabled.test("M47"))
			{
				for (int i = 0; i < result.size(); i++) { result.set(i, quietOutfit.decorate(frame, result.get(i), combat, lastDamage, this::candidate)); }
			}
			if (combat != CombatState.UNKNOWN && !frame.emergencySceneActive && frame.tick - frame.presentSinceTick >= 10)
			{
				for (Proof context : milestones)
				{
					if (scenarioEnabled.test(context.ruleId) && frame.tick < context.originalDeadline)
					{
						result.add(new SceneCandidate(++nextId, context.ruleId, 70, frame.tick, context.originalDeadline,
							frame.sessionGeneration, frame.sceneRevision, "personal-report:" + context.sourceSerial,
							regionFacts(frame), false, 4, context));
					}
				}
			}
		}
		else { expanded.reset(); combatExpanded.reset(); skillExpanded.reset(); inventoryExpanded.reset(); travelExpanded.reset(frame.tick); travelContinuation.reset(frame.tick); }
		updateSkills(frame); finishPosition(frame);
		if (canWitness && result.isEmpty()) { sceneryInspection(frame, result); }
		result.sort(Comparator.comparingInt(SceneCandidate::getPriority).reversed().thenComparingLong(SceneCandidate::getId));
		Map<String, SceneCandidate> episodes = new LinkedHashMap<>();
		for (SceneCandidate candidate : result) { episodes.putIfAbsent(candidate.getCausalToken(), candidate); }
		return Collections.unmodifiableList(new ArrayList<>(episodes.values()));
	}

	private void largeHit(ScenarioSnapshot f, ScenarioBatch b, List<SceneCandidate> out)
	{
		Hit largest = null;
		for (Hit hit : b.hits)
		{
			if (hit.local && hit.witnessed && DamageTypes.hpDamage(hit.type) && hit.amount >= DamageTypes.largeHitThreshold(f.realHp)
				&& hit.amount > 0 && (largest == null || hit.amount > largest.amount)) { largest = hit; }
		}
		if (largest != null)
		{
			latestHeavyHitTick = f.tick;
			Map<String, String> facts = regionFacts(f); facts.put("damage", Integer.toString(largest.amount));
			out.add(candidate(f, "M01", 100, 12, "hit:" + largest.id, facts, false, 20));
		}
	}

	private void updateCombat(ScenarioSnapshot f, ScenarioBatch b)
	{
		boolean knownTarget = !f.targetUnknown && (f.target == null || (f.target.key != null && f.target.name != null));
		for (Hit hit : b.hits)
		{
			if (hit.local && DamageTypes.hpDamage(hit.type) && hit.amount > 0) { lastDamage = f.tick; }
			boolean onTarget = f.target != null && f.target.key.equals(hit.npcKey);
			if ((hit.local || (hit.mine && onTarget)) && DamageTypes.combat(hit.type))
			{
				lastCombat = f.tick;
				if (onTarget) { combatTarget = hit.npcKey; }
			}
		}
		boolean targetCombat = f.target != null && f.target.alive && f.target.key.equals(combatTarget);
		combat = !knownTarget ? CombatState.UNKNOWN : (f.tick - lastCombat < 10 || targetCombat
			? CombatState.CONFIRMED : CombatState.NO_CONFIRMED_EVIDENCE);
	}

	private void chicken(ScenarioSnapshot f, ScenarioBatch b, List<SceneCandidate> out)
	{
		if (f.target == null || !f.target.alive || !"Chicken".equalsIgnoreCase(f.target.name)) { resetChicken(); return; }
		if (!f.target.key.equals(chickenTarget)) { resetChicken(); chickenTarget = f.target.key; }
		while (!chickenBlocks.isEmpty() && chickenBlocks.peekFirst() < f.tick - 29) { chickenBlocks.removeFirst(); chickenWeapons.removeFirst(); chickenIds.removeFirst(); }
		for (Hit hit : b.hits)
		{
			if (!hit.mine || !f.target.key.equals(hit.npcKey)) { continue; }
			if (DamageTypes.hpDamage(hit.type) && hit.amount > 0) { chickenBlocks.clear(); chickenWeapons.clear(); chickenIds.clear(); }
			else if (hit.witnessed && hit.type == HitsplatID.BLOCK_ME && hit.amount == 0)
			{
				if (chickenIds.contains(hit.id)) { continue; }
				chickenBlocks.addLast(f.tick);
				chickenIds.addLast(hit.id);
				chickenWeapons.addLast(f.weaponId == 4566 && previous != null && previous.weaponId == 4566);
				while (chickenBlocks.size() > 3) { chickenBlocks.removeFirst(); chickenWeapons.removeFirst(); chickenIds.removeFirst(); }
			}
		}
		if (chickenBlocks.size() == 3 && !chickenHandled)
		{
			chickenHandled = true;
			Map<String, String> facts = targetFacts(f); facts.put("blockCount", "3");
			boolean rubber = !chickenWeapons.contains(false) && scenarioEnabled.test("M50");
			out.add(candidate(f, rubber ? "M50" : "M03", 30, 10, "chicken:" + f.target.key + ":" + f.tick, facts, false, rubber ? 6 : 5));
		}
	}

	private List<SkillDelta> skillDeltas(ScenarioSnapshot f)
	{
		List<SkillDelta> result = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			Integer oldXp = xp.get(skill), oldLevel = levels.get(skill);
			if (oldXp != null && oldLevel != null && f.xp(skill) >= 0 && f.level(skill) > 0)
			{
				result.add(new SkillDelta(skill, oldXp, f.xp(skill), oldLevel, f.level(skill)));
			}
		}
		return result;
	}

	private void levelUp(ScenarioSnapshot f, ScenarioBatch b, List<SkillDelta> deltas, List<SceneCandidate> out)
	{
		List<SkillDelta> progress = new ArrayList<>();
		for (SkillDelta delta : deltas)
		{
			if (delta.newLevel > delta.oldLevel && delta.delta > 0 && b.witnessedSkills.contains(delta.skill)) { progress.add(delta); }
		}
		progress.sort(Comparator.comparing((SkillDelta d) -> d.newLevel == 99 && d.oldLevel < 99 ? 0 : 1)
			.thenComparing(Comparator.comparingLong((SkillDelta d) -> d.delta).reversed()).thenComparingInt(d -> d.skill.ordinal()));
		if (progress.isEmpty()) { return; }
		SkillDelta selected = progress.get(0);
		boolean speaks = !levelSeen.contains(selected.skill) || selected.newLevel % 10 == 0 || selected.newLevel == 99;
		for (SkillDelta d : progress) { levelSeen.add(d.skill); }
		boolean milestone = selected.newLevel == 99 && selected.oldLevel < 99;
		Map<String, String> facts = regionFacts(f);
		facts.put("skill", selected.skill.name()); facts.put("newRealLevel", Integer.toString(selected.newLevel));
		facts.put("newLevel", Integer.toString(selected.newLevel)); facts.put("priorRealLevel", Integer.toString(selected.oldLevel));
		facts.put("xpDelta", Long.toString(selected.delta));
		facts.put("witnessedPresent", "true"); facts.put("occurredTick", Long.toString(f.tick));
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		facts.put("settlingNotBeforeTick", Long.toString(milestone ? f.tick : f.presentSinceTick + 10));
		StringBuilder other = new StringBuilder();
		for (SkillDelta d : progress) { if (other.length() > 0) { other.append(','); } other.append(d.skill.name()); }
		facts.put("progressSkills", other.toString());
		String scenario = "M04";
		if (!milestone && selected.skill == Skill.COOKING && f.itemCount(7954) > 0 && previous != null && previous.itemCount(7954) > 0 && scenarioEnabled.test("M48"))
		{
			scenario = "M48"; facts.put("burntShrimpPresent", "true");
		}
		out.add(candidate(f, scenario, milestone ? 90 : 45, 10, "progress:" + f.tick, facts, !speaks, 8));
	}

	private void loot(ScenarioSnapshot f, ScenarioBatch b, List<SceneCandidate> out)
	{
		lootSeen.entrySet().removeIf(entry -> f.tick - entry.getValue() > 2);
		for (Loot report : b.loot)
		{
			List<Stack> sorted = new ArrayList<>(report.stacks);
			sorted.sort(Comparator.comparingInt((Stack s) -> s.id).thenComparingInt(s -> s.quantity));
			StringBuilder fingerprint = new StringBuilder(report.npcKey);
			for (Stack stack : sorted) { fingerprint.append('/').append(stack.id).append(':').append(stack.quantity); }
			String key = fingerprint.toString();
			if (lootSeen.containsKey(key)) { continue; }
			if (lootSeen.size() >= 64) { lootSeen.remove(lootSeen.keySet().iterator().next()); }
			lootSeen.put(key, f.tick);
			if (!report.witnessed) { continue; }
			Stack selected = null;
			for (Stack stack : sorted)
			{
				if (stack.total() >= 500_000 && (selected == null || stack.total() > selected.total())) { selected = stack; }
			}
			if (selected == null) { continue; }
			Map<String, String> facts = regionFacts(f); facts.put("itemId", Integer.toString(selected.id));
			facts.put("itemName", selected.name == null ? "" : selected.name); facts.put("quantity", Integer.toString(selected.quantity));
			facts.put("reportedValue", Long.toString(selected.total())); facts.put("npcKey", report.npcKey);
			facts.put("lootReportId", Long.toString(report.id));
			out.add(candidate(f, "M05", 80, 12, "loot:" + report.id, facts, true, 10));
		}
	}
	long groundOutcomeReportId(SceneCandidate candidate)
	{
		if (candidate == null || !"M05".equals(candidate.getScenarioId())) { return -1; }
		try
		{
			long report = Long.parseLong(candidate.getConditionFacts().get("lootReportId"));
			return report > 0 && candidate.getCausalToken().equals("loot:" + report) ? report : -1;
		}
		catch (NumberFormatException ignored) { return -1; }
	}

	private void grind(ScenarioSnapshot f, List<SkillDelta> deltas, List<SceneCandidate> out)
	{
		if (combat != CombatState.NO_CONFIRMED_EVIDENCE) { resetGrind(); return; }
		while (!footprint.isEmpty() && footprint.peekFirst().tick < f.tick - 299) { footprint.removeFirst(); }
		int minX = f.player.x, maxX = f.player.x, minY = f.player.y, maxY = f.player.y;
		for (PositionTick p : footprint)
		{
			minX = Math.min(minX, p.point.x); maxX = Math.max(maxX, p.point.x);
			minY = Math.min(minY, p.point.y); maxY = Math.max(maxY, p.point.y);
		}
		if (maxX - minX > 7 || maxY - minY > 7) { resetGrind(); }
		footprint.addLast(new PositionTick(f.tick, f.player));
		for (Skill skill : PRIMARY)
		{
			Deque<Long> ticks = gains.get(skill);
			while (!ticks.isEmpty() && ticks.peekFirst() < f.tick - 299) { ticks.removeFirst(); }
			for (SkillDelta delta : deltas) { if (delta.skill == skill && delta.delta > 0) { ticks.addLast(f.tick); break; } }
		}
		if (footprint.size() < 300 || grindHandled || !ambientReady(f)) { return; }
		Skill best = null;
		for (Skill skill : PRIMARY)
		{
			Deque<Long> ticks = gains.get(skill);
			if (ticks.size() >= 20 && f.tick - ticks.peekLast() <= 5 && (best == null || ticks.peekLast() > gains.get(best).peekLast()
				|| (ticks.peekLast().equals(gains.get(best).peekLast()) && skill.ordinal() < best.ordinal()))) { best = skill; }
		}
		if (best != null)
		{
			grindHandled = true; Map<String, String> facts = regionFacts(f); facts.put("skill", best.name());
			out.add(candidate(f, "M06", 15, 12, "grind:" + footprint.peekFirst().tick, facts, false, 9));
		}
	}

	private void departure(ScenarioSnapshot f, List<SceneCandidate> out)
	{
		if (parent != null && f.tick >= parent.expiresAt) { parent = null; }
		if (parent == null || parent.handled || f.companion == null || previous == null || previous.companion == null) { return; }
		long age = f.tick - parent.tick;
		if (age > 10) { parent = null; return; }
		int distance = f.player.distance(f.companion);
		boolean outward = !f.player.equals(previous.player) && f.player.distance(previous.companion) > previous.player.distance(previous.companion);
		if (age < 2 && distance >= 6 && outward) { parent = null; return; }
		if (age >= 2 && distance >= 6 && outward)
		{
			parent.handled = true; Map<String, String> facts = regionFacts(f);
			facts.put("parentUtteranceId", parent.id); facts.put("parentFirstPaintTick", Long.toString(parent.tick));
			facts.put("parentSpeechVisibleAtDeparture", Boolean.toString(parent.visible));
			facts.put("distance", Integer.toString(distance));
			out.add(candidate(f, "M07", 50, (int) (Math.min(parent.tick + 20, parent.expiresAt) - f.tick), "departure:" + parent.id, facts, false, 4));
		}
	}

	private void inspection(ScenarioSnapshot f, List<SceneCandidate> out)
	{
		if (sceneCommits < 15 || !ambientReady(f) || !f.directorIdle || f.inspectionSuppressed || combat != CombatState.NO_CONFIRMED_EVIDENCE) { return; }
		InspectionTarget best = null;
		for (InspectionTarget object : f.objects)
		{
			if (object.stand == null || object.pathSteps < 0 || object.pathSteps > 8 || object.distance(f.player) < 2
				|| object.distance(f.player) > 6 || objectKind(object.name) == null || inspected.contains(object.key)
				|| object.key.equals(objectOffered)) { continue; }
			if (best == null || object.pathSteps < best.pathSteps || (object.pathSteps == best.pathSteps && object.key.compareTo(best.key) < 0)) { best = object; }
		}
		if (best == null) { return; }
		objectOffered = best.key; Map<String, String> facts = regionFacts(f);
		facts.put("targetKey", best.key); facts.put("objectKind", objectKind(best.name)); facts.put("objectName", best.name);
		facts.put("targetX", Integer.toString(best.stand.x)); facts.put("targetY", Integer.toString(best.stand.y));
		facts.put("objectX", Integer.toString((best.minX + best.maxX) / 2)); facts.put("objectY", Integer.toString((best.minY + best.maxY) / 2));
		facts.put("pathSteps", Integer.toString(best.pathSteps));
		facts.put("footprintMinX", Integer.toString(best.minX)); facts.put("footprintMinY", Integer.toString(best.minY));
		facts.put("footprintMaxX", Integer.toString(best.maxX)); facts.put("footprintMaxY", Integer.toString(best.maxY));
		InspectionCompletionRecord prior = inspectionCompletions.peekLast();
		boolean categoryChange = scenarioEnabled.test("M69") && prior != null && completionCurrent(prior, f)
			&& "FOUNTAIN".equals(prior.category) && "TREE".equals(objectKind(best.name)) && !prior.key.equals(best.key);
		if (categoryChange)
		{
			facts.put("priorInspectionKey", prior.key); facts.put("priorInspectionKind", prior.category); facts.put("priorInspectionName", prior.name);
			facts.put("priorInspectionCompletedTick", Long.toString(prior.completedTick));
		}
		out.add(candidate(f, categoryChange ? "M69" : "M08", 10, 20, "interest:" + best.key + ":" + f.tick, facts, false, 9));
	}

	private void sceneryInspection(ScenarioSnapshot f, List<SceneCandidate> out)
	{
		if (sceneCommits < 15 || !ambientReady(f) || !f.directorIdle || f.inspectionSuppressed
			|| combat != CombatState.NO_CONFIRMED_EVIDENCE || !sceneryStartAllowed.getAsBoolean() || isPlayerMoving()) { return; }
		InspectionTarget best = null;
		for (InspectionTarget object : f.objects)
		{
			if (!canInspectScenery(object) || object.stand == null || object.distance(object.stand) != 1 || object.pathSteps < 0 || object.pathSteps > 8
				|| object.distance(f.player) < 2 || object.distance(f.player) > 6) { continue; }
			if (best == null || compareScenery(object, best, f.player) < 0) { best = object; }
		}
		if (best == null) { return; }
		SceneryGroup group = sceneryGroup(best.name);
		Map<String, String> facts = regionFacts(f);
		facts.put("livingWorld", "SCENERY"); facts.put("livingWorldFacts", String.join("|", group.facts));
		facts.put("scenerySessionKey", best.scenerySessionKey); facts.put("targetKey", best.key); facts.put("objectName", best.name);
		facts.put("objectDefinitionId", Integer.toString(best.resolvedDefinitionId));
		facts.put("targetX", Integer.toString(best.stand.x)); facts.put("targetY", Integer.toString(best.stand.y));
		facts.put("objectX", Integer.toString((best.minX + best.maxX) / 2)); facts.put("objectY", Integer.toString((best.minY + best.maxY) / 2));
		facts.put("pathSteps", Integer.toString(best.pathSteps)); facts.put("targetPlane", Integer.toString(best.plane));
		facts.put("targetWorldViewId", Integer.toString(f.worldViewId));
		facts.put("footprintMinX", Integer.toString(best.minX)); facts.put("footprintMinY", Integer.toString(best.minY));
		facts.put("footprintMaxX", Integer.toString(best.maxX)); facts.put("footprintMaxY", Integer.toString(best.maxY));
		out.add(candidate(f, group.id, -100, 20, "interest:" + best.key + ":" + f.tick, facts, false, 9));
	}
	private SceneryGroup sceneryGroup(String name)
	{
		return livingWorldData == null || objectKind(name) != null ? null : livingWorldData.scenery(name);
	}
	public boolean canInspectScenery(InspectionTarget object)
	{
		return sceneryGroup(object.name) != null && sceneryVisits.canVisit(object.scenerySessionKey);
	}
	public int compareScenery(InspectionTarget a, InspectionTarget b, Position player)
	{
		int kind = Integer.compare(sceneryVisits.unseenKind(sceneryGroup(a.name).id) ? 0 : 1,
			sceneryVisits.unseenKind(sceneryGroup(b.name).id) ? 0 : 1);
		if (kind != 0) { return kind; }
		int distance = Integer.compare(a.distance(player), b.distance(player));
		return distance != 0 ? distance : a.key.compareTo(b.key);
	}
	public static boolean isScenery(SceneCandidate candidate)
	{
		return candidate != null && "SCENERY".equals(candidate.getConditionFacts().get("livingWorld"));
	}

	private void outfit(ScenarioSnapshot f, boolean witness, List<SceneCandidate> out)
	{
		int[] finalOutfit = f.equipment();
		if (finalOutfit == null || finalOutfit.length != 4) { outfit = null; outfitProposal = null; outfitEqual = 0; return; }
		boolean allowed = witness && combat == CombatState.NO_CONFIRMED_EVIDENCE && f.tick - lastDamage >= 10;
		if (f.outfitBlocked)
		{
			if (!allowed) { outfit = null; outfitAge = 0; }
			outfitProposal = null; outfitEqual = 0; outfitEpisodeAnchor = null;
			outfits.clear(); hatChanges.clear(); return;
		}
		if (outfit == null || !allowed)
		{
			outfit = finalOutfit; outfitProposal = null; outfitEqual = 0; outfitAge = 1; outfitEpisodeAnchor = null; return;
		}
		if (finalOutfit[0] != outfit[0]) { hatChanges.clear(); }
		if (Arrays.equals(outfit, finalOutfit))
		{
			outfitAge = Math.min(15, outfitAge + 1); outfitProposal = null; outfitEqual = 0;
			if (outfits.isEmpty() && outfitAge >= 2) { outfits.addLast(new OutfitRecord(f.tick, outfit, false)); }
			return;
		}
		if (!Arrays.equals(outfitProposal, finalOutfit))
		{
			if (outfitProposal == null)
			{
				outfitStart = f.tick; previousOutfitAge = outfitAge;
				if (outfitEpisodeAnchor == null || f.tick - outfitEpisodeStart > 8) { outfitEpisodeAnchor = outfit.clone(); outfitEpisodeStart = f.tick; }
			}
			outfitProposal = finalOutfit; outfitEqual = 1; outfitWitness = f.present; return;
		}
		outfitEqual++;
		if (outfitEqual < 2) { return; }
		List<Integer> changed = new ArrayList<>();
		for (int i = 0; i < 4; i++) { if (finalOutfit[i] >= 0 && finalOutfit[i] != outfit[i]) { changed.add(i); } }
		int[] old = outfit; outfit = finalOutfit; outfitProposal = null; outfitEqual = 0; outfitAge = 1; outfitRevision++;
		String scenario = "M09"; int priority = 25;
		boolean fullChange = outfitEpisodeAnchor != null && f.tick - outfitEpisodeStart <= 8;
		if (fullChange) { for (int i = 0; i < 4; i++) { if (finalOutfit[i] < 0 || finalOutfit[i] == outfitEpisodeAnchor[i]) { fullChange = false; } } }
		if (fullChange) { scenario = "M34"; priority = 28; }
		else if (changed.size() == 1 && changed.contains(1) && previousOutfitAge >= 15
			&& finalOutfit[0] == old[0] && finalOutfit[2] == old[2] && finalOutfit[3] == old[3]) { scenario = "M31"; }
		if (outfits.size() >= 2)
		{
			OutfitRecord a = outfits.peekFirst();
			if (priority < 27 && f.tick - a.tick <= 60 && Arrays.equals(a.items, finalOutfit) && outfits.peekLast().realChange && !changed.isEmpty()) { scenario = "M33"; priority = 27; }
		}
		outfits.addLast(new OutfitRecord(f.tick, finalOutfit, !changed.isEmpty()));
		while (outfits.size() > 2) { outfits.removeFirst(); }
		if (finalOutfit[0] >= 0 && finalOutfit[0] == old[0] && (changed.contains(2) || changed.contains(3)))
		{
			while (!hatChanges.isEmpty() && (f.tick - hatChanges.peekFirst().tick > 119 || hatChanges.peekFirst().items[0] != finalOutfit[0])) { hatChanges.removeFirst(); }
			boolean duplicate = false;
			for (OutfitRecord record : hatChanges) { if (record.items[2] == finalOutfit[2] && record.items[3] == finalOutfit[3]) { duplicate = true; } }
			if (!duplicate) { hatChanges.addLast(new OutfitRecord(f.tick, finalOutfit, true)); }
			if (hatChanges.size() >= 3) { if (priority < 26) { scenario = "M32"; priority = 26; } hatChanges.clear(); }
		}
		else if (finalOutfit[0] != old[0]) { hatChanges.clear(); }
		if (changed.isEmpty() || !outfitWitness) { return; }
		if (!scenarioEnabled.test(scenario)) { scenario = "M09"; priority = 25; }
		Map<String, String> facts = regionFacts(f); facts.put("equipmentIds", outfitKey(finalOutfit));
		facts.put("priorEquipmentIds", outfitKey(old));
		facts.put("outfitRevision", Long.toString(outfitRevision)); facts.put("capeChanged", Boolean.toString(changed.contains(1)));
		String[] slotNames = {"HEAD", "CAPE", "BODY", "LEGS"};
		StringBuilder slots = new StringBuilder();
		for (int slot : changed) { if (slots.length() > 0) { slots.append(','); } slots.append(slotNames[slot]); }
		facts.put("changedSlots", slots.toString());
		if (changed.size() == 1) { facts.put("changedSlot", slotNames[changed.get(0)]); }
		out.add(candidate(f, scenario, priority, 12, "outfit:" + outfitRevision, facts, false, 6));
	}

	private void updateRegion(ScenarioSnapshot f, boolean witness, List<SceneCandidate> out)
	{
		if (f.player == null || !f.valid || !f.topLevel || f.instanced) { arrivalCommits = 0; return; }
		int now = f.player.region();
		if (region != null && region != 12850 && now == 12850 && f.player.plane == 0 && !f.userHidden && !f.autoAbsent)
		{
			arrivalArmed = true; arrivalCommits = 0; arrivalToken = ++arrivalSerial;
		}
		region = now;
		if (now != 12850 || f.player.plane != 0) { arrivalArmed = false; arrivalCommits = 0; return; }
		if (!arrivalArmed) { return; }
		arrivalCommits++;
		if (arrivalCommits >= 4)
		{
			arrivalArmed = false;
			if (witness)
			{
				Map<String, String> facts = regionFacts(f); facts.put("arrivalToken", Long.toString(arrivalToken));
				if (f.tick - reunionTick <= 100) { facts.put("recentReunionWithinTicks", "100"); }
				out.add(candidate(f, "M10", 20, 20, "arrival:" + arrivalToken, facts, false, 6));
			}
		}
	}

	private void updateSkills(ScenarioSnapshot f)
	{
		for (Skill skill : Skill.values())
		{
			if (f.xp(skill) >= 0) { xp.put(skill, f.xp(skill)); }
			if (f.level(skill) > 0) { levels.put(skill, f.level(skill)); }
		}
	}
	private void finishPosition(ScenarioSnapshot f) { }
	private static boolean sameTarget(ScenarioSnapshot a, ScenarioSnapshot b)
	{
		return a != null && a.target != null && b.target != null && a.target.key.equals(b.target.key) && b.target.alive
			&& a.target.name != null && a.target.name.equals(b.target.name);
	}
	private static boolean ambientReady(ScenarioSnapshot f) { return f.tick - f.presentSinceTick >= 10; }
	private static String outfitKey(int[] items) { return items[0] + "," + items[1] + "," + items[2] + "," + items[3]; }
	public static String objectKind(String name)
	{
		if ("Fountain".equals(name)) { return "FOUNTAIN"; }
		return "Tree".equals(name) || "Oak tree".equals(name) || "Willow tree".equals(name) || "Yew tree".equals(name) ? "TREE" : null;
	}
	private Map<String, String> regionFacts(ScenarioSnapshot f)
	{
		Map<String, String> result = new HashMap<>();
		if (f.player != null) { result.put("actualRegion", Integer.toString(f.player.region())); }
		return result;
	}
	private Map<String, String> targetFacts(ScenarioSnapshot f)
	{
		Map<String, String> facts = regionFacts(f); facts.put("targetKey", f.target.key); facts.put("npcResolvedName", f.target.name);
		if (f.target.position != null) { facts.put("targetX", Integer.toString(f.target.position.x)); facts.put("targetY", Integer.toString(f.target.position.y)); }
		return facts;
	}
	SceneCandidate candidate(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, boolean silent, int hold)
	{
		facts.putIfAbsent("witnessedPresent", Boolean.toString(f.present)); facts.putIfAbsent("occurredTick", Long.toString(f.tick));
		facts.putIfAbsent("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		if (priority < 50) { facts.putIfAbsent("settlingNotBeforeTick", Long.toString(f.presentSinceTick + 10)); }
		return new SceneCandidate(++nextId, id, priority, f.tick, f.tick + life, f.sessionGeneration, f.sceneRevision, cause, facts, silent, hold);
	}

	/** Only genuine current first-paint acknowledgement may call this. */
	public void firstPaint(String utteranceId, String scenarioId, long tick, long sessionGeneration, long sceneRevision)
	{
		firstPaint(utteranceId, scenarioId, tick, sessionGeneration, sceneRevision, Long.MAX_VALUE);
	}
	public void firstPaint(String utteranceId, String scenarioId, long tick, long sessionGeneration, long sceneRevision, long expiresAt)
	{
		if (current == null || sessionGeneration != session || sceneRevision != scene || tick != current.tick) { return; }
		if (parent != null && tick >= parent.expiresAt) { parent = null; }
		if (parent != null && parent.id.equals(utteranceId)) { return; }
		parent = null;
		if ("M09".equals(scenarioId) || "M31".equals(scenarioId) || "M32".equals(scenarioId) || "M33".equals(scenarioId) || "M34".equals(scenarioId) || "M44".equals(scenarioId)) { outfitEpisodeAnchor = null; }
		if (tick >= expiresAt || !current.present || current.companion == null
			|| current.player == null || previous == null || olderPlayer == null || !current.player.equals(previous.player)
			|| !previous.player.equals(olderPlayer) || current.player.distance(current.companion) > 3
			|| "M01".equals(scenarioId) || "M02".equals(scenarioId) || "M07".equals(scenarioId)) { return; }
		parent = new PaintedParent(utteranceId, tick, expiresAt);
	}
	public void presentationVisible(String utteranceId, boolean visible)
	{
		if (parent != null && parent.id.equals(utteranceId)) { parent.visible = visible; }
	}
	public void softRevokeParent() { parent = null; }
	public void reunionCompleted(long tick) { reunionTick = tick; deathStarted = false; }
	public boolean emoteArrived(SceneCandidate candidate)
	{
		return stillValid(candidate) && emotes.arrived(candidate, current, combat, lastDamage);
	}
	public void inspectionCompleted(String targetKey)
	{
		inspected.remove(targetKey); inspected.addLast(targetKey); while (inspected.size() > 2) { inspected.removeFirst(); }
		objectOffered = null;
	}
	/** Only the director's genuine matching arrival plus full HOLD completion calls this overload. */
	public void inspectionCompleted(SceneCandidate candidate)
	{
		if (!("M08".equals(candidate.getScenarioId()) || "M69".equals(candidate.getScenarioId())) || !continuationValid(candidate)) { return; }
		for (InspectionTarget object : current.objects)
		{
			if (object.key.equals(candidate.getConditionFacts().get("targetKey")) && object.name.equals(candidate.getConditionFacts().get("objectName")))
			{
				inspectionCompleted(object.key);
				inspectionCompletions.removeIf(record -> !completionCurrent(record, current) || record.key.equals(object.key));
				inspectionCompletions.addLast(new InspectionCompletionRecord(object, current));
				if (current.placementGeneration >= 0) { inspectionPlacementGeneration = current.placementGeneration; }
				while (inspectionCompletions.size() > 2) { inspectionCompletions.removeFirst(); }
				return;
			}
		}
	}
	/** Called before the arrival speech draw, from the existing actor's actual M08 arrival only. */
	public boolean sceneryArrived(SceneCandidate candidate)
	{
		if (!isScenery(candidate) || !continuationValid(candidate)) { return false; }
		for (InspectionTarget object : current.objects)
		{
			if (!sceneryTargetMatches(candidate, object) || object.stand == null || !object.stand.equals(current.companion)) { continue; }
			if (object.pathSteps > 0)
			{
				MotionObservation arrival = current.motion;
				if (arrival == null || !arrival.known || arrival.status != MotionObservation.Status.ARRIVED
					|| arrival.purpose != MotionObservation.Purpose.OTHER || !arrival.legalGoal || !arrival.fixedGoal
					|| arrival.sourceCandidateId != candidate.getId() || arrival.placementGeneration != current.placementGeneration
					|| arrival.worldViewId != current.worldViewId || arrival.plane != object.plane
					|| arrival.goalWorldX != object.stand.x || arrival.goalWorldY != object.stand.y
					|| arrival.completedEdges <= 0 || arrival.completedEdges > 8 || arrival.completedRunEdges != 0
					|| arrival.completedTick < candidate.getCreatedTick() || arrival.completedTick > current.tick) { return false; }
			}
			return sceneryVisits.arrived(object.scenerySessionKey, candidate.getScenarioId());
		}
		return false;
	}
	private boolean sceneryTargetMatches(SceneCandidate candidate, InspectionTarget object)
	{
		Map<String, String> facts = candidate.getConditionFacts(); SceneryGroup group = sceneryGroup(object.name);
		return group != null && group.id.equals(candidate.getScenarioId()) && object.key.equals(facts.get("targetKey"))
			&& object.name.equals(facts.get("objectName")) && object.scenerySessionKey != null
			&& object.stand != null && object.distance(object.stand) == 1 && object.pathSteps >= 0 && object.pathSteps <= 8
			&& Integer.toString(object.stand.x).equals(facts.get("targetX")) && Integer.toString(object.stand.y).equals(facts.get("targetY"))
			&& object.scenerySessionKey.equals(facts.get("scenerySessionKey"))
			&& Integer.toString(object.resolvedDefinitionId).equals(facts.get("objectDefinitionId"))
			&& Integer.toString(object.plane).equals(facts.get("targetPlane"))
			&& Integer.toString(current.worldViewId).equals(facts.get("targetWorldViewId"))
			&& Integer.toString(object.minX).equals(facts.get("footprintMinX")) && Integer.toString(object.minY).equals(facts.get("footprintMinY"))
			&& Integer.toString(object.maxX).equals(facts.get("footprintMaxX")) && Integer.toString(object.maxY).equals(facts.get("footprintMaxY"));
	}
	private static boolean completionCurrent(InspectionCompletionRecord record, ScenarioSnapshot frame)
	{
		return record.sessionGeneration == frame.sessionGeneration && record.sceneRevision == frame.sceneRevision
			&& record.presentSinceTick == frame.presentSinceTick && record.completedTick >= frame.presentSinceTick
			&& frame.tick >= record.completedTick && frame.tick - record.completedTick <= 180;
	}
	public boolean canInspectIdentity(String key) { return !inspected.contains(key); }
	public CombatState getCombatState() { return combat; }
	public boolean isPlayerMoving()
	{
		if (current == null || previous == null || !current.valid || !previous.valid || current.player == null || previous.player == null
			|| current.tick != previous.tick + 1 || current.sessionGeneration != previous.sessionGeneration || current.sceneRevision != previous.sceneRevision) { return false; }
		int displacement = current.player.distance(previous.player); return displacement >= 1 && displacement <= 2;
	}
	public long getLastLocalDamageTick() { return lastDamage; }
	public long getLatestHeavyHitTick() { return latestHeavyHitTick; }
	public long getLatestDiscontinuityTick() { return latestDiscontinuityTick; }
	public boolean hasPendingDeath() { return deathWaiting; }
	public long getDeathToken() { return deathToken; }
	public ScenarioSnapshot getCurrent() { return current; }
	public void onSpeechCancellation(SpeechCancellationReceipt receipt) { quietOutfit.onCancellation(receipt); }
	public void executionPainted(SpeechPacket.PaintReceipt receipt) { quietOutfit.onFirstPaint(receipt); longRun.onFirstPaint(receipt); }
	public void setFollowing(boolean following) { this.following = following; }
	public void setCreatureObservation(com.malfoid.motion.CreatureAwareness.Sample sample,
		com.malfoid.motion.CreatureAwareness awareness) { fear.observe(sample, awareness); }
	public void setActiveSceneMotion(long candidateId, MotionHoldReceipt hold)
	{
		setActiveSceneMotion(candidateId, hold, false);
	}
	public void setActiveSceneMotion(long candidateId, MotionHoldReceipt hold, boolean committed)
	{
		if (current != null) { current = current.withActiveSceneMotion(candidateId, hold, committed); }
	}
	public void setCancelledInterest(CancelledInterestObservation interest)
	{
		if (current != null) { current = current.withCancelledInterest(interest); }
	}
	public void setMotionObservations(com.malfoid.motion.MotionObservation motion, com.malfoid.motion.MotionObservation completion, long placement)
	{
		if (placement >= 0)
		{
			if (inspectionPlacementGeneration >= 0 && inspectionPlacementGeneration != placement)
			{
				inspectionCompletions.clear(); objectOffered = null;
			}
			inspectionPlacementGeneration = placement;
		}
		if (current != null) { current = current.withMotionObservations(motion, completion, placement); }
	}
	public long motionRequestId(SceneCandidate candidate)
	{
		return candidate.getScenarioId().matches("M6[1-3]") ? travelContinuation.motionRequestId(candidate) : travelExpanded.motionRequestId(candidate);
	}

	public boolean stillValid(SceneCandidate candidate)
	{
		if (current == null || current.tick >= candidate.getExpiresTick() || !continuationValid(candidate)) { return false; }
		if ("F03".equals(candidate.getScenarioId())) { return longRun.stillValid(candidate, current, combat, following); }
		return true;
	}

	/** Committed presentation and physical holds ignore only their admission deadline. */
	public boolean continuationValid(SceneCandidate candidate)
	{
		ScenarioSnapshot f = current;
		if (f == null || !f.valid || !f.present || f.dead || f.currentHp <= 0 || candidate.getSessionGeneration() != f.sessionGeneration
			|| candidate.getSceneRevision() != f.sceneRevision) { return false; }
		if (candidate.getCreatedTick() < latestDiscontinuityTick && !validLevelRebind(candidate, f)) { return false; }
		Map<String, String> facts = candidate.getConditionFacts(); String id = candidate.getScenarioId();
		Proof context = candidate.getMilestone();
		if (context != null)
		{
			return (context.ruleIndex == 142 || context.ruleIndex == 147)
				&& context.sessionGeneration == f.sessionGeneration && context.sceneRevision == f.sceneRevision
				&& context.presenceEpoch == f.presentSinceTick;
		}
		if (id.matches("M(7[1-9]|[89][0-9]|1[0-6][0-9]|170)")) { return false; }
		if ("F03".equals(id)) { return longRun.continuationValid(candidate, f, combat, following); }
		if (com.malfoid.motion.CreatureAwareness.isE5(id)) { return fear.continuationValid(candidate, f); }
		if ("E01".equals(id)) { return emotes.continuationValid(candidate, f, combat, lastDamage); }
		if ("M47".equals(id)) { return quietOutfit.continuationValid(candidate, f, combat, lastDamage); }
		if ("M03".equals(id) || "M50".equals(id))
		{
			return f.target != null && f.target.alive && f.target.key.equals(facts.get("targetKey")) && "Chicken".equalsIgnoreCase(f.target.name)
				&& (!"M50".equals(id) || f.weaponId == 4566);
		}
		if ("M08".equals(id) || "M69".equals(id) || isScenery(candidate))
		{
			if (f.player.distance(f.companion) >= 10 || f.inspectionSuppressed || combat != CombatState.NO_CONFIRMED_EVIDENCE) { return false; }
			if (isScenery(candidate) && isPlayerMoving()) { return false; }
			if ("M69".equals(id))
			{
				boolean priorExists = false;
				for (InspectionCompletionRecord record : inspectionCompletions)
				{
					if (completionCurrent(record, f) && record.key.equals(facts.get("priorInspectionKey")) && "FOUNTAIN".equals(record.category)
						&& Long.toString(record.completedTick).equals(facts.get("priorInspectionCompletedTick"))) { priorExists = true; }
				}
				if (!priorExists) { return false; }
			}
			for (InspectionTarget object : f.objects)
			{
				if (isScenery(candidate) ? sceneryTargetMatches(candidate, object)
					: object.key.equals(facts.get("targetKey")) && facts.get("objectName").equals(object.name)) { return true; }
			}
			return false;
		}
		if ("M09".equals(id) || "M31".equals(id) || "M32".equals(id) || "M33".equals(id) || "M34".equals(id) || "M44".equals(id))
		{
			int[] items = f.equipment();
			return items != null && !f.outfitBlocked && facts.get("equipmentIds").equals(outfitKey(items)) && combat == CombatState.NO_CONFIRMED_EVIDENCE && f.tick - lastDamage >= 10
				&& (!"M44".equals(id) || inventoryExpanded.continuationValid(candidate, f));
		}
		if ("M10".equals(id)) { return f.player.plane == 0 && f.player.region() == 12850 && f.topLevel && !f.instanced; }
		if ("M07".equals(id)) { return parent != null && f.tick < parent.expiresAt && parent.handled && parent.id.equals(facts.get("parentUtteranceId")); }
		if ("M48".equals(id)) { return f.itemCount(7954) > 0; }
		if (id.matches("M(14|15|17|18|19|20)")) { return combatExpanded.stillValid(candidate, f, combat, lastDamage); }
		if (id.matches("M(22|24|25|27|30)")) { return skillExpanded.continuationValid(candidate, f, combat, lastDamage); }
		if (id.matches("M(39|41|43|45|46|49)")) { return inventoryExpanded.continuationValid(candidate, f); }
		if (id.matches("M(5[1-9]|60|70)")) { return travelExpanded.continuationValid(candidate, f, combat, lastDamage); }
		if (id.matches("M6[1-3]")) { return travelContinuation.continuationValid(candidate, f, combat, lastDamage); }
		return expanded.stillValid(candidate, f);
	}
	private boolean validLevelRebind(SceneCandidate candidate, ScenarioSnapshot f)
	{
		Map<String, String> facts = candidate.getConditionFacts();
		return "M04".equals(candidate.getScenarioId()) && candidate.getRebindCount() == 1 && sceneCommits >= 2
			&& latestDiscontinuityTick <= f.presentSinceTick && "true".equals(facts.get("witnessedPresent"))
			&& Long.toString(candidate.getCreatedTick()).equals(facts.get("occurredTick")) && facts.containsKey("witnessPresenceEpoch")
			&& facts.containsKey("settlingNotBeforeTick");
	}

	public void recordDeath(long tick, boolean userHidden)
	{
		if (!deathWaiting || deathStarted)
		{
			deathToken = ++deathSerial; deathTick = tick; deathUserHidden = userHidden;
			deathWaiting = true; deathStarted = false; returnCommits = 0;
		}
		resetWitnessWindows();
	}
	public void resetScene()
	{
		longRun.resetScene();
		fear.reset();
		sceneCommits = 0; returnCommits = 0; outfit = null; outfitProposal = null; outfitEpisodeAnchor = null; outfitEqual = 0; outfitAge = 0;
		arrivalArmed = false; arrivalCommits = 0;
		latestDiscontinuityTick = Long.MIN_VALUE / 2;
		current = null; previous = null; olderPlayer = null; combatTarget = null; combat = CombatState.UNKNOWN;
		resetWitnessWindows(); inspected.clear(); outfits.clear(); hatChanges.clear(); objectOffered = null;
	}
	public void resetSession()
	{
		resetScene(); xp.clear(); levels.clear(); levelSeen.clear(); lootSeen.clear();
		longRun.resetSession();
		sceneryVisits.resetSession();
		emotes.resetSession();
		baselineCommits = 0; initialized = false; deathWaiting = false; deathStarted = false; deathTick = -1;
		region = null; arrivalArmed = false; arrivalCommits = 0; reunionTick = Long.MIN_VALUE / 2;
		lastDamage = Long.MIN_VALUE / 2; lastCombat = Long.MIN_VALUE / 2; latestHeavyHitTick = Long.MIN_VALUE / 2; wasPresent = false;
	}
	public void presenceLost()
	{
		longRun.resetScene();
		resetWitnessWindows(); outfit = null; outfitProposal = null; outfitEpisodeAnchor = null; outfitEqual = 0; outfitAge = 0;
		arrivalArmed = false; arrivalCommits = 0; wasPresent = false;
		current = null; previous = null; olderPlayer = null;
	}
	public void observeAbsentStat(Skill skill, int observedXp, int realLevel)
	{
		if (observedXp >= 0) { xp.put(skill, observedXp); }
		if (realLevel > 0) { levels.put(skill, realLevel); }
	}
	private void resetContinuity(long invalidThroughTick) { resetWitnessWindows(invalidThroughTick); outfitProposal = null; outfitEpisodeAnchor = null; outfitEqual = 0; }
	private void resetEngagement() { resetChicken(); combatTarget = null; expanded.resetEngagement(); }
	private void resetChicken() { chickenBlocks.clear(); chickenWeapons.clear(); chickenIds.clear(); chickenHandled = false; chickenTarget = null; }
	private void resetGrind() { footprint.clear(); for (Deque<Long> g : gains.values()) { g.clear(); } grindHandled = false; }
	private void resetWitnessWindows()
	{
		resetWitnessWindows(Long.MIN_VALUE);
	}
	private void resetWitnessWindows(long invalidThroughTick)
	{
		resetChicken(); resetGrind(); parent = null; expanded.reset(); combatExpanded.reset(); skillExpanded.reset(); inventoryExpanded.reset();
		if (invalidThroughTick == Long.MIN_VALUE) { travelExpanded.reset(); travelContinuation.reset(); }
		else { travelExpanded.reset(invalidThroughTick); travelContinuation.reset(invalidThroughTick); }
		emotes.reset(); quietOutfit.reset();
		outfits.clear(); hatChanges.clear(); objectOffered = null; inspectionCompletions.clear(); inspectionPlacementGeneration = -1;
	}
	public static final class SkillDelta
	{
		public final Skill skill;
		public final int oldXp, newXp, oldLevel, newLevel;
		public final long delta;
		SkillDelta(Skill skill, int oldXp, int newXp, int oldLevel, int newLevel)
		{
			this.skill = skill; this.oldXp = oldXp; this.newXp = newXp; this.oldLevel = oldLevel; this.newLevel = newLevel; delta = (long) newXp - oldXp;
		}
	}
	private static final class PositionTick
	{
		final long tick; final Position point;
		PositionTick(long tick, Position point) { this.tick = tick; this.point = point; }
	}
	private static final class PaintedParent
	{
		final String id; final long tick, expiresAt; boolean visible = true, handled;
		PaintedParent(String id, long tick, long expiresAt) { this.id = id; this.tick = tick; this.expiresAt = expiresAt; }
	}
	private static final class OutfitRecord
	{
		final long tick; final int[] items; final boolean realChange;
		OutfitRecord(long tick, int[] items, boolean realChange) { this.tick = tick; this.items = items.clone(); this.realChange = realChange; }
	}
}
