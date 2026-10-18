package com.malfoid.motion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Master spec H.2: she never walks near a creature she is afraid of, and never through one. */
public class KeepAwayTest
{
    private static final RoutePlanner.Collision OPEN = new RoutePlanner.Collision()
    {
        @Override public boolean isLegal(int x, int y) { return x >= 0 && y >= 0 && x <= 40 && y <= 40; }
        @Override public boolean canTravel(int x, int y, int dx, int dy) { return isLegal(x + dx, y + dy); }
    };

    private static RoutePlanner.Goal tile(int goalX, int goalY)
    {
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return x == goalX && y == goalY ? 1 : -1; }
            @Override public int distance(int x, int y) { return MotionMath.distance(x, y, goalX, goalY); }
        };
    }

    @Test public void theGroundRoundABeastIsThreeTilesAndRoundARatOne()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        assertTrue(bear.inside(20, 20));
        assertTrue(bear.inside(22, 18));
        assertTrue(bear.inside(23, 20));
        assertFalse(bear.inside(24, 20));
        assertFalse(bear.inside(20, 16));
        KeepAway rat = new KeepAway.Builder().add(20, 20, 1, KeepAway.RAT_RADIUS).build();
        assertTrue(rat.inside(21, 21));
        assertFalse(rat.inside(22, 20));
        assertTrue(KeepAway.NONE.isEmpty());
        assertFalse(KeepAway.NONE.inside(20, 20));
    }

    @Test public void aLargeCreatureIsMeasuredFromEveryTileItStandsOn()
    {
        KeepAway hound = new KeepAway.Builder().add(20, 20, 2, KeepAway.BEAST_RADIUS).build();
        assertTrue(hound.inside(24, 24));
        assertFalse(hound.inside(25, 21));
        assertTrue(hound.inside(17, 20));
        assertFalse(hound.inside(16, 20));
    }

    @Test public void sheMayNotStepInButMayAlwaysStepFurtherOut()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        assertFalse(bear.allowsStep(24, 20, 23, 20));      // from outside to inside
        assertFalse(bear.allowsStep(23, 20, 23, 21));      // along the inside, no farther away
        assertFalse(bear.allowsStep(23, 20, 22, 20));      // towards it
        assertTrue(bear.allowsStep(22, 20, 23, 20));       // away, still inside
        assertTrue(bear.allowsStep(23, 20, 24, 20));       // away and out
        assertTrue(bear.allowsStep(20, 20, 21, 21));       // off the very tile it stands on
        assertTrue(bear.allowsStep(24, 20, 24, 21));       // round the outside
    }

    @Test public void aRouteGoesRoundTheBeastAndNeverNearerThanFourTiles()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        RoutePlanner.Node start = new RoutePlanner.Node(16, 20);
        RoutePlanner.Result direct = new RoutePlanner().plan(OPEN, start, tile(24, 20));
        assertEquals(8, direct.steps.size());              // without the rule she would walk straight through it
        RoutePlanner.Result round = new RoutePlanner().plan(bear.over(OPEN), start, bear.over(tile(24, 20)));
        assertEquals(RoutePlanner.Status.ROUTE, round.status);
        assertEquals("Six steps longer than the straight way", 14, round.steps.size());
        for (RoutePlanner.Node step : round.steps)
        {
            assertTrue(MotionMath.distance(step.x, step.y, 20, 20) >= 4);
        }
        assertEquals(new RoutePlanner.Node(24, 20), round.steps.get(round.steps.size() - 1));
        assertEquals(-1, bear.firstRefused(start, round.steps));
        assertTrue(bear.firstRefused(start, direct.steps) >= 0);
    }

    @Test public void aTileBesideTheBeastIsNeverChosenSoSheStopsShortOfIt()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        // He stands beside the bear; the only place asked for is the tile between them.
        RoutePlanner.Result result = new RoutePlanner().plan(bear.over(OPEN), new RoutePlanner.Node(14, 20), bear.over(tile(21, 20)));
        assertEquals(RoutePlanner.Status.FRONTIER, result.status);
        RoutePlanner.Node last = result.steps.get(result.steps.size() - 1);
        assertEquals(4, MotionMath.distance(last.x, last.y, 20, 20));
        for (RoutePlanner.Node step : result.steps) { assertFalse(bear.inside(step.x, step.y)); }
    }

    @Test public void whenOneComesToHerTheOnlyWayIsOut()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        RoutePlanner.Goal anywhereClear = new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return 1d / (1 + steps); }
            @Override public int distance(int x, int y) { return 0; }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(bear.over(OPEN), new RoutePlanner.Node(21, 20), bear.over(anywhereClear));
        assertEquals(RoutePlanner.Status.ROUTE, result.status);
        assertEquals(3, result.steps.size());
        int before = 1;
        for (RoutePlanner.Node step : result.steps)
        {
            int now = MotionMath.distance(step.x, step.y, 20, 20);
            assertTrue(now > before);
            before = now;
        }
        assertEquals(4, before);
    }

    @Test public void aRouteAlreadyChosenIsRefusedFromTheStepABeastHasWalkedOnto()
    {
        KeepAway bear = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS).build();
        List<RoutePlanner.Node> steps = new ArrayList<>(Arrays.asList(new RoutePlanner.Node(15, 20), new RoutePlanner.Node(16, 20),
            new RoutePlanner.Node(17, 20), new RoutePlanner.Node(18, 20), new RoutePlanner.Node(19, 20)));
        assertEquals(2, bear.firstRefused(new RoutePlanner.Node(14, 20), steps));
        assertEquals(-1, KeepAway.NONE.firstRefused(new RoutePlanner.Node(14, 20), steps));
    }

    @Test public void givingWayThreeTimesLeavesOnlyTheTilesItStandsOn()
    {
        KeepAway squeezed = new KeepAway.Builder().add(20, 20, 1, KeepAway.BEAST_RADIUS - KeepAway.MAX_RELAX).build();
        assertTrue(squeezed.inside(20, 20));
        assertFalse(squeezed.inside(21, 20));
        assertTrue(squeezed.allowsStep(19, 20, 21, 21));
        assertFalse(squeezed.allowsStep(19, 20, 20, 20));
        KeepAway ratSqueezed = new KeepAway.Builder().add(20, 20, 1, KeepAway.RAT_RADIUS - KeepAway.MAX_RELAX).build();
        assertTrue(ratSqueezed.inside(20, 20));
        assertFalse(ratSqueezed.inside(21, 20));
    }
}
