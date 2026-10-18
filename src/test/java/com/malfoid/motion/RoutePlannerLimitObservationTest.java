package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoutePlannerLimitObservationTest
{
    @Test public void actualBoundExhaustionKeepsItsRawBudgetStatusAndLimitFlag()
    {
        RoutePlanner.Result result = new RoutePlanner().plan(open(), new RoutePlanner.Node(50, 50), unsuitable());
        assertEquals(RoutePlanner.Status.BUDGET_EXHAUSTED, result.status);
        assertTrue(result.searchLimited);
        assertEquals(625, result.visited);
        assertTrue(result.steps.isEmpty());
    }

    @Test public void anActuallyClosedPocketIsBlockedWithoutAComputationalLimit()
    {
        RoutePlanner.Collision pocket = new RoutePlanner.Collision()
        {
            @Override public boolean isLegal(int x, int y) { return x >= 49 && x <= 51 && y >= 49 && y <= 51; }
            @Override public boolean canTravel(int x, int y, int dx, int dy) { return isLegal(x + dx, y + dy); }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(pocket, new RoutePlanner.Node(50, 50), unsuitable());
        assertEquals(RoutePlanner.Status.BLOCKED, result.status);
        assertFalse(result.searchLimited);
        assertEquals(9, result.visited);
    }

    @Test public void aRealRouteCanAlsoHaveAnExpansionLimitWithoutBecomingABudgetFailure()
    {
        RoutePlanner.Goal goal = new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return x == 51 && y == 50 ? 1 : -1; }
            @Override public int distance(int x, int y) { return MotionMath.distance(x, y, 51, 50); }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(open(), new RoutePlanner.Node(50, 50), goal);
        assertEquals(RoutePlanner.Status.ROUTE, result.status);
        assertTrue(result.searchLimited);
        assertEquals(1, result.steps.size());
        assertEquals(new RoutePlanner.Node(51, 50), result.steps.get(0));
    }

    @Test public void anUnavailableStartHasNeitherAnExpansionNorALimit()
    {
        RoutePlanner.Result result = new RoutePlanner().plan(null, new RoutePlanner.Node(50, 50), unsuitable());
        assertEquals(RoutePlanner.Status.SCENE_UNAVAILABLE, result.status);
        assertFalse(result.searchLimited);
        assertEquals(0, result.visited);
    }

    private static RoutePlanner.Collision open()
    {
        return new RoutePlanner.Collision()
        {
            @Override public boolean isLegal(int x, int y) { return true; }
            @Override public boolean canTravel(int x, int y, int dx, int dy) { return true; }
        };
    }

    private static RoutePlanner.Goal unsuitable()
    {
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return -1; }
            @Override public int distance(int x, int y) { return 1; }
        };
    }
}
