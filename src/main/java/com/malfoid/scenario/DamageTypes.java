package com.malfoid.scenario;

import net.runelite.api.HitsplatID;

/** Audited ordinary-HP whitelist. Poise and damage-over-time are deliberately excluded. */
public final class DamageTypes
{
	private DamageTypes() { }
	public static boolean hpDamage(int type)
	{
		switch (type)
		{
			case HitsplatID.DAMAGE_ME: case HitsplatID.DAMAGE_OTHER:
			case HitsplatID.DAMAGE_ME_CYAN: case HitsplatID.DAMAGE_OTHER_CYAN:
			case HitsplatID.DAMAGE_ME_YELLOW: case HitsplatID.DAMAGE_OTHER_YELLOW:
			case HitsplatID.DAMAGE_ME_ORANGE: case HitsplatID.DAMAGE_OTHER_ORANGE:
			case HitsplatID.DAMAGE_ME_WHITE: case HitsplatID.DAMAGE_OTHER_WHITE:
			case HitsplatID.DAMAGE_MAX_ME: case HitsplatID.DAMAGE_MAX_ME_CYAN:
			case HitsplatID.DAMAGE_MAX_ME_YELLOW: case HitsplatID.DAMAGE_MAX_ME_ORANGE:
			case HitsplatID.DAMAGE_MAX_ME_WHITE:
				return true;
			default: return false;
		}
	}
	public static boolean combat(int type) { return hpDamage(type) || type == HitsplatID.BLOCK_ME || type == HitsplatID.BLOCK_OTHER; }
	public static int largeHitThreshold(int hp) { return hp <= 0 ? Integer.MAX_VALUE : (int) ((7L * hp + 9) / 10); }
}
