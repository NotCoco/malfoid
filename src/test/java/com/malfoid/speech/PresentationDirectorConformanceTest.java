package com.malfoid.speech;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.scenario.ScenarioBatch;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.scenario.ScenarioSnapshot;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.*;

/** Conformance on existing presentations, original deadlines and unmodified dialogue. */
public class PresentationDirectorConformanceTest
{
    @Test
    public void issuedUnpaintedExpiryRevokesBeforeDrawAndRetiresWithoutSpeechOrSilenceDebit() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        SpeechPacket issued = f.issue(original);
        assertTrue(f.guard.hasPendingReservation());
        assertFalse(f.director.isCommitted());
        f.tick(19);
        assertSame(issued, f.director.getPacket());

        f.tick(original.getExpiresTick());
        assertTrue(f.director.isIdle());
        assertFalse(f.guard.hasPendingReservation());
        assertFalse(issued.isContinuationAllowed());
        assertNull(issued.paint(0, 20, 2, () -> fail("Expired capability drew")));
        assertNull(issued.getFirstPaint());
        assertNull(f.director.getLastPaint());
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.guard.textCount());
        assertEquals(0, f.guard.interactionCount());
        assertEquals(0, f.guard.situationCount());
        assertEquals(0, f.silentArrivals.get());
        assertEquals(0, f.firstPaints.get());
        assertEquals(0, f.completed.get());
        assertEquals(1, f.terminalDisposals("M04"));
        assertEquals(1, f.choices.draws);
        f.tick(21);
        f.tick(21);
        assertEquals(1, f.terminalDisposals("M04"));
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void originalExpiredCauseCannotReturnAndOnlyAFreshFactPassesExactFiveTickRetry() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        f.issue(original);
        f.tick(20);
        int choiceDraws = f.choices.draws;
        assertFalse(f.director.offer(original));
        f.tick(24);
        assertFalse(f.director.offer(level(2, 24, false)));
        assertEquals(choiceDraws, f.choices.draws);
        assertFalse(f.guard.hasPendingReservation());
        f.tick(25);
        SceneCandidate fresh = level(3, 25, false);
        assertTrue(f.director.offer(fresh));
        assertSame(fresh, f.director.getActiveCandidate());
        assertEquals(35, fresh.getExpiresTick());
        assertEquals(20, original.getExpiresTick());
        assertNotEquals(original.getCausalToken(), fresh.getCausalToken());
        assertEquals(choiceDraws + 1, f.choices.draws);
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void busyAfterIssueDefersOnlyInsideOriginalDeadline() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        SpeechPacket issued = f.issue(original);
        f.director.refreshPresentation(true);
        assertNull(issued.paint(0, 10, 2, () -> fail("Busy packet drew")));
        assertSame(original, f.director.getActiveCandidate());
        assertEquals(20, original.getExpiresTick());
        f.tick(20, true);
        f.director.refreshPresentation(false);
        assertNull(issued.paint(0, 20, 3, () -> fail("Busy deferral renewed expiry")));
        assertTrue(f.director.isIdle());
        assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.silentArrivals.get());
    }

    @Test
    public void genuinePreExpiryPaintSettlesAtDeadlineEvenWithoutQueuedAcknowledgement() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        SpeechPacket issued = f.issue(original);
        f.tick(19);
        AtomicInteger draws = new AtomicInteger();
        SpeechPacket.PaintReceipt receipt = issued.paint(0, 19, 1, draws::incrementAndGet);
        assertNotNull(receipt);
        assertTrue(f.director.isCommitted());
        assertEquals(0, f.cooldowns.draws);
        f.tick(20);
        assertSame(receipt, f.director.getLastPaint());
        assertEquals(1, draws.get());
        assertEquals(1, f.firstPaints.get());
        assertEquals(1, f.cooldowns.draws);
        assertEquals(1, f.guard.textCount());
        assertEquals(1, f.guard.interactionCount());
        assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.terminalDisposals("M04"));
        assertFalse(issued.isContinuationAllowed());
        assertTrue(issued.canPaint(0)); // Readable original text survives its first-paint deadline.
        f.director.acknowledge(issued, receipt);
        f.director.acknowledge(issued, receipt);
        assertEquals(1, f.cooldowns.draws);
        assertEquals(1, f.firstPaints.get());
        assertEquals(0, f.silentArrivals.get());
    }

    @Test
    public void validIntentionalSilenceCommitsOnlyItsKeyAndCompletesAsItsOwnOutcome() throws Exception
    {
        Fixture f = new Fixture();
        f.tick(10);
        SceneCandidate original = level(1, 10, true);
        assertTrue(f.director.offer(original));
        f.director.arrive(original.getId(), 10);
        assertTrue(f.director.isCommitted());
        assertNull(f.director.getPacket());
        assertEquals(18, f.director.getHoldUntilTick());
        assertEquals(1, f.cooldowns.draws);
        assertEquals(1, f.guard.interactionCount());
        assertEquals(0, f.guard.textCount());
        assertEquals(0, f.guard.situationCount());
        assertEquals(1, f.silentArrivals.get());
        assertEquals(0, f.firstPaints.get());
        f.tick(18);
        f.tick(20);
        assertTrue(f.director.isIdle());
        assertEquals(1, f.completed.get());
        assertEquals(0, f.terminalDisposals("M04"));
        assertEquals(0, f.choices.draws);
    }

    @Test
    public void higherPriorityPreemptionIsNotExpiryAndDoesNotInstallTheExpiryRetry() throws Exception
    {
        Fixture f = new Fixture();
        SpeechPacket old = f.issue(level(1, 10, false));
        f.tick(11);
        SceneCandidate emergency = new SceneCandidate(2, "M01", 100, 11, 23, 1, 1,
            "hit:2", new HashMap<>(), false, 20);
        assertTrue(f.director.offer(emergency));
        assertSame(emergency, f.director.getActiveCandidate());
        assertNull(old.paint(0, 11, 2, () -> fail("Preempted packet drew")));
        assertEquals(1, f.terminalDisposals("M04"));
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.silentArrivals.get());
        f.director.invalidate(true);
        assertTrue(f.director.offer(level(3, 11, false)));
        assertEquals("M04", f.director.getActiveCandidate().getScenarioId());
    }

    @Test
    public void endedSessionDisposalCannotEmitCurrentSessionCompletionOrPaint() throws Exception
    {
        Fixture f = new Fixture();
        SpeechPacket old = f.issue(level(1, 10, false));
        f.director.tick(11, 2, 1, true, true, false);
        assertNull(old.paint(0, 11, 2, () -> fail("Ended session packet drew")));
        assertTrue(f.director.isIdle());
        assertEquals(2, f.director.getSessionGeneration());
        assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.firstPaints.get());
        assertEquals(0, f.silentArrivals.get());
        assertEquals(0, f.completed.get());
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void cameraHidePreservesActualLifetimeAndCannotAddAQuietFinish() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        SpeechPacket issued = f.issue(original);
        SpeechPacket.PaintReceipt receipt = f.paintAndAcknowledge(issued, 10);
        long lifetimeEnd = issued.getObservation().getLifetimeEndNanos();
        assertEquals(expectedLifetimeNanos(issued.getText()), lifetimeEnd);
        assertEquals(18, f.director.getHoldUntilTick());
        int choices = f.choices.draws;
        issued.markUndrawable(2);
        f.clock.set(1_000_000_000L);
        f.tick(11);
        assertFalse(issued.getObservation().isDrawable());
        assertEquals(lifetimeEnd, issued.getObservation().getLifetimeEndNanos());
        assertFalse(issued.isFinished(f.clock.get()));
        assertSame(original, f.director.getActiveCandidate());
        assertEquals(0, f.completed.get());
        assertEquals(18, f.director.getHoldUntilTick());
        f.director.acknowledge(issued, receipt);
        assertEquals(1, f.firstPaints.get());
        assertEquals(1, f.cooldowns.draws);
        assertEquals(choices, f.choices.draws);
        assertEquals(0, f.sceneChoices.draws);
    }

    @Test
    public void shorterAuthoredHoldDoesNotReceiveFourMoreTicksAtTextLifetimeEnd() throws Exception
    {
        Fixture f = new Fixture();
        SceneCandidate original = level(1, 10, false);
        SpeechPacket issued = f.issue(original);
        SpeechPacket.PaintReceipt receipt = f.paintAndAcknowledge(issued, 10);
        long lifetimeEnd = issued.getObservation().getLifetimeEndNanos();
        assertTrue(lifetimeEnd > 8 * 600_000_000L); // Actual M04 pool, unchanged authored eight-tick hold.
        f.clock.set(lifetimeEnd - 1);
        f.tick(18);
        assertEquals(0, f.completed.get());
        assertEquals(18, f.director.getHoldUntilTick());
        f.clock.set(lifetimeEnd);
        f.tick(20);
        assertEquals(1, f.completed.get());
        assertTrue(f.director.isIdle());
        assertNull(f.director.getPacket());
        int choices = f.choices.draws;
        f.director.acknowledge(issued, receipt);
        f.director.acknowledge(issued, receipt);
        f.tick(24);
        assertTrue(f.director.isIdle());
        assertEquals(1, f.completed.get());
        assertEquals(1, f.firstPaints.get());
        assertEquals(1, f.cooldowns.draws);
        assertEquals(choices, f.choices.draws);
        assertEquals(0, f.sceneChoices.draws);
        assertEquals(0, f.silentArrivals.get());
        assertEquals(0, f.director.pendingCount());
    }

    @Test
    public void newShortOutfitLinesStillCannotFitFourQuietTicksInTheRealProducerHold() throws Exception
    {
        SceneCandidate outfit = producedOutfit();
        assertEquals("M09", outfit.getScenarioId());
        assertEquals(6, outfit.getHoldTicks());
        Set<String> newShortLines = new HashSet<>();
        JsonObject runtime;
        try (InputStream input = getClass().getResourceAsStream("/com/malfoid/dialogue.json"))
        {
            assertNotNull(input);
            runtime = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int checked = 0;
        for (JsonElement element : runtime.getAsJsonArray("entries"))
        {
            JsonObject line = element.getAsJsonObject();
            String scenario = line.get("scenario").getAsString();
            if (!scenario.startsWith("M") || !"enabled".equals(line.get("status").getAsString())
                || "M01".equals(scenario) || "M02".equals(scenario) || "M05".equals(scenario)
                || "M07".equals(scenario)) { continue; }
            int index = Integer.parseInt(scenario.substring(1));
            // Upper bounds of existing producers, not new configurable hold allowances.
            int maximumExistingHold = "M06".equals(scenario) || "M70".equals(scenario) ? 12 : index <= 70 ? 10 : 4;
            long required = expectedLifetimeNanos(line.get("text").getAsString()) + 4 * 600_000_000L;
            if (Arrays.asList("M09", "M32", "M33", "M34").contains(scenario))
            {
                if (required <= maximumExistingHold * 600_000_000L) { newShortLines.add(line.get("id").getAsString()); }
                // These are specializations of the same production outfit candidate, with its six-tick hold.
                maximumExistingHold = outfit.getHoldTicks();
            }
            assertTrue("Review M68's closed gate if " + line.get("id").getAsString() + " gains an eligible budget",
                required > maximumExistingHold * 600_000_000L);
            checked++;
        }
        assertTrue(checked > 0);
        assertEquals(new HashSet<>(Arrays.asList("M09-H01", "M09-H02", "M09-T01", "M09-T02")), newShortLines);
    }

    @Test
    public void genuinelyPaintedHmphFinishesItsOriginalQuietTailOnceWithoutAddingM68() throws Exception
    {
        for (String lineId : new String[] {"M09-H01", "M09-H02"})
        {
            Fixture f = new Fixture();
            SceneCandidate original = producedOutfit();
            f.director.setLineValidator((candidate, id) -> lineId.equals(id));
            SpeechPacket issued = f.issue(original);
            assertEquals(lineId, issued.getLineId());
            SpeechPacket.PaintReceipt receipt = f.paintAndAcknowledge(issued, original.getCreatedTick());
            long lifetimeEnd = issued.getObservation().getLifetimeEndNanos();
            long holdEndTick = original.getCreatedTick() + original.getHoldTicks();
            long holdBudgetNanos = original.getHoldTicks() * 600_000_000L;
            assertEquals(3_500_000_000L, lifetimeEnd);
            assertEquals(100_000_000L, holdBudgetNanos - lifetimeEnd);
            assertTrue(lifetimeEnd + 4 * 600_000_000L > holdBudgetNanos);
            assertEquals(holdEndTick, f.director.getHoldUntilTick());
            f.clock.set(lifetimeEnd - 1);
            f.tick(holdEndTick - 1);
            assertTrue(issued.canPaint(f.clock.get()));
            assertEquals(0, f.completed.get());
            f.clock.set(lifetimeEnd);
            f.tick(holdEndTick - 1);
            assertTrue(issued.isFinished(f.clock.get()));
            assertFalse(issued.canPaint(f.clock.get()));
            assertSame(original, f.director.getActiveCandidate());
            assertEquals(holdEndTick, f.director.getHoldUntilTick());
            assertEquals(0, f.completed.get());
            int choices = f.choices.draws;
            f.clock.set(holdBudgetNanos);
            f.tick(holdEndTick);
            assertTrue(f.director.isIdle());
            assertNull(f.director.getPacket());
            f.director.acknowledge(issued, receipt);
            f.director.acknowledge(issued, receipt);
            f.clock.addAndGet(4 * 600_000_000L);
            f.tick(holdEndTick + 4);
            assertEquals(1, f.completed.get());
            assertEquals(1, f.firstPaints.get());
            assertEquals(1, f.cooldowns.draws);
            assertEquals(1, f.guard.textCount());
            assertEquals(1, f.guard.interactionCount());
            assertFalse(f.guard.interactionEligible("M09"));
            assertNull(f.catalogue.repeatKey("M68"));
            assertFalse(f.catalogue.isRuntimeScenarioEnabled("M68"));
            assertEquals(choices, f.choices.draws);
            assertEquals(0, f.sceneChoices.draws);
            assertEquals(0, f.silentArrivals.get());
            assertEquals(0, f.director.pendingCount());
            assertFalse(f.trace.stream().anyMatch(value -> value.contains("M68")));
        }
    }

    @Test
    public void conformanceIdsHaveNoRuntimeKeyOfferOrRandomDraw() throws Exception
    {
        Fixture f = new Fixture();
        f.tick(10);
        assertNull(f.catalogue.repeatKey("M67"));
        assertNull(f.catalogue.repeatKey("M68"));
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M67"));
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M68"));
        // No M67/M68 candidate is constructed: the assertions belong to the original execution.
        assertTrue(f.director.isIdle());
        assertEquals(0, f.choices.draws);
        assertEquals(0, f.sceneChoices.draws);
        assertEquals(0, f.cooldowns.draws);
        assertFalse(f.guard.hasPendingReservation());
    }

    private static long expectedLifetimeNanos(String text)
    {
        int points = text.codePointCount(0, text.length());
        return (Math.max(3_000, Math.min(7_000, 2_000 + 60 * points)) + 500) * 1_000_000L;
    }

    private static SceneCandidate producedOutfit()
    {
        ScenarioRules rules = new ScenarioRules();
        ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
        frame.xp = new int[Skill.values().length]; Arrays.fill(frame.xp, 100);
        frame.levels = new int[Skill.values().length]; Arrays.fill(frame.levels, 10);
        frame.boosted = frame.levels.clone();
        frame.equipment = new int[] {100, 200, 300, 400};
        frame.inventoryIds = new int[28]; Arrays.fill(frame.inventoryIds, -1);
        frame.inventoryQuantities = new int[28];
        frame.player = new ScenarioSnapshot.Position(3100, 3200, 0);
        frame.companion = new ScenarioSnapshot.Position(3103, 3200, 0);
        for (int tick = 1; tick <= 12; tick++) { frame.tick = tick; rules.close(frame.build(), ScenarioBatch.empty()); }
        frame.equipment[2]++;
        frame.tick++;
        assertTrue(rules.close(frame.build(), ScenarioBatch.empty()).stream().noneMatch(candidate -> "M09".equals(candidate.getScenarioId())));
        frame.tick++;
        return rules.close(frame.build(), ScenarioBatch.empty()).stream()
            .filter(candidate -> "M09".equals(candidate.getScenarioId())).findFirst().orElseThrow(AssertionError::new);
    }

    static SceneCandidate level(long id, long tick, boolean silent)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("skill", "COOKING");
        facts.put("newRealLevel", "20");
        facts.put("newLevel", "20");
        facts.put("witnessedPresent", "true");
        facts.put("witnessPresenceEpoch", "0");
        facts.put("occurredTick", Long.toString(tick));
        facts.put("settlingNotBeforeTick", "10");
        facts.put("actualRegion", "12850");
        return new SceneCandidate(id, "M04", 45, tick, tick + 10, 1, 1,
            "progress:" + tick + ":" + id, facts, silent, 8);
    }

    static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final CountingRandom choices = new CountingRandom(0);
        final CountingRandom sceneChoices = new CountingRandom(0);
        final CountingRandom cooldowns = new CountingRandom(15);
        final SpeechGuard guard = new SpeechGuard(clock::get, cooldowns, 349, 52);
        final DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        final List<String> trace = new ArrayList<>();
        final AtomicInteger firstPaints = new AtomicInteger();
        final AtomicInteger silentArrivals = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final SpeechDirector director = new SpeechDirector(catalogue, clock::get, choices,
            sceneChoices, trace::add, guard);

        Fixture() throws Exception
        {
            director.setFirstPaintListener(receipt -> firstPaints.incrementAndGet());
            director.setSilentArrivalListener(candidate -> silentArrivals.incrementAndGet());
            director.setCompletionListener(candidate -> completed.incrementAndGet());
        }

        void tick(long tick) { tick(tick, false); }
        void tick(long tick, boolean busy) { director.tick(tick, 1, 1, true, true, busy); }

        SpeechPacket issue(SceneCandidate candidate)
        {
            tick(candidate.getCreatedTick());
            assertTrue(director.offer(candidate));
            assertFalse(director.isIntentionalSilent());
            director.arrive(candidate.getId(), candidate.getCreatedTick());
            SpeechPacket issued = director.getPacket();
            assertNotNull(issued);
            assertSame(candidate, issued.getCandidate());
            return issued;
        }

        SpeechPacket.PaintReceipt paintAndAcknowledge(SpeechPacket issued, long atTick)
        {
            AtomicInteger draws = new AtomicInteger();
            SpeechPacket.PaintReceipt receipt = issued.paint(clock.get(), atTick, 1, draws::incrementAndGet);
            assertNotNull(receipt);
            assertEquals(1, draws.get());
            director.acknowledge(issued, receipt);
            return receipt;
        }

        long terminalDisposals(String scenario)
        {
            return trace.stream().filter(value -> value.equals("scene cancel " + scenario)
                || value.equals("scene expired " + scenario)).count();
        }
    }

    static final class CountingRandom extends Random
    {
        private final int value;
        int draws;
        CountingRandom(int value) { this.value = value; }
        @Override public int nextInt(int bound) { draws++; return Math.floorMod(value, bound); }
    }
}
