package com.malfoid.scenario;

import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.CreatureAwareness.Creature;
import com.malfoid.motion.CreatureAwareness.Sample;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class FearRulesTest
{
	@Test public void nearestKindAtOnsetOwnsOneNormalGuardedOfferWithoutMotion()
	{
		Replay r = new Replay(); SceneCandidate c = r.next(creature("rat", "rat", 6), creature("bear", "black bear", 1));
		assertNotNull(c); assertEquals("F02", c.getScenarioId()); assertNull(c.getMilestone());
		assertEquals(30, c.getPriority()); assertEquals(12, c.getExpiresTick() - c.getCreatedTick());
		assertEquals("bear", c.getConditionFacts().get("creatureKey"));
		assertEquals(Long.toString(r.frame.tick), c.getConditionFacts().get("fearBeginTick"));
		assertTrue(r.fear.continuationValid(c, r.frame.build()));
		ScenePlan plan = ScenePlan.forCandidate(c, 50, false);
		assertTrue(plan.preservesNativeMotion); assertFalse(plan.approach); assertFalse(plan.run);
		assertEquals(0, plan.holdTicks); assertEquals(0, plan.releaseTicks); assertEquals(ScenePlan.Facing.NATIVE, plan.facing);
		SceneCandidate forged = new SceneCandidate(c.getId(), c.getScenarioId(), c.getPriority(), c.getCreatedTick(),
			c.getExpiresTick(), c.getSessionGeneration(), c.getSceneRevision(), c.getCausalToken(), c.getConditionFacts(), false, 4);
		assertFalse("Copied numbers alone do not grant the issued source capability", r.fear.continuationValid(forged, r.frame.build()));
	}

	@Test public void kindSwitchAndFourQuietTicksNeverReofferButFiveQuietTicksRearm()
	{
		Replay r = new Replay(); SceneCandidate first = r.next(creature("rat", "rat", 2));
		assertEquals("F01", first.getScenarioId());
		assertNull(r.next(creature("rat", "rat", 5), creature("bear", "bear", 1)));
		assertEquals("F02", r.awareness.getGroupId()); assertTrue(r.fear.continuationValid(first, r.frame.build()));
		for (int i = 0; i < 4; i++) { assertNull(r.next()); }
		assertNull(r.next(creature("other-bear", "bear", 6)));
		assertEquals(first.getCreatedTick(), r.awareness.getBeginTick());
		for (int i = 0; i < 5; i++) { assertNull(r.next()); }
		SceneCandidate second = r.next(creature("other-bear", "bear", 6));
		assertNotNull(second); assertEquals("F02", second.getScenarioId());
		assertTrue(second.getCreatedTick() > first.getCreatedTick());
	}

	@Test public void sourceLossTransformationUnknownAndScopeChangesInvalidateTheUnpaintedCapability()
	{
		for (int change = 0; change < 5; change++)
		{
			Replay r = new Replay(); SceneCandidate c = r.next(creature("rat", "rat", 2));
			if (change == 0) { r.next(); }
			if (change == 1) { r.next(creature("rat", "bear", 2)); }
			if (change == 2) { r.known = false; r.next(creature("rat", "rat", 2)); }
			if (change == 3) { r.frame.presentSinceTick++; r.next(creature("rat", "rat", 2)); }
			if (change == 4) { r.frame.sceneRevision++; r.next(creature("rat", "rat", 2)); }
			assertFalse("change " + change, r.fear.continuationValid(c, r.frame.build()));
		}
	}

	@Test public void blockedOnsetOrLateObservationCannotBecomeADelayedOffer()
	{
		Replay absent = new Replay(); absent.frame.present = false; assertNull(absent.next(creature("rat", "rat", 2)));
		absent.frame.present = true; assertNull(absent.next(creature("rat", "rat", 2)));
		Replay late = new Replay(); late.frame.tick++;
		Sample sample = late.sample(creature("rat", "rat", 2)); late.awareness.commit(sample, 0, 0);
		late.frame.tick++; late.fear.observe(sample, late.awareness); assertNull(late.fear.close(late.frame.build(), late.factory::candidate));
		assertNull(late.next(creature("rat", "rat", 2)));
	}

	private static Creature creature(String key, String name, int x)
	{
		return new Creature(key, CreatureAwareness.classifyFear(name, 30), name, 30, x, 0, 0);
	}
	private static final class Replay
	{
		final FearRules fear = new FearRules();
		final CreatureAwareness awareness = new CreatureAwareness();
		final ScenarioRules factory = new ScenarioRules();
		final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		boolean known = true;
		Replay()
		{
			frame.tick = 20; frame.sessionGeneration = 1; frame.sceneRevision = 1; frame.presentSinceTick = 2;
			frame.worldViewId = 0; frame.valid = frame.present = true; frame.currentHp = frame.realHp = 50;
			frame.player = new Position(-2, 0, 0); frame.companion = new Position(0, 0, 0);
		}
		Sample sample(Creature... creatures) { return new Sample(frame.tick, 0, 0, 0, 0, known, Arrays.asList(creatures)); }
		SceneCandidate next(Creature... creatures)
		{
			frame.tick++; Sample sample = sample(creatures); awareness.commit(sample, 0, 0); fear.observe(sample, awareness);
			return fear.close(frame.build(), factory::candidate);
		}
	}
}
