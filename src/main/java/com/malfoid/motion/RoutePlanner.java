package com.malfoid.motion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** One bounded reachable expansion can choose a scored endpoint or a nearer frontier. */
public final class RoutePlanner
{
    public static final int RADIUS = 12;
    public static final int MAX_CELLS = 625;
    public static final int MAX_STEPS = 24;
    private static final int WIDTH = RADIUS * 2 + 1;
    private static final int[] DX = {0, -1, 0, 1, -1, -1, 1, 1};
    private static final int[] DY = {-1, 0, 1, 0, -1, 1, 1, -1};
    private final int[] queue = new int[MAX_CELLS];
    private final int[] predecessor = new int[MAX_CELLS];
    private final int[] depth = new int[MAX_CELLS];

    public interface Collision
    {
        boolean isLegal(int x, int y);
        boolean canTravel(int x, int y, int dx, int dy);
    }

    public interface Goal
    {
        /** Negative means unsuitable. Higher scores are preferred. */
        double score(int x, int y, int steps);
        /** Lower means closer to an unreachable or out-of-window target. */
        int distance(int x, int y);
        /** Expensive courtesy checks run only on the eight best reachable candidates. */
        default double refineScore(int x, int y, int steps, double score) { return score; }
    }

    public static final class Node
    {
        public final int x, y;
        public Node(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object other)
        {
            return other instanceof Node && x == ((Node) other).x && y == ((Node) other).y;
        }
        @Override public int hashCode() { return x * 31 + y; }
    }

    public enum Status { AT_GOAL, ROUTE, FRONTIER, BLOCKED, BUDGET_EXHAUSTED, SCENE_UNAVAILABLE }

    public static final class Result
    {
        public final Status status;
        public final List<Node> steps;
        public final int visited;
        public final boolean searchLimited;
        private Result(Status status, List<Node> steps, int visited, boolean searchLimited)
        {
            this.status = status;
            this.steps = Collections.unmodifiableList(steps);
            this.visited = visited;
            this.searchLimited = searchLimited;
        }
    }

    public Result plan(Collision collision, Node start, Goal goal)
    {
        if (collision == null || !collision.isLegal(start.x, start.y))
        {
            return new Result(Status.SCENE_UNAVAILABLE, Collections.emptyList(), 0, false);
        }
        Arrays.fill(predecessor, -2);
        int root = RADIUS * WIDTH + RADIUS;
        queue[0] = root;
        predecessor[root] = -1;
        depth[root] = 0;
        int head = 0, tail = 1, best = -1, frontier = root;
        int[] candidates = new int[8];
        double[] scores = new double[8];
        Arrays.fill(candidates, -1);
        Arrays.fill(scores, -1);
        boolean limited = false;
        int frontierDistance = goal.distance(start.x, start.y);
        while (head < tail)
        {
            int cell = queue[head++];
            int x = start.x + cell % WIDTH - RADIUS;
            int y = start.y + cell / WIDTH - RADIUS;
            double score = goal.score(x, y, depth[cell]);
            if (score >= 0)
            {
                for (int rank = 0; rank < candidates.length; rank++)
                {
                    if (score > scores[rank])
                    {
                        for (int lower = candidates.length - 1; lower > rank; lower--)
                        {
                            candidates[lower] = candidates[lower - 1];
                            scores[lower] = scores[lower - 1];
                        }
                        candidates[rank] = cell;
                        scores[rank] = score;
                        break;
                    }
                }
            }
            int distance = goal.distance(x, y);
            if (distance < frontierDistance)
            {
                frontier = cell;
                frontierDistance = distance;
            }
            for (int direction = 0; direction < 8; direction++)
            {
                int cx = cell % WIDTH + DX[direction], cy = cell / WIDTH + DY[direction];
                if (cx < 0 || cy < 0 || cx >= WIDTH || cy >= WIDTH)
                {
                    if (collision.isLegal(x + DX[direction], y + DY[direction])
                        && collision.canTravel(x, y, DX[direction], DY[direction]))
                    {
                        limited = true;
                    }
                    continue;
                }
                int next = cy * WIDTH + cx;
                if (predecessor[next] != -2 || !collision.isLegal(x + DX[direction], y + DY[direction])
                    || !collision.canTravel(x, y, DX[direction], DY[direction]))
                {
                    continue;
                }
                if (depth[cell] == MAX_STEPS)
                {
                    limited = true;
                    continue;
                }
                predecessor[next] = cell;
                depth[next] = depth[cell] + 1;
                queue[tail++] = next;
            }
        }
        double bestScore = -1;
        for (int rank = 0; rank < candidates.length && candidates[rank] >= 0; rank++)
        {
            int cell = candidates[rank];
            double refined = goal.refineScore(start.x + cell % WIDTH - RADIUS, start.y + cell / WIDTH - RADIUS,
                depth[cell], scores[rank]);
            if (refined >= 0 && refined > bestScore)
            {
                best = cell;
                bestScore = refined;
            }
        }
        boolean reached = best >= 0;
        int target = reached ? best : frontier;
        List<Node> steps = new ArrayList<>();
        for (int cell = target; predecessor[cell] >= 0; cell = predecessor[cell])
        {
            steps.add(new Node(start.x + cell % WIDTH - RADIUS, start.y + cell / WIDTH - RADIUS));
        }
        Collections.reverse(steps);
        Status status = reached ? (steps.isEmpty() ? Status.AT_GOAL : Status.ROUTE)
            : steps.isEmpty() ? limited ? Status.BUDGET_EXHAUSTED : Status.BLOCKED : Status.FRONTIER;
        return new Result(status, steps, tail, limited);
    }
}
