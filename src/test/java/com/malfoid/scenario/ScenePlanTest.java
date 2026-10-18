package com.malfoid.scenario;

import com.malfoid.speech.SceneCandidate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScenePlanTest
{
	@Test public void passiveLivingWorldLinesNeverApproachOrHoldEvenWhenDistantAndOffscreen()
	{
		for (String kind : new String[]{"PLACE", "CREATURE"})
		{
			Map<String, String> facts = new HashMap<>(); facts.put("livingWorld", kind);
			facts.put("targetKey", "passive-target"); facts.put("targetX", "3105"); facts.put("targetY", "3200");
			ScenePlan plan = ScenePlan.forCandidate(candidate("world-test", facts), 8, false, false, 12);
			assertFalse(plan.approach); assertFalse(plan.run); assertFalse(plan.voluntaryTrip);
			assertEquals(0, plan.holdTicks); assertEquals(0, plan.releaseTicks); assertEquals(ScenePlan.Facing.PLAYER, plan.facing);
			assertNull(plan.standWorldX); assertNull(plan.standWorldY); assertNull(plan.subjectWorldX); assertNull(plan.subjectWorldY);
		}
	}

	@Test public void voluntaryTripsWaitOneHundredTicksAfterActualMovement()
	{
		ScenePlan inspection = ScenePlan.forCandidate(candidate("M08", Collections.emptyMap()), 3, true, true);
		assertTrue(inspection.voluntaryTrip); assertTrue(inspection.voluntaryTripAllowed(12, -1));
		assertFalse(inspection.voluntaryTripAllowed(111, 12)); assertTrue(inspection.voluntaryTripAllowed(112, 12));
		assertFalse(inspection.voluntaryTripAllowed(11, 12));
		assertTrue(ScenePlan.forCandidate(candidate("M69", Collections.emptyMap()), 3, true, true).voluntaryTrip);
		ScenePlan speech = ScenePlan.forCandidate(candidate("M09", Collections.emptyMap()), 6, true, false);
		assertTrue(speech.approach); assertFalse(speech.voluntaryTrip); assertTrue(speech.voluntaryTripAllowed(20, 19));
	}

	@Test public void quietAndAlreadyNearbySpeechDoNotStartVoluntaryTrips()
	{
		SceneCandidate c = candidate("M06", Collections.emptyMap());
		ScenePlan quiet = ScenePlan.forCandidate(c, 8, false, true, 7);
		assertFalse(quiet.approach); assertFalse(quiet.voluntaryTrip); assertTrue(quiet.voluntaryTripAllowed(20, 19));
		assertEquals(7, quiet.holdTicks);
		ScenePlan nearby = ScenePlan.forCandidate(c, 3, true, false, 11);
		assertFalse(nearby.approach); assertFalse(nearby.voluntaryTrip); assertEquals(11, nearby.holdTicks);
	}

	@Test public void coreEmergencyAndRequestedMovementKeepsItsContracts()
	{
		for (String id : new String[]{"M01", "M02", "M05", "M07", "U01"})
		{
			ScenePlan plan = ScenePlan.forCandidate(candidate(id, Collections.emptyMap()), 8, false, false);
			assertTrue(id, plan.approach); assertFalse(id, plan.voluntaryTrip); assertTrue(id, plan.voluntaryTripAllowed(20, 19));
		}
		Map<String, String> facts = new HashMap<>(); facts.put("newRealLevel", "99");
		assertFalse(ScenePlan.forCandidate(candidate("M04", facts), 8, false, false).voluntaryTrip);
		facts.clear(); facts.put("emoteAnimation", Integer.toString(AnimationID.EMOTE_BECKON));
		assertFalse(ScenePlan.forCandidate(candidate("E01", facts), 8, false, true).voluntaryTrip);
	}

	private static SceneCandidate candidate(String id, Map<String, String> facts)
	{
		return new SceneCandidate(1, id, 40, 12, 24, 1, 1, "test:" + id, facts, false, 8);
	}
}
