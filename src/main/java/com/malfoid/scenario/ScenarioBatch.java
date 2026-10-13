package com.malfoid.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;

/** Copied callbacks in one open GameTick batch, including nested loot callbacks. */
public final class ScenarioBatch
{
	public final boolean localDeath, deathWhileUserHidden, engagementInterrupted;
	public final List<Hit> hits;
	public final List<Loot> loot;
	public final List<NpcDeath> npcDeaths;
	public final List<String> localTargetChanges;
	public final boolean targetChangesUnknown;
	public final Set<Skill> witnessedSkills;
	public ScenarioBatch(boolean localDeath, boolean deathWhileUserHidden, boolean engagementInterrupted, List<Hit> hits, List<Loot> loot, Set<Skill> witnessedSkills)
	{
		this(localDeath, deathWhileUserHidden, engagementInterrupted, hits, loot, witnessedSkills, Collections.emptyList(), Collections.emptyList(), false);
	}
	public ScenarioBatch(boolean localDeath, boolean deathWhileUserHidden, boolean engagementInterrupted, List<Hit> hits, List<Loot> loot,
		Set<Skill> witnessedSkills, List<NpcDeath> npcDeaths, List<String> localTargetChanges, boolean targetChangesUnknown)
	{
		this.localDeath = localDeath; this.deathWhileUserHidden = deathWhileUserHidden;
		this.engagementInterrupted = engagementInterrupted;
		this.hits = Collections.unmodifiableList(new ArrayList<>(hits));
		this.loot = Collections.unmodifiableList(new ArrayList<>(loot));
		this.npcDeaths = Collections.unmodifiableList(new ArrayList<>(npcDeaths));
		this.localTargetChanges = Collections.unmodifiableList(new ArrayList<>(localTargetChanges));
		this.targetChangesUnknown = targetChangesUnknown;
		EnumSet<Skill> skills = EnumSet.noneOf(Skill.class); skills.addAll(witnessedSkills);
		this.witnessedSkills = Collections.unmodifiableSet(skills);
	}
	public static ScenarioBatch empty()
	{
		return new ScenarioBatch(false, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet());
	}
	public static final class Hit
	{
		public final long id;
		public final String npcKey;
		public final boolean local, mine, witnessed;
		public final int type, amount;
		public Hit(long id, boolean local, String npcKey, int type, int amount, boolean mine, boolean witnessed)
		{
			this.id = id; this.local = local; this.npcKey = npcKey; this.type = type;
			this.amount = amount; this.mine = mine; this.witnessed = witnessed;
		}
	}
	public static final class NpcDeath
	{
		public final long id;
		public final String npcKey;
		public final boolean witnessed;
		public NpcDeath(long id, String npcKey, boolean witnessed) { this.id = id; this.npcKey = npcKey; this.witnessed = witnessed; }
	}
	public static final class Stack
	{
		public final int id, quantity;
		public final String name;
		public final long unitPrice;
		public Stack(int id, int quantity, String name, long unitPrice)
		{
			this.id = id; this.quantity = quantity; this.name = name; this.unitPrice = unitPrice;
		}
		public long total()
		{
			return quantity <= 0 || unitPrice <= 0 || unitPrice > Long.MAX_VALUE / quantity ? -1 : unitPrice * quantity;
		}
	}
	public static final class Loot
	{
		public final long id;
		public final String npcKey;
		public final boolean witnessed;
		public final List<Stack> stacks;
		public Loot(long id, String npcKey, boolean witnessed, List<Stack> stacks)
		{
			this.id = id; this.npcKey = npcKey; this.witnessed = witnessed;
			this.stacks = Collections.unmodifiableList(new ArrayList<>(stacks));
		}
	}
}
