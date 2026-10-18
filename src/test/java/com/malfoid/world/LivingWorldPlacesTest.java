package com.malfoid.world;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldPlacesTest
{
    private static final int LUMBRIDGE = 12850, VARROCK = 12596, EXCHANGE = 12598, FALADOR = 11828;

    @Test public void initialSessionPlaceIsAQuietFiveTickBaseline() throws Exception
    {
        Fixture f = new Fixture();
        assertNull(f.stay(LUMBRIDGE, 5, true));
        assertNull(f.stay(LUMBRIDGE, 50, true));
        assertEquals(0, f.random.draws); assertEquals(0, f.ids.get());
        assertNotNull(f.stay(VARROCK, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void newPlaceNeedsFiveConsecutiveCommittedTicksAndOffersOnlyOnTheFifth() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 4, true));
        SceneCandidate arrival = f.observe(VARROCK, true);
        assertNotNull(arrival);
        assertEquals("PL-VARROCK", arrival.getScenarioId());
        assertEquals(10, arrival.getCreatedTick()); assertEquals(11, arrival.getExpiresTick());
        assertEquals(-100, arrival.getPriority()); assertEquals(0, arrival.getHoldTicks());
        assertFalse(arrival.isIntentionalSilent());
        assertEquals(1, arrival.getSessionGeneration()); assertEquals(1, arrival.getSceneRevision());
        assertEquals("PLACE", arrival.getConditionFacts().get("livingWorld"));
        assertEquals("PL-VARROCK", arrival.getConditionFacts().get("placeEntry"));
        assertEquals("PLACE_ARRIVAL:PL-VARROCK:10", arrival.getCausalToken());
        assertNull(f.stay(VARROCK, 100, true));
    }

    @Test public void sameKindAcrossDifferentRegionsIsOneArrival() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.observe(12587, true)); assertNull(f.observe(12844, true));
        assertNull(f.observe(13358, true)); assertNull(f.observe(13613, true));
        SceneCandidate desert = f.observe(12843, true);
        assertNotNull(desert); assertEquals("PT-DESERT", desert.getScenarioId());
        assertNull(f.stay(13613, 5, true)); assertNull(f.stay(13358, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void steppingBackAndForthAcrossARegionBorderDoesNotQueueArrivals() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        for (int i = 0; i < 100; i++) { assertNull(f.observe(i % 2 == 0 ? VARROCK : LUMBRIDGE, true)); }
        assertEquals(0, f.random.draws);
        assertNotNull(f.stay(VARROCK, 5, true));
        for (int i = 0; i < 100; i++) { assertNull(f.observe(i % 2 == 0 ? LUMBRIDGE : VARROCK, true)); }
        assertEquals(1, f.random.draws);
    }

    @Test public void unmappedRegionsNeverOfferOrReplaceThePreviousArrival() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(65535, 10, true));
        assertNull(f.stay(LUMBRIDGE, 5, true));
        assertEquals(0, f.random.draws);
        assertNotNull(f.stay(VARROCK, 5, true));
    }

    @Test public void unknownInitialRegionsDoNotInventAPlaceBeforeTheFirstQuietBaseline() throws Exception
    {
        Fixture f = new Fixture();
        assertNull(f.stay(65535, 10, true));
        assertNull(f.stay(LUMBRIDGE, 5, true));
        assertNotNull(f.stay(VARROCK, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void blockedArrivalIsConsumedRatherThanDeferredUntilSpeechBecomesEligible() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 4, true));
        assertNull(f.observe(VARROCK, false));
        assertNull(f.stay(VARROCK, 20, true));
        assertEquals(0, f.random.draws);
        assertNotNull(f.stay(EXCHANGE, 5, true));
        assertNull(f.stay(VARROCK, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void everyArrivalIncludingABlockedOneRestartsIts1500TickMemory() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNotNull(f.stay(VARROCK, 5, true)); // Arrival at 10.
        assertNull(f.stay(LUMBRIDGE, 5, false));
        f.tick = 1504;
        assertNull(f.stay(VARROCK, 5, true)); // At 1509: only 1499 ticks since its last arrival.
        assertNull(f.stay(LUMBRIDGE, 5, false));
        f.tick = 3004;
        assertNotNull(f.stay(VARROCK, 5, true)); // At 3009: 1500 since the blocked arrival.
        assertEquals(2, f.random.draws);
    }

    @Test public void quietBaselineAlsoCountsAsAnArrivalForSessionMemory() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNotNull(f.stay(VARROCK, 5, true));
        assertNull(f.stay(LUMBRIDGE, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void sixtyPercentDrawIsMadeOnceAndASilentDrawDoesNotStartThePaintCooldown() throws Exception
    {
        Fixture f = new Fixture(); f.baseline(); f.random.values.add(0.6);
        assertNull(f.stay(VARROCK, 5, true));
        assertNull(f.stay(VARROCK, 20, true));
        assertEquals(1, f.random.draws);
        f.random.values.add(0.599999);
        assertNotNull(f.stay(EXCHANGE, 5, true));
        assertEquals(2, f.random.draws);
    }

    @Test public void merelyOfferedButUnpaintedLinesDoNotSuppressAnotherPlaceFor300Ticks() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNotNull(f.stay(VARROCK, 5, true));
        assertNotNull(f.stay(EXCHANGE, 5, true));
        assertEquals(2, f.random.draws);
    }

    @Test public void actualPlacePaintBlocksFor299TicksAndAllowsThe300TickBoundary() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        SceneCandidate varrock = f.stay(VARROCK, 5, true); assertNotNull(varrock);
        f.places.firstPaint(varrock.getScenarioId(), f.data.group(varrock.getScenarioId()).lines.get(0).id, 10);
        f.tick = 304;
        assertNull(f.stay(EXCHANGE, 5, true)); // Arrival at 309: 299 ticks after paint.
        assertNotNull(f.stay(FALADOR, 5, true));

        Fixture boundary = new Fixture(); boundary.baseline();
        SceneCandidate first = boundary.stay(VARROCK, 5, true);
        boundary.places.firstPaint(first.getScenarioId(), boundary.data.group(first.getScenarioId()).lines.get(0).id, 10);
        boundary.tick = 305;
        assertNotNull(boundary.stay(EXCHANGE, 5, true)); // Exactly 300 ticks after paint.
    }

    @Test public void sceneChangesResetPartialEvidenceWithoutReplayingThePreviousArrival() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 4, true)); f.revision++;
        assertNull(f.stay(VARROCK, 4, true));
        assertNotNull(f.observe(VARROCK, true));
        f.revision++; assertNull(f.stay(VARROCK, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void absenceResetsPartialEvidenceButPreservesArrivalAndPaintMemory() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        SceneCandidate first = f.stay(VARROCK, 5, true);
        f.places.firstPaint(first.getScenarioId(), f.data.group(first.getScenarioId()).lines.get(0).id, f.tick);
        assertNull(f.raw(EXCHANGE, true, false, true));
        assertNull(f.stay(VARROCK, 5, true));
        assertNull(f.stay(EXCHANGE, 5, true));
        assertEquals(1, f.random.draws);
        assertNull(f.raw(FALADOR, true, false, true));
        assertNull(f.stay(FALADOR, 4, true));
        assertNull(f.observe(FALADOR, true));
    }

    @Test public void nonCommittedObservationResetsTheWindowAndCannotCreateAnArrival() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 4, true));
        assertNull(f.raw(VARROCK, false, true, true));
        assertNull(f.stay(VARROCK, 4, true));
        assertNotNull(f.observe(VARROCK, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void tickDiscontinuityResetsOnlyPartialEvidence() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 4, true)); f.tick += 10;
        assertNull(f.stay(VARROCK, 4, true)); assertNotNull(f.observe(VARROCK, true));
        f.tick += 1000; assertNull(f.stay(VARROCK, 5, true));
        assertEquals(1, f.random.draws);
    }

    @Test public void duplicateCommittedTickDoesNotCountAsASecondObservation() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNull(f.stay(VARROCK, 3, true));
        assertNull(f.places.observe(f.tick, f.generation, f.revision, VARROCK, true, true, true));
        assertNull(f.stay(VARROCK, 3, true));
        assertNotNull(f.observe(VARROCK, true));
    }

    @Test public void resetObservationPreservesMemoryButANewSessionGetsANewQuietBaseline() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        assertNotNull(f.stay(VARROCK, 5, true)); f.places.resetObservation();
        assertNull(f.stay(VARROCK, 5, true));
        f.generation++; assertNull(f.stay(VARROCK, 5, true));
        assertNotNull(f.stay(LUMBRIDGE, 5, true));
        assertEquals(2, f.random.draws);
    }

    @Test public void sessionResetDiscardsOldPaintCooldown() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        SceneCandidate first = f.stay(VARROCK, 5, true);
        f.places.firstPaint(first.getScenarioId(), f.data.group(first.getScenarioId()).lines.get(0).id, f.tick);
        f.places.resetSession();
        assertNull(f.stay(VARROCK, 5, true));
        assertNotNull(f.stay(EXCHANGE, 5, true));
    }

    @Test public void onlyCurrentSessionPlaceFirstPaintReceiptsStartThePlaceCooldown() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        SceneCandidate first = f.stay(VARROCK, 5, true);
        SpeechPacket.PaintReceipt receipt = paint(f.data, first);
        f.places.onPaint(receipt);
        assertNull(f.stay(EXCHANGE, 5, true));
        f.generation++; assertNull(f.stay(VARROCK, 5, true));
        f.places.onPaint(receipt);
        assertNotNull(f.stay(EXCHANGE, 5, true));
    }

    @Test public void sceneryOrUnknownLineFirstPaintCannotStartThePlaceCooldown() throws Exception
    {
        Fixture f = new Fixture(); f.baseline();
        LivingWorldData.SceneryGroup scenery = f.data.scenery("Bookcase");
        f.places.firstPaint(scenery.id, scenery.lines.get(0).id, f.tick);
        f.places.firstPaint("PL-LUMBRIDGE", "not-an-authored-line", f.tick);
        assertNotNull(f.stay(VARROCK, 5, true));
    }

    @Test public void arrivalCausalIdentityDoesNotDependOnTheCandidateSequence() throws Exception
    {
        Fixture a = new Fixture(), b = new Fixture(); a.baseline(); b.baseline(); b.ids.set(100);
        SceneCandidate first = a.stay(VARROCK, 5, true), second = b.stay(VARROCK, 5, true);
        assertNotEquals(first.getId(), second.getId()); assertEquals(first.getCausalToken(), second.getCausalToken());
    }

    private static SpeechPacket.PaintReceipt paint(LivingWorldData data, SceneCandidate candidate) throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(LivingWorldPlacesTest.class).withLivingWorld(data);
        SpeechGuard guard = new SpeechGuard(() -> 0, new Random(1), catalogue.getMaximumTextKeys(),
            catalogue.getMaximumInteractionKeys());
        SpeechDirector director = new SpeechDirector(catalogue, () -> 0, new Random(1), message -> {}, guard);
        director.tick(candidate.getCreatedTick(), candidate.getSessionGeneration(), candidate.getSceneRevision(),
            true, true, false);
        assertTrue(director.offer(candidate));
        director.arrive(candidate.getId(), candidate.getCreatedTick());
        SpeechPacket packet = director.getPacket(); assertNotNull(packet);
        SpeechPacket.PaintReceipt receipt = packet.paint(0, candidate.getCreatedTick(), 1, () -> {});
        assertNotNull(receipt); return receipt;
    }

    private static final class Fixture
    {
        final LivingWorldData data = LivingWorldData.load(LivingWorldPlacesTest.class);
        final Draws random = new Draws();
        final AtomicLong ids = new AtomicLong();
        final LivingWorldPlaces places = new LivingWorldPlaces(data, random, ids::incrementAndGet);
        long tick, generation = 1, revision = 1;
        Fixture() throws Exception { }
        void baseline() { assertNull(stay(LUMBRIDGE, 5, true)); }
        SceneCandidate observe(int region, boolean eligible) { return raw(region, true, true, eligible); }
        SceneCandidate raw(int region, boolean committed, boolean present, boolean eligible)
        {
            return places.observe(++tick, generation, revision, region, committed, present, eligible);
        }
        SceneCandidate stay(int region, int count, boolean eligible)
        {
            SceneCandidate last = null;
            for (int i = 0; i < count; i++)
            {
                SceneCandidate next = observe(region, eligible);
                if (i < count - 1) { assertNull("Arrival before end of fixture window", next); }
                last = next;
            }
            return last;
        }
    }

    private static final class Draws extends Random
    {
        final ArrayDeque<Double> values = new ArrayDeque<>();
        int draws;
        @Override public double nextDouble() { draws++; return values.isEmpty() ? 0 : values.removeFirst(); }
    }
}
