package com.malfoid.asset;

import com.malfoid.asset.GesturePlayback.Gesture;
import org.junit.Test;
import static org.junit.Assert.*;

/** Master spec H.6: the emotes of her own. One sample lasts two client cycles. */
public class GesturePlaybackTest
{
    @Test public void aHeldEmoteIsTakenKeptOnItsLastSampleAndLetGoBackwards()
    {
        GesturePlayback playback = new GesturePlayback();
        assertFalse(playback.isActive());
        assertTrue(playback.begin(Gesture.PEER, 100));
        for (int cycle = 100; cycle <= 113; cycle++)
        {
            playback.advance(cycle, false);
            assertEquals("PEER", playback.getClip());
            assertEquals((cycle - 100) / 2, playback.getSample());
            assertTrue(playback.isHolding());
        }
        playback.advance(114, false);
        assertEquals(7, playback.getSample());
        playback.advance(900, false);
        assertEquals("Held for as long as it is wanted", 7, playback.getSample());
        assertTrue(playback.isActive());
        playback.release(900);
        assertFalse(playback.isHolding());
        for (int cycle = 900; cycle <= 915; cycle++)
        {
            playback.advance(cycle, false);
            assertTrue(playback.isActive());
            assertEquals(7 - (cycle - 900) / 2, playback.getSample());
        }
        playback.advance(916, false);
        assertFalse("After its first sample she is standing again", playback.isActive());
        assertNull(playback.getClip());
    }

    @Test public void lettingGoWhileItIsStillBeingTakenTurnsBackFromThatSample()
    {
        GesturePlayback playback = new GesturePlayback();
        playback.begin(Gesture.HANDS_ON_HIPS, 0);
        playback.advance(6, false);
        assertEquals(3, playback.getSample());
        playback.release(6);
        playback.advance(8, false);
        assertEquals(2, playback.getSample());
        playback.advance(12, false);
        assertEquals(0, playback.getSample());
        playback.advance(14, false);
        assertFalse(playback.isActive());
    }

    @Test public void aGesturePlaysOnceThroughEverySampleAndEnds()
    {
        for (Gesture gesture : new Gesture[] {Gesture.HAIR_FLICK, Gesture.STOMP})
        {
            GesturePlayback playback = new GesturePlayback();
            assertTrue(playback.begin(gesture, 40));
            assertFalse("A gesture is never held", playback.isHolding());
            for (int sample = 0; sample < gesture.samples; sample++)
            {
                playback.advance(40 + sample * 2, false);
                assertEquals(gesture.clip, playback.getClip());
                assertEquals(sample, playback.getSample());
                playback.advance(41 + sample * 2, false);
                assertEquals(sample, playback.getSample());
            }
            playback.release(40 + gesture.samples);
            playback.advance(40 + gesture.samples * 2, false);
            assertFalse(playback.isActive());
        }
        assertEquals(30, Gesture.HAIR_FLICK.samples);
        assertEquals(16, Gesture.STOMP.samples);
    }

    @Test public void anotherIsRefusedUntilTheFirstHasEnded()
    {
        GesturePlayback playback = new GesturePlayback();
        assertTrue(playback.begin(Gesture.PEER, 0));
        assertFalse(playback.begin(Gesture.STOMP, 4));
        assertFalse(playback.begin(null, 4));
        playback.advance(4, false);
        assertEquals(Gesture.PEER, playback.getGesture());
        playback.release(20);
        playback.advance(36, false);
        assertFalse(playback.isActive());
        assertTrue(playback.begin(Gesture.STOMP, 36));
    }

    @Test public void havingToWalkEndsItOnThatCycle()
    {
        GesturePlayback playback = new GesturePlayback();
        playback.begin(Gesture.COWER, 0);
        playback.advance(30, false);
        assertTrue(playback.isActive());
        playback.advance(31, true);
        assertFalse(playback.isActive());
        assertNull(playback.getGesture());
        playback.begin(Gesture.HAIR_FLICK, 40);
        playback.advance(50, true);
        assertFalse(playback.isActive());
    }

    @Test public void theClipsAreTheOnesTheModelCarries()
    {
        assertEquals(5, Gesture.values().length);
        int samples = 0;
        for (Gesture gesture : Gesture.values())
        {
            assertEquals(gesture.name(), gesture.clip);
            samples += gesture.samples;
        }
        assertTrue(Gesture.COWER.held && Gesture.PEER.held && Gesture.HANDS_ON_HIPS.held);
        assertFalse(Gesture.HAIR_FLICK.held || Gesture.STOMP.held);
        assertEquals("Cowering takes longer to reach than the other held poses", 12, Gesture.COWER.samples);
        assertEquals("Seventy-four samples beside the 130 of the first five clips", 74, samples);
        assertEquals(204, NativeAsset.MAX_SAMPLES);
    }
}
