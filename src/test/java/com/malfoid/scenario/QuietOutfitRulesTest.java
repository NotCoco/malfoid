package com.malfoid.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.QuietOutfitRules.Cancellation;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechCancellationReceipt.Reason;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
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
import org.junit.Test;
import static org.junit.Assert.*;

/** Copied-input contracts plus actual director-issued cancellation/paint receipts. */
public class QuietOutfitRulesTest
{
	@Test public void threeOwnedCancellationsReplaceTheCurrentAppraisalWithoutNewIdentityOrDeadline()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate base = r.base("M09"); SceneCandidate quiet = r.decorate(base);
		assertEquals("M47", quiet.getScenarioId()); assertEquals(29, quiet.getPriority()); assertTrue(quiet.isIntentionalSilent());
		assertEquals(8, quiet.getHoldTicks()); assertEquals(base.getId(), quiet.getId());
		assertEquals(base.getCausalToken(), quiet.getCausalToken()); assertEquals(base.getCreatedTick(), quiet.getCreatedTick());
		assertEquals(base.getExpiresTick(), quiet.getExpiresTick()); assertEquals(base.getSessionGeneration(), quiet.getSessionGeneration());
		assertEquals(base.getSceneRevision(), quiet.getSceneRevision());
		for (Map.Entry<String, String> fact : base.getConditionFacts().entrySet()) { assertEquals(fact.getValue(), quiet.getConditionFacts().get(fact.getKey())); }
		assertEquals("3", quiet.getConditionFacts().get("quietCancellationCount"));
		assertEquals(Long.toString(r.firstCancellation), quiet.getConditionFacts().get("quietFirstCancellationTick"));
		assertTrue(r.valid(quiet)); assertNull(quiet.getMilestone());
	}

	@Test public void everyApprovedOutfitClassifierUsesTheSameExecutionPatternAndReplacement()
	{
		for (String id : new String[]{"M09", "M31", "M32", "M33", "M34", "M44"})
		{
			Replay r = new Replay(); for (int execution = 1; execution <= 3; execution++) { r.cancel(execution, id, false, true, true, true); }
			SceneCandidate base = r.base(id); SceneCandidate quiet = r.decorate(base);
			assertEquals(id, "M47", quiet.getScenarioId()); assertEquals(base.getId(), quiet.getId()); assertTrue(r.valid(quiet));
		}
	}

	@Test public void twoCancellationsNeverProduceAQuietReplacement()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void equipmentCallbacksAndRejectedCandidatesSupplyNoExecutionReceipts()
	{
		Replay r = new Replay(); for (int i = 0; i < 12; i++) { r.frame.equipment[0]++; r.next(); r.next(); }
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void anotherCancellationReasonDoesNotSupplyTheThirdRevisionFailure()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); SceneCandidate old = r.base("M09");
		r.frame.equipment[0]++; r.next(); r.rules.onCancellation(new Cancellation(old, 3, r.frame.tick,
			Reason.INSPECTION_TARGET_LOST, false, true, true)); r.next();
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void nonOutfitExecutionsAndM47ItselfCannotSeedThePattern()
	{
		for (String id : new String[]{"M04", "M10", "M47", "E01", "U01"})
		{
			Replay r = new Replay(); for (int i = 1; i <= 3; i++) { r.cancel(i, id, false, true, true, true); }
			SceneCandidate base = r.base("M09"); assertSame(id, base, r.decorate(base));
		}
	}

	@Test public void duplicateExecutionIdsAreConsumedEvenAcrossFreshTupleChanges()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(1); r.cancel(1);
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		r.cancel(2); r.cancel(3); assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void executionIdentityIsIndependentOfRepeatedCandidateIds()
	{
		Replay r = new Replay(); r.fixedCandidateId = 90; r.prepare();
		assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void alreadyCommittedSilentArrivalIsNotAPrepaintAppraisalFailure()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); r.cancel(3, "M09", false, false, true, true);
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void aGenuinePaintedCancellationClearsEarlierUnpaintedFailures()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); r.cancel(3, "M09", true, false, true, true);
		r.cancel(4); r.cancel(5); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void unresolvedIssuedPacketLedgerConsumesThePatternRatherThanGuessingSilence()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); r.cancel(3, "M09", false, true, false, true);
		r.cancel(4); r.cancel(5); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		r.cancel(6); assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void genuineIssuedPaintResetsACompletePatternBeforeReplacement()
	{
		Replay r = new Replay(); r.prepare(); r.rules.onFirstPaint(r.base("M31"), 77, r.frame.tick, true);
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void unverifiedPaintFeedbackCannotInventAGenuinePaintOrResetThePattern()
	{
		Replay r = new Replay(); r.prepare(); r.rules.onFirstPaint(r.base("M09"), 77, r.frame.tick, false);
		assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void duplicateSettledPaintDoesNotResetNewFailures()
	{
		Replay r = new Replay(); SceneCandidate painted = r.base("M09"); long paintTick = r.frame.tick;
		r.rules.onFirstPaint(painted, 77, paintTick, true); r.prepare();
		r.rules.onFirstPaint(painted, 77, paintTick, true); assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void unrelatedOrOldScenePaintDoesNotEraseCurrentOutfitEvidence()
	{
		Replay r = new Replay(); r.prepare(); r.rules.onFirstPaint(r.base("M04"), 77, r.frame.tick, true);
		SceneCandidate oldScene = copy(r.base("M09"), 1, 2, null, null);
		r.rules.onFirstPaint(oldScene, 78, r.frame.tick, true); assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void eightyCommittedTicksIncludeTheOldestCancellationAtAgeSeventyNine()
	{
		Replay r = new Replay(); r.cancel(1); long first = r.firstCancellation;
		while (r.frame.tick < first + 75) { r.next(); } r.cancel(2); r.cancel(3);
		assertEquals(first + 79, r.frame.tick); assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void theOldestCancellationIsExcludedAtAgeEighty()
	{
		Replay r = new Replay(); r.cancel(1); long first = r.firstCancellation;
		while (r.frame.tick < first + 76) { r.next(); } r.cancel(2); r.cancel(3);
		assertEquals(first + 80, r.frame.tick); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void currentTupleNeedsTwoCommittedCopiesAfterTheThirdCancellation()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); r.cancel(3, "M09", false, true, true, false);
		SceneCandidate first = r.base("M09"); assertSame(first, r.decorate(first)); r.next();
		assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void duplicateCommittedCopiesCannotProvideTheFinalStabilityWitness()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); r.cancel(3, "M09", false, true, true, false);
		for (int i = 0; i < 8; i++) { r.rules.close(r.frame.build(), r.combat, r.lastDamage); }
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base)); r.next();
		assertEquals("M47", r.decorate(r.base("M09")).getScenarioId());
	}

	@Test public void aFourthRevisionCancelsTheQuietProofAndCannotRecycleHandledExecutions()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09")); assertTrue(r.valid(quiet));
		r.frame.equipment[0]++; r.next(); assertFalse(r.valid(quiet)); r.next();
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		r.rules.onCancellation(new Cancellation(base, 3, r.frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, true));
		assertSame(base, r.decorate(base));
	}

	@Test public void auditedUiSuppressionConsumesThePatternAndAnIssuedQuietScene()
	{
		for (boolean issued : new boolean[]{false, true})
		{
			Replay r = new Replay(); r.prepare(); SceneCandidate quiet = issued ? r.decorate(r.base("M09")) : null;
			r.frame.inventoryBlocked = true; r.next(); if (issued) { assertFalse(r.valid(quiet)); }
			r.frame.inventoryBlocked = false; r.next(); r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void outfitUiUnknownAndEmergencyCannotDeferM47IntoALaterSafeInterval()
	{
		for (boolean emergency : new boolean[]{false, true})
		{
			Replay r = new Replay(); r.prepare(); r.frame.emergencySceneActive = emergency; r.frame.outfitBlocked = !emergency;
			r.next(); r.frame.emergencySceneActive = false; r.frame.outfitBlocked = false; r.next(); r.next();
			SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void missingMalformedAndUnknownEquipmentCopiesRebaselineSilently()
	{
		for (int missing = 0; missing < 3; missing++)
		{
			Replay r = new Replay(); r.prepare(); int[] complete = r.frame.equipment.clone();
			r.frame.equipment = missing == 0 ? null : missing == 1 ? new int[]{100, 200, 300} : new int[]{-2, 200, 300, 400};
			r.next(); r.frame.equipment = complete; r.next(); r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void absenceHiddenAutoabsenceDeathAndLoadingCannotPreserveFailures()
	{
		for (int loss = 0; loss < 6; loss++)
		{
			Replay r = new Replay(); r.prepare();
			if (loss == 0) { r.frame.present = false; } if (loss == 1) { r.frame.userHidden = true; }
			if (loss == 2) { r.frame.autoAbsent = true; } if (loss == 3) { r.frame.dead = true; }
			if (loss == 4) { r.frame.valid = false; } if (loss == 5) { r.frame.currentHp = 0; }
			r.next(); r.frame.present = true; r.frame.userHidden = false; r.frame.autoAbsent = false;
			r.frame.dead = false; r.frame.valid = true; r.frame.currentHp = 99; r.next(); r.next();
			SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void sessionSceneViewAndPlaneChangesCancelAllHistoricalEvidence()
	{
		for (int loss = 0; loss < 4; loss++)
		{
			Replay r = new Replay(); r.prepare();
			if (loss == 0) { r.frame.sessionGeneration++; } if (loss == 1) { r.frame.sceneRevision++; }
			if (loss == 2) { r.frame.worldViewId++; }
			if (loss == 3) { r.frame.player = new Position(3100, 3200, 1); r.frame.companion = new Position(3103, 3200, 1); }
			r.next(); r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void anUnchangedSceneWithANewPresenceEpochCannotReclaimOldExecutions()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate old = r.base("M09"); r.frame.presentSinceTick = r.frame.tick;
		for (int i = 0; i < 12; i++) { r.next(); }
		r.rules.onCancellation(new Cancellation(old, 4, r.frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, true));
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void confirmedAndUnknownCombatConsumeThePattern()
	{
		for (CombatState combat : new CombatState[]{CombatState.CONFIRMED, CombatState.UNKNOWN})
		{
			Replay r = new Replay(); r.prepare(); r.combat = combat; r.next(); r.combat = CombatState.NO_CONFIRMED_EVIDENCE;
			r.next(); r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void firstArrivalRechecksTheExactTenTickDamageBoundary()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09"));
		r.lastDamage = r.frame.tick - 9; assertFalse(r.valid(quiet)); r.lastDamage = r.frame.tick - 10; assertTrue(r.valid(quiet));
	}

	@Test public void unknownViewAndMismatchedCompanionPlaneBlockFirstArrival()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09"));
		r.frame.worldViewId = Integer.MIN_VALUE; assertFalse(r.valid(quiet)); r.frame.worldViewId = 1;
		r.frame.companion = new Position(3103, 3200, 1); assertFalse(r.valid(quiet));
	}

	@Test public void activeCatchupAndUnknownCatchupEvidenceConsumeThePattern()
	{
		for (boolean known : new boolean[]{false, true})
		{
			Replay r = new Replay(); r.prepare(); MotionObservation.Builder motion = new MotionObservation.Builder();
			motion.purpose = MotionObservation.Purpose.CATCHUP; motion.known = known; r.frame.motion = motion.build();
			r.next(); r.frame.motion = null; r.next(); r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void startupAndDiscontinuousCommitsCannotCountUninitializedAttempts()
	{
		Replay startup = new Replay(false); startup.cancel(1); startup.cancel(2);
		SceneCandidate base = startup.base("M09"); assertSame(base, startup.decorate(base));
		Replay gap = new Replay(); gap.prepare(); gap.frame.tick++; gap.next(); gap.next();
		base = gap.base("M09"); assertSame(base, gap.decorate(base));
	}

	@Test public void onlyTheMostRecentThreeDistinctExecutionsRemainRetained()
	{
		Replay r = new Replay(); for (int i = 1; i <= 4; i++) { r.cancel(i); }
		SceneCandidate quiet = r.decorate(r.base("M09")); assertEquals("M47", quiet.getScenarioId());
		assertEquals(Long.toString(r.firstCancellation + 2), quiet.getConditionFacts().get("quietFirstCancellationTick"));
		assertEquals(Long.toString(r.frame.tick - 1), quiet.getConditionFacts().get("quietLastCancellationTick"));
	}

	@Test public void missingWitnessEpochWrongCauseAndUnwitnessedOccurrenceNeverQualify()
	{
		for (int missing = 0; missing < 5; missing++)
		{
			Replay r = new Replay(); r.cancel(1); r.cancel(2); SceneCandidate old = r.base("M09");
			Map<String, String> facts = new HashMap<>(old.getConditionFacts()); String cause = null;
			if (missing == 0) { facts.remove("witnessPresenceEpoch"); } if (missing == 1) { facts.put("witnessedPresent", "false"); }
			if (missing == 2) { facts.put("occurredTick", "-1"); } if (missing == 3) { facts.put("outfitRevision", "unknown"); }
			if (missing == 4) { cause = "callback:3"; }
			old = copy(old, 1, 1, cause, facts); r.frame.equipment[0]++; r.next();
			r.rules.onCancellation(new Cancellation(old, 3, r.frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, true)); r.next();
			SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void anExpiredPrepaintAppraisalDoesNotSupplyTheThirdCancellation()
	{
		Replay r = new Replay(); r.cancel(1); r.cancel(2); SceneCandidate old = r.base("M09");
		old = new SceneCandidate(old.getId(), "M09", 25, old.getCreatedTick(), old.getCreatedTick() + 1, 1, 1,
			old.getCausalToken(), old.getConditionFacts(), false, 6);
		r.frame.equipment[0]++; r.next(); r.rules.onCancellation(new Cancellation(old, 3, r.frame.tick,
			Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, true)); r.next();
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
	}

	@Test public void aCancellationWithoutAKnownCopiedFourSlotOutfitCannotSupplyTheThirdWitness()
	{
		for (String tuple : new String[]{null, "100,200,300", "-2,200,300,400", "100,unknown,300,400"})
		{
			Replay r = new Replay(); r.cancel(1); r.cancel(2); SceneCandidate old = r.base("M09");
			Map<String, String> facts = new HashMap<>(old.getConditionFacts());
			if (tuple == null) { facts.remove("equipmentIds"); } else { facts.put("equipmentIds", tuple); }
			old = copy(old, 1, 1, null, facts); r.frame.equipment[0]++; r.next();
			r.rules.onCancellation(new Cancellation(old, 3, r.frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, true)); r.next();
			SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		}
	}

	@Test public void theCurrentBaseMustHaveTheExactTupleCauseAndOriginalPresenceWitness()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate base = r.base("M09");
		Map<String, String> facts = new HashMap<>(base.getConditionFacts()); facts.put("equipmentIds", "1,2,3,4");
		SceneCandidate wrongTuple = copy(base, 1, 1, null, facts); assertSame(wrongTuple, r.decorate(wrongTuple));
		SceneCandidate wrongCause = copy(base, 1, 1, "inventory:7", null); assertSame(wrongCause, r.decorate(wrongCause));
		facts = new HashMap<>(base.getConditionFacts()); facts.put("witnessPresenceEpoch", "2");
		SceneCandidate wrongEpoch = copy(base, 1, 1, null, facts); assertSame(wrongEpoch, r.decorate(wrongEpoch));
		assertEquals("M47", r.decorate(base).getScenarioId());
	}

	@Test public void aCopiedCandidateCannotForgeTheRetainedQuietProofByReusingItsId()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09"));
		SceneCandidate forged = new SceneCandidate(quiet.getId(), "M47", 29, quiet.getCreatedTick(), quiet.getExpiresTick(),
			1, 1, quiet.getCausalToken(), quiet.getConditionFacts(), true, 8);
		assertFalse(r.valid(forged)); assertTrue(r.valid(quiet));
	}

	@Test public void originalAdmissionExpiryRemainsStrictWhileACommittedHoldCanContinue()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate base = r.base("M44");
		base = new SceneCandidate(base.getId(), "M44", 27, base.getCreatedTick(), base.getCreatedTick() + 5, 1, 1,
			base.getCausalToken(), base.getConditionFacts(), false, 6);
		SceneCandidate quiet = r.decorate(base); assertEquals(base.getExpiresTick(), quiet.getExpiresTick());
		while (r.frame.tick < quiet.getExpiresTick() - 1) { r.next(); } assertTrue(r.valid(quiet)); r.next(); assertFalse(r.valid(quiet));
		assertTrue(r.rules.continuationValid(quiet, r.frame.build(), r.combat, r.lastDamage));
	}

	@Test public void aConsumedPatternCannotIssueAgainOrExtendAQuietDeadline()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09")); long expiry = quiet.getExpiresTick();
		for (int i = 0; i < 12; i++) { r.next(); SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base)); }
		assertEquals(expiry, quiet.getExpiresTick()); assertFalse(r.valid(quiet));
	}

	@Test public void unknownLedgerAfterReplacementIrreversiblyRevokesItsProof()
	{
		Replay r = new Replay(); r.prepare(); SceneCandidate quiet = r.decorate(r.base("M09"));
		r.rules.onCancellation(new Cancellation(r.base("M31"), 4, r.frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, false, true, false));
		assertFalse(r.valid(quiet)); r.cancel(5); assertFalse(r.valid(quiet));
	}

	@Test public void realDirectorReceiptsProduceOneSilentReplacementAndOnlyTheM09LogicalCommit() throws Exception
	{
		Replay r = new Replay(); DirectorFixture d = new DirectorFixture(r);
		for (int i = 1; i <= 3; i++) { d.cancelUnpainted(); }
		SceneCandidate base = r.base("M44"), quiet = r.decorate(base); assertEquals("M47", quiet.getScenarioId());
		d.tick(); int choices = d.choices.draws; assertTrue(d.director.offer(quiet)); assertSame(quiet, d.director.getActiveCandidate());
		assertEquals(0, d.director.pendingCount()); d.director.arrive(quiet.getId(), r.frame.tick);
		assertTrue(d.director.isIntentionalSilent()); assertNull(d.director.getPacket()); assertNull(d.director.getLastPaint());
		assertEquals(r.frame.tick + 8, d.director.getHoldUntilTick()); assertEquals(1, d.guard.interactionCount());
		assertEquals(0, d.guard.textCount()); assertEquals(choices, d.choices.draws); assertFalse(d.guard.interactionEligible("M09"));
		assertFalse(d.director.offer(r.base("M09"))); assertEquals(1, d.cooldowns.draws);
	}

	@Test public void realPaintThenRevisionLossThenDelayedAckNeverCountsAsTheThirdFailure() throws Exception
	{
		Replay r = new Replay(); DirectorFixture d = new DirectorFixture(r); d.cancelUnpainted(); d.cancelUnpainted();
		SceneCandidate old = r.base("M09"); d.tick(); assertTrue(d.director.offer(old)); d.director.arrive(old.getId(), r.frame.tick);
		SpeechPacket packet = d.director.getPacket(); assertNotNull(packet);
		SpeechPacket.PaintReceipt paint = packet.paint(d.clock.get(), r.frame.tick, 1, () -> {}); assertNotNull(paint);
		d.tickNextBeforeCopies(); r.frame.equipment[0]++; r.next();
		d.director.invalidate(false, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED); r.next();
		assertTrue(d.receipts.get(2).isGenuinePainted()); assertFalse(d.receipts.get(2).isPrePaintAttempt());
		SceneCandidate base = r.base("M09"); assertSame(base, r.decorate(base));
		d.director.acknowledge(packet, paint); d.director.acknowledge(packet, paint);
		assertSame(paint, d.director.getLastPaint()); assertEquals(1, d.guard.textCount()); assertEquals(1, d.cooldowns.draws);
	}

	private static SceneCandidate copy(SceneCandidate base, long session, long scene, String cause, Map<String, String> facts)
	{
		return new SceneCandidate(base.getId(), base.getScenarioId(), base.getPriority(), base.getCreatedTick(), base.getExpiresTick(),
			session, scene, cause == null ? base.getCausalToken() : cause, facts == null ? base.getConditionFacts() : facts, false, 6);
	}

	private static final class Replay
	{
		final QuietOutfitRules rules = new QuietOutfitRules();
		final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		CombatState combat = CombatState.NO_CONFIRMED_EVIDENCE;
		long lastDamage = -10000, nextCandidate, fixedCandidateId, revision = 1, firstCancellation;
		Replay() { this(true); }
		Replay(boolean baseline)
		{
			frame.tick = 12; frame.worldViewId = 1; frame.player = new Position(3100, 3200, 0);
			frame.companion = new Position(3103, 3200, 0); frame.equipment = new int[]{100, 200, 300, 400};
			if (baseline) { rules.close(frame.build(), combat, lastDamage); next(); }
		}
		void next() { frame.tick++; rules.close(frame.build(), combat, lastDamage); }
		void prepare() { cancel(1); cancel(2); cancel(3); }
		void cancel(long execution) { cancel(execution, "M09", false, true, true, true); }
		void cancel(long execution, String scenario, boolean painted, boolean prepaint, boolean known, boolean settle)
		{
			SceneCandidate old = base(scenario); frame.equipment[0]++; revision++; next();
			if (firstCancellation == 0) { firstCancellation = frame.tick; }
			rules.onCancellation(new Cancellation(old, execution, frame.tick, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED, painted, prepaint, known));
			if (settle) { next(); }
		}
		SceneCandidate base(String scenario)
		{
			Map<String, String> facts = new HashMap<>(); facts.put("witnessedPresent", "true");
			facts.put("occurredTick", Long.toString(frame.tick)); facts.put("witnessPresenceEpoch", Long.toString(frame.presentSinceTick));
			facts.put("outfitRevision", Long.toString(revision)); facts.put("settlingNotBeforeTick", Long.toString(frame.presentSinceTick + 10));
			facts.put("equipmentIds", tupleKey(frame.equipment)); facts.put("changedSlot", "HEAD"); facts.put("changedSlots", "HEAD");
			facts.put("priorEquipmentIds", "100,200,300,400");
			return new SceneCandidate(fixedCandidateId > 0 ? fixedCandidateId : ++nextCandidate, scenario, 25, frame.tick, frame.tick + 12,
				frame.sessionGeneration, frame.sceneRevision, "outfit:" + revision, facts, false, 6);
		}
		SceneCandidate decorate(SceneCandidate base)
		{
			return rules.decorate(frame.build(), base, combat, lastDamage,
				(f, id, priority, life, cause, facts, silent, hold) -> { throw new AssertionError("Replacement must preserve existing candidate identity"); });
		}
		boolean valid(SceneCandidate candidate) { return rules.stillValid(candidate, frame.build(), combat, lastDamage); }
	}
	private static String tupleKey(int[] items)
	{ return items == null || items.length != 4 ? "" : items[0] + "," + items[1] + "," + items[2] + "," + items[3]; }

	private static final class CountingRandom extends Random
	{
		int draws;
		@Override public int nextInt(int bound) { draws++; return 0; }
	}
	private static final class DirectorFixture
	{
		final Replay replay;
		final AtomicLong clock = new AtomicLong();
		final CountingRandom choices = new CountingRandom(), cooldowns = new CountingRandom();
		final List<SpeechCancellationReceipt> receipts = new ArrayList<>();
		final SpeechGuard guard;
		final SpeechDirector director;
		DirectorFixture(Replay r) throws Exception
		{
			replay = r; DialogueCatalog catalogue;
			try (InputStream input = getClass().getResourceAsStream("/com/malfoid/dialogue.json"))
			{
				JsonObject runtime = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				runtime.getAsJsonArray("runtimeEnabledScenarios").add("M47");
				runtime.getAsJsonObject("silencePercentByScenario").addProperty("M09", 0);
				catalogue = DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
			}
			guard = new SpeechGuard(clock::get, cooldowns, catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
			director = new SpeechDirector(catalogue, clock::get, choices, ignored -> {}, guard);
			director.setCandidateValidator(c -> tupleKey(r.frame.equipment).equals(c.getConditionFacts().get("equipmentIds")));
			director.setCancellationListener(receipt -> { receipts.add(receipt); r.rules.onCancellation(receipt); });
			director.setFirstPaintListener(r.rules::onFirstPaint);
		}
		void tick() { director.tick(replay.frame.tick, replay.frame.sessionGeneration, replay.frame.sceneRevision, true, true, false); }
		void tickNextBeforeCopies()
		{ director.tick(replay.frame.tick + 1, replay.frame.sessionGeneration, replay.frame.sceneRevision, true, true, false); }
		void cancelUnpainted()
		{
			SceneCandidate old = replay.base("M09"); tick(); assertTrue("Appraisal admission at tick " + replay.frame.tick, director.offer(old));
			tickNextBeforeCopies(); replay.frame.equipment[0]++; replay.revision++; replay.next();
			director.invalidate(false, Reason.DEPENDENT_OUTFIT_REVISION_CHANGED);
			// The real no-paint retry remains five ticks, including M47's shared M09 repeat key.
			long retryTick = replay.frame.tick + 5;
			do { tickNextBeforeCopies(); replay.next(); } while (replay.frame.tick < retryTick);
		}
	}
}
