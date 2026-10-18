package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.malfoid.motion.WholeBoundsVisibility.Visibility.*;

public class WholeBoundsVisibilityTest
{
    private static final WholeBoundsVisibility.Bounds CHARACTER = new WholeBoundsVisibility.Bounds(-30, -190, -20, 30, -1, 20);
    private static final WholeBoundsVisibility.Projector FLAT = (x, y, z, out) ->
    {
        out[0] = x; out[1] = z; out[2] = 100;
        return true;
    };

    @Test public void footOutsideWithHeadStillVisibleDoesNotQualify()
    {
        assertEquals(VISIBLE, WholeBoundsVisibility.classify(CHARACTER, 0, 0, 250, 0, FLAT, 0, 0, 300, 300, 100));
    }

    @Test public void unionPartlyEnteringTheViewportBlocksRecovery()
    {
        assertEquals(VISIBLE, WholeBoundsVisibility.classify(CHARACTER, 175, 0, 0, 0, FLAT, 0, 0, 300, 300, 100));
        assertEquals(OFF_SCREEN, WholeBoundsVisibility.classify(CHARACTER, 190, 0, 0, 0, FLAT, 0, 0, 300, 300, 100));
        assertEquals(VISIBLE, WholeBoundsVisibility.classify(CHARACTER, 190, 0, 0, 512, FLAT, 0, 0, 350, 300, 100));
    }

    @Test public void nearPlaneCrossingAndUnknownProjectionDefer()
    {
        WholeBoundsVisibility.Projector crossing = (x, y, z, out) ->
        {
            out[0] = x; out[1] = z; out[2] = 50 + y;
            return true;
        };
        assertEquals(UNKNOWN, WholeBoundsVisibility.classify(CHARACTER, 1000, 0, 0, 0, crossing, 0, 0, 300, 300, 100));
        WholeBoundsVisibility.Projector behind = (x, y, z, out) ->
        {
            out[0] = x; out[1] = z; out[2] = 49;
            return true;
        };
        assertEquals(OFF_SCREEN, WholeBoundsVisibility.classify(CHARACTER, 0, 0, 0, 0, behind, 0, 0, 300, 300, 100));
        WholeBoundsVisibility.Projector unknown = (x, y, z, out) ->
        {
            out[0] = Double.NaN; out[1] = 0; out[2] = 100;
            return true;
        };
        assertEquals(UNKNOWN, WholeBoundsVisibility.classify(CHARACTER, 1000, 0, 0, 0, unknown, 0, 0, 300, 300, 100));
        assertEquals(UNKNOWN, WholeBoundsVisibility.classify(CHARACTER, 0, 0, 0, 0, null, 0, 0, 300, 300, 100));
    }

    @Test public void unequalViewportOffsetsAndRoundingMarginAreHonoured()
    {
        assertEquals(VISIBLE, WholeBoundsVisibility.classify(CHARACTER, 183, 0, 0, 0, FLAT, 30, 70, 300, 300, 100));
        assertEquals(OFF_SCREEN, WholeBoundsVisibility.classify(CHARACTER, 190, 0, 0, 0, FLAT, 30, 70, 300, 300, 100));
        assertEquals(UNKNOWN, WholeBoundsVisibility.classify(CHARACTER, 190, 0, 0, 0, FLAT, 30, 70, 0, 300, 100));
    }
}
