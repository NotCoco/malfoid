package com.malfoid.additional;

import java.util.Arrays;
import net.runelite.api.Skill;

/** One normalized numeric copy. Negative poison values are legitimate known values. */
public final class AdditionalNumericSnapshot
{
	public static final int UNKNOWN = Integer.MIN_VALUE, SKILL_COUNT = 24, MAX_WATCHES = 16;
	public enum Watch
	{
		POISON(false, 102), ANTIFIRE(true, 3981), SUPER_ANTIFIRE(true, 6101), REGULAR_SLAYER(true, 4069), COLLECTION_LOG_CHAT(true, 11959);
		public final boolean varbit;
		public final int id;
		Watch(boolean varbit, int id) { this.varbit = varbit; this.id = id; }
	}
	private final int energy, weight;
	private final int[] xp, levels, boosted, watched;
	public final boolean skillsKnown;
	public AdditionalNumericSnapshot(int energy, int weight, int[] xp, int[] levels, int[] boosted, int[] watched)
	{
		this.energy = energy >= 0 && energy <= 10000 ? energy : -1; this.weight = weight;
		this.xp = skills(xp); this.levels = skills(levels); this.boosted = skills(boosted);
		this.skillsKnown = known(this.xp) && known(this.levels) && known(this.boosted);
		this.watched = new int[Watch.values().length]; Arrays.fill(this.watched, UNKNOWN);
		if (watched != null && watched.length == this.watched.length) { System.arraycopy(watched, 0, this.watched, 0, watched.length); }
	}
	private static int[] skills(int[] values)
	{
		int[] copy = new int[SKILL_COUNT]; Arrays.fill(copy, -1);
		if (values != null && values.length == SKILL_COUNT)
		{
			for (int i = 0; i < copy.length; i++) { copy[i] = values[i] >= 0 ? values[i] : -1; }
		}
		return copy;
	}
	private static boolean known(int[] values) { for (int value : values) { if (value < 0) { return false; } } return true; }
	public int energy() { return energy; }
	public int weight() { return weight; }
	public int xp(Skill skill) { return skill == null ? -1 : xp[skill.ordinal()]; }
	public int level(Skill skill) { return skill == null ? -1 : levels[skill.ordinal()]; }
	public int boosted(Skill skill) { return skill == null ? -1 : boosted[skill.ordinal()]; }
	public int watched(Watch watch) { return watch == null ? UNKNOWN : watched[watch.ordinal()]; }
	public static AdditionalNumericSnapshot unknown() { return new AdditionalNumericSnapshot(-1, UNKNOWN, null, null, null, null); }
}
