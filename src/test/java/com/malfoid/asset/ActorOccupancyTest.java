package com.malfoid.asset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ActorOccupancyTest
{
    private static <T> IndexedObjectSet<T> set(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }

    private static Player player(int x, int y, int viewId)
    {
        Player player = mock(Player.class);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(x * 128 + 64, y * 128 + 64, viewId));
        return player;
    }

    @Test public void completeSampleCountsOnlyNearbyOtherPlayersAndCopiesOccupiedTiles()
    {
        Client client = mock(Client.class);
        WorldView view = mock(WorldView.class);
        when(view.getId()).thenReturn(9);
        Player local = player(32, 32, 9);
        List<Player> players = new ArrayList<>();
        players.add(local);
        for (int offset = 1; offset <= 8; offset++) players.add(player(33, 32 + offset % 6, 9));
        players.add(player(39, 32, 9));
        players.add(player(32, 32, 10));
        NPC npc = mock(NPC.class);
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(31 * 128 + 64, 32 * 128 + 64, 9));
        doReturn(set(players)).when(view).players();
        doReturn(set(Arrays.asList(npc))).when(view).npcs();
        ActorOccupancy.Snapshot sample = ActorOccupancy.sample(client, view, local, 32, 32);
        assertTrue(sample.complete);
        assertEquals(8, sample.otherPlayers);
        assertFalse(sample.occupied(32, 32));
        assertTrue(sample.occupied(33, 34));
        assertTrue(sample.occupied(31, 32));
        assertFalse(sample.occupied(39, 32));
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(30 * 128 + 64, 32 * 128 + 64, 9));
        assertTrue(sample.occupied(31, 32));
        assertFalse(sample.occupied(30, 32));
    }

    @Test public void truncatedSampleAppliesNeitherCrowdKnowledgeNorOccupancyPenalty()
    {
        Client client = mock(Client.class);
        WorldView view = mock(WorldView.class);
        when(view.getId()).thenReturn(9);
        Player local = player(32, 32, 9);
        List<Player> players = new ArrayList<>();
        players.add(local);
        for (int count = 0; count < 256; count++) players.add(player(33, 32, 9));
        doReturn(set(players)).when(view).players();
        doReturn(set(Collections.<NPC>emptyList())).when(view).npcs();
        ActorOccupancy.Snapshot sample = ActorOccupancy.sample(client, view, local, 32, 32);
        assertFalse(sample.complete);
        assertFalse(sample.complete && sample.otherPlayers >= 8);
        assertFalse(sample.occupied(33, 32));
    }

    @Test public void missingCollectionsOrPositionsDoNotTurnUnknownIntoUnoccupied()
    {
        Client client = mock(Client.class);
        WorldView view = mock(WorldView.class);
        Player local = player(32, 32, 0);
        assertFalse(ActorOccupancy.sample(client, view, local, 32, 32).complete);
        doReturn(set(Arrays.asList(local, mock(Player.class)))).when(view).players();
        doReturn(set(Collections.<NPC>emptyList())).when(view).npcs();
        ActorOccupancy.Snapshot sample = ActorOccupancy.sample(client, view, local, 32, 32);
        assertFalse(sample.complete);
        assertFalse(sample.occupied(32, 32));
    }

    @Test public void privateZonesExcludePlayersBeforeNullChecksCopyAndTheActorBudget()
    {
        for (int zone = 0; zone < 3; zone++)
        {
            Client client = mock(Client.class); WorldView view = mock(WorldView.class); when(view.getId()).thenReturn(9);
            when(client.getWorldType()).thenReturn(zone == 0 ? EnumSet.noneOf(WorldType.class)
                : EnumSet.of(zone == 1 ? WorldType.PVP : WorldType.DEADMAN));
            when(client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(zone == 0 ? 1 : 0);
            Player local = player(32, 32, 9); NPC npc = mock(NPC.class);
            when(npc.getLocalLocation()).thenReturn(new LocalPoint(31 * 128 + 64, 32 * 128 + 64, 9));
            doReturn(set(Collections.singletonList(npc))).when(view).npcs();
            ActorOccupancy.Snapshot alone = ActorOccupancy.sample(client, view, local, 32, 32);
            List<Player> bystanders = new ArrayList<>();
            for (int count = 0; count < 300; count++) { bystanders.add(mock(Player.class)); }
            doReturn(set(bystanders)).when(view).players(); clearInvocations(view);
            ActorOccupancy.Snapshot crowded = ActorOccupancy.sample(client, view, local, 32, 32);
            assertTrue(alone.complete && crowded.complete && crowded.ignoresOtherPlayers);
            assertEquals(0, crowded.otherPlayers); assertArrayEquals(alone.occupied, crowded.occupied);
            assertTrue(crowded.occupied(31, 32)); verify(view, never()).players();
            for (Player other : bystanders) { verifyNoInteractions(other); }
        }
    }
}
