package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;

/** Explicit, bounded implementations of catalogue predicates; prose is never executed. */
final class ExpandedScenarioRules
{
	interface Factory
	{
		SceneCandidate create(ScenarioSnapshot frame, String id, int priority, int life, String cause, Map<String, String> facts, boolean silent, int hold);
	}
	private final Deque<Long> blocked = new ArrayDeque<>();
	private final Deque<NumberTick> climbing = new ArrayDeque<>();
	private final Deque<GainTick> alternating = new ArrayDeque<>(), fletching = new ArrayDeque<>(), sampler = new ArrayDeque<>(), farming = new ArrayDeque<>();
	private final Deque<Long> cookingHat = new ArrayDeque<>(), firemakingItems = new ArrayDeque<>(), cookingQuartet = new ArrayDeque<>(), fishingQuantities = new ArrayDeque<>();
	private final Relation headSpare = new Relation(), cabbageHat = new Relation();
	private int fishingQuantityStable;
	private long injuryTick = -1, injuryId;
	private int injuryReal, lowWater, recoveryStable, oneHpSamples;

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch, List<SkillDelta> deltas,
		CombatState combat, long lastDamage, Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		injury(f, batch, lastDamage, factory, out);
		combatNumbers(f, batch, factory, out);
		if (combat != CombatState.NO_CONFIRMED_EVIDENCE)
		{
			resetSkills(); resetItems(); return out;
		}
		skills(f, deltas, factory, out);
		items(f, prior, deltas, factory, out);
		return out;
	}

	private void injury(ScenarioSnapshot f, ScenarioBatch batch, long lastDamage, Factory factory, List<SceneCandidate> out)
	{
		Hit damage = null;
		for (Hit hit : batch.hits)
		{
			if (hit.local && hit.witnessed && hit.amount > 0 && DamageTypes.hpDamage(hit.type))
			{
				if (damage == null || hit.id < damage.id) { damage = hit; }
			}
		}
		if (damage != null)
		{
			injuryTick = f.tick; injuryId = damage.id; injuryReal = f.realHp; lowWater = f.currentHp;
			recoveryStable = 0; oneHpSamples = f.currentHp == 1 ? 1 : 0;
			if ((f.realHp < 20 || f.currentHp > 3L * f.realHp / 10) && f.currentHp != 1) { injuryTick = -1; }
			return;
		}
		if (injuryTick < 0) { return; }
		if (f.realHp != injuryReal || f.tick - injuryTick > 20) { injuryTick = -1; return; }
		if (f.currentHp == 1 && f.tick - injuryTick <= 2) { oneHpSamples++; } else { oneHpSamples = 0; }
		if (oneHpSamples == 3 && f.directorIdle)
		{
			out.add(factory.create(f, "M12", 85, 6, "injury:" + injuryId, region(f), false, 4)); oneHpSamples = 0; injuryTick = -1; return;
		}
		lowWater = Math.min(lowWater, f.currentHp);
		boolean threshold = f.realHp >= 20 && f.currentHp >= (f.realHp + 1) / 2 && f.currentHp - lowWater >= (f.realHp + 4) / 5 && f.tick - lastDamage >= 4;
		recoveryStable = threshold ? recoveryStable + 1 : 0;
		if (recoveryStable >= 2 && f.directorIdle)
		{
			Map<String, String> facts = region(f); facts.put("lowWater", Integer.toString(lowWater)); facts.put("observedHp", Integer.toString(f.currentHp));
			out.add(factory.create(f, "M11", 60, 10, "injury:" + injuryId, facts, false, 6)); injuryTick = -1;
		}
	}

	private void combatNumbers(ScenarioSnapshot f, ScenarioBatch batch, Factory factory, List<SceneCandidate> out)
	{
		if (f.target == null || !f.target.alive || f.target.name == null) { resetEngagement(); return; }
		while (!blocked.isEmpty() && blocked.peekFirst() < f.tick - 11) { blocked.removeFirst(); }
		int positiveCount = 0, blockCount = 0, largest = 0; long positiveId = 0; boolean otherOwned = false;
		for (Hit hit : batch.hits)
		{
			if (!hit.mine || !hit.witnessed || !f.target.key.equals(hit.npcKey)) { continue; }
			if (DamageTypes.hpDamage(hit.type) && hit.amount > 0)
			{
				positiveCount++; if (hit.amount > largest) { largest = hit.amount; positiveId = hit.id; }
			}
			else if (hit.type == HitsplatID.BLOCK_ME && hit.amount == 0) { blockCount++; }
			else { otherOwned = true; }
		}
		if (positiveCount > 0)
		{
			if (!"Chicken".equalsIgnoreCase(f.target.name) && blocked.size() >= 3)
			{
				Map<String, String> facts = target(f); facts.put("ownedDamage", Integer.toString(largest));
				out.add(factory.create(f, "M13", 32, 10, "owned:" + positiveId, facts, false, 4));
			}
			blocked.clear();
		}
		if (blockCount > 0 && positiveCount == 0) { blocked.addLast(f.tick); while (blocked.size() > 3) { blocked.removeFirst(); } }
		if (blockCount > 0 || positiveCount > 1 || otherOwned) { climbing.clear(); }
		else if (positiveCount == 1)
		{
			while (!climbing.isEmpty() && climbing.peekFirst().tick < f.tick - 19) { climbing.removeFirst(); }
			climbing.addLast(new NumberTick(f.tick, largest)); while (climbing.size() > 4) { climbing.removeFirst(); }
			if (climbing.size() == 4)
			{
				int priorNumber = -1; boolean increasing = true;
				for (NumberTick number : climbing) { if (number.amount <= priorNumber) { increasing = false; } priorNumber = number.amount; }
				if (increasing)
				{
					Map<String, String> facts = target(f); facts.put("ownedDamage", Integer.toString(largest));
					out.add(factory.create(f, "M16", 42, 10, "owned:" + positiveId, facts, false, 5)); climbing.clear();
				}
			}
		}
	}

	private void skills(ScenarioSnapshot f, List<SkillDelta> deltas, Factory factory, List<SceneCandidate> out)
	{
		SkillDelta selectedAlternate = null, selectedSampler = null, farm = null, fish = null, fletch = null;
		int alternateCount = 0, samplerCount = 0;
		boolean anyLevel = false;
		for (SkillDelta delta : deltas)
		{
			if (delta.newLevel != delta.oldLevel)
			{
				anyLevel = true;
				if (delta.skill == Skill.CONSTRUCTION || delta.skill == Skill.FARMING) { alternating.clear(); }
				if (delta.skill == Skill.COOKING || delta.skill == Skill.FISHING || delta.skill == Skill.FIREMAKING) { sampler.clear(); }
				if (delta.skill == Skill.FLETCHING) { fletching.clear(); }
				if (delta.skill == Skill.FARMING) { farming.clear(); }
			}
			if (delta.delta <= 0 || delta.newLevel != delta.oldLevel) { continue; }
			if (delta.skill == Skill.CONSTRUCTION || delta.skill == Skill.FARMING) { alternateCount++; selectedAlternate = delta; }
			if (delta.skill == Skill.COOKING || delta.skill == Skill.FISHING || delta.skill == Skill.FIREMAKING) { samplerCount++; selectedSampler = delta; }
			if (delta.skill == Skill.FARMING) { farm = delta; }
			if (delta.skill == Skill.FISHING) { fish = delta; }
			if (delta.skill == Skill.FLETCHING) { fletch = delta; }
		}
		prune(alternating, f.tick - 59); prune(sampler, f.tick - 59); prune(farming, f.tick - 59); prune(fletching, f.tick - 119);
		if (alternateCount > 1) { alternating.clear(); }
		else if (selectedAlternate != null)
		{
			GainTick last = alternating.peekLast();
			if (last != null && last.skill == selectedAlternate.skill) { alternating.clear(); }
			alternating.addLast(new GainTick(f.tick, selectedAlternate.skill, selectedAlternate.delta));
			if (alternating.size() >= 6)
			{
				Map<String, String> facts = region(f); facts.put("skillSet", "CONSTRUCTION,FARMING");
				out.add(factory.create(f, "M21", 30, 12, "skill-alternation:" + f.tick, facts, false, 5)); alternating.clear();
			}
		}
		if (fletch != null && fletch.oldLevel == 99)
		{
			fletching.addLast(new GainTick(f.tick, Skill.FLETCHING, fletch.delta));
			long sum = 0; for (GainTick gain : fletching) { sum += gain.delta; }
			if (fletching.size() >= 10 && sum >= 1000)
			{
				Map<String, String> facts = region(f); facts.put("skillSet", "FLETCHING"); facts.put("xpDelta", Long.toString(sum));
				out.add(factory.create(f, "M23", 28, 12, "post99:" + f.tick, facts, false, 6)); fletching.clear();
			}
		}
		if (samplerCount > 1) { sampler.clear(); }
		else if (selectedSampler != null)
		{
			long count = 0; for (GainTick gain : sampler) { if (gain.skill == selectedSampler.skill) { count++; } }
			if (count < 2) { sampler.addLast(new GainTick(f.tick, selectedSampler.skill, selectedSampler.delta)); }
			if (sampler.size() == 6)
			{
				Map<String, String> facts = region(f); facts.put("skillSet", "COOKING,FISHING,FIREMAKING");
				out.add(factory.create(f, "M26", 27, 12, "skill-sampler:" + f.tick, facts, false, 5)); sampler.clear();
			}
		}
		if (fish != null && fish.oldXp < 1_000_000 && fish.newXp >= 1_000_000 && !anyLevel)
		{
			Map<String, String> facts = region(f); facts.put("skill", "FISHING"); facts.put("xpTotal", Integer.toString(fish.newXp));
			out.add(factory.create(f, "M28", 40, 12, "xp-million:" + f.tick, facts, false, 6));
		}
		if (farm != null)
		{
			long sum = 0; for (GainTick gain : farming) { sum += gain.delta; }
			if (farming.size() == 3 && farm.delta >= 1000 && farm.delta >= sum * 5)
			{
				Map<String, String> facts = region(f); facts.put("skillSet", "FARMING"); facts.put("xpDelta", Long.toString(farm.delta));
				out.add(factory.create(f, "M29", 32, 12, "farming-progress:" + f.tick, facts, false, 5)); farming.clear();
			}
			else { farming.addLast(new GainTick(f.tick, Skill.FARMING, farm.delta)); while (farming.size() > 3) { farming.removeFirst(); } }
		}
	}

	private void items(ScenarioSnapshot f, ScenarioSnapshot prior, List<SkillDelta> deltas, Factory factory, List<SceneCandidate> out)
	{
		if (prior == null || !prior.present || f.inventoryBlocked || prior.inventoryBlocked || !f.inventoryKnown() || !prior.inventoryKnown())
		{
			resetItems(); return;
		}
		int[] worn = f.equipment(), priorWorn = prior.equipment();
		if (worn == null || priorWorn == null) { resetItems(); return; }
		boolean relation = worn[0] >= 0 && f.itemCount(worn[0]) >= 1;
		if (headSpare.observe(relation, Integer.toString(worn[0])))
		{
			Map<String, String> facts = region(f); facts.put("matchedHeadItemId", Integer.toString(worn[0]));
			out.add(factory.create(f, "M35", 22, 12, "spare-hat:" + f.tick, facts, false, 6));
		}
		if (cabbageHat.observe(worn[0] == 1949 && f.occupiedSlots(1965) >= 20, "1949,1965"))
		{
			Map<String, String> facts = region(f); facts.put("namedItemIds", "1949,1965");
			out.add(factory.create(f, "M37", 24, 12, "cabbage-hat:" + f.tick, facts, false, 5));
		}
		boolean hatBurnt = worn[0] == 1949 && f.itemCount(7954) > 0;
		boolean fireItems = f.itemCount(590) > 0 && f.itemCount(592) > 0;
		boolean quartet = quartet(f);
		boolean fishQuantity = f.itemCount(314) == 1 && f.itemCount(313) >= 1000;
		fishingQuantityStable = fishQuantity ? Math.min(5, fishingQuantityStable + 1) : 0;
		pruneTicks(cookingHat, f.tick - 59); pruneTicks(firemakingItems, f.tick - 59);
		pruneTicks(cookingQuartet, f.tick - 59); pruneTicks(fishingQuantities, f.tick - 119);
		for (SkillDelta delta : deltas)
		{
			if (delta.delta <= 0) { continue; }
			if (delta.skill == Skill.COOKING) { addTick(cookingHat, f.tick, 3); if (quartet) { addTick(cookingQuartet, f.tick, 6); } }
			if (delta.skill == Skill.FIREMAKING && fireItems) { addTick(firemakingItems, f.tick, 10); }
			if (delta.skill == Skill.FISHING && fishQuantity) { addTick(fishingQuantities, f.tick, 10); }
		}
		if (hatBurnt && priorWorn[0] == 1949 && prior.itemCount(7954) > 0 && qualifies(cookingHat, 3, f.tick))
		{
			offerItem(f, factory, out, "M36", 23, "1949,7954", 5); cookingHat.clear();
		}
		if (fireItems && prior.itemCount(590) > 0 && prior.itemCount(592) > 0 && qualifies(firemakingItems, 10, f.tick))
		{
			offerItem(f, factory, out, "M38", 18, "590,592", 5); firemakingItems.clear();
		}
		if (quartet && quartet(prior) && qualifies(cookingQuartet, 6, f.tick))
		{
			offerItem(f, factory, out, "M40", 19, "1925,1931,1935,1980", 5); cookingQuartet.clear();
		}
		if (fishingQuantityStable >= 5 && qualifies(fishingQuantities, 10, f.tick))
		{
			offerItem(f, factory, out, "M42", 20, "313,314", 5); fishingQuantities.clear();
		}
	}
	private static void offerItem(ScenarioSnapshot f, Factory factory, List<SceneCandidate> out, String id, int priority, String subject, int hold)
	{
		Map<String, String> facts = region(f); facts.put("namedItemIds", subject);
		out.add(factory.create(f, id, priority, 12, "items-progress:" + id + ":" + f.tick, facts, false, hold));
	}
	private static boolean quartet(ScenarioSnapshot f)
	{
		return f.itemCount(1925) > 0 && f.itemCount(1931) > 0 && f.itemCount(1935) > 0 && f.itemCount(1980) > 0;
	}
	private static boolean qualifies(Deque<Long> ticks, int size, long now) { return ticks.size() >= size && now - ticks.peekLast() <= 5; }
	private static void pruneTicks(Deque<Long> ticks, long oldest) { while (!ticks.isEmpty() && ticks.peekFirst() < oldest) { ticks.removeFirst(); } }
	private static void addTick(Deque<Long> ticks, long tick, int cap)
	{
		if (ticks.isEmpty() || ticks.peekLast() != tick) { ticks.addLast(tick); }
		while (ticks.size() > cap) { ticks.removeFirst(); }
	}
	boolean stillValid(SceneCandidate c, ScenarioSnapshot f)
	{
		String id = c.getScenarioId(); Map<String, String> facts = c.getConditionFacts();
		if ("M11".equals(id)) { return f.currentHp >= (f.realHp + 1) / 2; }
		if ("M12".equals(id)) { return f.currentHp == 1; }
		if ("M13".equals(id) || "M16".equals(id))
		{
			return f.target != null && f.target.alive && f.target.key.equals(facts.get("targetKey")) && f.target.name.equals(facts.get("npcResolvedName"));
		}
		if (id.matches("M(3[5-9]|4[0-2])"))
		{
			if (!f.inventoryKnown() || f.inventoryBlocked) { return false; }
			if ("M36".equals(id)) { int[] worn = f.equipment(); return worn != null && worn[0] == 1949 && f.itemCount(7954) > 0; }
			if ("M37".equals(id)) { int[] worn = f.equipment(); return worn != null && worn[0] == 1949 && f.occupiedSlots(1965) >= 20; }
			String ids = facts.get("namedItemIds");
			if (ids != null) { for (String value : ids.split(",")) { if (f.itemCount(Integer.parseInt(value)) <= 0) { return false; } } }
			if ("M42".equals(id)) { return f.itemCount(314) == 1 && f.itemCount(313) >= 1000; }
			if ("M35".equals(id))
			{
				int item = Integer.parseInt(facts.get("matchedHeadItemId")); int[] worn = f.equipment();
				return worn != null && worn[0] == item && f.itemCount(item) >= 1;
			}
		}
		return true;
	}
	void reset()
	{
		resetEngagement(); resetSkills(); resetItems(); injuryTick = -1; recoveryStable = 0; oneHpSamples = 0;
	}
	void resetEngagement() { blocked.clear(); climbing.clear(); }
	private void resetSkills() { alternating.clear(); fletching.clear(); sampler.clear(); farming.clear(); }
	private void resetItems()
	{
		headSpare.reset(); cabbageHat.reset(); cookingHat.clear(); firemakingItems.clear(); cookingQuartet.clear(); fishingQuantities.clear(); fishingQuantityStable = 0;
	}
	private static void prune(Deque<GainTick> entries, long oldest) { while (!entries.isEmpty() && entries.peekFirst().tick < oldest) { entries.removeFirst(); } }
	private static Map<String, String> region(ScenarioSnapshot f)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region())); return facts;
	}
	private static Map<String, String> target(ScenarioSnapshot f)
	{
		Map<String, String> facts = region(f); facts.put("targetKey", f.target.key); facts.put("npcResolvedName", f.target.name);
		if (f.target.position != null) { facts.put("targetX", Integer.toString(f.target.position.x)); facts.put("targetY", Integer.toString(f.target.position.y)); }
		return facts;
	}
	private static final class GainTick
	{
		final long tick, delta; final Skill skill;
		GainTick(long tick, Skill skill, long delta) { this.tick = tick; this.skill = skill; this.delta = delta; }
	}
	private static final class NumberTick
	{
		final long tick; final int amount;
		NumberTick(long tick, int amount) { this.tick = tick; this.amount = amount; }
	}
	private static final class Relation
	{
		boolean baselined, armed, priorTrue; int falseTicks, trueTicks; String subject;
		boolean observe(boolean relation, String currentSubject)
		{
			if (!baselined) { baselined = true; priorTrue = relation; subject = currentSubject; return false; }
			if (!relation)
			{
				falseTicks = Math.min(5, falseTicks + 1); trueTicks = 0; priorTrue = false;
				if (falseTicks >= 5) { armed = true; }
				return false;
			}
			if (!priorTrue || !currentSubject.equals(subject)) { trueTicks = 0; subject = currentSubject; }
			priorTrue = true; falseTicks = 0;
			if (!armed) { return false; }
			trueTicks++;
			if (trueTicks >= 10) { armed = false; trueTicks = 0; return true; }
			return false;
		}
		void reset() { baselined = false; armed = false; priorTrue = false; falseTicks = 0; trueTicks = 0; subject = null; }
	}
}
