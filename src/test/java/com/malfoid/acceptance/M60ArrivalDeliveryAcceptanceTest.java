package com.malfoid.acceptance;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.dialogue.DialogueLine;
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

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real stopped catch-ups reach an accepted legal resting goal; later idle steps own different receipts. */
public class M60ArrivalDeliveryAcceptanceTest
{
    @Test public void aFarCatchupCanReachItsAcceptedRestingGoalAfterFiveStillCommitsAndPaintM60() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(12); MotionObservation completion = f.stoppedCompletion(accepted);
        assertEquals(5, completion.completedTick - accepted.startedTick); assertEquals(7, completion.completedEdges); assertEquals(4, completion.completedRunEdges);
        assertFalse(completion.fixedGoal); assertFalse(completion.physicalBlock); assertNotEquals(accepted.goalWorldX, completion.goalWorldX);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("M60", c.getScenarioId());
        assertEquals("5", c.getConditionFacts().get("stationarySamples")); assertEquals("catchup:" + accepted.requestId, c.getCausalToken());
        assertEquals(accepted.requestId, f.observer.getMotionRequestId(c)); assertEquals("M51", f.catalogue.repeatKey("M60"));
        assertEquals(18, c.getPriority()); assertEquals(10, c.getExpiresTick() - c.getCreatedTick()); assertEquals(6, c.getHoldTicks());
        assertEquals("4", c.getConditionFacts().get("maximumApproachTicks")); assertTrue(f.catalogue.hasValidOriginalContext(c));
        MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(c.getId()); assertNotNull(hold); assertSame(completion, hold.completion); assertTrue(hold.legalStanding);
        assertTrue(f.actor.hasMotionSceneArrived(c.getId())); assertFalse(f.director.isCommitted()); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        long deadline = c.getExpiresTick(); f.paint(packet); assertTrue(f.director.isCommitted()); assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        while (f.tick() < f.director.getHoldUntilTick() - 1)
        {
            f.commit(); assertFalse(f.director.isContinuationCancelled()); assertEquals(c.getId(), f.director.getActiveCandidate().getId());
            assertEquals(accepted.requestId, f.actor.getMotionHoldReceipt(c.getId()).requestId);
        }
        assertEquals(deadline, c.getExpiresTick()); assertEquals(0, f.actor.getRecoveryCount()); assertTrue(f.actor.getLastVoluntaryMovementTick() < 0);
    }

    @Test public void aGenuineArrivalAfterOnlyThreeStillCommitsCannotBeDeferredIntoM60() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(10); MotionObservation completion = f.stoppedCompletion(accepted);
        assertEquals(3, completion.completedTick - accepted.startedTick); assertTrue(completion.completedEdges > 0); assertEquals(4, completion.completedRunEdges);
        assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket());
        for (int later = 0; later < 6; later++) { f.commit(); f.cycles(30); assertNull(f.director.getActiveCandidate()); }
        assertSame(completion, f.actor.getLatestMotionCompletion()); assertEquals(0, f.guard.textCount());
    }

    @Test public void leavingTheStillTileBeforeM60PaintRevokesTheIssuedPacket() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(12); f.stoppedCompletion(accepted);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        long deadline = c.getExpiresTick(); f.clearCollision(); f.move(41, 50); f.commit();
        assertFalse(f.observer.candidateContinuationValid(c)); f.assertNoPaint(packet); assertEquals(deadline, c.getExpiresTick());
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void unknownCombatBeforeM60PaintCannotUseTheEarlierGenuineCompletion() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(12); f.stoppedCompletion(accepted);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        NPC unresolved = mock(NPC.class); when(unresolved.getWorldView()).thenReturn(f.view); when(unresolved.getTransformedComposition()).thenReturn(null);
        when(f.player.getInteracting()).thenReturn(unresolved); f.commit(); assertEquals(ScenarioRules.CombatState.UNKNOWN, f.observer.getCombatState());
        assertFalse(f.observer.candidateContinuationValid(c)); f.assertNoPaint(packet);
        when(f.player.getInteracting()).thenReturn(null); f.commit(); assertFalse(f.observer.candidateContinuationValid(c));
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void anUnpaintedM60ExpiresAtItsOriginalTenTickDeadlineDespiteItsOwnLegalHold() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(12); f.stoppedCompletion(accepted);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        long deadline = c.getExpiresTick(); while (f.tick() < deadline) f.commit();
        assertEquals(deadline, c.getExpiresTick()); f.assertNoPaint(packet); assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void aPlannedIdleStrollCannotInheritAnUnreachedCatchupOrProduceM60() throws Exception
    {
        Fixture f = new Fixture(); MotionObservation accepted = f.startCatchup(10);
        f.flags[43][50] = f.flags[44][50] = 0;
        f.move(43, 50); f.commit(); f.cycles(30); f.move(44, 50); f.commit();
        assertEquals(accepted.requestId, f.actor.getMotionObservation().requestId); assertTrue(f.actor.isMoving());
        // The reversal happens before the new WALK edge enters, so P19 stops this unfinished goal.
        f.move(43, 50); f.commit(); assertFalse(f.actor.isMoving()); MotionObservation abandoned = f.actor.getMotionObservation();
        assertEquals(accepted.requestId, abandoned.requestId); assertEquals(MotionObservation.Status.MOVING, abandoned.status);
        assertEquals(4, abandoned.completedEdges); assertEquals(4, abandoned.completedRunEdges);
        assertEquals(48, f.actor.getLocation().getSceneX()); assertEquals(3247, abandoned.goalWorldX); assertNull(f.actor.getLatestMotionCompletion());
        long stoppedAt = f.tick(), firstRestAt = stoppedAt + 2, inspectionStamp = f.actor.getLastVoluntaryMovementTick();
        assertTrue(inspectionStamp < 0); f.clearCollision(); MotionObservation stroll = null, roam = null;
        for (int quiet = 0; quiet < 32; quiet++)
        {
            f.commit(); assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket());
            MotionObservation current = f.actor.getMotionObservation();
            if (stroll == null && current != null && current.purpose == MotionObservation.Purpose.ROAM)
            {
                stroll = current; assertEquals(MotionObservation.Status.MOVING, stroll.status);
                assertNotEquals(abandoned.requestId, stroll.requestId); assertEquals(5, stroll.startDistance);
                assertEquals(0, stroll.completedEdges); assertEquals(0, stroll.completedRunEdges);
                assertTrue(stroll.plannedSteps >= 1 && stroll.plannedSteps <= 4);
                assertTrue(stroll.startedTick - firstRestAt >= 8 && stroll.startedTick - firstRestAt <= 20);
            }
            MotionObservation completion = f.actor.getLatestMotionCompletion();
            if (completion != null) { roam = completion; break; }
            f.cycles(30);
        }
        assertNotNull("The actual P25 WALK route must own its own request", stroll); assertNotNull(roam);
        assertEquals(MotionObservation.Purpose.ROAM, roam.purpose); assertNotEquals(abandoned.requestId, roam.requestId);
        assertEquals(stroll.requestId, roam.requestId); assertEquals(MotionObservation.Status.ARRIVED, roam.status);
        assertTrue(roam.known && roam.ordinary && roam.fixedGoal && roam.legalGoal);
        assertEquals(stroll.plannedSteps, roam.completedEdges); assertEquals(0, roam.completedRunEdges);
        assertTrue(roam.arrivalDistance >= 2 && roam.arrivalDistance <= 4);
        assertEquals(f.actor.getLocation().getSceneX() + 3200, roam.goalWorldX);
        assertEquals(f.actor.getLocation().getSceneY() + 3200, roam.goalWorldY);
        assertEquals(inspectionStamp, f.actor.getLastVoluntaryMovementTick()); assertEquals(0, f.actor.getRecoveryCount());
        long nextNotBefore = roam.completedTick + 12;
        while (f.tick() < nextNotBefore - 1)
        {
            f.commit(); f.cycles(30); assertSame(roam, f.actor.getLatestMotionCompletion()); assertNull(f.director.getActiveCandidate());
        }
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
    }

    @Test public void theAuthoredFiftyFivePercentSilentBranchCommitsAtGenuineArrivalForDrawFiftyFour() throws Exception
    {
        Fixture f = new Fixture(54, 4); assertEquals(55, f.catalogue.getSilencePercent("M60"));
        MotionObservation accepted = f.startCatchup(12); MotionObservation completion = f.stoppedCompletion(accepted);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("M60", c.getScenarioId());
        assertEquals("catchup:" + accepted.requestId, c.getCausalToken());
        MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(c.getId()); assertNotNull(hold); assertSame(completion, hold.completion);
        assertTrue(f.actor.hasMotionSceneArrived(c.getId())); assertTrue(f.director.hasArrived());
        assertTrue(f.director.isIntentionalSilent()); assertTrue(f.director.isCommitted()); assertNull(f.director.getPacket());
        assertEquals(0, f.guard.textCount()); assertEquals(1, f.guard.interactionCount()); assertFalse(f.guard.interactionEligible("M51"));
        assertEquals(completion.completedTick + 6, f.director.getHoldUntilTick());
        assertFalse(AdditionalProducerFixture.hasPixels(f.render())); assertNull(f.director.getLastPaint()); assertTrue(f.callbacks.isEmpty());
        while (f.tick() < f.director.getHoldUntilTick() - 1)
        {
            f.commit(); assertFalse(f.director.isContinuationCancelled()); assertEquals(c.getId(), f.director.getActiveCandidate().getId());
            assertSame(completion, f.actor.getMotionHoldReceipt(c.getId()).completion); assertNull(f.director.getPacket());
        }
        assertEquals(0, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
    }

    @Test public void drawFiftyFiveUsesTheAuthoredSpokenBranchAndCommitsOnlyAtFirstPaint() throws Exception
    {
        Fixture f = new Fixture(55, 0); assertEquals(55, f.catalogue.getSilencePercent("M60"));
        MotionObservation accepted = f.startCatchup(12); MotionObservation completion = f.stoppedCompletion(accepted);
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("M60", c.getScenarioId());
        assertSame(completion, f.actor.getMotionHoldReceipt(c.getId()).completion);
        assertFalse(f.director.isIntentionalSilent()); assertFalse(f.director.isCommitted());
        assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals("M60-G01", packet.getLineId());
        f.paint(packet); assertTrue(f.director.isCommitted()); assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        assertFalse(f.guard.interactionEligible("M51"));
    }

    @Test public void everyUnchangedAuthoredM60LineCanActuallyFirstPaintFromItsGenuineCatchup() throws Exception
    {
        for (int lineIndex = 0; lineIndex < 5; lineIndex++)
        {
            Fixture f = new Fixture(99, lineIndex); assertEquals(55, f.catalogue.getSilencePercent("M60"));
            List<DialogueLine> authored = f.catalogue.getLines("M60"); assertEquals(5, authored.size());
            DialogueLine expected = authored.get(lineIndex); assertEquals("M60-G0" + (lineIndex + 1), expected.getId());
            MotionObservation accepted = f.startCatchup(12); MotionObservation completion = f.stoppedCompletion(accepted);
            SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("M60", c.getScenarioId());
            assertSame(completion, f.actor.getMotionHoldReceipt(c.getId()).completion); assertEquals(accepted.requestId, f.observer.getMotionRequestId(c));
            SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals(expected.getId(), packet.getLineId());
            assertEquals(expected.getText(), packet.getText()); assertNull(packet.getFirstPaint()); assertFalse(f.director.isCommitted());
            assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
            f.paint(packet); assertEquals(expected.getId(), packet.getFirstPaint().getLineId()); assertSame(c, packet.getFirstPaint().getCandidate());
            assertTrue(f.director.isCommitted()); assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
            f.commit(); assertFalse(f.director.isContinuationCancelled()); assertSame(completion, f.actor.getMotionHoldReceipt(c.getId()).completion);
        }
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
        int cycle; long frame; WorldPoint playerPoint = new WorldPoint(3250, 3250, 0);

        Fixture() throws Exception
        {
            this(99, 4);
        }

        Fixture(int silenceDraw, int lineIndex) throws Exception
        {
            Widget warmupModal = mock(Widget.class); when(warmupModal.getBounds()).thenReturn(new Rectangle(0, 0, 200, 100));
            when(client.getWidget(InterfaceID.Messagebox.UNIVERSE)).thenReturn(warmupModal);
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
            actor = new NativeActor(client, new Gson()); prepareNativeModels();
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
            Random spoken = new Random() { @Override public int nextInt(int bound) { return bound == 100 ? silenceDraw : lineIndex % bound; } };
            director = new SpeechDirector(catalogue, () -> 0L, spoken, ignored -> {}, guard);
            director.setCandidateValidator(observer::candidateStillValid); director.setFirstPaintListener(observer::executionPainted);
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
            set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
            observer.setScenarioEnabled(id -> "M51".equals(id) || "M60".equals(id));
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = flags[51][50] = flags[52][50] = 0;
            while (!actor.isVisible() && tick() < 4) { commit(); }
            assertTrue(actor.isVisible()); assertEquals(52, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            for (int[] row : flags) { Arrays.fill(row, 0); }
            while (tick() < 24) { commit(); } assertTrue(presence.isPresent()); assertNull(director.getActiveCandidate());
            when(client.getWidget(InterfaceID.Messagebox.UNIVERSE)).thenReturn(null);
        }

        MotionObservation startCatchup(int distance) throws Exception
        {
            assertTrue(distance == 10 || distance == 12);
            for (int x = 51; x <= 53; x++) { for (int y = 49; y <= 51; y++) { if (x != 52 || y != 50) flags[x][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; } }
            for (int x : new int[]{48, 46, 44}) { move(x, 50); commit(); cycles(30); assertNull(actor.getMotionObservation()); }
            if (distance == 12)
            {
                move(42, 50); commit(); cycles(30); assertNull(actor.getMotionObservation());
                for (int wait = 0; wait < 4; wait++) { commit(); cycles(30); assertNull(actor.getMotionObservation()); }
            }
            int px = 52 - distance; move(px, 50); corridor(px); long searches = actor.getPlanningCount(); commit();
            assertEquals(searches + 1, actor.getPlanningCount()); MotionObservation accepted = actor.getMotionObservation(); assertNotNull(accepted);
            assertEquals(MotionObservation.Purpose.CATCHUP, accepted.purpose); assertEquals(MotionObservation.Status.MOVING, accepted.status);
            assertTrue(accepted.known && accepted.ordinary && accepted.legalGoal); assertEquals(distance, accepted.startDistance);
            assertEquals(3200 + px + 3, accepted.goalWorldX); assertEquals(3250, accepted.goalWorldY); assertEquals(0, accepted.completedEdges);
            cycles(30); assertEquals(2, actor.getMotionObservation().completedRunEdges); return accepted;
        }
        MotionObservation stoppedCompletion(MotionObservation accepted) throws Exception
        {
            for (int still = 1; still <= 12; still++)
            {
                commit(); MotionObservation completion = actor.getLatestMotionCompletion();
                if (completion != null && completion.requestId == accepted.requestId)
                {
                    assertEquals(MotionObservation.Status.ARRIVED, completion.status); assertTrue(completion.known && completion.ordinary && completion.legalGoal);
                    assertEquals(MotionObservation.Purpose.CATCHUP, completion.purpose); assertEquals(accepted.startedTick, completion.startedTick);
                    assertEquals(tick(), completion.completedTick); assertEquals(5, completion.arrivalDistance); assertEquals(5, actor.getDistanceToPlayer());
                    assertEquals(3200 + actor.getLocation().getSceneX(), completion.goalWorldX); assertEquals(3200 + actor.getLocation().getSceneY(), completion.goalWorldY);
                    assertTrue(completion.lastCompletedEdgeTick > accepted.startedTick + 1); assertFalse(actor.isMoving()); return completion;
                }
                assertNull(director.getActiveCandidate()); assertNull(director.getPacket()); cycles(30);
            }
            fail("The real accepted resting goal must be reached at normal cadence"); return null;
        }
        void move(int x, int y) { playerPoint = new WorldPoint(3200 + x, 3200 + y, 0); }
        void clearCollision() { for (int[] row : flags) { Arrays.fill(row, 0); } }
        void corridor(int px)
        {
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[px][50] = 0; for (int x = px + 3; x <= 52; x++) flags[x][50] = 0;
        }
        long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }
        void commit() throws Exception
        {
            long before = tick(); publish(); plugin.onGameTick(new GameTick()); assertEquals(1, closures.size()); closures.remove().run();
            assertEquals(before + 1, tick()); healthy();

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
            for (String scenario : new String[]{"M51", "M60"})
            {
                boolean enabled = false; for (JsonElement id : copy.getAsJsonArray("runtimeEnabledScenarios")) if (scenario.equals(id.getAsString())) enabled = true;
                if (!enabled) copy.getAsJsonArray("runtimeEnabledScenarios").add(scenario);
            }
            copy.getAsJsonObject("silencePercentByScenario").addProperty("M51", 0);
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
