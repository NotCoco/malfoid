package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;

/** M22/M24/M25/M27/M30 use only complete, presence-bound committed copies. */
final class SkillExpansionRules
{
	private static final Skill[] PRIMARY = {Skill.FISHING, Skill.WOODCUTTING, Skill.MINING};
	private final EnumMap<Skill, Burst> bursts = new EnumMap<>(Skill.class);
	private final Deque<TileTick> burstPositions = new ArrayDeque<>();
	private final Deque<TileTick> moving = new ArrayDeque<>();
	private final Deque<Gain> agility = new ArrayDeque<>();
	private final Map<String, SceneCandidate> issued = new HashMap<>();
	private long craftingGain = -1, smithingGain = -1, drainStart = -1;
	private long lastOwnedNpcCombat = Long.MIN_VALUE / 2, lastTick = Long.MIN_VALUE;
	private long lastSession, lastScene, lastPresence;
	private int equalLevelSamples, drainedSamples, drainXp, drainReal, agilitySamples;
	private Gain drop;
	private boolean ambiguousDrop;

	SkillExpansionRules()
	{
		for (Skill skill : PRIMARY) { bursts.put(skill, new Burst()); }
	}

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, List<SkillDelta> deltas,
		CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		if (f.tick == lastTick && f.sessionGeneration == lastSession && f.sceneRevision == lastScene
			&& f.presentSinceTick == lastPresence) { return out; }
		boolean consecutive = lastTick == Long.MIN_VALUE || f.tick == lastTick + 1
			&& f.sessionGeneration == lastSession && f.sceneRevision == lastScene && f.presentSinceTick == lastPresence;
		if (!consecutive || !continuous(f, prior) || batch.localDeath || combat == CombatState.UNKNOWN)
		{
			reset(); remember(f); return out;
		}
		remember(f);
		issued.entrySet().removeIf(entry -> !conditions(entry.getValue(), f, combat, lastDamage, false)
			|| selectedProgressInvalid(entry.getValue(), f, prior));
		boolean terminalLevel = false;
		for (SkillDelta delta : deltas)
		{
			if (delta.delta > 0 && delta.newLevel > delta.oldLevel && batch.witnessedSkills.contains(delta.skill))
			{
				terminalLevel = true;
			}
		}
		boolean terminalReady = !terminalLevel && !f.emergencySceneActive
			&& combat == CombatState.NO_CONFIRMED_EVIDENCE && f.tick - lastDamage >= 10;
		if (combat == CombatState.NO_CONFIRMED_EVIDENCE)
		{
			equality(f, prior, terminalReady, factory, out);
			burstReturn(f, prior, terminalReady, factory, out);
			movingFiremaking(f, prior, terminalReady, factory, out);
			restoration(f, prior, terminalReady, factory, out);
		}
		else { resetOrdinary(); }
		// Incoming damage at a counted Agility gain is expressly allowed in its earlier history.
		agilityAfterDrop(f, prior, batch, terminalReady, factory, out);
		return out;
	}

	private void equality(ScenarioSnapshot f, ScenarioSnapshot prior, boolean ready,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (!unchanged(f, prior, Skill.CRAFTING) || !unchanged(f, prior, Skill.SMITHING))
		{
			craftingGain = -1; smithingGain = -1; equalLevelSamples = 0; return;
		}
		equalLevelSamples = Math.min(10, equalLevelSamples + 1);
		if (craftingGain < f.tick - 9) { craftingGain = -1; }
		if (smithingGain < f.tick - 9) { smithingGain = -1; }
		boolean craft = gain(f, prior, Skill.CRAFTING), smith = gain(f, prior, Skill.SMITHING);
		long other = craft ? smithingGain : craftingGain;
		if (ready && equalLevelSamples >= 10 && craft != smith && other >= f.tick - 9 && other < f.tick
			&& f.xp(Skill.CRAFTING) > 0 && f.xp(Skill.CRAFTING) == f.xp(Skill.SMITHING)
			&& prior.xp(Skill.CRAFTING) != prior.xp(Skill.SMITHING))
		{
			Map<String, String> facts = facts(f, null, 4);
			facts.put("matchedTotal", Integer.toString(f.xp(Skill.CRAFTING)));
			facts.put("craftingRealLevel", Integer.toString(f.level(Skill.CRAFTING)));
			facts.put("smithingRealLevel", Integer.toString(f.level(Skill.SMITHING)));
			facts.put("otherGainTick", Long.toString(other));
			emit(f, "M22", 30, 8, "progress:" + f.tick, facts, 4, factory, out);
		}
		if (craft) { craftingGain = f.tick; }
		if (smith) { smithingGain = f.tick; }
	}

	private void burstReturn(ScenarioSnapshot f, ScenarioSnapshot prior, boolean ready,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		burstPositions.addLast(new TileTick(f.tick, f.player, false));
		while (!burstPositions.isEmpty() && burstPositions.peekFirst().tick < f.tick - 29) { burstPositions.removeFirst(); }
		Return selected = null;
		for (Skill skill : PRIMARY)
		{
			Burst burst = bursts.get(skill);
			if (!unchanged(f, prior, skill)) { burst.reset(); continue; }
			while (!burst.gains.isEmpty() && burst.gains.peekFirst() < f.tick - 29) { burst.gains.removeFirst(); }
			boolean positive = gain(f, prior, skill);
			if (burst.armed)
			{
				burst.include(f.player);
				if (f.tick - burst.first >= 100 || !burst.fits()) { burst.reset(); }
				else if (positive)
				{
					long gap = f.tick - burst.lastGain - 1;
					if (gap >= 40 && gap <= 59)
					{
						Return next = new Return(skill, burst, gap);
						if (selected == null || next.lastGain > selected.lastGain
							|| next.lastGain == selected.lastGain && skill.ordinal() < selected.skill.ordinal()) { selected = next; }
						burst.reset();
					}
					else { burst.lastGain = f.tick; }
				}
				else if (f.tick - burst.lastGain >= 60) { burst.reset(); }
			}
			if (positive) { burst.gains.addLast(f.tick); }
			if (!burst.armed && burst.gains.size() >= 6)
			{
				burst.first = burst.gains.peekFirst(); burst.lastGain = f.tick; burst.minX = burst.maxX = f.player.x;
				burst.minY = burst.maxY = f.player.y;
				for (TileTick sample : burstPositions) { if (sample.tick >= burst.first) { burst.include(sample.position); } }
				if (burst.fits()) { burst.armed = true; } else { burst.reset(); }
			}
		}
		if (selected != null && ready)
		{
			Map<String, String> facts = facts(f, selected.skill, 8);
			facts.put("realLevel", Integer.toString(f.level(selected.skill)));
			facts.put("quietGapTicks", Long.toString(selected.gap));
			facts.put("burstFirstTick", Long.toString(selected.first)); facts.put("preGapGainTick", Long.toString(selected.lastGain));
			facts.put("minX", Integer.toString(selected.minX)); facts.put("maxX", Integer.toString(selected.maxX));
			facts.put("minY", Integer.toString(selected.minY)); facts.put("maxY", Integer.toString(selected.maxY));
			emit(f, "M24", 24, 12, "progress:" + f.tick, facts, 5, factory, out);
		}
	}

	private void movingFiremaking(ScenarioSnapshot f, ScenarioSnapshot prior, boolean ready,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (!unchanged(f, prior, Skill.FIREMAKING)) { moving.clear(); return; }
		boolean positive = gain(f, prior, Skill.FIREMAKING);
		moving.addLast(new TileTick(f.tick, f.player, positive));
		while (moving.size() > 40) { moving.removeFirst(); }
		if (moving.size() < 40 || !positive) { return; }
		int count = 0, distance = 0, minX = f.player.x, maxX = f.player.x, minY = f.player.y, maxY = f.player.y;
		Set<Position> tiles = new HashSet<>(); Position previous = null;
		for (TileTick sample : moving)
		{
			if (sample.positive) { count++; tiles.add(sample.position); }
			if (previous != null) { distance += previous.distance(sample.position); }
			previous = sample.position;
			minX = Math.min(minX, previous.x); maxX = Math.max(maxX, previous.x);
			minY = Math.min(minY, previous.y); maxY = Math.max(maxY, previous.y);
		}
		if (count < 8 || tiles.size() < 6 || distance < 16 || maxX - minX < 8 && maxY - minY < 8) { return; }
		if (ready)
		{
			Map<String, String> facts = facts(f, Skill.FIREMAKING, 8);
			facts.put("realLevel", Integer.toString(f.level(Skill.FIREMAKING)));
			facts.put("gainTicks", Integer.toString(count)); facts.put("gainTiles", Integer.toString(tiles.size()));
			facts.put("observedDistance", Integer.toString(distance));
			facts.put("xSpan", Integer.toString(maxX - minX)); facts.put("ySpan", Integer.toString(maxY - minY));
			facts.put("windowFirstTick", Long.toString(moving.peekFirst().tick));
			emit(f, "M25", 22, 12, "progress:" + f.tick, facts, 5, factory, out);
		}
		moving.clear();
	}

	private void restoration(ScenarioSnapshot f, ScenarioSnapshot prior, boolean ready,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		int real = f.level(Skill.MINING), current = f.boosted(Skill.MINING);
		if (!unchanged(f, prior, Skill.MINING) || f.xp(Skill.MINING) != prior.xp(Skill.MINING)
			|| current < 0 || prior.boosted(Skill.MINING) < 0)
		{
			resetDrain(); return;
		}
		if (drainStart >= 0)
		{
			if (f.tick - drainStart >= 30 || real != drainReal || f.xp(Skill.MINING) != drainXp || current > real)
			{
				resetDrain(); return;
			}
			if (current < real) { drainedSamples = Math.min(30, drainedSamples + 1); return; }
			if (drainedSamples >= 10 && ready)
			{
				Map<String, String> facts = facts(f, Skill.MINING, 4);
				facts.put("realLevel", Integer.toString(real)); facts.put("restoredXp", Integer.toString(drainXp));
				facts.put("drainStartTick", Long.toString(drainStart)); facts.put("drainedTicks", Integer.toString(drainedSamples));
				emit(f, "M27", 23, 8, "progress:" + f.tick, facts, 4, factory, out);
			}
			resetDrain(); return;
		}
		if (prior.boosted(Skill.MINING) >= real && current < real)
		{
			drainStart = f.tick; drainedSamples = 1; drainXp = f.xp(Skill.MINING); drainReal = real;
		}
	}

	private void agilityAfterDrop(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, boolean ready,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		for (Hit hit : batch.hits)
		{
			if (!hit.local && hit.mine && hit.npcKey != null && (DamageTypes.hpDamage(hit.type) && hit.amount > 0
				|| hit.type == HitsplatID.BLOCK_ME && hit.amount == 0)) { lastOwnedNpcCombat = f.tick; }
		}
		if (!unchanged(f, prior, Skill.AGILITY) || f.realHp <= 0 || f.realHp != prior.realHp
			|| prior.currentHp <= 0 || f.currentHp <= 0)
		{
			resetAgility(); return;
		}
		agilitySamples = Math.min(20, agilitySamples + 1);
		while (!agility.isEmpty() && agility.peekFirst().tick < f.tick - 19) { agility.removeFirst(); }
		if (drop != null && drop.tick < f.tick - 19) { drop = null; ambiguousDrop = false; }
		if (!gain(f, prior, Skill.AGILITY)) { return; }
		long hitId = Long.MAX_VALUE;
		for (Hit hit : batch.hits)
		{
			if (hit.local && hit.witnessed && DamageTypes.hpDamage(hit.type) && hit.amount > 0) { hitId = Math.min(hitId, hit.id); }
		}
		Gain current = new Gain(f.tick, prior.currentHp, f.currentHp, hitId);
		agility.addLast(current); while (agility.size() > 3) { agility.removeFirst(); }
		int decrease = current.beforeHp - current.afterHp;
		if (decrease >= 5 && decrease <= 3L * f.realHp / 10)
		{
			// The authored three-gains/one-drop bound cannot retain overlapping drop choices.
			// Suppress the ambiguous episode rather than guess which older drop was newest at age 10.
			if (drop != null) { ambiguousDrop = true; }
			drop = current;
		}
		if (!ready || agilitySamples < 20 || agility.size() < 3 || drop == null || ambiguousDrop
			|| f.tick - drop.tick < 10 || lastOwnedNpcCombat >= f.tick - 19 || f.currentHp < (f.realHp + 1) / 2) { return; }
		Map<String, String> facts = facts(f, Skill.AGILITY, 6);
		facts.put("realLevel", Integer.toString(f.level(Skill.AGILITY))); facts.put("realHp", Integer.toString(f.realHp));
		facts.put("dropTick", Long.toString(drop.tick)); facts.put("priorHp", Integer.toString(drop.beforeHp));
		facts.put("observedHp", Integer.toString(drop.afterHp)); facts.put("hpDrop", Integer.toString(drop.beforeHp - drop.afterHp));
		String cause = drop.hitId == Long.MAX_VALUE ? "hp-progress:" + drop.tick : "hit:" + drop.hitId;
		emit(f, "M30", 50, 10, cause, facts, 6, factory, out);
		drop = null; ambiguousDrop = false; agility.clear();
	}

	boolean stillValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (!handles(candidate.getScenarioId())) { return true; }
		SceneCandidate retained = issued.get(candidate.getScenarioId());
		return retained != null && retained.getId() == candidate.getId() && conditions(candidate, f, combat, lastDamage, true);
	}

	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f, CombatState combat, long lastDamage)
	{
		if (!handles(candidate.getScenarioId())) { return true; }
		SceneCandidate retained = issued.get(candidate.getScenarioId());
		return retained != null && retained.getId() == candidate.getId() && conditions(candidate, f, combat, lastDamage, false);
	}

	private static boolean handles(String id)
	{
		return "M22".equals(id) || "M24".equals(id) || "M25".equals(id) || "M27".equals(id) || "M30".equals(id);
	}

	private boolean conditions(SceneCandidate c, ScenarioSnapshot f, CombatState combat, long lastDamage, boolean deadline)
	{
		if (!healthy(f) || f.emergencySceneActive || f.tick < c.getCreatedTick() || deadline && f.tick >= c.getExpiresTick()
			|| f.sessionGeneration != c.getSessionGeneration() || f.sceneRevision != c.getSceneRevision()
			|| combat != CombatState.NO_CONFIRMED_EVIDENCE || f.tick - lastDamage < 10) { return false; }
		Map<String, String> facts = c.getConditionFacts();
		if (!Long.toString(f.presentSinceTick).equals(facts.get("witnessPresenceEpoch"))) { return false; }
		String id = c.getScenarioId();
		if ("M22".equals(id))
		{
			return number(facts, "matchedTotal") == f.xp(Skill.CRAFTING) && f.xp(Skill.CRAFTING) == f.xp(Skill.SMITHING)
				&& number(facts, "craftingRealLevel") == f.level(Skill.CRAFTING)
				&& number(facts, "smithingRealLevel") == f.level(Skill.SMITHING);
		}
		Skill skill = Skill.valueOf(facts.get("skill"));
		if (!known(f, skill) || f.level(skill) != number(facts, "realLevel")) { return false; }
		if ("M24".equals(id))
		{
			return Math.max(f.player.x, number(facts, "maxX")) - Math.min(f.player.x, number(facts, "minX")) <= 7
				&& Math.max(f.player.y, number(facts, "maxY")) - Math.min(f.player.y, number(facts, "minY")) <= 7;
		}
		if ("M27".equals(id)) { return f.boosted(skill) == f.level(skill) && f.xp(skill) == number(facts, "restoredXp"); }
		if ("M30".equals(id))
		{
			return f.realHp == number(facts, "realHp") && f.currentHp >= (f.realHp + 1) / 2 && lastOwnedNpcCombat < c.getCreatedTick();
		}
		return true;
	}

	private static boolean selectedProgressInvalid(SceneCandidate c, ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		if ("M22".equals(c.getScenarioId())) { return !unchanged(f, prior, Skill.CRAFTING) || !unchanged(f, prior, Skill.SMITHING); }
		return !unchanged(f, prior, Skill.valueOf(c.getConditionFacts().get("skill")));
	}

	void reset()
	{
		resetOrdinary(); resetAgility(); issued.clear(); lastTick = Long.MIN_VALUE;
		lastOwnedNpcCombat = Long.MIN_VALUE / 2;
	}

	private void resetOrdinary()
	{
		craftingGain = -1; smithingGain = -1; equalLevelSamples = 0; resetDrain();
		for (Burst burst : bursts.values()) { burst.reset(); }
		burstPositions.clear(); moving.clear();
	}
	private void resetDrain() { drainStart = -1; drainedSamples = 0; }
	private void resetAgility() { agility.clear(); drop = null; ambiguousDrop = false; agilitySamples = 0; }
	private void remember(ScenarioSnapshot f)
	{
		lastTick = f.tick; lastSession = f.sessionGeneration; lastScene = f.sceneRevision; lastPresence = f.presentSinceTick;
	}
	private static boolean healthy(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0 && f.player != null;
	}
	private static boolean continuous(ScenarioSnapshot f, ScenarioSnapshot prior)
	{
		return healthy(f) && healthy(prior) && f.tick == prior.tick + 1 && f.sessionGeneration == prior.sessionGeneration
			&& f.sceneRevision == prior.sceneRevision && f.presentSinceTick == prior.presentSinceTick
			&& f.worldViewId == prior.worldViewId && prior.tick >= f.presentSinceTick && f.player.distance(prior.player) <= 2;
	}
	private static boolean known(ScenarioSnapshot f, Skill skill) { return f.xp(skill) >= 0 && f.level(skill) > 0; }
	private static boolean unchanged(ScenarioSnapshot f, ScenarioSnapshot prior, Skill skill)
	{
		return known(f, skill) && known(prior, skill) && f.level(skill) == prior.level(skill) && f.xp(skill) >= prior.xp(skill);
	}
	private static boolean gain(ScenarioSnapshot f, ScenarioSnapshot prior, Skill skill) { return f.xp(skill) > prior.xp(skill); }
	private static int number(Map<String, String> facts, String key) { return Integer.parseInt(facts.get(key)); }
	private static Map<String, String> facts(ScenarioSnapshot f, Skill skill, int approach)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region()));
		facts.put("witnessedPresent", "true"); facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick));
		facts.put("occurredTick", Long.toString(f.tick)); facts.put("maximumApproachTicks", Integer.toString(approach));
		if (skill != null) { facts.put("skill", skill.name()); facts.put("skillSet", skill.name()); }
		return facts;
	}
	private void emit(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, int hold,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		SceneCandidate candidate = factory.create(f, id, priority, life, cause, facts, false, hold);
		issued.put(id, candidate); out.add(candidate);
	}

	private static final class TileTick
	{
		final long tick; final Position position; final boolean positive;
		TileTick(long tick, Position position, boolean positive) { this.tick = tick; this.position = position; this.positive = positive; }
	}
	private static final class Gain
	{
		final long tick, hitId; final int beforeHp, afterHp;
		Gain(long tick, int beforeHp, int afterHp, long hitId)
		{
			this.tick = tick; this.beforeHp = beforeHp; this.afterHp = afterHp; this.hitId = hitId;
		}
	}
	private static final class Burst
	{
		final Deque<Long> gains = new ArrayDeque<>(); boolean armed; long first, lastGain;
		int minX, maxX, minY, maxY;
		void include(Position p)
		{
			minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
		}
		boolean fits() { return maxX - minX <= 7 && maxY - minY <= 7; }
		void reset() { gains.clear(); armed = false; }
	}
	private static final class Return
	{
		final Skill skill; final long first, lastGain, gap; final int minX, maxX, minY, maxY;
		Return(Skill skill, Burst b, long gap)
		{
			this.skill = skill; first = b.first; lastGain = b.lastGain; this.gap = gap;
			minX = b.minX; maxX = b.maxX; minY = b.minY; maxY = b.maxY;
		}
	}
}
