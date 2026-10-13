package com.malfoid.speech;

/** An OWN execution cancellation, copied only after its issued capability is revoked. */
public final class SpeechCancellationReceipt
{
    public enum Reason { DEPENDENT_OUTFIT_REVISION_CHANGED, INSPECTION_TARGET_LOST }

    private final SceneCandidate candidate;
    private final Reason reason;
    private final long cancelledAtTick;
    private final long issuedPacketToken;
    private final long executionId;
    private final boolean genuinePainted;
    private final boolean prePaintAttempt;

    SpeechCancellationReceipt(SceneCandidate candidate, Reason reason, long cancelledAtTick,
        long executionId, long issuedPacketToken, boolean genuinePainted, boolean prePaintAttempt)
    {
        this.candidate = candidate;
        this.reason = reason;
        this.cancelledAtTick = cancelledAtTick;
        this.issuedPacketToken = issuedPacketToken;
        this.executionId = executionId;
        this.genuinePainted = genuinePainted;
        this.prePaintAttempt = prePaintAttempt;
    }

    public SceneCandidate getCandidate() { return candidate; }
    public long getCandidateId() { return candidate.getId(); }
    public String getScenarioId() { return candidate.getScenarioId(); }
    public String getCausalToken() { return candidate.getCausalToken(); }
    public long getSessionGeneration() { return candidate.getSessionGeneration(); }
    public long getSceneRevision() { return candidate.getSceneRevision(); }
    public long getCancelledAtTick() { return cancelledAtTick; }
    public Reason getReason() { return reason; }
    public long getIssuedPacketToken() { return issuedPacketToken; }
    public long getExecutionId() { return executionId; }
    public boolean isGenuinePainted() { return genuinePainted; }
    public boolean isPrePaintAttempt() { return prePaintAttempt; }
}
