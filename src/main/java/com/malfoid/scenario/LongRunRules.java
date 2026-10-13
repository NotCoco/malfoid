package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** E.7: fifty committed movement records and one decision for each continuous run. */
final class LongRunRules
{
	private final Deque<Integer> movements = new ArrayDeque<>();
	private final Random random;
	private long session = Long.MIN_VALUE, scene, presence, lastTick = Long.MIN_VALUE;
	private long freshFrom = Long.MIN_VALUE, lastSpoken = Long.MIN_VALUE;
	private int view, tiles, stillTicks;
	private Position previous;
	private boolean consumedRun;
	private SceneCandidate issued;

	LongRunRules() { this(new Random()); }
	LongRunRules(Random random) { this.random = random; }

	SceneCandidate close(ScenarioSnapshot f, boolean localDeath, CombatState combat, boolean following,
		ExpandedScenarioRules.Factory factory)
	{
		if (f.sessionGeneration != session) { resetSession(); session = f.sessionGeneration; }
		if (f.tick == lastTick && f.sceneRevision == scene && f.presentSinceTick == presence) { return null; }
		boolean continuous = healthy(f) && previous != null && lastTick != Long.MIN_VALUE
			&& f.tick == lastTick + 1 && f.sceneRevision == scene && f.presentSinceTick == presence
			&& f.worldViewId == view && f.player.distance(previous) <= 2 && !localDeath;
		int movement = continuous ? f.player.distance(previous) : 0;
		lastTick = f.tick; scene = f.sceneRevision; presence = f.presentSinceTick; view = f.worldViewId;
		previous = healthy(f) && !localDeath ? f.player : null;
		if (!continuous)
		{
			invalidateWindow(); freshFrom = f.tick + 1; return null;
		}
		stillTicks = movement == 0 ? Math.min(10, stillTicks + 1) : 0;
		if (consumedRun && stillTicks == 10)
		{
			consumedRun = false; freshFrom = f.tick + 1; issued = null;
		}
		movements.addLast(movement); tiles += movement;
		if (movements.size() > 50) { tiles -= movements.removeFirst(); }
		if (issued != null && !valid(issued, f, combat, following, false)) { issued = null; }
		long windowStart = f.tick - 49;
		if (consumedRun || movements.size() != 50 || tiles < 60 || !ready(f, combat, following)
			|| windowStart < f.presentSinceTick || windowStart < freshFrom
			|| lastSpoken != Long.MIN_VALUE && (f.tick < lastSpoken || f.tick - lastSpoken < 500)) { return null; }
		consumedRun = true;
		// This is the only probability draw. A silent answer consumes this run too.
		if (!random.nextBoolean()) { return null; }
		Map<String, String> facts = new HashMap<>();
		facts.put("runWindowStartTick", Long.toString(windowStart));
		facts.put("runWindowTiles", Integer.toString(tiles));
		facts.put("runWorldViewId", Integer.toString(f.worldViewId));
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		// E.7 fixes priority and expiry; the four ticks are readable presentation, never a physical hold.
		issued = factory.create(f, "F03", 30, 12, "long-run:" + f.tick, facts, false, 4);
		return issued;
	}

	boolean stillValid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean following)
	{
		return issued != null && issued.getId() == c.getId() && issued.getCausalToken().equals(c.getCausalToken())
			&& valid(c, f, combat, following, true);
	}
	boolean continuationValid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean following)
	{
		return issued != null && issued.getId() == c.getId() && issued.getCausalToken().equals(c.getCausalToken())
			&& valid(c, f, combat, following, false);
	}
	private static boolean valid(SceneCandidate c, ScenarioSnapshot f, CombatState combat, boolean following, boolean deadline)
	{
		return "F03".equals(c.getScenarioId()) && ready(f, combat, following)
			&& f.sessionGeneration == c.getSessionGeneration() && f.sceneRevision == c.getSceneRevision()
			&& Long.toString(f.presentSinceTick).equals(c.getConditionFacts().get("witnessPresenceEpoch"))
			&& Integer.toString(f.worldViewId).equals(c.getConditionFacts().get("runWorldViewId"))
			&& f.tick >= c.getCreatedTick() && (!deadline || f.tick < c.getExpiresTick());
	}
	private static boolean ready(ScenarioSnapshot f, CombatState combat, boolean following)
	{
		return healthy(f) && following && combat != CombatState.CONFIRMED && !f.emergencySceneActive;
	}
	private static boolean healthy(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead
			&& f.currentHp > 0 && f.player != null && f.companion != null
			&& f.presentSinceTick >= 0 && f.tick >= f.presentSinceTick && f.player.plane == f.companion.plane;
	}

	/** Only a genuine first-paint receipt starts the separate 500-tick timer. */
	void onFirstPaint(SpeechPacket.PaintReceipt receipt)
	{
		if (receipt != null && "F03".equals(receipt.getCandidate().getScenarioId())
			&& receipt.getCandidate().getSessionGeneration() == session)
		{
			lastSpoken = Math.max(lastSpoken, receipt.getPaintedAtTick());
		}
	}
	void invalidateWindow()
	{
		movements.clear(); tiles = stillTicks = 0; issued = null;
	}
	void resetScene()
	{
		invalidateWindow(); previous = null; lastTick = freshFrom = Long.MIN_VALUE;
	}
	void resetSession()
	{
		resetScene(); session = Long.MIN_VALUE; scene = presence = 0; lastSpoken = Long.MIN_VALUE; consumedRun = false;
	}
}
