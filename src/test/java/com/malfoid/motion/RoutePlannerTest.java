package com.malfoid.motion;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoutePlannerTest
{
    private static RoutePlanner.Goal goal(int gx, int gy)
    {
        return new RoutePlanner.Goal()
        {
            public double score(int x, int y, int steps) { return x == gx && y == gy ? 1 : -1; }
            public int distance(int x, int y) { return MotionMath.distance(x, y, gx, gy); }
        };
    }

    @Test public void openExpansionHasAnExactBoundAndDeterministicAdjacentPath()
    {
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.Collision open = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return true; }
            public boolean canTravel(int x, int y, int dx, int dy) { return true; }
        };
        RoutePlanner.Result first = planner.plan(open, new RoutePlanner.Node(0, 0), goal(12, 12));
        RoutePlanner.Result second = planner.plan(open, new RoutePlanner.Node(0, 0), goal(12, 12));
        assertEquals(625, first.visited);
        assertEquals(RoutePlanner.Status.ROUTE, first.status);
        assertEquals(first.steps, second.steps);
        assertEquals(12, first.steps.size());
        RoutePlanner.Node prior = new RoutePlanner.Node(0, 0);
        for (RoutePlanner.Node node : first.steps)
        {
            assertEquals(1, MotionMath.distance(node.x, node.y, prior.x, prior.y));
            prior = node;
        }
    }

    @Test public void unreachableAndOutOfWindowGoalsNeverClaimArrival()
    {
        RoutePlanner.Collision wall = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return x < 2; }
            public boolean canTravel(int x, int y, int dx, int dy) { return x + dx < 2; }
        };
        RoutePlanner.Result blocked = new RoutePlanner().plan(wall, new RoutePlanner.Node(0, 0), goal(3, 0));
        assertEquals(RoutePlanner.Status.FRONTIER, blocked.status);
        assertTrue(blocked.steps.size() <= 24);
        assertTrue(blocked.visited <= 625);
        RoutePlanner.Result far = new RoutePlanner().plan(wall, new RoutePlanner.Node(0, 0), goal(100, 100));
        assertNotEquals(RoutePlanner.Status.AT_GOAL, far.status);
        assertNotEquals(RoutePlanner.Status.ROUTE, far.status);
    }

    @Test public void missingOrFullyEnclosedCollisionReturnsNoRoute()
    {
        RoutePlanner planner = new RoutePlanner();
        assertEquals(RoutePlanner.Status.SCENE_UNAVAILABLE, planner.plan(null, new RoutePlanner.Node(0, 0), goal(1, 1)).status);
        RoutePlanner.Collision closed = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return x == 0 && y == 0; }
            public boolean canTravel(int x, int y, int dx, int dy) { return false; }
        };
        RoutePlanner.Result result = planner.plan(closed, new RoutePlanner.Node(0, 0), goal(1, 1));
        assertEquals(RoutePlanner.Status.BLOCKED, result.status);
        assertEquals(1, result.visited);
        assertTrue(result.steps.isEmpty());
    }

    @Test public void longWallOutsideTheSearchWindowDoesNotProvePhysicalBlockage()
    {
        RoutePlanner.Collision longWall = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return x != 1 || y > 12; }
            public boolean canTravel(int x, int y, int dx, int dy)
            {
                return isLegal(x + dx, y + dy) && (dx == 0 || dy == 0
                    || isLegal(x + dx, y) && isLegal(x, y + dy));
            }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(longWall, new RoutePlanner.Node(0, 0), goal(3, 0));
        assertEquals(RoutePlanner.Status.BUDGET_EXHAUSTED, result.status);
        assertTrue(result.steps.isEmpty());
        assertTrue(result.visited <= 625);
    }

    @Test public void bearingsAndTurnQuantizationAgreeAtTheWrap()
    {
        assertEquals(0, MotionMath.bearing(0, -1));
        assertEquals(512, MotionMath.bearing(-1, 0));
        assertEquals(1024, MotionMath.bearing(0, 1));
        assertEquals(1536, MotionMath.bearing(1, 0));
        assertEquals(16, MotionMath.turn(2032, 48));
        assertEquals(2032, MotionMath.turn(16, 1968));
        boolean[] headings = new boolean[2048];
        for (int orientation = 0; orientation < 2048; orientation++)
        {
            int applied = MotionMath.quantize(orientation);
            headings[applied] = true;
            assertTrue(Math.abs(((applied - orientation + 1024) & 2047) - 1024) <= 16);
        }
        int distinct = 0;
        for (boolean heading : headings) if (heading) distinct++;
        assertEquals(64, distinct);
        assertEquals(0, MotionMath.cameraBearing(0));
        assertEquals(1536, MotionMath.cameraBearing(512));
        assertEquals(1024, MotionMath.cameraBearing(1024));
        assertEquals(512, MotionMath.cameraBearing(1536));
    }

    @Test public void expensiveCourtesyOnlyRefinesEightReachableCandidates()
    {
        int[] refined = {0};
        RoutePlanner.Collision open = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return true; }
            public boolean canTravel(int x, int y, int dx, int dy) { return true; }
        };
        RoutePlanner.Goal scored = new RoutePlanner.Goal()
        {
            public double score(int x, int y, int steps) { return 1d / (steps + 1); }
            public int distance(int x, int y) { return 0; }
            public double refineScore(int x, int y, int steps, double score)
            {
                refined[0]++;
                return score;
            }
        };
        RoutePlanner.Result result = new RoutePlanner().plan(open, new RoutePlanner.Node(0, 0), scored);
        assertEquals(RoutePlanner.Status.AT_GOAL, result.status);
        assertEquals(8, refined[0]);
    }

    @Test public void combatFootprintsIncludeEveryAdjacentTile()
    {
        for (int size : new int[]{1, 3})
        {
            int minX = 10, minY = 10, maxX = minX + size - 1, maxY = minY + size - 1;
            assertEquals(0, MotionMath.footprintDistance(minX, minY, minX, minY, maxX, maxY));
            assertEquals(1, MotionMath.footprintDistance(minX - 1, minY - 1, minX, minY, maxX, maxY));
            assertEquals(1, MotionMath.footprintDistance(maxX + 1, maxY + 1, minX, minY, maxX, maxY));
            assertEquals(2, MotionMath.footprintDistance(maxX + 2, maxY + 1, minX, minY, maxX, maxY));
        }
    }
}
