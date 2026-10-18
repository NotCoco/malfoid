package com.malfoid.world;

import org.junit.Test;
import static org.junit.Assert.*;

public class SceneryVisitsTest
{
    @Test public void capacityRefusesTheFiveHundredThirteenthIdentityWithoutEvictingAnyRecordedVisit()
    {
        SceneryVisits visits = new SceneryVisits();
        assertFalse(visits.canVisit(null)); assertFalse(visits.arrived(null, "SC-BOOKS"));
        for (int i = 0; i < 512; i++)
        {
            String key = "object:" + i; assertTrue(visits.canVisit(key));
            assertTrue(visits.arrived(key, "kind:" + (i % 21)));
            assertEquals(i + 1, visits.objectCount()); assertTrue(visits.kindCount() <= visits.objectCount());
        }
        assertEquals(512, SceneryVisits.MAX_VISITS); assertEquals(21, visits.kindCount());
        assertFalse(visits.canVisit("object:512")); assertFalse(visits.arrived("object:512", "new-kind"));
        assertEquals(512, visits.objectCount()); assertEquals(21, visits.kindCount()); assertTrue(visits.unseenKind("new-kind"));
        for (int i = 0; i < 512; i++) { assertFalse(visits.canVisit("object:" + i)); }
    }

    @Test public void repeatedArrivalAcknowledgementIsIdempotentEvenAtCapacityAndNeverPermitsAnotherVisit()
    {
        SceneryVisits visits = new SceneryVisits();
        for (int i = 0; i < 512; i++) { assertTrue(visits.arrived("object:" + i, "SC-BOOKS")); }
        assertTrue(visits.arrived("object:0", "SC-BOOKS"));
        assertFalse(visits.canVisit("object:0")); assertEquals(512, visits.objectCount()); assertEquals(1, visits.kindCount());
        assertFalse(visits.unseenKind("SC-BOOKS")); assertFalse(visits.arrived("object:512", "SC-STATUE"));
        assertTrue(visits.unseenKind("SC-STATUE"));
    }

    @Test public void sessionResetClearsCapacityAndKindMemoryAndPermitsPreviouslyRefusedOrRecordedIdentities()
    {
        SceneryVisits visits = new SceneryVisits();
        for (int i = 0; i < 512; i++) { assertTrue(visits.arrived("object:" + i, "SC-BOOKS")); }
        assertFalse(visits.arrived("object:512", "SC-STATUE")); visits.resetSession();
        assertEquals(0, visits.objectCount()); assertEquals(0, visits.kindCount()); assertTrue(visits.unseenKind("SC-BOOKS"));
        assertTrue(visits.canVisit("object:0")); assertTrue(visits.canVisit("object:512"));
        assertTrue(visits.arrived("object:512", "SC-STATUE")); assertEquals(1, visits.objectCount()); assertEquals(1, visits.kindCount());
        assertTrue(visits.unseenKind("SC-BOOKS"));
    }
}
