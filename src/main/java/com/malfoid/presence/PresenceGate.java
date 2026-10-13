package com.malfoid.presence;

/** Pure committed-tick gate; client cycles may revoke permission but never earn stability. */
final class PresenceGate
{
	private long lastTick = -1;
	private long sceneGeneration = -1;
	private long deathTick = -1;
	private long lastPlacementTick = -1;
	private long presenceEpoch;
	private long presentSinceTick = -1;
	private int stableTicks;
	private int requiredStableTicks = 2;
	private PresenceController.Decision decision = decision(PresenceState.SUSPENDED,
		PresenceReason.LOGGED_OUT, false, false);

	PresenceController.Decision commit(long tick, long scene, PresenceReason guard,
		boolean show, boolean ready, boolean failed, boolean placed)
	{
		boolean distinctCommit = tick > lastTick;
		if (distinctCommit)
		{
			lastTick = tick;
		}
		return evaluate(scene, guard, show, ready, failed, placed, distinctCommit);
	}

	PresenceController.Decision inspect(long scene, PresenceReason guard,
		boolean show, boolean ready, boolean failed, boolean placed)
	{
		return evaluate(scene, guard, show, ready, failed, placed, false);
	}

	private PresenceController.Decision evaluate(long scene, PresenceReason guard,
		boolean show, boolean ready, boolean failed, boolean placed, boolean commit)
	{
		if (sceneGeneration != scene)
		{
			sceneGeneration = scene;
			invalidateScene();
		}
		if (guard != PresenceReason.NONE && isLifecycle(guard))
		{
			return absent(PresenceState.SUSPENDED, guard);
		}
		if (deathTick >= 0 && lastTick <= deathTick)
		{
			return absent(PresenceState.SUSPENDED, PresenceReason.DEAD);
		}
		if (guard == PresenceReason.PVP_AREA || guard == PresenceReason.ABOARD)
		{
			requiredStableTicks = 5;
		}
		if (!show)
		{
			return absent(PresenceState.ABSENT_USER, PresenceReason.USER);
		}
		if (guard != PresenceReason.NONE)
		{
			return absent(PresenceState.ABSENT_AUTO, guard);
		}
		if (failed)
		{
			return absent(PresenceState.ABSENT_AUTO, PresenceReason.MODEL_UNAVAILABLE);
		}
		if (commit && stableTicks < requiredStableTicks)
		{
			stableTicks++;
		}
		if (!ready)
		{
			leavePresent();
			return decision = decision(PresenceState.ABSENT_AUTO, PresenceReason.PREPARING, false, true);
		}
		if (stableTicks < requiredStableTicks)
		{
			leavePresent();
			return decision = decision(PresenceState.SUSPENDED, PresenceReason.STABILIZING, false, true);
		}
		if (placed)
		{
			enterPresent();
			return decision = decision(PresenceState.PRESENT, PresenceReason.NONE, true, true);
		}
		leavePresent();
		return decision = decision(PresenceState.ABSENT_AUTO, PresenceReason.NO_LEGAL_TILE, true, true);
	}

	private PresenceController.Decision absent(PresenceState state, PresenceReason reason)
	{
		stableTicks = 0;
		leavePresent();
		return decision = decision(state, reason, false, false);
	}

	private static boolean isLifecycle(PresenceReason reason)
	{
		return reason == PresenceReason.LOGGED_OUT || reason == PresenceReason.LOADING
			|| reason == PresenceReason.CONNECTION_LOST || reason == PresenceReason.DEAD
			|| reason == PresenceReason.SPECTATING
			|| reason == PresenceReason.INVALID_SCENE;
	}

	void onLocalDeath(long tick)
	{
		deathTick = tick;
		absent(PresenceState.SUSPENDED, PresenceReason.DEAD);
	}

	void invalidateScene()
	{
		if (lastPlacementTick >= 0 && lastTick - lastPlacementTick < 15)
		{
			requiredStableTicks = 5;
		}
		stableTicks = 0;
		leavePresent();
		decision = decision(PresenceState.SUSPENDED, PresenceReason.STABILIZING, false, false);
	}

	PresenceController.Decision placementSucceeded(long tick)
	{
		if (decision.isPlacementAllowed())
		{
			lastTick = Math.max(lastTick, tick);
			enterPresent();
			decision = decision(PresenceState.PRESENT, PresenceReason.NONE, true, true);
		}
		return decision;
	}

	private void enterPresent()
	{
		if (decision.getState() != PresenceState.PRESENT)
		{
			presenceEpoch++;
			presentSinceTick = lastTick;
			lastPlacementTick = lastTick;
			deathTick = -1;
			requiredStableTicks = 2;
		}
	}

	private void leavePresent()
	{
		presentSinceTick = -1;
	}

	PresenceController.Decision getDecision()
	{
		return decision;
	}

	private PresenceController.Decision decision(PresenceState state, PresenceReason reason,
		boolean placementAllowed, boolean preparationAllowed)
	{
		return new PresenceController.Decision(state, reason, placementAllowed, preparationAllowed,
			presentSinceTick, presenceEpoch, stableTicks, requiredStableTicks);
	}
}
