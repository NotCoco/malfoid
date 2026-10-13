package com.malfoid.motion;

/** P19's edge rule is part of the existing search, rather than a second route search. */
public final class FollowCollision implements RoutePlanner.Collision
{
    private final RoutePlanner.Collision collision;
    private final int playerX, playerY;

    public FollowCollision(RoutePlanner.Collision collision, int playerX, int playerY)
    {
        this.collision = collision;
        this.playerX = playerX;
        this.playerY = playerY;
    }

    @Override public boolean isLegal(int x, int y) { return collision.isLegal(x, y); }

    @Override public boolean canTravel(int x, int y, int dx, int dy)
    {
        return allowsStep(x, y, x + dx, y + dy, playerX, playerY) && collision.canTravel(x, y, dx, dy);
    }

    public static boolean allowsStep(int fromX, int fromY, int toX, int toY, int playerX, int playerY)
    {
        int before = MotionMath.distance(fromX, fromY, playerX, playerY);
        return before > 2 || MotionMath.distance(toX, toY, playerX, playerY) >= before;
    }
}
