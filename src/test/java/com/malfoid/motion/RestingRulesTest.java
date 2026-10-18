package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RestingRulesTest
{
    @Test public void knownCourtesiesReduceWeightButKeepTheTileLegal()
    {
        assertEquals(.2, RestingRules.refine(1, true, false, false, 8), 1e-12);
        assertEquals(.2, RestingRules.refine(1, false, true, false, 8), 1e-12);
        assertEquals(.3, RestingRules.refine(1, false, false, true, 8), 1e-12);
        assertEquals(.3, RestingRules.refine(1, false, false, false, 2), 1e-12);
        assertEquals(.0036, RestingRules.refine(1, true, true, true, 2), 1e-12);
    }

    @Test public void unknownOrSceneOwnedExitCheckDoesNotInventAPenalty()
    {
        assertEquals(1, RestingRules.refine(1, false, false, false, -1), 0);
        assertEquals(1, RestingRules.refine(1, false, false, false, 3), 0);
    }
}
