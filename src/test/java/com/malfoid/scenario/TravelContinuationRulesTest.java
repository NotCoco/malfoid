package com.malfoid.scenario;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionObservation.Purpose;
import com.malfoid.motion.MotionObservation.Status;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.find;
import static org.junit.Assert.*;

public class TravelContinuationRulesTest
{
	@Test public void m61RequiresAnActualPreviouslyLegalUnenteredEdgeAndAChangedRouteArrival()
	{
		Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 4, 2, 8); blocked(b, b.completedTick - 7);
		r.receipt(b); SceneCandidate c = find(r.next(), "M61"); assertNotNull(c); contract(c, 19);
		assertEquals("route:7", c.getCausalToken()); assertEquals(7, r.rules.motionRequestId(c));
		assertTrue(r.valid(c)); assertNull(find(r.next(), "M61")); assertTrue(r.valid(c));
	}

	@Test public void m61RejectsBudgetFrontierUnprovenChangedEdgesWrongGoalAndBlockedOnlyReceipts()
	{
		for (int invalid = 0; invalid < 12; invalid++)
		{
			Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 4, 2, 8); blocked(b, b.completedTick - 7);
			if (invalid == 0) { b.status = Status.BLOCKED; }
			if (invalid == 1) { b.status = Status.BUDGET_EXHAUSTED; }
			if (invalid == 2) { b.status = Status.SCENE_UNAVAILABLE; }
			if (invalid == 3) { b.physicalBlock = false; }
			if (invalid == 4) { b.previouslyLegalEdge = false; }
			if (invalid == 5) { b.changedEdgeAfterBlock = false; }
			if (invalid == 6) { b.fixedGoal = false; }
			if (invalid == 7) { b.known = false; }
			if (invalid == 8) { b.blockedToWorldX = b.blockedFromWorldX; }
			if (invalid == 9) { b.blockedToWorldX = b.blockedFromWorldX + 2; }
			if (invalid == 10) { b.lastCompletedEdgeTick = b.blockedTick; }
			if (invalid == 11) { b.completedEdges = 0; }
			r.receipt(b); assertNull("unproven replan " + invalid, find(r.next(), "M61"));
		}
	}

	@Test public void m61ArrivalAfterBlockIncludesTwelveTicksAndRejectsThirteenOrPrePresenceFailures()
	{
		for (int elapsed : new int[]{1, 12, 13})
		{
			Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 4, 2, 16); blocked(b, b.completedTick - elapsed);
			r.receipt(b); assertEquals(elapsed <= 12, find(r.next(), "M61") != null);
		}
		Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 4, 2, 8); blocked(b, r.frame.presentSinceTick - 1);
		r.receipt(b); assertNull(find(r.next(), "M61"));
	}

	@Test public void m63UsesActualTwelveToTwentyFourCompletedEdgesAndAOneToFourTileFixedChord()
	{
		for (int edges : new int[]{11, 12, 24, 25})
		{
			for (int chord : new int[]{0, 1, 4, 5})
			{
				Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, edges, chord, 12); r.receipt(b);
				SceneCandidate c = find(r.next(), "M63"); assertEquals("edges/chord " + edges + "/" + chord, edges >= 12 && edges <= 24 && chord >= 1 && chord <= 4, c != null);
				if (c != null) { contract(c, 15); assertEquals(Integer.toString(edges), c.getConditionFacts().get("completedEdges")); }
			}
		}
	}

	@Test public void m63APlanCannotSupplyCompletedStepsAndIndependentRequestsNeverAccumulate()
	{
		Replay planned = new Replay(); MotionObservation.Builder plan = terminal(planned.frame, 7, 6, 3, 12); plan.plannedSteps = 14; planned.receipt(plan);
		assertNull(find(planned.next(), "M63"));
		Replay r = new Replay(); r.receipt(terminal(r.frame, 7, 7, 3, 7)); assertNull(find(r.next(), "M63"));
		r.receipt(terminal(r.frame, 8, 7, 3, 7)); assertNull(find(r.next(), "M63"));
	}

	@Test public void m63RealArrivalMustFitItsTwelveTickBudgetAndKeepTheSameFixedGoal()
	{
		for (int duration : new int[]{12, 13})
		{
			Replay r = new Replay(); r.receipt(terminal(r.frame, 7, 12, 3, duration)); assertEquals(duration == 12, find(r.next(), "M63") != null);
		}
		for (int invalid = 0; invalid < 5; invalid++)
		{
			Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12);
			if (invalid == 0) { b.fixedGoal = false; }
			if (invalid == 1) { b.purpose = Purpose.OTHER; b.ordinary = false; }
			if (invalid == 2) { b.legalGoal = false; }
			if (invalid == 3) { b.status = Status.MOVING; }
			if (invalid == 4) { b.known = false; }
			r.receipt(b); assertNull(find(r.next(), "M63"));
		}
	}

	@Test public void m61AndM63AreTwoContextsOfOneGenuineCompletedRequest()
	{
		Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12); blocked(b, b.completedTick - 7); r.receipt(b);
		List<SceneCandidate> output = r.next(); SceneCandidate replan = find(output, "M61"), longRoute = find(output, "M63");
		assertNotNull(replan); assertNotNull(longRoute); assertEquals(replan.getCausalToken(), longRoute.getCausalToken());
		assertEquals(replan.getExpiresTick(), longRoute.getExpiresTick()); assertTrue(r.duplicate().isEmpty());
	}

	@Test public void terminalReceiptsCannotCrossUnknownTicksPresenceSceneViewPlaneOrPlacement()
	{
		for (int invalid = 0; invalid < 13; invalid++)
		{
			Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12);
			if (invalid == 0) { b.completedTick--; }
			if (invalid == 1) { b.startedTick = -1; }
			if (invalid == 2) { b.lastCompletedEdgeTick = b.completedTick + 1; }
			if (invalid == 3) { b.worldViewId = 2; }
			if (invalid == 4) { b.placementGeneration++; }
			if (invalid == 5) { b.plane = 1; }
			r.receipt(b);
			if (invalid == 6) { r.frame.tick++; }
			if (invalid == 7) { r.frame.present = false; }
			if (invalid == 8) { r.frame.sceneRevision++; }
			if (invalid == 9) { r.frame.sessionGeneration++; }
			if (invalid == 10) { r.combat = CombatState.UNKNOWN; }
			if (invalid == 11) { r.frame.inventoryBlocked = true; }
			if (invalid == 12) { r.frame.companion = null; }
			assertNull("invalid terminal receipt " + invalid, find(r.next(), "M63"));
		}
	}

	@Test public void changedGoalNewObstructionOrLossOfBandPermanentlyRevokesTheOriginalTerminalCause()
	{
		for (int invalid = 0; invalid < 4; invalid++)
		{
			Replay r = new Replay(); MotionObservation.Builder original = terminal(r.frame, 7, 12, 3, 12); r.receipt(original);
			SceneCandidate c = find(r.next(), "M63"); assertNotNull(c);
			MotionObservation.Builder changed = terminal(r.frame, 7, 12, 3, 12); changed.completedTick = original.completedTick;
			changed.startedTick = original.startedTick; changed.lastCompletedEdgeTick = original.lastCompletedEdgeTick;
			if (invalid == 0) { changed.goalWorldX++; }
			if (invalid == 1) { changed.status = Status.BLOCKED; }
			if (invalid == 2) { changed.requestId++; }
			if (invalid == 3) { r.frame.companion = new Position(r.frame.player.x + 6, r.frame.player.y, 0); }
			r.frame.motion = changed.build(); r.next(); assertFalse(r.valid(c));
			r.frame.motion = original.build(); r.frame.companion = new Position(original.goalWorldX, original.goalWorldY, 0); r.next(); assertFalse(r.valid(c));
			assertEquals(-1, r.rules.motionRequestId(c));
		}
	}

	@Test public void productionReducerCoalescesTheSharedRouteAndValidatesItsOwnHoldBeforePaint()
	{
		ScenarioReplay r = production(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12); blocked(b, b.completedTick - 7);
		r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion; List<SceneCandidate> output = r.next();
		SceneCandidate c = find(output, "M61"); assertNotNull(c); assertNull(find(output, "M63")); assertTrue(r.rules.stillValid(c));
		assertEquals(7, r.rules.motionRequestId(c)); MotionObservation original = r.frame.motionCompletion;
		r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original);
		r.frame.motionHold = hold(c, original, r.frame, c.getId(), true); r.next(); assertTrue(r.rules.stillValid(c));
		long deadline = c.getExpiresTick(); while (r.frame.tick < deadline - 1) { r.next(); assertTrue(r.rules.stillValid(c)); }
		r.next(); assertFalse(r.rules.stillValid(c)); assertTrue(r.rules.continuationValid(c)); assertEquals(deadline, c.getExpiresTick());
		r.frame.motionHold = hold(c, original, r.frame, c.getId() + 1, true); r.next(); assertFalse(r.rules.continuationValid(c));
		r.frame.motionHold = hold(c, original, r.frame, c.getId(), true); r.next(); assertFalse(r.rules.continuationValid(c));
	}

	@Test public void productionLongRouteRejectsOldInspectionReturnAndInvalidOwnedStanding()
	{
		for (boolean legal : new boolean[]{false, true})
		{
			ScenarioReplay r = production(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12); r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion;
			SceneCandidate c = find(r.next(), "M63"); assertNotNull(c); MotionObservation original = r.frame.motionCompletion;
			r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, c.getId(), legal);
			r.next(); assertEquals(legal, r.rules.stillValid(c));
		}
		ScenarioReplay r = production(); MotionObservation.Builder inspection = terminal(r.frame, 8, 12, 3, 12);
		inspection.purpose = Purpose.OTHER; inspection.ordinary = false; r.frame.motion = inspection.build(); r.frame.motionCompletion = r.frame.motion;
		List<SceneCandidate> output = r.next(); assertNull(find(output, "M61")); assertNull(find(output, "M62")); assertNull(find(output, "M63"));
	}

	@Test public void productionTargetNamesAndObjectDisappearanceAloneCannotInventAnAbandonedInspectionReturn()
	{
		ScenarioReplay r = production(); r.frame.objects = Collections.singletonList(new ScenarioSnapshot.InspectionTarget("same-scene-object", "Oak tree", 3104, 3200, 3104, 3200, 0, new Position(3103, 3200, 0), 3));
		assertNull(find(r.next(), "M62")); r.frame.objects = Collections.emptyList();
		MotionObservation.Builder b = terminal(r.frame, 7, 4, 3, 4); r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion;
		assertNull(find(r.next(), "M62"));
	}

	@Test public void finiteTerminalFactsDeferAtAgeNineAndKeepTheirOriginalDeadlineAtAgeTen()
	{
		Replay r = new Replay(); r.frame.presentSinceTick = r.frame.tick + 1; r.next(); for (int i = 0; i < 8; i++) { r.next(); }
		MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 8); r.receipt(b); SceneCandidate c = find(r.next(), "M63");
		assertNotNull(c); assertEquals(9, r.current.tick - r.current.presentSinceTick); assertFalse(r.valid(c)); long expiry = c.getExpiresTick();
		assertNull(find(r.next(), "M63")); assertTrue(r.valid(c)); assertEquals(expiry, c.getExpiresTick());
	}

	@Test public void aCalmOneEdgeRoamAndACatchupCannotSupplyTheLongOrdinaryApproachContext()
	{
		Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 1, 1, 1);
		b.purpose = Purpose.ROAM; r.receipt(b);
		List<SceneCandidate> output = r.next(); assertNull(find(output, "M61")); assertNull(find(output, "M63"));
		b = terminal(r.frame, 8, 12, 3, 12); b.purpose = Purpose.CATCHUP; r.receipt(b); assertNull(find(r.next(), "M63"));
	}

	@Test public void anOrdinaryApproachRequiresItsNativeSourceIdentity()
	{
		Replay r = new Replay(); MotionObservation.Builder b = terminal(r.frame, 7, 12, 3, 12);
		b.sourceCandidateId = -1; blocked(b, b.completedTick - 7); r.receipt(b);
		List<SceneCandidate> output = r.next(); assertNull(find(output, "M61")); assertNull(find(output, "M63"));
	}

	@Test public void cancelledUnarrivedInspectionReturnsOnItsOwnRequestAndOnlyItsNewSceneCanPaint() throws Exception
	{
		CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
		assertTrue(r.interest.cancellation.isPrePaintAttempt()); assertFalse(r.interest.cancellation.isGenuinePainted());
		SceneCandidate c = r.arrive(2, b -> {}); assertNotNull(c); assertEquals(14, c.getPriority());
		assertEquals(10, c.getExpiresTick() - c.getCreatedTick()); assertEquals(4, c.getHoldTicks());
		assertEquals("12", c.getConditionFacts().get("maximumApproachTicks"));
		assertEquals("interest-return:" + r.inspection.getId() + ":" + r.interest.cancellation.getExecutionId(), c.getCausalToken());
		assertEquals("Oak tree", c.getConditionFacts().get("historicalObjectName")); assertNull(c.getConditionFacts().get("targetKey"));
		assertEquals(42, r.replay.rules.motionRequestId(c)); assertTrue(r.replay.rules.stillValid(c));
		MotionObservation original = r.replay.frame.motionCompletion; r.ownHold(c, original); r.replay.next();
		assertTrue(r.replay.rules.stillValid(c)); r.director.setCandidateValidator(r.replay.rules::continuationValid);
		r.director.tick(r.replay.frame.tick, 1, 1, true, true, false); assertTrue(r.director.offer(c));
		r.director.arrive(c.getId(), r.replay.frame.tick); SpeechPacket packet = r.director.getPacket(); assertNotNull(packet);
		SpeechPacket.PaintReceipt paint = packet.paint(0, r.replay.frame.tick, 1, () -> {}); assertNotNull(paint);
		r.director.acknowledge(packet, paint); assertEquals(c.getId(), r.director.getLastPaint().getCandidate().getId());
		assertTrue("The abandoned target was never successfully inspected", r.replay.rules.canInspectIdentity("lost-oak"));
		long deadline = c.getExpiresTick(); while (r.replay.frame.tick < deadline) { assertNull(find(r.replay.next(), "M62")); }
		assertFalse(r.replay.rules.stillValid(c)); assertTrue(r.replay.rules.continuationValid(c)); assertEquals(deadline, c.getExpiresTick());
		r.replay.frame.motionHold = null; r.replay.next(); assertFalse(r.replay.rules.continuationValid(c));
		r.ownHold(c, original); r.replay.next(); assertFalse(r.replay.rules.continuationValid(c));
	}

	@Test public void aPaintedOrAlreadyArrivedInspectionAndAnUnrelatedCancellationCannotBecomeM62() throws Exception
	{
		for (int outcome = 0; outcome < 3; outcome++)
		{
			CancelledReplay r = new CancelledReplay(); r.cancel(outcome == 0
				? SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED : SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST,
				outcome == 2, outcome > 0);
			if (outcome == 2) { assertTrue(r.interest.cancellation.isGenuinePainted()); }
			assertNull("Not a pre-arrival target-loss episode " + outcome, r.arrive(2, b -> {}));
		}
	}

	@Test public void abandonedInspectionRequiresItsExactKeyedPathIdentityAndCompleteCurrentFacts() throws Exception
	{
		for (int invalid = 0; invalid < 14; invalid++)
		{
			CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
			if (invalid == 0) { r.interest.known = false; }
			if (invalid == 1) { r.interest.targetKey = "different-oak"; }
			if (invalid == 2) { r.interest.historicalName = "Willow tree"; }
			if (invalid == 3) { r.interest.inspectionArrived = true; }
			if (invalid == 4) { r.path.sourceCandidateId++; }
			if (invalid == 5) { r.path.status = Status.ARRIVED; r.path.completedTick = r.interest.cancellation.getCancelledAtTick(); }
			if (invalid == 6) { r.path.plannedSteps = 9; }
			if (invalid == 7) { r.path.completedRunEdges = 1; }
			if (invalid == 8) { r.path.lastCompletedEdgeTick++; }
			if (invalid == 9) { r.interest.placementGeneration++; }
			if (invalid == 10) { r.interest.worldViewId++; }
			if (invalid == 11) { r.interest.plane = 1; }
			if (invalid == 12) { r.interest.presentSinceTick++; }
			r.interest.inspectionPath = invalid == 13 ? null : r.path.build(); r.attach();
			assertNull("Unproven abandoned inspection " + invalid, r.arrive(2, b -> {}));
		}
	}

	@Test public void m62ReturnRequiresADistinctKeyedActualWalkArrivalRatherThanAPlanOrGenericCatchup() throws Exception
	{
		for (int invalid = 0; invalid < 13; invalid++)
		{
			final int kind = invalid; CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
			assertNull("Invalid return " + invalid, r.arrive(2, b -> {
				if (kind == 0) { b.requestId = r.path.requestId; }
				if (kind == 1) { b.sourceCandidateId++; }
				if (kind == 2) { b.purpose = Purpose.CATCHUP; }
				if (kind == 3) { b.completedEdges = 0; b.plannedSteps = 8; }
				if (kind == 4) { b.known = false; }
				if (kind == 5) { b.status = Status.MOVING; }
				if (kind == 6) { b.placementGeneration++; }
				if (kind == 7) { b.worldViewId++; }
				if (kind == 8) { b.goalWorldX++; }
				if (kind == 9) { b.completedTick--; }
				if (kind == 10) { b.ordinary = false; }
				if (kind == 11) { b.completedRunEdges = 1; }
				if (kind == 12) { b.completedEdges = 25; }
			}));
		}
	}

	@Test public void currentReturnLossConsumesTheTokenAndRestoringItsOldFactsCannotRearmIt() throws Exception
	{
		for (Status status : new Status[]{Status.BLOCKED, Status.BUDGET_EXHAUSTED, Status.RECOVERED, Status.INVALIDATED})
		{
			CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
			r.beginReturn(r.returnPath(2)); MotionObservation.Builder changed = r.returned(0, Status.MOVING); changed.status = status;
			r.replay.frame.motion = changed.build(); r.replay.next(); assertNull(r.finishReturn(b -> {}));
		}
	}

	@Test public void keyedReturnWindowIncludesSixteenTicksAndRejectsSeventeen() throws Exception
	{
		for (int elapsed : new int[]{16, 17})
		{
			CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
			SceneCandidate c = r.arrive(elapsed, b -> {}); assertEquals(elapsed == 16, c != null);
			if (c != null) { assertEquals("15", c.getConditionFacts().get("completedEdges")); }
		}
	}

	@Test public void aReturnAtSixteenKeepsItsOriginalPaintDeadlineAndItsOwnCommittedHold() throws Exception
	{
		CancelledReplay r = new CancelledReplay(); r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false);
		SceneCandidate c = r.arrive(16, b -> {}); assertNotNull(c); MotionObservation completedReturn = r.replay.frame.motionCompletion;
		long deadline = c.getExpiresTick();
		while (r.replay.frame.tick < deadline - 2) { assertNull(find(r.replay.next(), "M62")); assertTrue(r.replay.rules.stillValid(c)); }
		r.director.setCandidateValidator(r.replay.rules::continuationValid);
		r.director.tick(r.replay.frame.tick, 1, 1, true, true, false); assertTrue(r.director.offer(c));
		r.ownHold(c, completedReturn); r.replay.next(); assertTrue(r.replay.rules.stillValid(c));
		r.director.tick(r.replay.frame.tick, 1, 1, true, true, false); r.director.arrive(c.getId(), r.replay.frame.tick);
		SpeechPacket packet = r.director.getPacket(); assertNotNull(packet);
		SpeechPacket.PaintReceipt paint = packet.paint(0, r.replay.frame.tick, 1, () -> {}); assertNotNull(paint);
		r.director.acknowledge(packet, paint); assertEquals(25, paint.getPaintedAtTick() - r.lossTick);
		assertEquals(paint.getPaintedAtTick() + 4, r.director.getHoldUntilTick());
		while (r.replay.frame.tick < r.director.getHoldUntilTick() - 1)
		{
			assertNull(find(r.replay.next(), "M62")); assertFalse(r.replay.rules.stillValid(c));
			assertTrue(r.replay.rules.continuationValid(c)); r.director.tick(r.replay.frame.tick, 1, 1, true, true, false);
			assertEquals(c.getId(), r.director.getActiveCandidate().getId());
		}
		assertEquals(deadline, c.getExpiresTick()); assertFalse(r.director.isContinuationCancelled());
		r.replay.frame.cancelledInterest = null; r.replay.next(); assertFalse(r.replay.rules.continuationValid(c));
	}

	@Test public void anUnknownIntervalRejectsOldRouteCountersButAllowsAFreshKnownRequest()
	{
		Replay r = new Replay(); r.combat = CombatState.UNKNOWN; r.next(); r.combat = CombatState.NO_CONFIRMED_EVIDENCE;
		r.receipt(terminal(r.frame, 7, 12, 3, 12)); assertNull(find(r.next(), "M63"));
		for (int i = 0; i < 11; i++) { r.next(); }
		r.receipt(terminal(r.frame, 8, 12, 3, 12)); assertNotNull(find(r.next(), "M63"));
	}

	@Test public void coreResetsCannotRestoreOldBlockedRouteCountersInTheSameScope()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			ScenarioReplay r = production(); long oldStart = r.frame.tick;
			MotionObservation.Builder moving = terminal(r.frame, 7, 0, 3, 1); moving.purpose = Purpose.CATCHUP;
			moving.startedTick = oldStart; moving.completedTick = moving.lastCompletedEdgeTick = -1; moving.status = Status.MOVING;
			r.frame.motion = moving.build(); r.next();
			if (interruption == 0) { r.frame.targetUnknown = true; }
			if (interruption == 1) { r.frame.tick++; }
			if (interruption == 2) { r.frame.valid = false; }
			if (interruption == 3) { r.frame.inventoryBlocked = true; }
			r.next(); r.frame.targetUnknown = false; r.frame.valid = true; r.frame.inventoryBlocked = false;
			for (int i = 0; i < 3; i++) { r.next(); }
			MotionObservation.Builder old = terminal(r.frame, 7, 4, 3, 4); old.purpose = Purpose.CATCHUP;
			old.startedTick = oldStart; blocked(old, old.completedTick - 2);
			r.frame.motion = old.build(); r.frame.motionCompletion = r.frame.motion;
			assertNull("Old blocked route " + interruption, find(r.next(), "M61"));
			long newStart = r.frame.tick + 1; moving.requestId = 8; moving.startedTick = newStart; r.frame.motion = moving.build();
			for (int i = 0; i < 3; i++) { r.next(); }
			MotionObservation.Builder fresh = terminal(r.frame, 8, 4, 3, 4); fresh.purpose = Purpose.CATCHUP;
			fresh.startedTick = newStart; blocked(fresh, fresh.completedTick - 2);
			r.frame.motion = fresh.build(); r.frame.motionCompletion = r.frame.motion;
			SceneCandidate c = find(r.next(), "M61"); assertNotNull("Fresh blocked route " + interruption, c);
			assertEquals("route:8", c.getCausalToken());
		}
	}

	@Test public void targetLossDuringUnknownCombatCannotBecomeDeferredReturnNarration() throws Exception
	{
		CancelledReplay r = new CancelledReplay(); r.sourceUnknownAtLoss = true;
		r.cancel(SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST, false, false); r.replay.frame.targetUnknown = false;
		assertNull(r.arrive(2, b -> {}));
	}

	private static void contract(SceneCandidate c, int priority)
	{
		assertEquals(priority, c.getPriority()); assertEquals(10, c.getExpiresTick() - c.getCreatedTick()); assertEquals(4, c.getHoldTicks());
		assertEquals("4", c.getConditionFacts().get("maximumApproachTicks"));
	}
	private static MotionObservation.Builder terminal(ScenarioSnapshot.Builder frame, long requestId, int edges, int chord, int duration)
	{
		MotionObservation.Builder b = new MotionObservation.Builder(); b.requestId = requestId; b.sourceCandidateId = 100;
		b.purpose = Purpose.APPROACH_PLAYER; b.status = Status.ARRIVED;
		b.startedTick = frame.tick + 1 - duration; b.completedTick = frame.tick + 1; b.lastCompletedEdgeTick = b.completedTick;
		b.startWorldX = frame.companion.x + chord; b.startWorldY = frame.companion.y; b.goalWorldX = frame.companion.x; b.goalWorldY = frame.companion.y;
		b.startDistance = Math.max(2, 3 + chord); b.arrivalDistance = 3; b.completedEdges = edges; b.ordinary = true; b.known = true; b.legalGoal = true; b.fixedGoal = true;
		b.worldViewId = frame.worldViewId; b.placementGeneration = frame.placementGeneration; b.plane = frame.player.plane; return b;
	}
	private static void blocked(MotionObservation.Builder b, long tick)
	{
		b.physicalBlock = true; b.previouslyLegalEdge = true; b.changedEdgeAfterBlock = true; b.blockedTick = tick;
		b.blockedFromWorldX = b.startWorldX; b.blockedFromWorldY = b.startWorldY; b.blockedToWorldX = b.startWorldX - 1; b.blockedToWorldY = b.startWorldY;
	}
	private static MotionObservation invalidated(MotionObservation original)
	{
		MotionObservation.Builder b = new MotionObservation.Builder(); b.requestId = original.requestId; b.sourceCandidateId = original.sourceCandidateId; b.purpose = original.purpose;
		b.status = Status.INVALIDATED; b.ordinary = true; b.known = true; b.legalGoal = true; b.placementGeneration = original.placementGeneration;
		b.worldViewId = original.worldViewId; b.plane = original.plane; return b.build();
	}
	private static MotionHoldReceipt hold(SceneCandidate c, MotionObservation original, ScenarioSnapshot.Builder frame, long candidateId, boolean legal)
	{
		return new MotionHoldReceipt(candidateId, original, c.getCreatedTick(), frame.tick, original.placementGeneration,
			original.worldViewId, original.plane, frame.companion.x, frame.companion.y, legal);
	}
	private static ScenarioReplay production()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.placementGeneration = 1; r.frame.worldViewId = -1;
		for (int i = 0; i < 24; i++) { r.next(); } return r;
	}

	private static final class CancelledReplay
	{
		final ScenarioReplay replay = production(); final SceneCandidate inspection;
		final MotionObservation.Builder path = new MotionObservation.Builder();
		final CancelledInterestObservation.Builder interest = new CancelledInterestObservation.Builder();
		final List<SpeechCancellationReceipt> cancellations = new ArrayList<>(); final SpeechDirector director;
		List<Position> returningPath; long lossTick; int returnEdges; boolean sourceUnknownAtLoss;
		CancelledReplay() throws Exception
		{
			replay.frame.objects = Collections.singletonList(new ScenarioSnapshot.InspectionTarget("lost-oak", "Oak tree",
				3106, 3200, 3106, 3200, 0, new Position(3106, 3201, 0), 5));
			inspection = find(replay.next(), "M08"); assertNotNull(inspection);
			path.requestId = 41; path.sourceCandidateId = inspection.getId(); path.purpose = Purpose.OTHER; path.status = Status.MOVING;
			path.startedTick = replay.frame.tick + 1; path.startWorldX = 3103; path.startWorldY = 3200;
			path.goalWorldX = 3106; path.goalWorldY = 3201; path.plannedSteps = 5; path.known = path.legalGoal = path.fixedGoal = true;
			path.placementGeneration = 1; path.worldViewId = -1; path.plane = 0; replay.frame.motion = path.build(); replay.next();
			DialogueCatalog catalogue;
			try (InputStream input = getClass().getResourceAsStream("/com/malfoid/dialogue.json"))
			{
				JsonObject fixture = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
				fixture.getAsJsonArray("runtimeEnabledScenarios").add("M62");
				for (JsonElement entry : fixture.getAsJsonArray("entries"))
				{
					if ("M62".equals(entry.getAsJsonObject().get("scenario").getAsString())) { entry.getAsJsonObject().addProperty("status", "enabled"); }
				}
				catalogue = DialogueCatalog.read(new ByteArrayInputStream(fixture.toString().getBytes(StandardCharsets.UTF_8)));
			}
			Random choices = new Random() { @Override public int nextInt(int bound) { return bound - 1; } };
			SpeechGuard guard = new SpeechGuard(() -> 0L, new Random(0), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
			director = new SpeechDirector(catalogue, () -> 0L, choices, ignored -> {}, guard);
			director.setCancellationListener(cancellations::add); director.setCompletionListener(replay.rules::inspectionCompleted);
			director.tick(inspection.getCreatedTick(), 1, 1, true, true, false); assertTrue(director.offer(inspection));
		}
		void cancel(SpeechCancellationReceipt.Reason reason, boolean paint, boolean arrived)
		{
			Position[] partial = {new Position(3103, 3201, 0), new Position(3104, 3202, 0), new Position(3105, 3202, 0)};
			for (Position node : partial)
			{
				path.completedEdges++; path.lastCompletedEdgeTick = replay.frame.tick + 1; replay.frame.companion = node;
				replay.frame.motion = path.build(); replay.next();
			}
			lossTick = replay.frame.tick + 1; path.completedEdges++; path.lastCompletedEdgeTick = lossTick;
			replay.frame.companion = new Position(3106, 3202, 0); director.tick(lossTick, 1, 1, true, true, false);
			if (arrived) { director.arrive(inspection.getId(), lossTick); }
			if (paint)
			{
				SpeechPacket issued = director.getPacket(); assertNotNull(issued); assertNotNull(issued.paint(0, lossTick, 1, () -> {}));
			}
			director.invalidate(false, reason); assertEquals(1, cancellations.size());
			interest.inspectionCandidate = inspection; interest.inspectionPath = path.build(); interest.cancellation = cancellations.get(0);
			interest.lossReason = CancelledInterestObservation.LossReason.DESPAWNED; interest.targetKey = "lost-oak"; interest.historicalName = "Oak tree";
			interest.presentSinceTick = replay.frame.presentSinceTick; interest.placementGeneration = 1; interest.worldViewId = -1; interest.plane = 0;
			interest.inspectionArrived = arrived; replay.frame.objects = Collections.emptyList(); replay.frame.motion = path.build();
			replay.frame.targetUnknown = sourceUnknownAtLoss; attach(); replay.next();
		}
		void attach() { replay.frame.cancelledInterest = interest.build(); }
		List<Position> returnPath(int elapsed)
		{
			List<Position> nodes = new ArrayList<>();
			if (elapsed == 2) { nodes.add(new Position(3105, 3202, 0)); return nodes; }
			if (elapsed == 17) { nodes.add(new Position(3106, 3201, 0)); }
			for (int x = 3107; x <= 3109; x++) { nodes.add(new Position(x, 3202, 0)); }
			for (int y = 3203; y <= 3207; y++) { nodes.add(new Position(3109, y, 0)); }
			for (int x = 3108; x >= 3106; x--) { nodes.add(new Position(x, 3207, 0)); }
			for (int y = 3206; y >= 3204; y--) { nodes.add(new Position(3106, y, 0)); }
			nodes.add(new Position(3105, 3204, 0)); return nodes;
		}
		void beginReturn(List<Position> nodes)
		{
			returningPath = nodes; returnEdges = 0; interest.returnRequestId = 42; attach();
			replay.frame.motion = returned(0, Status.MOVING).build(); replay.frame.motionCompletion = null; replay.next();
		}
		MotionObservation.Builder returned(int completedEdges, Status status)
		{
			Position goal = returningPath.get(returningPath.size() - 1); MotionObservation.Builder b = new MotionObservation.Builder();
			b.requestId = 42; b.sourceCandidateId = inspection.getId(); b.purpose = Purpose.APPROACH_PLAYER; b.status = status;
			b.startedTick = lossTick + 1; b.completedTick = status == Status.ARRIVED ? replay.frame.tick + 1 : -1;
			b.lastCompletedEdgeTick = completedEdges > 0 ? replay.frame.tick + 1 : -1; b.completedEdges = completedEdges;
			b.startWorldX = 3106; b.startWorldY = 3202; b.goalWorldX = goal.x; b.goalWorldY = goal.y; b.startDistance = 6; b.arrivalDistance = 5;
			b.plannedSteps = returningPath.size(); b.ordinary = b.known = b.legalGoal = b.fixedGoal = true;
			b.worldViewId = -1; b.placementGeneration = 1; b.plane = 0; return b;
		}
		SceneCandidate arrive(int elapsed, Consumer<MotionObservation.Builder> change)
		{
			beginReturn(returnPath(elapsed)); return finishReturn(change);
		}
		SceneCandidate finishReturn(Consumer<MotionObservation.Builder> change)
		{
			List<SceneCandidate> result = Collections.emptyList();
			for (Position node : returningPath)
			{
				returnEdges++; replay.frame.companion = node; boolean terminal = returnEdges == returningPath.size();
				MotionObservation.Builder b = returned(returnEdges, terminal ? Status.ARRIVED : Status.MOVING); if (terminal) { change.accept(b); }
				replay.frame.motion = b.build(); if (terminal) { replay.frame.motionCompletion = replay.frame.motion; } result = replay.next();
			}
			return find(result, "M62");
		}
		void ownHold(SceneCandidate c, MotionObservation completion)
		{
			replay.frame.activeSceneCandidateId = c.getId(); replay.frame.motion = invalidated(completion);
			replay.frame.motionHold = new MotionHoldReceipt(c.getId(), completion, replay.frame.tick, replay.frame.tick,
				completion.placementGeneration, completion.worldViewId, completion.plane, replay.frame.companion.x, replay.frame.companion.y, true);
		}
	}

	private static final class Replay
	{
		final TravelContinuationRules rules = new TravelContinuationRules(); final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		ScenarioSnapshot current, prior; long nextCandidate; CombatState combat = CombatState.NO_CONFIRMED_EVIDENCE;
		Replay()
		{
			frame.player = new Position(3100, 3200, 0); frame.companion = new Position(3103, 3200, 0); frame.worldViewId = -1; frame.placementGeneration = 1;
			for (int i = 0; i < 24; i++) { next(); }
		}
		void receipt(MotionObservation.Builder b) { frame.motion = b.build(); frame.motionCompletion = frame.motion; }
		List<SceneCandidate> next()
		{
			frame.tick++; prior = current; current = frame.build();
			return rules.close(current, prior, ScenarioBatch.empty(), Collections.emptyList(), combat, Long.MIN_VALUE / 2, this::candidate);
		}
		List<SceneCandidate> duplicate() { return rules.close(current, prior, ScenarioBatch.empty(), Collections.emptyList(), combat, Long.MIN_VALUE / 2, this::candidate); }
		boolean valid(SceneCandidate c) { return rules.stillValid(c, current, combat, Long.MIN_VALUE / 2); }
		SceneCandidate candidate(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, boolean silent, int hold)
		{
			return new SceneCandidate(++nextCandidate, id, priority, f.tick, f.tick + life, f.sessionGeneration, f.sceneRevision, cause, facts, silent, hold);
		}
	}
}
