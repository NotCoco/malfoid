package com.malfoid.asset;

/**
 * H.6: the emotes of her own. A held one is taken (its clip forwards), kept on its last sample and let go (the clip
 * backwards); a gesture plays once, from her standing pose back to her standing pose. Pure sample selection: no pose
 * interpolation, no facing, no clock of its own. One sample lasts two client cycles, like every clip of hers.
 */
public final class GesturePlayback
{
    public enum Gesture
    {
        COWER("COWER", 12, true), PEER("PEER", 8, true), HANDS_ON_HIPS("HANDS_ON_HIPS", 8, true),
        HAIR_FLICK("HAIR_FLICK", 30, false), STOMP("STOMP", 16, false);

        public final String clip;
        public final int samples;
        public final boolean held;

        Gesture(String clip, int samples, boolean held)
        {
            this.clip = clip; this.samples = samples; this.held = held;
        }
    }

    private enum Phase { NONE, TAKING, HELD, LEAVING, ONCE }

    private Gesture gesture;
    private Phase phase = Phase.NONE;
    private int phaseCycle, fromSample, sample;

    /** Begins one from her standing pose. Refused while another is still in progress. */
    public boolean begin(Gesture next, int clientCycle)
    {
        if (next == null || phase != Phase.NONE) { return false; }
        gesture = next; phase = next.held ? Phase.TAKING : Phase.ONCE;
        phaseCycle = clientCycle; fromSample = sample = 0;
        return true;
    }

    /** A held one starts to let go from wherever it is; a gesture simply runs out. */
    public void release(int clientCycle)
    {
        advance(clientCycle, false);
        if (phase == Phase.TAKING || phase == Phase.HELD)
        {
            phase = Phase.LEAVING; phaseCycle = clientCycle; fromSample = sample;
        }
    }

    /** Walking or running ends anything at once; her Walk or Run takes over on that cycle. */
    public void advance(int clientCycle, boolean moving)
    {
        if (phase == Phase.NONE) { return; }
        if (moving) { reset(); return; }
        int steps = Math.max(0, clientCycle - phaseCycle) / 2;
        switch (phase)
        {
            case TAKING:
                sample = Math.min(gesture.samples - 1, fromSample + steps);
                if (sample == gesture.samples - 1) { phase = Phase.HELD; }
                break;
            case HELD:
                sample = gesture.samples - 1;
                break;
            case LEAVING:
                if (steps > fromSample) { reset(); }
                else { sample = fromSample - steps; }
                break;
            case ONCE:
                if (steps >= gesture.samples) { reset(); }
                else { sample = steps; }
                break;
            default:
                break;
        }
    }

    public void reset()
    {
        gesture = null; phase = Phase.NONE; phaseCycle = fromSample = sample = 0;
    }

    public boolean isActive() { return phase != Phase.NONE; }
    /** Taken or being taken, and not yet let go. */
    public boolean isHolding() { return phase == Phase.TAKING || phase == Phase.HELD; }
    public Gesture getGesture() { return gesture; }
    public String getClip() { return gesture == null ? null : gesture.clip; }
    public int getSample() { return sample; }
}
