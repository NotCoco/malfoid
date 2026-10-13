package com.malfoid.interaction;

import java.util.ArrayDeque;
import java.util.Deque;

/** U01 request etiquette. Coalesced requests never extend or increment the streak. */
public final class TalkState
{
	private final Deque<Long> accepted = new ArrayDeque<>(4);
	private long lastAccepted = -1;
	private boolean turnAway;

	/** Returns zero for a coalesced request, 1-3 for a speaking pool, or 4 for silent turn-away. */
	public int accept(long tick)
	{
		if (lastAccepted >= 0 && tick - lastAccepted < 2)
		{
			return 0;
		}
		if (turnAway && tick - lastAccepted >= 50)
		{
			turnAway = false;
			accepted.clear();
		}
		lastAccepted = tick;
		if (turnAway)
		{
			return 4;
		}
		while (!accepted.isEmpty() && accepted.peekFirst() < tick - 20)
		{
			accepted.removeFirst();
		}
		int streak = Math.min(4, accepted.size() + 1);
		accepted.addLast(tick);
		turnAway = streak == 4;
		return streak;
	}

	public void reset()
	{
		accepted.clear();
		lastAccepted = -1;
		turnAway = false;
	}
}
