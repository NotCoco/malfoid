package com.malfoid.world;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/** G.4's committed stationary count and bounded history of actual native impatient loops. */
public final class LivingWorldWaiting
{
    public static final int MAX_RECENT_EMOTES = 8;
    private final Random random;
    private final Deque<Long> loops = new ArrayDeque<>(MAX_RECENT_EMOTES);
    private long generation = Long.MIN_VALUE, revision = Long.MIN_VALUE, previousTick = Long.MIN_VALUE;
    private long lastEnd = Long.MIN_VALUE, requestedAt = -1;
    private int x, y, plane, stillTicks;
    private boolean havePoint, active, loopStarted;

    public LivingWorldWaiting(Random random) { this.random = random; }

    /** Zero means no request. A fact is consumed even if its one chance draw is silent. */
    public int observe(long tick, long sessionGeneration, long sceneRevision, boolean known,
        int playerX, int playerY, int playerPlane, boolean eligible)
    {
        if (generation != sessionGeneration) { resetSession(); generation = sessionGeneration; }
        if (tick == previousTick) { return 0; }
        if (revision != sceneRevision || previousTick != Long.MIN_VALUE && tick != previousTick + 1)
        {
            havePoint = false; stillTicks = 0;
        }
        revision = sceneRevision; previousTick = tick;
        if (!known) { havePoint = false; stillTicks = 0; return 0; }
        if (havePoint && x == playerX && y == playerY && plane == playerPlane) { stillTicks = Math.min(150, stillTicks + 1); }
        else { stillTicks = 1; }
        havePoint = true; x = playerX; y = playerY; plane = playerPlane;
        if (stillTicks < 150 || !eligible || active || lastEnd != Long.MIN_VALUE && tick - lastEnd < 400) { return 0; }
        stillTicks = 0;
        if (random.nextInt(100) >= 50) { return 0; }
        active = true; loopStarted = false; requestedAt = tick;
        return 8 + random.nextInt(9);
    }

    public boolean isActive() { return active; }
    public void requestRejected() { active = loopStarted = false; requestedAt = -1; }
    public boolean playerStillOnTile(int playerX, int playerY, int playerPlane)
    { return havePoint && x == playerX && y == playerY && plane == playerPlane; }

    public int onLoopStarted(long tick)
    {
        if (!active || loopStarted || tick < requestedAt) { return 0; }
        loopStarted = true;
        while (!loops.isEmpty() && tick - loops.peekFirst() >= 3000) { loops.removeFirst(); }
        // The 400-tick end spacing permits at most eight loop starts in this window.
        if (loops.size() < MAX_RECENT_EMOTES) { loops.addLast(tick); }
        return loops.size();
    }

    public void onEmoteEnded(long tick)
    {
        if (!active || tick < requestedAt) { return; }
        lastEnd = Math.max(lastEnd, tick); active = loopStarted = false; requestedAt = -1;
    }

    public int getRecentEmoteCount() { return loops.size(); }
    public long getLastEndTick() { return lastEnd; }

    public void resetSession()
    {
        generation = revision = previousTick = lastEnd = Long.MIN_VALUE;
        requestedAt = -1; havePoint = active = loopStarted = false; stillTicks = 0; loops.clear();
    }
}
