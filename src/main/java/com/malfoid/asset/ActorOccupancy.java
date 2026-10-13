package com.malfoid.asset;

import com.malfoid.presence.OtherPlayerPolicy;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/** Copies at most 256 active-view actor positions; incomplete samples apply no soft occupancy assumptions. */
final class ActorOccupancy
{
    static final class Snapshot
    {
        final boolean[] occupied = new boolean[169];
        final int playerX, playerY;
        int otherPlayers;
        boolean ignoresOtherPlayers;
        boolean complete = true;
        Snapshot(int playerX, int playerY) { this.playerX = playerX; this.playerY = playerY; }
        boolean occupied(int x, int y)
        {
            int dx = x - playerX + 6, dy = y - playerY + 6;
            return complete && dx >= 0 && dy >= 0 && dx < 13 && dy < 13 && occupied[dy * 13 + dx];
        }
    }

    private ActorOccupancy() {}

    static Snapshot sample(Client client, WorldView view, Player local, int x, int y)
    {
        Snapshot result = new Snapshot(x, y);
        boolean ignorePlayers = OtherPlayerPolicy.ignoreOtherPlayers(client);
        result.ignoresOtherPlayers = ignorePlayers;
        if (!ignorePlayers && view.players() == null || view.npcs() == null)
        {
            result.complete = false;
            return result;
        }
        int inspected = 0;
        if (!ignorePlayers)
        {
            for (Player player : view.players())
            {
                if (inspected++ >= 256)
                {
                    result.complete = false;
                    return result;
                }
                if (player != local && copy(player, view, result)) result.otherPlayers++;
            }
        }
        for (NPC npc : view.npcs())
        {
            if (inspected++ >= 256)
            {
                result.complete = false;
                return result;
            }
            copy(npc, view, result);
        }
        copy(client.getFollower(), view, result);
        return result;
    }

    private static boolean copy(Actor actor, WorldView view, Snapshot result)
    {
        if (actor == null) return false;
        LocalPoint point = actor.getLocalLocation();
        if (point == null)
        {
            result.complete = false;
            return false;
        }
        if (point.getWorldView() != view.getId()) return false;
        int dx = point.getSceneX() - result.playerX + 6, dy = point.getSceneY() - result.playerY + 6;
        if (dx < 0 || dy < 0 || dx >= 13 || dy >= 13) return false;
        result.occupied[dy * 13 + dx] = true;
        return true;
    }
}
