package com.malfoid.presence;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.util.Text;

/** E.4 combat evidence only. No opponent identity, position, count, gear or actions are retained. */
final class PlayerCombat
{
	private final Client client;
	private long lastCommit = -1, lastEvidenceTick = -1, lastIncomingTargetTick = -1;
	private long quietCompletedTick = -1;
	private int quietTicks;
	private boolean active;

	PlayerCombat(Client client) { this.client = client; }

	boolean onMenuOptionClicked(MenuOptionClicked event, long tick)
	{
		Player local = localPlayer();
		MenuEntry entry = event == null ? null : event.getMenuEntry();
		String option = entry == null ? null : entry.getOption();
		if (local == null || option == null || !"Attack".equalsIgnoreCase(Text.removeTags(option).trim())
			|| !isPlayerOption(entry.getType())) { return false; }
		Player target = entry.getPlayer();
		return target != null && target != local && evidence(tick);
	}

	boolean onHitsplatApplied(HitsplatApplied event, long tick)
	{
		Player local = localPlayer();
		Actor actor = event == null ? null : event.getActor();
		Hitsplat hit = event == null ? null : event.getHitsplat();
		if (local == null || actor == null || hit == null) { return false; }
		if (actor != local && actor instanceof Player && hit.isMine()) { return evidence(tick); }
		if (actor != local) { return false; }
		sampleIncomingTarget(local, tick);
		return lastIncomingTargetTick >= 0 && tick >= lastIncomingTargetTick
			&& tick - lastIncomingTargetTick <= 2 && evidence(tick);
	}

	void onInteractingChanged(InteractingChanged event, long tick)
	{
		Player local = localPlayer();
		if (local != null && event != null && event.getSource() instanceof Player
			&& event.getSource() != local && event.getTarget() == local)
		{
			lastIncomingTargetTick = Math.max(lastIncomingTargetTick, tick);
		}
	}

	/** The hundredth evidence-free commit completes the wait; normal stability starts afterwards. */
	boolean commit(long tick)
	{
		Player local = localPlayer();
		Boolean incoming = local == null ? null : sampleIncomingTarget(local, tick);
		if (tick > lastCommit)
		{
			lastCommit = tick;
			if (active && local != null && incoming != null && tick > lastEvidenceTick)
			{
				quietTicks = Math.min(100, quietTicks + 1);
				if (quietTicks == 100 && quietCompletedTick < 0) { quietCompletedTick = tick; }
			}
		}
		return isActive();
	}

	/** Inspection rechecks current targets during stability, but cannot advance the quiet interval. */
	boolean isActive()
	{
		if (!active) { return false; }
		if (quietTicks < 100 || lastCommit <= quietCompletedTick) { return true; }
		Player local = localPlayer();
		Boolean incoming = local == null ? null : sampleIncomingTarget(local, lastCommit);
		return incoming == null || incoming || local.getInteracting() instanceof Player;
	}

	void returned() { active = false; }

	void invalidateScene() { lastIncomingTargetTick = -1; }

	void reset()
	{
		lastCommit = lastEvidenceTick = lastIncomingTargetTick = -1;
		quietCompletedTick = -1;
		quietTicks = 0; active = false;
	}

	private boolean evidence(long tick)
	{
		lastEvidenceTick = Math.max(lastEvidenceTick, tick);
		quietTicks = 0; quietCompletedTick = -1; active = true;
		return true;
	}

	private Player localPlayer()
	{
		return client.getGameState() == GameState.LOGGED_IN ? client.getLocalPlayer() : null;
	}

	/** These target relations are the only permitted other-player reads, for evidence and return. */
	private Boolean sampleIncomingTarget(Player local, long tick)
	{
		WorldView view = local.getWorldView();
		Iterable<? extends Player> players = view == null ? null : view.players();
		if (players == null) { return null; }
		for (Player other : players)
		{
			if (other != null && other != local && other.getInteracting() == local)
			{
				lastIncomingTargetTick = Math.max(lastIncomingTargetTick, tick);
				return true;
			}
		}
		return false;
	}

	private static boolean isPlayerOption(MenuAction action)
	{
		return action == MenuAction.PLAYER_FIRST_OPTION || action == MenuAction.PLAYER_SECOND_OPTION
			|| action == MenuAction.PLAYER_THIRD_OPTION || action == MenuAction.PLAYER_FOURTH_OPTION
			|| action == MenuAction.PLAYER_FIFTH_OPTION || action == MenuAction.PLAYER_SIXTH_OPTION
			|| action == MenuAction.PLAYER_SEVENTH_OPTION || action == MenuAction.PLAYER_EIGHTH_OPTION;
	}
}
