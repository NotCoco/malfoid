package com.malfoid;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Manual development entry point; this is not a gameplay unit test. */
public final class MalfoidPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MalfoidPlugin.class);
		RuneLite.main(args);
	}
}
