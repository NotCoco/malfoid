package com.malfoid.asset;

import com.malfoid.MalfoidPlugin;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldData;
import com.malfoid.world.LivingWorldMoments;
import com.malfoid.world.LivingWorldWaiting;
import com.malfoid.world.SpeechPostureTriggers;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import static com.malfoid.asset.NativeRecoveryRootFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real root command/outfit sources, native client-cycle samples and genuine overhead rasterization. */
public class R4NativePostureAcceptanceTest
{
    @Test public void eachOutfitHmphFoldsOnlyAfterTheActualPaintAndUnfoldsAtTheHoldEnd() throws Exception
    {
        for (String line : new String[]{"M09-H01", "M09-H02"})
        {
            NativeRecoveryRootFixture f = ready();
            f.observer.setScenarioEnabled("M09"::equals);
            f.director.setLineValidator((candidate, selected) -> !"M09".equals(candidate.getScenarioId()) || line.equals(selected));
            ItemContainer equipment = mock(ItemContainer.class); Item[] items = new Item[8]; items[4] = new Item(100, 1);
            when(equipment.getItems()).thenReturn(items); when(f.client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment)); f.commit(); f.commit();
            SpeechPacket packet = f.director.getPacket(); assertNotNull(packet); assertEquals(line, packet.getLineId());
            f.clientTicks(10); assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
            assertNull(packet.getFirstPaint());
            SpeechPacket.PaintReceipt paint = f.paintCurrent();
            assertEquals("CROSS_ARMS", f.actor.getLastClip()); f.clientTicks(20);
            assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
            f.commitsThrough(paint.getPaintedAtTick() + 2);
            assertTrue((Boolean) get(f.actor, "turnAway"));
            assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
            f.commitsThrough(f.director.getHoldUntilTick());
            assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
            assertEquals("IDLE", f.actor.getLastClip());
            assertSame(paint, f.director.getLastPaint());
        }
    }

    @Test public void talkHmphHasExactlyTheFourTickFoldHoldAndARealWalkCutsTheFoldedPose() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(id -> false);
        f.director.setLineValidator((candidate, line) -> !"U01".equals(candidate.getScenarioId()) || "U01-G28".equals(line));
        f.requestTalk(); f.commit(); assertNotNull(f.director.getPacket());
        SpeechPacket.PaintReceipt paint = f.paintCurrent(); assertEquals("U01-G28", paint.getLineId());
        f.clientTicks(20); assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        f.commitsThrough(paint.getPaintedAtTick() + 4); assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());

        // The third answer owns a longer fold, which genuine following must cut without a reverse blend.
        f = ready(); f.observer.setScenarioEnabled(id -> false);
        f.director.setLineValidator((candidate, line) -> !"U01".equals(candidate.getScenarioId())
            || "U01-G01".equals(line) || "U01-S01".equals(line) || "U01-T01".equals(line));
        for (int answer = 1; answer <= 3; answer++)
        {
            requestAnswer(f, answer);
            assertEquals(Integer.toString(answer), f.director.getPacket().getCandidate().getConditionFacts().get("talkStreak"));
            f.paintCurrent(); if (answer < 3) { f.waitUntilIdle(); }
        }
        f.clientTicks(20); assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        net.runelite.api.coords.LocalPoint companion = f.actor.getLocation();
        int dx = Integer.signum(f.playerX - companion.getSceneX());
        int dy = dx == 0 ? Integer.signum(f.playerY - companion.getSceneY()) : 0;
        assertTrue("The real settled companion is separate from the player", dx != 0 || dy != 0);
        f.director.invalidate(true); f.playerX += dx * 2; f.playerY += dy * 2; f.commit();
        f.playerX += dx * 2; f.playerY += dy * 2; f.plugin.onGameTick(new GameTick());
        assertEquals(1, f.closures.size()); f.closures.remove().run();
        // A genuinely offered higher scene can own the next plan; wait within its own deadline.
        for (int i = 0; !f.actor.isMoving() && i < 4; i++)
        {
            f.clientTicks(30); f.plugin.onGameTick(new GameTick());
            assertEquals(1, f.closures.size()); f.closures.remove().run();
        }
        assertTrue("Actual follow route: position=" + f.actor.getLocation() + " player=" + f.playerX + "," + f.playerY
            + " separation=" + f.actor.getDistanceToPlayer() + " intent=" + get(f.actor, "intent")
            + " plan=" + f.actor.getLastPlannerStatus() + " steps=" + f.actor.getLastPlannerSteps() + " logs=" + f.logs(), f.actor.isMoving());
        f.clientTicks(1);
        assertTrue("A moving client cycle must paint the locomotion clip", "WALK".equals(f.actor.getLastClip())
            || "RUN".equals(f.actor.getLastClip()));
        assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
    }

    @Test public void theFourthSilentNativeTurnKeepsArmsFoldedUntilFiftyTicksWithoutAnotherCommand() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(id -> false);
        f.director.setLineValidator((candidate, line) -> !"U01".equals(candidate.getScenarioId())
            || "U01-G01".equals(line) || "U01-S01".equals(line) || "U01-T01".equals(line));
        for (int answer = 1; answer <= 3; answer++)
        {
            requestAnswer(f, answer); f.paintCurrent(); if (answer < 3) { f.waitUntilIdle(); }
        }
        f.commit(); f.requestTalk(); long command = f.tick(); f.commit();
        SceneCandidate fourth = f.director.getActiveCandidate(); assertNotNull(fourth);
        assertEquals("4", fourth.getConditionFacts().get("talkStreak")); assertNull(f.director.getPacket());
        assertTrue((Boolean) get(f.actor, "turnAway"));
        f.clientTicks(20); assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        // Holding a modal stops the ordinary stroll, while the authored 50-tick reset still expires.
        when(f.client.getVarcStrValue(anyInt())).thenReturn("typing");
        f.commitsThrough(command + 49); assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        f.commitsThrough(command + 50); assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
    }

    @Test public void ordinaryIndignantTalkCanFoldAfterTheSceneMovesZeroDistance() throws Exception
    { assertIndignantTranslation(false); }

    @Test public void ordinaryIndignantTalkCannotFoldAfterItsSceneApproachReallyWalks() throws Exception
    { assertIndignantTranslation(true); }

    private static void assertIndignantTranslation(boolean walked) throws Exception
    {
            NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(id -> false);
            Draws chance = new Draws(); set(f.session, "speechPostures", new SpeechPostureTriggers(chance));
            f.director.setLineValidator((candidate, line) -> !"U01".equals(candidate.getScenarioId()) || "U01-G03".equals(line));
            if (walked) { f.playerX -= 2; }
            long edges = ((com.malfoid.motion.RouteMotion) get(f.actor, "motion")).getCompletedEdges();
            requestAnswer(f, 1); SpeechPacket.PaintReceipt paint = f.paintCurrent();
            assertEquals("U01-G03", paint.getLineId());
            long completed = ((com.malfoid.motion.RouteMotion) get(f.actor, "motion")).getCompletedEdges() - edges;
            assertEquals(walked, completed > 0);
            assertEquals(walked ? 0 : 1, chance.draws);
            f.clientTicks(20);
            assertEquals(walked ? PosturePlayback.Posture.STANDING : PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
    }

    private static NativeRecoveryRootFixture ready() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture();
        doReturn(indexed(Collections.singletonList(f.player))).when(f.view).players();
        doReturn(indexed(Collections.<NPC>emptyList())).when(f.view).npcs();
        f.observer.setScenarioEnabled(id -> false); f.showOrigin(); f.baseline();
        assertTrue(f.actor.hasEmotes());
        LivingWorldData data = LivingWorldData.load(MalfoidPlugin.class); AtomicLong ids = new AtomicLong(1_000_000_000_000L);
        set(f.session, "worldData", data);
        set(f.session, "worldMoments", new LivingWorldMoments(data, new QuietRandom(), ids::incrementAndGet));
        set(f.session, "worldWaiting", new LivingWorldWaiting(new QuietRandom()));
        set(f.session, "speechPostures", new SpeechPostureTriggers(new QuietRandom()));
        set(f.director, "random", new Random() { @Override public int nextInt(int bound) { return 0; } });
        Method paint = MalfoidPlugin.class.getDeclaredMethod("speechPainted", f.session.getClass(), SpeechPacket.PaintReceipt.class);
        Method silent = MalfoidPlugin.class.getDeclaredMethod("silentSceneArrived", f.session.getClass(), SceneCandidate.class);
        paint.setAccessible(true); silent.setAccessible(true);
        f.director.setFirstPaintListener(receipt -> invoke(paint, f, receipt));
        f.director.setSilentArrivalListener(candidate -> invoke(silent, f, candidate));
        return f;
    }
    private static void requestAnswer(NativeRecoveryRootFixture f, int answer) throws Exception
    {
        f.requestTalk();
        SceneCandidate command = f.director.getActiveCandidate();
        assertNotNull("The real Talk command must be admitted: " + f.logs(), command);
        assertEquals(Integer.toString(answer), command.getConditionFacts().get("talkStreak"));
        for (int i = 0; f.director.getPacket() == null && f.director.getActiveCandidate() != null && i < 7; i++) { f.commit(); }
        assertNotNull("Talk reaches its actual native arrival before expiry: " + f.logs(), f.director.getPacket());
    }
    private static void invoke(Method method, NativeRecoveryRootFixture f, Object value)
    {
        try { method.invoke(f.plugin, f.session, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static <T> IndexedObjectSet<T> indexed(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
    private static final class QuietRandom extends Random
    { @Override public int nextInt(int bound) { return bound - 1; } }
    private static final class Draws extends Random
    { int draws; @Override public int nextInt(int bound) { draws++; return 0; } }
}
