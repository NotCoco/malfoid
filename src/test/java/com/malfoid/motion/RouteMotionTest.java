package com.malfoid.motion;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class RouteMotionTest
{
    private static final RoutePlanner.Collision OPEN = new RoutePlanner.Collision()
    {
        public boolean isLegal(int x, int y) { return true; }
        public boolean canTravel(int x, int y, int dx, int dy) { return true; }
    };

    @Test public void walkUsesThirtyDistinctCyclesAndDoesNotDoubleAdvance()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(10, 10, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(11, 10)), false);
        for (int cycle = 1; cycle < 30; cycle++)
        {
            motion.advance(cycle, OPEN);
            double x = motion.getX();
            motion.advance(cycle, OPEN);
            assertEquals(x, motion.getX(), 0);
            assertEquals(new RoutePlanner.Node(10, 10), motion.completedNode());
        }
        motion.advance(30, OPEN);
        assertEquals(new RoutePlanner.Node(11, 10), motion.completedNode());
        assertFalse(motion.isMoving());
        assertEquals(11 * 128 + 64, motion.getX(), 0);
        motion.advance(31, OPEN);
        assertEquals(11 * 128 + 64, motion.getX(), 0);
    }

    @Test public void runFollowsTwoSegmentsInsteadOfCuttingTheCorner()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(10, 10, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(11, 10), new RoutePlanner.Node(11, 11)), true);
        for (int cycle = 1; cycle <= 15; cycle++)
        {
            motion.advance(cycle, OPEN);
            assertEquals(10 * 128 + 64, motion.getY(), 0);
        }
        assertEquals(new RoutePlanner.Node(11, 10), motion.completedNode());
        for (int cycle = 16; cycle <= 30; cycle++)
        {
            motion.advance(cycle, OPEN);
            assertEquals(11 * 128 + 64, motion.getX(), 0);
        }
        assertEquals(new RoutePlanner.Node(11, 11), motion.completedNode());
    }

    @Test public void secondRunEdgeIsRevalidatedAfterTheFirstCompletes()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(0, 0, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(1, 0), new RoutePlanner.Node(2, 0)), true);
        for (int cycle = 1; cycle <= 15; cycle++) motion.advance(cycle, OPEN);
        RoutePlanner.Collision changed = new RoutePlanner.Collision()
        {
            public boolean isLegal(int x, int y) { return x != 2; }
            public boolean canTravel(int x, int y, int dx, int dy) { return x + dx != 2; }
        };
        motion.advance(16, changed);
        assertTrue(motion.isBlocked());
        assertFalse(motion.isMoving());
        assertEquals(new RoutePlanner.Node(1, 0), motion.completedNode());
    }

    @Test public void replanningAndStoppingPreserveACommittedShortSegment()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(0, 0, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(1, 0), new RoutePlanner.Node(2, 0)), false);
        motion.advance(1, OPEN);
        motion.stop();
        for (int cycle = 2; cycle <= 30; cycle++) motion.advance(cycle, OPEN);
        assertEquals(new RoutePlanner.Node(1, 0), motion.completedNode());
        assertFalse(motion.isMoving());
        motion.advance(1000, OPEN);
        assertEquals(192, motion.getX(), 0);
    }

    @Test public void stallAdvancesOnlyOneValidatedCycle()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(0, 0, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(1, 0)), false);
        motion.advance(1000, OPEN);
        assertEquals(64 + 128d / 30, motion.getX(), .000001);
        assertEquals(new RoutePlanner.Node(0, 0), motion.completedNode());
    }

    @Test public void runBurstDemotionFinishesOnlyTheCommittedRunEdgeBeforeWalking()
    {
        RouteMotion motion = new RouteMotion();
        motion.place(0, 0, 0);
        motion.setRoute(Arrays.asList(new RoutePlanner.Node(1, 0), new RoutePlanner.Node(2, 0)), true);
        for (int cycle = 1; cycle <= 7; cycle++) motion.advance(cycle, OPEN);
        motion.setRunning(false);
        assertTrue(motion.isRunning());
        for (int cycle = 8; cycle <= 15; cycle++) motion.advance(cycle, OPEN);
        assertEquals(new RoutePlanner.Node(1, 0), motion.completedNode());
        motion.advance(16, OPEN);
        assertFalse(motion.isRunning());
        for (int cycle = 17; cycle < 45; cycle++) motion.advance(cycle, OPEN);
        assertEquals(new RoutePlanner.Node(1, 0), motion.completedNode());
        motion.advance(45, OPEN);
        assertEquals(new RoutePlanner.Node(2, 0), motion.completedNode());
        assertFalse(motion.isMoving());
    }
}
