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
import com.malfoid.motion.RouteMotion;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.CancelledInterestObservation;
import com.malfoid.scenario.ScenarioObserver;
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

/** Root callbacks and real native BFS/edge receipts precede the independent M62 paint. */
public class InspectionReturnDeliveryAcceptanceTest
{
    @Test public void aRawBlockedCatchupCanReachItsUnchangedLegalGoalAndPaintM61() throws Exception
    {
        Fixture f = new Fixture(true); MotionObservation accepted = f.startOrdinaryCatchup();
        RoutePlanner.Node failed = f.blockAcceptedEdge();
        MotionObservation blocked = f.actor.getMotionObservation(); assertEquals(MotionObservation.Status.BLOCKED, blocked.status);
        assertTrue(blocked.known && blocked.physicalBlock && blocked.previouslyLegalEdge); assertEquals(0, blocked.completedEdges);
        assertEquals(3200 + failed.x, blocked.blockedToWorldX); assertEquals(3200 + failed.y, blocked.blockedToWorldY);
        int[][] moving = {{46, 51}, {46, 53}, {46, 52}};
        for (int[] point : moving)
        {
            f.playerPoint = new WorldPoint(3200 + point[0], 3200 + point[1], 0);
            f.nearGoalMask(failed); long searches = f.actor.getPlanningCount(); f.commit();
            assertTrue("The root still owns one BFS per commit", f.actor.getPlanningCount() - searches <= 1);
            if (f.isActive("M61")) { break; }
            MotionObservation current = f.actor.getMotionObservation(); assertEquals(accepted.requestId, current.requestId);
            assertTrue("No retargeted endpoint may supply M61", current.known && current.fixedGoal && current.legalGoal);
            assertEquals(accepted.goalWorldX, current.goalWorldX); assertEquals(accepted.goalWorldY, current.goalWorldY);
            for (int cycles = 0; cycles < 30; cycles++) { f.clientCycle(); }
        }
        if (!f.isActive("M61")) { f.commit(); }
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull("The actual unchanged-goal recovery must qualify", c);
        assertEquals("M61", c.getScenarioId()); MotionObservation completion = f.actor.getLatestMotionCompletion(); assertNotNull(completion);
        assertEquals(accepted.requestId, completion.requestId); assertEquals(MotionObservation.Purpose.CATCHUP, completion.purpose);
        assertEquals(MotionObservation.Status.ARRIVED, completion.status); assertTrue(completion.fixedGoal && completion.known && completion.legalGoal);
        assertEquals(accepted.goalWorldX, completion.goalWorldX); assertEquals(accepted.goalWorldY, completion.goalWorldY);
        assertTrue(completion.changedEdgeAfterBlock && completion.completedEdges > 0); assertTrue(completion.completedTick - completion.blockedTick <= 12);
        assertEquals(blocked.blockedTick, completion.blockedTick); assertEquals(blocked.blockedToWorldX, completion.blockedToWorldX);
        assertEquals(blocked.blockedToWorldY, completion.blockedToWorldY); assertEquals("route:" + accepted.requestId, c.getCausalToken());
        assertEquals(19, c.getPriority()); assertEquals(10, c.getExpiresTick() - c.getCreatedTick()); assertEquals(4, c.getHoldTicks());
        assertNull("A moving player keeps ordinary following", f.actor.getMotionHoldReceipt(c.getId()));
        assertSame(completion, f.actor.getMotionObservation()); assertTrue(f.actor.hasMotionSceneArrived(c.getId()));
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); f.paint(packet);
        assertEquals(c.getId(), f.director.getLastPaint().getCandidate().getId()); assertEquals(1, f.guard.textCount());
        f.commit(); assertFalse(f.director.isContinuationCancelled());
        MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(c.getId()); assertNotNull(hold); assertSame(completion, hold.completion);
        assertTrue(hold.legalStanding); assertEquals(accepted.requestId, hold.requestId);
    }

    @Test public void settlingBeforeTheBlockedRoutesGoalCannotTurnItsPartialProgressIntoM61() throws Exception
    {
        Fixture f = new Fixture(true); f.startOrdinaryCatchup(); f.blockAcceptedEdge();
        f.commit(); for (int cycles = 0; cycles < 30; cycles++) { f.clientCycle(); }
        assertTrue(f.actor.getMotionObservation().completedEdges > 0);
        for (int ticks = 0; ticks < 3; ticks++) { f.commit(); }
        assertFalse(f.isActive("M61")); assertNull(f.actor.getLatestMotionCompletion()); assertNull(f.director.getPacket());
        assertEquals(0, f.guard.textCount());
    }

    @Test public void targetLossCancelsTheUnarrivedInspectionAndOnlyItsCompletedReturnCanPaint() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate inspection = f.startInspection(); f.reachOuterUnarrivedNode(true);
        MotionObservation original = f.actor.getMotionObservation(); assertTrue(original.completedEdges > 0);
        assertEquals(MotionObservation.Status.MOVING, original.status); assertFalse(f.actor.hasArrived());
        assertFalse(f.director.hasArrived()); assertNull(f.director.getPacket()); f.despawn();
        CancelledInterestObservation loss = f.interest(); assertNotNull(loss); assertEquals(inspection.getId(), loss.inspectionCandidate.getId());
        assertSame(original, loss.inspectionPath); assertEquals(CancelledInterestObservation.LossReason.DESPAWNED, loss.lossReason);
        assertTrue(loss.cancellation.isPrePaintAttempt()); assertFalse(loss.cancellation.isGenuinePainted());
        assertEquals(-1, loss.returnRequestId); assertEquals(-1, f.actor.getInspectionReturnRequestId(inspection.getId()));
        assertNull(f.director.getPacket()); assertEquals(0, f.guard.textCount());
        long searches = f.actor.getPlanningCount(); f.commit(); assertEquals(searches + 1, f.actor.getPlanningCount());
        MotionObservation returning = f.actor.getMotionObservation();
        assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, returning.purpose); assertEquals(inspection.getId(), returning.sourceCandidateId);
        assertNotEquals(original.requestId, returning.requestId); assertEquals(0, returning.completedEdges);
        assertNull("Accepting a route is not delivery", f.director.getActiveCandidate()); assertNull(f.director.getPacket());
        long returnId = returning.requestId; f.finishReturn();
        MotionObservation completion = f.actor.getLatestMotionCompletion(); SceneCandidate returned = f.director.getActiveCandidate();
        assertNotNull(returned); assertEquals("M62", returned.getScenarioId()); assertNotEquals(inspection.getId(), returned.getId());
        assertEquals(returnId, completion.requestId); assertEquals(returnId, f.observer.getMotionRequestId(returned));
        assertEquals(MotionObservation.Status.ARRIVED, completion.status); assertTrue(completion.completedEdges > 0);
        assertEquals(0, completion.completedRunEdges); assertTrue(completion.completedTick - loss.lossTick <= 16);
        assertEquals(10, returned.getExpiresTick() - returned.getCreatedTick()); assertEquals(4, returned.getHoldTicks());
        assertEquals("Oak tree", returned.getConditionFacts().get("historicalObjectName")); assertNull(returned.getConditionFacts().get("targetKey"));
        assertEquals("interest-return:" + inspection.getId() + ":" + loss.cancellation.getExecutionId(), returned.getCausalToken());
        MotionHoldReceipt held = f.actor.getMotionHoldReceipt(returned.getId()); assertNotNull(held);
        assertSame(completion, held.completion); assertTrue(held.legalStanding); assertTrue(f.actor.hasMotionSceneArrived(returned.getId()));
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals(returned.getId(), packet.getCandidate().getId());
        f.paint(packet); assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        assertTrue("The failed inspection cannot enter successful inspection history", f.canInspect(loss.targetKey));
        f.commit(); assertFalse(f.director.isContinuationCancelled()); assertEquals(returned.getId(), f.director.getActiveCandidate().getId());
        assertEquals(returnId, f.actor.getMotionHoldReceipt(returned.getId()).requestId);
    }

    @Test public void aSceneRebuildRevokesTheCancelledInterestBeforeAnyReturnCanDeliver() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate inspection = f.startInspection(); f.reachOuterUnarrivedNode(true); f.despawn();
        assertNotNull(f.interest()); Scene rebuilt = mock(Scene.class); when(rebuilt.getTiles()).thenReturn(new Tile[4][104][104]);
        when(f.view.getScene()).thenReturn(rebuilt); f.clientCycle(); assertNull(f.interest());
        assertEquals(-1, f.actor.getInspectionReturnRequestId(inspection.getId()));
        for (int i = 0; i < 4; i++) { f.commit(); }
        assertNull(f.director.getPacket()); assertFalse(f.isActive("M62")); assertEquals(0, f.guard.textCount());
    }

    @Test public void anAlreadyPaintedInspectionCannotSupplyTheUnpaintedReturnCause() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate inspection = f.startInspection(); f.finishInspection();
        assertTrue(f.director.hasArrived()); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); f.paint(packet);
        assertTrue(f.director.isCommitted()); f.despawn(); assertNull(f.interest());
        assertEquals(-1, f.actor.getInspectionReturnRequestId(inspection.getId()));
        for (int i = 0; i < 4; i++) { f.commit(); }
        assertFalse(f.isActive("M62")); assertEquals(inspection.getId(), f.director.getLastPaint().getCandidate().getId());
        assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
    }

    @Test public void aPhysicallyArrivedInspectionCannotReturnWhileItsReceiptStillSaysMoving() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate inspection = f.startInspection();
        while (!f.actor.hasArrived()) { f.completeOneEdge(); if (!f.actor.hasArrived()) { f.commit(); } }
        assertEquals(MotionObservation.Status.MOVING, f.actor.getMotionObservation().status);
        assertFalse(f.director.hasArrived()); f.despawn(); assertNull(f.interest());
        assertEquals(-1, f.actor.getInspectionReturnRequestId(inspection.getId()));
        for (int i = 0; i < 3; i++) { f.commit(); }
        assertFalse(f.isActive("M62")); assertNull(f.director.getPacket()); assertEquals(0, f.guard.textCount());
    }

    @Test public void aFutureBucketEdgeCannotBeRetimedIntoTheEarlierCancellationReceipt() throws Exception
    {
        Fixture f = new Fixture(); f.startInspection(); f.reachOuterUnarrivedNode(false); f.despawn();
        CancelledInterestObservation loss = f.interest(); assertNotNull(loss);
        assertEquals(loss.lossTick + 1, loss.inspectionPath.lastCompletedEdgeTick);
        f.commit(); f.finishReturn(); assertNotNull(f.actor.getLatestMotionCompletion());
        assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, f.actor.getLatestMotionCompletion().purpose);
        assertFalse("Conservative temporal proof must stay closed", f.isActive("M62")); assertNull(f.director.getPacket());
        assertEquals(0, f.guard.textCount()); assertEquals(loss.lossTick, loss.cancellation.getCancelledAtTick());
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
        boolean objectPresent; int cycle; long frame; WorldPoint playerPoint = new WorldPoint(3250, 3250, 0);

        Fixture() throws Exception
        {
            this(false);
        }

        Fixture(boolean routeRecovery) throws Exception
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
            catalogue = prospectiveCatalogue(routeRecovery); guard = new SpeechGuard(() -> 0L, new Random(1), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            Random spoken = new Random() { @Override public int nextInt(int bound) { return bound - 1; } };
            director = new SpeechDirector(catalogue, () -> 0L, spoken, ignored -> {}, guard);
            director.setCandidateValidator(observer::candidateStillValid); director.setFirstPaintListener(observer::executionPainted);
            director.setCompletionListener(observer::inspectionCompleted);
            director.setCancellationListener(receipt -> invoke("inspectionCancelled", new Class<?>[]{type, SpeechCancellationReceipt.class}, session, receipt));
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
            set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
            observer.setScenarioEnabled(id -> "M08".equals(id) || "M62".equals(id) || routeRecovery && "M61".equals(id));
            observer.setInspectionSuppression(actor::isInspectionSuppressed);
            observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> (ScenarioSnapshot.InspectionTarget)
                invoke("inspectionTarget", new Class<?>[]{type, String.class, String.class, int.class, int.class, int.class, int.class, int.class},
                    session, key, name, minX, minY, maxX, maxY, plane));
            // A real two-edge spawn has just one legal endpoint; no placement or motion receipt is injected.
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = flags[51][50] = flags[52][50] = 0;
            while (!actor.isVisible() && tick() < 4) { commit(); }
            assertTrue(actor.isVisible()); assertEquals(52, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            tiles[0][56][50] = objectTile;
            when(objectTile.getGameObjects()).thenAnswer(call -> objectPresent ? new GameObject[]{object} : new GameObject[0]);
            when(object.getId()).thenReturn(1000); when(object.getWorldView()).thenReturn(view); when(object.getPlane()).thenReturn(0);
            when(object.getSceneMinLocation()).thenReturn(new net.runelite.api.Point(56, 50));
            when(object.getSceneMaxLocation()).thenReturn(new net.runelite.api.Point(56, 50));
            ObjectComposition definition = mock(ObjectComposition.class); when(definition.getName()).thenReturn("Oak tree");
            when(client.getObjectDefinition(1000)).thenReturn(definition);
            while (tick() < 24) { commit(); } assertTrue(presence.isPresent()); assertNull(director.getActiveCandidate());
            // Establish the inspection route after warm-up, without an E.2 stroll pending.
            for (int[] row : flags) { Arrays.fill(row, 0); }
            for (int y = 49; y <= 51; y++) { flags[55][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; }
            flags[56][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        }

        MotionObservation startOrdinaryCatchup() throws Exception
        {
            for (int[] row : flags) { Arrays.fill(row, 0); }
            playerPoint = new WorldPoint(3248, 3250, 0); commit(); assertFalse(actor.isMoving());
            playerPoint = new WorldPoint(3246, 3250, 0); nearGoalMask(null); commit();
            MotionObservation accepted = actor.getMotionObservation(); assertNotNull(accepted);
            assertEquals(MotionObservation.Purpose.CATCHUP, accepted.purpose); assertEquals(MotionObservation.Status.MOVING, accepted.status);
            assertTrue(accepted.known && accepted.ordinary && accepted.fixedGoal && accepted.legalGoal);
            assertEquals(6, accepted.startDistance); assertEquals(3249, accepted.goalWorldX); assertEquals(3250, accepted.goalWorldY);
            assertEquals(0, accepted.completedEdges); assertTrue(actor.isMoving()); assertNull(director.getPacket()); return accepted;
        }

        void nearGoalMask(RoutePlanner.Node blocked)
        {
            for (int[] row : flags) { Arrays.fill(row, 0); }
            int px = playerPoint.getX() - 3200, py = playerPoint.getY() - 3200;
            for (int x = px - 3; x <= px + 3; x++)
            {
                for (int y = py - 3; y <= py + 3; y++)
                {
                    int distance = Math.max(Math.abs(x - px), Math.abs(y - py));
                    if (distance >= 2 && distance <= 3 && !(x == 49 && y == 50)) { flags[x][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; }
                }
            }
            if (blocked != null) { flags[blocked.x][blocked.y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; }
        }

        RoutePlanner.Node blockAcceptedEdge() throws Exception
        {
            RouteMotion route = (RouteMotion) get(actor, "motion"); assertFalse(route.isSegmentInFlight());
            RoutePlanner.Node next = route.nextUnenteredNode(); assertNotNull(next);
            flags[next.x][next.y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; clientCycle();
            assertEquals(MotionObservation.Status.BLOCKED, actor.getMotionObservation().status); assertFalse(route.isSegmentInFlight()); return next;
        }

        SceneCandidate startInspection() throws Exception
        {
            objectPresent = true; GameObjectSpawned spawn = new GameObjectSpawned(); spawn.setTile(objectTile); spawn.setGameObject(object);
            plugin.onGameObjectSpawned(spawn); healthy();
            for (int tries = 0; tries < 5 && !isActive("M08"); tries++) { commit(); }
            assertTrue(isActive("M08")); SceneCandidate selected = director.getActiveCandidate();
            assertTrue(catalogue.hasValidOriginalContext(selected)); assertFalse(director.hasArrived()); commit();
            MotionObservation path = actor.getMotionObservation(); assertNotNull(path); assertEquals(selected.getId(), path.sourceCandidateId);
            assertEquals(MotionObservation.Purpose.OTHER, path.purpose); assertFalse(path.ordinary);
            assertTrue(path.known && path.legalGoal && path.fixedGoal); assertTrue(path.plannedSteps > 0 && path.plannedSteps <= 8);
            assertEquals(0, path.completedEdges); assertNull(director.getPacket()); return selected;
        }

        void reachOuterUnarrivedNode(boolean commitEdge) throws Exception
        {
            for (int edges = 0; edges < 8; edges++)
            {
                completeOneEdge(); assertFalse("The final inspection edge must remain unentered", actor.hasArrived());
                if (actor.getDistanceToPlayer() > 5) { if (commitEdge) { commit(); } return; }
                commit();
            }
            fail("The genuine inspection path needs an outside-band node before its goal");
        }
        void completeOneEdge() throws Exception
        {
            int before = actor.getMotionObservation().completedEdges;
            for (int cycles = 0; cycles < 30; cycles++) { clientCycle(); }
            assertEquals(before + 1, actor.getMotionObservation().completedEdges);
        }
        void finishInspection() throws Exception
        {
            for (int edges = 0; edges < 8 && !actor.hasArrived(); edges++) { completeOneEdge(); commit(); }
            assertTrue(actor.hasArrived()); assertTrue(director.hasArrived()); assertTrue(isActive("M08"));
        }
        void finishReturn() throws Exception
        {
            for (int edges = 0; edges < 16 && !actor.hasArrived(); edges++) { completeOneEdge(); commit(); }
            assertTrue("The separate accepted return must genuinely arrive", actor.hasArrived()); healthy();
        }
        void despawn() throws Exception
        {
            objectPresent = false; GameObjectDespawned event = new GameObjectDespawned(); event.setTile(objectTile); event.setGameObject(object);
            plugin.onGameObjectDespawned(event); healthy();
        }
        CancelledInterestObservation interest() throws Exception { return (CancelledInterestObservation) get(session, "inspectionReturn"); }
        boolean canInspect(String key) throws Exception
        {
            Object rules = get(observer, "rules"); return (boolean) rules.getClass().getMethod("canInspectIdentity", String.class).invoke(rules, key);
        }
        boolean isActive(String id) { return director.getActiveCandidate() != null && id.equals(director.getActiveCandidate().getScenarioId()); }
        long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }
        void commit() throws Exception
        {
            long before = tick(); publish(); plugin.onGameTick(new GameTick()); assertEquals(1, closures.size()); closures.remove().run();
            assertEquals(before + 1, tick()); healthy();
        }
        void clientCycle() throws Exception { cycle++; plugin.onClientTick(new ClientTick()); healthy(); }
        void publish() { overlay.publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ++frame, new ArrayList<>(), ModalOcclusionSnapshot.CLEAR); }
        void paint(SpeechPacket packet) throws Exception
        {
            int before = guard.textCount(); publish(); BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics(); try { overlay.render(graphics); } finally { graphics.dispose(); }
            assertTrue(AdditionalProducerFixture.hasPixels(image)); assertNotNull(packet.getFirstPaint()); assertEquals(tick(), packet.getFirstPaint().getPaintedAtTick());
            assertEquals(before, guard.textCount()); assertEquals(1, callbacks.size()); callbacks.remove().run();
            assertSame(packet.getFirstPaint(), director.getLastPaint()); assertEquals(before + 1, guard.textCount()); healthy();
        }
        void healthy() throws Exception
        {
            assertTrue(failures.toString(), failures.isEmpty()); assertFalse("Root failure containment must remain unused", ((AtomicBoolean) get(session, "failed")).get());
        }
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
                    for (int vertex = 0; vertex < y.length; vertex++) { y[vertex] = coordinates[vertex * 3 + 1]; }
                    when(model.getVerticesY()).thenReturn(y); when(model.getModelHeight()).thenReturn(200); models[offset++] = model;
                }
            }
            models[0] = models[1]; set(bank, "readyAt", 1L); set(actor, "bank", bank);
        }
        DialogueCatalog prospectiveCatalogue(boolean routeRecovery) throws Exception
        {
            Gson gson = new Gson(); JsonObject copy;
            try (InputStream input = MalfoidPlugin.class.getResourceAsStream("/com/malfoid/dialogue.json"))
            { copy = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class); }
            copy.getAsJsonArray("runtimeEnabledScenarios").add("M62");
            if (routeRecovery) { copy.getAsJsonArray("runtimeEnabledScenarios").add("M61"); }
            copy.getAsJsonObject("silencePercentByScenario").addProperty("M08", 0); copy.getAsJsonObject("silencePercentByScenario").addProperty("M62", 0);
            if (routeRecovery) { copy.getAsJsonObject("silencePercentByScenario").addProperty("M61", 0); }
            for (JsonElement element : copy.getAsJsonArray("entries"))
            {
                JsonObject entry = element.getAsJsonObject(); String id = entry.get("scenario").getAsString();
                if ("M62".equals(id) || routeRecovery && "M61".equals(id)) { entry.addProperty("status", "enabled"); }
            }
            return DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
        }
    }

    private static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
