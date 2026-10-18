package com.malfoid.asset;

import com.malfoid.motion.CreatureAwareness;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CreatureIntakeFollowerTest
{
    @Test public void onlyTheExactLocalFollowerCanAcquireTheIdentityOnlyPetGroup()
    {
        Client client = mock(Client.class); WorldView view = view();
        NPC mine = npc(view, "Rat", true), theirs = npc(view, "Rat", true), wild = npc(view, "Rat", false);
        when(client.getFollower()).thenReturn(mine); doReturn(indexed(Arrays.asList(theirs, mine, wild))).when(view).npcs();
        CreatureIntake intake = new CreatureIntake();
        intake.setLocalFollowerClassification(new CreatureAwareness.Classification("CR-PET", 8,
            CreatureAwareness.Behaviour.WIZARD, 1, 2));
        CreatureAwareness.Sample sample = intake.sample(client, view, 1);
        assertTrue(sample.known); assertEquals(2, sample.creatures.size());
        CreatureAwareness.Creature pet = sample.creatures.get(0);
        assertEquals("CR-PET", pet.classification.groupId); assertEquals("F01", sample.creatures.get(1).classification.groupId);
        assertNotNull(intake.current(client, view, pet.key));
        when(client.getFollower()).thenReturn(theirs);
        assertNull("Ownership loss revokes the old identity before another paint", intake.current(client, view, pet.key));
        verify(view, never()).players();
    }

    @Test public void unavailableIdentityClassificationKeepsLegacyResourcesAndAllFollowersExcluded()
    {
        Client client = mock(Client.class); WorldView view = view(); NPC own = npc(view, "Rat", false);
        when(client.getFollower()).thenReturn(own); doReturn(indexed(Arrays.asList(own, npc(view, "Rat", true)))).when(view).npcs();
        CreatureAwareness.Sample sample = new CreatureIntake().sample(client, view, 1);
        assertTrue(sample.known); assertTrue(sample.creatures.isEmpty());
    }

    @Test public void aDeadWrongPlaneOrWrongViewLocalFollowerCannotCreatePetEvidence()
    {
        Client client = mock(Client.class); WorldView view = view();
        for (int invalid = 0; invalid < 3; invalid++)
        {
            NPC mine = npc(view, "Cat", true);
            if (invalid == 0) { when(mine.isDead()).thenReturn(true); }
            if (invalid == 1) { when(mine.getWorldLocation()).thenReturn(new WorldPoint(3252, 3250, 1)); }
            if (invalid == 2) { when(mine.getWorldView()).thenReturn(mock(WorldView.class)); }
            when(client.getFollower()).thenReturn(mine); doReturn(indexed(Arrays.asList(mine))).when(view).npcs();
            CreatureIntake intake = new CreatureIntake();
            intake.setLocalFollowerClassification(new CreatureAwareness.Classification("CR-PET", 8,
                CreatureAwareness.Behaviour.WIZARD, 1, 2));
            assertTrue(intake.sample(client, view, 1).creatures.isEmpty());
        }
    }

    private static WorldView view()
    {
        WorldView view = mock(WorldView.class); when(view.getScene()).thenReturn(mock(Scene.class));
        when(view.getId()).thenReturn(WorldView.TOPLEVEL); when(view.getBaseX()).thenReturn(3200);
        when(view.getBaseY()).thenReturn(3200); when(view.getPlane()).thenReturn(0);
        when(view.getSizeX()).thenReturn(104); when(view.getSizeY()).thenReturn(104); return view;
    }
    private static NPC npc(WorldView view, String name, boolean follower)
    {
        NPC npc = mock(NPC.class); NPCComposition definition = mock(NPCComposition.class);
        when(definition.getName()).thenReturn(name); when(definition.isFollower()).thenReturn(follower);
        when(npc.getTransformedComposition()).thenReturn(definition); when(npc.getWorldView()).thenReturn(view);
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(52 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
        when(npc.getWorldLocation()).thenReturn(new WorldPoint(3252, 3250, 0)); return npc;
    }
    private static <T> IndexedObjectSet<T> indexed(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
}
