package com.malfoid.interaction;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TalkStateTest
{
	@Test
	public void fourthRequestLatchesUntilFiftyTicksWithoutAnAcceptedRequest()
	{
		TalkState state = new TalkState();
		assertEquals(1, state.accept(100));
		assertEquals(2, state.accept(103));
		assertEquals(3, state.accept(106));
		assertEquals(4, state.accept(109));
		assertEquals(4, state.accept(112));
		assertEquals(4, state.accept(125));
		assertEquals(4, state.accept(170));
		assertEquals(1, state.accept(220));
	}

	@Test
	public void exactFiftyTickBoundaryRestartsAtOne()
	{
		TalkState state = new TalkState();
		state.accept(100);
		state.accept(103);
		state.accept(106);
		state.accept(109);
		state.accept(125);
		assertEquals(1, state.accept(175));
	}

	@Test
	public void commandsUnderTwoTicksCoalesceWithoutExtendingTheWindow()
	{
		TalkState state = new TalkState();
		assertEquals(1, state.accept(10));
		assertEquals(0, state.accept(10));
		assertEquals(0, state.accept(11));
		assertEquals(2, state.accept(12));
		assertEquals(3, state.accept(14));
		assertEquals(4, state.accept(16));
		assertEquals(0, state.accept(17));
		assertEquals(1, state.accept(66));
	}

	@Test
	public void preLatchStreakCountsThePreviousTwentyTickWindow()
	{
		TalkState state = new TalkState();
		assertEquals(1, state.accept(10));
		assertEquals(2, state.accept(30));
		assertEquals(2, state.accept(32));
		assertEquals(1, state.accept(53));
	}

	@Test
	public void resetDropsTheLatchedSessionHistory()
	{
		TalkState state = new TalkState();
		state.accept(10);
		state.accept(12);
		state.accept(14);
		state.accept(16);
		state.reset();
		assertEquals(1, state.accept(1));
	}
}
