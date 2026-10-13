package com.malfoid.presence;

import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;

/** E.4: world/zone signals alone decide whether cosmetic behaviour may read other players. */
public final class OtherPlayerPolicy
{
	private OtherPlayerPolicy() { }

	public static boolean ignoreOtherPlayers(Client client)
	{
		EnumSet<WorldType> types = client.getWorldType();
		return client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1
			|| types != null && WorldType.isPvpWorld(types);
	}
}
