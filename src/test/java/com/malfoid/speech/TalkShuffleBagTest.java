package com.malfoid.speech;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class TalkShuffleBagTest
{
    @Test
    public void everyGenericLinePaintsOncePerBagAndForcedBoundaryRepeatIsAvoided() throws Exception
    {
        Fixture f = new Fixture(new BoundaryRandom());
        String last = null;
        for (int bag = 0; bag < 2; bag++)
        {
            Set<String> painted = new HashSet<>();
            for (int index = 0; index < 6; index++)
            {
                SpeechPacket packet = f.request(1);
                assertNotNull(packet);
                if (index == 0 && last != null) { assertNotEquals(last, packet.getLineId()); }
                assertTrue("Repeated before exhaustion: " + packet.getLineId(), painted.add(packet.getLineId()));
                f.paint(packet);
                last = packet.getLineId();
            }
            assertEquals(6, painted.size());
        }
    }

    @Test
    public void hundredLinePoolDoesNotRepeatAfterOnlyFiveRecentPaints() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom(), 100);
        Set<String> painted = new HashSet<>();
        String last = null;
        for (int index = 0; index < 100; index++)
        {
            SpeechPacket packet = f.request(1);
            assertNotNull(packet);
            assertTrue(packet.getLineId(), painted.add(packet.getLineId()));
            f.paint(packet);
            last = packet.getLineId();
        }
        SpeechPacket next = f.request(1);
        assertNotNull(next);
        assertTrue(painted.contains(next.getLineId()));
        assertNotEquals(last, next.getLineId());
    }

    @Test
    public void textCooldownExpiryDoesNotCreateAnotherBagOrRepeatUsedLines() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        f.clockStep = 0;
        String cooling = f.catalogue.getLine("U01-02").getNormalizedText();
        assertTrue(f.guard.settlePaint(f.guard.reserve(null, cooling, null), 0));
        Set<String> painted = new HashSet<>();
        SpeechPacket first = f.request(1);
        assertNotNull(first);
        assertNotEquals("U01-02", first.getLineId());
        assertTrue(painted.add(first.getLineId()));
        f.paint(first);
        f.clockStep = 61_000_000_000L;
        for (int index = 1; index < 6; index++)
        {
            SpeechPacket packet = f.request(1);
            assertNotNull(packet);
            assertTrue("Cooldown expiry repeated a used line", painted.add(packet.getLineId()));
            f.paint(packet);
        }
        assertEquals(6, painted.size());
        assertTrue(painted.contains("U01-02"));
    }

    @Test
    public void unpaintedCancellationAndFailedDrawNeitherConsumeNorReshuffle() throws Exception
    {
        ZeroRandom choices = new ZeroRandom();
        Fixture f = new Fixture(choices);
        SpeechPacket cancelled = f.request(1);
        String unused = cancelled.getLineId();
        int draws = choices.draws;
        f.director.invalidate(true);
        assertNull(cancelled.paint(f.clock.get(), f.tick, 1, () -> fail("Cancelled packet painted")));
        SpeechPacket failed = f.request(1);
        assertEquals(unused, failed.getLineId());
        try
        {
            failed.paint(f.clock.get(), f.tick, 2, () -> { throw new IllegalStateException("draw failed"); });
            fail("Expected draw failure");
        }
        catch (IllegalStateException expected) { }
        SpeechPacket replay = f.request(1);
        assertEquals(unused, replay.getLineId());
        assertEquals(draws, choices.draws);
        assertEquals(0, f.guard.textCount());
        f.paint(replay);
        assertNotEquals(unused, f.request(1).getLineId());
    }

    @Test
    public void muteAndTurnAwayDoNotUseUnpaintedTalkLines() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        SpeechPacket unused = f.request(1);
        f.director.tick(f.tick + 1, f.generation, f.revision, true, false, false);
        assertNull(f.director.getPacket());
        assertNull(f.request(4));
        SpeechPacket replay = f.request(1);
        assertEquals(unused.getLineId(), replay.getLineId());
        assertEquals(0, f.guard.textCount());
        f.paint(replay);
        assertNotEquals(replay.getLineId(), f.request(1).getLineId());
    }

    @Test
    public void conditionPrecedenceAndIndependentPoolProgressSurviveSwitching() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        assertEquals("U01-02", f.say(1));
        assertEquals("U01-17", f.say(1, "localNight", "true"));
        assertEquals("U01-15", f.say(1, "combatConfirmed", "true", "playerHpAtMostPercent", "10", "localNight", "true"));
        assertEquals("U01-13", f.say(1, "playerHpAtMostPercent", "10", "localNight", "true"));
        assertEquals("U01-03", f.say(1));
        assertEquals("U01-16", f.say(1, "localNight", "true"));
        assertEquals("U01-14", f.say(1, "combatConfirmed", "true"));
        assertEquals("U01-12", f.say(1, "playerHpAtMostPercent", "10"));
        assertEquals("U01-08", f.say(2));
        assertEquals("U01-11", f.say(3));
        assertEquals("U01-09", f.say(2));
        assertEquals("U01-10", f.say(3));
        assertEquals("U01-07", f.say(2));
    }

    @Test
    public void coolingConditionalPoolFallsBackOnlyToGenericFirstAnswers() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        f.clockStep = 0;
        for (String id : new String[] {"U01-14", "U01-15"})
        {
            assertTrue(f.guard.settlePaint(f.guard.reserve(null, f.catalogue.getLine(id).getNormalizedText(), null), 0));
        }
        assertEquals("U01-02", f.say(1, "combatConfirmed", "true", "playerHpAtMostPercent", "10", "localNight", "true"));
        assertEquals("U01-03", f.say(1, "combatConfirmed", "true", "localNight", "true"));
        assertEquals("U01-04", f.say(1, "combatConfirmed", "true"));
    }

    @Test
    public void usedEligibleLineCannotBypassAnUnusedButTemporarilyInvalidLine() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        String used = f.say(1);
        f.director.setLineValidator((candidate, lineId) -> used.equals(lineId));
        assertNull(f.request(1));
        assertTrue(f.director.isIntentionalSilent());
        f.director.setLineValidator((candidate, lineId) -> true);
        SpeechPacket next = f.request(1);
        assertNotNull(next);
        assertNotEquals(used, next.getLineId());
    }

    @Test
    public void newBagCannotStartWithItsPriorLastLineWhenOnlyThatLinePassesAProofGuard() throws Exception
    {
        Fixture f = new Fixture(new BoundaryRandom());
        String last = null;
        for (int index = 0; index < 6; index++) { last = f.say(1); }
        String blockedBoundary = last;
        f.director.setLineValidator((candidate, lineId) -> blockedBoundary.equals(lineId));
        assertNull(f.request(1));
        f.director.setLineValidator((candidate, lineId) -> true);
        SpeechPacket next = f.request(1);
        assertNotNull(next);
        assertNotEquals(last, next.getLineId());
    }

    @Test
    public void absenceSceneChangesAndLoadingKeepBagsButNewSessionsResetThem() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        assertEquals("U01-02", f.say(1));
        f.director.tick(++f.tick, f.generation, f.revision, false, true, false);
        f.revision++;
        assertEquals("U01-03", f.say(1));
        SpeechPacket unpainted = f.request(1);
        f.director.suspendForOrdinaryLoading(f.tick, f.generation);
        f.revision++;
        SpeechPacket resumed = f.request(1);
        assertNotNull(resumed);
        assertEquals(unpainted.getLineId(), resumed.getLineId());
        f.paint(resumed);
        f.generation++;
        assertEquals("U01-02", f.say(1));
    }

    @Test
    public void newSessionResetsBagHistoryWhileProcessTextCooldownStillApplies() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        f.clockStep = 0;
        assertEquals("U01-02", f.say(1));
        f.generation++;
        SpeechPacket next = f.request(1);
        assertNotNull(next);
        assertNotEquals("U01-02", next.getLineId());
        assertFalse(f.guard.textEligible(f.catalogue.getLine("U01-02").getNormalizedText()));
        assertEquals(0, f.guard.interactionCount());
    }

    @Test
    public void delayedRealPaintConsumesOnceBeforeCancellationAndOldReceiptCannotConsumeTheNextChoice() throws Exception
    {
        Fixture f = new Fixture(new ZeroRandom());
        SpeechPacket first = f.request(1);
        SpeechPacket.PaintReceipt receipt = first.paint(f.clock.get(), f.tick, 1, () -> {});
        assertNotNull(receipt);
        f.director.invalidate(true);
        SpeechPacket second = f.request(1);
        assertNotEquals(first.getLineId(), second.getLineId());
        f.director.acknowledge(first, receipt);
        String stillUnused = second.getLineId();
        SpeechPacket replay = f.request(1);
        assertEquals(stillUnused, replay.getLineId());
        f.paint(replay);
        assertNotEquals(stillUnused, f.request(1).getLineId());
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final DialogueCatalog catalogue;
        final SpeechGuard guard;
        final SpeechDirector director;
        long clockStep = 61_000_000_000L;
        long tick;
        long generation = 1;
        long revision = 1;
        long id;

        Fixture(Random choices) throws Exception { this(choices, 0); }

        Fixture(Random choices, int testLineCount) throws Exception
        {
            {
                try (InputStream input = getClass().getResourceAsStream("/com/malfoid/dialogue.json"))
                {
                    JsonObject runtime = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement element : runtime.getAsJsonArray("entries"))
                    {
                        JsonObject line = element.getAsJsonObject();
                        if ("U01".equals(line.get("scenario").getAsString())
                            && (testLineCount > 0 || !line.get("id").getAsString().matches("U01-[0-9]{2}")))
                        {
                            line.addProperty("status", "disabled");
                        }
                    }
                    for (int index = 0; index < testLineCount; index++)
                    {
                        JsonObject line = new JsonObject();
                        line.addProperty("id", String.format("U01-TEST-%03d", index));
                        line.addProperty("scenario", "U01");
                        line.addProperty("status", "enabled");
                        line.addProperty("text", "Test talk line " + index + ".");
                        line.addProperty("emotion", "QUIET");
                        line.addProperty("presentation", "OVERHEAD");
                        JsonObject conditions = new JsonObject();
                        conditions.addProperty("talkStreak", 1);
                        line.add("conditions", conditions);
                        runtime.getAsJsonArray("entries").add(line);
                    }
                    catalogue = DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
            guard = new SpeechGuard(clock::get, new ZeroRandom(), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            director = new SpeechDirector(catalogue, clock::get, choices, new Random(0), ignored -> {}, guard);
        }

        SpeechPacket request(int streak, String... pairs)
        {
            director.invalidate(true);
            clock.addAndGet(clockStep);
            tick += 3;
            director.tick(tick, generation, revision, true, true, false);
            Map<String, String> facts = new HashMap<>();
            facts.put("talkStreak", Integer.toString(streak));
            for (int index = 0; index < pairs.length; index += 2) { facts.put(pairs[index], pairs[index + 1]); }
            SceneCandidate candidate = new SceneCandidate(++id, "U01", 85, tick, tick + 8,
                generation, revision, "talk:" + id, facts, streak >= 4, streak >= 4 ? 5 : 4);
            assertTrue(director.offer(candidate));
            director.arrive(candidate.getId(), tick);
            return director.getPacket();
        }

        String say(int streak, String... pairs)
        {
            SpeechPacket packet = request(streak, pairs);
            assertNotNull(packet);
            paint(packet);
            return packet.getLineId();
        }

        void paint(SpeechPacket packet)
        {
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), tick, id, () -> {});
            assertNotNull(receipt);
            director.acknowledge(packet, receipt);
            director.acknowledge(packet, receipt);
        }
    }

    private static final class ZeroRandom extends Random
    {
        int draws;
        @Override public int nextInt(int bound) { draws++; return 0; }
    }

    private static final class BoundaryRandom extends Random
    {
        int draws;
        @Override public int nextInt(int bound)
        {
            draws++;
            return draws == 6 ? 0 : bound - 1;
        }
    }
}
