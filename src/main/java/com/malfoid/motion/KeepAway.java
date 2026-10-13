package com.malfoid.motion;

import java.util.ArrayList;
import java.util.List;

/**
 * H.2: the ground round every creature she is afraid of. She never steps into it and never chooses a tile in it;
 * if one comes to her, the only steps she may take inside are steps away from that creature.
 * Copied scene tiles only; contains no client objects.
 */
public final class KeepAway
{
    public static final KeepAway NONE = new KeepAway(new int[0][]);
    /** Tiles kept clear round a rat, and round every other creature she is afraid of. */
    public static final int RAT_RADIUS = 1, BEAST_RADIUS = 3;
    /** How far from her a creature still shapes her routes: the route search's own window, and a little. */
    public static final int CONSIDERED_WITHIN = RoutePlanner.RADIUS + BEAST_RADIUS;
    /** Giving way three times leaves only the creature's own tiles: she squeezes past, she never walks through it. */
    public static final int MAX_RELAX = 3;

    private final int[][] zones;      // minX, minY, maxX, maxY, radius

    private KeepAway(int[][] zones) { this.zones = zones; }

    public static final class Builder
    {
        private final List<int[]> zones = new ArrayList<>();

        /** A creature whose south-west tile is (x, y), `size` tiles square, kept `radius` tiles away. */
        public Builder add(int x, int y, int size, int radius)
        {
            int side = Math.max(1, size);
            zones.add(new int[] {x, y, x + side - 1, y + side - 1, Math.max(0, radius)});
            return this;
        }

        public KeepAway build() { return zones.isEmpty() ? NONE : new KeepAway(zones.toArray(new int[0][])); }
    }

    public boolean isEmpty() { return zones.length == 0; }
    public int size() { return zones.length; }

    private static int distance(int[] zone, int x, int y)
    {
        return MotionMath.footprintDistance(x, y, zone[0], zone[1], zone[2], zone[3]);
    }

    /** The tile is too near a creature she is afraid of. */
    public boolean inside(int x, int y)
    {
        for (int[] zone : zones) { if (distance(zone, x, y) <= zone[4]) { return true; } }
        return false;
    }

    /** A step is refused when it ends too near a creature without having moved away from that creature. */
    public boolean allowsStep(int fromX, int fromY, int toX, int toY)
    {
        for (int[] zone : zones)
        {
            int after = distance(zone, toX, toY);
            if (after <= zone[4] && after <= distance(zone, fromX, fromY)) { return false; }
        }
        return true;
    }

    /** The first step of `steps` from `start` that she may no longer take, or -1. */
    public int firstRefused(RoutePlanner.Node start, List<RoutePlanner.Node> steps)
    {
        RoutePlanner.Node from = start;
        for (int index = 0; index < steps.size() && from != null; index++)
        {
            RoutePlanner.Node to = steps.get(index);
            if (!allowsStep(from.x, from.y, to.x, to.y)) { return index; }
            from = to;
        }
        return -1;
    }

    public RoutePlanner.Collision over(RoutePlanner.Collision collision)
    {
        if (isEmpty() || collision == null) { return collision; }
        return new RoutePlanner.Collision()
        {
            @Override public boolean isLegal(int x, int y) { return collision.isLegal(x, y); }
            @Override public boolean canTravel(int x, int y, int dx, int dy)
            {
                return allowsStep(x, y, x + dx, y + dy) && collision.canTravel(x, y, dx, dy);
            }
        };
    }

    public RoutePlanner.Goal over(RoutePlanner.Goal goal)
    {
        if (isEmpty() || goal == null) { return goal; }
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return inside(x, y) ? -1 : goal.score(x, y, steps); }
            @Override public int distance(int x, int y) { return goal.distance(x, y); }
            @Override public double refineScore(int x, int y, int steps, double score)
            {
                return inside(x, y) ? -1 : goal.refineScore(x, y, steps, score);
            }
        };
    }
}
