package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LongRunRulesTest
{
	@Test public void sixtyTilesInThirtyTicksNeedsTheFullFiftyCommittedTickWindow()
	{
		Replay r = new Replay(true);
		for (int i = 0; i < 31; i++) { assertNull(r.next(i == 0 ? 0 : 2)); }
		assertEquals(0, r.random.draws);
		for (int i = 0; i < 20; i++) { r.next(0); }
		assertEquals("The last window still includes sixty moved tiles", 1, r.random.draws);
	}

	@Test public void aPreparedWindowAndSixtyTileRunOffersOnceIncludingItsSilentChoice()
	{
		for (boolean spoken : new boolean[]{true, false})
		{
			Replay r = new Replay(spoken); SceneCandidate candidate = r.qualify();
			assertEquals(spoken, candidate != null); assertEquals(1, r.random.draws);
			if (candidate != null)
			{
				assertEquals("F03", candidate.getScenarioId()); assertEquals(30, candidate.getPriority());
				assertEquals(12, candidate.getExpiresTick() - candidate.getCreatedTick()); assertEquals(4, candidate.getHoldTicks());
				assertEquals("60", candidate.getConditionFacts().get("runWindowTiles"));
				assertEquals(Long.toString(r.frame.tick - 49), candidate.getConditionFacts().get("runWindowStartTick"));
			}
			for (int i = 0; i < 200; i++) { assertNull(r.next(2)); }
			assertEquals("Continuing the run never rerolls", 1, r.random.draws);
		}
	}

	@Test public void tenStillTicksAndAWholeFreshWindowAreBothRequiredAfterEitherOutcome()
	{
		Replay r = new Replay(false); r.qualify();
		for (int i = 0; i < 9; i++) { assertNull(r.next(0)); }
		for (int i = 0; i < 60; i++) { assertNull(r.next(2)); }
		assertEquals(1, r.random.draws);
		for (int i = 0; i < 10; i++) { assertNull(r.next(0)); }
		for (int i = 0; i < 49; i++) { assertNull(r.next(2)); }
		assertEquals(1, r.random.draws); assertNull(r.next(2)); assertEquals(2, r.random.draws);
	}

	@Test public void teleportScenePlaneViewPresenceAndMissingCommitResetTheWindow()
	{
		for (int interruption = 0; interruption < 6; interruption++)
		{
			Replay r = new Replay(true); for (int i = 0; i < 21; i++) { r.next(0); }
			for (int i = 0; i < 20; i++) { r.next(2); }
			if (interruption == 0) { r.next(3); }
			if (interruption == 1) { r.frame.sceneRevision++; r.next(0); }
			if (interruption == 2) { r.plane++; r.next(0); }
			if (interruption == 3) { r.frame.worldViewId++; r.next(0); }
			if (interruption == 4) { r.frame.present = false; r.next(0); r.frame.present = true; r.frame.presentSinceTick = r.frame.tick + 1; }
			if (interruption == 5) { r.frame.tick++; r.next(0); }
			for (int i = 0; i < 49; i++) { assertNull("interruption " + interruption, r.next(2)); }
			assertEquals(0, r.random.draws);
		}
	}

	@Test public void onlyConfirmedCombatAndFollowingGateTheDecisionWithoutDestroyingTheWindow()
	{
		Replay fight = new Replay(true); fight.combat = CombatState.CONFIRMED;
		assertNull(fight.qualify()); assertEquals(0, fight.random.draws);
		fight.combat = CombatState.NO_CONFIRMED_EVIDENCE; assertNotNull(fight.next(2));
		Replay stationary = new Replay(true); stationary.following = false;
		assertNull(stationary.qualify()); stationary.following = true; assertNotNull(stationary.next(2));
		Replay unknown = new Replay(true); unknown.combat = CombatState.UNKNOWN; assertNotNull(unknown.qualify());
	}

	@Test public void presentationPlanCannotAcquireMotionEvenWhenFarAwayOrOffScreen()
	{
		Replay r = new Replay(true); SceneCandidate candidate = r.qualify();
		ScenePlan plan = ScenePlan.forCandidate(candidate, 40, false);
		assertTrue(plan.preservesNativeMotion); assertEquals(ScenePlan.Facing.NATIVE, plan.facing);
		assertFalse(plan.approach); assertFalse(plan.run); assertFalse(plan.voluntaryTrip);
		assertEquals(0, plan.holdTicks); assertEquals(0, plan.releaseTicks); assertNull(plan.standWorldX);
	}

	private static final class Choice extends Random
	{
		final boolean spoken; int draws;
		Choice(boolean spoken) { this.spoken = spoken; }
		@Override public boolean nextBoolean() { draws++; return spoken; }
	}
	private static final class Replay
	{
		final Choice random;
		final LongRunRules rules;
		final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		CombatState combat = CombatState.NO_CONFIRMED_EVIDENCE;
		int x, plane; long id; boolean following = true;
		Replay(boolean spoken) { random = new Choice(spoken); rules = new LongRunRules(random); frame.worldViewId = 0; }
		SceneCandidate next(int step)
		{
			frame.tick++; x += step; frame.player = new Position(x, 0, plane); frame.companion = new Position(x + 2, 0, plane);
			return rules.close(frame.build(), false, combat, following,
				(f, scenario, priority, expiry, cause, facts, silent, hold) -> new SceneCandidate(++id, scenario, priority,
					f.tick, f.tick + expiry, f.sessionGeneration, f.sceneRevision, cause, facts, silent, hold));
		}
		SceneCandidate qualify()
		{
			for (int i = 0; i < 21; i++) { assertNull(next(0)); }
			for (int i = 0; i < 29; i++) { assertNull(next(2)); }
			return next(2);
		}
	}
}
