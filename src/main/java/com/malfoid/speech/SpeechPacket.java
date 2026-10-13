package com.malfoid.speech;

import com.malfoid.dialogue.DialogueLine;

/** One bounded issued capability. Its lock linearizes first paint against revocation. */
public final class SpeechPacket
{
    private final SceneCandidate candidate;
    private final DialogueLine line;
    private final SpeechGuard.Reservation reservation;
    private final long earliestPaint;
    private final long holdNanos;
    private boolean hardRevoked;
    private boolean continuationAllowed = true;
    private boolean startAllowed = true;
    private PaintReceipt firstPaint;
    private PresentationObservation observation;

    SpeechPacket(SceneCandidate candidate, DialogueLine line, SpeechGuard.Reservation reservation, long earliestPaint)
    {
        this.candidate = candidate;
        this.line = line;
        this.reservation = reservation;
        this.earliestPaint = earliestPaint;
        int points = line.getText().codePointCount(0, line.getText().length());
        this.holdNanos = Math.max(3_000, Math.min(7_000, 2_000 + 60 * points)) * 1_000_000L;
    }

    public String getText() { return line.getText(); }
    public String getLineId() { return line.getId(); }
    public String getEmotion() { return line.getEmotion(); }
    public SceneCandidate getCandidate() { return candidate; }
    SpeechGuard.Reservation getReservation() { return reservation; }

    public synchronized PaintReceipt paint(long now, long tick, long frameId, Runnable draw)
    {
        if (!canPaint(now))
        {
            markUndrawable(frameId);
            return null;
        }
        // Keep the capability lock through the real draw. Revoked-before-claim never draws;
        // claimed-before-revoke settles either the successful draw or the explicit failure.
        draw.run();
        PaintReceipt receipt = null;
        if (firstPaint == null)
        {
            firstPaint = new PaintReceipt(candidate, line.getId(), reservation.getToken(), now, tick);
            receipt = firstPaint;
        }
        observation = new PresentationObservation(candidate.getId(), candidate.getSessionGeneration(),
            candidate.getSceneRevision(), frameId, now, firstPaint.getPaintedAtNanos() + holdNanos + 500_000_000L, true);
        return receipt;
    }

    public synchronized boolean canPaint(long now)
    {
        return !hardRevoked && now - earliestPaint >= 0
            && (firstPaint != null ? now - firstPaint.getPaintedAtNanos() < holdNanos + 500_000_000L
                : continuationAllowed && startAllowed);
    }

    public synchronized float alpha(long now)
    {
        if (firstPaint == null)
        {
            return 1f;
        }
        long afterHold = now - firstPaint.getPaintedAtNanos() - holdNanos;
        return afterHold <= 0 ? 1f : Math.max(0f, 1f - afterHold / 500_000_000f);
    }

    public synchronized void setStartAllowed(boolean allowed) { startAllowed = allowed; }

    public synchronized void revoke(boolean hard)
    {
        continuationAllowed = false;
        hardRevoked |= hard;
        if (hard || firstPaint == null)
        {
            markUndrawable(observation == null ? -1 : observation.getFrameId());
        }
    }

    public synchronized void markUndrawable(long frameId)
    {
        observation = new PresentationObservation(candidate.getId(), candidate.getSessionGeneration(),
            candidate.getSceneRevision(), frameId, observation == null ? 0 : observation.getLastPaintNanos(),
            firstPaint == null ? 0 : firstPaint.getPaintedAtNanos() + holdNanos + 500_000_000L, false);
    }

    public synchronized boolean isPainted() { return firstPaint != null; }
    public synchronized boolean isContinuationAllowed() { return continuationAllowed && !hardRevoked; }
    public synchronized PaintReceipt getFirstPaint() { return firstPaint; }
    public synchronized PresentationObservation getObservation() { return observation; }
    public synchronized boolean isFinished(long now)
    {
        return hardRevoked || firstPaint != null && now - firstPaint.getPaintedAtNanos() >= holdNanos + 500_000_000L;
    }

    public static final class PaintReceipt
    {
        private final SceneCandidate candidate;
        private final String lineId;
        private final long admissionToken;
        private final long paintedAtNanos;
        private final long paintedAtTick;

        private PaintReceipt(SceneCandidate candidate, String lineId, long admissionToken, long paintedAtNanos, long paintedAtTick)
        {
            this.candidate = candidate;
            this.lineId = lineId;
            this.admissionToken = admissionToken;
            this.paintedAtNanos = paintedAtNanos;
            this.paintedAtTick = paintedAtTick;
        }

        public SceneCandidate getCandidate() { return candidate; }
        public String getLineId() { return lineId; }
        public long getAdmissionToken() { return admissionToken; }
        public long getPaintedAtNanos() { return paintedAtNanos; }
        public long getPaintedAtTick() { return paintedAtTick; }
    }

    public static final class PresentationObservation
    {
        private final long candidateId;
        private final long sessionGeneration;
        private final long sceneRevision;
        private final long frameId;
        private final long lastPaintNanos;
        private final long lifetimeEndNanos;
        private final boolean drawable;

        private PresentationObservation(long candidateId, long sessionGeneration, long sceneRevision,
            long frameId, long lastPaintNanos, long lifetimeEndNanos, boolean drawable)
        {
            this.candidateId = candidateId;
            this.sessionGeneration = sessionGeneration;
            this.sceneRevision = sceneRevision;
            this.frameId = frameId;
            this.lastPaintNanos = lastPaintNanos;
            this.lifetimeEndNanos = lifetimeEndNanos;
            this.drawable = drawable;
        }

        public long getCandidateId() { return candidateId; }
        public long getSessionGeneration() { return sessionGeneration; }
        public long getSceneRevision() { return sceneRevision; }
        public long getFrameId() { return frameId; }
        public long getLastPaintNanos() { return lastPaintNanos; }
        public long getLifetimeEndNanos() { return lifetimeEndNanos; }
        public boolean isDrawable() { return drawable; }
    }
}
