package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class FollowCollisionTest
{
    @Test public void aStepInsideTwoTilesMayOnlyKeepOrIncreaseSeparation()
    {
        assertTrue(FollowCollision.allowsStep(3, 6, 4, 6, 6, 6));
        assertFalse(FollowCollision.allowsStep(4, 6, 5, 6, 6, 6));
        assertFalse(FollowCollision.allowsStep(5, 6, 6, 6, 6, 6));
        assertTrue(FollowCollision.allowsStep(5, 6, 4, 6, 6, 6));
        assertTrue(FollowCollision.allowsStep(4, 6, 4, 5, 6, 6));
    }

    @Test public void theSingleSearchCannotTakeAnInnerRingShortcutToALegalOuterGoal()
    {
        RoutePlanner.Collision open = new RoutePlanner.Collision()
        {
            @Override public boolean isLegal(int x, int y) { return x >= 0 && y >= 0 && x <= 12 && y <= 12 && (x != 6 || y != 6); }
            @Override public boolean canTravel(int x, int y, int dx, int dy) { return isLegal(x + dx, y + dy); }
        };
        RoutePlanner.Goal goal = new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return x == 8 && y == 6 ? 1 : -1; }
            @Override public int distance(int x, int y) { return MotionMath.distance(x, y, 8, 6); }
        };
        RoutePlanner.Node previous = new RoutePlanner.Node(4, 6);
        RoutePlanner.Result route = new RoutePlanner().plan(new FollowCollision(open, 6, 6), previous, goal);
        assertEquals(RoutePlanner.Status.ROUTE, route.status);
        assertTrue(route.steps.size() > 4);
        assertTrue(route.visited <= 625);
        for (RoutePlanner.Node next : route.steps)
        {
            assertTrue(FollowCollision.allowsStep(previous.x, previous.y, next.x, next.y, 6, 6));
            assertTrue(MotionMath.distance(next.x, next.y, 6, 6) >= 2);
            previous = next;
        }
        assertEquals(new RoutePlanner.Node(8, 6), previous);
    }
}
