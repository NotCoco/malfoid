package com.malfoid.motion;

/** The object scene requires a proved legal route of at most eight edges to a neighboring tile. */
public final class InspectionRoutes
{
    public static final int MAX_STEPS = 8;

    private InspectionRoutes() {}

    public static RoutePlanner.Goal besideFootprint(int minX, int minY, int maxX, int maxY, int playerX, int playerY)
    {
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                return steps <= MAX_STEPS && MotionMath.footprintDistance(x, y, minX, minY, maxX, maxY) == 1
                    && !(x == playerX && y == playerY) ? 1d / (1 + steps) : -1;
            }

            @Override public int distance(int x, int y)
            {
                return Math.abs(MotionMath.footprintDistance(x, y, minX, minY, maxX, maxY) - 1);
            }
        };
    }
}
