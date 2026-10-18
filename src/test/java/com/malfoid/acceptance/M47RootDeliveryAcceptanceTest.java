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
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenePlan;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechCancellationReceipt.Reason;
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
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
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

/** Root equipment callbacks and actual native standing precede the silent M47 guard settlement. */
public class M47RootDeliveryAcceptanceTest
{
    @Test public void threeActualRevisionCancellationsProduceOneStationaryNativeQuietArrival() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate first = f.firstAppraisal();
        SceneCandidate quiet = f.threeFailures(first);
        assertEquals(3, f.receipts.size());
        long previousExecution = -1;
        for (SpeechCancellationReceipt receipt : f.receipts)
        {
            assertEquals(Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, receipt.getReason());
            assertTrue(receipt.isPrePaintAttempt()); assertFalse(receipt.isGenuinePainted());
            assertTrue(receipt.getExecutionId() > previousExecution); previousExecution = receipt.getExecutionId();
            assertEquals(quiet.getSessionGeneration(), receipt.getSessionGeneration());
            assertEquals(quiet.getSceneRevision(), receipt.getSceneRevision());
            assertEquals("M09", receipt.getScenarioId()); assertTrue(receipt.getIssuedPacketToken() >= 0);
        }
        assertTrue(f.tick() - f.receipts.get(0).getCancelledAtTick() < 80);
        assertEquals("3", quiet.getConditionFacts().get("quietCancellationCount"));
        assertEquals("outfit:" + quiet.getConditionFacts().get("outfitRevision"), quiet.getCausalToken());
        assertEquals(f.tick(), quiet.getCreatedTick()); assertEquals(quiet.getCreatedTick() + 12, quiet.getExpiresTick());
        assertEquals(f.tuple(), quiet.getConditionFacts().get("equipmentIds"));
        assertEquals(Long.toString(f.presence.getPresentSinceTick()), quiet.getConditionFacts().get("witnessPresenceEpoch"));
        f.assertQuietArrival(quiet);
        long arrival = f.tick(), deadline = quiet.getExpiresTick();
        while (f.tick() < arrival + 8 - 1)
        {
            f.commit(); assertSame(quiet, f.director.getActiveCandidate()); f.assertNoBubble();
            assertFalse(f.director.isContinuationCancelled()); assertEquals(arrival + 8, f.director.getHoldUntilTick());
        }
        assertTrue(f.completed.isEmpty()); f.commit(); assertNull(f.director.getActiveCandidate());
        assertEquals(Arrays.asList(quiet), f.completed); assertEquals(deadline, quiet.getExpiresTick());
        assertEquals(1, f.guard.interactionCount()); assertEquals(0, f.guard.textCount());
        assertTrue(f.directorTrace.contains("scene complete M47")); assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void twoActualFailedExecutionsLeaveTheNextNativeAppraisalAsM09() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate current = f.firstAppraisal();
        for (int i = 0; i < 2; i++) { f.revise(current); current = f.settleAfterRetry(); }
        assertEquals(2, f.receipts.size()); assertEquals("M09", current.getScenarioId());
        assertFalse(f.director.isIntentionalSilent()); assertNotNull(f.director.getPacket());
        assertEquals(0, f.guard.interactionCount()); assertEquals(0, f.guard.textCount());
        assertFalse(f.directorTrace.stream().anyMatch(line -> line.contains("M47")));
    }

    @Test public void genuineRootDrawThenRevisionAndDelayedDuplicateAcknowledgementResetThePattern() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate current = f.firstAppraisal();
        for (int i = 0; i < 2; i++) { f.revise(current); current = f.settleAfterRetry(); }
        SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
        Runnable acknowledgement = f.paintWithoutAcknowledgement(packet);
        assertNull(f.director.getLastPaint()); assertEquals(0, f.guard.textCount());
        f.changeHead(); assertEquals(3, f.receipts.size());
        SpeechCancellationReceipt painted = f.receipts.get(2);
        assertEquals(Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, painted.getReason());
        assertTrue(painted.isGenuinePainted()); assertFalse(painted.isPrePaintAttempt());
        assertSame(packet.getFirstPaint(), f.director.getLastPaint());
        assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        f.clockNanos = 61_000_000_000L;
        for (int i = 0; i < 6; i++) { f.changeHead(); f.commit(); }
        f.commit(); SceneCandidate next = f.director.getActiveCandidate(); assertNotNull(next); assertEquals("M09", next.getScenarioId());
        int interactions = f.guard.interactionCount(), texts = f.guard.textCount(), samples = f.cooldowns.draws;
        acknowledgement.run(); acknowledgement.run(); f.healthy();
        assertSame(packet.getFirstPaint(), f.director.getLastPaint()); assertEquals(texts, f.guard.textCount());
        assertEquals(interactions, f.guard.interactionCount()); assertEquals(samples, f.cooldowns.draws);
        assertFalse(f.directorTrace.stream().anyMatch(line -> line.contains("M47")));
    }

    @Test public void aRealLoadingAndPresenceEpochResetCannotReuseTwoEarlierFailures() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate current = f.firstAppraisal();
        for (int i = 0; i < 2; i++) { f.revise(current); current = f.settleAfterRetry(); }
        long oldEpoch = f.presence.getPresentSinceTick();
        f.gameState(GameState.LOADING); f.commit(); assertFalse(f.actor.isVisible()); assertNull(f.director.getActiveCandidate());
        f.gameState(GameState.LOGGED_IN);
        for (int i = 0; i < 10 && !f.presence.isPresent(); i++) { f.commit(); }
        assertTrue(f.presence.isPresent()); assertTrue(f.actor.isVisible()); assertTrue(f.presence.getPresentSinceTick() > oldEpoch);
        while (f.tick() < f.presence.getPresentSinceTick() + 10) { f.commit(); }
        current = f.firstAppraisal(); f.revise(current); current = f.settleAfterRetry();
        assertEquals("M09", current.getScenarioId()); assertEquals(3, f.receipts.size());
        assertFalse(f.directorTrace.stream().anyMatch(line -> line.contains("M47")));
        assertEquals(0, f.guard.interactionCount()); assertEquals(0, f.guard.textCount());
    }

    @Test public void aCommittedAuditedBankInterruptionConsumesTheEarlierFailurePattern() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate current = f.firstAppraisal();
        for (int i = 0; i < 2; i++) { f.revise(current); current = f.settleAfterRetry(); }
        f.bank(true); f.inspect(); f.commit(); assertNull(f.director.getActiveCandidate());
        f.bank(false); f.inspect(); for (int i = 0; i < 6; i++) { f.commit(); }
        current = f.firstAppraisal(); f.revise(current); current = f.settleAfterRetry();
        assertEquals("M09", current.getScenarioId()); assertEquals(3, f.receipts.size());
        assertFalse(f.directorTrace.stream().anyMatch(line -> line.contains("M47")));
        assertEquals(0, f.guard.interactionCount());
    }

    @Test public void aFreshWatchedTupleLossBeforeNativeQuietArrivalCannotDebitOrRevive() throws Exception
    {
        Fixture f = new Fixture(); f.beforeQuietArrival = f::changeHead;
        f.threeFailures(f.firstAppraisal()); SceneCandidate quiet = f.quietObserved; assertNotNull(quiet);
        assertNull(f.director.getActiveCandidate()); assertFalse(f.observer.candidateStillValid(quiet));
        assertEquals(0, f.guard.interactionCount()); f.assertNoBubble();
        f.commit(); f.restoreQuietTuple(quiet); f.commit(); f.commit();
        assertFalse(f.observer.candidateStillValid(quiet)); assertEquals(0, f.guard.interactionCount());
        assertFalse(f.isActive("M47")); f.assertNoBubble();
    }

    @Test public void anUnknownCurrentEquipmentContainerBeforeNativeQuietArrivalIsNotAnEmptyTuple() throws Exception
    {
        Fixture f = new Fixture(); f.beforeQuietArrival = () ->
        {
            when(f.client.getItemContainer(InventoryID.WORN)).thenReturn(null);
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, null)); f.healthy();
        };
        f.threeFailures(f.firstAppraisal()); SceneCandidate quiet = f.quietObserved; assertNotNull(quiet);
        assertNull(f.director.getActiveCandidate()); assertFalse(f.observer.candidateStillValid(quiet));
        f.commit(); when(f.client.getItemContainer(InventoryID.WORN)).thenReturn(f.equipment);
        for (int i = 0; i < 3; i++) { f.commit(); }
        assertFalse(f.observer.candidateStillValid(quiet)); assertEquals(0, f.guard.interactionCount()); f.assertNoBubble();
    }

    @Test public void aFreshAuditedBankBeforeNativeQuietArrivalRefusesTheSilentDebit() throws Exception
    {
        Fixture f = new Fixture(); f.beforeQuietArrival = () -> { f.bank(true); f.inspect(); };
        f.threeFailures(f.firstAppraisal()); SceneCandidate quiet = f.quietObserved; assertNotNull(quiet);
        assertNull(f.director.getActiveCandidate()); assertFalse(f.observer.candidateStillValid(quiet));
        f.commit(); f.bank(false); f.inspect(); f.commit(); f.commit();
        assertFalse(f.observer.candidateStillValid(quiet)); assertEquals(0, f.guard.interactionCount()); f.assertNoBubble();
    }

    @Test public void anUnarrivedQuietSceneCanSettleOnTheLastTickBeforeItsOriginalDeadline() throws Exception
    {
        Fixture f = new Fixture(); f.beforeQuietArrival = f::blockCurrentStandingTile;
        SceneCandidate quiet = f.threeFailures(f.firstAppraisal()); assertNotNull(quiet);
        assertFalse(f.actor.hasArrived()); assertFalse(f.director.hasArrived()); assertFalse(f.director.isCommitted());
        long original = quiet.getExpiresTick();
        while (f.tick() < original - 2) { f.commit(); assertSame(quiet, f.director.getActiveCandidate()); assertEquals(0, f.guard.interactionCount()); }
        f.unblockCurrentStandingTile(); f.commit(); assertEquals(original - 1, f.tick()); f.assertQuietArrival(quiet);
        assertEquals(original, quiet.getExpiresTick()); assertEquals(original - 1 + 8, f.director.getHoldUntilTick());
        f.commit(); assertSame(quiet, f.director.getActiveCandidate()); assertFalse(f.director.isContinuationCancelled());
        assertEquals(1, f.guard.interactionCount());
    }

    @Test public void exactOriginalDeadlineRefusesAnUnarrivedNativeQuietSceneWithoutSlidingOrRetry() throws Exception
    {
        Fixture f = new Fixture(); f.beforeQuietArrival = f::blockCurrentStandingTile;
        SceneCandidate quiet = f.threeFailures(f.firstAppraisal()); assertNotNull(quiet);
        long original = quiet.getExpiresTick();
        while (f.tick() < original - 1) { f.commit(); assertSame(quiet, f.director.getActiveCandidate()); assertEquals(0, f.guard.interactionCount()); }
        f.unblockCurrentStandingTile(); f.commit(); assertEquals(original, f.tick()); assertNull(f.director.getActiveCandidate());
        assertFalse(f.observer.candidateStillValid(quiet)); assertEquals(original, quiet.getExpiresTick());
        assertTrue(f.guard.interactionEligible("M09")); assertEquals(0, f.guard.interactionCount()); f.assertNoBubble();
        for (int i = 0; i < 3; i++) { f.commit(); assertFalse(f.isActive("M47")); }
        assertEquals(0, f.guard.interactionCount()); assertTrue(f.completed.isEmpty());
    }

    @Test public void aCurrentTupleLossAfterSilentArrivalKeepsItsSingleTruthfulDebitAndStopsTheHold() throws Exception
    {
        Fixture f = new Fixture(); SceneCandidate quiet = f.threeFailures(f.firstAppraisal()); f.assertQuietArrival(quiet);
        int head = f.head; f.changeHead(); assertNull(f.director.getActiveCandidate()); assertFalse(f.observer.candidateStillValid(quiet));
        assertEquals(1, f.guard.interactionCount()); assertEquals(1, f.cooldowns.draws); assertEquals(0, f.guard.textCount());
        f.commit(); f.head = head; f.wornCallback(); f.commit();
        assertFalse(f.observer.candidateStillValid(quiet)); assertFalse(f.isActive("M47")); f.assertNoBubble();
        assertEquals(1, f.guard.interactionCount()); assertEquals(1, f.cooldowns.draws);
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class CountingRandom extends Random
    {
        final boolean chooseSpeech; int draws;
        CountingRandom(boolean chooseSpeech) { this.chooseSpeech = chooseSpeech; }
        @Override public int nextInt(int bound) { draws++; return chooseSpeech && bound == 100 ? 99 : 0; }
    }

    private static final class Fixture
    {
        private static final int[] HEADS = {1137, 1139, 1141, 1143, 1145, 1147, 1149, 1151, 1153, 1155, 1157, 1159, 1161, 1163, 1165, 1167, 1169};
        final Client client = mock(Client.class); final ClientThread clientThread = mock(ClientThread.class);
        final WorldView view = mock(WorldView.class); final Scene scene = mock(Scene.class); final Player player = mock(Player.class);
        final ItemContainer inventory = mock(ItemContainer.class), equipment = mock(ItemContainer.class);
        final MalfoidConfig config = mock(MalfoidConfig.class); final MalfoidPlugin plugin = new MalfoidPlugin();
        final Tile[][][] tiles = new Tile[4][104][104]; final int[][] flags = new int[104][104];
        final Queue<Runnable> closures = new ArrayDeque<>(), callbacks = new ArrayDeque<>();
        final List<Throwable> failures = new ArrayList<>(); final List<String> directorTrace = new ArrayList<>();
        final List<SpeechCancellationReceipt> receipts = new ArrayList<>(); final List<SceneCandidate> completed = new ArrayList<>();
        final CountingRandom choices = new CountingRandom(true), cooldowns = new CountingRandom(false);
        final NativeActor actor; final ScenarioObserver observer; final PresenceController presence;
        final DialogueCatalog catalogue; final SpeechDirector director; final SpeechGuard guard; final MalfoidSpeechOverlay overlay;
        final Object session;
        GameState state = GameState.LOGGED_IN;
        long clockNanos, frame; int cycle, head = -1, headIndex;
        SceneCandidate quietObserved; CheckedAction beforeQuietArrival;
        int selectionDrawsBeforeQuiet; long paintHandoffBeforeQuiet; LocalPoint positionBeforeQuiet;

        Fixture() throws Exception
        {
            when(client.isClientThread()).thenReturn(true); when(client.getGameState()).thenAnswer(call -> state);
            when(client.getLocalPlayer()).thenReturn(player); when(client.getWorldView(WorldView.TOPLEVEL)).thenReturn(view);
            when(client.getTopLevelWorldView()).thenReturn(view); when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getPlayerOptions()).thenReturn(new String[8]); when(client.getGameCycle()).thenAnswer(call -> cycle);
            when(view.getId()).thenReturn(WorldView.TOPLEVEL); when(view.isTopLevel()).thenReturn(true); when(view.getScene()).thenReturn(scene);
            when(view.getBaseX()).thenReturn(3200); when(view.getBaseY()).thenReturn(3200);
            when(view.getSizeX()).thenReturn(104); when(view.getSizeY()).thenReturn(104); when(view.contains(any(LocalPoint.class))).thenReturn(true);
            when(view.getTileSettings()).thenReturn(new byte[4][104][104]); when(scene.getTiles()).thenReturn(tiles);
            int[][][] heights = new int[4][105][105]; for (int[][] plane : heights) { for (int[] row : plane) { Arrays.fill(row, 120); } }
            when(view.getTileHeights()).thenReturn(heights); CollisionData collision = mock(CollisionData.class);
            when(collision.getFlags()).thenReturn(flags); when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
            when(player.getWorldView()).thenReturn(view); when(player.getWorldLocation()).thenReturn(new WorldPoint(3250, 3250, 0));
            when(player.getLocalLocation()).thenReturn(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
            when(player.getAnimation()).thenReturn(-1);
            int[] levels = new int[Skill.values().length], xp = new int[Skill.values().length]; Arrays.fill(levels, 50); Arrays.fill(xp, 1000);
            when(client.getRealSkillLevels()).thenReturn(levels); when(client.getBoostedSkillLevels()).thenReturn(levels); when(client.getSkillExperiences()).thenReturn(xp);
            when(client.getRealSkillLevel(any(Skill.class))).thenReturn(50); when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(50);
            when(inventory.getItems()).thenReturn(new Item[0]); when(equipment.getItems()).thenAnswer(call -> items());
            when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory); when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
            for (int id : HEADS)
            {
                ItemComposition definition = mock(ItemComposition.class); when(definition.getId()).thenReturn(id);
                when(definition.getName()).thenReturn("Helmet"); when(definition.getNote()).thenReturn(-1);
                when(definition.getPlaceholderTemplateId()).thenReturn(-1); when(client.getItemDefinition(id)).thenReturn(definition);
            }
            when(config.showMalfoid()).thenReturn(true); when(config.showDialogue()).thenReturn(true);
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
            catalogue = prospectiveCatalogue(); guard = new SpeechGuard(() -> clockNanos, cooldowns, catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            director = new SpeechDirector(catalogue, () -> clockNanos, choices, this::trace, guard);
            director.setCandidateValidator(candidate -> candidate.isRequested() || observer.candidateStillValid(candidate));
            director.setLineValidator(observer::groundOutcomeLineStillValid); director.setFirstPaintListener(observer::executionPainted);
            director.setCancellationListener(receipt ->
            {
                receipts.add(receipt);
                invoke("inspectionCancelled", new Class<?>[]{session.getClass(), SpeechCancellationReceipt.class}, session, receipt);
            });
            director.setCompletionListener(completed::add);
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
            set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
            observer.setScenarioEnabled(id -> "M09".equals(id) || "M47".equals(id));
            for (int[] row : flags) { Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
            flags[50][50] = flags[51][50] = flags[52][50] = 0;
            while (!actor.isVisible() && tick() < 4) { commit(); }
            assertTrue(actor.isVisible()); assertEquals(52, actor.getLocation().getSceneX()); assertEquals(50, actor.getLocation().getSceneY());
            for (int[] row : flags) { Arrays.fill(row, 0); }
            while (tick() < 24) { commit(); }
            assertTrue(presence.isPresent()); assertNull(director.getActiveCandidate()); assertEquals(0, guard.interactionCount());
            assertEquals(100, catalogue.getSilencePercent("M47")); assertEquals("M09", catalogue.repeatKey("M47"));
            assertEquals(5, catalogue.getLines("M47").size());
            assertFalse("Archived drafts cannot supply a usable runtime row", catalogue.getLines("M47").stream().anyMatch(line -> line.isEnabled()));
        }

        Item[] items() { Item[] items = new Item[14]; if (head >= 0) { items[0] = new Item(head, 1); } return items; }
        String tuple() { return head + ",-1,-1,-1"; }
        boolean isActive(String id) { return director.getActiveCandidate() != null && id.equals(director.getActiveCandidate().getScenarioId()); }
        long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }
        void changeHead() throws Exception { head = HEADS[headIndex++ % HEADS.length]; wornCallback(); }
        void wornCallback() throws Exception
        { plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment)); healthy(); }
        SceneCandidate firstAppraisal() throws Exception
        {
            changeHead(); commit(); assertNull(director.getActiveCandidate()); commit();
            SceneCandidate candidate = director.getActiveCandidate(); assertNotNull("Two actual current tuple copies must create M09", candidate);
            assertEquals("M09", candidate.getScenarioId()); assertTrue(actor.hasArrived()); assertTrue(director.hasArrived());
            assertNotNull(director.getPacket()); assertNull(director.getPacket().getFirstPaint()); assertFalse(director.isCommitted());
            assertTrue(catalogue.hasValidOriginalContext(candidate)); return candidate;
        }
        void revise(SceneCandidate candidate) throws Exception
        {
            assertSame(candidate, director.getActiveCandidate()); int count = receipts.size();
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory)); healthy();
            assertEquals("An unchanged inventory callback cannot invent an outfit cancellation", count, receipts.size());
            changeHead(); assertEquals(count + 1, receipts.size()); SpeechCancellationReceipt receipt = receipts.get(count);
            assertSame(candidate, receipt.getCandidate()); assertEquals(Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, receipt.getReason());
            assertTrue(receipt.isPrePaintAttempt()); assertFalse(receipt.isGenuinePainted()); assertNull(director.getActiveCandidate());
            assertEquals(0, guard.textCount()); assertEquals(0, guard.interactionCount());
        }
        SceneCandidate settleAfterRetry() throws Exception
        {
            long notBefore = receipts.get(receipts.size() - 1).getCancelledAtTick() + 5;
            // Every changed copy is a real callback; no two equal copies settle inside the retry guard.
            while (tick() < notBefore) { changeHead(); commit(); assertNull(director.getActiveCandidate()); }
            selectionDrawsBeforeQuiet = choices.draws; paintHandoffBeforeQuiet = guard.earliestOrdinaryPaint(); positionBeforeQuiet = actor.getLocation();
            commit(); healthy();
            SceneCandidate candidate = director.getActiveCandidate();
            if (quietObserved == null) { assertNotNull("The stable current appraisal must be admitted after its existing retry", candidate); }
            return candidate;
        }
        SceneCandidate threeFailures(SceneCandidate current) throws Exception
        {
            for (int i = 0; i < 3; i++)
            {
                revise(current); current = settleAfterRetry();
                if (i < 2) { assertNotNull(current); assertEquals("M09", current.getScenarioId()); }
            }
            assertNotNull("The third genuine failure must produce a current M47 replacement", quietObserved);
            assertEquals(3, receipts.size()); return current;
        }
        void assertQuietArrival(SceneCandidate quiet) throws Exception
        {
            assertNotNull(quiet); assertEquals("M47", quiet.getScenarioId()); assertSame(quietObserved, quiet);
            assertEquals(29, quiet.getPriority()); assertTrue(quiet.isIntentionalSilent()); assertEquals(8, quiet.getHoldTicks());
            assertTrue(observer.candidateStillValid(quiet)); assertTrue(catalogue.hasValidOriginalContext(quiet));
            assertTrue("Only the real legal native HOLD can supply arrival", actor.hasArrived()); assertTrue(director.hasArrived());
            assertTrue(director.isCommitted()); assertTrue(director.isIntentionalSilent()); assertFalse(actor.isMoving());
            assertEquals(positionBeforeQuiet, actor.getLocation()); assertNull(actor.getLatestMotionCompletion());
            assertEquals(quiet.getId(), ((Number) get(session, "directorSceneId")).longValue());
            ScenePlan plan = (ScenePlan) get(session, "plan"); assertFalse("P23 silent generic reactions never walk", plan.approach); assertFalse(plan.run);
            assertEquals(choices.draws, selectionDrawsBeforeQuiet); assertEquals(1, cooldowns.draws);
            assertEquals(paintHandoffBeforeQuiet, guard.earliestOrdinaryPaint()); assertEquals(1, guard.interactionCount());
            assertFalse(guard.interactionEligible("M09")); assertTrue(guard.interactionEligible("M04"));
            assertEquals(tick() + 8, director.getHoldUntilTick()); assertEquals(0, director.pendingCount()); assertTrue(completed.isEmpty());
            assertNull(quiet.getMilestone()); assertNoBubble();
        }
        void assertNoBubble()
        {
            assertNull(director.getPacket()); assertNull(director.getLastPaint()); assertEquals(0, guard.textCount()); assertEquals(0, guard.situationCount());
            assertFalse(AdditionalProducerFixture.hasPixels(render())); assertTrue(callbacks.isEmpty());
        }
        void restoreQuietTuple(SceneCandidate quiet) throws Exception
        { head = Integer.parseInt(quiet.getConditionFacts().get("equipmentIds").split(",")[0]); wornCallback(); }
        void bank(boolean shown)
        {
            if (!shown) { when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(null); return; }
            Widget bank = mock(Widget.class); when(bank.isHidden()).thenReturn(false); when(bank.getBounds()).thenReturn(new Rectangle(0, 0, 80, 80));
            when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
        }
        void blockCurrentStandingTile() { flags[actor.getLocation().getSceneX()][actor.getLocation().getSceneY()] = CollisionDataFlag.BLOCK_MOVEMENT_FULL; }
        void unblockCurrentStandingTile() { flags[actor.getLocation().getSceneX()][actor.getLocation().getSceneY()] = 0; }
        void gameState(GameState next) throws Exception
        { state = next; GameStateChanged event = new GameStateChanged(); event.setGameState(next); plugin.onGameStateChanged(event); healthy(); }
        void commit() throws Exception
        {
            long before = tick(); publish(); plugin.onGameTick(new GameTick()); assertEquals(1, closures.size()); closures.remove().run();
            assertEquals(before + 1, tick()); healthy();
        }
        void inspect() throws Exception { cycle++; plugin.onClientTick(new ClientTick()); healthy(); }
        void publish() { overlay.publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ++frame, new ArrayList<>(), ModalOcclusionSnapshot.CLEAR); }
        BufferedImage render()
        {
            publish(); BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB); Graphics2D graphics = image.createGraphics();
            try { overlay.render(graphics); } finally { graphics.dispose(); } assertTrue(failures.toString(), failures.isEmpty()); return image;
        }
        Runnable paintWithoutAcknowledgement(SpeechPacket packet) throws Exception
        {
            assertTrue(AdditionalProducerFixture.hasPixels(render())); assertNotNull(packet.getFirstPaint()); assertEquals(tick(), packet.getFirstPaint().getPaintedAtTick());
            assertSame(director.getActiveCandidate(), packet.getFirstPaint().getCandidate()); assertEquals(1, callbacks.size()); return callbacks.remove();
        }
        void trace(String line)
        {
            directorTrace.add(line);
            if ("scene start M47 silent".equals(line))
            {
                quietObserved = director.getActiveCandidate();
                if (beforeQuietArrival != null)
                {
                    try { beforeQuietArrival.run(); } catch (Exception failure) { throw new AssertionError(failure); }
                }
            }
        }
        void healthy() throws Exception
        { assertTrue(failures.toString(), failures.isEmpty()); assertFalse("Root failure containment must remain unused", ((AtomicBoolean) get(session, "failed")).get()); }
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
        DialogueCatalog prospectiveCatalogue() throws Exception
        {
            Gson gson = new Gson(); JsonObject copy;
            try (InputStream input = MalfoidPlugin.class.getResourceAsStream("/com/malfoid/dialogue.json"))
            { copy = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class); }
            boolean enabled = false; for (JsonElement id : copy.getAsJsonArray("runtimeEnabledScenarios")) { if ("M47".equals(id.getAsString())) { enabled = true; } }
            if (!enabled) { copy.getAsJsonArray("runtimeEnabledScenarios").add("M47"); }
            assertEquals(100, copy.getAsJsonObject("silencePercentByScenario").get("M47").getAsInt());
            for (JsonElement element : copy.getAsJsonArray("entries"))
            {
                JsonObject entry = element.getAsJsonObject();
                if ("M47".equals(entry.get("scenario").getAsString())) { assertEquals("disabled", entry.get("status").getAsString()); }
            }
            return DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
        }
    }

    private static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
