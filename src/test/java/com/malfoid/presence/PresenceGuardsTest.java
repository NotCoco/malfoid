package com.malfoid.presence;

import org.junit.Test;

import static org.junit.Assert.*;

public class PresenceGuardsTest
{
	@Test
	public void documentedPvpAreaFlagHidesWhenPlayerAttackOptionsAreDisabled()
	{
		assertEquals(PresenceReason.PVP_AREA, PresenceGuards.pvpArea(1,
			new String[] {null, "Follow", "Trade with"}));
		assertEquals(PresenceReason.PVP_AREA, PresenceGuards.pvpArea(1, null));
	}

	@Test
	public void taggedAttackOptionStillHidesWithoutAreaFlag()
	{
		assertEquals(PresenceReason.PVP_AREA, PresenceGuards.pvpArea(0,
			new String[] {"Follow", "<col=ff0000>aTtAcK</col>"}));
		assertEquals(PresenceReason.NONE, PresenceGuards.pvpArea(0,
			new String[] {null, "Follow", "Trade with"}));
	}

	@Test
	public void unknownPvpFlagsAndMissingOptionsCannotAuthorizePresence()
	{
		assertEquals(PresenceReason.INVALID_SCENE, PresenceGuards.pvpArea(2, new String[0]));
		assertEquals(PresenceReason.INVALID_SCENE, PresenceGuards.pvpArea(-1, new String[0]));
		assertEquals(PresenceReason.INVALID_SCENE, PresenceGuards.pvpArea(0, null));
	}

	@Test
	public void onlyDocumentedDeadSpectatingStatusIsADeathGuard()
	{
		assertTrue(PresenceGuards.isDeadSpectating(3));
		assertFalse(PresenceGuards.isDeadSpectating(0));
		assertFalse(PresenceGuards.isDeadSpectating(1));
		assertFalse(PresenceGuards.isDeadSpectating(2));
	}
}
