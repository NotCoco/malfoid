package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;

/** Silent A.9 reactions to witnessed committed animation transitions. */
final class EmoteRules
{
	private static final int[] ANIMATIONS = {
		AnimationID.EMOTE_WAVE, AnimationID.EMOTE_BECKON, AnimationID.EMOTE_BOW,
		AnimationID.EMOTE_DANCE, AnimationID.EMOTE_BLOW_KISS
	};
	private static final int[] HOLDS = {3, 5, 3, 4, 6};
	private final long[] nextEmoteTick = new long[ANIMATIONS.length];
	private long nextGlobalTick = Long.MIN_VALUE;
	private long session = Long.MIN_VALUE, scene = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
	private boolean baseline;
	private String committedCause;

	EmoteRules()
	{
		Arrays.fill(nextEmoteTick, Long.MIN_VALUE);
	}

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch,
		List<SkillDelta> deltas, CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		if (session != f.sessionGeneration)
		{
			resetSession(); session = f.sessionGeneration; scene = f.sceneRevision;
		}
		if (scene != f.sceneRevision) { reset(); scene = f.sceneRevision; }
		if (f.tick <= lastTick) { return Collections.emptyList(); }
		boolean hadBaseline = baseline;
		lastTick = f.tick;
		baseline = f.valid && f.player != null && f.animation >= -1;
		if (!hadBaseline || prior == null || !prior.valid || !prior.present || !f.present
			|| prior.player == null || prior.animation < -1 || f.tick - prior.tick != 1
			|| prior.sessionGeneration != f.sessionGeneration || prior.sceneRevision != f.sceneRevision
			|| prior.presentSinceTick != f.presentSinceTick || f.animation == prior.animation)
		{
			return Collections.emptyList();
		}
		int emote = index(f.animation);
		if (emote < 0 || batch.localDeath || !safe(f, combat) || !cooldownReady(emote, f.tick))
		{
			return Collections.emptyList();
		}
		Map<String, String> facts = new HashMap<>();
		facts.put("emoteAnimation", Integer.toString(f.animation));
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		String cause = "emote:" + f.sessionGeneration + ":" + f.sceneRevision + ":" + f.tick + ":" + f.animation;
		return Collections.singletonList(factory.create(f, "E01", 40, 12, cause, facts, true, HOLDS[emote]));
	}

	/** Called only for a matching validated motion arrival, never candidate creation. */
	boolean arrived(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (!continuationValid(candidate, f, combat, lastDamage)
			|| candidate.getCausalToken().equals(committedCause)) { return false; }
		int emote = index(candidate.getConditionFacts().get("emoteAnimation"));
		nextEmoteTick[emote] = f.tick + 20;
		nextGlobalTick = f.tick + 10;
		committedCause = candidate.getCausalToken();
		return true;
	}

	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (!"E01".equals(candidate.getScenarioId()) || !candidate.isIntentionalSilent() || !safe(f, combat)
			|| candidate.getSessionGeneration() != f.sessionGeneration || candidate.getSceneRevision() != f.sceneRevision
			|| candidate.getCreatedTick() > f.tick || candidate.getCreatedTick() < f.presentSinceTick
			|| !Long.toString(f.presentSinceTick).equals(candidate.getConditionFacts().get("witnessPresenceEpoch")))
		{
			return false;
		}
		int emote = index(candidate.getConditionFacts().get("emoteAnimation"));
		return emote >= 0 && (candidate.getCausalToken().equals(committedCause) || cooldownReady(emote, f.tick));
	}

	/** Scene/presence invalidation clears occurrence continuity, preserving session cooldowns. */
	void reset()
	{
		lastTick = Long.MIN_VALUE; baseline = false; committedCause = null;
	}
	void resetSession()
	{
		Arrays.fill(nextEmoteTick, Long.MIN_VALUE); nextGlobalTick = Long.MIN_VALUE;
		session = Long.MIN_VALUE; scene = Long.MIN_VALUE; reset();
	}

	private boolean cooldownReady(int emote, long tick)
	{
		return tick >= nextGlobalTick && tick >= nextEmoteTick[emote];
	}

	private static boolean safe(ScenarioSnapshot f, CombatState combat)
	{
		return f != null && f.valid && f.present && !f.dead && f.currentHp > 0 && !f.emergencySceneActive
			&& f.player != null && f.companion != null && f.player.distance(f.companion) <= 8
			&& combat != null && combat != CombatState.CONFIRMED;
	}

	private static int index(int animation)
	{
		for (int i = 0; i < ANIMATIONS.length; i++) { if (ANIMATIONS[i] == animation) { return i; } }
		return -1;
	}
	private static int index(String animation)
	{
		for (int i = 0; i < ANIMATIONS.length; i++) { if (Integer.toString(ANIMATIONS[i]).equals(animation)) { return i; } }
		return -1;
	}
}
