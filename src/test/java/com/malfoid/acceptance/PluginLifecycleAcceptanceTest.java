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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Provider;
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
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Client callback contracts, without a client launch, profile directory, or login. */
public class PluginLifecycleAcceptanceTest
{
    @Test
    public void localDeathRemovesTheNativeObjectAndRevokesSpeechBeforeReturning() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        SpeechPacket issued = f.issueSpeech(s);

        f.plugin.onActorDeath(new ActorDeath(f.player));

        assertFalse(s.actor.isVisible());
        assertFalse(s.presence.isPresent());
        verify(f.client).removeRuneLiteObject(same(s.controller));
        assertNull(issued.paint(0, 2, 1, () -> fail("Death left a drawable packet")));
        assertTrue("Death cleanup must not wait for an async callback", f.clientQueue.isEmpty());
    }

    @Test
    public void showMalfoidOffRemovesTheObjectOnItsClientThreadCallback() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        SpeechPacket issued = f.issueSpeech(s);
        when(f.config.showMalfoid()).thenReturn(false);

        f.plugin.onConfigChanged(configOff("showMalfoid"));
        f.drainClientQueue();

        assertFalse(s.actor.isVisible());
        assertFalse(s.presence.isPresent());
        verify(f.client).removeRuneLiteObject(same(s.controller));
        assertNull(issued.paint(0, 2, 1, () -> fail("Hidden companion retained speech")));
    }

    @Test
    public void speechOffRevokesTheBubbleAndKeepsTheNativeCompanion() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        SpeechPacket issued = f.issueSpeech(s);
        when(f.config.showDialogue()).thenReturn(false);

        f.plugin.onConfigChanged(configOff("showDialogue"));
        f.drainClientQueue();

        assertTrue(s.actor.isVisible());
        assertTrue(s.presence.isPresent());
        verify(f.client, never()).removeRuneLiteObject(any());
        assertNull(issued.paint(0, 2, 1, () -> fail("Muted packet drew")));
    }

    @Test
    public void oldSettingsAndDisposalCallbacksCannotHideAReenabledCompanion() throws Exception
    {
        Fixture f = new Fixture();
        Session old = f.attachSession();
        f.plugin.onConfigChanged(configOff("showMalfoid"));
        f.stop();
        Session replacement = f.attachSession();
        SpeechPacket replacementSpeech = f.issueSpeech(replacement);

        f.drainClientQueue();

        assertFalse(old.actor.isVisible());
        assertTrue(replacement.actor.isVisible());
        assertTrue(replacement.presence.isPresent());
        verify(f.client, never()).removeRuneLiteObject(same(replacement.controller));
        assertTrue(replacementSpeech.canPaint(0));
    }

    @Test
    public void oldSpeechSettingCannotMuteANewLoginInTheSameEnabledPlugin() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        f.issueSpeech(s);
        f.plugin.onConfigChanged(configOff("showDialogue"));
        f.state(GameState.LOGIN_SCREEN);
        f.state(GameState.LOGGED_IN);
        f.makePresent(s);
        SpeechPacket newLoginSpeech = f.issueSpeech(s);

        f.drainClientQueue();

        assertTrue(s.actor.isVisible());
        assertTrue(newLoginSpeech.canPaint(0));
    }

    @Test
    public void anOldTickCommitDoesNoClientWorkAfterLogout() throws Exception
    {
        Fixture f = new Fixture();
        f.attachSession();
        f.plugin.onGameTick(new GameTick());
        assertEquals(1, f.tickEndQueue.size());
        f.state(GameState.LOGIN_SCREEN);
        clearInvocations(f.client, f.messages, f.overlays);

        f.drainTickEndQueue();

        verifyNoInteractions(f.client, f.messages, f.overlays);
    }

    @Test
    public void ordinaryLoadingRetainsAnUnpaintedLevelButLogoutAndDeathCancelIt() throws Exception
    {
        for (boolean death : new boolean[]{false, true})
        {
            Fixture f = new Fixture();
            Session s = f.attachSession();
            f.offerLevel(s);

            f.state(GameState.LOADING);
            assertFalse(s.actor.isVisible());
            assertTrue("Ordinary LOADING discarded the witnessed level", s.director.hasRetainedLevel());

            if (death)
            {
                f.plugin.onActorDeath(new ActorDeath(f.player));
            }
            else
            {
                f.state(GameState.LOGIN_SCREEN);
            }
            assertFalse(s.director.hasRetainedLevel());
            assertTrue(s.director.isIdle());
        }
    }

    @Test
    public void ordinaryLoadingCannotRetainAnUnwitnessedLevel() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        assertTrue(s.director.offer(f.candidate(s, "M04", "newRealLevel", "20")));

        f.state(GameState.LOADING);

        assertFalse(s.director.hasRetainedLevel());
        assertTrue(s.director.isIdle());
    }

    @Test
    public void aQueuedOldFailureCannotPostNoticesOrDisposeTheNewSession() throws Exception
    {
        Fixture f = new Fixture();
        Session old = f.attachSession();
        when(f.client.getGameState()).thenThrow(new IllegalStateException("acceptance failure"));
        f.plugin.onVarbitChanged(new VarbitChanged());
        f.stop();
        doAnswer(ignored -> f.gameState).when(f.client).getGameState();
        Session replacement = f.attachSession();
        clearInvocations(f.client, f.messages);

        f.drainClientQueue();

        assertFalse(old.actor.isVisible());
        assertTrue(replacement.actor.isVisible());
        verify(f.client, never()).removeRuneLiteObject(same(replacement.controller));
        verifyNoInteractions(f.messages);
    }

    @Test
    public void callbackFailureStopsFurtherCallbacksAndRevokesItsIssuedPacket() throws Exception
    {
        Fixture f = new Fixture();
        Session s = f.attachSession();
        SpeechPacket issued = f.issueSpeech(s);
        when(f.client.getGameState()).thenThrow(new IllegalStateException("acceptance failure"));

        f.plugin.onClientTick(new ClientTick());
        int cleanupCallbacks = f.clientQueue.size();
        clearInvocations(f.client);
        f.plugin.onClientTick(new ClientTick());
        f.plugin.onGameTick(new GameTick());
        f.plugin.onVarbitChanged(new VarbitChanged());
        verifyNoInteractions(f.client);
        assertEquals(cleanupCallbacks, f.clientQueue.size());
        assertTrue(f.tickEndQueue.isEmpty());
        doAnswer(ignored -> f.gameState).when(f.client).getGameState();

        f.drainClientQueue();

        assertFalse(s.actor.isVisible());
        assertNull(issued.paint(0, 2, 1, () -> fail("Failed session retained a drawable packet")));
    }

    @Test
    public void rejectedLifecycleSchedulingDoesNotEscapeThePluginCallbacks() throws Exception
    {
        Fixture f = new Fixture();
        f.attachSession();
        doThrow(new RejectedExecutionException("acceptance queue rejection"))
            .when(f.clientThread).invokeLater(any(Runnable.class));

        f.plugin.onConfigChanged(configOff("showMalfoid"));
        f.stop();
    }

    private static ConfigChanged configOff(String key)
    {
        ConfigChanged event = new ConfigChanged();
        event.setGroup("malfoid");
        event.setKey(key);
        event.setOldValue("true");
        event.setNewValue("false");
        return event;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object injectedValue(Object target, String fieldName) throws Exception
    {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Session
    {
        final Object rootSession;
        final NativeActor actor;
        final PresenceController presence;
        final SpeechDirector director;
        final RuneLiteObjectController controller;

        Session(Object rootSession, NativeActor actor, PresenceController presence, SpeechDirector director)
            throws Exception
        {
            this.rootSession = rootSession;
            this.actor = actor;
            this.presence = presence;
            this.director = director;
            controller = (RuneLiteObjectController) injectedValue(actor, "controller");
        }
    }

    private static final class Fixture
    {
        final MalfoidPlugin plugin = new MalfoidPlugin();
        final Client client = mock(Client.class);
        final Player player = mock(Player.class);
        final WorldView view = mock(WorldView.class);
        final MalfoidConfig config = mock(MalfoidConfig.class);
        final ClientThread clientThread = mock(ClientThread.class);
        final ConfigManager configuration = mock(ConfigManager.class);
        final ChatMessageManager messages = mock(ChatMessageManager.class);
        final OverlayManager overlays = mock(OverlayManager.class);
        final Queue<Runnable> clientQueue = new ArrayDeque<>();
        final Queue<Runnable> tickEndQueue = new ArrayDeque<>();
        final AtomicLong clock = new AtomicLong();
        final Gson gson = new Gson();
        GameState gameState = GameState.LOGGED_IN;
        long sequence;

        Fixture() throws Exception
        {
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
            when(client.getGameState()).thenAnswer(ignored -> gameState);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getPlayerOptions()).thenReturn(new String[8]);
            when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99);
            when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(99);
            when(config.showMalfoid()).thenReturn(true);
            when(config.showDialogue()).thenReturn(true);
            doAnswer(call -> { clientQueue.add(call.getArgument(0)); return null; })
                .when(clientThread).invokeLater(any(Runnable.class));
            doAnswer(call -> { tickEndQueue.add(call.getArgument(0)); return null; })
                .when(clientThread).invokeAtTickEnd(any(Runnable.class));
            inject(plugin, "client", client);
            inject(plugin, "clientThread", clientThread);
            inject(plugin, "configManager", configuration);
            inject(plugin, "config", config);
            inject(plugin, "gson", gson);
            inject(plugin, "overlayManager", overlays);
            inject(plugin, "executor", mock(ScheduledExecutorService.class));
        }

        Session attachSession() throws Exception
        {
            NativeActor actor = new NativeActor(client, gson);
            ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
            PresenceController presence = new PresenceController(client);
            MalfoidMenu menu = new MalfoidMenu(client);
            ChatNotices notices = new ChatNotices(client, messages, configuration);
            inject(plugin, "actors", provider(actor));
            inject(plugin, "observers", provider(observer));
            inject(plugin, "presences", provider(presence));
            inject(plugin, "menus", provider(menu));
            inject(plugin, "notices", provider(notices));
            AtomicLong generations = (AtomicLong) injectedValue(plugin, "generations");
            Class<?> sessionType = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
            Constructor<?> constructor = sessionType.getDeclaredConstructor(long.class, NativeActor.class,
                ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
            constructor.setAccessible(true);
            Object rootSession = constructor.newInstance(generations.incrementAndGet(), actor, observer,
                presence, menu, notices);
            DialogueCatalog catalog = DialogueCatalog.load(MalfoidPlugin.class, gson);
            SpeechGuard guard = new SpeechGuard(clock::get, new Random(1),
                catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            SpeechDirector director = new SpeechDirector(catalog, clock::get, new Random(1), ignored -> {}, guard);
            inject(rootSession, "director", director);
            inject(rootSession, "overlay", new MalfoidSpeechOverlay(director, clientThread, error -> fail(error.toString())));
            inject(plugin, "current", rootSession);
            Session result = new Session(rootSession, actor, presence, director);
            makePresent(result);
            return result;
        }

        void makePresent(Session s) throws Exception
        {
            s.presence.commit(1, true, true, false, false);
            assertTrue(s.presence.commit(2, true, true, false, false).isPlacementAllowed());
            s.presence.placementSucceeded(2);
            // Inject only an already registered object's state; these tests exercise removal, not placement.
            inject(s.actor, "visible", true);
            inject(s.rootSession, "tick", 2L);
            inject(s.rootSession, "presenceSceneGeneration", s.presence.getSceneGeneration());
        }

        SpeechPacket issueSpeech(Session s) throws Exception
        {
            SceneCandidate candidate = candidate(s, "U01", "talkStreak", "1");
            assertTrue(s.director.offer(candidate));
            s.director.arrive(candidate.getId(), 2);
            SpeechPacket packet = s.director.getPacket();
            assertNotNull(packet);
            return packet;
        }

        void offerLevel(Session s) throws Exception
        {
            long occurred = 12;
            long generation = (long) injectedValue(s.rootSession, "sessionGeneration");
            long revision = (long) injectedValue(s.rootSession, "sceneRevision");
            inject(s.rootSession, "tick", occurred);
            s.director.tick(occurred, generation, revision, true, true, false);
            Map<String, String> facts = new HashMap<>();
            facts.put("skill", "ATTACK");
            facts.put("newRealLevel", "20");
            facts.put("newLevel", "20");
            facts.put("priorRealLevel", "10");
            facts.put("xpDelta", "100");
            facts.put("progressSkills", "ATTACK");
            facts.put("witnessedPresent", "true");
            facts.put("occurredTick", Long.toString(occurred));
            facts.put("witnessPresenceEpoch", Long.toString(s.presence.getPresentSinceTick()));
            facts.put("settlingNotBeforeTick", Long.toString(s.presence.getPresentSinceTick() + 10));
            assertTrue(s.director.offer(new SceneCandidate(++sequence, "M04", 45,
                occurred, occurred + 10, generation, revision, "progress:" + occurred, facts, false, 8)));
        }

        SceneCandidate candidate(Session s, String scenario, String fact, String value) throws Exception
        {
            long generation = (long) injectedValue(s.rootSession, "sessionGeneration");
            long revision = (long) injectedValue(s.rootSession, "sceneRevision");
            s.director.tick(2, generation, revision, true, true, false);
            return new SceneCandidate(++sequence, scenario, "U01".equals(scenario) ? 85 : 45,
                2, 12, generation, revision, "acceptance:" + sequence,
                Collections.singletonMap(fact, value), false, 4);
        }

        void state(GameState state)
        {
            gameState = state;
            GameStateChanged event = new GameStateChanged();
            event.setGameState(state);
            plugin.onGameStateChanged(event);
        }

        void stop() throws Exception
        {
            Method shutdown = MalfoidPlugin.class.getDeclaredMethod("shutDown");
            shutdown.setAccessible(true);
            try { shutdown.invoke(plugin); }
            catch (InvocationTargetException failure)
            {
                if (failure.getCause() instanceof Exception) { throw (Exception) failure.getCause(); }
                throw (Error) failure.getCause();
            }
        }

        void drainClientQueue() { drain(clientQueue); }
        void drainTickEndQueue() { drain(tickEndQueue); }

        private static void drain(Queue<Runnable> queue)
        {
            for (int count = 0; !queue.isEmpty(); count++)
            {
                assertTrue("Lifecycle callbacks kept rescheduling themselves", count < 30);
                queue.remove().run();
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> Provider<T> provider(T value)
        {
            Provider<T> provider = mock(Provider.class);
            when(provider.get()).thenReturn(value);
            return provider;
        }
    }
}
