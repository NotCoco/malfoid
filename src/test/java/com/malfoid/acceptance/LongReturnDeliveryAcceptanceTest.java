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
import com.malfoid.scenario.CancelledInterestObservation;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.scenario.ScenarioSnapshot;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
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
import java.lang.reflect.Method;
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
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A genuinely cancelled inspection starts an independent, fixed twelve-edge WALK return. */
public class LongReturnDeliveryAcceptanceTest
{
    @Test public void aTwelveEdgeKeyedReturnCanWinRealM62ArbitrationAndPaintM63FromItsOwnHold() throws Exception
    {
        Fixture f = new Fixture(); CancelledInterestObservation loss = f.startLongReturn();
        MotionObservation accepted = f.actor.getMotionObservation(); assertEquals(12, accepted.plannedSteps);
        f.finishReturn(); MotionObservation completion = f.actor.getLatestMotionCompletion();
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("M63", c.getScenarioId());
        f.longCompletion(accepted, completion, loss); assertEquals(12, completion.completedTick - completion.startedTick);
        assertEquals("route:" + accepted.requestId, c.getCausalToken()); assertEquals(15, c.getPriority());
        assertEquals("M61", f.catalogue.repeatKey("M63")); assertEquals(10, c.getExpiresTick() - c.getCreatedTick()); assertEquals(4, c.getHoldTicks());
        assertEquals(accepted.requestId, f.observer.getMotionRequestId(c)); assertTrue(f.catalogue.hasValidOriginalContext(c));
        SceneCandidate competing = f.pendingCandidate("M62"); assertNotNull("The competing interest-return offer remains genuine", competing);
        assertEquals(14, competing.getPriority()); assertEquals(accepted.requestId, f.observer.getMotionRequestId(competing));
        assertEquals("interest-return:" + loss.inspectionCandidate.getId() + ":" + loss.cancellation.getExecutionId(), competing.getCausalToken());
        assertEquals(c.getCreatedTick(), competing.getCreatedTick()); assertNull(f.pendingCandidate("M61"));
        MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(c.getId()); assertNotNull(hold); assertSame(completion, hold.completion);
        assertTrue(hold.legalStanding); assertTrue(f.actor.hasMotionSceneArrived(c.getId())); assertNull(f.actor.getMotionHoldReceipt(competing.getId()));
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals(c.getId(), packet.getCandidate().getId());
        long deadline = c.getExpiresTick(); f.paint(packet); assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        f.commit(); assertEquals(0, f.director.pendingCount()); assertFalse(f.observer.candidateContinuationValid(competing));
        assertFalse(f.director.isContinuationCancelled()); assertEquals(c.getId(), f.director.getLastPaint().getCandidate().getId());
        while (f.tick() < f.director.getHoldUntilTick() - 1)
        {
            f.commit(); assertFalse(f.director.isContinuationCancelled()); assertEquals(c.getId(), f.director.getActiveCandidate().getId());
            assertEquals(accepted.requestId, f.actor.getMotionHoldReceipt(c.getId()).requestId);
        }
        assertEquals(deadline, c.getExpiresTick()); assertEquals(1, f.guard.textCount());
        assertTrue("The unarrived abandoned parent is not a completed inspection", f.canInspect(loss.targetKey));
    }

    @Test public void unknownCombatBeforeM63PaintRevokesItsOwnPhysicalHoldAndIssuedCapability() throws Exception
    {
        Fixture f = new Fixture(); f.startLongReturn(); f.finishReturn(); SceneCandidate c = f.director.getActiveCandidate();
        assertNotNull(c); assertEquals("M63", c.getScenarioId()); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        assertNotNull(f.actor.getMotionHoldReceipt(c.getId())); NPC unresolved = mock(NPC.class);
        when(unresolved.getWorldView()).thenReturn(f.view); when(unresolved.getTransformedComposition()).thenReturn(null);
        when(f.player.getInteracting()).thenReturn(unresolved); f.commit();
        assertEquals(ScenarioRules.CombatState.UNKNOWN, f.observer.getCombatState()); assertFalse(f.observer.candidateContinuationValid(c));
        assertNull(f.actor.getMotionHoldReceipt(c.getId())); f.assertNoPaint(packet);
        when(f.player.getInteracting()).thenReturn(null); f.commit(); assertFalse(f.observer.candidateContinuationValid(c));
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void aThirteenTickGenuineReturnCannotSupplyM63ButCanStillDeliverItsEligibleM62Cause() throws Exception
    {
        Fixture f = new Fixture(); CancelledInterestObservation loss = f.startLongReturn(); MotionObservation accepted = f.actor.getMotionObservation();
        // One complete root observation while the render clock is stalled adds no fabricated edge.
        f.commit(); assertEquals(0, f.actor.getMotionObservation().completedEdges); assertEquals(accepted.requestId, f.actor.getMotionObservation().requestId);
        f.finishReturn(); MotionObservation completion = f.actor.getLatestMotionCompletion(); f.longCompletion(accepted, completion, loss);
        assertEquals(13, completion.completedTick - completion.startedTick); SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c);
        assertEquals("M62", c.getScenarioId()); assertNull(f.pendingCandidate("M63"));
        assertEquals("interest-return:" + loss.inspectionCandidate.getId() + ":" + loss.cancellation.getExecutionId(), c.getCausalToken());
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals("M62", packet.getCandidate().getScenarioId());
        f.paint(packet); assertEquals("M62", f.director.getLastPaint().getCandidate().getScenarioId()); assertEquals(1, f.guard.textCount());
    }

    @Test public void elevenCompletedWalkEdgesAndAMovingReceiptCannotInventAnyLongReturnPaint() throws Exception
    {
        Fixture f = new Fixture(); f.startLongReturn(); MotionObservation accepted = f.actor.getMotionObservation();
        for (int edge = 0; edge < 11; edge++) { f.completeOneEdge(); f.commit(); }
        MotionObservation current = f.actor.getMotionObservation(); assertEquals(accepted.requestId, current.requestId);
        assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, current.purpose); assertEquals(MotionObservation.Status.MOVING, current.status);
        assertTrue(current.known && current.ordinary && current.fixedGoal && current.legalGoal); assertEquals(11, current.completedEdges);
        assertEquals(0, current.completedRunEdges); assertEquals(6, f.actor.getDistanceToPlayer()); assertFalse(f.actor.hasArrived());
        assertNull(f.actor.getLatestMotionCompletion()); assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket());
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    private static final class Fixture
    {
        final Client client = mock(Client.class); final ClientThread clientThread = mock(ClientThread.class);
        final WorldView view = mock(WorldView.class); final Scene scene = mock(Scene.class); final Player player = mock(Player.class);
        final Tile objectTile = mock(Tile.class); final GameObject object = mock(GameObject.class);
        final Tile[][][] tiles = new Tile[4][104][104]; final int[][] flags = new int[104][104];
        final Queue<Runnable> closures = new ArrayDeque<>(), callbacks = new ArrayDeque<>(); final List<Throwable> failures = new ArrayList<>();
        final MalfoidPlugin plugin = new MalfoidPlugin(); final NativeActor actor; final ScenarioObserver observer;
        final PresenceController presence; final DialogueCatalog catalogue; final SpeechDirector director;
        final SpeechGuard guard; final MalfoidSpeechOverlay overlay; final Object session;
        boolean objectPresent; int cycle; long frame;

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
            when(player.getWorldView()).thenReturn(view); when(player.getWorldLocation()).thenReturn(new WorldPoint(3250, 3250, 0));
            when(player.getLocalLocation()).thenReturn(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
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
            prepareNativeModels(); observer = new ScenarioObserver(client, mock(ItemManager.class));
            presence = new PresenceController(client); Class<?> type = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
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
            director.setCompletionListener(observer::inspectionCompleted);
            director.setCancellationListener(receipt -> invoke("inspectionCancelled", new Class<?>[]{type, SpeechCancellationReceipt.class}, session, receipt));
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
            set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
            observer.setScenarioEnabled(id -> "M08".equals(id) || "M61".equals(id) || "M62".equals(id) || "M63".equals(id));
            observer.setInspectionSuppression(actor::isInspectionSuppressed);
            observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> (ScenarioSnapshot.InspectionTarget)
                invoke("inspectionTarget", new Class<?>[]{type, String.class, String.class, int.class, int.class, int.class, int.class, int.class},
                    session, key, name, minX, minY, maxX, maxY, plane));
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = flags[51][50] = flags[52][50] = 0;
            while (!actor.isVisible() && tick() < 4) { commit(); }
            assertTrue(actor.isVisible()); assertEquals(52, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            tiles[0][56][52] = objectTile;
            when(objectTile.getGameObjects()).thenAnswer(call -> objectPresent ? new GameObject[]{object} : new GameObject[0]);
            when(object.getId()).thenReturn(1000); when(object.getWorldView()).thenReturn(view); when(object.getPlane()).thenReturn(0);
            when(object.getSceneMinLocation()).thenReturn(new net.runelite.api.Point(56, 52));
            when(object.getSceneMaxLocation()).thenReturn(new net.runelite.api.Point(56, 52));
            ObjectComposition definition = mock(ObjectComposition.class); when(definition.getName()).thenReturn("Oak tree");
            when(client.getObjectDefinition(1000)).thenReturn(definition);
            while (tick() < 24) { commit(); } assertTrue(presence.isPresent()); assertNull(director.getActiveCandidate());
            // E.2 strolls must not pre-own the corridor before this inspection fixture begins.
            inspectionCorridor();
        }

        void inspectionCorridor()
        {
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = 0; for (int x = 52; x <= 57; x++) flags[x][50] = 0; flags[57][51] = 0;
        }
        void returnMaze()
        {
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = 0;
            int[][] corridor = {{57, 50}, {58, 50}, {59, 50}, {60, 50}, {60, 49}, {60, 48}, {59, 48},
                {58, 48}, {57, 48}, {56, 48}, {56, 49}, {56, 50}, {55, 50}};
            for (int[] point : corridor) flags[point[0]][point[1]] = 0;
            flags[57][50] |= CollisionDataFlag.BLOCK_MOVEMENT_WEST; flags[56][50] |= CollisionDataFlag.BLOCK_MOVEMENT_EAST;
        }
        CancelledInterestObservation startLongReturn() throws Exception
        {
            objectPresent = true; GameObjectSpawned spawn = new GameObjectSpawned(); spawn.setTile(objectTile); spawn.setGameObject(object);
            plugin.onGameObjectSpawned(spawn); healthy();
            for (int tries = 0; tries < 5 && !isActive("M08"); tries++) commit();
            assertTrue(isActive("M08")); SceneCandidate inspection = director.getActiveCandidate(); assertTrue(catalogue.hasValidOriginalContext(inspection));
            commit(); MotionObservation original = actor.getMotionObservation(); assertNotNull(original);
            assertEquals(MotionObservation.Purpose.OTHER, original.purpose); assertFalse(original.ordinary); assertEquals(6, original.plannedSteps);
            assertEquals(3257, original.goalWorldX); assertEquals(3251, original.goalWorldY); assertEquals(inspection.getId(), original.sourceCandidateId);
            for (int edge = 0; edge < 5; edge++) { completeOneEdge(); commit(); }
            assertEquals(57, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            assertFalse(actor.hasArrived()); assertFalse(director.hasArrived()); assertNull(director.getPacket());
            MotionObservation unarrived = actor.getMotionObservation(); assertEquals(5, unarrived.completedEdges);
            assertEquals(MotionObservation.Status.MOVING, unarrived.status); assertEquals(tick(), unarrived.lastCompletedEdgeTick);
            objectPresent = false; GameObjectDespawned despawn = new GameObjectDespawned(); despawn.setTile(objectTile); despawn.setGameObject(object);
            plugin.onGameObjectDespawned(despawn); healthy();
            CancelledInterestObservation loss = (CancelledInterestObservation) get(session, "inspectionReturn"); assertNotNull(loss);
            assertSame(unarrived, loss.inspectionPath); assertEquals(inspection.getId(), loss.inspectionCandidate.getId());
            assertTrue(loss.cancellation.isPrePaintAttempt()); assertFalse(loss.cancellation.isGenuinePainted());
            assertEquals(CancelledInterestObservation.LossReason.DESPAWNED, loss.lossReason); assertEquals(tick(), loss.lossTick);
            returnMaze(); long searches = actor.getPlanningCount(); commit(); assertEquals(searches + 1, actor.getPlanningCount());
            MotionObservation returning = actor.getMotionObservation(); assertNotNull(returning);
            assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, returning.purpose); assertEquals(inspection.getId(), returning.sourceCandidateId);
            assertNotEquals(original.requestId, returning.requestId); assertEquals(0, returning.completedEdges); assertEquals(12, returning.plannedSteps);
            assertTrue(returning.known && returning.ordinary && returning.fixedGoal && returning.legalGoal);
            assertEquals(3257, returning.startWorldX); assertEquals(3250, returning.startWorldY); assertEquals(3255, returning.goalWorldX); assertEquals(3250, returning.goalWorldY);
            assertNull(director.getActiveCandidate()); assertNull(director.getPacket()); return loss;
        }
        void completeOneEdge() throws Exception
        {
            int before = actor.getMotionObservation().completedEdges;
            for (int count = 0; count < 30; count++) { cycle++; plugin.onClientTick(new ClientTick()); healthy(); }
            assertEquals(before + 1, actor.getMotionObservation().completedEdges);
        }
        void finishReturn() throws Exception
        {
            long request = actor.getMotionObservation().requestId;
            for (int edge = 0; edge < 12 && !actor.hasArrived(); edge++)
            {
                completeOneEdge(); commit(); MotionObservation current = actor.getMotionObservation(); assertEquals(request, current.requestId);
                assertTrue(current.known && current.fixedGoal && current.legalGoal);
            }
            assertTrue("Only the actual unchanged accepted goal supplies arrival", actor.hasArrived()); healthy();
        }
        void longCompletion(MotionObservation accepted, MotionObservation completion, CancelledInterestObservation loss)
        {
            assertNotNull(completion); assertEquals(accepted.requestId, completion.requestId); assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, completion.purpose);
            assertEquals(MotionObservation.Status.ARRIVED, completion.status); assertEquals(loss.inspectionCandidate.getId(), completion.sourceCandidateId);
            assertTrue(completion.known && completion.ordinary && completion.fixedGoal && completion.legalGoal); assertFalse(completion.physicalBlock);
            assertEquals(12, completion.completedEdges); assertEquals(0, completion.completedRunEdges); assertEquals(accepted.startedTick, completion.startedTick);
            assertEquals(2, Math.max(Math.abs(completion.goalWorldX - completion.startWorldX), Math.abs(completion.goalWorldY - completion.startWorldY)));
            assertEquals(3255, completion.goalWorldX); assertEquals(3250, completion.goalWorldY); assertEquals(5, completion.arrivalDistance);
            assertTrue(completion.completedTick - loss.lossTick <= 16);
        }
        @SuppressWarnings("unchecked") SceneCandidate pendingCandidate(String scenario) throws Exception
        {
            for (SceneCandidate c : (List<SceneCandidate>) get(director, "pending")) if (scenario.equals(c.getScenarioId())) return c;
            return null;
        }
        boolean isActive(String id) { return director.getActiveCandidate() != null && id.equals(director.getActiveCandidate().getScenarioId()); }
        boolean canInspect(String key) throws Exception
        { return ((ScenarioRules) get(observer, "rules")).canInspectIdentity(key); }
        long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }
        void commit() throws Exception
        {
            long before = tick(), searches = actor.getPlanningCount(); publish(); plugin.onGameTick(new GameTick());
            assertEquals(1, closures.size()); closures.remove().run(); assertEquals(before + 1, tick());
            assertTrue("The real root commit owns at most one actor search", actor.getPlanningCount() - searches <= 1); healthy();
        }
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
            assertFalse(packet.canPaint(0)); assertFalse(AdditionalProducerFixture.hasPixels(render())); assertNull(packet.getFirstPaint());
            assertTrue(callbacks.isEmpty()); assertNull(director.getLastPaint()); healthy();
        }
        void healthy() throws Exception
        { assertTrue(failures.toString(), failures.isEmpty()); assertFalse(((AtomicBoolean) get(session, "failed")).get()); }
        Object invoke(String name, Class<?>[] signature, Object... arguments)
        {
            try { Method method = MalfoidPlugin.class.getDeclaredMethod(name, signature); method.setAccessible(true); return method.invoke(plugin, arguments); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(name, failure); }
        }
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
                    for (int vertex = 0; vertex < y.length; vertex++) y[vertex] = coordinates[vertex * 3 + 1];
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
            for (String scenario : new String[]{"M61", "M62", "M63"})
            {
                boolean enabled = false; for (JsonElement id : copy.getAsJsonArray("runtimeEnabledScenarios")) if (scenario.equals(id.getAsString())) enabled = true;
                if (!enabled) copy.getAsJsonArray("runtimeEnabledScenarios").add(scenario);
                copy.getAsJsonObject("silencePercentByScenario").addProperty(scenario, 0);
            }
            copy.getAsJsonObject("silencePercentByScenario").addProperty("M08", 0);
            for (JsonElement element : copy.getAsJsonArray("entries"))
            {
                JsonObject entry = element.getAsJsonObject(); String id = entry.get("scenario").getAsString();
                if ("M61".equals(id) || "M62".equals(id) || "M63".equals(id)) entry.addProperty("status", "enabled");
            }
            return DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
        }
    }

    private static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
