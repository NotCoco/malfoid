package com.malfoid.speech;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechGuardTest
{
    @Test
    public void exactThirtyAndSixtySecondBoundariesUseRealTime()
    {
        for (int sample : new int[] {0, 30})
        {
            AtomicLong clock = new AtomicLong(0);
            CountingRandom random = new CountingRandom(sample);
            SpeechGuard guard = new SpeechGuard(clock::get, random, 349, 52);
            SpeechGuard.Reservation admission = guard.reserve("M04", "well done", null);
            assertTrue(guard.settlePaint(admission, 0));
            clock.set((30L + sample) * 1_000_000_000L - 1);
            assertFalse(guard.interactionEligible("M04"));
            assertFalse(guard.textEligible("well done"));
            clock.incrementAndGet();
            assertTrue(guard.interactionEligible("M04"));
            assertTrue(guard.textEligible("well done"));
            assertEquals(1, random.draws);
        }
    }

    @Test
    public void duplicateReceiptsAndSuppressedOffersNeverResample()
    {
        AtomicLong clock = new AtomicLong();
        CountingRandom random = new CountingRandom(15);
        SpeechGuard guard = new SpeechGuard(clock::get, random, 349, 52);
        SpeechGuard.Reservation first = guard.reserve("M09", "nice outfit", 1L);
        assertTrue(guard.settlePaint(first, 0));
        for (int i = 0; i < 400; i++)
        {
            assertFalse(guard.settlePaint(first, 0));
            assertNull(guard.reserve("M09", "alternate words", 2L));
        }
        clock.set(44_999_000_000L);
        assertFalse(guard.interactionEligible("M09"));
        clock.set(45_000_000_000L);
        assertTrue(guard.interactionEligible("M09"));
        assertEquals(1, random.draws);
    }

    @Test
    public void silentArrivalCommitsOnlyItsInteractionAndNoPaintRetirementDrawsNothing()
    {
        AtomicLong clock = new AtomicLong();
        CountingRandom random = new CountingRandom(10);
        SpeechGuard guard = new SpeechGuard(clock::get, random, 349, 52);
        SpeechGuard.Reservation cancelled = guard.reserve("M03", "not drawn", null);
        guard.retireNoPaint(cancelled);
        assertEquals(0, random.draws);
        assertTrue(guard.textEligible("not drawn"));
        SpeechGuard.Reservation silent = guard.reserve("M06", null, null);
        assertTrue(guard.settleSilentArrival(silent));
        assertFalse(guard.interactionEligible("M06"));
        assertEquals(0, guard.textCount());
        assertTrue(guard.handoffEligible());
        assertEquals(1, random.draws);
    }

    @Test
    public void handoffAndSituationExpiryDoNotRefreshOnLookup()
    {
        AtomicLong clock = new AtomicLong();
        SpeechGuard guard = new SpeechGuard(clock::get, new CountingRandom(0), 349, 52);
        assertTrue(guard.settlePaint(guard.reserve("M01", "stay close", 2L), 0));
        clock.set(1_999_999_999L);
        assertFalse(guard.handoffEligible());
        clock.set(2_000_000_000L);
        assertTrue(guard.handoffEligible());
        clock.set(5_399_999_999_999L);
        assertFalse(guard.situationEligible(2L));
        clock.incrementAndGet();
        assertTrue(guard.situationEligible(2L));
        assertEquals(0, guard.situationCount());
    }

    @Test
    public void finiteGuardCapacitySuppressesRatherThanEvicts()
    {
        AtomicLong clock = new AtomicLong();
        SpeechGuard guard = new SpeechGuard(clock::get, new CountingRandom(0), 1, 1);
        assertTrue(guard.settlePaint(guard.reserve("M03", "first", null), 0));
        assertNull(guard.reserve("M04", "second", null));
        assertFalse(guard.textEligible("first"));
        assertEquals(1, guard.textCount());
        clock.set(30_000_000_000L);
        assertNotNull(guard.reserve("M04", "second", null));
    }

    @Test
    public void oneUnresolvedReservationKeepsDependentSpeechClosed()
    {
        AtomicLong clock = new AtomicLong();
        SpeechGuard guard = new SpeechGuard(clock::get, new CountingRandom(0), 349, 52);
        SpeechGuard.Reservation first = guard.reserve("M03", "first", null);
        assertTrue(guard.interactionEligible("M04"));
        assertNull(guard.reserve("M04", "second", null));
        assertTrue(guard.settlePaint(first, 0));
        assertNotNull(guard.reserve("M04", "second", null));
    }

    static final class CountingRandom extends Random
    {
        int draws;
        private final int sample;
        CountingRandom(int sample) { this.sample = sample; }
        @Override public int nextInt(int bound) { draws++; return Math.min(sample, bound - 1); }
    }
}
