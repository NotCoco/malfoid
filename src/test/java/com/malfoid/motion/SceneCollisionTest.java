package com.malfoid.motion;

import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SceneCollisionTest
{
    private static WorldView view(int[][] flags)
    {
        WorldView view = mock(WorldView.class);
        CollisionData data = mock(CollisionData.class);
        when(view.getId()).thenReturn(0);
        when(view.getBaseX()).thenReturn(3200);
        when(view.getBaseY()).thenReturn(3200);
        when(view.getSizeX()).thenReturn(8);
        when(view.getSizeY()).thenReturn(8);
        when(view.getPlane()).thenReturn(0);
        when(data.getFlags()).thenReturn(flags);
        when(view.getCollisionMaps()).thenReturn(new CollisionData[]{data});
        return view;
    }

    @Test public void cardinalAndDiagonalWallsUseMovementMasks()
    {
        int[][] flags = new int[8][8];
        SceneCollision collision = new SceneCollision(view(flags), 0, -1, -1);
        int[] dx = {-1, 1, 0, 0, -1, -1, 1, 1};
        int[] dy = {0, 0, -1, 1, -1, 1, -1, 1};
        int[] masks = {CollisionDataFlag.BLOCK_MOVEMENT_EAST, CollisionDataFlag.BLOCK_MOVEMENT_WEST,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST,
            CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST, CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST};
        for (int direction = 0; direction < dx.length; direction++)
        {
            assertTrue(collision.canTravel(3, 3, dx[direction], dy[direction]));
            flags[3 + dx[direction]][3 + dy[direction]] = masks[direction];
            assertFalse(collision.canTravel(3, 3, dx[direction], dy[direction]));
            flags[3 + dx[direction]][3 + dy[direction]] = 0;
        }
    }

    @Test public void everyDiagonalSideWallIsRechecked()
    {
        int[][] flags = new int[8][8];
        SceneCollision collision = new SceneCollision(view(flags), 0, -1, -1);
        for (int dx : new int[]{-1, 1})
        {
            for (int dy : new int[]{-1, 1})
            {
                flags[3 + dx][3] = dx < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST : CollisionDataFlag.BLOCK_MOVEMENT_WEST;
                assertFalse(collision.canTravel(3, 3, dx, dy));
                flags[3 + dx][3] = 0;
                flags[3][3 + dy] = dy < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH : CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
                assertFalse(collision.canTravel(3, 3, dx, dy));
                flags[3][3 + dy] = 0;
                assertTrue(collision.canTravel(3, 3, dx, dy));
            }
        }
    }

    @Test public void fullTilesAndThePlayerCannotBeChosen()
    {
        int[][] flags = new int[8][8];
        SceneCollision collision = new SceneCollision(view(flags), 0, 4, 3);
        assertFalse(collision.isLegal(4, 3));
        assertFalse(collision.canTravel(3, 3, 1, 0));
        flags[3][4] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        assertFalse(collision.isLegal(3, 4));
        assertFalse(collision.canTravel(3, 3, 0, 1));
        assertFalse(collision.canTravel(3, 3, 0, 0));
    }

    @Test public void shortNullAndMissingPlaneDataNeverReachTheNativeHelper()
    {
        int[][] flags = new int[5][];
        flags[0] = new int[1];
        flags[3] = new int[8];
        flags[4] = new int[1];
        SceneCollision collision = new SceneCollision(view(flags), 0, -1, -1);
        assertFalse(collision.canTravel(3, 3, 1, 1));
        assertFalse(collision.canTravel(3, 3, -1, 0));
        assertFalse(collision.canTravel(0, 0, -1, 0));
        assertFalse(new SceneCollision(view(flags), 2, -1, -1).isAvailable());
        assertFalse(new SceneCollision(null, 0, -1, -1).contains(0, 0));
    }

    @Test public void aDistantMissingCellIsUnknownAndCannotBecomePhysicalBlockage()
    {
        int[][] flags = new int[8][8];
        flags[7] = null;
        SceneCollision unknown = new SceneCollision(view(flags), 0, -1, -1);
        assertFalse(unknown.isAvailable());
        RoutePlanner.Goal goal = new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps) { return x == 5 && y == 3 ? 1 : -1; }
            @Override public int distance(int x, int y) { return MotionMath.distance(x, y, 5, 3); }
        };
        assertEquals(RoutePlanner.Status.SCENE_UNAVAILABLE,
            new RoutePlanner().plan(unknown, new RoutePlanner.Node(3, 3), goal).status);
        flags[7] = new int[7];
        assertFalse(new SceneCollision(view(flags), 0, -1, -1).isAvailable());
        flags[7] = new int[8];
        for (int[] row : flags) java.util.Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        flags[3][3] = 0;
        SceneCollision known = new SceneCollision(view(flags), 0, -1, -1);
        assertTrue(known.isAvailable());
        assertEquals(RoutePlanner.Status.BLOCKED,
            new RoutePlanner().plan(known, new RoutePlanner.Node(3, 3), goal).status);
    }
}
