package com.malfoid.motion;

/** Latest immutable native recovery fact; it retains no client objects or route history. */
public final class RecoveryObservation
{
    public enum Cause { OFF_SCREEN, P15_STUCK }
    public enum Disposition
    {
        SEARCH_FAILED, ORIGIN_VISIBLE, ORIGIN_UNKNOWN, DESTINATION_INVALID, HEIGHT_UNAVAILABLE, RELOCATED
    }

    public final long observationId, episodeId, episodeStartedTick, committedTick, sceneRevision;
    public final long placementGenerationBefore, placementGenerationAfter, planNotBefore, retryAt;
    public final int worldViewId, plane, baseX, baseY, sizeX, sizeY, separation;
    public final int originLocalX, originLocalY, originWorldX, originWorldY, destinationWorldX, destinationWorldY;
    public final int placedLocalX, placedLocalY, drawZ, orientation;
    public final int offScreenTicksBefore, offScreenTicksAfter, blockedTicksBefore, blockedTicksAfter;
    public final int ineffectivePlansBefore, ineffectivePlansAfter, visitedCells, plannedSteps;
    public final Cause cause;
    public final Disposition disposition;
    public final RoutePlanner.Status plannerStatus;
    public final WholeBoundsVisibility.Visibility decisionVisibility, originRecheckVisibility;
    public final boolean searchLimited, destinationKnown, destinationRechecked, destinationLegal;
    public final boolean originRechecked, heightRechecked, heightValid;

    private RecoveryObservation(Builder b)
    {
        observationId = b.observationId; episodeId = b.episodeId; episodeStartedTick = b.episodeStartedTick;
        committedTick = b.committedTick; sceneRevision = b.sceneRevision;
        placementGenerationBefore = b.placementGenerationBefore; placementGenerationAfter = b.placementGenerationAfter;
        planNotBefore = b.planNotBefore; retryAt = b.retryAt;
        worldViewId = b.worldViewId; plane = b.plane; baseX = b.baseX; baseY = b.baseY; sizeX = b.sizeX; sizeY = b.sizeY;
        separation = b.separation; originLocalX = b.originLocalX; originLocalY = b.originLocalY;
        originWorldX = b.originWorldX; originWorldY = b.originWorldY;
        destinationWorldX = b.destinationWorldX; destinationWorldY = b.destinationWorldY;
        placedLocalX = b.placedLocalX; placedLocalY = b.placedLocalY; drawZ = b.drawZ; orientation = b.orientation;
        offScreenTicksBefore = b.offScreenTicksBefore; offScreenTicksAfter = b.offScreenTicksAfter;
        blockedTicksBefore = b.blockedTicksBefore; blockedTicksAfter = b.blockedTicksAfter;
        ineffectivePlansBefore = b.ineffectivePlansBefore; ineffectivePlansAfter = b.ineffectivePlansAfter;
        visitedCells = b.visitedCells; plannedSteps = b.plannedSteps;
        cause = b.cause; disposition = b.disposition; plannerStatus = b.plannerStatus;
        decisionVisibility = b.decisionVisibility; originRecheckVisibility = b.originRecheckVisibility;
        searchLimited = b.searchLimited; destinationKnown = b.destinationKnown;
        destinationRechecked = b.destinationRechecked; destinationLegal = b.destinationLegal;
        originRechecked = b.originRechecked; heightRechecked = b.heightRechecked; heightValid = b.heightValid;
    }

    public static final class Builder
    {
        public long observationId, episodeId, episodeStartedTick = -1, committedTick, sceneRevision;
        public long placementGenerationBefore, placementGenerationAfter, planNotBefore, retryAt;
        public int worldViewId, plane, baseX, baseY, sizeX, sizeY, separation;
        public int originLocalX, originLocalY, originWorldX, originWorldY, destinationWorldX, destinationWorldY;
        public int placedLocalX, placedLocalY, drawZ, orientation;
        public int offScreenTicksBefore, offScreenTicksAfter, blockedTicksBefore, blockedTicksAfter;
        public int ineffectivePlansBefore, ineffectivePlansAfter, visitedCells, plannedSteps;
        public Cause cause;
        public Disposition disposition;
        /** Null means this fact was a visibility-only reset, with no search. */
        public RoutePlanner.Status plannerStatus;
        public WholeBoundsVisibility.Visibility decisionVisibility = WholeBoundsVisibility.Visibility.UNKNOWN;
        public WholeBoundsVisibility.Visibility originRecheckVisibility = WholeBoundsVisibility.Visibility.UNKNOWN;
        public boolean searchLimited, destinationKnown, destinationRechecked, destinationLegal;
        public boolean originRechecked, heightRechecked, heightValid;
        public RecoveryObservation build() { return new RecoveryObservation(this); }
    }
}
