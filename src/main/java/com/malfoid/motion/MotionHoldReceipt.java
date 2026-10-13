package com.malfoid.motion;

/** An exact admitted scene's genuine earlier arrival and its current legal stationary hold. */
public final class MotionHoldReceipt
{
    public enum Mode { HOLD, NORMAL_RESUME }
    public final long candidateId, requestId, placementGeneration, admittedTick, heldTick;
    public final int worldViewId, plane, worldX, worldY;
    public final MotionObservation completion;
    public final boolean legalStanding;
    public final Mode mode;

    public MotionHoldReceipt(long candidateId, MotionObservation completion, long admittedTick, long heldTick,
        long placementGeneration, int worldViewId, int plane, int worldX, int worldY, boolean legalStanding)
    {
        this(candidateId, completion, admittedTick, heldTick, placementGeneration, worldViewId, plane, worldX, worldY,
            legalStanding, Mode.HOLD);
    }

    public MotionHoldReceipt(long candidateId, MotionObservation completion, long admittedTick, long heldTick,
        long placementGeneration, int worldViewId, int plane, int worldX, int worldY, boolean legalStanding, Mode mode)
    {
        this.candidateId = candidateId;
        this.completion = completion;
        requestId = completion == null ? -1 : completion.requestId;
        this.admittedTick = admittedTick;
        this.heldTick = heldTick;
        this.placementGeneration = placementGeneration;
        this.worldViewId = worldViewId;
        this.plane = plane;
        this.worldX = worldX;
        this.worldY = worldY;
        this.legalStanding = legalStanding;
        this.mode = mode;
    }
}
