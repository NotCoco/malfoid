package com.malfoid.asset;

import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.motion.WholeBoundsVisibility;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Scene;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real public callbacks with the independently passed collision/placement fixture. */
public class NativeRecoveryObservationTest
{
    @Test public void firstActualFailureCopiesItsScopeQualificationAndExistingPolicyValues() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = offScreenFailureFixture();
        assertNull(f.actor.getRecoveryObservation());
        f.commit(11);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertNotNull(o);
        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, o.cause);
        assertEquals(RecoveryObservation.Disposition.SEARCH_FAILED, o.disposition);
        assertTrue(o.observationId > 0 && o.episodeId > 0 && o.sceneRevision > 0);
        assertEquals(6, o.episodeStartedTick);
        assertEquals(11, o.committedTick);
        assertEquals(-1, o.worldViewId);
        assertEquals(0, o.plane);
        assertEquals(3200, o.baseX);
        assertEquals(3200, o.baseY);
        assertEquals(104, o.sizeX);
        assertEquals(104, o.sizeY);
        assertEquals(f.originalGeneration, o.placementGenerationBefore);
        assertEquals(f.originalGeneration, o.placementGenerationAfter);
        assertEquals(f.originalLocation.getX(), o.originLocalX);
        assertEquals(f.originalLocation.getY(), o.originLocalY);
        assertEquals(3252, o.originWorldX);
        assertEquals(3250, o.originWorldY);
        assertEquals(16, o.separation);
        assertEquals(5, o.offScreenTicksBefore);
        assertEquals(5, o.offScreenTicksAfter);
        assertEquals(0, o.ineffectivePlansBefore);
        assertEquals(1, o.ineffectivePlansAfter);
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, o.decisionVisibility);
        assertEquals(RoutePlanner.Status.BLOCKED, o.plannerStatus);
        assertEquals(1, o.visitedCells);
        assertEquals(0, o.plannedSteps);
        assertFalse(o.searchLimited);
        assertFalse(o.destinationKnown || o.destinationRechecked || o.originRechecked || o.heightRechecked);
        assertEquals(f.policy().getPlanNotBefore(), o.planNotBefore);
        assertEquals(f.policy().getRetryAt(), o.retryAt);
        f.assertNoRelocation();
    }

    @Test public void repeatedFailuresCoalesceWithinOneEpisodeAndLeaveEarlierFactsImmutable() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = offScreenFailureFixture();
        f.commit(11);
        RecoveryObservation first = f.actor.getRecoveryObservation();
        f.commit(12);
        RecoveryObservation second = f.actor.getRecoveryObservation();
        f.commit(13);
        RecoveryObservation third = f.actor.getRecoveryObservation();
        assertEquals(first.episodeId, second.episodeId);
        assertEquals(first.episodeId, third.episodeId);
        assertEquals(first.episodeStartedTick, third.episodeStartedTick);
        assertTrue(first.observationId < second.observationId && second.observationId < third.observationId);
        assertEquals(1, first.ineffectivePlansAfter);
        assertEquals(0, first.planNotBefore);
        assertEquals(0, first.retryAt);
        assertEquals(2, second.ineffectivePlansAfter);
        assertEquals(3, third.ineffectivePlansAfter);
        assertEquals(18, third.planNotBefore);
        assertEquals(43, third.retryAt);
        long searches = f.actor.getPlanningCount();
        f.actor.onGameTick(true, 13);
        f.clientTicks(90);
        f.commitsThrough(42);
        assertSame(third, f.actor.getRecoveryObservation());
        assertEquals(searches, f.actor.getPlanningCount());
        f.commit(43);
        RecoveryObservation retry = f.actor.getRecoveryObservation();
        assertEquals(first.episodeId, retry.episodeId);
        assertEquals(6, retry.episodeStartedTick);
        assertEquals(48, retry.planNotBefore);
        assertEquals(73, retry.retryAt);
        assertEquals(3, third.ineffectivePlansAfter);
        assertEquals(43, third.retryAt);
    }

    @Test public void visibleSliverAndUnknownAreDistinctSingleFactsForTheirActualOffScreenEpisodes() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilTickNine();
        f.showOrigin();
        when(f.client.getCameraX()).thenReturn(7140);
        f.commit(10);
        RecoveryObservation revealed = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.ORIGIN_VISIBLE, revealed.disposition);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, revealed.decisionVisibility);
        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, revealed.cause);
        assertEquals(6, revealed.episodeStartedTick);
        assertEquals(4, revealed.offScreenTicksBefore);
        assertEquals(0, revealed.offScreenTicksAfter);
        assertNull(revealed.plannerStatus);
        assertFalse(revealed.originRechecked);
        assertEquals(1, f.actor.getPlanningCount());
        f.commit(11);
        assertSame(revealed, f.actor.getRecoveryObservation());
        f.hideOrigin();
        f.commitsThrough(16);
        when(f.client.getViewportWidth()).thenReturn(0);
        f.commit(17);
        RecoveryObservation unknown = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.ORIGIN_UNKNOWN, unknown.disposition);
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, unknown.decisionVisibility);
        assertEquals(12, unknown.episodeStartedTick);
        assertNotEquals(revealed.episodeId, unknown.episodeId);
        assertEquals(5, unknown.offScreenTicksBefore);
        assertEquals(0, unknown.offScreenTicksAfter);
        assertNull(unknown.plannerStatus);
        assertEquals(revealed.ineffectivePlansAfter, unknown.ineffectivePlansAfter);
        f.commit(18);
        assertSame(unknown, f.actor.getRecoveryObservation());
        assertEquals(1, f.actor.getPlanningCount());
    }

    @Test public void aRevealAtPlacementRecheckKeepsTheRealRouteAndDoesNotPublishSuccess() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        when(f.client.getCameraY()).thenReturn(10000, 5952);
        f.commit(11);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.ORIGIN_VISIBLE, o.disposition);
        assertEquals(RoutePlanner.Status.ROUTE, o.plannerStatus);
        assertEquals(2, o.plannedSteps);
        assertTrue(o.destinationKnown && o.destinationRechecked && o.destinationLegal && o.originRechecked);
        assertEquals(3234, o.destinationWorldX);
        assertEquals(3250, o.destinationWorldY);
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, o.decisionVisibility);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, o.originRecheckVisibility);
        assertEquals(5, o.offScreenTicksBefore);
        assertEquals(0, o.offScreenTicksAfter);
        assertFalse(o.heightRechecked);
        assertEquals(o.placementGenerationBefore, o.placementGenerationAfter);
        f.assertNoRelocation();
    }

    @Test public void successIsPublishedOnlyAfterTheSameControllerRegistrationReturns() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        AtomicBoolean registrationObserved = new AtomicBoolean();
        doAnswer(invocation ->
        {
            assertNull(f.actor.getRecoveryObservation());
            assertEquals(0, f.actor.getRecoveryCount());
            registrationObserved.set(true);
            return null;
        }).when(f.client).registerRuneLiteObject(any());
        f.commit(11);
        assertTrue(registrationObserved.get());
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.RELOCATED, o.disposition);
        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, o.cause);
        assertEquals(1, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration, o.placementGenerationBefore);
        assertEquals(f.originalGeneration + 1, o.placementGenerationAfter);
        assertEquals(5, o.offScreenTicksBefore);
        assertEquals(0, o.offScreenTicksAfter);
        assertEquals(0, o.ineffectivePlansAfter);
        assertEquals(0, o.planNotBefore);
        assertEquals(0, o.retryAt);
        assertTrue(o.destinationLegal && o.originRechecked && o.heightRechecked && o.heightValid);
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, o.originRecheckVisibility);
        NativeActor.PlacementSnapshot p = f.actor.getPlacementSnapshot();
        assertEquals(p.getLocation().getX(), o.placedLocalX);
        assertEquals(p.getLocation().getY(), o.placedLocalY);
        assertEquals(p.getDrawZ(), o.drawZ);
        assertEquals(p.getOrientation(), o.orientation);
        assertEquals(120, o.drawZ);
        f.assertRecoveredPlacement(point(34, 50));
        long searches = f.actor.getPlanningCount();
        f.commit(12);
        f.commit(13);
        assertSame(o, f.actor.getRecoveryObservation());
        assertEquals(searches, f.actor.getPlanningCount());
        assertEquals("IDLE", f.actor.getLastClip());
        assertNull(f.actor.getMotionObservation());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void aThrowingRegistrationCannotPublishARecoverySuccessOrKeepTheEarlierFailure() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = NativeRecoveryConformanceTest.Fixture.withThreeFailedOffScreenSearches();
        assertNotNull(f.actor.getRecoveryObservation());
        f.commitsThrough(17);
        f.flags[53][50] = f.flags[35][50] = f.flags[34][50] = 0;
        IllegalStateException failure = new IllegalStateException("registration fixture failure");
        doThrow(failure).when(f.client).registerRuneLiteObject(any());
        try
        {
            f.actor.onGameTick(true, 18);
            fail("The fixture registration must fail");
        }
        catch (IllegalStateException actual)
        {
            assertSame(failure, actual);
        }
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(0, f.actor.getRecoveryCount());
        assertNull(f.actor.getLatestMotionCompletion());
        f.actor.hide(); // The product root performs its existing failure-session cleanup.
        assertNull(f.actor.getRecoveryObservation());
    }

    @Test public void realFrontierAndSearchLimitRemainSeparateFacts() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture pocket = offScreenFailureFixture();
        for (int x = 35; x <= 37; x++) for (int y = 49; y <= 51; y++) pocket.flags[x][y] = 0;
        pocket.commit(11);
        RecoveryObservation small = pocket.actor.getRecoveryObservation();
        assertEquals(RoutePlanner.Status.FRONTIER, small.plannerStatus);
        assertEquals(9, small.visitedCells);
        assertEquals(1, small.plannedSteps);
        assertFalse(small.searchLimited);

        NativeRecoveryConformanceTest.Fixture bounded = new NativeRecoveryConformanceTest.Fixture(false);
        bounded.holdUntilSeparationSixteen();
        bounded.actor.clearIntent();
        bounded.actor.setCombatTarget(true, point(36, 50), 9);
        bounded.commit(11);
        RecoveryObservation full = bounded.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.SEARCH_FAILED, full.disposition);
        assertEquals(RoutePlanner.Status.FRONTIER, full.plannerStatus);
        assertEquals(625, full.visitedCells);
        assertEquals(2, full.plannedSteps);
        assertTrue(full.searchLimited);
        assertEquals(0, full.blockedTicksAfter);
        assertNull(bounded.actor.getLatestMotionCompletion());
        bounded.assertNoRelocation();
    }

    @Test public void visibleP15SuccessCannotBeMisattributedToAnOffScreenEpisode() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = NativeRecoveryConformanceTest.Fixture.withVisibleBlockedRoute();
        f.commitsThrough(61);
        f.flags[53][50] = f.flags[35][50] = f.flags[34][50] = 0;
        f.commit(62);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Cause.P15_STUCK, o.cause);
        assertEquals(RecoveryObservation.Disposition.RELOCATED, o.disposition);
        assertEquals(12, o.episodeStartedTick);
        assertEquals(50, o.blockedTicksBefore);
        assertEquals(0, o.blockedTicksAfter);
        assertEquals(0, o.offScreenTicksBefore);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, o.decisionVisibility);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, o.originRecheckVisibility);
        assertEquals(1, f.actor.getRecoveryCount());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void missingDestinationHeightRecordsTheActualDeferredCheckWithoutRelocating() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.heights[0][34] = null;
        f.actor.clearIntent();
        f.commit(11);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.HEIGHT_UNAVAILABLE, o.disposition);
        assertEquals(RoutePlanner.Status.ROUTE, o.plannerStatus);
        assertTrue(o.destinationLegal && o.originRechecked && o.heightRechecked);
        assertFalse(o.heightValid);
        assertEquals(5, o.offScreenTicksAfter); // Preserve the existing effect; do not invent a reset.
        f.assertNoRelocation();
    }

    @Test public void anActuallyInvalidatedDestinationIsRejectedBeforeAnOriginRecheck() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        AtomicBoolean invalidated = new AtomicBoolean();
        long acceptedSearchCount = f.actor.getPlanningCount() + 1;
        when(f.view.getScene()).thenAnswer(invocation ->
        {
            if (f.actor.getPlanningCount() == acceptedSearchCount && f.actor.getLastPlannerVisited() == 3
                && f.actor.getLastPlannerStatus() == RoutePlanner.Status.ROUTE && invalidated.compareAndSet(false, true))
                f.flags[34][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
            return f.scene;
        });
        f.commit(11);
        RecoveryObservation o = f.actor.getRecoveryObservation();
        assertEquals(RoutePlanner.Status.ROUTE, o.plannerStatus);
        assertEquals(RecoveryObservation.Disposition.DESTINATION_INVALID, o.disposition);
        assertTrue(invalidated.get());
        assertTrue(o.destinationKnown && o.destinationRechecked);
        assertFalse(o.destinationLegal || o.originRechecked || o.heightRechecked);
        f.assertNoRelocation();
    }

    @Test public void sceneDriftAtTheExistingRecheckClearsTheOldObservationWithoutPlacement() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = NativeRecoveryConformanceTest.Fixture.withThreeFailedOffScreenSearches();
        f.commitsThrough(17);
        f.flags[53][50] = f.flags[35][50] = f.flags[34][50] = 0;
        Scene changed = mock(Scene.class);
        AtomicBoolean invalidated = new AtomicBoolean();
        long acceptedSearchCount = f.actor.getPlanningCount() + 1;
        when(f.view.getScene()).thenAnswer(invocation ->
        {
            if (f.actor.getPlanningCount() == acceptedSearchCount && f.actor.getLastPlannerVisited() == 3
                && f.actor.getLastPlannerStatus() == RoutePlanner.Status.ROUTE) invalidated.set(true);
            return invalidated.get() ? changed : f.scene;
        });
        f.actor.onGameTick(true, 18);
        assertEquals(RoutePlanner.Status.ROUTE, f.actor.getLastPlannerStatus());
        assertTrue(invalidated.get());
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration, f.actor.getPlacementGeneration());
        verify(f.client, never()).removeRuneLiteObject(any());
    }

    @Test public void hideSessionResetAndUnavailableCollisionClearTheOnlyRetainedObservation() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = offScreenFailureFixture();
        f.commit(11);
        RecoveryObservation first = f.actor.getRecoveryObservation();
        f.actor.hide();
        assertNull(f.actor.getRecoveryObservation());
        f.actor.onGameTick(true, 12); // The still-isolated player cannot spawn a new object.
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(1, first.ineffectivePlansAfter);
        f.actor.resetForLogin();
        assertNull(f.actor.getRecoveryObservation());
        assertEquals(0, f.actor.getPreparedModelCount());

        NativeRecoveryConformanceTest.Fixture missing = offScreenFailureFixture();
        missing.commit(11);
        assertNotNull(missing.actor.getRecoveryObservation());
        missing.flags[100] = null;
        missing.commit(12);
        assertNull(missing.actor.getRecoveryObservation());
        assertFalse(missing.actor.isVisible());
    }

    @Test public void aNewSceneCaptureUsesANewRevisionAndEpisodeWithoutReusingTheOldFact() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = offScreenFailureFixture();
        f.commit(11);
        RecoveryObservation first = f.actor.getRecoveryObservation();
        f.actor.hide();
        when(f.view.getScene()).thenReturn(mock(Scene.class));
        f.fill(0);
        f.commit(12);
        assertTrue(f.actor.isVisible());
        assertNull(f.actor.getRecoveryObservation());
        f.actor.holdPosition();
        while (f.playerX > 16)
        {
            f.playerX -= 2;
            f.commit(f.tick + 1);
        }
        f.commit(f.tick + 1);
        f.commit(f.tick + 1);
        assertEquals(5, f.policy().getOffScreenTicks());
        LocalPoint origin = f.actor.getLocation();
        long generation = f.actor.getPlacementGeneration();
        f.fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        f.flags[origin.getSceneX()][origin.getSceneY()] = f.flags[f.playerX][f.playerY] = 0;
        f.actor.clearIntent();
        f.commit(f.tick + 1);
        RecoveryObservation current = f.actor.getRecoveryObservation();
        assertEquals(RecoveryObservation.Disposition.SEARCH_FAILED, current.disposition);
        assertEquals(RoutePlanner.Status.BLOCKED, current.plannerStatus);
        assertTrue(current.sceneRevision > first.sceneRevision);
        assertTrue(current.episodeId > first.episodeId);
        assertTrue(current.observationId > first.observationId);
        assertEquals(generation, current.placementGenerationBefore);
        assertEquals(generation, current.placementGenerationAfter);
        assertEquals(origin.getX(), current.originLocalX);
        assertEquals(11, first.committedTick);
        assertEquals(f.originalGeneration, first.placementGenerationAfter);
    }

    private static NativeRecoveryConformanceTest.Fixture offScreenFailureFixture() throws Exception
    {
        NativeRecoveryConformanceTest.Fixture f = new NativeRecoveryConformanceTest.Fixture(false);
        f.holdUntilSeparationSixteen();
        f.isolateBothOrigins();
        f.actor.clearIntent();
        return f;
    }

    private static LocalPoint point(int x, int y)
    {
        return new LocalPoint(x * 128 + 64, y * 128 + 64, -1);
    }
}
