package com.malfoid.speech;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechDirectorTest
{
    @Test
    public void exactGroundOutcomeLineGateSelectsOnlyThePermittedAuthoredId() throws Exception
    {
        for (String permitted : new String[] {"M05-03", "M05-04", "M05-05"})
        {
            Fixture f = new Fixture(null, "M05");
            SceneCandidate loot = reportedLoot("M05-05".equals(permitted));
            f.director.setLineValidator((candidate, lineId) -> {
                assertSame(loot, candidate);
                return permitted.equals(lineId);
            });
            f.director.tick(1, 1, 1, true, true, false);
            assertTrue(f.director.offer(loot));
            f.director.arrive(1, 1);
            assertEquals(permitted, f.director.getPacket().getLineId());
            assertSame(loot, f.director.getPacket().getCandidate());
            assertEquals("loot:42", loot.getCausalToken());
            assertEquals(13, loot.getExpiresTick());
            assertEquals("M05-05".equals(permitted) ? 1 : 0, loot.getConditionFacts().size());
            assertEquals(1, f.choices.draws);
            assertEquals(0, f.cooldowns.draws);
        }
    }

    @Test
    public void noTruthfulGroundOutcomeLineKeepsTheSameSceneQuietWithoutATextDraw() throws Exception
    {
        Fixture f = new Fixture(null, "M05");
        SceneCandidate loot = reportedLoot(true);
        f.director.setLineValidator((candidate, lineId) -> false);
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(loot));
        assertTrue(f.director.isIntentionalSilent());
        assertEquals(0, f.choices.draws);
        f.director.arrive(1, 1);
        assertSame(loot, f.director.getActiveCandidate());
        assertTrue(f.director.isCommitted());
        assertNull(f.director.getPacket());
        assertNull(f.director.getLastPaint());
        assertEquals(9, f.director.getHoldUntilTick());
        assertEquals(1, f.cooldowns.draws);
        assertEquals(0, f.guard.textCount());
    }

    @Test
    public void selectedGroundOutcomeLineLostBeforeArrivalCannotIssueOrRenewItsDeadline() throws Exception
    {
        Fixture f = new Fixture(null, "M05");
        SceneCandidate loot = reportedLoot(false);
        AtomicBoolean available = new AtomicBoolean(true);
        f.director.setLineValidator((candidate, lineId) -> "M05-03".equals(lineId) && available.get());
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(loot));
        available.set(false);
        f.director.arrive(1, 1);
        f.director.refreshPresentation(false);
        assertNull(f.director.getPacket());
        assertSame(loot, f.director.getActiveCandidate());
        assertTrue(f.guard.hasPendingReservation());
        assertEquals(1, f.choices.draws);
        assertEquals(13, loot.getExpiresTick());
        f.director.tick(13, 1, 1, true, true, false);
        available.set(true);
        f.director.refreshPresentation(false);
        f.director.arrive(1, 13);
        assertTrue(f.director.isIdle());
        assertNull(f.director.getPacket());
        assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.guard.textCount());
        assertEquals(1, f.choices.draws);
    }

    @Test
    public void issuedGroundOutcomeLineRefreshBlocksFirstPaintWithoutRedrawingTheChoice() throws Exception
    {
        Fixture f = new Fixture(null, "M05");
        SceneCandidate loot = reportedLoot(true);
        AtomicBoolean possessed = new AtomicBoolean(true);
        f.director.setLineValidator((candidate, lineId) -> "M05-05".equals(lineId) && possessed.get());
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(loot));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        assertNotNull(issued);
        possessed.set(false);
        f.director.refreshPresentation(false);
        assertNull(issued.paint(0, 1, 1, () -> fail("Stale possession drew a conditional line")));
        assertSame(issued, f.director.getPacket());
        assertSame(loot, f.director.getActiveCandidate());
        assertEquals(1, f.director.getTick());
        assertEquals(13, loot.getExpiresTick());
        assertEquals(1, f.choices.draws);
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.guard.textCount());
        possessed.set(true);
        f.director.refreshPresentation(false);
        SpeechPacket.PaintReceipt receipt = issued.paint(0, 1, 2, () -> {});
        assertNotNull(receipt);
        f.director.acknowledge(issued, receipt);
        assertEquals("M05-05", receipt.getLineId());
        assertEquals("loot:42", receipt.getCandidate().getCausalToken());
        assertEquals(1, f.choices.draws);
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void genuinePaintKeepsGroundOutcomeHoldAndTextWhenProofIsLostBeforeAcknowledgement() throws Exception
    {
        Fixture f = new Fixture(null, "M05");
        SceneCandidate loot = reportedLoot(false);
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicLong validations = new AtomicLong();
        AtomicLong completions = new AtomicLong();
        f.director.setLineValidator((candidate, lineId) -> {
            validations.incrementAndGet();
            return "M05-03".equals(lineId) && available.get();
        });
        f.director.setCompletionListener(candidate -> completions.incrementAndGet());
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(loot));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        SpeechPacket.PaintReceipt paint = issued.paint(0, 1, 1, () -> {});
        assertNotNull(paint);
        assertTrue(f.director.isCommitted());
        assertNull(f.director.getLastPaint());
        assertEquals(0, f.cooldowns.draws);
        long beforeProofLoss = validations.get();
        available.set(false);
        f.director.refreshPresentation(false);
        assertEquals(beforeProofLoss, validations.get());
        assertTrue(issued.canPaint(0));
        assertSame(loot, f.director.getActiveCandidate());
        assertFalse(f.director.isContinuationCancelled());
        f.director.tick(2, 1, 1, true, true, false);
        assertSame(paint, f.director.getLastPaint());
        assertEquals(9, f.director.getHoldUntilTick());
        assertFalse(f.director.isContinuationCancelled());
        f.director.acknowledge(issued, paint);
        assertEquals(1, f.cooldowns.draws);
        assertEquals(1, f.guard.textCount());
        assertEquals(1, f.choices.draws);
        f.clock.set(8_000_000_000L);
        f.director.tick(8, 1, 1, true, true, false);
        assertSame(loot, f.director.getActiveCandidate());
        assertEquals(0, completions.get());
        f.director.tick(9, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        assertEquals(1, completions.get());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void currentBusyRefreshBlocksFirstPaintWithoutAdvancingTheOpportunity() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        SceneCandidate candidate = candidate(1, "M04", 45, 1, false, "newRealLevel", "20");
        assertTrue(f.director.offer(candidate));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        assertNotNull(issued);
        int choices = f.choices.draws;
        f.director.refreshPresentation(true);
        assertNull(issued.paint(0, 1, 1, () -> fail("Current busy input drew automatic text")));
        assertEquals(1, f.director.getTick());
        assertSame(candidate, f.director.getActiveCandidate());
        assertEquals(11, candidate.getExpiresTick());
        assertEquals(choices, f.choices.draws);
        assertEquals(0, f.cooldowns.draws);
        f.director.refreshPresentation(false);
        assertSame(issued, f.director.getPacket());
        SpeechPacket.PaintReceipt receipt = issued.paint(0, 1, 2, () -> {});
        assertNotNull(receipt);
        f.director.acknowledge(issued, receipt);
        assertEquals(1, f.director.getTick());
        assertEquals(choices, f.choices.draws);
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void currentBusyRefreshKeepsRequestedAndEmergencyPaintExceptions() throws Exception
    {
        for (boolean requested : new boolean[] {true, false})
        {
            Fixture f = new Fixture();
            f.director.tick(1, 1, 1, true, true, false);
            assertTrue(f.director.offer(requested ? requested(1, 1, 1)
                : candidate(1, "M01", 100, 1, false, "actualRegion", "123")));
            f.director.arrive(1, 1);
            SpeechPacket issued = f.director.getPacket();
            assertNotNull(issued);
            f.director.refreshPresentation(true);
            assertNotNull(issued.paint(0, 1, 1, () -> {}));
            assertEquals(1, f.director.getTick());
        }
    }

    @Test
    public void activityToneHasExactFiftyThirtyTwentyBranchesAndOneHoldDraw() throws Exception
    {
        int restless = 0, amused = 0, quiet = 0;
        for (int roll = 0; roll < 100; roll++)
        {
            SceneRandom scene = new SceneRandom(roll, roll % 7);
            Fixture f = new Fixture(null, null, scene);
            f.director.tick(1, 1, 1, true, true, false);
            assertTrue(f.director.offer(candidate(1, "M06", 15, 1, false, "skill", "FISHING", "actualRegion", "123")));
            assertEquals(6 + roll % 7, f.director.getExecutionHoldTicks());
            switch (f.director.getActivityTone())
            {
                case RESTLESS: restless++; break;
                case AMUSED: amused++; break;
                case QUIET: quiet++; break;
            }
            assertEquals(roll >= 80, f.director.isIntentionalSilent());
            assertEquals(roll >= 80 ? 0 : 1, f.choices.draws);
            f.director.arrive(1, 1);
            f.director.arrive(1, 1);
            f.director.tick(2, 1, 1, true, true, false);
            assertEquals(2, scene.draws);
            if (roll >= 80)
            {
                assertNull(f.director.getPacket());
                assertEquals(1 + 6 + roll % 7, f.director.getHoldUntilTick());
                assertEquals(1, f.cooldowns.draws);
            }
        }
        assertEquals(50, restless);
        assertEquals(30, amused);
        assertEquals(20, quiet);
    }

    @Test
    public void coolingAndExpiredPendingActivityNeverConsumeToneRandomness() throws Exception
    {
        SceneRandom scene = new SceneRandom(0, 0);
        Fixture f = new Fixture(null, null, scene);
        SpeechGuard.Reservation prior = f.guard.reserve("M06", null, null);
        assertTrue(f.guard.settleSilentArrival(prior));
        f.director.tick(1, 1, 1, true, true, false);
        assertFalse(f.director.offer(candidate(1, "M06", 15, 1, false, "skill", "FISHING", "actualRegion", "123")));
        assertEquals(0, scene.draws);
        SceneRandom queuedScene = new SceneRandom(0, 0);
        Fixture queued = new Fixture(null, null, queuedScene);
        queued.director.tick(1, 1, 1, true, true, false);
        assertTrue(queued.director.offer(candidate(1, "M01", 100, 1, true, "actualRegion", "123")));
        assertTrue(queued.director.offer(candidate(2, "M06", 15, 1, false, "skill", "FISHING", "actualRegion", "123")));
        queued.director.tick(11, 1, 1, true, true, false);
        assertEquals(0, queuedScene.draws);
    }

    private static final class SceneRandom extends Random
    {
        private final int tone, hold;
        private int draws;
        private SceneRandom(int tone, int hold) { this.tone = tone; this.hold = hold; }
        @Override public int nextInt(int bound)
        {
            draws++;
            if (draws == 1) { assertEquals(100, bound); return tone; }
            assertEquals(2, draws);
            assertEquals(7, bound);
            return hold;
        }
    }

    @Test
    public void precisePrepaintCancellationOwnsScopeAndRevokesIssuedTokenOnce() throws Exception
    {
        Fixture f = new Fixture();
        List<SpeechCancellationReceipt> receipts = new ArrayList<>();
        f.director.setCancellationListener(receipts::add);
        f.director.tick(1, 1, 1, true, true, false);
        SceneCandidate outfit = candidate(1, "M09", 25, 1, false, "equipmentIds", "1,2,3,4");
        assertTrue(f.director.offer(outfit));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        assertNotNull(issued);
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertEquals(1, receipts.size());
        SpeechCancellationReceipt receipt = receipts.get(0);
        assertSame(outfit, receipt.getCandidate());
        assertEquals(1, receipt.getCandidateId());
        assertEquals("cause:1", receipt.getCausalToken());
        assertEquals(1, receipt.getSessionGeneration());
        assertEquals(1, receipt.getSceneRevision());
        assertEquals(1, receipt.getCancelledAtTick());
        assertTrue(receipt.getExecutionId() > 0);
        assertTrue(receipt.getIssuedPacketToken() > 0);
        assertFalse(receipt.isGenuinePainted());
        assertTrue(receipt.isPrePaintAttempt());
        assertNull(issued.paint(0, 1, 1, () -> fail("Cancelled packet drew")));
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void delayedGenuinePaintSettlesBeforeDependentCancellationAndCannotCountAsPrepaint() throws Exception
    {
        Fixture f = new Fixture();
        List<String> order = new ArrayList<>();
        List<SpeechCancellationReceipt> receipts = new ArrayList<>();
        f.director.setFirstPaintListener(receipt -> order.add("paint:" + receipt.getAdmissionToken()));
        f.director.setCancellationListener(receipt -> { order.add("cancel"); receipts.add(receipt); });
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "M09", 25, 1, false, "equipmentIds", "1,2,3,4")));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        SpeechPacket.PaintReceipt paint = issued.paint(0, 1, 1, () -> {});
        assertNotNull(paint);
        assertNull(f.director.getLastPaint());
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertEquals(2, order.size());
        assertEquals("paint:" + paint.getAdmissionToken(), order.get(0));
        assertEquals("cancel", order.get(1));
        assertTrue(receipts.get(0).isGenuinePainted());
        assertFalse(receipts.get(0).isPrePaintAttempt());
        assertEquals(paint.getAdmissionToken(), receipts.get(0).getIssuedPacketToken());
        f.director.acknowledge(issued, paint);
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertEquals(1, receipts.size());
        assertEquals(2, order.size());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void lateReceiptCannotMarkANewerExecutionWithTheSameCauseAsPainted() throws Exception
    {
        Fixture f = new Fixture();
        List<SpeechCancellationReceipt> receipts = new ArrayList<>();
        f.director.setCancellationListener(receipts::add);
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "M09", 25, 1, false, "equipmentIds", "1,2,3,4")));
        f.director.arrive(1, 1);
        SpeechPacket old = f.director.getPacket();
        SpeechPacket.PaintReceipt paint = old.paint(0, 1, 1, () -> {});
        assertNotNull(paint);
        f.director.invalidate(true);
        f.clock.set(46_000_000_000L);
        f.director.tick(2, 1, 1, true, true, false);
        Map<String, String> facts = new HashMap<>();
        facts.put("equipmentIds", "5,6,7,8");
        assertTrue(f.director.offer(new SceneCandidate(2, "M09", 25, 2, 12, 1, 1, "cause:1", facts, false, 4)));
        f.director.arrive(2, 2);
        assertNotNull(f.director.getPacket());
        f.director.acknowledge(old, paint);
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertEquals(1, receipts.size());
        assertEquals(2, receipts.get(0).getCandidateId());
        assertNotEquals(paint.getAdmissionToken(), receipts.get(0).getIssuedPacketToken());
        assertFalse(receipts.get(0).isGenuinePainted());
        assertTrue(receipts.get(0).isPrePaintAttempt());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void genuinePaintLedgerSurvivesMuteAndGenericInvalidityCreatesNoReasonReceipt() throws Exception
    {
        Fixture f = new Fixture();
        List<SpeechCancellationReceipt> receipts = new ArrayList<>();
        f.director.setCancellationListener(receipts::add);
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "M09", 25, 1, false, "equipmentIds", "1,2,3,4")));
        f.director.arrive(1, 1);
        SpeechPacket issued = f.director.getPacket();
        SpeechPacket.PaintReceipt paint = issued.paint(0, 1, 1, () -> {});
        f.director.tick(2, 1, 1, true, false, false);
        assertNull(f.director.getPacket());
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertTrue(receipts.get(0).isGenuinePainted());
        assertEquals(paint.getAdmissionToken(), receipts.get(0).getIssuedPacketToken());
        f.director.acknowledge(issued, paint);
        f.director.tick(3, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(2, "M04", 45, 3, true, "newRealLevel", "20")));
        f.director.invalidate(false);
        assertEquals(1, receipts.size());
    }

    @Test
    public void alreadyCommittedSilentHoldIsNotAnUnpaintedAttempt() throws Exception
    {
        Fixture f = new Fixture();
        List<SpeechCancellationReceipt> receipts = new ArrayList<>();
        f.director.setCancellationListener(receipts::add);
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "M09", 25, 1, true, "equipmentIds", "1,2,3,4")));
        f.director.arrive(1, 1);
        f.director.invalidate(false, SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
        assertEquals(1, receipts.size());
        assertFalse(receipts.get(0).isGenuinePainted());
        assertFalse(receipts.get(0).isPrePaintAttempt());
        assertEquals(-1, receipts.get(0).getIssuedPacketToken());
    }

    @Test
    public void speechCommitsOnlyAfterActualCompletePaintAndDuplicateReceiptIsHarmless() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "U01", 85, 1, false, "talkStreak", "1")));
        f.director.arrive(1, 1);
        SpeechPacket packet = f.director.getPacket();
        assertNotNull(packet);
        assertNull(f.director.getLastPaint());
        assertEquals(0, f.guard.textCount());
        SpeechPacket.PaintReceipt receipt = packet.paint(0, 1, 1, () -> {});
        assertNotNull(receipt);
        f.director.acknowledge(packet, receipt);
        assertSame(receipt, f.director.getLastPaint());
        assertEquals(1, f.guard.textCount());
        assertEquals(0, f.guard.interactionCount());
        assertNull(packet.paint(1, 1, 2, () -> {}));
        f.director.acknowledge(packet, receipt);
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void revokedBeforePaintCannotDrawAndNeverCommits() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        SpeechPacket packet = f.director.getPacket();
        f.director.invalidate(true);
        assertNull(packet.paint(0, 1, 1, () -> fail("Revoked packet drew")));
        assertEquals(0, f.cooldowns.draws);
        assertFalse(f.guard.hasPendingReservation());
    }

    @Test
    public void paintThenTargetLossThenReceiptRetainsSentenceAndTruthfulHistory() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        SpeechPacket packet = f.director.getPacket();
        SpeechPacket.PaintReceipt receipt = packet.paint(0, 1, 1, () -> {});
        f.director.invalidate(false);
        assertTrue(packet.canPaint(1_000_000_000L));
        assertFalse(packet.isContinuationAllowed());
        f.director.acknowledge(packet, receipt);
        assertSame(receipt, f.director.getLastPaint());
        assertEquals(1, f.guard.interactionCount());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void softRevokedPaintedTailFinishesReadabilityWithoutCompletingOrReleasingTheCancelledScene() throws Exception
    {
        Fixture f = new Fixture();
        AtomicLong completed = new AtomicLong();
        f.director.setCompletionListener(candidate -> completed.incrementAndGet());
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        SpeechPacket packet = f.director.getPacket();
        SpeechPacket.PaintReceipt receipt = packet.paint(0, 1, 1, () -> {});
        f.director.invalidate(false);
        assertTrue(f.director.isContinuationCancelled());
        assertTrue(packet.canPaint(1_000_000_000L));
        assertFalse(f.director.isReleasing());
        f.clock.set(8_000_000_000L);
        f.director.tick(2, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        assertEquals(0, completed.get());
        f.director.acknowledge(packet, receipt);
        assertEquals(1, f.cooldowns.draws);
        assertEquals(1, f.guard.textCount());
    }

    @Test
    public void oldSessionPaintSettlesAnonymousGuardWithoutNarrativeResurrection() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        SpeechPacket packet = f.director.getPacket();
        SpeechPacket.PaintReceipt receipt = packet.paint(0, 1, 1, () -> {});
        f.director.resetSession(2);
        f.director.acknowledge(packet, receipt);
        assertNull(f.director.getLastPaint());
        assertFalse(f.guard.interactionEligible("M04"));
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void silentArrivalBlocksAliasesAndBusyDoesNotInventPaint() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, true);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        assertNull(f.director.getPacket());
        f.director.tick(2, 1, 1, true, false, true);
        assertTrue(f.director.isIntentionalSilent());
        assertEquals(1, f.guard.interactionCount());
        assertEquals(0, f.guard.textCount());
        assertFalse(f.director.offer(candidate(2, "M48", 45, 2, true, "newRealLevel", "30")));
    }

    @Test
    public void onlyTwoPendingCandidatesAndPriorityPreemptionAreBounded() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M01", 100, 1, false, "actualRegion", "123"));
        assertTrue(f.director.offer(candidate(2, "M06", 15, 1, true)));
        assertTrue(f.director.offer(candidate(3, "M10", 20, 1, true)));
        assertTrue(f.director.offer(candidate(4, "M09", 25, 1, true)));
        assertEquals(2, f.director.pendingCount());
        assertFalse(f.director.offer(candidate(5, "M08", 10, 1, true)));
        assertTrue(f.director.offer(candidate(6, "M02", 110, 1, true)));
        assertEquals("M02", f.director.getActiveCandidate().getScenarioId());
    }

    @Test
    public void expiredUnpaintedOpportunityReleasesReservationAndCannotWaitForCooldown() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, true);
        f.director.offer(candidate(1, "M04", 45, 1, false, "newRealLevel", "20"));
        f.director.arrive(1, 1);
        f.director.tick(11, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void complaintMayDetectEarlyButNeverPaintsBeforeParentPlusEight() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(2, 1, 1, true, true, false);
        assertTrue(f.director.offer(candidate(1, "M07", 50, 2, false, "parentFirstPaintTick", "0")));
        f.director.arrive(1, 2);
        assertNull(f.director.getPacket());
        f.director.tick(7, 1, 1, true, true, false);
        assertNull(f.director.getPacket());
        f.director.tick(8, 1, 1, true, true, false);
        assertNotNull(f.director.getPacket());
    }

    @Test
    public void silentHoldCanFinishAfterFirstSpeechDeadlineAndCancellationNeverCompletes() throws Exception
    {
        Fixture f = new Fixture();
        AtomicLong completed = new AtomicLong();
        f.director.setCompletionListener(candidate -> completed.incrementAndGet());
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M02", 110, 1, true));
        f.director.arrive(1, 10);
        f.director.tick(11, 1, 1, true, true, false);
        assertEquals(0, completed.get());
        f.director.tick(14, 1, 1, true, true, false);
        assertEquals(1, completed.get());
        assertTrue(f.director.isReleasing());
        f.director.tick(24, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        f.director.offer(candidate(2, "M04", 45, 24, true));
        f.director.arrive(2, 24);
        f.director.invalidate(true);
        f.director.tick(30, 1, 1, true, true, false);
        assertEquals(1, completed.get());
    }

    @Test
    public void newerUnpaintedTalkUsesItsStreakPoolWithoutExtendingOriginalOpportunity() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        SceneCandidate first = requested(1, 1, 1);
        f.director.offer(first);
        f.director.arrive(1, 1);
        SpeechPacket oldPacket = f.director.getPacket();
        f.director.tick(2, 1, 1, true, true, false);
        assertTrue(f.director.offer(requested(2, 2, 2)));
        assertFalse(oldPacket.canPaint(0));
        assertEquals("2", f.catalogue.getLine(f.director.getPacket().getLineId()).getConditions().get("talkStreak"));
        f.director.tick(3, 1, 1, true, true, false);
        assertTrue(f.director.offer(requested(3, 3, 3)));
        SceneCandidate updated = f.director.getActiveCandidate();
        assertEquals(3, updated.getId());
        assertEquals(first.getCreatedTick(), updated.getCreatedTick());
        assertEquals(first.getExpiresTick(), updated.getExpiresTick());
        assertEquals(first.getCausalToken(), updated.getCausalToken());
        assertEquals("3", f.catalogue.getLine(f.director.getPacket().getLineId()).getConditions().get("talkStreak"));
        assertEquals(0, f.director.pendingCount());
        assertEquals(0, f.cooldowns.draws);
        f.director.tick(first.getExpiresTick(), 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        assertFalse(f.guard.hasPendingReservation());
    }

    @Test
    public void paintedTalkSurvivesSecondRequestButFourthAndLaterRequestsOnlyTurnAway() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(requested(1, 1, 1));
        f.director.arrive(1, 1);
        SpeechPacket firstPacket = f.director.getPacket();
        SpeechPacket.PaintReceipt receipt = firstPacket.paint(0, 1, 1, () -> {});
        assertTrue(f.director.offer(requested(2, 1, 2)));
        assertSame(firstPacket, f.director.getPacket());
        assertSame(receipt, f.director.getLastPaint());
        assertEquals(1, f.cooldowns.draws);
        f.director.tick(2, 1, 1, true, true, false);
        assertTrue(f.director.offer(requested(4, 2, 4)));
        assertFalse(firstPacket.canPaint(1_000_000_000L));
        assertNull(f.director.getPacket());
        assertTrue(f.director.isIntentionalSilent());
        assertTrue(f.director.hasArrived());
        assertEquals(0, f.guard.interactionCount());
        assertEquals(1, f.guard.textCount());
        f.director.tick(4, 1, 1, true, true, false);
        assertTrue(f.director.offer(requested(5, 4, 4)));
        assertEquals(9, f.director.getActiveCandidate().getExpiresTick());
        f.director.tick(8, 1, 1, true, true, false);
        assertFalse(f.director.isIdle());
        f.director.tick(9, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        f.director.acknowledge(firstPacket, receipt);
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void queuedTalkUpdatesBehindEmergencyAndCancelsLowerPriorityPendingScenes() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M01", 100, 1, true));
        f.director.offer(candidate(2, "M04", 45, 1, true));
        assertTrue(f.director.offer(requested(3, 1, 1)));
        assertEquals(1, f.director.pendingCount());
        f.director.tick(2, 1, 1, true, true, false);
        assertTrue(f.director.offer(requested(4, 2, 4)));
        assertEquals("M01", f.director.getActiveCandidate().getScenarioId());
        assertEquals(1, f.director.pendingCount());
        f.director.tick(9, 1, 1, true, true, false);
        assertEquals(0, f.director.pendingCount());
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void witnessedLevelWaitsOriginalGraceAndRebindsOnceAfterTwoDistinctSnapshots() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(12, 1, 1, true, true, false);
        SceneCandidate level = witnessedLevel(11, "M04", 12, 3, 13, false);
        assertTrue(f.director.offer(level));
        assertNull(f.director.getActiveCandidate());
        assertEquals(0, f.choices.draws);
        f.director.suspendForOrdinaryLoading(12, 1);
        assertTrue(f.director.hasRetainedLevel());
        f.director.resumeAfterLoading(13, 1, 2, true);
        f.director.resumeAfterLoading(13, 1, 2, true);
        assertNull(f.director.getActiveCandidate());
        f.director.resumeAfterLoading(14, 1, 2, true);
        SceneCandidate rebound = f.director.getActiveCandidate();
        assertNotNull(rebound);
        assertEquals(level.getId(), rebound.getId());
        assertEquals(level.getCausalToken(), rebound.getCausalToken());
        assertEquals(12, rebound.getCreatedTick());
        assertEquals(22, rebound.getExpiresTick());
        assertEquals(2, rebound.getSceneRevision());
        assertEquals(1, rebound.getRebindCount());
        assertEquals("13", rebound.getConditionFacts().get("settlingNotBeforeTick"));
        f.director.suspendForOrdinaryLoading(15, 1);
        assertFalse(f.director.hasRetainedLevel());
        assertTrue(f.director.isIdle());
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void unpaintedLevelKeepsChosenLineAndNoCooldownDrawAcrossLoading() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(10, 1, 1, true, true, false);
        f.director.offer(witnessedLevel(1, "M04", 10, 0, 10, false));
        f.director.arrive(1, 10);
        SpeechPacket old = f.director.getPacket();
        String lineId = old.getLineId();
        assertEquals(1, f.choices.draws);
        f.director.suspendForOrdinaryLoading(11, 1);
        assertFalse(old.canPaint(0));
        assertFalse(f.guard.hasPendingReservation());
        f.director.resumeAfterLoading(12, 1, 2, true);
        f.director.resumeAfterLoading(13, 1, 2, true);
        f.director.arrive(1, 13);
        SpeechPacket current = f.director.getPacket();
        assertEquals(lineId, current.getLineId());
        assertEquals(1, f.choices.draws);
        assertEquals(0, f.cooldowns.draws);
        SpeechPacket.PaintReceipt receipt = current.paint(0, 13, 3, () -> {});
        f.director.acknowledge(current, receipt);
        assertEquals(1, f.cooldowns.draws);
        assertFalse(f.guard.interactionEligible("M04"));
    }

    @Test
    public void paintedLevelNeverRebindsEvenBeforeItsQueuedAcknowledgement() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(10, 1, 1, true, true, false);
        f.director.offer(witnessedLevel(1, "M04", 10, 0, 10, false));
        f.director.arrive(1, 10);
        SpeechPacket packet = f.director.getPacket();
        SpeechPacket.PaintReceipt receipt = packet.paint(0, 10, 1, () -> {});
        f.director.suspendForOrdinaryLoading(11, 1);
        assertFalse(f.director.hasRetainedLevel());
        assertTrue(f.director.isIdle());
        assertSame(receipt, f.director.getLastPaint());
        assertEquals(1, f.cooldowns.draws);
        f.director.acknowledge(packet, receipt);
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void retainedLevelExpiresAtOriginalDeadlineAndHardAbsenceClearsIt() throws Exception
    {
        Fixture expired = new Fixture();
        expired.director.tick(10, 1, 1, true, true, false);
        expired.director.offer(witnessedLevel(1, "M04", 10, 0, 10, false));
        expired.director.suspendForOrdinaryLoading(11, 1);
        expired.director.resumeAfterLoading(19, 1, 2, true);
        expired.director.resumeAfterLoading(20, 1, 2, true);
        assertFalse(expired.director.hasRetainedLevel());
        assertTrue(expired.director.isIdle());
        assertEquals(0, expired.cooldowns.draws);
        Fixture absent = new Fixture();
        absent.director.tick(10, 1, 1, true, true, false);
        absent.director.offer(witnessedLevel(1, "M04", 10, 0, 10, false));
        absent.director.suspendForOrdinaryLoading(11, 1);
        absent.director.invalidate(true);
        absent.director.resumeAfterLoading(12, 1, 2, true);
        absent.director.resumeAfterLoading(13, 1, 2, true);
        assertTrue(absent.director.isIdle());
        assertEquals(0, absent.cooldowns.draws);
    }

    @Test
    public void unknownWitnessCannotRetainAndInvalidSnapshotRestartsTheRebindGate() throws Exception
    {
        Fixture unknown = new Fixture();
        unknown.director.tick(10, 1, 1, true, true, false);
        unknown.director.offer(candidate(1, "M04", 45, 10, false, "newRealLevel", "20"));
        unknown.director.suspendForOrdinaryLoading(11, 1);
        assertFalse(unknown.director.hasRetainedLevel());
        Fixture f = new Fixture();
        f.director.tick(10, 1, 1, true, true, false);
        f.director.offer(witnessedLevel(1, "M04", 10, 0, 10, false));
        f.director.suspendForOrdinaryLoading(11, 1);
        f.director.resumeAfterLoading(12, 1, 2, true);
        f.director.resumeAfterLoading(13, 1, 2, false);
        f.director.resumeAfterLoading(14, 1, 2, true);
        assertNull(f.director.getActiveCandidate());
        f.director.resumeAfterLoading(15, 1, 3, true);
        assertNull(f.director.getActiveCandidate());
        f.director.resumeAfterLoading(16, 1, 3, true);
        assertEquals(3, f.director.getActiveCandidate().getSceneRevision());
    }

    @Test
    public void retainedBurntShrimpAliasDowngradesToBaseLevelFactWithoutInventoryClaim() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(10, 1, 1, true, true, false);
        SceneCandidate original = witnessedLevel(1, "M48", 10, 0, 10, true);
        f.director.offer(original);
        f.director.suspendForOrdinaryLoading(11, 1);
        f.director.resumeAfterLoading(12, 1, 2, true);
        f.director.resumeAfterLoading(13, 1, 2, true);
        SceneCandidate rebound = f.director.getActiveCandidate();
        assertEquals("M04", rebound.getScenarioId());
        assertEquals(original.getId(), rebound.getId());
        assertEquals(original.getCausalToken(), rebound.getCausalToken());
        assertEquals(original.getExpiresTick(), rebound.getExpiresTick());
        assertFalse(rebound.getConditionFacts().containsKey("burntShrimpPresent"));
        assertTrue(f.director.isIntentionalSilent());
    }

    @Test
    public void pendingAmbientGraceDoesNotConsumeRandomnessOrExtendDeadline() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(5, 1, 1, true, true, false);
        f.director.offer(candidate(1, "M10", 20, 5, false, "actualRegion", "12850", "settlingNotBeforeTick", "10"));
        assertNull(f.director.getActiveCandidate());
        f.director.tick(9, 1, 1, true, true, false);
        assertNull(f.director.getActiveCandidate());
        assertEquals(0, f.choices.draws);
        f.director.tick(10, 1, 1, true, true, false);
        assertEquals(1, f.director.getActiveCandidate().getId());
        assertEquals(15, f.director.getActiveCandidate().getExpiresTick());
        assertEquals(1, f.choices.draws);
    }

    @Test
    public void concernWatchRefreshesWithinThirtyTickCapThenReleasesForTenTicks() throws Exception
    {
        Fixture f = new Fixture();
        AtomicLong completed = new AtomicLong();
        f.director.setCompletionListener(candidate -> completed.incrementAndGet());
        f.director.tick(1, 1, 1, true, false, false);
        f.director.offer(emergency(1, "M01", 100, 1, 20));
        f.director.arrive(1, 1);
        assertEquals(21, f.director.getHoldUntilTick());
        assertEquals(1, f.cooldowns.draws);
        f.director.tick(5, 1, 1, true, false, false);
        f.director.refreshEmergencyWatch(5);
        assertEquals(25, f.director.getHoldUntilTick());
        f.director.tick(20, 1, 1, true, false, false);
        f.director.refreshEmergencyWatch(20);
        assertEquals(31, f.director.getHoldUntilTick());
        f.director.tick(30, 1, 1, true, false, false);
        assertFalse(f.director.isReleasing());
        f.director.tick(31, 1, 1, true, false, false);
        assertTrue(f.director.isReleasing());
        assertEquals(41, f.director.getReleaseUntilTick());
        assertEquals(0d, f.director.getReleaseProgress(), 0d);
        f.director.tick(36, 1, 1, true, false, false);
        f.director.refreshEmergencyWatch(36);
        assertEquals(0.5d, f.director.getReleaseProgress(), 0d);
        assertEquals(31, f.director.getHoldUntilTick());
        f.director.tick(41, 1, 1, true, false, false);
        assertTrue(f.director.isIdle());
        assertEquals(1, completed.get());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void qualifyingHitDuringEarlyReleaseResumesOnlyTheSameBoundedPhysicalWatch() throws Exception
    {
        Fixture f = new Fixture();
        AtomicLong completed = new AtomicLong();
        f.director.setCompletionListener(candidate -> completed.incrementAndGet());
        f.director.tick(1, 1, 1, true, false, false);
        f.director.offer(emergency(1, "M01", 100, 1, 20));
        f.director.arrive(1, 1);
        f.director.tick(21, 1, 1, true, false, false);
        assertTrue(f.director.isReleasing());
        f.director.tick(23, 1, 1, true, false, false);
        f.director.refreshEmergencyWatch(23);
        assertFalse(f.director.isReleasing());
        assertEquals(31, f.director.getHoldUntilTick());
        assertNull(f.director.getPacket());
        f.director.tick(31, 1, 1, true, false, false);
        assertTrue(f.director.isReleasing());
        f.director.tick(41, 1, 1, true, false, false);
        assertTrue(f.director.isIdle());
        assertEquals(1, completed.get());
        assertEquals(1, f.cooldowns.draws);
    }

    @Test
    public void reunionCompletesItsFifteenTickHoldBeforeTenTickReleaseAndOnlyOnce() throws Exception
    {
        Fixture f = new Fixture();
        AtomicLong completionTick = new AtomicLong(-1);
        f.director.setCompletionListener(candidate -> completionTick.set(f.director.getTick()));
        f.director.tick(2, 1, 1, true, false, false);
        f.director.offer(emergency(1, "M02", 110, 2, 15));
        f.director.arrive(1, 5);
        f.director.tick(19, 1, 1, true, false, false);
        assertEquals(-1, completionTick.get());
        f.director.tick(20, 1, 1, true, false, false);
        assertEquals(20, completionTick.get());
        assertTrue(f.director.isReleasing());
        f.director.tick(29, 1, 1, true, false, false);
        assertFalse(f.director.isIdle());
        assertEquals(0.9d, f.director.getReleaseProgress(), 0.0001d);
        f.director.tick(30, 1, 1, true, false, false);
        assertTrue(f.director.isIdle());
        assertEquals(20, completionTick.get());
    }

    @Test
    public void copiedExpansionApproachBoundIsEnforcedAndMalformedBoundFailsClosed() throws Exception
    {
        Fixture f = new Fixture();
        f.director.tick(1, 1, 1, true, true, false);
        assertFalse(f.director.offer(candidate(1, "M04", 45, 1, false,
            "newRealLevel", "20", "maximumApproachTicks", "100")));
        assertTrue(f.director.offer(candidate(2, "M04", 45, 1, false,
            "newRealLevel", "20", "maximumApproachTicks", "4")));
        f.director.tick(4, 1, 1, true, true, false);
        assertFalse(f.director.isIdle());
        f.director.tick(5, 1, 1, true, true, false);
        assertTrue(f.director.isIdle());
        assertEquals(0, f.cooldowns.draws);
    }

    @Test
    public void emoteArrivalBypassesUnrelatedSpeechReservationAndMuteWithoutResampling() throws Exception
    {
        Fixture f = new Fixture(true);
        AtomicLong arrivals = new AtomicLong();
        f.director.setSilentArrivalListener(candidate -> {
            assertEquals("E01", candidate.getScenarioId());
            arrivals.incrementAndGet();
        });
        SpeechGuard.Reservation unrelated = f.guard.reserve("M04", null, null);
        f.director.tick(10, 1, 1, true, true, false);
        SceneCandidate emote = candidate(1, "E01", 40, 10, true, "settlingNotBeforeTick", "11");
        assertTrue(f.director.offer(emote));
        assertNull(f.director.getActiveCandidate());
        f.director.tick(11, 1, 1, true, true, false);
        assertSame(emote, f.director.getActiveCandidate());
        assertTrue(f.director.isIntentionalSilent());
        f.director.arrive(1, 11);
        f.director.arrive(1, 11);
        f.director.tick(12, 1, 1, true, false, false);
        assertEquals(1, arrivals.get());
        assertNull(f.director.getPacket());
        assertNull(f.director.getLastPaint());
        assertTrue(f.guard.hasPendingReservation());
        assertEquals(0, f.guard.textCount());
        assertEquals(0, f.guard.interactionCount());
        assertEquals(0, f.guard.situationCount());
        assertEquals(0, f.choices.draws);
        assertEquals(0, f.cooldowns.draws);
        f.director.tick(15, 1, 1, true, false, false);
        assertTrue(f.director.isIdle());
        assertTrue(f.guard.hasPendingReservation());
        f.guard.retireNoPaint(unrelated);
        assertFalse(f.guard.hasPendingReservation());
    }

    @Test
    public void emoteMustBeApprovedSilentPriorityFortyAndCancellationDoesNotCommitArrival() throws Exception
    {
        Fixture disabled = new Fixture(false);
        disabled.director.tick(10, 1, 1, true, true, false);
        assertFalse(disabled.director.offer(candidate(1, "E01", 40, 10, true)));
        Fixture f = new Fixture(true);
        f.director.setSilentArrivalListener(candidate -> fail("Cancelled emote committed arrival"));
        f.director.tick(10, 1, 1, true, true, false);
        assertFalse(f.director.offer(candidate(1, "E01", 85, 10, true)));
        assertFalse(f.director.offer(candidate(2, "E01", 40, 10, false)));
        assertTrue(f.director.offer(candidate(3, "E01", 40, 10, true)));
        assertFalse(f.guard.hasPendingReservation());
        f.director.invalidate(true);
        f.director.arrive(3, 10);
        assertTrue(f.director.isIdle());
        assertEquals(0, f.cooldowns.draws);
        assertEquals(0, f.choices.draws);
        assertEquals(0, f.guard.interactionCount());
    }

    private static SceneCandidate emergency(long id, String scenario, int priority, long tick, int hold)
    {
        return new SceneCandidate(id, scenario, priority, tick, tick + 20, 1, 1, "emergency:" + id,
            new HashMap<>(), true, hold);
    }

    private static SceneCandidate requested(long id, long tick, int streak)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("talkStreak", Integer.toString(streak));
        return new SceneCandidate(id, "U01", 85, tick, tick + 8, 1, 1, "request:" + id,
            facts, streak >= 4, streak >= 4 ? 5 : 4);
    }

    private static SceneCandidate witnessedLevel(long id, String scenario, long tick,
        long witnessEpoch, long notBefore, boolean silent)
    {
        Map<String, String> facts = new HashMap<>();
        facts.put("witnessedPresent", "true");
        facts.put("occurredTick", Long.toString(tick));
        facts.put("witnessPresenceEpoch", Long.toString(witnessEpoch));
        facts.put("settlingNotBeforeTick", Long.toString(notBefore));
        facts.put("newRealLevel", "20");
        facts.put("skill", "COOKING");
        if ("M48".equals(scenario)) { facts.put("burntShrimpPresent", "true"); }
        return new SceneCandidate(id, scenario, 45, tick, tick + 10, 1, 1, "level:" + id, facts, silent, 8);
    }

    private static SceneCandidate candidate(long id, String scenario, int priority, long tick,
        boolean silent, String... pairs)
    {
        Map<String, String> facts = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { facts.put(pairs[i], pairs[i + 1]); }
        return new SceneCandidate(id, scenario, priority, tick, tick + 10, 1, 1, "cause:" + id, facts, silent, 4);
    }

    private static SceneCandidate reportedLoot(boolean possession)
    {
        Map<String, String> facts = new HashMap<>();
        if (possession) { facts.put("itemPossessionConfirmed", "true"); }
        return new SceneCandidate(1, "M05", 60, 1, 13, 1, 1, "loot:42", facts, false, 8);
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final SpeechGuardTest.CountingRandom cooldowns = new SpeechGuardTest.CountingRandom(15);
        final SpeechGuardTest.CountingRandom choices = new SpeechGuardTest.CountingRandom(0);
        final SpeechGuard guard = new SpeechGuard(clock::get, cooldowns, 349, 52);
        final DialogueCatalog catalogue;
        final SpeechDirector director;
        Fixture() throws Exception
        {
            this(null, null);
        }
        Fixture(Boolean enableEmote) throws Exception
        {
            this(enableEmote, null);
        }
        Fixture(Boolean enableEmote, String additionalScenario) throws Exception
        {
            this(enableEmote, additionalScenario, new Random(0));
        }
        Fixture(Boolean enableEmote, String additionalScenario, Random sceneRandom) throws Exception
        {
            if (enableEmote != null || additionalScenario != null)
            {
                try (InputStream input = getClass().getResourceAsStream("/com/malfoid/dialogue.json"))
                {
                    JsonObject runtime = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    for (int index = runtime.getAsJsonArray("runtimeEnabledScenarios").size() - 1; enableEmote != null && index >= 0; index--)
                    {
                        if ("E01".equals(runtime.getAsJsonArray("runtimeEnabledScenarios").get(index).getAsString()))
                        {
                            runtime.getAsJsonArray("runtimeEnabledScenarios").remove(index);
                        }
                    }
                    if (Boolean.TRUE.equals(enableEmote)) { runtime.getAsJsonArray("runtimeEnabledScenarios").add("E01"); }
                    if (additionalScenario != null)
                    {
                        runtime.getAsJsonArray("runtimeEnabledScenarios").add(additionalScenario);
                        runtime.getAsJsonObject("silencePercentByScenario").addProperty(additionalScenario, 0);
                        for (com.google.gson.JsonElement element : runtime.getAsJsonArray("entries"))
                        {
                            JsonObject line = element.getAsJsonObject();
                            if (additionalScenario.equals(line.get("scenario").getAsString())
                                && (!"M05".equals(additionalScenario)
                                    || "M05-03".equals(line.get("id").getAsString())
                                    || "M05-04".equals(line.get("id").getAsString())
                                    || "M05-05".equals(line.get("id").getAsString())))
                            {
                                line.addProperty("status", "enabled");
                            }
                        }
                    }
                    catalogue = DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
                }
            }
            else { catalogue = DialogueCatalog.load(getClass()); }
            director = new SpeechDirector(catalogue, clock::get, choices, sceneRandom, ignored -> {}, guard);
        }
    }
}
