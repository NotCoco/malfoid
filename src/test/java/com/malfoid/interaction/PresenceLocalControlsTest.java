package com.malfoid.interaction;

import com.malfoid.presence.PresenceController;
import com.malfoid.presence.PresenceReason;
import com.malfoid.presence.PresenceState;
import com.malfoid.asset.NativeActor;
import com.google.gson.Gson;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.MenuOpened;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PresenceLocalControlsTest
{
	@Test public void staleMenuReportsOneDefiniteAbsenceBeforeAddingEntriesOrRunningAnyLocalCommand()
	{
		Fixture f = new Fixture(); f.becomePresent(); MalfoidMenu menu = new MalfoidMenu(f.client);
		AtomicInteger absences = new AtomicInteger();
		menu.setFailureHandler(failure -> fail("Unexpected menu failure: " + failure));
		menu.setAbsenceHandler(decision -> { assertEquals(PresenceReason.PVP_AREA, decision.getReason()); absences.incrementAndGet(); });
		when(f.client.getVarbitValue(VarbitID.PVP_AREA_CLIENT)).thenReturn(1);
		NativeActor actor = new NativeActor(f.client, new Gson()); Runnable command = () -> fail("Stale local command ran");
		menu.onMenuOpened(new MenuOpened(), actor, f.presence, 1, () -> 1, command, command, command);
		menu.onMenuOpened(new MenuOpened(), actor, f.presence, 1, () -> 1, command, command, command);
		assertEquals(1, absences.get()); verify(f.client, never()).getMenu();
	}

	@Test public void staleLocalMenuObservesPvpImmediatelyAndRetainsTheFiveCommitReturnGate()
	{
		Fixture f = new Fixture(); f.becomePresent();
		MenuPolicy.Lease opened = new MenuPolicy.Lease(1, f.presence.getSceneGeneration(), f.presence.getPresenceEpoch(), 1);
		when(f.client.getVarbitValue(VarbitID.PVP_AREA_CLIENT)).thenReturn(1);
		assertTrue(f.presence.isPresent()); assertFalse(f.presence.permitsLocalControls());
		assertFalse(opened.isCurrent(1, f.presence.getSceneGeneration(), f.presence.getPresenceEpoch(), 1, f.presence.isPresent()));
		when(f.client.getVarbitValue(VarbitID.PVP_AREA_CLIENT)).thenReturn(0);
		for (int i = 0; i < 20; i++) { assertFalse(f.presence.permitsLocalControls()); }
		for (long tick = 3; tick < 7; tick++) { assertFalse(f.presence.commit(tick, true, true, false, false).isPlacementAllowed()); }
		assertTrue(f.presence.commit(7, true, true, false, false).isPlacementAllowed());
		f.presence.placementSucceeded(7); assertTrue(f.presence.permitsLocalControls());
	}
	@Test public void liveCutsceneLoadingDeathSpectatorAndBoatGuardsRevokeLocalControlsBeforeAnotherCommit()
	{
		for (int guard = 0; guard < 5; guard++)
		{
			Fixture f = new Fixture(); f.becomePresent();
			if (guard == 0) { when(f.client.getVarbitValue(VarbitID.CUTSCENE_STATUS)).thenReturn(1); }
			else if (guard == 1) { when(f.client.getGameState()).thenReturn(GameState.LOADING); }
			else if (guard == 2) { when(f.client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(0); }
			else if (guard == 3) { when(f.client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS)).thenReturn(3); }
			else { when(f.view.isTopLevel()).thenReturn(false); }
			assertFalse(f.presence.permitsLocalControls()); assertFalse(f.presence.isPresent());
		}
	}
	@Test public void pvpWorldDoesNotRevokeControlsOrLatchIntoLaterOrdinaryWorldSamples()
	{
		Fixture f = new Fixture(); f.becomePresent(); when(f.client.getWorldType()).thenReturn(EnumSet.of(WorldType.PVP));
		when(f.client.getPlayerOptions()).thenReturn(new String[]{"Attack"});
		assertTrue(f.presence.permitsLocalControls()); when(f.client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(f.client.getPlayerOptions()).thenReturn(new String[8]);
		for (long tick = 3; tick <= 20; tick++) { assertTrue(f.presence.commit(tick, true, true, false, true).isPresent()); }
	}
	@Test public void controlReadsCannotCreatePresenceAdvanceGraceOrReenableUserHiddenState()
	{
		Fixture f = new Fixture(); assertFalse(f.presence.commit(1, true, true, false, false).isPlacementAllowed());
		for (int i = 0; i < 20; i++) { assertFalse(f.presence.permitsLocalControls()); }
		assertEquals(1, f.presence.getDecision().getStableTicks());
		f.presence.commit(2, true, true, false, false); f.presence.placementSucceeded(2);
		long epoch = f.presence.getPresenceEpoch(), since = f.presence.getPresentSinceTick();
		for (int i = 0; i < 20; i++) { assertTrue(f.presence.permitsLocalControls()); }
		assertEquals(epoch, f.presence.getPresenceEpoch()); assertEquals(since, f.presence.getPresentSinceTick());
		f.presence.inspect(false, true, false, true);
		for (int i = 0; i < 20; i++) { assertFalse(f.presence.permitsLocalControls()); }
		assertEquals(PresenceState.ABSENT_USER, f.presence.getDecision().getState());
	}

	private static final class Fixture
	{
		private final Client client = mock(Client.class);
		private final Player player = mock(Player.class);
		private final WorldView view = mock(WorldView.class);
		private final PresenceController presence = new PresenceController(client);
		private Fixture()
		{
			CollisionData collision = mock(CollisionData.class); when(collision.getFlags()).thenReturn(new int[104][104]);
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(client.getLocalPlayer()).thenReturn(player);
			when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99); when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
			when(client.getPlayerOptions()).thenReturn(new String[8]); when(player.getWorldView()).thenReturn(view);
			when(player.getWorldLocation()).thenReturn(new WorldPoint(3250, 3250, 0));
			when(view.getId()).thenReturn(-1); when(view.getScene()).thenReturn(mock(Scene.class)); when(view.getPlane()).thenReturn(0);
			when(view.isTopLevel()).thenReturn(true); when(view.getBaseX()).thenReturn(3200); when(view.getBaseY()).thenReturn(3200);
			when(view.getSizeX()).thenReturn(104); when(view.getSizeY()).thenReturn(104); when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision});
			LocalPoint localLocation = new LocalPoint(50 * 128 + 64, 50 * 128 + 64, view);
			when(player.getLocalLocation()).thenReturn(localLocation);
		}
		private void becomePresent()
		{
			assertFalse(presence.commit(1, true, true, false, false).isPlacementAllowed());
			assertTrue(presence.commit(2, true, true, false, false).isPlacementAllowed());
			presence.placementSucceeded(2); assertTrue(presence.permitsLocalControls());
		}
	}
}
