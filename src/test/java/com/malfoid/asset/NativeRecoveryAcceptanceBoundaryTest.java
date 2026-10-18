package com.malfoid.asset;

import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.motion.WholeBoundsVisibility;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Missing native boundaries; no policy/result injection and no root conformance activation. */
public class NativeRecoveryAcceptanceBoundaryTest
{
    @Test public void separationThirteenNeedsThreeActualOrdinaryFailuresBeforeItsRecoveryCanRun() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = heldAtThirteen(7);
        f.isolateBothOrigins();
        f.actor.clearIntent();
        f.commit(8);
        assertEquals(1, f.policy().getIneffectivePlans());
        assertNull(f.actor.getRecoveryObservation());
        f.commit(9);
        assertEquals(2, f.policy().getIneffectivePlans());
        assertNull(f.actor.getRecoveryObservation());
        f.commit(10);
        assertEquals(3, f.policy().getIneffectivePlans());
        assertEquals(5, f.policy().getOffScreenTicks());
        assertEquals(15, f.policy().getPlanNotBefore());
        assertEquals(40, f.policy().getRetryAt());
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(4, f.actor.getPlanningCount());
        f.commitsThrough(14);
        assertEquals(4, f.actor.getPlanningCount());
        f.flags[53][50] = f.flags[38][50] = f.flags[37][50] = 0;
        f.commit(15);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.RELOCATED, o.disposition);
        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, o.cause);
        assertEquals(13, o.separation);
        assertEquals(3, o.ineffectivePlansBefore);
        assertEquals(5, o.offScreenTicksBefore);
        assertEquals(6, o.episodeStartedTick);
        assertEquals(15, o.committedTick);
        assertEquals(RoutePlanner.Status.ROUTE, o.plannerStatus);
        assertEquals(2, o.plannedSteps);
        assertEquals(5, f.actor.getPlanningCount());
        f.assertRecoveredPlacement(point(37, 50));
        f.commit(16);
        f.commit(17);
        assertEquals(5, f.actor.getPlanningCount());
        assertEquals("IDLE", f.actor.getLastClip());
        assertSame(o, f.actor.getRecoveryObservation());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void aSecondActualRelocationCannotPrecedeSixtyCommittedTicks() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = recoveredAtEleven();
        RecoveryObservation first = f.actor.getRecoveryObservation();
        assertEquals(11, first.committedTick);
        f.commit(12);
        f.commit(13);
        f.fill(0);
        f.actor.holdPosition();
        while (f.playerX < 50)
        {
            f.playerX += 2;
            f.commit(f.tick + 1);
        }
        f.commit(21);
        f.commit(22);
        assertEquals(16, f.actor.getDistanceToPlayer());
        assertEquals(5, f.policy().getOffScreenTicks());
        f.fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        f.flags[34][50] = f.flags[50][50] = 0;
        f.actor.clearIntent();
        f.commitsThrough(70);
        assertEquals(1, f.actor.getRecoveryCount());
        assertEquals(first.placementGenerationAfter, f.actor.getPlacementGeneration());
        assertEquals(point(34, 50), f.actor.getLocation());
        assertSame(first, f.actor.getRecoveryObservation());
        assertEquals(85, f.policy().getRetryAt());
        // A fresh actual change can open the existing retry guard, but only now
        // has the independent 60-tick relocation guard passed (71 - 11).
        f.flags[35][50] = f.flags[51][50] = f.flags[52][50] = 0;
        long searches = f.actor.getPlanningCount();
        f.commit(71);
        RecoveryObservation second = f.actor.getRecoveryObservation();
        assertEquals(searches + 1, f.actor.getPlanningCount());
        assertEquals(2, f.actor.getRecoveryCount());
        assertEquals(71, second.committedTick);
        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, second.cause);
        assertEquals(RecoveryObservation.Disposition.RELOCATED, second.disposition);
        assertEquals(16, second.separation);
        assertEquals(5, second.offScreenTicksBefore);
        assertEquals(first.placementGenerationAfter, second.placementGenerationBefore);
        assertEquals(second.placementGenerationBefore + 1, second.placementGenerationAfter);
        assertEquals(point(52, 50), f.actor.getLocation());
        assertTrue(second.episodeId > first.episodeId && second.observationId > first.observationId);
        assertEquals(11, first.committedTick);
        verify(f.client, times(3)).registerRuneLiteObject(any());
        verify(f.client, times(2)).removeRuneLiteObject(any());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void ownerSuppressionCannotRelocateEvenWithAnActuallyReachablePlayerDestination() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = NativeRecoveryConformanceTest.Fixture.withThreeFailedOffScreenSearches();
        RecoveryObservation failure = f.actor.getRecoveryObservation();
        f.actor.setRecoveryAllowed(false);
        f.commitsThrough(17);
        f.flags[53][50] = f.flags[35][50] = f.flags[34][50] = 0;
        f.commit(18);
        assertSame(failure, f.actor.getRecoveryObservation());
        assertEquals(5, f.actor.getPlanningCount()); // Ordinary failure, not another recovery search.
        assertEquals(RoutePlanner.Status.BLOCKED, f.actor.getLastPlannerStatus());
        f.assertNoRelocation();
    }

    @Test public void theExactThirteenTileFourToZeroSliverEdgeIsOneKnownVisibilityOutcome() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = heldAtThirteen(9);
        assertEquals(4, f.policy().getOffScreenTicks());
        f.showOrigin();
        when(f.client.getCameraX()).thenReturn(7140);
        double anchorX = 400d + (f.actor.getLocation().getX() - f.client.getCameraX()) * 512d
            / (f.actor.getLocation().getY() - f.client.getCameraY());
        assertTrue(anchorX < 0);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility());
        f.commit(10);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(13, o.separation);
        assertEquals(4, o.offScreenTicksBefore);
        assertEquals(0, o.offScreenTicksAfter);
        assertEquals(RecoveryObservation.Disposition.ORIGIN_VISIBLE, o.disposition);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, o.decisionVisibility);
        assertNull(o.plannerStatus);
        assertFalse(o.originRechecked);
        f.actor.onGameTick(true, 10);
        f.clientTicks(90);
        f.commit(11);
        assertSame(o, f.actor.getRecoveryObservation());
        assertEquals(1, f.actor.getPlanningCount());
        f.assertNoRelocation();
    }

    @Test public void actualNearPlaneCrossingAndNonfiniteGpuCameraAreUnknownNotVisibleSlivers() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture mixed = heldAtThirteen(9);
        when(mixed.client.getCameraY()).thenReturn(6414); // West-facing union has depths both below/above 50.
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, mixed.actor.getWholeBoundsVisibility());
        mixed.commit(10);
        assertUnknownReset(mixed);

        NativeRecoveryConformanceTest.Fixture nonfinite = heldAtThirteen(9);
        when(nonfinite.client.isGpu()).thenReturn(true);
        when(nonfinite.client.getCameraFpX()).thenReturn(Float.NaN);
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, nonfinite.actor.getWholeBoundsVisibility());
        nonfinite.commit(10);
        assertUnknownReset(nonfinite);
    }

    @Test public void absenceDropsTheOldOffScreenPrefixAndReappearanceIsOrdinaryPlacement() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = heldAtThirteen(9);
        assertEquals(4, f.policy().getOffScreenTicks());
        long searches = f.actor.getPlanningCount();
        f.actor.onGameTick(false, 10);
        f.clientTicks(30);
        assertFalse(f.actor.isVisible());
        assertEquals(0, f.policy().getOffScreenTicks());
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(searches, f.actor.getPlanningCount());
        f.actor.onGameTick(true, 11);
        f.clientTicks(30);
        assertTrue(f.actor.isVisible());
        assertTrue(f.actor.getDistanceToPlayer() <= 2);
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(0, f.actor.getRecoveryCount());
        assertNull(f.actor.getRecoveryObservation());
        assertNull(f.actor.getLatestMotionCompletion());
        verify(f.client, times(2)).registerRuneLiteObject(any());
        verify(f.client, times(1)).removeRuneLiteObject(any());
    }

    @Test public void numericalViewReuseAtTheRealPrePlacementRecheckCannotProduceAnOutcome() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = eligiblePlayerRouteFixture();
        WorldView replacement = mock(WorldView.class);
        when(replacement.getId()).thenReturn(-1);
        when(replacement.getScene()).thenReturn(f.scene);
        when(replacement.getBaseX()).thenReturn(3200);
        when(replacement.getBaseY()).thenReturn(3200);
        when(replacement.getSizeX()).thenReturn(104);
        when(replacement.getSizeY()).thenReturn(104);
        when(replacement.getTileHeights()).thenReturn(f.heights);
        when(replacement.getTileSettings()).thenReturn(f.settings);
        net.runelite.api.CollisionData[] maps = f.view.getCollisionMaps();
        when(replacement.getCollisionMaps()).thenReturn(maps);
        long nextSearch = f.actor.getPlanningCount() + 1;
        when(f.player.getWorldView()).thenAnswer(invocation -> afterPlayerSearch(f, nextSearch) ? replacement : f.view);
        f.actor.onGameTick(true, 11);
        assertEquals(RoutePlanner.Status.ROUTE, f.actor.getLastPlannerStatus());
        assertEquals(3, f.actor.getLastPlannerVisited());
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration, f.actor.getPlacementGeneration());
        verify(f.client, never()).removeRuneLiteObject(any());
        verify(f.client, times(1)).registerRuneLiteObject(any());
    }

    @Test public void aPlaneChangeAfterTheActualSearchCannotRegisterOrPublishSuccess() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = eligiblePlayerRouteFixture();
        long nextSearch = f.actor.getPlanningCount() + 1;
        when(f.view.getPlane()).thenAnswer(invocation -> afterPlayerSearch(f, nextSearch) ? 1 : 0);
        f.actor.onGameTick(true, 11);
        assertEquals(RoutePlanner.Status.ROUTE, f.actor.getLastPlannerStatus());
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration, f.actor.getPlacementGeneration());
        verify(f.client, never()).removeRuneLiteObject(any());
        verify(f.client, times(1)).registerRuneLiteObject(any());
    }

    @Test public void revealingTheOldOriginAfterPlacementCannotCancelOrReactivateItsEpisode() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = recoveredAtEleven();
        RecoveryObservation success = f.actor.getRecoveryObservation();
        f.showOrigin();
        when(f.client.getCameraX()).thenReturn(7140); // Would reveal the old (52,50) union, not the placed (34,50) one.
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, f.actor.getWholeBoundsVisibility());
        f.commit(12);
        f.commit(13);
        assertSame(success, f.actor.getRecoveryObservation());
        assertEquals(RecoveryObservation.Disposition.RELOCATED, success.disposition);
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(1, f.actor.getRecoveryCount());
        assertEquals(point(34, 50), f.actor.getLocation());
        assertEquals(2, f.actor.getPlanningCount());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    private static boolean afterPlayerSearch(NativeRecoveryConformanceTest.Fixture f, long searches)
    {
        return f.actor.getPlanningCount() == searches && f.actor.getLastPlannerStatus() == RoutePlanner.Status.ROUTE
            && f.actor.getLastPlannerVisited() == 3;
    }

    private static void assertUnknownReset(NativeRecoveryConformanceTest.Fixture f)
    {
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.ORIGIN_UNKNOWN, o.disposition);
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, o.decisionVisibility);
        assertEquals(4, o.offScreenTicksBefore);
        assertEquals(0, o.offScreenTicksAfter);
        assertNull(o.plannerStatus);
        assertEquals(1, f.actor.getPlanningCount());
        f.assertNoRelocation();
    }

    private static NativeRecoveryConformanceTest.Fixture heldAtThirteen(long through) throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.actor.holdPosition();
        for (int x = 48; x >= 40; x -= 2)
        {
            f.playerX = x;
            f.commit(f.tick + 1);
        }
        f.playerX = 39;
        f.commit(7);
        f.commitsThrough(through);
        assertEquals(13, f.actor.getDistanceToPlayer());
        assertEquals(0, f.policy().getIneffectivePlans());
        assertEquals(1, f.actor.getPlanningCount());
        return f;
    }

    private static NativeRecoveryConformanceTest.Fixture eligiblePlayerRouteFixture() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        return f;
    }

    private static NativeRecoveryConformanceTest.Fixture recoveredAtEleven() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = eligiblePlayerRouteFixture();
        f.commit(11);
        assertEquals(RecoveryObservation.Disposition.RELOCATED, f.actor.getRecoveryObservation().disposition);
        assertEquals(point(34, 50), f.actor.getLocation());
        return f;
    }

    private static LocalPoint point(int x, int y)
    {
        return new LocalPoint(x * 128 + 64, y * 128 + 64, -1);
    }
}
