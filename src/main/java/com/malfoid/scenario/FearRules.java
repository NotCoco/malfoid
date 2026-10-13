package com.malfoid.scenario;

import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.CreatureAwareness.Creature;
import com.malfoid.motion.CreatureAwareness.Sample;
import com.malfoid.speech.SceneCandidate;
import java.util.HashMap;
import java.util.Map;

/** One copied E.5 speech opportunity at the beginning of a continuous fear episode. */
final class FearRules
{
	private Sample sample;
	private Creature beginCreature;
	private long beginTick = -1, consumedBegin = Long.MIN_VALUE;
	private boolean keepingAway;
	private SceneCandidate issued;

	void observe(Sample sample, CreatureAwareness awareness)
	{
		this.sample = sample;
		beginCreature = awareness.getBeginCreature(); beginTick = awareness.getBeginTick();
		keepingAway = awareness.isKeepingAway();
	}

	SceneCandidate close(ScenarioSnapshot f, ExpandedScenarioRules.Factory factory)
	{
		if (issued != null && !valid(issued, f)) { issued = null; }
		if (sample == null || sample.tick != f.tick || !sample.known || !keepingAway || beginCreature == null
			|| !CreatureAwareness.isE5(beginCreature.classification.groupId) || beginTick == consumedBegin) { return null; }
		consumedBegin = beginTick;
		// A blocked onset is consumed; absence, a cooldown or a later callback cannot replay it.
		if (beginTick != f.tick || !healthy(f) || sample.worldViewId != f.worldViewId || beginCreature.plane != f.companion.plane
			|| beginCreature.distance(f.companion.x, f.companion.y) > 6) { return null; }
		Map<String, String> facts = new HashMap<>();
		facts.put("fearBeginTick", Long.toString(beginTick));
		facts.put("creatureKey", beginCreature.key);
		facts.put("creatureGroup", beginCreature.classification.groupId);
		facts.put("creatureX", Integer.toString(beginCreature.worldX));
		facts.put("creatureY", Integer.toString(beginCreature.worldY));
		facts.put("creaturePlane", Integer.toString(beginCreature.plane));
		facts.put("creatureWorldViewId", Integer.toString(sample.worldViewId));
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		// E.5 says optional, without another probability. Section 5 guards may refuse this offer.
		// Priority, short deadline and readable length are ordinary presentation defaults for ruling.
		issued = factory.create(f, beginCreature.classification.groupId, 30, 12,
			"fear:" + beginTick + ":" + beginCreature.key, facts, false, 4);
		return issued;
	}

	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f)
	{
		return issued == candidate && valid(candidate, f);
	}

	private boolean valid(SceneCandidate candidate, ScenarioSnapshot f)
	{
		return healthy(f) && sample != null && sample.tick == f.tick && sample.known && keepingAway
			&& beginCreature != null && CreatureAwareness.isE5(candidate.getScenarioId())
			&& beginCreature.key.equals(candidate.getConditionFacts().get("creatureKey"))
			&& beginCreature.classification.groupId.equals(candidate.getScenarioId())
			&& Long.toString(beginTick).equals(candidate.getConditionFacts().get("fearBeginTick"))
			&& f.sessionGeneration == candidate.getSessionGeneration() && f.sceneRevision == candidate.getSceneRevision()
			&& sample.worldViewId == f.worldViewId
			&& sourceMatches(candidate, f)
			&& Integer.toString(f.worldViewId).equals(candidate.getConditionFacts().get("creatureWorldViewId"))
			&& Long.toString(f.presentSinceTick).equals(candidate.getConditionFacts().get("witnessPresenceEpoch"));
	}

	private boolean sourceMatches(SceneCandidate candidate, ScenarioSnapshot f)
	{
		for (Creature creature : sample.creatures)
		{
			if (creature.key.equals(candidate.getConditionFacts().get("creatureKey"))
				&& creature.classification.groupId.equals(candidate.getScenarioId())
				&& creature.plane == f.companion.plane && creature.distance(f.companion.x, f.companion.y) <= 6) { return true; }
		}
		return false;
	}

	private static boolean healthy(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0
			&& f.player != null && f.companion != null && f.player.plane == f.companion.plane;
	}

	void reset() { issued = null; consumedBegin = Long.MIN_VALUE; }
}
