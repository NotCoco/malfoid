package com.malfoid.asset;

/** Pure playback of the authored clips, with no pose interpolation or facing changes. */
public final class PosturePlayback
{
    public enum Posture { STANDING, ARMS_FOLDED, IMPATIENT }
    private enum Phase { STANDING, FOLDING, FOLDED, IMPATIENT, UNFOLDING }

    private final boolean emotes;
    private Posture requested = Posture.STANDING;
    private Phase phase = Phase.STANDING;
    private int phaseCycle, lastCycle = Integer.MIN_VALUE, transitionSample;
    private int loopReleaseCycle = Integer.MIN_VALUE;
    private String clip = "IDLE";
    private int sample;
    private boolean moving, running, loopStarted, impatientEnded;
    private int loopStartCycle = Integer.MIN_VALUE, impatientEndCycle = Integer.MIN_VALUE;

    public PosturePlayback(boolean emotes)
    {
        this.emotes = emotes;
    }

    public void request(Posture posture, int clientCycle)
    {
        advance(clientCycle, moving, running);
        if (!emotes || moving) return;
        requested = posture;
        if (phase == Phase.IMPATIENT)
        {
            int elapsed = Math.max(0, clientCycle - phaseCycle);
            loopReleaseCycle = posture == Posture.IMPATIENT ? Integer.MIN_VALUE
                : phaseCycle + Math.max(1, (elapsed + 39) / 40) * 40;
        }
        if (phase == Phase.STANDING && posture != Posture.STANDING)
        {
            begin(Phase.FOLDING, clientCycle, 0);
        }
        else if (phase == Phase.FOLDING && posture == Posture.STANDING)
        {
            begin(Phase.UNFOLDING, clientCycle, sample);
        }
        else if (phase == Phase.UNFOLDING && posture != Posture.STANDING)
        {
            begin(Phase.FOLDING, clientCycle, sample);
        }
        else if (phase == Phase.FOLDED)
        {
            if (posture == Posture.STANDING) begin(Phase.UNFOLDING, clientCycle, 9);
            else if (posture == Posture.IMPATIENT) beginLoop(clientCycle);
        }
        advance(clientCycle, moving, running);
    }

    /** Standing releases leave the loop at its next boundary; locomotion cuts in advance(). */
    public void endImpatient(int clientCycle)
    {
        advance(clientCycle, moving, running);
        if (requested != Posture.IMPATIENT && phase != Phase.IMPATIENT) return;
        if (phase == Phase.FOLDING)
        {
            endLoop(clientCycle);
        }
        request(Posture.STANDING, clientCycle);
    }

    public void advance(int clientCycle, boolean moving, boolean running)
    {
        if (lastCycle == Integer.MIN_VALUE) phaseCycle = clientCycle;
        lastCycle = clientCycle;
        boolean changedMotion = this.moving != moving || moving && this.running != running;
        this.moving = moving;
        this.running = running;
        if (moving)
        {
            if (phase == Phase.IMPATIENT || requested == Posture.IMPATIENT) endLoop(clientCycle);
            requested = Posture.STANDING;
            phase = Phase.STANDING;
            if (changedMotion) phaseCycle = clientCycle;
            select(clientCycle);
            return;
        }
        if (changedMotion) phaseCycle = clientCycle;
        int elapsed = Math.max(0, clientCycle - phaseCycle);
        if (phase == Phase.FOLDING && elapsed >= (10 - transitionSample) * 2)
        {
            int finished = phaseCycle + (10 - transitionSample) * 2;
            if (requested == Posture.IMPATIENT) beginLoop(clientCycle);
            else begin(Phase.FOLDED, finished, 9);
        }
        if (phase == Phase.IMPATIENT && requested != Posture.IMPATIENT
            && loopReleaseCycle != Integer.MIN_VALUE && clientCycle >= loopReleaseCycle)
        {
            endLoop(loopReleaseCycle);
            begin(requested == Posture.STANDING ? Phase.UNFOLDING : Phase.FOLDED, loopReleaseCycle, 9);
        }
        if (phase == Phase.UNFOLDING && clientCycle - phaseCycle >= (transitionSample + 1) * 2)
        {
            begin(Phase.STANDING, phaseCycle + (transitionSample + 1) * 2, 0);
        }
        select(clientCycle);
    }

    public void reset(int clientCycle)
    {
        requested = Posture.STANDING;
        phase = Phase.STANDING;
        phaseCycle = lastCycle = clientCycle;
        transitionSample = sample = 0;
        moving = running = loopStarted = impatientEnded = false;
        loopReleaseCycle = Integer.MIN_VALUE;
        clip = "IDLE";
    }

    public String getClip() { return clip; }
    public int getSample() { return sample; }
    public Posture getPosture()
    {
        return phase == Phase.IMPATIENT ? Posture.IMPATIENT
            : phase == Phase.FOLDED || phase == Phase.UNFOLDING ? Posture.ARMS_FOLDED : Posture.STANDING;
    }
    public boolean isTransitioning() { return phase == Phase.FOLDING || phase == Phase.UNFOLDING; }
    public int getImpatientLoopStartCycle() { return loopStartCycle; }
    public int getImpatientEndCycle() { return impatientEndCycle; }
    public boolean consumeImpatientLoopStart()
    {
        boolean value = loopStarted; loopStarted = false; return value;
    }
    public boolean consumeImpatientEnd()
    {
        boolean value = impatientEnded; impatientEnded = false; return value;
    }

    private void begin(Phase next, int clientCycle, int firstSample)
    {
        phase = next;
        phaseCycle = clientCycle;
        transitionSample = firstSample;
        loopReleaseCycle = Integer.MIN_VALUE;
    }

    private void beginLoop(int clientCycle)
    {
        begin(Phase.IMPATIENT, clientCycle, 0);
        loopStarted = true;
        loopStartCycle = clientCycle;
    }

    private void endLoop(int clientCycle)
    {
        impatientEnded = true;
        impatientEndCycle = clientCycle;
    }

    private void select(int clientCycle)
    {
        int elapsed = Math.max(0, clientCycle - phaseCycle);
        if (moving)
        {
            clip = running ? "RUN" : "WALK";
            sample = elapsed / 2 % (running ? 20 : 30);
        }
        else if (phase == Phase.FOLDING || phase == Phase.UNFOLDING || phase == Phase.FOLDED)
        {
            clip = "CROSS_ARMS";
            sample = phase == Phase.FOLDED ? 9 : phase == Phase.FOLDING
                ? Math.min(9, transitionSample + elapsed / 2) : Math.max(0, transitionSample - elapsed / 2);
        }
        else
        {
            clip = phase == Phase.IMPATIENT ? "IMPATIENT" : "IDLE";
            sample = elapsed / 2 % (phase == Phase.IMPATIENT ? 20 : 50);
        }
    }
}
