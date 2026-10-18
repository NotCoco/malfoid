package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerDialoguePickerTest
{
    @Test
    public void m09HalfBucketIsUniformInsideHmphAndOtherPools() throws Exception
    {
        Map<String, Integer> counts = new HashMap<>();
        for (int sample = 0; sample < 8; sample++)
        {
            int bucket = sample < 4 ? 0 : 1;
            Fixture f = new Fixture(bucket, bucket == 0 ? sample % 2 : sample - 4);
            SpeechPacket packet = f.acquire(outfit(1, 1, "1,2,3,4"));
            counts.merge(packet.getLineId(), 1, Integer::sum);
            assertEquals(2, f.choices.draws);
            assertEquals(2, (int) f.choices.bounds.getFirst());
            assertEquals(bucket == 0 ? 2 : 4, (int) f.choices.bounds.getLast());
        }
        assertEquals(Integer.valueOf(2), counts.get("M09-H01"));
        assertEquals(Integer.valueOf(2), counts.get("M09-H02"));
        for (String id : new String[] {"M09-05", "M09-T01", "M09-T02", "M09-T03"})
        {
            assertEquals(id, Integer.valueOf(1), counts.get(id));
        }
    }

    @Test
    public void f03HalfOwnerBucketAndFiveUniformAlternativesDoNotRerollProducerSilence() throws Exception
    {
        Map<String, Integer> counts = new HashMap<>();
        for (int sample = 0; sample < 10; sample++)
        {
            int bucket = sample < 5 ? 0 : 1;
            Fixture f = new Fixture(bucket, bucket == 0 ? 0 : sample - 5);
            SpeechPacket packet = f.acquire(run(false));
            counts.merge(packet.getLineId(), 1, Integer::sum);
            assertEquals(2, f.choices.draws);
            assertEquals(2, (int) f.choices.bounds.getFirst());
            assertEquals(bucket == 0 ? 1 : 5, (int) f.choices.bounds.getLast());
        }
        assertEquals(Integer.valueOf(5), counts.get("F03-01"));
        for (int index = 1; index <= 5; index++)
        {
            assertEquals(Integer.valueOf(1), counts.get(String.format("F03-G%02d", index)));
        }
        Fixture silent = new Fixture();
        silent.director.tick(50, 1, 1, true, true, false);
        assertTrue(silent.director.offer(run(true)));
        silent.director.arrive(1, 50);
        assertNull(silent.director.getPacket());
        assertEquals(0, silent.choices.draws);
    }

    @Test
    public void guardEmptySelectedBucketIsQuietWithoutTryingTheOtherBucket() throws Exception
    {
        for (String scenario : new String[] {"M09", "F03"})
        {
            Fixture f = new Fixture(0);
            String[] ownerIds = "M09".equals(scenario) ? new String[] {"M09-H01", "M09-H02"} : new String[] {"F03-01"};
            for (String id : ownerIds)
            {
                assertTrue(f.guard.settlePaint(f.guard.reserve(null, f.catalogue.getLine(id).getNormalizedText(), null), 0));
            }
            SceneCandidate candidate = "M09".equals(scenario) ? outfit(1, 1, "1,2,3,4") : run(false);
            f.director.tick(candidate.getCreatedTick(), 1, 1, true, true, false);
            assertTrue(f.director.offer(candidate));
            assertTrue(f.director.isIntentionalSilent());
            f.director.arrive(1, candidate.getCreatedTick());
            assertNull(f.director.getPacket());
            assertEquals(1, f.choices.draws);
            assertEquals(2, (int) f.choices.bounds.getFirst());
        }
    }

    @Test
    public void recentOwnerPaintDoesNotChangeBucketOddsAfterGuardsExpire() throws Exception
    {
        Fixture f = new Fixture(0, 0, 0, 0);
        SpeechPacket first = f.acquire(outfit(1, 1, "1,2,3,4"));
        assertEquals("M09-H01", first.getLineId());
        f.paint(first, 1);
        assertFalse(f.guard.textEligible("hmph."));
        assertFalse(f.guard.interactionEligible("M09"));
        f.director.invalidate(true);
        f.clock.set(61_000_000_000L);
        SpeechPacket second = f.acquire(outfit(2, 60, "5,6,7,8"));
        assertEquals("M09-H01", second.getLineId());
        assertEquals(4, f.choices.draws);
        assertEquals(1, f.guard.situationCount());
    }

    @Test
    public void interactionAndSituationGuardsApplyBeforeEitherBucketDraw() throws Exception
    {
        Fixture repeat = new Fixture();
        assertTrue(repeat.guard.settleSilentArrival(repeat.guard.reserve("M09", null, null)));
        repeat.director.tick(1, 1, 1, true, true, false);
        assertFalse(repeat.director.offer(outfit(1, 1, "1,2,3,4")));
        assertEquals(0, repeat.choices.draws);
        Fixture seen = new Fixture();
        SceneCandidate outfit = outfit(1, 1, "1,2,3,4");
        assertTrue(seen.guard.settlePaint(seen.guard.reserve(null, null, seen.catalogue.situationFingerprint(outfit)), 0));
        seen.director.tick(1, 1, 1, true, true, false);
        assertTrue(seen.director.offer(outfit));
        assertTrue(seen.director.isIntentionalSilent());
        assertEquals(0, seen.choices.draws);
    }

    @Test
    public void capabilityLossBlocksPaintWithoutRedrawingSelectedBucket() throws Exception
    {
        Fixture f = new Fixture(0, 0);
        f.director.setLineValidator((candidate, lineId) -> "M09-H02".equals(lineId));
        SpeechPacket packet = f.acquire(outfit(1, 1, "1,2,3,4"));
        assertEquals("M09-H02", packet.getLineId());
        assertEquals(1, (int) f.choices.bounds.getLast());
        f.director.setLineValidator((candidate, lineId) -> false);
        f.director.refreshPresentation(false);
        assertNull(packet.paint(0, 1, 1, () -> fail("Invalid line painted")));
        assertEquals(2, f.choices.draws);
    }

    @Test
    public void ordinaryNumberedReactionStillUsesRecentFivePreference() throws Exception
    {
        Fixture f = new Fixture(0, 0);
        SpeechPacket first = f.acquire(level(1, 1));
        assertEquals("M04-01", first.getLineId());
        assertEquals(4, (int) f.choices.bounds.getLast());
        f.paint(first, 1);
        f.director.invalidate(true);
        f.clock.set(61_000_000_000L);
        SpeechPacket second = f.acquire(level(2, 60));
        assertEquals("M04-02", second.getLineId());
        assertEquals(3, (int) f.choices.bounds.getLast());
    }

    private static SceneCandidate outfit(long id, long tick, String equipment)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("equipmentIds", equipment);
        return new SceneCandidate(id, "M09", 25, tick, tick + 12, 1, 1, "outfit:" + id, facts, false, 8);
    }

    private static SceneCandidate run(boolean silent)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("runWindowStartTick", "1");
        facts.put("runWindowTiles", "60");
        facts.put("runWorldViewId", "-1");
        facts.put("witnessPresenceEpoch", "1");
        return new SceneCandidate(1, "F03", 30, 50, 62, 1, 1, "run:1", facts, silent, 4);
    }

    private static SceneCandidate level(long id, long tick)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("newRealLevel", "99");
        return new SceneCandidate(id, "M04", 45, tick, tick + 12, 1, 1, "level:" + id, facts, false, 4);
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final DialogueCatalog catalogue;
        final Draws choices;
        final SpeechGuard guard;
        final SpeechDirector director;
        Fixture(Integer... samples) throws Exception
        {
            catalogue = DialogueCatalog.load(getClass());
            choices = new Draws(samples);
            guard = new SpeechGuard(clock::get, new Random(0), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            director = new SpeechDirector(catalogue, clock::get, choices, new Random(0), ignored -> {}, guard);
        }
        SpeechPacket acquire(SceneCandidate candidate)
        {
            director.tick(candidate.getCreatedTick(), 1, 1, true, true, false);
            assertTrue(director.offer(candidate));
            assertFalse(director.isIntentionalSilent());
            director.arrive(candidate.getId(), candidate.getCreatedTick());
            assertNotNull(director.getPacket());
            return director.getPacket();
        }
        void paint(SpeechPacket packet, long tick)
        {
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), tick, tick, () -> {});
            assertNotNull(receipt);
            director.acknowledge(packet, receipt);
        }
    }

    private static final class Draws extends Random
    {
        final Deque<Integer> samples;
        final Deque<Integer> bounds = new ArrayDeque<>();
        int draws;
        Draws(Integer... samples) { this.samples = new ArrayDeque<>(Arrays.asList(samples)); }
        @Override public int nextInt(int bound)
        {
            draws++;
            bounds.add(bound);
            assertFalse("Unexpected random draw", samples.isEmpty());
            int sample = samples.removeFirst();
            assertTrue(sample >= 0 && sample < bound);
            return sample;
        }
    }
}
