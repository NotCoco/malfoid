package com.malfoid.acceptance;

import com.malfoid.MalfoidPlugin;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.motion.CreatureAwareness;
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldCreatures;
import com.malfoid.world.LivingWorldData;
import com.malfoid.world.LivingWorldPlaces;
import com.malfoid.world.LivingWorldMoments;
import com.malfoid.world.LivingWorldWaiting;
import com.malfoid.world.SpeechPostureTriggers;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.CollisionData;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import static com.malfoid.acceptance.AdditionalProducerFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real root closures and overlay receipts; the existing fixture supplies a prepared native visual. */
final class LivingWorldDeliveryFixture
{
    final AdditionalProducerFixture f;
    final LivingWorldData data;
    final DialogueCatalog cat;
    final SpeechDirector director;
    final SpeechGuard guard;
    final MalfoidSpeechOverlay overlay;
    final LivingWorldPlaces places;
    final LivingWorldCreatures creatures;
    final LivingWorldMoments moments;
    final LivingWorldWaiting waiting;
    final SpeechPostureTriggers postures;
    final AtomicLong ids = new AtomicLong(1_000_000_000_000L);
    final ZeroRandom worldRandom = new ZeroRandom();
    private final Method candidateValid;
    private long frame;
    private int cycle;

    LivingWorldDeliveryFixture() throws Exception { this(12); }

    LivingWorldDeliveryFixture(long baselineThrough) throws Exception
    {
        f = new AdditionalProducerFixture(147, baselineThrough);
        data = LivingWorldData.load(MalfoidPlugin.class);
        cat = f.catalogue.withLivingWorld(data);
        guard = new SpeechGuard(() -> f.clockNanos, new ZeroRandom(), cat.getMaximumTextKeys(),
            cat.getMaximumInteractionKeys());
        director = new SpeechDirector(cat, () -> f.clockNanos, new ZeroRandom(), ignored -> {}, guard);
        overlay = new MalfoidSpeechOverlay(director, f.clientThread, f.failures::add);
        places = new LivingWorldPlaces(data, worldRandom, ids::incrementAndGet);
        creatures = new LivingWorldCreatures(worldRandom, ids::incrementAndGet);
        moments = new LivingWorldMoments(data, worldRandom, ids::incrementAndGet);
        waiting = new LivingWorldWaiting(new QuietRandom());
        postures = new SpeechPostureTriggers(new QuietRandom());
        set(f.session, "worldData", data); set(f.session, "worldPlaces", places); set(f.session, "worldCreatures", creatures);
        set(f.session, "worldMoments", moments); set(f.session, "worldWaiting", waiting); set(f.session, "speechPostures", postures);
        set(f.session, "catalog", cat); set(f.session, "director", director); set(f.session, "overlay", overlay);
        f.observer.setLivingWorldData(data);
        candidateValid = MalfoidPlugin.class.getDeclaredMethod("livingWorldCandidateValid",
            f.session.getClass(), SceneCandidate.class);
        candidateValid.setAccessible(true);
        director.setCandidateValidator(candidate -> candidate.isRequested()
            || (director.isLivingWorld(candidate) ? valid(candidate) : f.observer.candidateStillValid(candidate)));
        director.setLineValidator(f.observer::groundOutcomeLineStillValid);
        Method painted = MalfoidPlugin.class.getDeclaredMethod("speechPainted", f.session.getClass(), SpeechPacket.PaintReceipt.class);
        painted.setAccessible(true);
        director.setFirstPaintListener(receipt ->
        {
            try { painted.invoke(f.plugin, f.session, receipt); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        });
        Method classify = MalfoidPlugin.class.getDeclaredMethod("livingWorldClassification", LivingWorldData.CreatureGroup.class);
        classify.setAccessible(true);
        f.actor.setCreatureClassifier((name, level) ->
        {
            try
            {
                return (CreatureAwareness.Classification) classify.invoke(null, data.creature(name, level == null ? 0 : level));
            }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        });
        f.actor.setLocalFollowerClassification((CreatureAwareness.Classification) classify.invoke(null, data.group("CR-PET")));
        when(f.client.getGameCycle()).thenAnswer(call -> cycle);
        director.resetSession(((Number) get(f.session, "sessionGeneration")).longValue());
        publish();
    }

    boolean valid(SceneCandidate candidate)
    {
        try { return (Boolean) candidateValid.invoke(f.plugin, f.session, candidate); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    void baseline() throws Exception
    {
        hold(5);
        assertTrue(f.presence.isPresent()); assertTrue(director.isIdle());
        assertEquals("The first stable place is quiet", 0, worldRandom.draws);
    }

    void commit() throws Exception
    {
        f.clockNanos += 600_000_000L;
        f.commit();
        assertEquals(f.tick(), director.getTick());
        assertHealthy();
        clearReplayMockHistory();
    }

    void hold(int ticks) throws Exception { for (int i = 0; i < ticks; i++) { commit(); } }

    void moveTo(int x, int y) throws Exception
    {
        while (f.point.getX() != x || f.point.getY() != y)
        {
            int dx = Math.max(-2, Math.min(2, x - f.point.getX()));
            int dy = Math.max(-2, Math.min(2, y - f.point.getY()));
            f.point = new WorldPoint(f.point.getX() + dx, f.point.getY() + dy, f.point.getPlane());
            commit();
        }
    }

    /** Enable actual native GameTick planning and ClientTick edge advancement, never synthetic receipts. */
    void enableNative() throws Exception { set(f.session, "resourcesLoaded", true); }

    void cycles(int count) throws Exception
    {
        for (int i = 0; i < count; i++)
        {
            cycle++; f.plugin.onClientTick(new ClientTick()); assertHealthy(); clearReplayMockHistory();
        }
    }

    void clearReplayMockHistory() throws Exception
    {
        // These replays assert actual fields, motion and paint receipts, never recorded mock calls.
        // As in R2CNativeAcceptanceTest, retain every stub while bounding long replay history.
        Set<Object> mocks = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(mocks, f.client, f.clientThread, f.player, f.view, f.config, f.inventory,
            f.equipment, f.view.getScene(), get(f.observer, "itemManager"));

        CollisionData[] collisions = f.view.getCollisionMaps();
        if (collisions != null)
        {
            for (CollisionData collision : collisions) { if (collision != null) { mocks.add(collision); } }
        }
        Object bank = get(f.actor, "bank");
        if (bank != null)
        {
            for (Model model : (Model[]) get(bank, "models")) { if (model != null) { mocks.add(model); } }
        }
        IndexedObjectSet<? extends NPC> npcs = f.view.npcs();
        if (npcs != null)
        {
            int inspected = 0;
            for (NPC npc : npcs)
            {
                if (inspected++ >= 256) { break; }
                if (npc == null) { continue; }
                NPCComposition base = npc.getComposition(), transformed = npc.getTransformedComposition();
                if (base != null) { mocks.add(base); }
                if (transformed != null) { mocks.add(transformed); }
                mocks.add(npc);
            }
        }
        mocks.remove(null);
        clearInvocations(mocks.toArray());
    }

    SpeechPacket issued(String scenario) throws Exception
    {
        SpeechPacket packet = director.getPacket(); assertNotNull("Root must issue the observed living-world scene", packet);
        assertEquals(scenario, packet.getCandidate().getScenarioId());
        assertTrue(cat.isLivingWorldScenario(scenario)); assertTrue(valid(packet.getCandidate()));
        assertHealthy(); return packet;
    }

    void publish()
    {
        overlay.publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ++frame,
            new ArrayList<>(), ModalOcclusionSnapshot.CLEAR);
    }

    BufferedImage render()
    {
        BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { overlay.render(graphics); } finally { graphics.dispose(); }
        assertTrue(f.failures.toString(), f.failures.isEmpty()); return image;
    }

    SpeechPacket.PaintReceipt paint(SpeechPacket packet) throws Exception
    {
        int texts = guard.textCount(), interactions = guard.interactionCount();
        assertNull(packet.getFirstPaint()); publish();
        assertTrue("The actual overlay must draw pixels", hasPixels(render()));
        SpeechPacket.PaintReceipt receipt = packet.getFirstPaint(); assertNotNull(receipt);
        assertEquals(f.tick(), receipt.getPaintedAtTick());
        assertEquals(texts, guard.textCount()); assertEquals(interactions, guard.interactionCount());
        assertEquals("Render only queues acknowledgement", 1, f.callbacks.size());
        drain(); assertSame(receipt, director.getLastPaint());
        assertEquals(texts + 1, guard.textCount()); assertEquals(interactions + 1, guard.interactionCount());
        assertHealthy(); return receipt;
    }

    void assertNoPaint(SpeechPacket packet) throws Exception
    {
        assertFalse(packet.canPaint(f.clockNanos));
        assertFalse("A refused capability cannot paint the published snapshot", hasPixels(render()));
        assertNull(packet.getFirstPaint()); assertTrue(f.callbacks.isEmpty());
        assertEquals(0, guard.textCount()); assertEquals(0, guard.interactionCount()); assertHealthy();
    }

    void drain() { f.drain(); }

    void assertHealthy() throws Exception { f.assertHealthy(); assertTrue(f.failures.toString(), f.failures.isEmpty()); }

    static final class ZeroRandom extends Random
    {
        int draws;
        @Override public double nextDouble() { draws++; return 0; }
        @Override public int nextInt(int bound) { draws++; return 0; }
    }
    private static final class QuietRandom extends Random
    { @Override public int nextInt(int bound) { return bound - 1; } }
}
