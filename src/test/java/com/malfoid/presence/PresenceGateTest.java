package com.malfoid.presence;

import org.junit.Test;

import static org.junit.Assert.*;

public class PresenceGateTest
{
	private final PresenceGate gate = new PresenceGate();

	private PresenceController.Decision commit(long tick)
	{
		return gate.commit(tick, 1, PresenceReason.NONE, true, true, false, false);
	}

	@Test
	public void clientCyclesAndDuplicateCommitsCannotEarnStability()
	{
		assertFalse(commit(1).isPlacementAllowed());
		for (int cycle = 0; cycle < 100; cycle++)
		{
			assertFalse(gate.inspect(1, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
			assertFalse(commit(1).isPlacementAllowed());
		}
		assertTrue(commit(2).isPlacementAllowed());
		assertFalse(gate.getDecision().isPresent());
		assertTrue(gate.placementSucceeded(2).isPresent());
		assertEquals(2, gate.getDecision().getPresentSinceTick());
		assertEquals(1, gate.getDecision().getPresenceEpoch());
	}

	@Test
	public void userHideRevokesImmediatelyAndStopsPreparation()
	{
		commit(1);
		commit(2);
		gate.placementSucceeded(2);
		PresenceController.Decision hidden = gate.inspect(1, PresenceReason.NONE, false, true, false, true);
		assertEquals(PresenceState.ABSENT_USER, hidden.getState());
		assertFalse(hidden.isPresent());
		assertFalse(hidden.isPreparationAllowed());
		assertEquals(-1, hidden.getPresentSinceTick());
		assertFalse(commit(3).isPlacementAllowed());
		assertTrue(commit(4).isPlacementAllowed());
		assertEquals(2, gate.placementSucceeded(4).getPresenceEpoch());
	}

	@Test
	public void loadingAndRapidSceneTransitionsNeedFiveSettledCommits()
	{
		commit(1);
		commit(2);
		gate.placementSucceeded(2);
		gate.inspect(2, PresenceReason.LOADING, true, true, false, true);
		assertFalse(gate.getDecision().isPresent());
		for (long tick = 3; tick < 7; tick++)
		{
			assertFalse(gate.commit(tick, 3, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
		}
		assertTrue(gate.commit(7, 3, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
		assertEquals(7, gate.placementSucceeded(7).getPresentSinceTick());
	}

	@Test
	public void sceneTransitionAfterDebounceWindowUsesOrdinaryTwoTicks()
	{
		commit(1);
		commit(2);
		gate.placementSucceeded(2);
		gate.commit(17, 1, PresenceReason.NONE, true, true, false, true);
		assertFalse(gate.commit(18, 2, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
		assertTrue(gate.commit(19, 2, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
	}

	@Test
	public void eachPvpOrBoatAbsenceNeedsFiveClearTicks()
	{
		for (PresenceReason reason : new PresenceReason[] {
			PresenceReason.PVP_AREA, PresenceReason.ABOARD})
		{
			PresenceGate isolated = new PresenceGate();
			assertFalse(isolated.commit(1, 1, reason, true, true, false, false).isPreparationAllowed());
			for (long tick = 2; tick < 6; tick++)
			{
				assertFalse(isolated.commit(tick, 1, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
			}
			assertTrue(isolated.commit(6, 1, PresenceReason.NONE, true, true, false, false).isPlacementAllowed());
		}
	}

	@Test
	public void anotherForbiddenSnapshotRestartsTheClearInterval()
	{
		gate.commit(1, 1, PresenceReason.PVP_AREA, true, true, false, false);
		commit(2);
		commit(3);
		gate.inspect(1, PresenceReason.PVP_AREA, true, true, false, false);
		for (long tick = 4; tick < 8; tick++)
		{
			assertFalse(commit(tick).isPlacementAllowed());
		}
		assertTrue(commit(8).isPlacementAllowed());
	}

	@Test
	public void deathBatchCannotBeAnAliveReturn()
	{
		gate.onLocalDeath(10);
		assertEquals(PresenceReason.DEAD, commit(10).getReason());
		assertFalse(commit(11).isPlacementAllowed());
		assertTrue(commit(12).isPlacementAllowed());
	}

	@Test
	public void lifecycleOutranksUserAndUserOutranksAutomaticAbsence()
	{
		assertEquals(PresenceState.SUSPENDED,
			gate.inspect(1, PresenceReason.LOADING, false, false, false, false).getState());
		assertEquals(PresenceState.ABSENT_USER,
			gate.inspect(1, PresenceReason.PLAYER_COMBAT, false, false, false, false).getState());
	}

	@Test
	public void deadSpectatingRevokesImmediatelyAndCannotCountAsAliveReturn()
	{
		commit(1);
		commit(2);
		gate.placementSucceeded(2);
		PresenceController.Decision spectator = gate.inspect(1, PresenceReason.SPECTATING,
			false, true, false, true);
		assertEquals(PresenceState.SUSPENDED, spectator.getState());
		assertEquals(PresenceReason.SPECTATING, spectator.getReason());
		assertFalse(spectator.isPresent());
		assertFalse(spectator.isPreparationAllowed());
		assertFalse(gate.commit(3, 1, PresenceReason.SPECTATING,
			true, true, false, false).isPlacementAllowed());
		assertFalse(commit(4).isPlacementAllowed());
		assertTrue(commit(5).isPlacementAllowed());
	}

	@Test
	public void playerCombatUsesOrdinaryStabilityAfterItsExternalQuietWait()
	{
		gate.commit(1, 1, PresenceReason.PLAYER_COMBAT, true, true, false, false);
		assertFalse(commit(2).isPlacementAllowed());
		assertTrue(commit(3).isPlacementAllowed());
	}

	@Test
	public void modelPreparationCanAdvanceBeforePresenceButFailureCannotRetry()
	{
		assertTrue(gate.commit(1, 1, PresenceReason.NONE, true, false, false, false).isPreparationAllowed());
		assertFalse(gate.getDecision().isPlacementAllowed());
		assertTrue(commit(2).isPlacementAllowed());
		PresenceController.Decision failed = gate.inspect(1, PresenceReason.NONE, true, false, true, false);
		assertEquals(PresenceReason.MODEL_UNAVAILABLE, failed.getReason());
		assertFalse(failed.isPreparationAllowed());
	}
}
