package com.malfoid.speech;

import org.junit.Test;
import static org.junit.Assert.*;

/** An original no-paint deadline has one distinct terminal outcome. */
public class M67TerminalOutcomeGapTest
{
    @Test
    public void genuineOriginalDeadlineExpiryHasOneDistinctExpiredTerminalOutcome() throws Exception
    {
        PresentationDirectorConformanceTest.Fixture f = new PresentationDirectorConformanceTest.Fixture();
        f.issue(PresentationDirectorConformanceTest.level(1, 10, false));
        f.tick(20);
        f.tick(21);
        f.tick(21);
        assertEquals(1, f.trace.stream().filter(value -> "scene expired M04".equals(value)).count());
        assertEquals(0, f.trace.stream().filter(value -> "scene cancel M04".equals(value)).count());
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.silentArrivals.get());
        assertEquals(0, f.completed.get());
    }
}
