package com.malfoid.presence;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlayerCombatTest
{
	@Test public void allEightNativePlayerAttackOptionsCountButOtherActionsAndNpcAttackDoNot()
	{
		for (MenuAction action : new MenuAction[] {MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
			MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION, MenuAction.PLAYER_FIFTH_OPTION,
			MenuAction.PLAYER_SIXTH_OPTION, MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION})
		{
			Fixture f = new Fixture();
			assertTrue(f.combat.onMenuOptionClicked(f.menu(action, "<col=ff0000>aTtAcK</col>", f.other), 3));
			assertTrue(f.combat.isActive());
		}
		Fixture f = new Fixture();
		assertFalse(f.combat.onMenuOptionClicked(f.menu(MenuAction.PLAYER_FIRST_OPTION, "Follow", f.other), 3));
		assertFalse(f.combat.onMenuOptionClicked(f.menu(MenuAction.NPC_SECOND_OPTION, "Attack", f.other), 3));
		assertFalse(f.combat.onMenuOptionClicked(f.menu(MenuAction.RUNELITE_PLAYER, "Attack", f.other), 3));
		assertFalse(f.combat.onMenuOptionClicked(f.menu(MenuAction.PLAYER_FIRST_OPTION, "Attack", null), 3));
		assertFalse(f.combat.onMenuOptionClicked(f.menu(MenuAction.PLAYER_FIRST_OPTION, "Attack", f.local), 3));
		assertFalse(f.combat.isActive());
	}

	@Test public void ownHitsplatOnAnotherPlayerCountsEvenAtZeroButNpcAndOtherPlayersHitsDoNot()
	{
		Fixture f = new Fixture();
		assertFalse(f.combat.onHitsplatApplied(f.hit(f.other, false), 3));
		assertFalse(f.combat.onHitsplatApplied(f.hit(mock(NPC.class), true), 3));
		assertFalse(f.combat.onHitsplatApplied(f.hit(f.local, true), 3));
		assertFalse(f.combat.isActive());
		assertTrue(f.combat.onHitsplatApplied(f.hit(f.other, true), 3));
		verify(f.other, never()).getLocalLocation(); verify(f.other, never()).getPlayerComposition();
	}

	@Test public void localHitsplatUsesCurrentAndExactlyTwoPriorTickTargetRelations()
	{
		for (long age = 0; age <= 3; age++)
		{
			Fixture f = new Fixture();
			f.combat.onInteractingChanged(new InteractingChanged(f.other, f.local), 10);
			assertEquals("Target history age " + age, age <= 2,
				f.combat.onHitsplatApplied(f.hit(f.local, false), 10 + age));
		}
		Fixture current = new Fixture(); when(current.other.getInteracting()).thenReturn(current.local);
		assertTrue(current.combat.onHitsplatApplied(current.hit(current.local, false), 3));
		Fixture committed = new Fixture(); when(committed.other.getInteracting()).thenReturn(committed.local);
		assertFalse(committed.combat.commit(8)); when(committed.other.getInteracting()).thenReturn(null);
		assertTrue(committed.combat.onHitsplatApplied(committed.hit(committed.local, false), 10));
	}

	@Test public void targetingAloneAndLocalTargetingAnotherPlayerNeverStartAbsence()
	{
		Fixture f = new Fixture(); when(f.local.getInteracting()).thenReturn(f.other);
		when(f.other.getInteracting()).thenReturn(f.local);
		f.combat.onInteractingChanged(new InteractingChanged(f.other, f.local), 3);
		for (long tick = 3; tick < 130; tick++) { assertFalse(f.combat.commit(tick)); }
	}

	@Test public void onlyDistinctCommittedEvidenceFreeTicksEarnTheHundredTickReturn()
	{
		Fixture f = new Fixture(); f.attack(3);
		assertTrue(f.combat.commit(3));
		for (long tick = 4; tick <= 103; tick++)
		{
			for (int cycle = 0; cycle < 10; cycle++) { assertTrue(f.combat.isActive()); }
			assertTrue("Includes hundredth quiet tick " + tick, f.combat.commit(tick));
			assertTrue(f.combat.commit(tick));
		}
		assertFalse(f.combat.commit(104));
	}

	@Test public void freshEvidenceRestartsQuietTimeAndEitherCurrentTargetDirectionBlocksReturn()
	{
		Fixture f = new Fixture(); f.attack(3); f.combat.commit(3);
		for (long tick = 4; tick <= 70; tick++) { assertTrue(f.combat.commit(tick)); }
		f.attack(71); assertTrue(f.combat.commit(71));
		for (long tick = 72; tick <= 171; tick++) { assertTrue(f.combat.commit(tick)); }
		when(f.local.getInteracting()).thenReturn(f.other); assertTrue(f.combat.commit(172));
		when(f.local.getInteracting()).thenReturn(null); when(f.other.getInteracting()).thenReturn(f.local);
		assertTrue(f.combat.commit(173)); when(f.other.getInteracting()).thenReturn(null);
		assertFalse(f.combat.commit(174));
		when(f.other.getInteracting()).thenReturn(f.local);
		assertTrue("A target arriving during stability revokes its permission", f.combat.isActive());
		when(f.other.getInteracting()).thenReturn(null); assertFalse(f.combat.isActive());
		f.combat.returned(); when(f.other.getInteracting()).thenReturn(f.local);
		assertFalse("After return, targets alone still are not combat evidence", f.combat.isActive());
	}

	@Test public void missingTargetListAndLoggedOutCommitsCannotEstablishQuietReturn()
	{
		Fixture f = new Fixture(); f.attack(3); f.combat.commit(3);
		doReturn(null).when(f.view).players();
		for (long tick = 4; tick < 104; tick++) { assertTrue(f.combat.commit(tick)); }
		f.players(Collections.singletonList(f.other)); when(f.client.getGameState()).thenReturn(GameState.LOADING);
		for (long tick = 104; tick < 204; tick++) { assertTrue(f.combat.commit(tick)); }
		when(f.client.getGameState()).thenReturn(GameState.LOGGED_IN);
		for (long tick = 204; tick <= 303; tick++) { assertTrue(f.combat.commit(tick)); }
		assertFalse(f.combat.commit(304));
	}

	@Test public void sceneReplacementForgetsTargetHistoryButRetainsCombatWaitUntilSessionReset()
	{
		Fixture f = new Fixture();
		f.combat.onInteractingChanged(new InteractingChanged(f.other, f.local), 3);
		f.combat.invalidateScene();
		assertFalse(f.combat.onHitsplatApplied(f.hit(f.local, false), 4));
		f.attack(4); f.combat.invalidateScene(); assertTrue(f.combat.isActive());
		f.combat.reset(); assertFalse(f.combat.isActive()); assertFalse(f.combat.commit(5));
	}

	@Test public void cosmeticPolicyUsesOnlyWildernessAndActualPvpWorldFlags()
	{
		Fixture f = new Fixture(); when(f.client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		assertFalse(OtherPlayerPolicy.ignoreOtherPlayers(f.client));
		when(f.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
		assertTrue(OtherPlayerPolicy.ignoreOtherPlayers(f.client));
		when(f.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(0);
		for (WorldType type : Arrays.asList(WorldType.PVP, WorldType.DEADMAN))
		{
			when(f.client.getWorldType()).thenReturn(EnumSet.of(type));
			assertTrue(OtherPlayerPolicy.ignoreOtherPlayers(f.client));
		}
		when(f.client.getWorldType()).thenReturn(EnumSet.of(WorldType.PVP_ARENA));
		assertFalse(OtherPlayerPolicy.ignoreOtherPlayers(f.client));
	}

	private static final class Fixture
	{
		final Client client = mock(Client.class);
		final Player local = mock(Player.class), other = mock(Player.class);
		final WorldView view = mock(WorldView.class);
		final PlayerCombat combat = new PlayerCombat(client);
		Fixture()
		{
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(client.getLocalPlayer()).thenReturn(local);
			when(local.getWorldView()).thenReturn(view); players(Collections.singletonList(other));
		}
		void players(List<Player> values)
		{
			doReturn(new IndexedObjectSet<Player>()
			{
				public Player byIndex(int index) { return values.get(index); }
				public Iterator<Player> iterator() { return values.iterator(); }
			}).when(view).players();
		}
		MenuOptionClicked menu(MenuAction action, String option, Player target)
		{
			MenuEntry entry = mock(MenuEntry.class); when(entry.getType()).thenReturn(action);
			when(entry.getOption()).thenReturn(option); when(entry.getPlayer()).thenReturn(target);
			return new MenuOptionClicked(entry);
		}
		void attack(long tick) { assertTrue(combat.onMenuOptionClicked(menu(MenuAction.PLAYER_FIRST_OPTION, "Attack", other), tick)); }
		HitsplatApplied hit(Actor actor, boolean mine)
		{
			Hitsplat hit = mock(Hitsplat.class); when(hit.isMine()).thenReturn(mine);
			HitsplatApplied event = new HitsplatApplied(); event.setActor(actor); event.setHitsplat(hit); return event;
		}
	}
}
