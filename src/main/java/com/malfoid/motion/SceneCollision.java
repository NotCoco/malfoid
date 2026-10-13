package com.malfoid.motion;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;

/** Guards every public collision-helper read, including diagonal side tiles. */
public final class SceneCollision implements RoutePlanner.Collision
{
    private final WorldView view;
    private final int plane;
    private final int[][] flags;
    private final int blockedX, blockedY;
    private final boolean complete;

    public SceneCollision(WorldView view, int plane, int blockedX, int blockedY)
    {
        this.view = view;
        this.plane = plane;
        this.blockedX = blockedX;
        this.blockedY = blockedY;
        CollisionData[] maps = view == null ? null : view.getCollisionMaps();
        flags = maps != null && plane >= 0 && plane < maps.length && maps[plane] != null ? maps[plane].getFlags() : null;
        complete = view != null && completeShape(flags, view.getSizeX(), view.getSizeY());
    }

    private static boolean completeShape(int[][] flags, int width, int height)
    {
        if (flags == null || width <= 0 || height <= 0 || flags.length < width) return false;
        for (int x = 0; x < width; x++)
        {
            if (flags[x] == null || flags[x].length < height) return false;
        }
        return true;
    }

    public boolean isAvailable() { return complete && view.getPlane() == plane; }

    public boolean contains(int x, int y)
    {
        return isAvailable() && x >= 0 && y >= 0 && x < view.getSizeX() && y < view.getSizeY()
            && x < flags.length && flags[x] != null && y < flags[x].length;
    }

    @Override public boolean isLegal(int x, int y)
    {
        return contains(x, y) && (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0
            && (x != blockedX || y != blockedY);
    }

    @Override public boolean canTravel(int x, int y, int dx, int dy)
    {
        if ((dx == 0 && dy == 0) || Math.abs(dx) > 1 || Math.abs(dy) > 1
            || !contains(x, y) || !isLegal(x + dx, y + dy))
        {
            return false;
        }
        if (dx != 0 && dy != 0 && (!contains(x + dx, y) || !contains(x, y + dy)))
        {
            return false;
        }
        return new WorldArea(view.getBaseX() + x, view.getBaseY() + y, 1, 1, plane)
            .canTravelInDirection(view, dx, dy);
    }

    /** Missing cells are unknown, rather than evidence that a previously legal edge became blocked. */
    public boolean hasEdgeData(int x, int y, int dx, int dy)
    {
        return Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && (dx != 0 || dy != 0)
            && contains(x, y) && contains(x + dx, y + dy)
            && (dx == 0 || dy == 0 || contains(x + dx, y) && contains(x, y + dy));
    }

    public int neighborhoodFingerprint(int x, int y)
    {
        int hash = 1;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                hash = 31 * hash + (contains(x + dx, y + dy) ? flags[x + dx][y + dy] : -1);
            }
        }
        return hash;
    }
}
