package com.malfoid.asset;

import org.junit.Test;
import static com.malfoid.asset.PosturePlayback.Posture.*;
import static org.junit.Assert.*;

public class PosturePlaybackTest
{
    @Test public void threeClipPlaybackIgnoresPosturesAndKeepsLocomotionTiming()
    {
        PosturePlayback playback = new PosturePlayback(false);
        playback.request(IMPATIENT, 0);
        for (int cycle = 0; cycle < 120; cycle++) assertPose(playback, cycle, "IDLE", cycle / 2 % 50);
        playback.advance(120, true, false);
        assertEquals("WALK", playback.getClip()); assertEquals(0, playback.getSample());
        playback.advance(151, true, false); assertEquals(15, playback.getSample());
        playback.advance(152, true, true);
        assertEquals("RUN", playback.getClip()); assertEquals(0, playback.getSample());
        playback.advance(193, true, true); assertEquals(0, playback.getSample());
        playback.advance(194, false, false);
        assertEquals("IDLE", playback.getClip()); assertEquals(0, playback.getSample());
        assertEquals(STANDING, playback.getPosture());
        assertFalse(playback.consumeImpatientLoopStart()); assertFalse(playback.consumeImpatientEnd());
    }

    @Test public void foldingHoldsNineAndUnfoldsNineToZeroAtTwoCyclesEach()
    {
        PosturePlayback playback = new PosturePlayback(true);
        playback.request(ARMS_FOLDED, 0);
        for (int cycle = 0; cycle < 20; cycle++) assertPose(playback, cycle, "CROSS_ARMS", cycle / 2);
        for (int cycle = 20; cycle < 30; cycle++) assertPose(playback, cycle, "CROSS_ARMS", 9);
        assertEquals(ARMS_FOLDED, playback.getPosture()); assertFalse(playback.isTransitioning());
        playback.request(STANDING, 30);
        for (int cycle = 30; cycle < 50; cycle++) assertPose(playback, cycle, "CROSS_ARMS", 9 - (cycle - 30) / 2);
        assertPose(playback, 50, "IDLE", 0);
        assertEquals(STANDING, playback.getPosture());
    }

    @Test public void foldedToLoopIsImmediateAndLoopToFoldedWaitsItsBoundary()
    {
        PosturePlayback playback = new PosturePlayback(true);
        playback.request(ARMS_FOLDED, 0);
        assertPose(playback, 20, "CROSS_ARMS", 9);
        playback.request(IMPATIENT, 30);
        assertEquals("IMPATIENT", playback.getClip()); assertEquals(0, playback.getSample());
        assertTrue(playback.consumeImpatientLoopStart()); assertFalse(playback.consumeImpatientLoopStart());
        assertEquals(30, playback.getImpatientLoopStartCycle());
        playback.request(ARMS_FOLDED, 35);
        for (int cycle = 35; cycle < 70; cycle++) assertPose(playback, cycle, "IMPATIENT", (cycle - 30) / 2);
        assertPose(playback, 70, "CROSS_ARMS", 9);
        assertEquals(ARMS_FOLDED, playback.getPosture());
        assertTrue(playback.consumeImpatientEnd()); assertEquals(70, playback.getImpatientEndCycle());
    }

    @Test public void standingCancellationWaitsTheLoopBoundaryBeforeAuthoredReverse()
    {
        PosturePlayback playback = new PosturePlayback(true);
        playback.request(IMPATIENT, 60);
        assertPose(playback, 80, "IMPATIENT", 0);
        assertTrue(playback.consumeImpatientLoopStart());
        playback.endImpatient(125);
        for (int cycle = 125; cycle < 160; cycle++)
        {
            assertPose(playback, cycle, "IMPATIENT", (cycle - 80) / 2 % 20);
            assertFalse(playback.consumeImpatientEnd());
        }
        for (int cycle = 160; cycle < 180; cycle++) assertPose(playback, cycle, "CROSS_ARMS", 9 - (cycle - 160) / 2);
        assertTrue(playback.consumeImpatientEnd()); assertFalse(playback.consumeImpatientEnd());
        assertEquals(160, playback.getImpatientEndCycle());
        assertPose(playback, 180, "IDLE", 0);
    }

    @Test public void walkingAndRunningCutEveryEmotePhaseOnThatCycle()
    {
        for (boolean run : new boolean[]{false, true})
        {
            for (int cut : new int[]{7, 20, 27, 63})
            {
                PosturePlayback playback = new PosturePlayback(true);
                playback.request(cut == 20 ? ARMS_FOLDED : IMPATIENT, 0);
                playback.advance(cut - 1, false, false);
                playback.advance(cut, true, run);
                assertEquals(run ? "RUN" : "WALK", playback.getClip());
                assertEquals(0, playback.getSample()); assertEquals(STANDING, playback.getPosture());
                playback.advance(cut + 2, true, run); assertEquals(1, playback.getSample());
                playback.advance(cut + 3, false, false);
                assertEquals("IDLE", playback.getClip()); assertEquals(0, playback.getSample());
                assertEquals(cut != 20, playback.consumeImpatientEnd());
                assertFalse(playback.consumeImpatientEnd());
            }
        }
    }

    @Test public void cancellationBeforeLoopNeverEmitsALoopStartAndEndsOnce()
    {
        PosturePlayback playback = new PosturePlayback(true);
        playback.request(IMPATIENT, 0);
        playback.endImpatient(7);
        assertEquals("CROSS_ARMS", playback.getClip()); assertEquals(3, playback.getSample());
        assertTrue(playback.consumeImpatientEnd()); assertEquals(7, playback.getImpatientEndCycle());
        playback.endImpatient(8); assertFalse(playback.consumeImpatientEnd());
        assertPose(playback, 15, "IDLE", 0); assertFalse(playback.consumeImpatientLoopStart());
    }

    @Test public void allChosenDurationsReleaseAtABoundaryBetweenEightAndSixteenTicks()
    {
        for (int ticks = 8; ticks <= 16; ticks++)
        {
            PosturePlayback playback = new PosturePlayback(true);
            playback.request(IMPATIENT, 17);
            playback.advance(37, false, false);
            int deadline = 37 + ticks * 30;
            playback.request(STANDING, deadline);
            int end = 37 + (ticks * 30 + 39) / 40 * 40;
            if (end > deadline) assertEquals("IMPATIENT", playback.getClip());
            assertPose(playback, end, "CROSS_ARMS", 9);
            assertEquals(end, playback.getImpatientEndCycle());
            assertTrue(end - 37 >= 8 * 30); assertTrue(end - 37 <= 16 * 30);
            assertPose(playback, end + 20, "IDLE", 0);
        }
    }

    private static void assertPose(PosturePlayback playback, int cycle, String clip, int sample)
    {
        playback.advance(cycle, false, false);
        assertEquals("clip at cycle " + cycle, clip, playback.getClip());
        assertEquals("sample at cycle " + cycle, sample, playback.getSample());
    }
}
