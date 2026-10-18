package com.malfoid.acceptance;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation;
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native goals and edges, committed root observations, and actual overlay paint supply every receipt. */
public class CatchupDeliveryAcceptanceTest
{
    @Test public void aGenuineFarCatchupAndSixMovingNearSamplesCanPaintM51AndThenOwnItsStop() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup();
        MotionObservation completion = f.finishMovingCatchup(accepted);
        SceneCandidate c = f.sixMovingSamples(completion); assertNotNull(c); assertEquals("M51", c.getScenarioId());
        assertEquals(completion.completedTick + 6, c.getCreatedTick()); assertEquals(12, c.getExpiresTick() - c.getCreatedTick());
        assertEquals("catchup:" + accepted.requestId, c.getCausalToken()); assertEquals(accepted.requestId, f.observer.getMotionRequestId(c));
        assertTrue(f.catalogue.hasValidOriginalContext(c)); assertSame(completion, f.actor.getMotionObservation());
        assertTrue(f.actor.hasMotionSceneArrived(c.getId())); assertNull(f.actor.getMotionHoldReceipt(c.getId()));
        assertFalse(f.director.isCommitted()); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        long deadline = c.getExpiresTick(); f.paint(packet); assertTrue(f.director.isCommitted());
        assertEquals(c.getId(), f.director.getLastPaint().getCandidate().getId()); assertEquals(1, f.guard.textCount());
        assertEquals(1, f.guard.interactionCount()); f.commit();
        MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(c.getId()); assertNotNull(hold); assertSame(completion, hold.completion);
        assertTrue(hold.legalStanding); assertEquals(accepted.requestId, hold.requestId); assertFalse(f.director.isContinuationCancelled());
        while (f.tick() < f.director.getHoldUntilTick() - 1)
        {
            f.commit(); assertEquals(c.getId(), f.director.getActiveCandidate().getId()); assertFalse(f.director.isContinuationCancelled());
            assertEquals(accepted.requestId, f.actor.getMotionHoldReceipt(c.getId()).requestId);
        }
        assertEquals(deadline, c.getExpiresTick()); assertTrue(f.actor.getLastVoluntaryMovementTick() < 0);
    }

    @Test public void stoppingBeforeM51PaintRevokesTheIssuedPacketWithoutCommittingItsHistory() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup();
        SceneCandidate c = f.sixMovingSamples(f.finishMovingCatchup(accepted)); assertNotNull(c);
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); long deadline = c.getExpiresTick();
        f.commit(); f.assertNoPaint(packet); assertFalse(f.observer.candidateContinuationValid(c));
        assertEquals(deadline, c.getExpiresTick()); assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void unknownCombatBeforeM51PaintRevokesTheCapabilityInTheUnchangedScope() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup();
        SceneCandidate c = f.sixMovingSamples(f.finishMovingCatchup(accepted)); assertNotNull(c);
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); NPC unresolved = mock(NPC.class);
        when(unresolved.getWorldView()).thenReturn(f.view); when(unresolved.getTransformedComposition()).thenReturn(null);
        when(f.player.getInteracting()).thenReturn(unresolved); f.commit();
        assertEquals(ScenarioRules.CombatState.UNKNOWN, f.observer.getCombatState());
        assertEquals(c.getSceneRevision(), ((ScenarioRules) get(f.observer, "rules")).getCurrent().sceneRevision);
        f.assertNoPaint(packet); when(f.player.getInteracting()).thenReturn(null); f.commit();
        assertFalse(f.observer.candidateContinuationValid(c)); assertEquals(0, f.guard.textCount());
    }

    @Test public void anOverflowedRootIntervalCannotCarryOldNativeCompletionIntoM51() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup();
        for (int i = 0; i <= 256; i++) { f.plugin.onInteractingChanged(new InteractingChanged(f.player, null)); }
        MotionObservation completion = f.finishMovingCatchup(accepted);
        assertTrue("An actual incomplete committed observation cuts the witness", f.invalidCommitSeen);
        assertEquals(accepted.requestId, completion.requestId); assertEquals(accepted.placementGeneration, completion.placementGeneration);
        assertTrue("The old route genuinely completes; its narrative witness was interrupted", completion.known && completion.completedRunEdges >= 2);
        assertNull(f.sixMovingSamples(completion)); assertNull(f.director.getPacket()); assertEquals(0, f.guard.textCount());
    }

    @Test public void anUnpaintedM51UsesItsOriginalDeadlineWhileOrdinaryFollowingRemainsLegal() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup();
        SceneCandidate c = f.sixMovingSamples(f.finishMovingCatchup(accepted)); assertNotNull(c);
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); long deadline = c.getExpiresTick();
        int[][] remainingRing = {{45, 47}, {46, 47}, {47, 47}, {48, 47}, {48, 48}, {48, 49},
            {48, 50}, {48, 51}, {48, 52}, {48, 53}, {47, 53}, {46, 53}};
        for (int[] point : remainingRing) { f.move(point[0], point[1]); f.goalMask(45, 50); f.commit(); f.cycles(30); }
        assertEquals(deadline, f.tick()); assertEquals(deadline, c.getExpiresTick());
        f.assertNoPaint(packet); assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void aStoppedCatchupAtTenGenuinelyReachesItsRestingGoalBeforeTheFourStillBoundary() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startFarCatchup(); f.clearCollision();
        for (int still = 0; still < 8; still++) { f.commit(); f.cycles(30); }
        MotionObservation completion = f.actor.getLatestMotionCompletion(); assertNotNull(completion);
        assertEquals(accepted.requestId, completion.requestId); assertEquals(10, completion.startDistance);
        assertTrue(completion.known && completion.ordinary && completion.completedEdges > 0); assertFalse(f.actor.isMoving());
        assertEquals(5, f.actor.getDistanceToPlayer()); assertSame(completion, f.actor.getMotionObservation());
        assertEquals(MotionObservation.Status.ARRIVED, completion.status); assertFalse(f.observer.isPlayerMoving());
        assertEquals(3, completion.completedTick - accepted.startedTick);
        assertEquals(f.actor.getLocation().getSceneX() + 3200, completion.goalWorldX);
        assertEquals(f.actor.getLocation().getSceneY() + 3200, completion.goalWorldY);
        assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket()); assertEquals(0, f.guard.textCount());
        assertEquals(0, f.actor.getRecoveryCount());
    }

    private static final class Fixture
    {
        final Client client = mock(Client.class); final ClientThread clientThread = mock(ClientThread.class);
        final WorldView view = mock(WorldView.class); final Scene scene = mock(Scene.class); final Player player = mock(Player.class);
        final Tile[][][] tiles = new Tile[4][104][104]; final int[][] flags = new int[104][104];
        final Queue<Runnable> closures = new ArrayDeque<>(), callbacks = new ArrayDeque<>(); final List<Throwable> failures = new ArrayList<>();
        final MalfoidPlugin plugin = new MalfoidPlugin(); final NativeActor actor; final ScenarioObserver observer;
        final PresenceController presence; final DialogueCatalog catalogue; final SpeechDirector director;
        final SpeechGuard guard; final MalfoidSpeechOverlay overlay; final Object session;
        int cycle; long frame; boolean invalidCommitSeen; WorldPoint playerPoint = new WorldPoint(3250, 3250, 0);

        Fixture() throws Exception
        {
            when(client.isClientThread()).thenReturn(true); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getLocalPlayer()).thenReturn(player); when(client.getWorldView(WorldView.TOPLEVEL)).thenReturn(view);
            when(client.getTopLevelWorldView()).thenReturn(view); when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getPlayerOptions()).thenReturn(new String[8]); when(client.getGameCycle()).thenAnswer(call -> cycle);
            when(view.getId()).thenReturn(WorldView.TOPLEVEL); when(view.isTopLevel()).thenReturn(true); when(view.getScene()).thenReturn(scene);
            when(view.getBaseX()).thenReturn(3200); when(view.getBaseY()).thenReturn(3200);
            when(view.getSizeX()).thenReturn(104); when(view.getSizeY()).thenReturn(104); when(view.contains(any(LocalPoint.class))).thenReturn(true);
            when(view.getTileSettings()).thenReturn(new byte[4][104][104]); when(scene.getTiles()).thenReturn(tiles);
            int[][][] heights = new int[4][105][105]; for (int[][] level : heights) { for (int[] row : level) { Arrays.fill(row, 120); } }
            when(view.getTileHeights()).thenReturn(heights); CollisionData collision = mock(CollisionData.class);
            when(collision.getFlags()).thenReturn(flags); when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
            when(player.getWorldView()).thenReturn(view); when(player.getWorldLocation()).thenAnswer(call -> playerPoint);
            when(player.getLocalLocation()).thenAnswer(call -> new LocalPoint((playerPoint.getX() - 3200) * 128 + 64,
                (playerPoint.getY() - 3200) * 128 + 64, WorldView.TOPLEVEL));
            when(player.getAnimation()).thenReturn(-1);
            int[] levels = new int[Skill.values().length], xp = new int[Skill.values().length]; Arrays.fill(levels, 50); Arrays.fill(xp, 1000);
            when(client.getRealSkillLevels()).thenReturn(levels); when(client.getBoostedSkillLevels()).thenReturn(levels); when(client.getSkillExperiences()).thenReturn(xp);
            when(client.getRealSkillLevel(any(Skill.class))).thenReturn(50); when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(50);
            ItemContainer inventory = mock(ItemContainer.class), equipment = mock(ItemContainer.class);
            when(inventory.getItems()).thenReturn(new Item[0]); when(equipment.getItems()).thenReturn(new Item[0]);
            when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory); when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
            MalfoidConfig config = mock(MalfoidConfig.class); when(config.showMalfoid()).thenReturn(true); when(config.showDialogue()).thenReturn(true);
            set(plugin, "client", client); set(plugin, "clientThread", clientThread); set(plugin, "config", config);
            actor = new NativeActor(client, new Gson());
            ((Random) get(actor, "random")).setSeed(7);
            prepareNativeModels();
            observer = new ScenarioObserver(client, mock(ItemManager.class)); presence = new PresenceController(client);
            Class<?> type = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
            Constructor<?> constructor = type.getDeclaredConstructor(long.class, NativeActor.class, ScenarioObserver.class,
                PresenceController.class, MalfoidMenu.class, ChatNotices.class); constructor.setAccessible(true);
            session = constructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
                new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
            set(plugin, "current", session); set(session, "resourcesLoaded", true);
            doAnswer(call -> { closures.add(call.getArgument(0)); return null; }).when(clientThread).invokeAtTickEnd(any(Runnable.class));
            doAnswer(call -> { callbacks.add(call.getArgument(0)); return null; }).when(clientThread).invokeLater(any(Runnable.class));
            catalogue = prospectiveCatalogue(); guard = new SpeechGuard(() -> 0L, new Random(1), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            Random spoken = new Random() { @Override public int nextInt(int bound) { return bound - 1; } };
            director = new SpeechDirector(catalogue, () -> 0L, spoken, ignored -> {}, guard);
            director.setCandidateValidator(observer::candidateStillValid); director.setFirstPaintListener(observer::executionPainted);
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
            set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
            observer.setScenarioEnabled(id -> "M51".equals(id) || "M60".equals(id));
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = flags[51][50] = flags[52][50] = 0;
            while (!actor.isVisible() && tick() < 4) { commit(); }
            assertTrue(actor.isVisible()); assertEquals(52, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            while (tick() < 24) { commit(); } assertTrue(presence.isPresent()); assertNull(director.getActiveCandidate());
            // Keep the unique spawn geometry through warm-up; E.2 now permits an earlier stroll.
            for (int[] row : flags) { Arrays.fill(row, 0); }
        }

        MotionObservation startFarCatchup() throws Exception
        {
            for (int x = 51; x <= 53; x++) { for (int y = 49; y <= 51; y++) { if (x != 52 || y != 50) flags[x][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; } }
            for (int x : new int[]{48, 46, 44}) { move(x, 50); commit(); cycles(30); assertNull(actor.getMotionObservation()); }
            move(42, 50); goalMask(45, 50); long searches = actor.getPlanningCount(); commit();
            assertEquals(searches + 1, actor.getPlanningCount()); MotionObservation accepted = actor.getMotionObservation(); assertNotNull(accepted);
            assertEquals(MotionObservation.Purpose.CATCHUP, accepted.purpose); assertEquals(MotionObservation.Status.MOVING, accepted.status);
            assertTrue(accepted.known && accepted.ordinary && accepted.legalGoal); assertEquals(10, accepted.startDistance);
            assertEquals(3245, accepted.goalWorldX); assertEquals(3250, accepted.goalWorldY); assertEquals(0, accepted.completedEdges);
            cycles(30); assertEquals(2, actor.getMotionObservation().completedRunEdges); return accepted;
        }

        MotionObservation finishMovingCatchup(MotionObservation accepted) throws Exception
        {
            int[][] closing = {{42, 51}, {42, 53}, {42, 52}, {42, 51}};
            for (int[] point : closing)
            {
                move(point[0], point[1]); goalMask(45, 50); commit();
                MotionObservation completed = actor.getLatestMotionCompletion();
                if (completed != null && completed.requestId == accepted.requestId)
                {
                    assertEquals(MotionObservation.Status.ARRIVED, completed.status); assertTrue(completed.known && completed.ordinary && completed.legalGoal);
                    assertEquals(accepted.startedTick, completed.startedTick); assertEquals(10, completed.startDistance);
                    assertTrue(completed.completedRunEdges >= 2 && completed.completedEdges <= 24); assertEquals(tick(), completed.completedTick);
                    assertEquals(3245, completed.goalWorldX); assertEquals(3250, completed.goalWorldY);
                    assertEquals(new WorldPoint(3242, 3251, 0), playerPoint); assertFalse(actor.isMoving());
                    assertNull(director.getActiveCandidate()); assertNull(director.getPacket()); return completed;
                }
                assertEquals(accepted.requestId, actor.getMotionObservation().requestId); cycles(30);
            }
            fail("The actual accepted catch-up must reach its goal before the moving witness starts"); return null;
        }

        SceneCandidate sixMovingSamples(MotionObservation completion) throws Exception
        {
            int[][] following = {{42, 50}, {42, 49}, {42, 48}, {42, 47}, {43, 47}, {44, 47}};
            for (int sample = 0; sample < following.length; sample++)
            {
                move(following[sample][0], following[sample][1]); goalMask(45, 50); commit();
                assertSame(completion, actor.getMotionObservation()); assertEquals(3, actor.getDistanceToPlayer());
                if (sample < 5) { assertNull(director.getActiveCandidate()); assertNull(director.getPacket()); }
                cycles(30);
            }
            return director.getActiveCandidate();
        }

        void move(int x, int y) { playerPoint = new WorldPoint(3200 + x, 3200 + y, 0); }
        void clearCollision() { for (int[] row : flags) { Arrays.fill(row, 0); } }
        void goalMask(int goalX, int goalY)
        {
            clearCollision();
            int px = playerPoint.getX() - 3200, py = playerPoint.getY() - 3200;
            for (int x = px - 3; x <= px + 3; x++)
            {
                for (int y = py - 3; y <= py + 3; y++)
                {
                    int distance = Math.max(Math.abs(x - px), Math.abs(y - py));
                    if (distance >= 2 && distance <= 3 && !(x == goalX && y == goalY)) flags[x][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
                }
            }
        }
        long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }
        void commit() throws Exception
        {
            long before = tick(); publish(); plugin.onGameTick(new GameTick()); assertEquals(1, closures.size()); closures.remove().run();
            assertEquals(before + 1, tick()); healthy();
            if (!((ScenarioRules) get(observer, "rules")).getCurrent().valid) invalidCommitSeen = true;
        }
        void cycles(int count) throws Exception { for (int i = 0; i < count; i++) { cycle++; plugin.onClientTick(new ClientTick()); healthy(); } }
        void publish() { overlay.publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ++frame, new ArrayList<>(), ModalOcclusionSnapshot.CLEAR); }
        BufferedImage render()
        {
            publish(); BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB); Graphics2D graphics = image.createGraphics();
            try { overlay.render(graphics); } finally { graphics.dispose(); } return image;
        }
        void paint(SpeechPacket packet) throws Exception
        {
            int before = guard.textCount(); assertTrue(AdditionalProducerFixture.hasPixels(render())); assertNotNull(packet.getFirstPaint());
            assertEquals(tick(), packet.getFirstPaint().getPaintedAtTick()); assertEquals(before, guard.textCount());
            assertEquals(1, callbacks.size()); callbacks.remove().run(); assertSame(packet.getFirstPaint(), director.getLastPaint());
            assertEquals(before + 1, guard.textCount()); healthy();
        }
        void assertNoPaint(SpeechPacket packet) throws Exception
        {
            assertFalse(packet.canPaint(0)); assertFalse(AdditionalProducerFixture.hasPixels(render()));
            assertNull(packet.getFirstPaint()); assertTrue(callbacks.isEmpty()); assertNull(director.getLastPaint()); healthy();
        }
        void healthy() throws Exception
        { assertTrue(failures.toString(), failures.isEmpty()); assertFalse(((AtomicBoolean) get(session, "failed")).get()); }
        void prepareNativeModels() throws Exception
        {
            actor.loadResources(); Object asset = get(actor, "decodedAsset"); Class<?> type = Class.forName("com.malfoid.asset.NativePoseBank");
            Constructor<?> constructor = type.getDeclaredConstructor(Client.class, asset.getClass()); constructor.setAccessible(true);
            Object bank = constructor.newInstance(client, asset); Model[] models = (Model[]) get(bank, "models");
            int offset = 1; for (Object clip : ((Map<?, ?>) get(asset, "clips")).values())
            {
                for (float[] coordinates : (float[][]) get(clip, "samples"))
                {
                    Model model = mock(Model.class); float[] y = new float[coordinates.length / 3];
                    for (int vertex = 0; vertex < y.length; vertex++) { y[vertex] = coordinates[vertex * 3 + 1]; }
                    when(model.getVerticesY()).thenReturn(y); when(model.getModelHeight()).thenReturn(200); models[offset++] = model;
                }
            }
            models[0] = models[1]; set(bank, "readyAt", 1L); set(actor, "bank", bank);
        }
        DialogueCatalog prospectiveCatalogue() throws Exception
        {
            Gson gson = new Gson(); JsonObject copy;
            try (InputStream input = MalfoidPlugin.class.getResourceAsStream("/com/malfoid/dialogue.json"))
            { copy = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class); }
            copy.getAsJsonArray("runtimeEnabledScenarios").add("M51"); copy.getAsJsonArray("runtimeEnabledScenarios").add("M60");
            copy.getAsJsonObject("silencePercentByScenario").addProperty("M51", 0); copy.getAsJsonObject("silencePercentByScenario").addProperty("M60", 0);
            for (JsonElement element : copy.getAsJsonArray("entries"))
            {
                JsonObject entry = element.getAsJsonObject(); String id = entry.get("scenario").getAsString();
                if ("M51".equals(id) || "M60".equals(id)) entry.addProperty("status", "enabled");
            }
            return DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
        }
    }

    private static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
