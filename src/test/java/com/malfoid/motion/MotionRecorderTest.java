package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionRecorderTest
{
    @Test public void onlyCompletedOwnedEdgesCanProduceAnImmutableExactArrival()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.CATCHUP, 1, 3, false, route(3, false));
        recorder.arrive(2, 3203, 3200, 3, true);
        assertNull(recorder.completion());
        recorder.segmentStarted();
        recorder.completedEdge(2, 3200, 3200, 3201, 3200, true);
        recorder.completedEdge(2, 3201, 3200, 3202, 3200, true);
        recorder.completedEdge(3, 3202, 3200, 3203, 3200, false);
        recorder.arrive(3, 3202, 3200, 3, true);
        assertNull(recorder.completion());
        recorder.arrive(3, 3203, 3200, 3, true);
        MotionObservation receipt = recorder.completion();
        assertEquals(MotionObservation.Status.ARRIVED, receipt.status);
        assertEquals(3, receipt.completedTick);
        assertEquals(3, receipt.completedEdges);
        assertEquals(2, receipt.completedRunEdges);
        assertEquals(3, receipt.lastCompletedEdgeTick);
        assertTrue(receipt.ordinary);
        plan(recorder, MotionObservation.Purpose.ROAM, 4, 2, false, route(2, false));
        assertNotEquals(receipt.requestId, recorder.current().requestId);
        assertSame(receipt, recorder.completion());
        assertEquals(MotionObservation.Status.ARRIVED, receipt.status);
    }

    @Test public void aFrontierNeverBecomesAnArrival()
    {
        MotionRecorder recorder = new MotionRecorder();
        RoutePlanner.Result result = route(40, false);
        assertEquals(RoutePlanner.Status.FRONTIER, result.status);
        plan(recorder, MotionObservation.Purpose.CATCHUP, 1, 12, false, result);
        recorder.segmentStarted();
        recorder.completedEdge(2, 3200, 3200, 3212, 3200, true);
        recorder.arrive(2, 3212, 3200, 3, true);
        assertFalse(recorder.current().legalGoal);
        assertNull(recorder.completion());
    }

    @Test public void physicalBlockAndActualChangedEdgeRemainBoundedAndKeepTheFixedGoal()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.ROAM, 1, 3, false, route(3, false));
        long id = recorder.current().requestId;
        recorder.blocked(2, 3200, 3200, 3201, 3200);
        plan(recorder, MotionObservation.Purpose.ROAM, 3, 3, false, route(3, true));
        assertEquals(id, recorder.current().requestId);
        assertTrue(recorder.current().fixedGoal);
        assertTrue(recorder.current().physicalBlock);
        assertTrue(recorder.current().previouslyLegalEdge);
        assertFalse(recorder.current().changedEdgeAfterBlock);
        recorder.segmentStarted();
        recorder.completedEdge(4, 3200, 3200, 3201, 3199, false);
        assertTrue(recorder.current().changedEdgeAfterBlock);
        recorder.arrive(5, 3203, 3200, 3, true);
        assertTrue(recorder.completion().changedEdgeAfterBlock);
        recorder.commit(22);
        assertFalse(recorder.current().physicalBlock);
        assertEquals(-1, recorder.current().blockedTick);
    }

    @Test public void aReplacementCannotClaimAnAlreadyCommittedEdgeAndResetDropsReceipts()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.CATCHUP, 1, 3, false, route(3, false));
        recorder.segmentStarted();
        recorder.cancel(MotionObservation.Status.INVALIDATED);
        plan(recorder, MotionObservation.Purpose.ROAM, 2, 2, true, route(2, false));
        recorder.completedEdge(3, 3200, 3200, 3201, 3200, true);
        assertEquals(0, recorder.current().completedEdges);
        assertFalse(recorder.current().known);
        recorder.clear();
        assertNull(recorder.current());
        assertNull(recorder.completion());
    }

    @Test public void countersCapAtTwentyFourAndFurtherTravelBecomesUnknown()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.CATCHUP, 1, 3, false, route(3, false));
        recorder.segmentStarted();
        for (int i = 0; i < 25; i++) recorder.completedEdge(2, 3200 + i, 3200, 3201 + i, 3200, true);
        assertEquals(24, recorder.current().completedEdges);
        assertEquals(24, recorder.current().completedRunEdges);
        assertFalse(recorder.current().known);
        recorder.arrive(2, 3203, 3200, 3, true);
        assertNull(recorder.recentCompletion(recorder.current().requestId, 2));
    }

    @Test public void genuineRecentCompletionsAreBoundedByCountAndAgeAndNeverRebuiltFromInvalidation()
    {
        MotionRecorder recorder = new MotionRecorder();
        MotionObservation first = null;
        for (int tick = 1; tick <= 25; tick++)
        {
            plan(recorder, MotionObservation.Purpose.CATCHUP, tick, 1, false, route(1, false));
            recorder.segmentStarted();
            recorder.completedEdge(tick, 3200, 3200, 3201, 3200, true);
            recorder.arrive(tick, 3201, 3200, 3, true);
            if (tick == 1) first = recorder.completion();
        }
        assertNull(recorder.recentCompletion(first.requestId, 25));
        MotionObservation last = recorder.completion();
        assertSame(last, recorder.recentCompletion(last.requestId, 25));
        recorder.cancel(MotionObservation.Status.INVALIDATED);
        assertEquals(MotionObservation.Status.INVALIDATED, recorder.current().status);
        assertSame(last, recorder.recentCompletion(last.requestId, 25));
        recorder.clearRecentCompletions();
        assertNull(recorder.recentCompletion(last.requestId, 25));
        assertSame(last, recorder.completion());
        recorder.clear();
        assertNull(recorder.completion());
    }

    @Test public void authoredOtherRoutesAreNeverEligibleForOrdinaryAdmission()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.OTHER, 1, 1, false, route(1, false));
        recorder.segmentStarted();
        recorder.completedEdge(2, 3200, 3200, 3201, 3200, false);
        recorder.arrive(2, 3201, 3200, 3, true);
        assertNotNull(recorder.completion());
        assertNull(recorder.recentCompletion(recorder.completion().requestId, 2));
    }

    @Test public void anEmptyAtGoalDecisionCannotRearmAnInvalidatedArrival()
    {
        MotionRecorder recorder = new MotionRecorder();
        plan(recorder, MotionObservation.Purpose.CATCHUP, 1, 1, false, route(1, false));
        recorder.segmentStarted();
        recorder.completedEdge(2, 3200, 3200, 3201, 3200, true);
        recorder.arrive(2, 3201, 3200, 3, true);
        MotionObservation original = recorder.completion();
        recorder.cancel(MotionObservation.Status.INVALIDATED);
        recorder.clearRecentCompletions();
        recorder.plan(MotionObservation.Purpose.CATCHUP, 0, 3, 7, -1, 0, 3201, 3200, 3201, 3200, 3,
            false, route(0, false));
        assertEquals(MotionObservation.Status.INVALIDATED, recorder.current().status);
        recorder.arrive(4, 3201, 3200, 3, true);
        assertSame(original, recorder.completion());
        assertEquals(2, recorder.completion().completedTick);
        assertNull(recorder.recentCompletion(original.requestId, 4));
    }

    @Test public void aKeyedSourceIsImmutableAndCannotReuseAnotherSourcesRequestOrCommittedEdge()
    {
        MotionRecorder recorder = new MotionRecorder();
        recorder.plan(MotionObservation.Purpose.OTHER, 700L, 2, 1, 7, -1, 0,
            3200, 3200, 3203, 3200, 12, false, route(3, false));
        MotionObservation inspection = recorder.current();
        assertEquals(700, inspection.sourceCandidateId);
        assertFalse(inspection.ordinary);
        recorder.segmentStarted();
        recorder.cancel(MotionObservation.Status.INVALIDATED);
        recorder.plan(MotionObservation.Purpose.APPROACH_PLAYER, 700L, 1, 2, 7, -1, 0,
            3200, 3200, 3202, 3200, 8, true, route(2, false));
        MotionObservation returning = recorder.current();
        assertEquals(700, returning.sourceCandidateId);
        assertTrue(returning.ordinary);
        assertNotEquals(inspection.requestId, returning.requestId);
        recorder.completedEdge(3, 3200, 3200, 3201, 3200, false);
        assertEquals(0, recorder.current().completedEdges);
        recorder.segmentStarted();
        recorder.completedEdge(3, 3201, 3200, 3202, 3200, false);
        recorder.arrive(3, 3202, 3200, 3, true);
        assertEquals(700, recorder.completion().sourceCandidateId);
        assertEquals(1, recorder.completion().completedEdges);
        assertEquals(MotionObservation.Status.MOVING, returning.status);
        recorder.plan(MotionObservation.Purpose.APPROACH_PLAYER, 701L, 1, 4, 7, -1, 0,
            3200, 3200, 3202, 3200, 8, false, route(2, false));
        assertNotEquals(returning.requestId, recorder.current().requestId);
        assertEquals(701, recorder.current().sourceCandidateId);
        long otherSourceRequest = recorder.current().requestId;
        recorder.plan(MotionObservation.Purpose.APPROACH_PLAYER, 702L, 1, 5, 7, -1, 0,
            3200, 3200, 3202, 3200, 8, false, route(2, false));
        assertNotEquals(otherSourceRequest, recorder.current().requestId);
        assertEquals(702, recorder.current().sourceCandidateId);
        plan(recorder, MotionObservation.Purpose.OTHER, 5, 2, false, route(2, false));
        assertEquals(-1, recorder.current().sourceCandidateId);
    }

    private static void plan(MotionRecorder recorder, MotionObservation.Purpose purpose, long tick,
        int goal, boolean oldSegment, RoutePlanner.Result result)
    {
        recorder.plan(purpose, 0, tick, 7, -1, 0, 3200, 3200, 3200 + goal, 3200, 12, oldSegment, result);
    }

    private static RoutePlanner.Result route(int goal, boolean blocked)
    {
        return new RoutePlanner().plan(new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return !blocked || x != 1 || y != 0; }
            public boolean canTravel(int x, int y, int dx, int dy) { return isLegal(x + dx, y + dy); }
        }, new RoutePlanner.Node(0, 0), new RoutePlanner.Goal()
        {
            public double score(int x, int y, int steps) { return x == goal && y == 0 ? 1 : -1; }
            public int distance(int x, int y) { return MotionMath.distance(x, y, goal, 0); }
        });
    }
}
