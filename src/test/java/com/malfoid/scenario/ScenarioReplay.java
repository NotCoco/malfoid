package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.Skill;

/** Replays feed the same immutable adapter boundary that production commits. */
final class ScenarioReplay
{
	final ScenarioRules rules = new ScenarioRules();
	final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
	long fact;
	ScenarioReplay() { this(12); }
	ScenarioReplay(int baselineTicks)
	{
		frame.xp = new int[Skill.values().length]; Arrays.fill(frame.xp, 100);
		frame.levels = new int[Skill.values().length]; Arrays.fill(frame.levels, 10);
		frame.boosted = frame.levels.clone(); frame.equipment = new int[]{100, 200, 300, 400};
		frame.inventoryIds = new int[28]; Arrays.fill(frame.inventoryIds, -1); frame.inventoryQuantities = new int[28];
		frame.player = new Position(3100, 3200, 0); frame.companion = new Position(3103, 3200, 0);
		for (int i = 0; i < baselineTicks; i++) { next(); }
	}
	List<SceneCandidate> next() { return next(ScenarioBatch.empty()); }
	List<SceneCandidate> next(ScenarioBatch batch) { frame.tick++; return rules.close(frame.build(), batch); }
	void xp(Skill skill, int delta) { frame.xp[skill.ordinal()] += delta; }
	void level(Skill skill, int level) { frame.levels[skill.ordinal()] = level; }
	void item(int slot, int id, int quantity) { frame.inventoryIds[slot] = id; frame.inventoryQuantities[slot] = quantity; }
	Hit hit(boolean local, String npc, int type, int amount, boolean mine)
	{
		return new Hit(++fact, local, npc, type, amount, mine, frame.present);
	}
	static ScenarioBatch hits(Hit... hits)
	{
		return new ScenarioBatch(false, false, false, Arrays.asList(hits), Collections.emptyList(), Collections.emptySet());
	}
	static ScenarioBatch stats(Skill... skills)
	{
		EnumSet<Skill> known = EnumSet.noneOf(Skill.class); Collections.addAll(known, skills);
		return new ScenarioBatch(false, false, false, Collections.emptyList(), Collections.emptyList(), known);
	}
	static ScenarioBatch loot(Loot... reports)
	{
		return new ScenarioBatch(false, false, false, Collections.emptyList(), Arrays.asList(reports), Collections.emptySet());
	}
	static SceneCandidate find(List<SceneCandidate> output, String id)
	{
		return output.stream().filter(c -> c.getScenarioId().equals(id)).findFirst().orElse(null);
	}
	static long count(List<SceneCandidate> output, String id) { return output.stream().filter(c -> c.getScenarioId().equals(id)).count(); }
}
