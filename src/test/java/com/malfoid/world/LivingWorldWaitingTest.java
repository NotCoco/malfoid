package com.malfoid.world;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldWaitingTest
{
    @Test public void aFactNeedsOneHundredFiftyCommittedTicksAndOneChanceDraw() throws Exception
    {
        for (int chance : new int[]{0, 49, 50, 99})
        {
            Draws random = new Draws(chance, 8); LivingWorldWaiting waiting = new LivingWorldWaiting(random);
            for (int tick = 1; tick < 150; tick++) { assertEquals(0, observe(waiting, tick, true)); }
            assertEquals(chance < 50 ? 16 : 0, observe(waiting, 150, true));
            assertEquals(chance < 50 ? 2 : 1, random.draws);
            assertEquals(0, observe(waiting, 151, true));
        }
    }

    @Test public void movementAndUnknownGapsRestartTheCommittedCountButAnIneligibleRestCanFinishLater()
    {
        LivingWorldWaiting waiting = new LivingWorldWaiting(new Draws(0, 0));
        for (int tick = 1; tick <= 150; tick++) { assertEquals(0, observe(waiting, tick, false)); }
        assertEquals(8, observe(waiting, 151, true)); waiting.requestRejected();
        for (int tick = 152; tick <= 250; tick++) { observe(waiting, tick, false); }
        assertEquals(0, waiting.observe(251, 1, 1, false, 10, 20, 0, true));
        for (int tick = 252; tick < 401; tick++) { assertEquals(0, observe(waiting, tick, true)); }
        assertEquals(8, observe(waiting, 401, true));
        assertFalse(waiting.playerStillOnTile(11, 20, 0));
    }

    @Test public void actualLoopStartCountsOnceAndTheActualEndOwnsTheFourHundredTickCooldown()
    {
        LivingWorldWaiting waiting = new LivingWorldWaiting(new Draws(0, 0));
        for (int tick = 1; tick <= 150; tick++) { observe(waiting, tick, true); }
        assertTrue(waiting.isActive()); assertEquals(0, waiting.getRecentEmoteCount());
        assertEquals(1, waiting.onLoopStarted(150)); assertEquals(0, waiting.onLoopStarted(150));
        waiting.onEmoteEnded(159); assertFalse(waiting.isActive()); assertEquals(159, waiting.getLastEndTick());
        for (int tick = 151; tick < 559; tick++) { assertEquals(0, observe(waiting, tick, true)); }
        assertEquals(8, observe(waiting, 559, true));
        assertEquals(2, waiting.onLoopStarted(559)); waiting.onEmoteEnded(568);
        for (int tick = 560; tick <= 968; tick++) { observe(waiting, tick, true); }
        assertEquals(3, waiting.onLoopStarted(968));
    }

    @Test public void recentLoopHistoryIsBoundedAndOldEntriesExpire()
    {
        LivingWorldWaiting waiting = new LivingWorldWaiting(new Draws(0, 0));
        long tick = 1;
        for (int count = 1; count <= 15; count++)
        {
            int request = 0;
            while (request == 0) { request = observe(waiting, tick++, true); }
            int recent = waiting.onLoopStarted(tick - 1);
            assertTrue(recent <= LivingWorldWaiting.MAX_RECENT_EMOTES);
            waiting.onEmoteEnded(tick + 8);
        }
        assertTrue(waiting.getRecentEmoteCount() <= 8);
        waiting.resetSession(); assertEquals(0, waiting.getRecentEmoteCount()); assertFalse(waiting.isActive());
    }

    private static int observe(LivingWorldWaiting waiting, long tick, boolean eligible)
    { return waiting.observe(tick, 1, 1, true, 10, 20, 0, eligible); }
    private static final class Draws extends Random
    {
        final int chance, duration; int draws;
        Draws(int chance, int duration) { this.chance = chance; this.duration = duration; }
        @Override public int nextInt(int bound) { draws++; return bound == 100 ? chance : duration; }
    }
}
