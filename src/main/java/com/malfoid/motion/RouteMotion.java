package com.malfoid.motion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** The render clock follows adjacent segments; it never advances again on GameTick. */
public final class RouteMotion
{
    private final ArrayDeque<RoutePlanner.Node> route = new ArrayDeque<>();
    private RoutePlanner.Node completed;
    private RoutePlanner.Node target;
    private int progress;
    private int segmentCycles;
    private int lastCycle;
    private double x, y;
    private boolean running, blocked;
    private long completedEdges;

    public void place(int tileX, int tileY, int cycle)
    {
        clear();
        completed = new RoutePlanner.Node(tileX, tileY);
        x = tileX * 128 + 64;
        y = tileY * 128 + 64;
        lastCycle = cycle;
    }

    public void setRoute(List<RoutePlanner.Node> steps, boolean run)
    {
        route.clear();
        route.addAll(steps);
        running = run;
        blocked = false;
    }

    public void stop()
    {
        // A committed short segment finishes before a stop or changed destination takes effect.
        route.clear();
    }

    /** A pace change applies to the next edge, while the committed edge keeps its original duration. */
    public void setRunning(boolean run)
    {
        running = run;
    }

    public void advance(int cycle, RoutePlanner.Collision collision)
    {
        if (completed == null || cycle == lastCycle)
        {
            return;
        }
        lastCycle = cycle;
        // A stall never accumulates a jump across unvalidated segments.
        if (target == null)
        {
            RoutePlanner.Node next = route.peekFirst();
            if (next == null)
            {
                return;
            }
            int dx = next.x - completed.x, dy = next.y - completed.y;
            if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || (dx == 0 && dy == 0)
                || collision == null || !collision.isLegal(next.x, next.y)
                || !collision.canTravel(completed.x, completed.y, dx, dy))
            {
                blocked = true;
                route.clear();
                return;
            }
            target = route.removeFirst();
            progress = 0;
            segmentCycles = running ? 15 : 30;
        }
        progress++;
        double fraction = Math.min(1d, (double) progress / segmentCycles);
        x = (completed.x + (target.x - completed.x) * fraction) * 128 + 64;
        y = (completed.y + (target.y - completed.y) * fraction) * 128 + 64;
        if (progress >= segmentCycles)
        {
            completed = target;
            target = null;
            progress = 0;
            completedEdges++;
        }
    }

    public RoutePlanner.Node planningNode() { return target == null ? completed : target; }
    /** The steps not yet begun, in order, from planningNode(). */
    public List<RoutePlanner.Node> remainingSteps() { return new ArrayList<>(route); }
    public RoutePlanner.Node completedNode() { return completed; }
    public RoutePlanner.Node destinationNode() { return route.isEmpty() ? planningNode() : route.peekLast(); }
    public boolean isMoving() { return target != null || !route.isEmpty(); }
    public boolean isSegmentInFlight() { return target != null; }
    public RoutePlanner.Node nextUnenteredNode() { return target == null ? route.peekFirst() : null; }
    public boolean isRunning() { return target != null ? segmentCycles == 15 : running; }
    public boolean isBlocked() { return blocked; }
    public double getX() { return x; }
    public double getY() { return y; }
    public long getCompletedEdges() { return completedEdges; }
    public int getBearing()
    {
        if (target != null)
        {
            return MotionMath.bearing(target.x - completed.x, target.y - completed.y);
        }
        RoutePlanner.Node next = route.peekFirst();
        return next == null || completed == null ? 0 : MotionMath.bearing(next.x - completed.x, next.y - completed.y);
    }

    public void clear()
    {
        route.clear();
        completed = target = null;
        progress = 0;
        blocked = false;
    }
}
