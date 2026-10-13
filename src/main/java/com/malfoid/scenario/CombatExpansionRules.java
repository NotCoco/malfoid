package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.NpcDeath;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/** Scene-local combat histories contain copied facts, never actors or inferred kill credit. */
final class CombatExpansionRules
{
	private static final Skill[] FIELDS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC};
	private final Deque<Incoming> incoming = new ArrayDeque<>();
	private final Deque<DeathRecord> deaths = new ArrayDeque<>();
	private final EnumMap<Skill, FieldGain> fields = new EnumMap<>(Skill.class);
	private Engagement engagement;
	private ReturnSequence returning;
	private QuietArm quiet, issuedQuiet;
	private long continuityEpoch, engagementEpoch, activityEpoch, hpEpoch, levelEpoch, evidenceEpoch, sequenceDataEpoch, quietEpoch;
	private long lastHitId = -1, lastDeathId = -1, overflowUntil = -1;
	private long lastTick = -1, session, scene, presence;
	private ScenarioSnapshot last;
	private boolean fieldsHandled;

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, List<SkillDelta> deltas,
		CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		if (!complete(f) || batch.localDeath || batch.targetChangesUnknown || combat == CombatState.UNKNOWN)
		{
			reset(); return out;
		}
		if (last != null && (f.tick != lastTick + 1 || f.sessionGeneration != session || f.sceneRevision != scene
			|| f.presentSinceTick != presence || f.player.distance(last.player) > 2)) { reset(); }
		last = f; lastTick = f.tick; session = f.sessionGeneration; scene = f.sceneRevision; presence = f.presentSinceTick;
		List<Hit> hits = freshHits(batch.hits);
		List<NpcDeath> reportedDeaths = freshDeaths(batch.npcDeaths);
		boolean localPositive = false, freshEvidence = false, heavy = false;
		for (Hit hit : hits)
		{
			if (hit.local && hit.amount > 0) { localPositive = true; }
			if ((hit.local || hit.mine && hit.npcKey != null) && DamageTypes.combat(hit.type)) { freshEvidence = true; }
			if (hit.local && hit.amount > 0 && DamageTypes.hpDamage(hit.type)
				&& hit.amount >= DamageTypes.largeHitThreshold(f.realHp)) { heavy = true; }
		}
		if (freshEvidence || localPositive) { evidenceEpoch++; }
		if (localPositive || prior != null && prior.currentHp != f.currentHp) { hpEpoch++; }
		boolean selectedLevel = false, changedCombatLevel = false, knownFields = prior != null;
		for (SkillDelta delta : deltas)
		{
			if (delta.delta > 0 && delta.newLevel > delta.oldLevel && batch.witnessedSkills.contains(delta.skill)) { selectedLevel = true; }
			if (supported(delta.skill) && delta.newLevel != delta.oldLevel) { changedCombatLevel = true; }
		}
		for (Skill skill : FIELDS)
		{
			knownFields &= prior != null && prior.xp(skill) >= 0 && prior.level(skill) > 0 && f.xp(skill) >= 0 && f.level(skill) > 0;
		}
		if (selectedLevel || changedCombatLevel || !knownFields) { levelEpoch++; fields.clear(); }
		boolean emergency = f.emergencySceneActive || heavy;
		if (engagement != null)
		{
			for (Hit hit : hits)
			{
				if (hit.local && hit.witnessed && hit.amount > 0 && DamageTypes.hpDamage(hit.type)) { engagement.injuryId = hit.id; }
			}
		}
		boolean same = sameLiveTarget(prior, f) && batch.localTargetChanges.isEmpty() && !batch.engagementInterrupted;
		boolean directSwitch = prior != null && live(prior) && live(f) && !prior.target.key.equals(f.target.key)
			&& batch.localTargetChanges.size() == 1 && f.target.key.equals(batch.localTargetChanges.get(0));
		if (!same) { engagementEpoch++; incoming.clear(); overflowUntil = -1; fields.clear(); fieldsHandled = false; }

		// A death must use the previous commit's owned evidence, before considering this batch's hits.
		NpcDeath terminal = null;
		if (prior != null && live(prior) && engagement != null && engagement.key.equals(prior.target.key))
		{
			for (NpcDeath death : reportedDeaths)
			{
				if (death.witnessed && engagement.key.equals(death.npcKey)) { terminal = death; break; }
			}
		}
		boolean newConfirmedTarget = live(f) && combat == CombatState.CONFIRMED
			&& (terminal == null || !f.target.key.equals(terminal.npcKey)) && !same;
		if (newConfirmedTarget) { activityEpoch++; }
		boolean freshInteraction = batch.engagementInterrupted || !batch.localTargetChanges.isEmpty();
		if (issuedQuiet != null && (live(f) || f.target != null
			|| combat != CombatState.NO_CONFIRMED_EVIDENCE || freshEvidence || localPositive || freshInteraction || !deficit(f, issuedQuiet)))
		{
			issuedQuiet = null; quietEpoch++;
		}
		if (terminal != null)
		{
			observedDeaths(f, prior, terminal, emergency, newConfirmedTarget, factory, out);
			equalEndpoints(f, terminal, localPositive, emergency, newConfirmedTarget || !terminalTransition(batch, terminal.npcKey), factory, out);
		}

		if (quiet != null) { quietGap(f, combat, lastDamage, freshEvidence || localPositive || freshInteraction, emergency, factory, out); }
		if (engagement != null && f.target == null && prior != null && prior.target != null
			&& engagement.key.equals(prior.target.key) && !emergency && nullTransition(batch))
		{
			if (engagement.span >= 30 && engagement.span <= 120 && engagement.hitTicks.size() >= 6 && !engagement.realHpChanged
				&& engagement.realHp == f.realHp && f.currentHp > 0 && engagement.startHp - f.currentHp >= quarter(f.realHp))
			{
				quietEpoch++;
				quiet = new QuietArm(engagement, f);
			}
		}
		if (!same || terminal != null)
		{
			engagement = null;
		}
		else if (engagement != null)
		{
			engagement.span = Math.min(121, engagement.span + 1);
			if (engagement.realHp != f.realHp) { engagement.realHpChanged = true; }
			if (engagement.realHp != f.realHp || localPositive || engagement.span > 120) { engagement.endpointInvalid = true; }
			engagement.name = f.target.name;
		}

		Hit owned = null;
		if (live(f) && (terminal == null || !f.target.key.equals(terminal.npcKey)))
		{
			for (Hit hit : hits)
			{
				if (ownedPositive(hit, f) && (owned == null || hit.id < owned.id)) { owned = hit; }
			}
			if (owned != null)
			{
				if (engagement == null)
				{
					engagement = new Engagement(f, owned);
					if (!newConfirmedTarget) { activityEpoch++; }
				}
				engagement.record(f.tick);
			}
			if (engagement != null)
			{
				if (localPositive) { engagement.endpointInvalid = true; }
				for (Hit hit : hits)
				{
					if (hit.local && hit.witnessed && hit.amount > 0 && DamageTypes.hpDamage(hit.type)) { engagement.injuryId = hit.id; }
				}
			}
		}
		returnSequence(f, prior, batch, directSwitch, owned, emergency, factory, out);
		coincidence(f, hits, emergency, combat, factory, out);
		combatFields(f, batch, deltas, owned, knownFields && !changedCombatLevel && !selectedLevel, emergency, combat, factory, out);
		return out;
	}

	private void returnSequence(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, boolean directSwitch,
		Hit owned, boolean emergency, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (f.liveNpcKeys == null)
		{
			returning = null; sequenceDataEpoch++; return;
		}
		for (NpcDeath death : batch.npcDeaths)
		{
			if (returning != null && (returning.a.equals(death.npcKey) || returning.b != null && returning.b.equals(death.npcKey)))
			{
				returning = null; sequenceDataEpoch++;
			}
			if (f.target != null && f.target.key.equals(death.npcKey)) { owned = null; }
		}
		if (returning != null && (f.tick - returning.first.tick >= 20 || !f.liveNpcKeys.contains(returning.a)
			|| returning.b != null && !f.liveNpcKeys.contains(returning.b))) { returning = null; }
		boolean changed = prior == null || !sameLiveTarget(prior, f) || batch.engagementInterrupted || !batch.localTargetChanges.isEmpty();
		if (returning != null && changed)
		{
			if (directSwitch && returning.segment == 0 && returning.a.equals(prior.target.key) && !returning.a.equals(f.target.key))
			{
				returning.b = f.target.key; returning.segment = 1;
			}
			else if (directSwitch && returning.segment == 1 && returning.b.equals(prior.target.key)
				&& returning.a.equals(f.target.key) && returning.second != null) { returning.segment = 2; }
			else { returning = null; }
		}
		if (!live(f) || !f.liveNpcKeys.contains(f.target.key) || owned == null) { return; }
		if (returning == null)
		{
			returning = new ReturnSequence(f.target.key, new OwnedTick(f.tick, owned.id)); return;
		}
		if (returning.segment == 1 && returning.second == null && f.tick > returning.first.tick)
		{
			returning.second = new OwnedTick(f.tick, owned.id);
		}
		else if (returning.segment == 2)
		{
			ReturnSequence sequence = returning; returning = null;
			if (f.tick <= sequence.second.tick || emergency || !settled(f)) { return; }
			Map<String, String> facts = targetFacts(f);
			facts.put("firstNpcKey", sequence.a); facts.put("secondNpcKey", sequence.b);
			facts.put("engagementSequence", sequence.a + "," + sequence.b + "," + sequence.a);
			facts.put("engagementEvidenceTicks", sequence.first.tick + "," + sequence.second.tick + "," + f.tick);
			facts.put("sequenceDataEpoch", Long.toString(sequenceDataEpoch));
			facts.put("engagementEpoch", Long.toString(engagementEpoch));
			out.add(factory.create(f, "M14", 34, 10, "owned:" + owned.id, facts, false, 4));
		}
	}

	private void coincidence(ScenarioSnapshot f, List<Hit> hits, boolean emergency, CombatState combat,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (engagement == null || !live(f) || emergency || combat != CombatState.CONFIRMED)
		{
			incoming.clear(); return;
		}
		while (!incoming.isEmpty() && incoming.peekFirst().tick < f.tick - 6) { incoming.removeFirst(); }
		incoming.removeIf(record -> record.realHp != f.realHp);
		List<Incoming> additions = new ArrayList<>();
		for (Hit hit : hits)
		{
			if (hit.local && hit.witnessed && DamageTypes.hpDamage(hit.type) && hit.amount >= 5
				&& hit.amount < DamageTypes.largeHitThreshold(f.realHp)) { additions.add(new Incoming(f.tick, hit.id, hit.amount, f.realHp)); }
		}
		if (f.tick <= overflowUntil) { return; }
		if (incoming.size() + additions.size() > 8)
		{
			incoming.clear(); overflowUntil = f.tick + 6; return;
		}
		Incoming selected = null; Hit outgoing = null;
		for (Hit hit : hits)
		{
			if (!ownedPositive(hit, f)) { continue; }
			for (Incoming record : incoming)
			{
				if (record.tick >= f.tick || record.amount != hit.amount) { continue; }
				if (selected == null || record.amount > selected.amount || record.amount == selected.amount
					&& (record.id < selected.id || record.id == selected.id && hit.id < outgoing.id))
				{
					selected = record; outgoing = hit;
				}
			}
		}
		if (selected != null)
		{
			incoming.remove(selected);
			if (settled(f))
			{
				Map<String, String> facts = targetFacts(f); facts.put("matchingAmount", Integer.toString(selected.amount));
				facts.put("incomingTick", Long.toString(selected.tick)); facts.put("outgoingTick", Long.toString(f.tick));
				facts.put("incomingFactId", Long.toString(selected.id)); facts.put("outgoingFactId", Long.toString(outgoing.id));
				facts.put("engagementEpoch", Long.toString(engagementEpoch)); facts.put("realHp", Integer.toString(f.realHp));
				out.add(factory.create(f, "M15", 38, 10, "owned:" + outgoing.id, facts, false, 4));
			}
		}
		incoming.addAll(additions);
	}

	private void observedDeaths(ScenarioSnapshot f, ScenarioSnapshot prior, NpcDeath death, boolean emergency,
		boolean newTarget, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		while (!deaths.isEmpty() && deaths.peekFirst().tick < f.tick - 79) { deaths.removeFirst(); }
		long age = f.tick - engagement.lastOwnedTick;
		if (age < 1 || age > 4 || prior.presentSinceTick != f.presentSinceTick) { return; }
		for (DeathRecord record : deaths) { if (record.key.equals(death.npcKey)) { return; } }
		deaths.addLast(new DeathRecord(f.tick, death.id, death.npcKey));
		while (deaths.size() > 3) { deaths.removeFirst(); }
		if (deaths.size() == 3)
		{
			StringBuilder keys = new StringBuilder(), ticks = new StringBuilder();
			for (DeathRecord record : deaths)
			{
				if (keys.length() > 0) { keys.append(','); ticks.append(','); }
				keys.append(record.key); ticks.append(record.tick);
			}
			deaths.clear();
			if (emergency || newTarget || !settled(f)) { return; }
			Map<String, String> facts = baseFacts(f); facts.put("observedEngagedDeaths", "3");
			facts.put("deadNpcKeys", keys.toString()); facts.put("deathTicks", ticks.toString());
			facts.put("terminalDeathFactId", Long.toString(death.id)); facts.put("activityEpoch", Long.toString(activityEpoch));
			out.add(factory.create(f, "M17", 44, 10, "engaged-death:" + death.id, facts, false, 5));
		}
	}

	private void equalEndpoints(ScenarioSnapshot f, NpcDeath death, boolean localPositive, boolean emergency,
		boolean newTarget, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		long age = f.tick - engagement.lastOwnedTick;
		if (engagement.endpointInvalid || localPositive || engagement.span < 40 || engagement.span > 120
			|| engagement.hitTicks.size() < 8 || age < 1 || age > 2 || engagement.realHp != f.realHp
			|| engagement.startHp != f.currentHp || emergency || newTarget || !settled(f)) { return; }
		Map<String, String> facts = baseFacts(f); facts.put("deadNpcKey", death.npcKey); facts.put("npcResolvedName", engagement.name);
		facts.put("startHp", Integer.toString(engagement.startHp)); facts.put("realHp", Integer.toString(engagement.realHp));
		facts.put("observedEngagementCommits", Integer.toString(engagement.span)); facts.put("terminalDeathFactId", Long.toString(death.id));
		facts.put("activityEpoch", Long.toString(activityEpoch)); facts.put("hpEpoch", Long.toString(hpEpoch));
		out.add(factory.create(f, "M18", 40, 10, "engaged-death:" + death.id, facts, false, 6));
	}

	private void combatFields(ScenarioSnapshot f, ScenarioBatch batch, List<SkillDelta> deltas, Hit owned, boolean known,
		boolean emergency, CombatState combat, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (!known || engagement == null || !live(f) || combat != CombatState.CONFIRMED) { fields.clear(); return; }
		fields.entrySet().removeIf(entry -> entry.getValue().tick < f.tick - 19);
		SkillDelta selected = null; int count = 0;
		for (SkillDelta delta : deltas)
		{
			if (supported(delta.skill) && delta.delta > 0) { selected = delta; count++; }
		}
		if (count != 1 || owned == null || !batch.witnessedSkills.contains(selected.skill) || fieldsHandled) { return; }
		fields.put(selected.skill, new FieldGain(f.tick, selected.skill, selected.delta, owned.id));
		if (fields.size() < 3) { return; }
		fieldsHandled = true;
		if (emergency || !settled(f)) { return; }
		List<FieldGain> selectedFields = new ArrayList<>(fields.values());
		selectedFields.sort(Comparator.comparingLong((FieldGain gain) -> gain.tick).thenComparingInt(gain -> gain.skill.ordinal()));
		selectedFields = new ArrayList<>(selectedFields.subList(0, 3));
		StringBuilder order = new StringBuilder(), ticks = new StringBuilder(), amounts = new StringBuilder(), proofs = new StringBuilder();
		for (FieldGain gain : selectedFields)
		{
			if (order.length() > 0) { order.append(','); ticks.append(','); amounts.append(','); proofs.append(','); }
			order.append(gain.skill.name()); ticks.append(gain.tick); amounts.append(gain.delta); proofs.append(gain.ownedFactId);
		}
		selectedFields.sort(Comparator.comparingInt(gain -> gain.skill.ordinal()));
		StringBuilder subject = new StringBuilder();
		for (FieldGain gain : selectedFields) { if (subject.length() > 0) { subject.append(','); } subject.append(gain.skill.name()); }
		Map<String, String> facts = targetFacts(f); facts.put("skillSet", subject.toString()); facts.put("fieldOrder", order.toString());
		facts.put("gainTicks", ticks.toString()); facts.put("gainDeltas", amounts.toString()); facts.put("gainOwnedFactIds", proofs.toString());
		facts.put("engagementEpoch", Long.toString(engagementEpoch));
		facts.put("levelEpoch", Long.toString(levelEpoch)); facts.put("ownedFactId", Long.toString(owned.id));
		out.add(factory.create(f, "M19", 36, 12, "progress:" + f.tick, facts, false, 5));
	}

	private void quietGap(ScenarioSnapshot f, CombatState combat, long lastDamage, boolean fresh, boolean emergency,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (f.tick >= quiet.expires || f.target != null || fresh || emergency || !deficit(f, quiet)) { quiet = null; return; }
		if (f.tick <= quiet.nullTick) { return; }
		if (combat == CombatState.CONFIRMED)
		{
			if (quiet.calm > 0 || f.tick >= quiet.nullTick + 10) { quiet = null; }
			return;
		}
		if (combat != CombatState.NO_CONFIRMED_EVIDENCE || quiet.calm == 0 && f.tick > quiet.nullTick + 10) { quiet = null; return; }
		quiet.calm++;
		if (quiet.calm < 10) { return; }
		QuietArm ready = quiet; quiet = null;
		if (f.tick - lastDamage < 10) { return; }
		issuedQuiet = ready;
		Map<String, String> facts = baseFacts(f); facts.put("startHp", Integer.toString(ready.startHp)); facts.put("endHp", Integer.toString(ready.endHp));
		facts.put("observedHp", Integer.toString(f.currentHp)); facts.put("realHp", Integer.toString(ready.realHp));
		facts.put("nullTick", Long.toString(ready.nullTick)); facts.put("armExpiresTick", Long.toString(ready.nullTick + 20));
		facts.put("calmSnapshots", "10"); facts.put("quietEpoch", Long.toString(quietEpoch)); facts.put("evidenceEpoch", Long.toString(evidenceEpoch));
		String cause = ready.injuryId > 0 ? "injury:" + ready.injuryId : "engagement-end:" + ready.startFactId;
		out.add(factory.create(f, "M20", 55, 12, cause, facts, false, 8));
	}

	boolean stillValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (!complete(f) || f.emergencySceneActive || combat == CombatState.UNKNOWN
			|| f.sessionGeneration != candidate.getSessionGeneration() || f.sceneRevision != candidate.getSceneRevision()) { return false; }
		Map<String, String> facts = candidate.getConditionFacts(); String id = candidate.getScenarioId();
		if (!Long.toString(continuityEpoch).equals(facts.get("combatContinuityEpoch"))
			|| !Long.toString(f.presentSinceTick).equals(facts.get("witnessPresenceEpoch"))) { return false; }
		if ("M14".equals(id) || "M15".equals(id) || "M19".equals(id))
		{
			if (!live(f) || combat != CombatState.CONFIRMED || !f.target.key.equals(facts.get("targetKey"))
				|| !f.target.name.equals(facts.get("npcResolvedName")) || !Long.toString(engagementEpoch).equals(facts.get("engagementEpoch"))) { return false; }
			if ("M14".equals(id))
			{
				return f.liveNpcKeys != null && f.liveNpcKeys.contains(facts.get("firstNpcKey"))
					&& f.liveNpcKeys.contains(facts.get("secondNpcKey")) && Long.toString(sequenceDataEpoch).equals(facts.get("sequenceDataEpoch"));
			}
			if ("M15".equals(id)) { return Integer.toString(f.realHp).equals(facts.get("realHp")); }
			return Long.toString(levelEpoch).equals(facts.get("levelEpoch"));
		}
		if ("M17".equals(id) || "M18".equals(id))
		{
			if (!Long.toString(activityEpoch).equals(facts.get("activityEpoch"))) { return false; }
			if (live(f) && combat == CombatState.CONFIRMED && !f.target.key.equals(facts.get("deadNpcKey"))) { return false; }
			return !"M18".equals(id) || Integer.toString(f.currentHp).equals(facts.get("startHp"))
				&& Integer.toString(f.realHp).equals(facts.get("realHp")) && Long.toString(hpEpoch).equals(facts.get("hpEpoch"));
		}
		if ("M20".equals(id))
		{
			return issuedQuiet != null && f.target == null && combat == CombatState.NO_CONFIRMED_EVIDENCE
				&& f.tick - lastDamage >= 10 && deficit(f, issuedQuiet) && Long.toString(quietEpoch).equals(facts.get("quietEpoch"))
				&& Long.toString(evidenceEpoch).equals(facts.get("evidenceEpoch"));
		}
		return false;
	}

	void reset()
	{
		continuityEpoch++; engagementEpoch++; activityEpoch++; quietEpoch++;
		engagement = null; returning = null; quiet = null; issuedQuiet = null;
		incoming.clear(); deaths.clear(); fields.clear(); fieldsHandled = false;
		last = null; lastTick = -1; overflowUntil = -1;
	}
	private List<Hit> freshHits(List<Hit> reported)
	{
		List<Hit> fresh = new ArrayList<>(); Set<Long> unique = new HashSet<>(); long high = lastHitId;
		for (Hit hit : reported)
		{
			if (hit.id > lastHitId && unique.add(hit.id)) { fresh.add(hit); high = Math.max(high, hit.id); }
		}
		lastHitId = high; fresh.sort(Comparator.comparingLong(hit -> hit.id)); return fresh;
	}
	private List<NpcDeath> freshDeaths(List<NpcDeath> reported)
	{
		List<NpcDeath> fresh = new ArrayList<>(); Set<String> unique = new HashSet<>(); long high = lastDeathId;
		for (NpcDeath death : reported)
		{
			if (death.id > lastDeathId) { high = Math.max(high, death.id); }
			if (death.id > lastDeathId && death.npcKey != null && unique.add(death.npcKey)) { fresh.add(death); high = Math.max(high, death.id); }
		}
		lastDeathId = high; fresh.sort(Comparator.comparingLong(death -> death.id)); return fresh;
	}
	private static boolean complete(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && !f.dead && f.player != null && f.currentHp > 0 && f.realHp > 0
			&& !f.targetUnknown && (f.target == null || f.target.key != null && f.target.name != null && !f.target.name.trim().isEmpty());
	}
	private static boolean live(ScenarioSnapshot f) { return f != null && f.target != null && f.target.alive && f.target.key != null && f.target.name != null; }
	private static boolean sameLiveTarget(ScenarioSnapshot a, ScenarioSnapshot b) { return live(a) && live(b) && a.target.key.equals(b.target.key); }
	private static boolean settled(ScenarioSnapshot f) { return f.tick - f.presentSinceTick >= 10; }
	private static boolean ownedPositive(Hit hit, ScenarioSnapshot f)
	{
		return hit.mine && hit.witnessed && !hit.local && f.target.key.equals(hit.npcKey) && hit.amount > 0 && DamageTypes.hpDamage(hit.type);
	}
	private static boolean nullTransition(ScenarioBatch batch)
	{
		return !batch.targetChangesUnknown && (batch.localTargetChanges.isEmpty() && !batch.engagementInterrupted
			|| batch.localTargetChanges.size() == 1 && batch.localTargetChanges.get(0) == null);
	}
	private static boolean terminalTransition(ScenarioBatch batch, String key)
	{
		return batch.localTargetChanges.isEmpty() && !batch.engagementInterrupted || batch.localTargetChanges.size() == 1
			&& (batch.localTargetChanges.get(0) == null || key.equals(batch.localTargetChanges.get(0)));
	}
	private static int quarter(int hp) { return (hp + 3) / 4; }
	private static boolean deficit(ScenarioSnapshot f, QuietArm arm) { return f.realHp == arm.realHp && f.currentHp > 0 && arm.startHp - f.currentHp >= quarter(f.realHp); }
	private static boolean supported(Skill skill)
	{
		for (Skill field : FIELDS) { if (field == skill) { return true; } } return false;
	}
	private Map<String, String> baseFacts(ScenarioSnapshot f)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region()));
		facts.put("combatContinuityEpoch", Long.toString(continuityEpoch)); facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick)); return facts;
	}
	private Map<String, String> targetFacts(ScenarioSnapshot f)
	{
		Map<String, String> facts = baseFacts(f); facts.put("targetKey", f.target.key); facts.put("npcResolvedName", f.target.name); return facts;
	}
	private static final class OwnedTick
	{
		final long tick, id;
		OwnedTick(long tick, long id) { this.tick = tick; this.id = id; }
	}
	private static final class ReturnSequence
	{
		final String a; final OwnedTick first; String b; OwnedTick second; int segment;
		ReturnSequence(String a, OwnedTick first) { this.a = a; this.first = first; }
	}
	private static final class Incoming
	{
		final long tick, id; final int amount, realHp;
		Incoming(long tick, long id, int amount, int realHp) { this.tick = tick; this.id = id; this.amount = amount; this.realHp = realHp; }
	}
	private static final class DeathRecord
	{
		final long tick, id; final String key;
		DeathRecord(long tick, long id, String key) { this.tick = tick; this.id = id; this.key = key; }
	}
	private static final class FieldGain
	{
		final long tick, delta, ownedFactId; final Skill skill;
		FieldGain(long tick, Skill skill, long delta, long ownedFactId) { this.tick = tick; this.skill = skill; this.delta = delta; this.ownedFactId = ownedFactId; }
	}
	private static final class Engagement
	{
		final String key; final int startHp, realHp; final long startFactId;
		final Deque<Long> hitTicks = new ArrayDeque<>();
		String name; int span = 1; boolean endpointInvalid, realHpChanged; long lastOwnedTick, injuryId;
		Engagement(ScenarioSnapshot f, Hit hit)
		{
			key = f.target.key; name = f.target.name; startHp = f.currentHp; realHp = f.realHp; startFactId = hit.id;
			endpointInvalid = startHp < (3L * realHp + 3) / 4;
		}
		void record(long tick)
		{
			lastOwnedTick = tick;
			if (hitTicks.isEmpty() || hitTicks.peekLast() != tick) { hitTicks.addLast(tick); }
			while (hitTicks.size() > 8) { hitTicks.removeFirst(); }
		}
	}
	private static final class QuietArm
	{
		final int startHp, endHp, realHp; final long nullTick, startFactId, injuryId, expires; int calm;
		QuietArm(Engagement engagement, ScenarioSnapshot f)
		{
			startHp = engagement.startHp; endHp = f.currentHp; realHp = engagement.realHp; nullTick = f.tick;
			expires = f.tick + 20; startFactId = engagement.startFactId; injuryId = engagement.injuryId;
		}
	}
}
