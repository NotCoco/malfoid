package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ExpandedScenarioRules.Factory;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechCancellationReceipt.Reason;
import com.malfoid.speech.SpeechPacket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** One quiet replacement from resolved OWN execution receipts, never inferred equipment callbacks. */
final class QuietOutfitRules
{
	private final long[] executions = new long[3], cancelledAt = new long[3];
	private long lastExecution, lastPaintToken = -1, lastTick = -1, session = -1, scene = -1, epoch = -1;
	private long tupleFirst = -1;
	private int count, baselines, equalCopies, worldView = Integer.MIN_VALUE, plane = -1;
	private int[] tuple;
	private boolean eligible, receiptsKnown = true;
	private SceneCandidate issued;

	void onCancellation(SpeechCancellationReceipt receipt)
	{
		if (receipt == null) { return; }
		onCancellation(new Cancellation(receipt.getCandidate(), receipt.getExecutionId(), receipt.getCancelledAtTick(),
			receipt.getReason(), receipt.isGenuinePainted(), receipt.isPrePaintAttempt(), true));
	}

	void onCancellation(Cancellation receipt)
	{
		if (receipt == null || receipt.execution <= lastExecution || receipt.execution <= 0) { return; }
		lastExecution = receipt.execution;
		SceneCandidate candidate = receipt.candidate;
		if (candidate == null || !outfit(candidate.getScenarioId()) || candidate.getSessionGeneration() != session
			|| candidate.getSceneRevision() != scene || number(candidate, "witnessPresenceEpoch") != epoch) { return; }
		if (!receipt.ledgerKnown)
		{
			receiptsKnown = false; clearPattern(); issued = null; return;
		}
		if (receipt.genuinePainted) { clearPattern(); issued = null; return; }
		if (!receipt.prePaintAttempt || receipt.reason != Reason.DEPENDENT_OUTFIT_REVISION_CHANGED
			|| !eligible || baselines < 2 || receipt.tick < epoch || receipt.tick < candidate.getCreatedTick()
			|| receipt.tick >= candidate.getExpiresTick()
			|| receipt.tick < lastTick - 79 || receipt.tick > lastTick + 1
			|| !"true".equals(candidate.getConditionFacts().get("witnessedPresent"))
			|| number(candidate, "occurredTick") != candidate.getCreatedTick() || candidate.getCreatedTick() < epoch
			|| !knownTuple(candidate.getConditionFacts().get("equipmentIds"))
			|| number(candidate, "outfitRevision") <= 0
			|| !candidate.getCausalToken().equals("outfit:" + number(candidate, "outfitRevision"))) { return; }
		receiptsKnown = true;
		prune(receipt.tick);
		if (count == 3)
		{
			System.arraycopy(executions, 1, executions, 0, 2);
			System.arraycopy(cancelledAt, 1, cancelledAt, 0, 2); count--;
		}
		executions[count] = receipt.execution; cancelledAt[count] = receipt.tick; count++;
	}

	void onFirstPaint(SpeechPacket.PaintReceipt receipt)
	{
		if (receipt != null) { onFirstPaint(receipt.getCandidate(), receipt.getAdmissionToken(), receipt.getPaintedAtTick(), true); }
	}

	void onFirstPaint(SceneCandidate candidate, long admissionToken, long paintedTick, boolean issuedTokenVerified)
	{
		if (!issuedTokenVerified || candidate == null || admissionToken <= lastPaintToken || admissionToken < 0) { return; }
		lastPaintToken = admissionToken;
		if (outfit(candidate.getScenarioId()) && candidate.getSessionGeneration() == session
			&& candidate.getSceneRevision() == scene && number(candidate, "witnessPresenceEpoch") == epoch
			&& paintedTick >= epoch) { clearPattern(); issued = null; }
	}

	void close(ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (f.tick == lastTick) { return; }
		int[] copied = f.equipment();
		boolean continuous = lastTick >= 0 && f.tick == lastTick + 1 && f.sessionGeneration == session
			&& f.sceneRevision == scene && f.presentSinceTick == epoch && f.worldViewId == worldView
			&& f.player != null && f.player.plane == plane;
		boolean ready = ready(f, copied, combat, lastDamage);
		if (!continuous || !ready)
		{
			reset(); bind(f); lastTick = f.tick; eligible = ready;
			if (ready) { tuple = copied; tupleFirst = f.tick; equalCopies = 1; baselines = 1; }
			return;
		}
		lastTick = f.tick; eligible = true; baselines = Math.min(2, baselines + 1); prune(f.tick);
		if (Arrays.equals(tuple, copied)) { equalCopies = Math.min(2, equalCopies + 1); }
		else { tuple = copied; tupleFirst = f.tick; equalCopies = 1; }
		if (issued != null && (!key(copied).equals(issued.getConditionFacts().get("equipmentIds"))
			|| f.tick > issued.getExpiresTick() + 40)) { issued = null; clearPattern(); }
	}

	SceneCandidate decorate(ScenarioSnapshot f, SceneCandidate base, CombatState combat, long lastDamage, Factory factory)
	{
		if (base == null || !outfit(base.getScenarioId())) { return base; }
		if (!receiptsKnown || !eligible || f.tick != lastTick || baselines < 2 || count != 3 || equalCopies < 2
			|| f.tick <= cancelledAt[2] || tupleFirst < cancelledAt[2] || f.tick - cancelledAt[0] >= 80
			|| !ready(f, f.equipment(), combat, lastDamage) || !ownedCurrent(base, f)
			|| base.getCreatedTick() < cancelledAt[2] || base.getCreatedTick() > f.tick
			|| f.tick >= base.getExpiresTick() || base.isIntentionalSilent()) { return base; }
		Map<String, String> facts = new HashMap<>(base.getConditionFacts());
		facts.put("quietCancellationCount", "3");
		facts.put("quietFirstCancellationTick", Long.toString(cancelledAt[0]));
		facts.put("quietLastCancellationTick", Long.toString(cancelledAt[2]));
		facts.put("quietWorldViewId", Integer.toString(worldView));
		issued = new SceneCandidate(base.getId(), "M47", 29, base.getCreatedTick(), base.getExpiresTick(),
			base.getSessionGeneration(), base.getSceneRevision(), base.getCausalToken(), facts, true, 8);
		clearPattern(); return issued;
	}

	boolean stillValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		return continuationValid(candidate, f, combat, lastDamage) && f.tick < candidate.getExpiresTick();
	}

	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		return candidate != null && candidate == issued && receiptsKnown && "M47".equals(candidate.getScenarioId())
			&& ready(f, f.equipment(), combat, lastDamage) && ownedCurrent(candidate, f)
			&& f.worldViewId == worldView && f.player.plane == plane && f.presentSinceTick == epoch;
	}

	void reset()
	{
		clearPattern(); tuple = null; issued = null; tupleFirst = -1; lastTick = -1;
		baselines = 0; equalCopies = 0; eligible = false; receiptsKnown = true;
		session = -1; scene = -1; epoch = -1; worldView = Integer.MIN_VALUE; plane = -1;
		// Execution/admission high-water marks survive narrative reset, so consumed receipts cannot return.
	}

	private void bind(ScenarioSnapshot f)
	{
		session = f.sessionGeneration; scene = f.sceneRevision; epoch = f.presentSinceTick; worldView = f.worldViewId;
		plane = f.player == null ? -1 : f.player.plane;
	}
	private void clearPattern() { count = 0; Arrays.fill(executions, 0); Arrays.fill(cancelledAt, 0); }
	private void prune(long tick)
	{
		while (count > 0 && tick - cancelledAt[0] >= 80)
		{
			System.arraycopy(executions, 1, executions, 0, count - 1);
			System.arraycopy(cancelledAt, 1, cancelledAt, 0, count - 1); count--;
			executions[count] = 0; cancelledAt[count] = 0;
		}
	}
	private boolean ownedCurrent(SceneCandidate candidate, ScenarioSnapshot f)
	{
		return candidate.getSessionGeneration() == session && candidate.getSceneRevision() == scene
			&& f.sessionGeneration == session && f.sceneRevision == scene
			&& f.tick >= candidate.getCreatedTick()
			&& "true".equals(candidate.getConditionFacts().get("witnessedPresent"))
			&& number(candidate, "witnessPresenceEpoch") == epoch && number(candidate, "occurredTick") == candidate.getCreatedTick()
			&& candidate.getCreatedTick() >= epoch
			&& number(candidate, "outfitRevision") > 0
			&& candidate.getCausalToken().equals("outfit:" + number(candidate, "outfitRevision"))
			&& key(f.equipment()).equals(candidate.getConditionFacts().get("equipmentIds"));
	}
	private static boolean ready(ScenarioSnapshot f, int[] items, CombatState combat, long lastDamage)
	{
		if (!f.valid || !f.present || f.userHidden || f.autoAbsent || f.dead || f.currentHp <= 0 || f.player == null
			|| f.companion == null || f.player.plane != f.companion.plane || f.worldViewId == Integer.MIN_VALUE
			|| f.presentSinceTick < 0 || f.tick - f.presentSinceTick < 10 || f.tick - lastDamage < 10
			|| combat != CombatState.NO_CONFIRMED_EVIDENCE || f.emergencySceneActive || f.outfitBlocked || f.inventoryBlocked
			|| items == null || items.length != 4) { return false; }
		for (int item : items) { if (item < -1) { return false; } }
		return f.motion == null || f.motion.purpose != MotionObservation.Purpose.CATCHUP
			|| f.motion.known && f.motion.completedTick >= 0;
	}
	private static boolean outfit(String id)
	{
		return "M09".equals(id) || "M31".equals(id) || "M32".equals(id) || "M33".equals(id)
			|| "M34".equals(id) || "M44".equals(id);
	}
	private static String key(int[] items)
	{
		return items == null || items.length != 4 ? "" : items[0] + "," + items[1] + "," + items[2] + "," + items[3];
	}
	private static boolean knownTuple(String fact)
	{
		if (fact == null) { return false; }
		String[] slots = fact.split(",", -1);
		if (slots.length != 4) { return false; }
		try { for (String slot : slots) { if (Integer.parseInt(slot) < -1) { return false; } } }
		catch (NumberFormatException e) { return false; }
		return true;
	}
	private static long number(SceneCandidate candidate, String field)
	{
		try { return Long.parseLong(candidate.getConditionFacts().get(field)); }
		catch (NumberFormatException e) { return -1; }
	}

	/** Copied-input seam; production input comes exclusively from the typed director receipt. */
	static final class Cancellation
	{
		final SceneCandidate candidate;
		final long execution, tick;
		final Reason reason;
		final boolean genuinePainted, prePaintAttempt, ledgerKnown;
		Cancellation(SceneCandidate candidate, long execution, long tick, Reason reason,
			boolean genuinePainted, boolean prePaintAttempt, boolean ledgerKnown)
		{
			this.candidate = candidate; this.execution = execution; this.tick = tick; this.reason = reason;
			this.genuinePainted = genuinePainted; this.prePaintAttempt = prePaintAttempt; this.ledgerKnown = ledgerKnown;
		}
	}
}
