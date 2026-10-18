package com.malfoid.asset;

import com.malfoid.MalfoidPlugin;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldCreatures;
import com.malfoid.world.LivingWorldData;
import com.malfoid.world.LivingWorldMoments;
import com.malfoid.world.LivingWorldPlaces;
import com.malfoid.world.LivingWorldWaiting;
import com.malfoid.world.SpeechPostureTriggers;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import org.junit.Test;
import static com.malfoid.asset.NativeRecoveryRootFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual committed waiting facts, native loop-entry receipts, root line offer and overhead paint. */
public class R4NativeWaitingAcceptanceTest
{
    @Test public void theLoopStartsAfterARealWaitingFactAndItsOwnWorldSentenceDoesNotCancelIt() throws Exception
    {
        Fixture f = new Fixture();
        for (int tick = 0; tick < 149; tick++) { f.commit(); }
        assertFalse(f.waiting.isActive()); assertNull(f.nativeRoot.director.getPacket());
        f.commit(); assertTrue(f.waiting.isActive());
        assertEquals(1, f.waiting.getRecentEmoteCount());
        assertEquals("IMPATIENT", f.nativeRoot.actor.getLastClip());
        SpeechPacket packet = f.nativeRoot.director.getPacket(); assertNotNull(packet);
        assertEquals("G-WAITING", packet.getCandidate().getScenarioId()); assertNull(packet.getFirstPaint());
        assertEquals(0, f.nativeRoot.guard.textCount()); assertTrue(f.nativeRoot.director.livingWorldSpacingReady());
        SpeechPacket.PaintReceipt paint = f.nativeRoot.paintCurrent();
        assertEquals(1, f.nativeRoot.guard.textCount()); assertEquals(1, f.nativeRoot.guard.interactionCount());
        assertFalse(f.nativeRoot.director.livingWorldSpacingReady()); assertTrue(f.waiting.isActive());
        assertEquals("IMPATIENT", f.nativeRoot.actor.getLastClip());
        int start = f.nativeRoot.actor.getImpatientLoopStartCycle();
        while (f.nativeRoot.cycle < start + 8 * 30) { f.nativeRoot.clientTicks(1); }
        assertFalse(f.waiting.isActive()); assertEquals(start + 8 * 30, f.nativeRoot.actor.getImpatientEndCycle());
        assertEquals("CROSS_ARMS", f.nativeRoot.actor.getLastClip()); f.nativeRoot.clientTicks(20);
        assertEquals(PosturePlayback.Posture.STANDING, f.nativeRoot.actor.getPosture());
        assertSame(paint, f.nativeRoot.director.getLastPaint());
        long ended = f.waiting.getLastEndTick();
        while (f.nativeRoot.tick() < ended + 399) { f.commit(); }
        assertEquals("No second emote can start within 400 committed ticks", 1, f.waiting.getRecentEmoteCount());
    }

    @Test public void anActiveRealTalkSceneBlocksWaitingAndWalkingOffCutsTheNativeLoopOnItsMovingCycle() throws Exception
    {
        Fixture blocked = new Fixture();
        for (int tick = 0; tick < 149; tick++) { blocked.commit(); }
        blocked.nativeRoot.requestTalk(); blocked.commit();
        assertEquals("U01", blocked.nativeRoot.director.getActiveCandidate().getScenarioId());
        assertFalse(blocked.waiting.isActive()); assertEquals(0, blocked.waiting.getRecentEmoteCount());
        assertNotEquals("IMPATIENT", blocked.nativeRoot.actor.getLastClip());

        Fixture moving = new Fixture(); for (int tick = 0; tick < 150; tick++) { moving.commit(); }
        assertEquals("IMPATIENT", moving.nativeRoot.actor.getLastClip());
        moving.nativeRoot.fill(0);
        for (int step = 0; step < 3; step++) { moving.nativeRoot.playerX += 2; moving.commit(); }
        assertFalse(moving.waiting.isActive()); assertEquals(PosturePlayback.Posture.STANDING, moving.nativeRoot.actor.getPosture());
        assertTrue("Genuine native following must be advancing", moving.nativeRoot.actor.getMotionObservation().completedEdges > 0);
        assertTrue("The locomotion pose replaces the emote", "WALK".equals(moving.nativeRoot.actor.getLastClip())
            || "RUN".equals(moving.nativeRoot.actor.getLastClip()) || !moving.nativeRoot.actor.isMoving());
    }

    private static final class Fixture
    {
        final NativeRecoveryRootFixture nativeRoot = new NativeRecoveryRootFixture();
        final LivingWorldWaiting waiting = new LivingWorldWaiting(new ZeroRandom());
        Fixture() throws Exception
        {
            NativeRecoveryRootFixture f = nativeRoot;
            doReturn(indexed(Collections.singletonList(f.player))).when(f.view).players();
            doReturn(indexed(Collections.<NPC>emptyList())).when(f.view).npcs();
            f.observer.setScenarioEnabled(id -> false); f.showOrigin(); f.baseline(); f.boxSource();
            LivingWorldData data = LivingWorldData.load(MalfoidPlugin.class);
            DialogueCatalog catalog = f.catalogue.withLivingWorld(data);
            SpeechGuard guard = new SpeechGuard(() -> f.clockNanos, new ZeroRandom(), catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            SpeechDirector director = new SpeechDirector(catalog, () -> f.clockNanos, new ZeroRandom(), ignored -> {}, guard);
            MalfoidSpeechOverlay overlay = new MalfoidSpeechOverlay(director, f.clientThread, f.failures::add);
            AtomicLong ids = new AtomicLong(1_000_000_000_000L);
            set(f.session, "worldData", data); set(f.session, "worldPlaces", new LivingWorldPlaces(data, new QuietRandom(), ids::incrementAndGet));
            set(f.session, "worldCreatures", new LivingWorldCreatures(new QuietRandom(), ids::incrementAndGet));
            set(f.session, "worldMoments", new LivingWorldMoments(data, new ZeroRandom(), ids::incrementAndGet));
            set(f.session, "worldWaiting", waiting); set(f.session, "speechPostures", new SpeechPostureTriggers(new QuietRandom()));
            set(f.session, "catalog", catalog); set(f.session, "director", director); set(f.session, "overlay", overlay);
            set(f, "catalogue", catalog); set(f, "director", director); set(f, "guard", guard); set(f, "overlay", overlay);
            Method valid = MalfoidPlugin.class.getDeclaredMethod("livingWorldCandidateValid", f.session.getClass(), SceneCandidate.class);
            Method paint = MalfoidPlugin.class.getDeclaredMethod("speechPainted", f.session.getClass(), SpeechPacket.PaintReceipt.class);
            valid.setAccessible(true); paint.setAccessible(true);
            director.resetSession(((Number) get(f.session, "sessionGeneration")).longValue());
            director.setCandidateValidator(candidate -> candidate.isRequested()
                || (director.isLivingWorld(candidate) ? (Boolean) invoke(valid, candidate) : f.observer.candidateStillValid(candidate)));
            director.setFirstPaintListener(receipt -> invoke(paint, receipt));
            director.tick(f.tick(), ((Number) get(f.session, "sessionGeneration")).longValue(),
                ((Number) get(f.session, "sceneRevision")).longValue(), true, true, false);
            assertTrue(f.actor.hasEmotes());
        }
        Object invoke(Method method, Object argument)
        {
            try { return method.invoke(nativeRoot.plugin, nativeRoot.session, argument); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void commit() throws Exception
        {
            nativeRoot.commit();
            clearInvocations(nativeRoot.client, nativeRoot.clientThread, nativeRoot.player, nativeRoot.view,
                nativeRoot.scene, get(nativeRoot.observer, "itemManager"));
        }
    }
    private static <T> IndexedObjectSet<T> indexed(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
    private static final class ZeroRandom extends Random
    { @Override public int nextInt(int bound) { return 0; } }
    private static final class QuietRandom extends Random
    { @Override public int nextInt(int bound) { return bound - 1; } @Override public double nextDouble() { return 0.99; } }
}
