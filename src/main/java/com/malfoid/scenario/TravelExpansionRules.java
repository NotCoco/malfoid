package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Skill;

/** Finite witnessed travel patterns; only native completed-edge receipts prove her travel. */
final class TravelExpansionRules
{
	private final Deque<TileTick> positions = new ArrayDeque<>(), corner = new ArrayDeque<>();
	private final Deque<Endpoint> endpoints = new ArrayDeque<>();
	private final Deque<Run> runs = new ArrayDeque<>();
	private final List<Position> cycle = new ArrayList<>();
	private final Deque<Heading> headings = new ArrayDeque<>();
	private final Deque<Arrival> arrivals = new ArrayDeque<>();
	private final Deque<MotionObservation> wanderings = new ArrayDeque<>();
	private final Map<String, SceneCandidate> issued = new HashMap<>();
	private final Map<String, MotionObservation> motionWitnesses = new HashMap<>();
	private long lastTick = Long.MIN_VALUE, lastSession, lastScene, lastPresence;
	private long cycleStart, pauseStart = -1, headingStart, outsideTick = -1, witnessedArrivalTick = -1;
	private long lastCompletion = Long.MIN_VALUE, placement = Long.MIN_VALUE;
	private long motionWitnessSinceTick = Long.MIN_VALUE;
	private int cycleIndex, dwell, stillTicks, quietTicks, insideTicks, headingBin = -1, headingTicks, headingDirection;
	private boolean cycleSealed, quietConsumed, progressQuiet;
	private int movement;
	private MotionObservation catchup;
	private int catchupMoving;

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, List<SkillDelta> deltas,
		CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		if (f.tick == lastTick && f.sessionGeneration == lastSession && f.sceneRevision == lastScene
			&& f.presentSinceTick == lastPresence) { return out; }
		boolean consecutive = lastTick == Long.MIN_VALUE || f.tick == lastTick + 1
			&& f.sessionGeneration == lastSession && f.sceneRevision == lastScene && f.presentSinceTick == lastPresence;
		if (!consecutive || !continuous(f, prior) || batch.localDeath || combat != CombatState.NO_CONFIRMED_EVIDENCE || held(f))
		{
			boolean retainPause = quietConsumed && f.present && !f.userHidden && !f.autoAbsent && !f.dead && !batch.localDeath
				&& f.sessionGeneration == lastSession && f.sceneRevision == lastScene && f.presentSinceTick == lastPresence;
			reset(); motionWitnessSinceTick = f.tick + 1; quietConsumed = retainPause; remember(f); return out;
		}
		if (f.motion != null && f.motion.known && (f.motion.status == MotionObservation.Status.RECOVERED
			|| placement != Long.MIN_VALUE && placement != f.motion.placementGeneration))
		{
			reset(); motionWitnessSinceTick = f.tick + 1; remember(f); placement = f.motion.placementGeneration; return out;
		}
		remember(f);
		movement = f.player.distance(prior.player);
		progressQuiet = noProgress(f, prior);
		if (movement == 0)
		{
			dwell = Math.min(121, dwell + 1); stillTicks = Math.min(120, stillTicks + 1);
			if (pauseStart < 0) { pauseStart = f.tick; }
		}
		else
		{
			dwell = 1; stillTicks = 0; pauseStart = -1; quietTicks = 0; quietConsumed = false;
		}
		if (dwell == 0) { dwell = 1; }
		issued.entrySet().removeIf(entry -> !conditions(entry.getValue(), f, combat, false));
		motionWitnesses.keySet().retainAll(issued.keySet());
		positions.addLast(new TileTick(f.tick, f.player, movement));
		while (positions.size() > 120) { positions.removeFirst(); }
		boolean ready = !f.emergencySceneActive;
		shuttle(f, ready, factory, out);
		circuit(f, ready, factory, out);
		retrace(f, ready, factory, out);
		startsAndStops(f, ready, factory, out);
		travelPause(f, ready, factory, out);
		regionArrival(f, prior);
		facing(f, ready, factory, out);
		quietPause(f, ready, factory, out);
		nativeTravel(f, ready, factory, out);
		return out;
	}

	private void shuttle(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		while (!endpoints.isEmpty() && f.tick - endpoints.peekFirst().firstTick >= 50) { endpoints.removeFirst(); }
		if (dwell != 2 || !endpoints.isEmpty() && endpoints.peekLast().position.equals(f.player)) { return; }
		if (endpoints.size() >= 2)
		{
			Endpoint[] records = endpoints.toArray(new Endpoint[0]);
			Position a = records[0].position, b = records[1].position;
			if (!f.player.equals(a) && !f.player.equals(b)) { endpoints.clear(); }
		}
		if (!endpoints.isEmpty())
		{
			int separation = endpoints.peekLast().position.distance(f.player);
			if (separation < 2 || separation > 4) { endpoints.clear(); }
		}
		endpoints.addLast(new Endpoint(f.tick - 1, f.player));
		while (endpoints.size() > 5) { endpoints.removeFirst(); }
		if (endpoints.size() != 5) { return; }
		List<Endpoint> e = new ArrayList<>(endpoints);
		if (!e.get(0).position.equals(e.get(2).position) || !e.get(0).position.equals(e.get(4).position)
			|| !e.get(1).position.equals(e.get(3).position)) { return; }
		if (ready)
		{
			Map<String, String> facts = facts(f, 8); facts.put("windowFirstTick", Long.toString(e.get(0).firstTick));
			facts.put("endpointDistance", Integer.toString(e.get(0).position.distance(e.get(1).position)));
			emit(f, "M52", 17, 12, pattern(f), facts, 4, factory, out);
		}
		Endpoint terminal = endpoints.peekLast(); endpoints.clear(); endpoints.addLast(terminal);
	}

	private void circuit(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (cycle.isEmpty() || f.tick - cycleStart >= 64) { seedCycle(f); return; }
		if (movement == 0) { return; }
		Position start = cycle.get(0);
		if (!cycleSealed)
		{
			if (f.player.equals(start))
			{
				if (cycle.size() >= 4) { cycleSealed = true; cycleIndex = 1; } else { seedCycle(f); }
			}
			else if (cycle.contains(f.player) || cycle.size() == 8) { seedCycle(f); }
			else { cycle.add(f.player); }
			return;
		}
		Position expected = cycleIndex == cycle.size() ? start : cycle.get(cycleIndex);
		if (!f.player.equals(expected)) { seedCycle(f); return; }
		if (cycleIndex < cycle.size()) { cycleIndex++; return; }
		if (ready)
		{
			Map<String, String> facts = facts(f, 8); facts.put("cycleVertices", Integer.toString(cycle.size()));
			facts.put("windowFirstTick", Long.toString(cycleStart));
			emit(f, "M53", 16, 12, pattern(f), facts, 6, factory, out);
		}
		seedCycle(f);
	}

	private void seedCycle(ScenarioSnapshot f)
	{
		cycle.clear(); cycle.add(f.player); cycleStart = f.tick; cycleSealed = false; cycleIndex = 0;
	}

	private void retrace(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		while (!corner.isEmpty() && f.tick - corner.peekFirst().tick >= 50) { corner.removeFirst(); }
		if (corner.isEmpty() || movement > 0) { corner.addLast(new TileTick(f.tick, f.player, movement)); }
		while (corner.size() > 9) { corner.removeFirst(); }
		if (movement == 0 || corner.size() != 9) { return; }
		List<TileTick> p = new ArrayList<>(corner);
		Position a = p.get(0).position, b = p.get(1).position, c = p.get(2).position;
		int abX = b.x - a.x, abY = b.y - a.y, bcX = c.x - b.x, bcY = c.y - b.y;
		if (Math.abs(abX) + Math.abs(abY) != 1 || Math.abs(bcX) + Math.abs(bcY) != 1
			|| abX * bcX + abY * bcY != 0 || p.get(5).tick - p.get(4).tick - 1 > 4) { return; }
		Position[] expected = {a, b, c, b, a, b, c, b, a};
		for (int i = 0; i < expected.length; i++) { if (!expected[i].equals(p.get(i).position)) { return; } }
		if (ready)
		{
			Map<String, String> facts = facts(f, 6); facts.put("windowFirstTick", Long.toString(p.get(0).tick));
			facts.put("betweenTracesStillTicks", Long.toString(p.get(5).tick - p.get(4).tick - 1));
			emit(f, "M54", 16, 10, pattern(f), facts, 4, factory, out);
		}
		corner.clear(); corner.addLast(p.get(8));
	}

	private void startsAndStops(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		boolean moving = movement > 0;
		if (runs.isEmpty() || runs.peekLast().moving != moving) { runs.addLast(new Run(f.tick, moving)); }
		else { runs.peekLast().length = Math.min(5, runs.peekLast().length + 1); }
		while (runs.size() > 6) { runs.removeFirst(); }
		while (!runs.isEmpty() && f.tick - runs.peekFirst().tick >= 36) { runs.removeFirst(); }
		if (runs.size() != 6 || moving || runs.peekLast().length != 2) { return; }
		List<Run> r = new ArrayList<>(runs);
		for (int i = 0; i < r.size(); i++)
		{
			Run run = r.get(i); boolean expectedMove = (i & 1) == 0;
			if (run.moving != expectedMove || run.length < (expectedMove ? 1 : 2) || run.length > (expectedMove ? 3 : 4)) { return; }
		}
		if (ready)
		{
			Map<String, String> facts = facts(f, 4); facts.put("windowFirstTick", Long.toString(r.get(0).tick));
			facts.put("currentStillStartTick", Long.toString(r.get(5).tick));
			emit(f, "M55", 15, 8, pattern(f), facts, 4, factory, out);
		}
	}

	private void travelPause(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (stillTicks != 12 || positions.size() < 76) { return; }
		List<TileTick> history = tail(76); int moves = 0;
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (int i = 0; i < 64; i++)
		{
			TileTick p = history.get(i); if (p.movement > 0) { moves++; }
			minX = Math.min(minX, p.position.x); maxX = Math.max(maxX, p.position.x);
			minY = Math.min(minY, p.position.y); maxY = Math.max(maxY, p.position.y);
		}
		if (moves < 24 || maxX - minX < 12 && maxY - minY < 12) { return; }
		if (ready)
		{
			Map<String, String> facts = facts(f, 8); facts.put("windowFirstTick", Long.toString(history.get(0).tick));
			facts.put("movingSamples", Integer.toString(moves)); facts.put("xSpan", Integer.toString(maxX - minX));
			facts.put("ySpan", Integer.toString(maxY - minY)); facts.put("pauseStartTick", Long.toString(pauseStart));
			emit(f, "M56", 14, 12, quietCause(), facts, 8, factory, out);
		}
	}

	private void regionArrival(ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		witnessedArrivalTick = -1;
		while (!arrivals.isEmpty() && f.tick - arrivals.peekFirst().tick > 2400) { arrivals.removeFirst(); }
		if (!f.topLevel || f.instanced || f.player.plane != 0) { outsideTick = -1; insideTicks = 0; return; }
		if (f.player.region() != 12850) { outsideTick = f.tick; insideTicks = 0; return; }
		if (insideTicks == 0 && prior.topLevel && !prior.instanced && prior.player.plane == 0
			&& prior.player.region() != 12850 && prior.tick >= f.presentSinceTick) { outsideTick = prior.tick; }
		if (outsideTick < f.presentSinceTick || outsideTick < 0) { return; }
		insideTicks = Math.min(5, insideTicks + 1);
		if (insideTicks == 4) { witnessedArrivalTick = f.tick; }
	}

	/** Decorates the same M10 cause/deadline; it never creates an extra arrival scene. */
	SceneCandidate decorateArrival(ScenarioSnapshot f, SceneCandidate original, ExpandedScenarioRules.Factory factory)
	{
		if (!"M10".equals(original.getScenarioId()) || witnessedArrivalTick != f.tick || held(f) || f.emergencySceneActive) { return original; }
		String cause = original.getCausalToken();
		for (Arrival arrival : arrivals) { if (arrival.cause.equals(cause)) { return original; } }
		arrivals.addLast(new Arrival(f.tick, cause)); while (arrivals.size() > 3) { arrivals.removeFirst(); }
		if (arrivals.size() < 3) { return original; }
		Map<String, String> facts = new HashMap<>(original.getConditionFacts());
		facts.put("witnessedArrivalCount", "3"); facts.put("firstArrivalTick", Long.toString(arrivals.peekFirst().tick));
		facts.put("outsideWitnessTick", Long.toString(outsideTick)); facts.put("maximumApproachTicks", "12");
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		facts.putIfAbsent("settlingNotBeforeTick", Long.toString(f.presentSinceTick + 10));
		SceneCandidate decorated = new SceneCandidate(original.getId(), "M57", original.getPriority(), original.getCreatedTick(),
			original.getExpiresTick(), original.getSessionGeneration(), original.getSceneRevision(), cause, facts, original.isIntentionalSilent(), 4);
		issued.put("M57", decorated); return decorated;
	}

	private void facing(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (movement > 0 || f.observedFacing < 0) { resetFacing(); return; }
		while (!headings.isEmpty() && f.tick - headings.peekFirst().firstTick >= 32) { headings.removeFirst(); headingDirection = 0; }
		int bin = cardinal(f.observedFacing);
		if (bin < 0) { headingBin = -1; headingTicks = 0; return; }
		if (bin == headingBin) { headingTicks = Math.min(3, headingTicks + 1); }
		else { headingBin = bin; headingTicks = 1; headingStart = f.tick; }
		if (headingTicks != 2 || !headings.isEmpty() && headings.peekLast().bin == bin) { return; }
		if (!headings.isEmpty())
		{
			int difference = (bin - headings.peekLast().bin + 4) & 3;
			int direction = difference == 1 ? 1 : difference == 3 ? -1 : 0;
			boolean repeated = false; for (Heading h : headings) { if (h.bin == bin) { repeated = true; } }
			if (repeated || direction == 0 || headingDirection != 0 && direction != headingDirection) { headings.clear(); headingDirection = 0; }
			else { headingDirection = direction; }
		}
		headings.addLast(new Heading(headingStart, bin));
		if (headings.size() != 4) { return; }
		if (ready)
		{
			Map<String, String> facts = facts(f, 6); facts.put("windowFirstTick", Long.toString(headings.peekFirst().firstTick));
			facts.put("headingDirection", Integer.toString(headingDirection)); facts.put("headingCount", "4");
			emit(f, "M59", 16, 10, pattern(f), facts, 4, factory, out);
		}
		headings.clear(); headingDirection = 0;
	}

	private static int cardinal(int orientation)
	{
		int angle = orientation & 2047;
		for (int bin = 0; bin < 4; bin++)
		{
			int difference = Math.abs(angle - bin * 512); difference = Math.min(difference, 2048 - difference);
			if (difference <= 32) { return bin; }
		}
		return -1;
	}

	private void quietPause(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (movement != 0 || !progressQuiet || !f.interactionKnown || f.interactionPresent) { quietTicks = 0; return; }
		quietTicks = Math.min(120, quietTicks + 1);
		if (quietTicks != 120 || quietConsumed) { return; }
		quietConsumed = true;
		if (ready)
		{
			Map<String, String> facts = facts(f, 8); facts.put("windowFirstTick", Long.toString(f.tick - 119));
			facts.put("pauseStartTick", Long.toString(pauseStart));
			emit(f, "M70", 13, 12, quietCause(), facts, 12, factory, out);
		}
	}

	private void nativeTravel(ScenarioSnapshot f, boolean ready, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		MotionObservation current = f.motion, completion = f.motionCompletion;
		if (current == null || !current.known || current.plane != f.player.plane || current.worldViewId != f.worldViewId
			|| current.placementGeneration != f.placementGeneration || !current.ordinary || !current.legalGoal
			|| current.purpose == MotionObservation.Purpose.OTHER
			|| current.status != MotionObservation.Status.MOVING && current.status != MotionObservation.Status.ARRIVED)
		{
			catchup = null; wanderings.clear(); return;
		}
		if (placement != Long.MIN_VALUE && placement != current.placementGeneration)
		{
			catchup = null; wanderings.clear(); issued.remove("M51"); issued.remove("M58"); issued.remove("M60");
		}
		placement = current.placementGeneration;
		if (catchup != null)
		{
			int distance = f.player.distance(f.companion);
			if (f.tick - catchup.completedTick >= 12 || current.placementGeneration != catchup.placementGeneration
				|| current.worldViewId != catchup.worldViewId || movement < 1 || movement > 2 || distance < 2 || distance > 5)
			{
				catchup = null;
			}
			else if (++catchupMoving == 6)
			{
				if (ready)
				{
					Map<String, String> facts = motionFacts(f, catchup, 6); facts.put("movingSamples", "6");
					emitMotion(f, "M51", 18, 12, catchupCause(catchup), facts, 6, catchup, factory, out);
				}
				catchup = null;
			}
		}
		while (!wanderings.isEmpty() && f.tick - wanderings.peekFirst().completedTick >= 60) { wanderings.removeFirst(); }
		if (!freshCompletion(f, current, completion) || completion.requestId == lastCompletion) { return; }
		lastCompletion = completion.requestId;
		if (completion.purpose == MotionObservation.Purpose.CATCHUP && completion.startDistance >= 10)
		{
			int distance = f.player.distance(f.companion);
			if (distance < 2 || distance > 5) { return; }
			if (stillTicks >= 4 && completion.lastCompletedEdgeTick > Math.max(pauseStart, f.tick - 12))
			{
				if (ready)
				{
					Map<String, String> facts = motionFacts(f, completion, 4); facts.put("pauseStartTick", Long.toString(pauseStart));
					facts.put("stationarySamples", Integer.toString(Math.min(12, stillTicks)));
					emitMotion(f, "M60", 18, 10, catchupCause(completion), facts, 6, completion, factory, out);
				}
				catchup = null;
			}
			else if (completion.completedRunEdges >= 2) { catchup = completion; catchupMoving = 0; }
		}
		if (completion.purpose != MotionObservation.Purpose.ROAM || !completion.fixedGoal || f.player.plane < 1 || f.player.plane > 3) { return; }
		for (MotionObservation roaming : wanderings) { if (roaming.requestId == completion.requestId) { return; } }
		wanderings.addLast(completion); while (wanderings.size() > 3) { wanderings.removeFirst(); }
		if (wanderings.size() != 3 || positions.size() < 60) { return; }
		List<TileTick> history = tail(60); Set<Position> goals = new HashSet<>();
		for (MotionObservation roaming : wanderings)
		{
			if (roaming.completedTick < history.get(0).tick || roaming.placementGeneration != placement || roaming.worldViewId != current.worldViewId) { return; }
			goals.add(new Position(roaming.goalWorldX, roaming.goalWorldY, roaming.plane));
		}
		if (goals.size() != 3) { return; }
		int minX = f.player.x, maxX = f.player.x, minY = f.player.y, maxY = f.player.y;
		for (TileTick p : history)
		{
			minX = Math.min(minX, p.position.x); maxX = Math.max(maxX, p.position.x);
			minY = Math.min(minY, p.position.y); maxY = Math.max(maxY, p.position.y);
		}
		if (maxX - minX > 2 || maxY - minY > 2) { return; }
		if (ready)
		{
			Map<String, String> facts = motionFacts(f, completion, 4); facts.put("wanderCount", "3");
			facts.put("windowFirstTick", Long.toString(history.get(0).tick));
			facts.put("minX", Integer.toString(minX)); facts.put("maxX", Integer.toString(maxX));
			facts.put("minY", Integer.toString(minY)); facts.put("maxY", Integer.toString(maxY));
			emitMotion(f, "M58", 14, 12, "wander:" + completion.requestId, facts, 6, completion, factory, out);
		}
		wanderings.clear();
	}

	private boolean freshCompletion(ScenarioSnapshot f, MotionObservation current, MotionObservation completed)
	{
		return completed != null && completed.known && completed.ordinary && completed.legalGoal && completed.requestId >= 0
			&& completed.status == MotionObservation.Status.ARRIVED && completed.completedTick == f.tick
			&& completed.startedTick >= f.presentSinceTick && completed.startedTick >= motionWitnessSinceTick
			&& completed.startedTick <= completed.completedTick
			&& completed.completedEdges > 0 && completed.completedEdges <= 24 && completed.completedRunEdges >= 0
			&& completed.completedRunEdges <= completed.completedEdges && completed.lastCompletedEdgeTick >= completed.startedTick
			&& completed.lastCompletedEdgeTick <= completed.completedTick && completed.arrivalDistance >= 2 && completed.arrivalDistance <= 5
			&& completed.plane == f.player.plane && completed.placementGeneration == current.placementGeneration
			&& completed.placementGeneration == f.placementGeneration && completed.worldViewId == f.worldViewId
			&& completed.worldViewId == current.worldViewId && current.ordinary && current.legalGoal && current.purpose != MotionObservation.Purpose.OTHER
			&& (current.status == MotionObservation.Status.MOVING || current.status == MotionObservation.Status.ARRIVED)
			&& (completed.requestId == current.requestId || current.startedTick >= completed.completedTick);
	}

	private static boolean failed(MotionObservation motion)
	{
		return motion.status == MotionObservation.Status.BLOCKED || motion.status == MotionObservation.Status.TARGET_LOST
			|| motion.status == MotionObservation.Status.INVALIDATED || motion.status == MotionObservation.Status.RECOVERED
			|| motion.status == MotionObservation.Status.SCENE_UNAVAILABLE;
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
		SceneCandidate retained = issued.get(c.getScenarioId());
		MotionObservation receipt = motionWitnesses.get(c.getScenarioId());
		return sameCandidate(c, retained) && receipt != null ? receipt.requestId : -1;
	}
	private boolean valid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean deadline)
	{
		if (!handles(c.getScenarioId())) { return true; }
		SceneCandidate retained = issued.get(c.getScenarioId());
		return sameCandidate(c, retained) && conditions(retained, f, combat, deadline);
	}
	private static boolean handles(String id)
	{
		return "M51".equals(id) || "M52".equals(id) || "M53".equals(id) || "M54".equals(id) || "M55".equals(id)
			|| "M56".equals(id) || "M57".equals(id) || "M58".equals(id) || "M59".equals(id) || "M60".equals(id) || "M70".equals(id);
	}
	private boolean conditions(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean deadline)
	{
		if (!healthy(f) || held(f) || f.emergencySceneActive || combat != CombatState.NO_CONFIRMED_EVIDENCE
			|| deadline && f.tick - f.presentSinceTick < 10
			|| f.tick < c.getCreatedTick() || deadline && f.tick >= c.getExpiresTick()
			|| f.sessionGeneration != c.getSessionGeneration() || f.sceneRevision != c.getSceneRevision()
			|| f.player.distance(f.companion) >= 10) { return false; }
		Map<String, String> facts = c.getConditionFacts();
		if (!Long.toString(f.presentSinceTick).equals(facts.get("witnessPresenceEpoch"))) { return false; }
		String id = c.getScenarioId();
		boolean ownCommitted = !deadline && f.activeSceneCandidateId == c.getId() && f.activeSceneCommitted;
		if ("M57".equals(id)) { return f.topLevel && !f.instanced && f.player.plane == 0 && f.player.region() == 12850; }
		if ("M51".equals(id) || "M58".equals(id) || "M60".equals(id))
		{
			boolean ownHold = ownedHold(c, f, motionWitnesses.get(id));
			if (f.motion == null || !f.motion.known || failed(f.motion) && !ownHold
				|| !ownHold && (!f.motion.ordinary || !f.motion.legalGoal || f.motion.purpose == MotionObservation.Purpose.OTHER
					|| f.motion.status != MotionObservation.Status.MOVING && f.motion.status != MotionObservation.Status.ARRIVED)
				|| f.motion.placementGeneration != f.placementGeneration || f.motion.worldViewId != f.worldViewId || f.motion.plane != f.player.plane
				|| !Long.toString(f.motion.placementGeneration).equals(facts.get("placementGeneration"))
				|| !Integer.toString(f.motion.worldViewId).equals(facts.get("worldViewId"))
				|| f.player.distance(f.companion) < 2 || f.player.distance(f.companion) > 5) { return false; }
		}
		if ("M51".equals(id)) { return ownCommitted || movement >= 1 && movement <= 2; }
		if ("M58".equals(id))
		{
			return f.player.plane >= 1 && f.player.plane <= 3 && f.player.x >= number(facts, "minX") && f.player.x <= number(facts, "maxX")
				&& f.player.y >= number(facts, "minY") && f.player.y <= number(facts, "maxY");
		}
		if ("M60".equals(id) && ownCommitted) { return true; }
		if (!Integer.toString(f.player.x).equals(facts.get("playerX")) || !Integer.toString(f.player.y).equals(facts.get("playerY"))) { return false; }
		if ("M55".equals(id))
		{
			return !runs.isEmpty() && !runs.peekLast().moving && runs.peekLast().length >= 2 && runs.peekLast().length <= 4
				&& Long.toString(runs.peekLast().tick).equals(facts.get("currentStillStartTick"));
		}
		return !"M70".equals(id) || progressQuiet && f.interactionKnown && !f.interactionPresent;
	}

	/** Only a genuine completion admitted by this candidate can authorize its own stationary hold. */
	static boolean ownedHold(SceneCandidate c, ScenarioSnapshot f, MotionObservation original)
	{
		MotionHoldReceipt hold = f.motionHold;
		if (!healthy(f) || f.sessionGeneration != c.getSessionGeneration() || f.sceneRevision != c.getSceneRevision()
			|| !Long.toString(f.presentSinceTick).equals(c.getConditionFacts().get("witnessPresenceEpoch"))
			|| original == null || hold == null || f.activeSceneCandidateId != c.getId() || hold.candidateId != c.getId()
			|| hold.requestId != original.requestId || !sameCompletion(original, hold.completion) || !hold.legalStanding
			|| hold.admittedTick < c.getCreatedTick() || hold.admittedTick >= c.getExpiresTick()
			|| hold.heldTick < hold.admittedTick || hold.heldTick > f.tick
			|| hold.mode != MotionHoldReceipt.Mode.HOLD && (hold.mode != MotionHoldReceipt.Mode.NORMAL_RESUME || f.tick - hold.heldTick > 1)
			|| hold.placementGeneration != original.placementGeneration || hold.placementGeneration != f.placementGeneration
			|| hold.worldViewId != original.worldViewId || hold.worldViewId != f.worldViewId || hold.plane != f.player.plane
			|| original.plane != hold.plane || original.startedTick < f.presentSinceTick || original.completedTick > c.getCreatedTick()
			|| original.completedTick < original.startedTick || original.completedEdges <= 0
			|| f.motion == null || f.motion.status != MotionObservation.Status.INVALIDATED || !f.motion.known
			|| !f.motion.ordinary || !f.motion.legalGoal || f.motion.purpose == MotionObservation.Purpose.OTHER
			|| f.motion.placementGeneration != hold.placementGeneration || f.motion.worldViewId != hold.worldViewId
			|| f.motion.plane != hold.plane) { return false; }
		Position standing = new Position(hold.worldX, hold.worldY, hold.plane);
		int distance = standing.distance(f.player);
		return standing.equals(f.companion) && distance >= 2 && distance <= 5;
	}

	static boolean sameCompletion(MotionObservation a, MotionObservation b)
	{
		return a != null && b != null && a.known && b.known && a.status == MotionObservation.Status.ARRIVED
			&& b.status == MotionObservation.Status.ARRIVED && a.ordinary && b.ordinary && a.legalGoal && b.legalGoal
			&& a.requestId == b.requestId && a.placementGeneration == b.placementGeneration && a.worldViewId == b.worldViewId
			&& a.plane == b.plane && a.purpose == b.purpose && a.startedTick == b.startedTick && a.completedTick == b.completedTick
			&& a.sourceCandidateId == b.sourceCandidateId
			&& a.lastCompletedEdgeTick == b.lastCompletedEdgeTick && a.completedEdges == b.completedEdges
			&& a.completedRunEdges == b.completedRunEdges && a.startWorldX == b.startWorldX && a.startWorldY == b.startWorldY
			&& a.goalWorldX == b.goalWorldX && a.goalWorldY == b.goalWorldY && a.startDistance == b.startDistance
			&& a.arrivalDistance == b.arrivalDistance && a.fixedGoal == b.fixedGoal && a.physicalBlock == b.physicalBlock
			&& a.previouslyLegalEdge == b.previouslyLegalEdge && a.changedEdgeAfterBlock == b.changedEdgeAfterBlock
			&& a.blockedTick == b.blockedTick && a.blockedFromWorldX == b.blockedFromWorldX && a.blockedFromWorldY == b.blockedFromWorldY
			&& a.blockedToWorldX == b.blockedToWorldX && a.blockedToWorldY == b.blockedToWorldY;
	}

	static boolean sameCandidate(SceneCandidate candidate, SceneCandidate retained)
	{
		return retained != null && candidate.getId() == retained.getId() && candidate.getScenarioId().equals(retained.getScenarioId())
			&& candidate.getCreatedTick() == retained.getCreatedTick() && candidate.getExpiresTick() == retained.getExpiresTick()
			&& candidate.getSessionGeneration() == retained.getSessionGeneration() && candidate.getSceneRevision() == retained.getSceneRevision()
			&& candidate.getCausalToken().equals(retained.getCausalToken()) && candidate.getConditionFacts().equals(retained.getConditionFacts())
			&& candidate.getHoldTicks() == retained.getHoldTicks() && candidate.isIntentionalSilent() == retained.isIntentionalSilent();
	}

	private static boolean noProgress(ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL) { continue; }
			if (f.xp(skill) < 0 || prior.xp(skill) < 0 || f.xp(skill) != prior.xp(skill)) { return false; }
		}
		return true;
	}
	private static boolean held(ScenarioSnapshot f) { return f.inventoryBlocked || !f.interactionKnown || f.interactionPresent; }
	private static boolean healthy(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && f.presentSinceTick >= 0 && f.tick >= f.presentSinceTick
			&& !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0 && f.player != null && f.companion != null
			&& f.player.plane >= 0 && f.player.plane <= 3 && f.companion.plane == f.player.plane;
	}
	private static boolean continuous(ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		return healthy(f) && healthy(prior) && f.tick == prior.tick + 1 && f.sessionGeneration == prior.sessionGeneration
			&& f.sceneRevision == prior.sceneRevision && f.presentSinceTick == prior.presentSinceTick && prior.tick >= f.presentSinceTick
			&& f.topLevel == prior.topLevel && f.instanced == prior.instanced && f.player.distance(prior.player) <= 2;
	}
	private List<TileTick> tail(int count)
	{
		List<TileTick> history = new ArrayList<>(positions); return history.subList(history.size() - count, history.size());
	}
	private void remember(ScenarioSnapshot f)
	{
		lastTick = f.tick; lastSession = f.sessionGeneration; lastScene = f.sceneRevision; lastPresence = f.presentSinceTick;
	}
	private static int number(Map<String, String> facts, String key) { return Integer.parseInt(facts.get(key)); }
	private static String pattern(ScenarioSnapshot f) { return "travel:" + f.tick; }
	private String quietCause() { return "pause:" + pauseStart; }
	private static String catchupCause(MotionObservation motion) { return "catchup:" + motion.requestId; }
	private static Map<String, String> facts(ScenarioSnapshot f, int approach)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region()));
		facts.put("playerX", Integer.toString(f.player.x)); facts.put("playerY", Integer.toString(f.player.y));
		facts.put("observedPlane", Integer.toString(f.player.plane)); facts.put("witnessedPresent", "true");
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick)); facts.put("occurredTick", Long.toString(f.tick));
		facts.put("settlingNotBeforeTick", Long.toString(f.presentSinceTick + 10));
		facts.put("maximumApproachTicks", Integer.toString(approach)); return facts;
	}
	private static Map<String, String> motionFacts(ScenarioSnapshot f, MotionObservation motion, int approach)
	{
		Map<String, String> facts = facts(f, approach); facts.put("requestId", Long.toString(motion.requestId));
		facts.put("placementGeneration", Long.toString(motion.placementGeneration)); facts.put("worldViewId", Integer.toString(motion.worldViewId));
		facts.put("completionTick", Long.toString(motion.completedTick)); facts.put("completedEdges", Integer.toString(motion.completedEdges));
		facts.put("completedRunEdges", Integer.toString(motion.completedRunEdges)); return facts;
	}
	private void emit(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, int hold,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		SceneCandidate c = factory.create(f, id, priority, life, cause, facts, false, hold); issued.put(id, c); out.add(c);
	}
	private void emitMotion(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, int hold,
		MotionObservation completion, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		emit(f, id, priority, life, cause, facts, hold, factory, out); motionWitnesses.put(id, completion);
	}
	void reset()
	{
		positions.clear(); corner.clear(); endpoints.clear(); runs.clear(); cycle.clear(); resetFacing(); arrivals.clear(); wanderings.clear(); issued.clear(); motionWitnesses.clear();
		lastTick = Long.MIN_VALUE; pauseStart = -1; outsideTick = -1; witnessedArrivalTick = -1; lastCompletion = Long.MIN_VALUE; placement = Long.MIN_VALUE;
		motionWitnessSinceTick = Long.MIN_VALUE;
		dwell = 0; stillTicks = 0; quietTicks = 0; insideTicks = 0; quietConsumed = false; progressQuiet = false; catchup = null; catchupMoving = 0;
	}
	void reset(long invalidThroughTick)
	{
		reset(); motionWitnessSinceTick = invalidThroughTick + 1;
	}
	private void resetFacing() { headings.clear(); headingBin = -1; headingTicks = 0; headingDirection = 0; }
	private static final class TileTick
	{
		final long tick; final Position position; final int movement;
		TileTick(long tick, Position position, int movement) { this.tick = tick; this.position = position; this.movement = movement; }
	}
	private static final class Endpoint
	{
		final long firstTick; final Position position;
		Endpoint(long firstTick, Position position) { this.firstTick = firstTick; this.position = position; }
	}
	private static final class Run
	{
		final long tick; final boolean moving; int length = 1;
		Run(long tick, boolean moving) { this.tick = tick; this.moving = moving; }
	}
	private static final class Heading
	{
		final long firstTick; final int bin;
		Heading(long firstTick, int bin) { this.firstTick = firstTick; this.bin = bin; }
	}
	private static final class Arrival
	{
		final long tick; final String cause;
		Arrival(long tick, String cause) { this.tick = tick; this.cause = cause; }
	}
}
