package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Genuine terminal route facts; no search, visual interpolation, or callback can invent an arrival. */
final class TravelContinuationRules
{
	private final Map<String, RouteWitness> issued = new HashMap<>();
	private long lastTick = Long.MIN_VALUE, lastSession, lastScene, lastPresence, lastPlacement = -1, lastCompletion = -1;
	private long lastCancelledExecution = -1;
	private long motionWitnessSinceTick = Long.MIN_VALUE;
	private CancelledInterestObservation pendingInterest;

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, List<SkillDelta> deltas,
		CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		if (f.tick == lastTick && f.sessionGeneration == lastSession && f.sceneRevision == lastScene
			&& f.presentSinceTick == lastPresence) { return out; }
		boolean consecutive = lastTick == Long.MIN_VALUE || f.tick == lastTick + 1 && f.sessionGeneration == lastSession
			&& f.sceneRevision == lastScene && f.presentSinceTick == lastPresence && f.placementGeneration == lastPlacement;
		if (!consecutive || !continuous(f, prior) || batch.localDeath || suppressed(f, combat))
		{
			reset(); motionWitnessSinceTick = f.tick + 1;
			if (f.cancelledInterest != null && f.cancelledInterest.cancellation != null)
			{
				lastCancelledExecution = f.cancelledInterest.cancellation.getExecutionId();
			}
			remember(f); return out;
		}
		remember(f);
		issued.entrySet().removeIf(entry -> !conditions(entry.getValue(), f, combat, false));
		cancelledInterest(f, factory, out);
		MotionObservation completion = f.motionCompletion;
		if (!freshCompletion(f, completion) || completion.requestId == lastCompletion) { return out; }
		lastCompletion = completion.requestId;
		if (!completion.fixedGoal) { return out; }
		if (completion.physicalBlock
			&& completion.previouslyLegalEdge && completion.changedEdgeAfterBlock
			&& completion.blockedTick >= completion.startedTick && completion.blockedTick >= f.presentSinceTick
			&& completion.completedTick > completion.blockedTick && completion.completedTick - completion.blockedTick <= 12
			&& f.tick - completion.blockedTick < 20 && completion.lastCompletedEdgeTick > completion.blockedTick
			&& adjacentFailedEdge(completion))
		{
			emit(f, "M61", 19, completion, factory, out);
		}
		int chord = Math.max(Math.abs(completion.goalWorldX - completion.startWorldX), Math.abs(completion.goalWorldY - completion.startWorldY));
		// D.3 idle shifts cannot supply twelve edges; their M63 runtime lane remains closed.
		if ((completion.purpose == MotionObservation.Purpose.ROAM || completion.purpose == MotionObservation.Purpose.APPROACH_PLAYER)
			&& chord >= 1 && chord <= 4
			&& completion.completedEdges >= 12 && completion.completedEdges <= 24
			&& completion.completedTick - completion.startedTick <= 12)
		{
			emit(f, "M63", 15, completion, factory, out);
		}
		return out;
	}

	private void cancelledInterest(ScenarioSnapshot f, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		CancelledInterestObservation current = f.cancelledInterest;
		if (!interestCurrent(current, f) || f.tick - current.lossTick > 16 || current.lossTick < motionWitnessSinceTick
			|| current.inspectionPath.startedTick < motionWitnessSinceTick || !pendingReturnCurrent(current, f))
		{
			if (current != null && current.cancellation != null) { lastCancelledExecution = current.cancellation.getExecutionId(); }
			else if (pendingInterest != null) { lastCancelledExecution = pendingInterest.cancellation.getExecutionId(); }
			pendingInterest = null; return;
		}
		if (current.cancellation.getExecutionId() == lastCancelledExecution) { return; }
		if (pendingInterest == null)
		{
			if (current.lossTick < f.tick - 1) { return; }
			pendingInterest = current;
		}
		else if (!sameInterest(pendingInterest, current) || pendingInterest.returnRequestId >= 0
			&& pendingInterest.returnRequestId != current.returnRequestId)
		{
			lastCancelledExecution = pendingInterest.cancellation.getExecutionId(); pendingInterest = null; return;
		}
		else { pendingInterest = current; }
		MotionObservation returned = f.motionCompletion;
		if (!freshReturn(f, current, returned)) { return; }
		lastCancelledExecution = current.cancellation.getExecutionId(); pendingInterest = null;
		Map<String, String> facts = terminalFacts(f, returned, 12);
		facts.put("historicalObjectName", current.historicalName); facts.put("targetLossReason", current.lossReason.name());
		facts.put("cancelledInspectionId", Long.toString(current.inspectionCandidate.getId()));
		facts.put("cancelledInspectionRequestId", Long.toString(current.inspectionPath.requestId));
		facts.put("cancelledExecutionId", Long.toString(current.cancellation.getExecutionId())); facts.put("targetLossTick", Long.toString(current.lossTick));
		SceneCandidate c = factory.create(f, "M62", 14, 10, "interest-return:" + current.inspectionCandidate.getId()
			+ ":" + current.cancellation.getExecutionId(), facts, false, 4);
		issued.put("M62", new RouteWitness(c, returned, current)); out.add(c);
	}

	private static boolean interestCurrent(CancelledInterestObservation interest, ScenarioSnapshot f)
	{
		if (interest == null || !interest.known || interest.inspectionArrived || interest.inspectionCandidate == null
			|| interest.inspectionPath == null || interest.cancellation == null || interest.lossReason == null
			|| interest.targetKey == null || interest.historicalName == null || interest.targetKey.isEmpty()
			|| ScenarioRules.objectKind(interest.historicalName) == null || interest.presentSinceTick != f.presentSinceTick
			|| interest.placementGeneration != f.placementGeneration || interest.worldViewId != f.worldViewId || interest.plane != f.player.plane
			|| interest.lossTick < f.presentSinceTick || interest.lossTick > f.tick) { return false; }
		SceneCandidate selected = interest.inspectionCandidate; MotionObservation path = interest.inspectionPath;
		SpeechCancellationReceipt cancellation = interest.cancellation; Map<String, String> facts = selected.getConditionFacts();
		return "M08".equals(selected.getScenarioId()) && selected.getSessionGeneration() == f.sessionGeneration
			&& selected.getSceneRevision() == f.sceneRevision && selected.getCreatedTick() >= f.presentSinceTick
			&& interest.lossTick < selected.getExpiresTick() && TravelExpansionRules.sameCandidate(selected, cancellation.getCandidate())
			&& cancellation.getReason() == SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST
			&& cancellation.isPrePaintAttempt() && !cancellation.isGenuinePainted()
			&& interest.targetKey.equals(facts.get("targetKey")) && interest.historicalName.equals(facts.get("objectName"))
			&& Long.toString(f.presentSinceTick).equals(facts.get("witnessPresenceEpoch"))
			&& path.known && !path.ordinary && path.purpose == MotionObservation.Purpose.OTHER
			&& path.sourceCandidateId == selected.getId() && path.legalGoal && path.fixedGoal
			&& path.status == MotionObservation.Status.MOVING && path.completedTick < 0 && path.completedEdges >= 0 && path.completedEdges <= 8
			&& path.completedRunEdges == 0 && (path.completedEdges == 0 || path.lastCompletedEdgeTick >= path.startedTick && path.lastCompletedEdgeTick <= interest.lossTick)
			&& path.plannedSteps >= 1 && path.plannedSteps <= 8 && path.requestId >= 0 && path.startedTick >= selected.getCreatedTick()
			&& path.startedTick <= interest.lossTick && path.placementGeneration == interest.placementGeneration
			&& path.worldViewId == interest.worldViewId && path.plane == interest.plane
			&& Integer.toString(path.goalWorldX).equals(facts.get("targetX")) && Integer.toString(path.goalWorldY).equals(facts.get("targetY"));
	}

	private static boolean pendingReturnCurrent(CancelledInterestObservation interest, ScenarioSnapshot f)
	{
		if (interest.returnRequestId < 0) { return true; }
		MotionObservation current = f.motion;
		return current != null && current.known && current.ordinary && current.legalGoal
			&& current.purpose == MotionObservation.Purpose.APPROACH_PLAYER && current.sourceCandidateId == interest.inspectionCandidate.getId()
			&& current.requestId == interest.returnRequestId && current.requestId != interest.inspectionPath.requestId
			&& current.startedTick >= interest.lossTick && current.startedTick <= f.tick
			&& (current.status == MotionObservation.Status.MOVING || current.status == MotionObservation.Status.ARRIVED)
			&& current.placementGeneration == interest.placementGeneration && current.worldViewId == interest.worldViewId && current.plane == interest.plane;
	}

	private static boolean sameInterest(CancelledInterestObservation a, CancelledInterestObservation b)
	{
		MotionObservation x = a.inspectionPath, y = b.inspectionPath;
		return TravelExpansionRules.sameCandidate(a.inspectionCandidate, b.inspectionCandidate)
			&& a.cancellation.getExecutionId() == b.cancellation.getExecutionId() && a.cancellation.getIssuedPacketToken() == b.cancellation.getIssuedPacketToken()
			&& a.lossTick == b.lossTick && a.lossReason == b.lossReason && a.targetKey.equals(b.targetKey) && a.historicalName.equals(b.historicalName)
			&& a.presentSinceTick == b.presentSinceTick && a.placementGeneration == b.placementGeneration && a.worldViewId == b.worldViewId && a.plane == b.plane
			&& x.requestId == y.requestId && x.startedTick == y.startedTick && x.completedTick == y.completedTick && x.completedEdges == y.completedEdges
			&& x.sourceCandidateId == y.sourceCandidateId && x.completedRunEdges == y.completedRunEdges && x.lastCompletedEdgeTick == y.lastCompletedEdgeTick
			&& x.plannedSteps == y.plannedSteps && x.goalWorldX == y.goalWorldX && x.goalWorldY == y.goalWorldY
			&& x.startWorldX == y.startWorldX && x.startWorldY == y.startWorldY;
	}

	private static boolean freshReturn(ScenarioSnapshot f, CancelledInterestObservation interest, MotionObservation returned)
	{
		return returned != null && returned.known && returned.ordinary && returned.legalGoal
			&& returned.purpose == MotionObservation.Purpose.APPROACH_PLAYER && returned.sourceCandidateId == interest.inspectionCandidate.getId()
			&& returned.status == MotionObservation.Status.ARRIVED && returned.requestId >= 0 && returned.requestId == interest.returnRequestId
			&& returned.requestId != interest.inspectionPath.requestId && returned.completedTick == f.tick
			&& returned.startedTick >= interest.lossTick && returned.startedTick <= returned.completedTick
			&& returned.completedTick - interest.lossTick <= 16 && returned.completedEdges >= 1 && returned.completedEdges <= 24
			&& returned.completedRunEdges == 0
			&& returned.lastCompletedEdgeTick >= returned.startedTick && returned.lastCompletedEdgeTick <= returned.completedTick
			&& returned.arrivalDistance >= 2 && returned.arrivalDistance <= 5
			&& returned.worldViewId == interest.worldViewId && returned.placementGeneration == interest.placementGeneration && returned.plane == interest.plane
			&& matchingCurrent(f, returned);
	}

	private static boolean adjacentFailedEdge(MotionObservation completion)
	{
		int dx = Math.abs(completion.blockedToWorldX - completion.blockedFromWorldX);
		int dy = Math.abs(completion.blockedToWorldY - completion.blockedFromWorldY);
		return dx <= 1 && dy <= 1 && dx + dy > 0;
	}

	private boolean freshCompletion(ScenarioSnapshot f, MotionObservation completion)
	{
		return completion != null && completion.known && completion.ordinary && completion.legalGoal && completion.requestId >= 0
			&& (completion.purpose == MotionObservation.Purpose.ROAM || completion.purpose == MotionObservation.Purpose.CATCHUP
				|| completion.purpose == MotionObservation.Purpose.APPROACH_PLAYER && completion.sourceCandidateId >= 0)
			&& completion.status == MotionObservation.Status.ARRIVED && completion.completedTick == f.tick
			&& completion.startedTick >= f.presentSinceTick && completion.startedTick >= motionWitnessSinceTick
			&& completion.startedTick <= completion.completedTick
			&& completion.completedEdges > 0 && completion.completedEdges <= 24
			&& completion.lastCompletedEdgeTick >= completion.startedTick && completion.lastCompletedEdgeTick <= completion.completedTick
			&& completion.arrivalDistance >= 2 && completion.arrivalDistance <= 5
			&& completion.placementGeneration == f.placementGeneration && completion.worldViewId == f.worldViewId
			&& completion.plane == f.player.plane && matchingCurrent(f, completion);
	}

	/** A fixed goal may not be replaced while this terminal opportunity waits for admission. */
	private static boolean matchingCurrent(ScenarioSnapshot f, MotionObservation original)
	{
		MotionObservation current = f.motion;
		return TravelExpansionRules.sameCompletion(original, current)
			&& current.placementGeneration == f.placementGeneration && current.worldViewId == f.worldViewId && current.plane == f.player.plane
			&& new Position(original.goalWorldX, original.goalWorldY, original.plane).equals(f.companion)
			&& f.player.distance(f.companion) >= 2 && f.player.distance(f.companion) <= 5;
	}

	boolean stillValid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		return valid(c, f, combat, true);
	}
	boolean continuationValid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		return valid(c, f, combat, false);
	}
	long motionRequestId(SceneCandidate c)
	{
		RouteWitness retained = issued.get(c.getScenarioId());
		return retained != null && TravelExpansionRules.sameCandidate(c, retained.candidate) ? retained.completion.requestId : -1;
	}
	private boolean valid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean deadline)
	{
		if (!("M61".equals(c.getScenarioId()) || "M62".equals(c.getScenarioId()) || "M63".equals(c.getScenarioId()))) { return true; }
		RouteWitness retained = issued.get(c.getScenarioId());
		return retained != null && TravelExpansionRules.sameCandidate(c, retained.candidate) && conditions(retained, f, combat, deadline);
	}
	private static boolean conditions(RouteWitness witness, ScenarioSnapshot f, CombatState combat, boolean deadline)
	{
		SceneCandidate c = witness.candidate;
		if (!healthy(f) || suppressed(f, combat) || f.tick < c.getCreatedTick()
			|| deadline && (f.tick >= c.getExpiresTick() || f.tick - f.presentSinceTick < 10)
			|| f.sessionGeneration != c.getSessionGeneration() || f.sceneRevision != c.getSceneRevision()
			|| !Long.toString(f.presentSinceTick).equals(c.getConditionFacts().get("witnessPresenceEpoch"))
			|| f.player.distance(f.companion) < 2 || f.player.distance(f.companion) > 5) { return false; }
		if ("M61".equals(c.getScenarioId()) && f.tick - witness.completion.blockedTick >= 20) { return false; }
		if (witness.interest != null && (!interestCurrent(f.cancelledInterest, f) || !sameInterest(witness.interest, f.cancelledInterest)
			|| witness.interest.returnRequestId != f.cancelledInterest.returnRequestId)) { return false; }
		return matchingCurrent(f, witness.completion) || TravelExpansionRules.ownedHold(c, f, witness.completion);
	}

	private static boolean healthy(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && f.presentSinceTick >= 0 && f.tick >= f.presentSinceTick
			&& !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0 && f.player != null && f.companion != null
			&& f.player.plane >= 0 && f.player.plane <= 3 && f.companion.plane == f.player.plane
			&& f.placementGeneration >= 0 && f.worldViewId != Integer.MIN_VALUE;
	}
	private static boolean suppressed(ScenarioSnapshot f, CombatState combat)
	{
		return f.emergencySceneActive || f.inventoryBlocked || !f.interactionKnown || f.interactionPresent
			|| combat != CombatState.NO_CONFIRMED_EVIDENCE;
	}
	private static boolean continuous(ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		return healthy(f) && healthy(prior) && f.tick == prior.tick + 1 && f.sessionGeneration == prior.sessionGeneration
			&& f.sceneRevision == prior.sceneRevision && f.presentSinceTick == prior.presentSinceTick && prior.tick >= f.presentSinceTick
			&& f.topLevel == prior.topLevel && f.instanced == prior.instanced && f.player.distance(prior.player) <= 2
			&& f.placementGeneration == prior.placementGeneration && f.worldViewId == prior.worldViewId;
	}
	private void remember(ScenarioSnapshot f)
	{
		lastTick = f.tick; lastSession = f.sessionGeneration; lastScene = f.sceneRevision;
		lastPresence = f.presentSinceTick; lastPlacement = f.placementGeneration;
	}
	private void emit(ScenarioSnapshot f, String id, int priority, MotionObservation completion,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		Map<String, String> facts = terminalFacts(f, completion, 4);
		facts.put("blockedTick", Long.toString(completion.blockedTick));
		SceneCandidate c = factory.create(f, id, priority, 10, "route:" + completion.requestId, facts, false, 4);
		issued.put(id, new RouteWitness(c, completion, null)); out.add(c);
	}
	private static Map<String, String> terminalFacts(ScenarioSnapshot f, MotionObservation completion, int approach)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region()));
		facts.put("witnessedPresent", "true"); facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		facts.put("settlingNotBeforeTick", Long.toString(f.presentSinceTick + 10)); facts.put("occurredTick", Long.toString(f.tick));
		facts.put("maximumApproachTicks", Integer.toString(approach)); facts.put("requestId", Long.toString(completion.requestId));
		facts.put("completionTick", Long.toString(completion.completedTick)); facts.put("completedEdges", Integer.toString(completion.completedEdges));
		facts.put("goalWorldX", Integer.toString(completion.goalWorldX)); facts.put("goalWorldY", Integer.toString(completion.goalWorldY));
		facts.put("placementGeneration", Long.toString(completion.placementGeneration)); facts.put("worldViewId", Integer.toString(completion.worldViewId));
		return facts;
	}
	void reset()
	{
		issued.clear(); lastTick = Long.MIN_VALUE; lastCompletion = -1; lastPlacement = -1; lastCancelledExecution = -1;
		motionWitnessSinceTick = Long.MIN_VALUE; pendingInterest = null;
	}
	void reset(long invalidThroughTick)
	{
		reset(); motionWitnessSinceTick = invalidThroughTick + 1;
	}
	private static final class RouteWitness
	{
		final SceneCandidate candidate; final MotionObservation completion; final CancelledInterestObservation interest;
		RouteWitness(SceneCandidate candidate, MotionObservation completion, CancelledInterestObservation interest)
		{
			this.candidate = candidate; this.completion = completion; this.interest = interest;
		}
	}
}
