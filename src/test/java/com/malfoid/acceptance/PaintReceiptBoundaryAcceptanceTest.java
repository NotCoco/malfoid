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
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real GameTick closure with render feedback crossing its boundary. */
public class PaintReceiptBoundaryAcceptanceTest
{
    @Test
    public void paintAcknowledgedOnNextCommitKeepsItsOriginalTickAndEnablesDeparture() throws Exception
    {
        Fixture f = new Fixture(Proof.STATIONARY, 32);
        f.commitAfterPaint();

        assertEquals(12, f.director.getLastPaint().getPaintedAtTick());
        SceneCandidate departure = f.depart();
        assertNotNull("A real frame-12 paint must survive frame-13 closure", departure);
        assertEquals("12", departure.getConditionFacts().get("parentFirstPaintTick"));
        assertTrue(f.observer.candidateStillValid(departure));
    }

    @Test
    public void lateReceiptCannotInventTheStationaryOrNearbyWitness() throws Exception
    {
        for (Proof proof : new Proof[]{Proof.MOVED, Proof.UNKNOWN, Proof.TOO_FAR})
        {
            Fixture f = new Fixture(proof, 32);
            f.commitAfterPaint();
            assertEquals(12, f.director.getLastPaint().getPaintedAtTick());
            assertNull("Unproved paint context: " + proof, f.depart());
        }
    }

    @Test
    public void originalDeadlineAndSceneChangeStillRefuseTheLateParent() throws Exception
    {
        Fixture expired = new Fixture(Proof.STATIONARY, 13);
        expired.commitAfterPaint();
        assertNull("Equality with the original deadline must revoke the parent", expired.depart());

        Fixture rebuilt = new Fixture(Proof.STATIONARY, 32);
        when(rebuilt.view.getScene()).thenReturn(mock(Scene.class));
        rebuilt.commitAfterPaint();
        assertNull("A frame from the previous scene must not create a parent", rebuilt.depart());
    }

    private enum Proof { STATIONARY, MOVED, UNKNOWN, TOO_FAR }

    private static final class Fixture
    {
        final Client client = mock(Client.class);
        final ClientThread clientThread = mock(ClientThread.class);
        final WorldView view = mock(WorldView.class);
        final Player player = mock(Player.class);
        final MalfoidPlugin plugin = new MalfoidPlugin();
        final Queue<Runnable> closures = new ArrayDeque<>();
        final NativeActor actor;
        final PresenceController presence;
        final ScenarioObserver observer;
        final SpeechDirector director;
        final Object session;
        final int companionX;
        WorldPoint playerPoint = new WorldPoint(3250, 3250, 0);

        Fixture(Proof proof, long deadline) throws Exception
        {
            companionX = proof == Proof.TOO_FAR ? 3254 : 3253;
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
            when(player.getWorldLocation()).thenAnswer(ignored -> playerPoint);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getPlayerOptions()).thenReturn(new String[8]);
            when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99);
            when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(99);
            MalfoidConfig config = mock(MalfoidConfig.class);
            when(config.showMalfoid()).thenReturn(true);
            when(config.showDialogue()).thenReturn(true);
            doAnswer(call -> { closures.add(call.getArgument(0)); return null; })
                .when(clientThread).invokeAtTickEnd(any(Runnable.class));
            field(plugin, "client").set(plugin, client);
            field(plugin, "clientThread").set(plugin, clientThread);
            field(plugin, "config").set(plugin, config);

            Gson gson = new Gson();
            actor = new NativeActor(client, gson);
            // Supply an already prepared/registered visual; baking and motion are outside this contract.
            Class<?> assetClass = Class.forName("com.malfoid.asset.NativeAsset");
            Method load = assetClass.getDeclaredMethod("load", Gson.class);
            load.setAccessible(true);
            Object asset = load.invoke(null, gson);
            Class<?> bankClass = Class.forName("com.malfoid.asset.NativePoseBank");
            Constructor<?> bankConstructor = bankClass.getDeclaredConstructor(Client.class, assetClass);
            bankConstructor.setAccessible(true);
            Object bank = bankConstructor.newInstance(client, asset);
            field(bank, "readyAt").set(bank, 1L);
            field(actor, "bank").set(actor, bank);
            field(actor, "visible").set(actor, true);
            field(actor, "view").set(actor, view);
            field(actor, "scene").set(actor, view.getScene());
            Model model = mock(Model.class);
            when(model.getVerticesY()).thenReturn(new float[]{-100});
            Constructor<?> placementConstructor = NativeActor.PlacementSnapshot.class.getDeclaredConstructor(
                Model.class, LocalPoint.class, WorldView.class, int.class, int.class, int.class, long.class);
            placementConstructor.setAccessible(true);
            field(actor, "placement").set(actor, placementConstructor.newInstance(model,
                new LocalPoint((companionX - 3200) * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL), view, 0, 0, 0, 1L));
            presence = new PresenceController(client);
            presence.commit(1, true, true, false, false);
            assertTrue(presence.commit(2, true, true, false, false).isPlacementAllowed());
            presence.placementSucceeded(2);

            observer = new ScenarioObserver(client, mock(ItemManager.class));
            observer.setPresence(true, false, false);
            if (proof == Proof.MOVED) { playerPoint = new WorldPoint(3249, 3250, 0); }
            if (proof == Proof.UNKNOWN) { playerPoint = null; }
            close(10);
            playerPoint = new WorldPoint(3250, 3250, 0);
            close(11);
            close(12);

            DialogueCatalog catalog = DialogueCatalog.load(MalfoidPlugin.class, gson);
            SpeechGuard guard = new SpeechGuard(() -> 0L, new Random(1),
                catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            director = new SpeechDirector(catalog, () -> 0L, new Random(1), ignored -> {}, guard);
            director.tick(12, 1, 1, true, true, false);
            SceneCandidate candidate = new SceneCandidate(1, "U01", 85, 12, deadline, 1, 1,
                "boundary:talk", Collections.singletonMap("talkStreak", "1"), false, 4);
            assertTrue(director.offer(candidate));
            director.arrive(candidate.getId(), 12);
            MalfoidSpeechOverlay overlay = new MalfoidSpeechOverlay(director, clientThread, error -> fail(error.toString()));
            overlay.publish(new Point(100, 100), new Rectangle(0, 0, 300, 300), null, 1);
            SpeechPacket packet = director.getPacket();
            assertNotNull(packet.paint(0, 12, 1, () -> {}));
            assertTrue(overlay.isDrawable());
            assertNull("No acknowledgement occurs before the next GameTick", director.getLastPaint());

            Class<?> sessionClass = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
            Constructor<?> constructor = sessionClass.getDeclaredConstructor(long.class, NativeActor.class,
                ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
            constructor.setAccessible(true);
            session = constructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
                new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
            field(session, "tick").set(session, 12L);
            field(session, "director").set(session, director);
            field(session, "overlay").set(session, overlay);
            field(session, "presenceSceneGeneration").set(session, presence.getSceneGeneration());
            field(plugin, "current").set(plugin, session);
        }

        void commitAfterPaint() throws Exception
        {
            plugin.onGameTick(new GameTick());
            assertEquals(1, closures.size());
            closures.remove().run();
            assertFalse("Fixture must reach receipt handling without the failure guard", ((AtomicBoolean) field(session, "failed").get(session)).get());
            assertEquals(13L, field(session, "tick").get(session));
        }

        SceneCandidate depart()
        {
            playerPoint = new WorldPoint(3248, 3250, 0);
            close(14);
            playerPoint = new WorldPoint(3246, 3250, 0);
            return close(15).stream().filter(candidate -> "M07".equals(candidate.getScenarioId())).findFirst().orElse(null);
        }

        List<SceneCandidate> close(long tick)
        {
            return observer.closeGameTick(tick, 1, 1, true, 2, companionX, 3250, false);
        }
    }

    private static Field field(Object instance, String name) throws Exception
    {
        Field result = instance.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
