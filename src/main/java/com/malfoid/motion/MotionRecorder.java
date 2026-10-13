package com.malfoid.motion;

import java.util.ArrayDeque;

/** Bounded active, completion and recent genuine arrival facts, with one scalar failed-edge record. */
public final class MotionRecorder
{
    private static final int RECENT_LIMIT = 24;
    private final ArrayDeque<MotionObservation> recent = new ArrayDeque<>();
    private MotionObservation.Builder active;
    private MotionObservation current, completion;
    private long nextRequestId, segmentRequestId;
    private int intentToken;

    public void plan(MotionObservation.Purpose purpose, int intentToken, long tick, long generation, int viewId, int plane,
        int startX, int startY, int goalX, int goalY, int startDistance, boolean committedOldSegment,
        RoutePlanner.Result result)
    {
        plan(purpose, -1, intentToken, tick, generation, viewId, plane, startX, startY, goalX, goalY, startDistance,
            committedOldSegment, result);
    }

    public void plan(MotionObservation.Purpose purpose, long sourceCandidateId, int intentToken, long tick, long generation,
        int viewId, int plane, int startX, int startY, int goalX, int goalY, int startDistance,
        boolean committedOldSegment, RoutePlanner.Result result)
    {
        boolean continuing = active != null && open() && active.placementGeneration == generation && active.worldViewId == viewId
            && active.plane == plane && this.intentToken == intentToken && active.sourceCandidateId == sourceCandidateId
            && active.purpose == purpose
            && (purpose == MotionObservation.Purpose.CATCHUP || active.goalWorldX == goalX && active.goalWorldY == goalY);
        if (!continuing)
        {
            if (result.steps.isEmpty())
            {
                if (active != null && active.status == MotionObservation.Status.ARRIVED
                    && result.status == RoutePlanner.Status.AT_GOAL && active.goalWorldX == goalX && active.goalWorldY == goalY) return;
                if (active != null && open())
                {
                    active.legalGoal = false;
                    active.lastPlanStatus = result.status;
                    cancel(result.status == RoutePlanner.Status.AT_GOAL ? MotionObservation.Status.INVALIDATED : status(result.status));
                }
                else if (active != null && active.status == MotionObservation.Status.ARRIVED)
                    cancel(MotionObservation.Status.INVALIDATED);
                return;
            }
            active = new MotionObservation.Builder();
            active.requestId = ++nextRequestId;
            active.sourceCandidateId = sourceCandidateId;
            active.purpose = purpose;
            active.ordinary = purpose != MotionObservation.Purpose.OTHER;
            active.placementGeneration = generation;
            active.worldViewId = viewId;
            active.plane = plane;
            active.startedTick = tick;
            active.startWorldX = startX;
            active.startWorldY = startY;
            active.startDistance = startDistance;
            active.goalWorldX = goalX;
            active.goalWorldY = goalY;
            active.known = purpose != MotionObservation.Purpose.ROAM || !committedOldSegment;
            this.intentToken = intentToken;
        }
        else
        {
            active.replans = Math.min(24, active.replans + 1);
            if (active.physicalBlock && result.status != RoutePlanner.Status.ROUTE && result.status != RoutePlanner.Status.AT_GOAL)
                active.known = false;
            if (active.goalWorldX != goalX || active.goalWorldY != goalY) active.fixedGoal = false;
            active.goalWorldX = goalX;
            active.goalWorldY = goalY;
        }
        active.legalGoal = result.status == RoutePlanner.Status.ROUTE || result.status == RoutePlanner.Status.AT_GOAL;
        active.lastPlanStatus = result.status;
        active.visitedCells = result.visited;
        active.plannedSteps = result.steps.size();
        active.status = status(result.status);
        publish();
    }

    public void segmentStarted() { segmentRequestId = active == null ? 0 : active.requestId; }

    public void completedEdge(long tick, int fromX, int fromY, int toX, int toY, boolean run)
    {
        if (active == null || !open() || segmentRequestId != active.requestId) return;
        if (active.completedEdges == 24) active.known = false;
        active.completedEdges = Math.min(24, active.completedEdges + 1);
        if (run) active.completedRunEdges = Math.min(24, active.completedRunEdges + 1);
        active.lastCompletedEdgeTick = tick;
        if (active.physicalBlock && (fromX != active.blockedFromWorldX || fromY != active.blockedFromWorldY
            || toX != active.blockedToWorldX || toY != active.blockedToWorldY)) active.changedEdgeAfterBlock = true;
        publish();
    }

    public void blocked(long tick, int fromX, int fromY, int toX, int toY)
    {
        if (active == null || !open()) return;
        active.status = MotionObservation.Status.BLOCKED;
        active.physicalBlock = active.previouslyLegalEdge = true;
        active.blockedTick = tick;
        active.blockedFromWorldX = fromX; active.blockedFromWorldY = fromY;
        active.blockedToWorldX = toX; active.blockedToWorldY = toY;
        active.changedEdgeAfterBlock = false;
        publish();
    }

    public void arrive(long tick, int x, int y, int separation, boolean legal)
    {
        if (active == null || !open() || active.completedEdges == 0 || !active.legalGoal
            || x != active.goalWorldX || y != active.goalWorldY) return;
        active.status = legal ? MotionObservation.Status.ARRIVED : MotionObservation.Status.TARGET_LOST;
        active.completedTick = tick;
        active.arrivalDistance = separation;
        publish();
        if (legal)
        {
            completion = current;
            if (current.known && current.ordinary)
            {
                recent.addLast(current);
                while (recent.size() > RECENT_LIMIT) recent.removeFirst();
            }
        }
    }

    public void cancel(MotionObservation.Status status)
    {
        if (active != null) { active.status = status; publish(); }
    }

    public void commit(long tick)
    {
        pruneRecent(tick);
        if (active != null && active.blockedTick >= 0 && tick - active.blockedTick >= 20)
        {
            active.physicalBlock = active.previouslyLegalEdge = active.changedEdgeAfterBlock = false;
            active.blockedTick = -1;
            publish();
        }
    }

    public void clear() { active = null; current = completion = null; segmentRequestId = 0; recent.clear(); }
    public MotionObservation current() { return current; }
    public MotionObservation completion() { return completion; }
    public void clearRecentCompletions() { recent.clear(); }
    public MotionObservation recentCompletion(long requestId, long tick)
    {
        pruneRecent(tick);
        for (MotionObservation receipt : recent)
        {
            if (receipt.requestId == requestId && receipt.completedTick <= tick) return receipt;
        }
        return null;
    }
    private void pruneRecent(long tick)
    {
        while (!recent.isEmpty() && tick - recent.peekFirst().completedTick >= RECENT_LIMIT) recent.removeFirst();
    }
    private static MotionObservation.Status status(RoutePlanner.Status status)
    {
        return status == RoutePlanner.Status.BLOCKED ? MotionObservation.Status.BLOCKED
            : status == RoutePlanner.Status.BUDGET_EXHAUSTED ? MotionObservation.Status.BUDGET_EXHAUSTED
            : status == RoutePlanner.Status.SCENE_UNAVAILABLE ? MotionObservation.Status.SCENE_UNAVAILABLE
            : MotionObservation.Status.MOVING;
    }
    private boolean open()
    {
        return active.status == MotionObservation.Status.MOVING || active.status == MotionObservation.Status.BLOCKED
            || active.status == MotionObservation.Status.BUDGET_EXHAUSTED || active.status == MotionObservation.Status.SCENE_UNAVAILABLE;
    }
    private void publish() { current = active.build(); }
}
