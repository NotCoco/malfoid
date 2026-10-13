package com.malfoid.motion;

/** Immutable facts from accepted routes and completed edges; it retains no client objects or render coordinates. */
public final class MotionObservation
{
    public enum Purpose { CATCHUP, ROAM, OTHER, APPROACH_PLAYER }
    public enum Status { MOVING, ARRIVED, BLOCKED, BUDGET_EXHAUSTED, SCENE_UNAVAILABLE, TARGET_LOST, INVALIDATED, RECOVERED }
    public final long requestId, sourceCandidateId, placementGeneration, startedTick, completedTick, lastCompletedEdgeTick, blockedTick;
    public final int worldViewId, plane, startWorldX, startWorldY, goalWorldX, goalWorldY, startDistance, arrivalDistance;
    public final int completedEdges, completedRunEdges, visitedCells, plannedSteps, replans;
    public final int blockedFromWorldX, blockedFromWorldY, blockedToWorldX, blockedToWorldY;
    public final Purpose purpose;
    public final Status status;
    public final RoutePlanner.Status lastPlanStatus;
    public final boolean known, ordinary, legalGoal, fixedGoal, physicalBlock, previouslyLegalEdge, changedEdgeAfterBlock;

    private MotionObservation(Builder b)
    {
        requestId = b.requestId; sourceCandidateId = b.sourceCandidateId;
        placementGeneration = b.placementGeneration; startedTick = b.startedTick;
        completedTick = b.completedTick; lastCompletedEdgeTick = b.lastCompletedEdgeTick; blockedTick = b.blockedTick;
        worldViewId = b.worldViewId; plane = b.plane; startWorldX = b.startWorldX; startWorldY = b.startWorldY;
        goalWorldX = b.goalWorldX; goalWorldY = b.goalWorldY; startDistance = b.startDistance; arrivalDistance = b.arrivalDistance;
        completedEdges = b.completedEdges; completedRunEdges = b.completedRunEdges;
        visitedCells = b.visitedCells; plannedSteps = b.plannedSteps; replans = b.replans;
        blockedFromWorldX = b.blockedFromWorldX; blockedFromWorldY = b.blockedFromWorldY;
        blockedToWorldX = b.blockedToWorldX; blockedToWorldY = b.blockedToWorldY;
        purpose = b.purpose; status = b.status; lastPlanStatus = b.lastPlanStatus;
        known = b.known; ordinary = b.ordinary; legalGoal = b.legalGoal; fixedGoal = b.fixedGoal;
        physicalBlock = b.physicalBlock; previouslyLegalEdge = b.previouslyLegalEdge; changedEdgeAfterBlock = b.changedEdgeAfterBlock;
    }

    public static final class Builder
    {
        public long requestId, sourceCandidateId = -1, placementGeneration, startedTick, completedTick = -1, lastCompletedEdgeTick = -1, blockedTick = -1;
        public int worldViewId, plane, startWorldX, startWorldY, goalWorldX, goalWorldY, startDistance, arrivalDistance = -1;
        public int completedEdges, completedRunEdges, visitedCells, plannedSteps, replans;
        public int blockedFromWorldX, blockedFromWorldY, blockedToWorldX, blockedToWorldY;
        public Purpose purpose = Purpose.OTHER;
        public Status status = Status.MOVING;
        public RoutePlanner.Status lastPlanStatus;
        public boolean known = true, ordinary, legalGoal, fixedGoal = true, physicalBlock, previouslyLegalEdge, changedEdgeAfterBlock;
        public MotionObservation build() { return new MotionObservation(this); }
    }
}
