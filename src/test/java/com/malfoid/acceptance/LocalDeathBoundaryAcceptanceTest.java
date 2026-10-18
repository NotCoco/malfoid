package com.malfoid.acceptance;

import com.google.gson.Gson;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A late local ActorDeath must revoke presentation within that same callback. */
public class LocalDeathBoundaryAcceptanceTest
{
    @Test
    public void rememberedLocalDeathAfterPlayerBecomesNullHidesObjectAndPaintedBubble() throws Exception
    {
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        Player player = mock(Player.class);
        WorldView view = mock(WorldView.class);
        CollisionData collision = mock(CollisionData.class);
        when(collision.getFlags()).thenReturn(new int[104][104]);
        when(view.getId()).thenReturn(WorldView.TOPLEVEL);
        when(view.getScene()).thenReturn(mock(Scene.class));
        when(view.isTopLevel()).thenReturn(true);
        when(view.getBaseX()).thenReturn(3200);
        when(view.getBaseY()).thenReturn(3200);
        when(view.getSizeX()).thenReturn(104);
        when(view.getSizeY()).thenReturn(104);
        when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
        when(player.getWorldView()).thenReturn(view);
        when(player.getLocalLocation()).thenReturn(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
        when(player.getWorldLocation()).thenReturn(new WorldPoint(3250, 3250, 0));
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getPlayerOptions()).thenReturn(new String[8]);
        when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99);
        when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(99);
        MalfoidConfig config = mock(MalfoidConfig.class);
        when(config.showMalfoid()).thenReturn(true);

        Gson gson = new Gson();
        NativeActor actor = new NativeActor(client, gson);
        PresenceController presence = new PresenceController(client);
        presence.commit(1, true, true, false, false);
        assertTrue(presence.commit(2, true, true, false, false).isPlacementAllowed());
        presence.placementSucceeded(2);
        // The object already exists; model baking and placement are tested separately.
        field(actor, "visible").set(actor, true);
        RuneLiteObjectController controller = (RuneLiteObjectController) field(actor, "controller").get(actor);
        ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
        observer.setPresence(true, false, false);
        observer.closeGameTick(2, 1, 1, true, 2, 3250, 3250, true);

        DialogueCatalog catalog = DialogueCatalog.load(MalfoidPlugin.class, gson);
        SpeechGuard guard = new SpeechGuard(() -> 0L, new Random(1),
            catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
        SpeechDirector director = new SpeechDirector(catalog, () -> 0L, new Random(1), ignored -> {}, guard);
        director.tick(2, 1, 1, true, true, false);
        SceneCandidate candidate = new SceneCandidate(1, "U01", 85, 2, 12, 1, 1,
            "boundary:talk", Collections.singletonMap("talkStreak", "1"), false, 4);
        assertTrue(director.offer(candidate));
        director.arrive(candidate.getId(), 2);
        SpeechPacket packet = director.getPacket();
        assertNotNull(packet);
        assertNotNull(packet.paint(0, 2, 1, () -> {}));
        assertTrue(packet.isPainted());

        MalfoidPlugin plugin = new MalfoidPlugin();
        field(plugin, "client").set(plugin, client);
        field(plugin, "clientThread").set(plugin, clientThread);
        field(plugin, "config").set(plugin, config);
        Class<?> sessionClass = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
        Constructor<?> constructor = sessionClass.getDeclaredConstructor(long.class, NativeActor.class,
            ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
            new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
        field(session, "tick").set(session, 2L);
        field(session, "director").set(session, director);
        field(session, "overlay").set(session,
            new MalfoidSpeechOverlay(director, clientThread, failure -> fail(failure.toString())));
        field(plugin, "current").set(plugin, session);
        when(client.getLocalPlayer()).thenReturn(null);

        plugin.onActorDeath(new ActorDeath(player));

        assertTrue(observer.hasPendingDeath());
        assertFalse(actor.isVisible());
        assertFalse(presence.isPresent());
        verify(client).removeRuneLiteObject(same(controller));
        assertNull(packet.paint(0, 2, 1, () -> fail("A known local death left a painted bubble")));
        assertFalse(((AtomicBoolean) field(session, "failed").get(session)).get());
        verify(clientThread, never()).invokeLater(any(Runnable.class));
        verify(clientThread, never()).invokeAtTickEnd(any(Runnable.class));
    }

    private static Field field(Object instance, String name) throws Exception
    {
        Field result = instance.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
