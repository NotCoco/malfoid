package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class InspectionRoutesTest
{
    private static final RoutePlanner.Collision OPEN = new RoutePlanner.Collision()
    {
        public boolean isLegal(int x, int y) { return true; }
        public boolean canTravel(int x, int y, int dx, int dy) { return true; }
    };

    @Test public void exactlyEightLegalEdgesProvesTheNeighborButNineDoesNot()
    {
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.Result eight = planner.plan(OPEN, new RoutePlanner.Node(0, 0),
            InspectionRoutes.besideFootprint(9, 0, 9, 0, 0, 0));
        assertEquals(RoutePlanner.Status.ROUTE, eight.status);
        assertEquals(8, eight.steps.size());
        RoutePlanner.Node stand = eight.steps.get(7);
        assertEquals(1, MotionMath.footprintDistance(stand.x, stand.y, 9, 0, 9, 0));
        RoutePlanner.Result nine = planner.plan(OPEN, new RoutePlanner.Node(0, 0),
            InspectionRoutes.besideFootprint(10, 0, 10, 0, 0, 0));
        assertNotEquals(RoutePlanner.Status.ROUTE, nine.status);
        assertNotEquals(RoutePlanner.Status.AT_GOAL, nine.status);
    }

    @Test public void aNearbyObjectBehindALongWallHasNoGeometricProof()
    {
        RoutePlanner.Collision wall = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return x != 1 || y >= 7; }
            public boolean canTravel(int x, int y, int dx, int dy)
            {
                return isLegal(x + dx, y + dy) && (dx == 0 || dy == 0
                    || isLegal(x + dx, y) && isLegal(x, y + dy));
            }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(wall, new RoutePlanner.Node(0, 0),
            InspectionRoutes.besideFootprint(3, 0, 3, 0, 0, 0));
        assertNotEquals(RoutePlanner.Status.ROUTE, result.status);
        assertNotEquals(RoutePlanner.Status.AT_GOAL, result.status);
        assertTrue(result.visited <= 625);
    }

    @Test public void alreadyBesideFootprintAllowsZeroStepsButPlayerAndInteriorAreExcluded()
    {
        RoutePlanner.Goal goal = InspectionRoutes.besideFootprint(1, 1, 3, 3, 0, 1);
        assertTrue(goal.score(0, 2, 0) >= 0);
        assertEquals(-1, goal.score(0, 1, 0), 0);
        assertEquals(-1, goal.score(2, 2, 0), 0);
        RoutePlanner.Result result = new RoutePlanner().plan(OPEN, new RoutePlanner.Node(0, 2), goal);
        assertEquals(RoutePlanner.Status.AT_GOAL, result.status);
        assertTrue(result.steps.isEmpty());
    }
}
