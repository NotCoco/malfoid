package com.malfoid.presence;

import net.runelite.client.util.Text;

/** Exact pinned client signals; no inferred combat, instance or opponent classification. */
final class PresenceGuards
{
	private PresenceGuards() { }

	static boolean isDeadSpectating(int theatrePartyStatus)
	{
		// Varbits.THEATRE_OF_BLOOD documents 3 as dead spectating; 2 also includes live participants.
		return theatrePartyStatus == 3;
	}

	static PresenceReason pvpArea(int areaFlag, String[] playerOptions)
	{
		// Varbits.PVP_SPEC_ORB (the current PVP_AREA_CLIENT) documents 0 outside and 1 inside.
		if (areaFlag == 1)
		{
			return PresenceReason.PVP_AREA;
		}
		if (areaFlag != 0 || playerOptions == null)
		{
			return PresenceReason.INVALID_SCENE;
		}
		for (String option : playerOptions)
		{
			if (option != null && "Attack".equalsIgnoreCase(Text.removeTags(option).trim()))
			{
				return PresenceReason.PVP_AREA;
			}
		}
		return PresenceReason.NONE;
	}
}
