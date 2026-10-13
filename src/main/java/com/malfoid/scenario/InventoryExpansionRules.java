package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.Skill;

/** Bounded observations of the copied bag. No inventory change is attributed to an action. */
final class InventoryExpansionRules
{
	private final AnchorWindow cabbage = new AnchorWindow(1965, Skill.FISHING, 90, 10);
	private final AnchorWindow rock = new AnchorWindow(3695, Skill.MINING, 120, 15);
	private final Deque<Long> fullMiningGains = new ArrayDeque<>();
	private final Deque<LayoutTick> arrangements = new ArrayDeque<>();
	private long lastTick = Long.MIN_VALUE, witnessVersion;
	private long latestFishing = Long.MIN_VALUE / 2, latestMining = Long.MIN_VALUE / 2;
	private boolean seeded, eligible, kitArmed, miningHandled;
	private int kitFalse, kitHold, kitMovement, fullTicks, propFullTicks, miningFullTicks;
	private long kitStart = -1, miningStart = -1, headStart = -1, spaceSerial, arrangementSerial, headSerial;
	private String kitLayout, pendingSpace, spaceProof, arrangementProof;
	private String arrangementMultiset, arrangementAnchor, stableArrangement, arrangementIds;
	private String headTuple, headPriorTuple, headProof;
	private boolean headEligible;

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch,
		List<SkillDelta> deltas, CombatState combat, long lastDamage, ExpandedScenarioRules.Factory factory)
	{
		List<SceneCandidate> out = new ArrayList<>();
		if (f.tick == lastTick) { return out; }
		Bag bag = Bag.from(f), old = Bag.from(prior);
		boolean continuous = prior != null && f.tick == prior.tick + 1
			&& f.sessionGeneration == prior.sessionGeneration && f.sceneRevision == prior.sceneRevision
			&& f.presentSinceTick == prior.presentSinceTick && prior.tick >= f.presentSinceTick
			&& permitted(f) && permitted(prior) && f.player.distance(prior.player) <= 2;
		if (!continuous || bag == null || old == null || combat != CombatState.NO_CONFIRMED_EVIDENCE
			|| f.tick - lastDamage < 10 || (lastTick != Long.MIN_VALUE && f.tick != lastTick + 1))
		{
			reset(); lastTick = f.tick; return out;
		}
		lastTick = f.tick; eligible = true;
		if (!seeded)
		{
			seeded = true; kitArmed = !old.eggKit();
		}
		boolean fishing = positiveGain(f, prior, batch, deltas, Skill.FISHING);
		boolean mining = positiveGain(f, prior, batch, deltas, Skill.MINING);
		if (fishing) { latestFishing = f.tick; }
		if (mining) { latestMining = f.tick; }
		travellingKit(f, prior, bag, factory, out);
		capacity(f, prior, bag, old, mining, factory, out);
		if (knownProgress(f, prior, Skill.FISHING))
		{
			persistent(f, bag, cabbage, fishing, "M41", 20, factory, out);
		}
		else { cabbage.reset(); latestFishing = Long.MIN_VALUE / 2; }
		if (knownProgress(f, prior, Skill.MINING))
		{
			persistent(f, bag, rock, mining, "M49", 21, factory, out);
		}
		else { rock.reset(); latestMining = Long.MIN_VALUE / 2; }
		arrangement(f, bag, old, factory, out);
		if (f.tick - f.presentSinceTick < 10) { out.clear(); }
		return out;
	}

	private static boolean permitted(ScenarioSnapshot f)
	{
		return f != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead
			&& f.currentHp > 0 && !f.inventoryBlocked && f.player != null && f.companion != null
			&& f.player.distance(f.companion) < 10;
	}

	private static boolean knownProgress(ScenarioSnapshot f, ScenarioSnapshot prior, Skill skill)
	{
		return f.xp(skill) >= 0 && prior.xp(skill) >= 0 && f.xp(skill) >= prior.xp(skill);
	}

	private static boolean positiveGain(ScenarioSnapshot f, ScenarioSnapshot prior, ScenarioBatch batch,
		List<SkillDelta> deltas, Skill skill)
	{
		if (!batch.witnessedSkills.contains(skill) || !knownProgress(f, prior, skill)) { return false; }
		for (SkillDelta delta : deltas)
		{
			if (delta.skill == skill && delta.delta > 0 && delta.oldXp == prior.xp(skill)
				&& delta.newXp == f.xp(skill)) { return true; }
		}
		return false;
	}

	private void travellingKit(ScenarioSnapshot f, ScenarioSnapshot prior, Bag bag,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (!bag.eggKit())
		{
			kitFalse = Math.min(5, kitFalse + 1); kitStart = -1; kitLayout = null;
			kitHold = 0; kitMovement = 0;
			if (kitFalse == 5) { kitArmed = true; }
			return;
		}
		kitFalse = 0;
		if (kitStart >= 0 && !bag.layout.equals(kitLayout))
		{
			kitStart = -1; kitLayout = null; kitArmed = false; return;
		}
		if (kitStart < 0)
		{
			if (!kitArmed) { return; }
			kitStart = f.tick; kitLayout = bag.layout; kitHold = 1; kitMovement = 0;
		}
		else
		{
			if (f.tick - kitStart > 12) { kitArmed = false; return; }
			kitHold = Math.min(10, kitHold + 1);
			kitMovement = Math.min(8, kitMovement + f.player.distance(prior.player));
		}
		if (kitArmed && kitHold == 10 && kitMovement == 8)
		{
			Map<String, String> facts = facts(f); facts.put("namedItemIds", "954,1925,1944");
			facts.put("inventoryLayout", kitLayout); facts.put("kitEpisode", Long.toString(kitStart));
			facts.put("observedMovementTiles", "8");
			out.add(factory.create(f, "M39", 21, 12, "INVENTORY_ODDITIES:movement:" + kitStart, facts, false, 6));
			kitArmed = false;
		}
	}

	private void capacity(ScenarioSnapshot f, ScenarioSnapshot prior, Bag bag, Bag old, boolean mining,
		ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (spaceProof != null && !spaceProof.equals(bag.layout)) { spaceProof = null; }
		int previousProps = propFullTicks;
		boolean full = bag.occupied == 28;
		if (full)
		{
			fullTicks = Math.min(30, fullTicks + 1); pendingSpace = null;
		}
		else
		{
			if (pendingSpace != null && bag.occupied == 27 && pendingSpace.equals(bag.layout))
			{
				spaceProof = bag.layout; spaceSerial++;
				Map<String, String> facts = facts(f); facts.put("inventoryLayout", bag.layout);
				facts.put("spaceEpisode", Long.toString(spaceSerial)); facts.put("occupiedSlots", "27");
				out.add(factory.create(f, "M43", 18, 12, "BAG_CAPACITY_REMARK:space:" + f.tick, facts, false, 6));
			}
			pendingSpace = fullTicks == 30 && bag.occupied == 27 ? bag.layout : null;
			fullTicks = 0;
		}
		propFullTicks = bag.fullProps() ? Math.min(30, propFullTicks + 1) : 0;
		int[] equipment = f.equipment(), priorEquipment = prior.equipment();
		if (headProof != null && (!bag.fullProps() || equipment == null || equipment.length != 4
			|| !headProof.equals(equipmentKey(equipment)))) { headProof = null; }
		if (!bag.fullProps() || equipment == null || priorEquipment == null
			|| equipment.length != 4 || priorEquipment.length != 4)
		{
			headEligible = false; headTuple = null;
		}
		else if (!Arrays.equals(equipment, priorEquipment))
		{
			headEligible = previousProps == 30 && old.fullProps() && equipment[0] >= 0
				&& equipment[0] != priorEquipment[0] && equipment[1] == priorEquipment[1]
				&& equipment[2] == priorEquipment[2] && equipment[3] == priorEquipment[3];
			headTuple = equipmentKey(equipment); headPriorTuple = equipmentKey(priorEquipment); headStart = f.tick;
		}
		if (!full || !knownProgress(f, prior, Skill.MINING))
		{
			miningFullTicks = 0; miningStart = -1; miningHandled = false; fullMiningGains.clear(); return;
		}
		if (miningFullTicks == 0) { miningStart = f.tick; }
		miningFullTicks = Math.min(60, miningFullTicks + 1);
		prune(fullMiningGains, f.tick - 59);
		if (mining && !miningHandled) { addTick(fullMiningGains, f.tick, 12); }
		if (!miningHandled && miningFullTicks == 60 && recent(fullMiningGains, 12, f.tick))
		{
			Map<String, String> facts = facts(f); facts.put("fullEpisode", Long.toString(miningStart));
			facts.put("occupiedSlots", "28"); facts.put("skill", "MINING"); facts.put("gainTicks", "12");
			out.add(factory.create(f, "M45", 17, 12, "BAG_CAPACITY_REMARK:full:" + miningStart, facts, false, 8));
			miningHandled = true; fullMiningGains.clear();
		}
	}

	/** Replace the existing M09 opportunity, keeping its source revision, ID and deadline. */
	SceneCandidate decorateOutfit(ScenarioSnapshot f, SceneCandidate base, ExpandedScenarioRules.Factory factory)
	{
		Bag bag = Bag.from(f);
		if (!eligible || f.tick != lastTick || !"M09".equals(base.getScenarioId()) || !headEligible
			|| f.tick != headStart + 1 || bag == null || !bag.fullProps()
			|| !"HEAD".equals(base.getConditionFacts().get("changedSlot"))
			|| !"HEAD".equals(base.getConditionFacts().get("changedSlots"))
			|| !headPriorTuple.equals(base.getConditionFacts().get("priorEquipmentIds"))
			|| !headTuple.equals(base.getConditionFacts().get("equipmentIds"))) { return base; }
		Map<String, String> facts = new HashMap<>(base.getConditionFacts()); facts.putAll(facts(f));
		facts.put("namedItemIds", "952,954"); facts.put("occupiedSlots", "28");
		headProof = headTuple; headSerial++; facts.put("hatEpisode", Long.toString(headSerial));
		return new SceneCandidate(base.getId(), "M44", 27, base.getCreatedTick(), base.getExpiresTick(),
			base.getSessionGeneration(), base.getSceneRevision(), base.getCausalToken(), facts,
			base.isIntentionalSilent(), base.getHoldTicks());
	}

	private void persistent(ScenarioSnapshot f, Bag bag, AnchorWindow window, boolean gain,
		String id, int priority, ExpandedScenarioRules.Factory factory, List<SceneCandidate> out)
	{
		if (!window.observe(f.tick, bag, gain)) { return; }
		Map<String, String> facts = facts(f); facts.put("namedItemIds", Integer.toString(window.item));
		facts.put("anchorSlot", Integer.toString(window.slot)); facts.put("anchorWitness", Long.toString(window.serial));
		facts.put("skill", window.skill.name()); facts.put("gainTicks", Integer.toString(window.gainCount));
		String terminal = window.changedNow ? "inventory-revision:" + f.tick : "skill-gain:" + window.skill + ":" + f.tick;
		out.add(factory.create(f, id, priority, 12, "PERSISTENT_ODD_PROP:" + terminal, facts, false, 8));
		window.restart(f.tick, bag);
	}

	private void arrangement(ScenarioSnapshot f, Bag bag, Bag old, ExpandedScenarioRules.Factory factory,
		List<SceneCandidate> out)
	{
		if (arrangementProof != null && !arrangementProof.equals(bag.layout)) { arrangementProof = null; }
		String multiset = bag.multiset(-1);
		if (bag.occupied < 8 || bag.distinctIds < 4)
		{
			resetArrangement(); return;
		}
		if (arrangementMultiset == null || !arrangementMultiset.equals(multiset))
		{
			resetArrangement(); arrangementMultiset = multiset; arrangementAnchor = bag.layout;
			stableArrangement = bag.layout; arrangementIds = bag.itemIds; return;
		}
		while (!arrangements.isEmpty() && arrangements.peekFirst().tick < f.tick - 99) { arrangements.removeFirst(); }
		if (bag.layout.equals(stableArrangement) || !bag.layout.equals(old.layout)) { return; }
		stableArrangement = bag.layout;
		if (bag.layout.equals(arrangementAnchor)) { return; }
		for (LayoutTick layout : arrangements) { if (layout.key.equals(bag.layout)) { return; } }
		arrangements.addLast(new LayoutTick(f.tick, bag.layout));
		while (arrangements.size() > 3) { arrangements.removeFirst(); }
		if (arrangements.size() == 3)
		{
			arrangementProof = bag.layout; arrangementSerial++;
			Map<String, String> facts = facts(f); facts.put("inventoryLayout", bag.layout);
			facts.put("anchorBagMultiset", arrangementMultiset); facts.put("anchorBagItemIds", arrangementIds);
			facts.put("arrangementEpisode", Long.toString(arrangementSerial));
			out.add(factory.create(f, "M46", 16, 12, "INVENTORY_ARRANGEMENT:confirmation:" + f.tick, facts, false, 6));
			arrangements.clear(); arrangementAnchor = bag.layout;
		}
	}

	boolean stillValid(SceneCandidate c, ScenarioSnapshot f)
	{
		return validate(c, f, false);
	}

	boolean continuationValid(SceneCandidate c, ScenarioSnapshot f)
	{
		return validate(c, f, true);
	}

	private boolean validate(SceneCandidate c, ScenarioSnapshot f, boolean continuation)
	{
		String id = c.getScenarioId();
		if (!handles(id)) { return true; }
		Map<String, String> facts = c.getConditionFacts(); Bag bag = Bag.from(f);
		if (!eligible || f.tick != lastTick || !permitted(f) || bag == null
			|| f.sessionGeneration != c.getSessionGeneration() || f.sceneRevision != c.getSceneRevision()
			|| (!continuation && f.tick >= c.getExpiresTick()) || f.tick < c.getCreatedTick()
			|| !Long.toString(witnessVersion).equals(facts.get("inventoryWitnessVersion"))
			|| !Long.toString(f.presentSinceTick).equals(facts.get("witnessPresenceEpoch"))) { return false; }
		if ("M39".equals(id))
		{
			return bag.eggKit() && bag.layout.equals(kitLayout) && bag.layout.equals(facts.get("inventoryLayout"))
				&& Long.toString(kitStart).equals(facts.get("kitEpisode"));
		}
		if ("M41".equals(id) || "M49".equals(id))
		{
			AnchorWindow window = "M41".equals(id) ? cabbage : rock;
			long latest = "M41".equals(id) ? latestFishing : latestMining;
			return window.slot >= 0 && bag.count(window.item) == 1 && bag.ids[window.slot] == window.item
				&& bag.quantities[window.slot] == 1 && Integer.toString(window.slot).equals(facts.get("anchorSlot"))
				&& Long.toString(window.serial).equals(facts.get("anchorWitness")) && f.tick - latest <= 5;
		}
		if ("M43".equals(id))
		{
			return bag.occupied == 27 && bag.layout.equals(spaceProof) && bag.layout.equals(facts.get("inventoryLayout"))
				&& Long.toString(spaceSerial).equals(facts.get("spaceEpisode"));
		}
		if ("M44".equals(id))
		{
			int[] worn = f.equipment();
			return bag.fullProps() && worn != null && worn.length == 4
				&& equipmentKey(worn).equals(headProof) && headProof.equals(facts.get("equipmentIds"))
				&& Long.toString(headSerial).equals(facts.get("hatEpisode"));
		}
		if ("M45".equals(id))
		{
			return bag.occupied == 28 && miningFullTicks == 60 && f.tick - latestMining <= 5
				&& Long.toString(miningStart).equals(facts.get("fullEpisode"));
		}
		return bag.layout.equals(arrangementProof) && bag.layout.equals(facts.get("inventoryLayout"))
			&& bag.multiset(-1).equals(facts.get("anchorBagMultiset"))
			&& Long.toString(arrangementSerial).equals(facts.get("arrangementEpisode"));
	}

	private static boolean handles(String id)
	{
		return "M39".equals(id) || "M41".equals(id) || "M43".equals(id) || "M44".equals(id)
			|| "M45".equals(id) || "M46".equals(id) || "M49".equals(id);
	}

	void reset()
	{
		witnessVersion++; lastTick = Long.MIN_VALUE; eligible = false; seeded = false; kitArmed = false;
		kitFalse = 0; kitHold = 0; kitMovement = 0; fullTicks = 0; propFullTicks = 0; miningFullTicks = 0;
		kitStart = -1; miningStart = -1; headStart = -1; miningHandled = false; headEligible = false;
		kitLayout = null; pendingSpace = null; spaceProof = null; headTuple = null; headPriorTuple = null; headProof = null;
		latestFishing = Long.MIN_VALUE / 2; latestMining = Long.MIN_VALUE / 2;
		fullMiningGains.clear(); cabbage.reset(); rock.reset(); resetArrangement();
	}

	private void resetArrangement()
	{
		arrangements.clear(); arrangementMultiset = null; arrangementAnchor = null;
		stableArrangement = null; arrangementIds = null; arrangementProof = null;
	}
	private Map<String, String> facts(ScenarioSnapshot f)
	{
		Map<String, String> facts = new HashMap<>(); facts.put("actualRegion", Integer.toString(f.player.region()));
		facts.put("inventoryWitnessVersion", Long.toString(witnessVersion));
		facts.put("witnessPresenceEpoch", Long.toString(f.presentSinceTick)); facts.put("witnessedPresent", "true");
		facts.put("occurredTick", Long.toString(f.tick)); return facts;
	}
	private static String equipmentKey(int[] ids) { return ids[0] + "," + ids[1] + "," + ids[2] + "," + ids[3]; }
	private static void addTick(Deque<Long> ticks, long tick, int cap)
	{
		if (ticks.isEmpty() || ticks.peekLast() != tick) { ticks.addLast(tick); }
		while (ticks.size() > cap) { ticks.removeFirst(); }
	}
	private static void prune(Deque<Long> ticks, long oldest)
	{
		while (!ticks.isEmpty() && ticks.peekFirst() < oldest) { ticks.removeFirst(); }
	}
	private static boolean recent(Deque<Long> ticks, int count, long now)
	{
		return ticks.size() == count && now - ticks.peekLast() <= 5;
	}
	private static final class LayoutTick
	{
		final long tick; final String key;
		LayoutTick(long tick, String key) { this.tick = tick; this.key = key; }
	}
	private static final class AnchorWindow
	{
		final int item, duration, gainCount; final Skill skill;
		final Deque<Long> gains = new ArrayDeque<>();
		final List<String> changes = new ArrayList<>(3);
		int slot = -1; long start, serial;
		String baseline, lastMultiset;
		boolean changedNow;
		AnchorWindow(int item, Skill skill, int duration, int gainCount)
		{
			this.item = item; this.skill = skill; this.duration = duration; this.gainCount = gainCount;
		}
		boolean observe(long tick, Bag bag, boolean gain)
		{
			changedNow = false;
			if (bag.count(item) != 1 || (slot >= 0 && (bag.ids[slot] != item || bag.quantities[slot] != 1))) { reset(); }
			if (slot < 0)
			{
				if (bag.count(item) != 1) { return false; }
				for (int i = 0; i < 28; i++) { if (bag.ids[i] == item && bag.quantities[i] == 1) { slot = i; break; } }
				if (slot < 0) { return false; }
				serial++; restart(tick, bag); return false;
			}
			if (tick - start >= duration) { restart(tick, bag); return false; }
			String current = bag.multiset(slot);
			changedNow = !current.equals(lastMultiset);
			if (changedNow && !current.equals(baseline) && !changes.contains(current) && changes.size() < 3) { changes.add(current); }
			lastMultiset = current;
			if (gain) { addTick(gains, tick, gainCount); }
			return changes.size() == 3 && recent(gains, gainCount, tick);
		}
		void restart(long tick, Bag bag)
		{
			start = tick; gains.clear(); changes.clear(); baseline = bag.multiset(slot); lastMultiset = baseline;
		}
		void reset() { if (slot >= 0) { serial++; } slot = -1; gains.clear(); changes.clear(); baseline = null; lastMultiset = null; }
	}
	private static final class Bag
	{
		final int[] ids, quantities;
		final int occupied, distinctIds;
		final String layout, itemIds;
		private Bag(int[] ids, int[] quantities)
		{
			this.ids = ids; this.quantities = quantities; int filled = 0; TreeSet<Integer> distinct = new TreeSet<>();
			for (int i = 0; i < 28; i++) { if (ids[i] >= 0 && quantities[i] > 0) { filled++; distinct.add(ids[i]); } }
			occupied = filled; distinctIds = distinct.size();
			layout = Arrays.toString(ids) + "/" + Arrays.toString(quantities);
			StringBuilder key = new StringBuilder();
			for (int id : distinct) { if (key.length() > 0) { key.append(','); } key.append(id); }
			itemIds = key.toString();
		}
		static Bag from(ScenarioSnapshot f)
		{
			if (f == null || !f.inventoryKnown()) { return null; }
			int[] ids = f.inventoryIds(), quantities = f.inventoryQuantities();
			if (ids == null || quantities == null || ids.length != 28 || quantities.length != 28) { return null; }
			for (int i = 0; i < 28; i++)
			{
				if (!((ids[i] >= 0 && quantities[i] > 0) || (ids[i] == -1 && quantities[i] == 0))) { return null; }
			}
			return new Bag(ids, quantities);
		}
		long count(int item)
		{
			long count = 0; for (int i = 0; i < 28; i++) { if (ids[i] == item) { count += quantities[i]; } } return count;
		}
		boolean eggKit() { return occupied == 3 && count(954) == 1 && count(1925) == 1 && count(1944) == 1; }
		boolean fullProps() { return occupied == 28 && count(952) > 0 && count(954) > 0; }
		String multiset(int excluded)
		{
			long[] pairs = new long[occupied]; int n = 0;
			for (int i = 0; i < 28; i++)
			{
				if (i != excluded && ids[i] >= 0) { pairs[n++] = ((long) ids[i] << 32) | (quantities[i] & 0xffff_ffffL); }
			}
			pairs = Arrays.copyOf(pairs, n); Arrays.sort(pairs); return Arrays.toString(pairs);
		}
	}
}
