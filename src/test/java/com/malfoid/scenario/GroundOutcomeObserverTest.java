package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class GroundOutcomeObserverTest
{
	@Test public void loadSeededSpawnsBeforeTheValidCommittedBaselineAreNotFreshGroundEvidence()
	{
		Fixture f = new Fixture(); f.observer.onItemSpawned(new ItemSpawned(f.tile, f.item));
		GroundOutcomeProof proof = f.close(2, Collections.singletonList(f.report()));
		assertFalse(proof.currentGroundAvailable); assertFalse(proof.itemPossessionConfirmed);
	}
	@Test public void loadingSpawnsInvalidateThePriorSceneRatherThanCreatingAStoryOnReturn()
	{
		Fixture f = new Fixture(); f.baseline(); when(f.client.getGameState()).thenReturn(GameState.LOADING);
		f.observer.onItemSpawned(new ItemSpawned(f.tile, f.item)); when(f.client.getGameState()).thenReturn(GameState.LOGGED_IN);
		assertFalse(f.close(2, Collections.singletonList(f.report())).currentGroundAvailable);
	}
	@Test public void freshSelfOwnedPileUsesCurrentExactIdentityAndCurrentCompleteInventoryAtPaint()
	{
		Fixture f = new Fixture(); GroundOutcomeProof proof = f.record(); assertTrue(proof.currentGroundAvailable);
		assertFalse(f.observer.revalidate(1, f.frame(2)).itemPossessionConfirmed);
		when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1)});
		assertTrue(f.observer.revalidate(1, f.frame(2)).itemPossessionConfirmed);
	}
	@Test public void sameIdAndQuantityOnAReplacementItemNeverSubstituteForTheWitnessedIdentity()
	{
		Fixture f = new Fixture(); f.record(); TileItem replacement = mock(TileItem.class);
		when(replacement.getId()).thenReturn(100); when(replacement.getQuantity()).thenReturn(2);
		f.currentItems.clear(); f.currentItems.add(replacement);
		assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
	}
	@Test public void despawnAndIndependentInventoryGainArePossessionOnlyWithoutAPickupClaim()
	{
		Fixture f = new Fixture(); f.record(); f.currentItems.clear();
		f.observer.onItemDespawned(new ItemDespawned(f.tile, f.item));
		when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1)});
		GroundOutcomeProof proof = f.observer.revalidate(1, f.frame(2));
		assertFalse(proof.currentGroundAvailable); assertTrue(proof.itemPossessionConfirmed); assertNull(proof.groundPosition);
	}
	@Test public void groupOwnedPileAndGroupmateRemovalStillPermitOnlyIndependentCurrentPossession()
	{
		Fixture f = new Fixture(); when(f.item.getOwnership()).thenReturn(TileItem.OWNERSHIP_GROUP);
		assertFalse(f.record().currentGroundAvailable); f.currentItems.clear();
		when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1)});
		GroundOutcomeProof proof = f.observer.revalidate(1, f.frame(2));
		assertFalse(proof.currentGroundAvailable); assertTrue(proof.itemPossessionConfirmed);
	}
	@Test public void quantityChangeRevokesTheCapturedPileEvenIfItsQuantityLaterReturnsToTheOldValue()
	{
		Fixture f = new Fixture(); f.record(); when(f.item.getQuantity()).thenReturn(3);
		f.observer.onItemQuantityChanged(new ItemQuantityChanged(f.item, f.tile, 2, 3));
		assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
		when(f.item.getQuantity()).thenReturn(2); assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
	}
	@Test public void ownershipAndExactServerDespawnBoundaryRevokeImmediatelyBeforeAnotherCommit()
	{
		Fixture f = new Fixture(); f.record(); when(f.item.getOwnership()).thenReturn(TileItem.OWNERSHIP_OTHER);
		assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
		when(f.item.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF); when(f.client.getTickCount()).thenReturn(200);
		assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
	}
	@Test public void immediateAbsenceDeathAndLivePlaneChangesDestroyTheOldProof()
	{
		for (int change = 0; change < 3; change++)
		{
			Fixture f = new Fixture(); f.record();
			if (change == 0) { f.observer.setPresence(false); }
			else if (change == 1) { when(f.player.isDead()).thenReturn(true); }
			else { when(f.player.getWorldLocation()).thenReturn(new WorldPoint(3205, 3205, 1)); }
			assertNull(f.observer.revalidate(1, f.frame(2)));
			f.observer.setPresence(true); when(f.player.isDead()).thenReturn(false); when(f.player.getWorldLocation()).thenReturn(new WorldPoint(3205, 3205, 0));
			assertNull(f.observer.revalidate(1, f.frame(2)));
		}
	}
	@Test public void reusedViewIdOrSceneReplacementCannotRevalidateTheOldPile()
	{
		Fixture f = new Fixture(); f.record(); when(f.view.getScene()).thenReturn(mock(Scene.class));
		assertNull(f.observer.revalidate(1, f.frame(2)));
		f = new Fixture(); f.record(); WorldView replacement = mock(WorldView.class);
		when(replacement.getId()).thenReturn(-1); when(replacement.getPlane()).thenReturn(0); when(replacement.isTopLevel()).thenReturn(true);
		when(replacement.getScene()).thenReturn(f.scene); when(f.player.getWorldView()).thenReturn(replacement);
		assertNull(f.observer.revalidate(1, f.frame(2)));
	}
	@Test public void tileReplacementWithinThePinnedSceneCannotUseTheOldGroundItemReference()
	{
		Fixture f = new Fixture(); f.record(); f.tiles[0][6][6] = mock(Tile.class);
		assertFalse(f.observer.revalidate(1, f.frame(2)).currentGroundAvailable);
	}
	@Test public void inPlaceViewBaseSizeAndInstanceChangesRevokeTheOldEpisodeBeforeAnotherCommit()
	{
		for (int change = 0; change < 3; change++)
		{
			Fixture f = new Fixture(); f.record();
			if (change == 0) { when(f.view.getBaseX()).thenReturn(3208); }
			else if (change == 1) { when(f.view.getSizeX()).thenReturn(104); }
			else { when(f.view.isInstance()).thenReturn(true); }
			assertNull(f.observer.revalidate(1, f.frame(2)));
			when(f.view.getBaseX()).thenReturn(3200); when(f.view.getSizeX()).thenReturn(12); when(f.view.isInstance()).thenReturn(false);
			assertNull(f.observer.revalidate(1, f.frame(2)));
		}
	}
	@Test public void anInPlaceInstanceTemplateMutationCannotKeepTheOldSceneProof()
	{
		Fixture f = new Fixture(); int[][][] templates = new int[1][1][1];
		when(f.view.isInstance()).thenReturn(true); when(f.view.getInstanceTemplateChunks()).thenReturn(templates);
		assertTrue(f.record().currentGroundAvailable); templates[0][0][0] = 2;
		assertNull(f.observer.revalidate(1, f.frame(2))); templates[0][0][0] = 0;
		assertNull(f.observer.revalidate(1, f.frame(2)));
	}
	@Test public void missingJaggedOrOversizedInstanceTemplatesCannotSeedAProof()
	{
		for (int[][][] templates : new int[][][][]{null, new int[0][][], new int[5][1][1], new int[1][14][1], new int[1][1][14], new int[1][][]})
		{
			Fixture f = new Fixture(); when(f.view.isInstance()).thenReturn(true); when(f.view.getInstanceTemplateChunks()).thenReturn(templates);
			assertTrue(f.observer.closeGameTick(f.frame(1), Collections.emptyList()).isEmpty());
			f.observer.onItemSpawned(new ItemSpawned(f.tile, f.item));
			assertTrue(f.observer.closeGameTick(f.frame(2), Collections.singletonList(f.report())).isEmpty());
		}
	}
	@Test public void nullEntriesInsideCurrentInventoryAreUnknownWhileSparseKnownInventoryMayConfirmPossession()
	{
		Fixture f = new Fixture(); f.record(); when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1), null});
		GroundOutcomeProof unknown = f.observer.revalidate(1, f.frame(2));
		assertFalse(unknown.itemPossessionConfirmed); assertTrue(unknown.currentGroundAvailable);
		when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1)});
		assertTrue(f.observer.revalidate(1, f.frame(2)).itemPossessionConfirmed);
	}
	@Test public void aRegressedCommittedTickCannotMoveTheFreshSpawnBucketBackwards()
	{
		Fixture f = new Fixture(); assertTrue(f.observer.closeGameTick(f.frame(3), Collections.emptyList()).isEmpty());
		assertTrue(f.observer.closeGameTick(f.frame(2), Collections.emptyList()).isEmpty());
		f.observer.onItemSpawned(new ItemSpawned(f.tile, f.item));
		assertTrue(f.close(4, Collections.singletonList(f.report())).currentGroundAvailable);
	}
	@Test public void sameSessionAbsenceDiscardsOldReportIdsWithoutBlockingFreshReports()
	{
		Fixture f = new Fixture(); f.record(); f.observer.setPresence(false); f.observer.setPresence(true);
		assertTrue(f.observer.closeGameTick(f.frame(3), Collections.emptyList()).isEmpty());
		assertTrue(f.observer.closeGameTick(f.frame(4), Collections.singletonList(f.report())).isEmpty());
		Loot fresh = new Loot(2, "npc:2", true, Collections.singletonList(new Stack(100, 2, "Exact item", 250_000)));
		assertEquals(1, f.observer.closeGameTick(f.frame(5), Collections.singletonList(fresh)).size());
	}
	@Test public void newlyVisibleBankBlocksDeliveryUsingLiveUiStateAndKeepsTheOriginalDeadline()
	{
		Fixture f = new Fixture(); f.record(); Widget bank = mock(Widget.class); when(bank.isHidden()).thenReturn(false);
		when(f.client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
		assertNull(f.observer.revalidate(1, f.frame(2))); when(bank.isHidden()).thenReturn(true);
		assertEquals(14, f.observer.revalidate(1, f.frame(13)).originalDeadline);
		assertNull(f.observer.revalidate(1, f.frame(14)));
	}
	@Test public void oversizedTileDataAndMissingInventoryStayUnknownWithoutScanningAnotherTile()
	{
		Fixture f = new Fixture(); f.baseline();
		while (f.currentItems.size() <= GroundOutcomeObserver.MAX_ITEMS_PER_TILE) { f.currentItems.add(mock(TileItem.class)); }
		f.observer.onItemSpawned(new ItemSpawned(f.tile, f.item));
		assertFalse(f.close(2, Collections.singletonList(f.report())).currentGroundAvailable);
		when(f.client.getItemContainer(InventoryID.INV)).thenReturn(null);
		assertFalse(f.observer.revalidate(1, f.frame(2)).itemPossessionConfirmed);
	}
	@Test public void silentArrivalSettlementAndResetSessionCannotLeaveAReusablePossessionOpportunity()
	{
		Fixture f = new Fixture(); f.record(); f.observer.settled(1);
		when(f.inventory.getItems()).thenReturn(new Item[]{new Item(100, 1)});
		assertNull(f.observer.revalidate(1, f.frame(2))); f.observer.resetSession();
		assertNull(f.observer.revalidate(1, f.frame(2)));
	}

	private static final class Fixture
	{
		private final Client client = mock(Client.class);
		private final Player player = mock(Player.class);
		private final WorldView view = mock(WorldView.class);
		private final Scene scene = mock(Scene.class);
		private final Tile tile = mock(Tile.class);
		private final TileItem item = mock(TileItem.class);
		private final ItemContainer inventory = mock(ItemContainer.class);
		private final Tile[][][] tiles = new Tile[1][12][12];
		private final List<TileItem> currentItems = new ArrayList<>();
		private final GroundOutcomeObserver observer = new GroundOutcomeObserver(client);
		private Fixture()
		{
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(client.getLocalPlayer()).thenReturn(player);
			when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99); when(client.getTickCount()).thenReturn(100);
			when(player.getWorldView()).thenReturn(view); when(player.getWorldLocation()).thenReturn(new WorldPoint(3205, 3205, 0));
			when(view.getId()).thenReturn(-1); when(view.getPlane()).thenReturn(0); when(view.isTopLevel()).thenReturn(true);
			when(view.getBaseX()).thenReturn(3200); when(view.getBaseY()).thenReturn(3200); when(view.getSizeX()).thenReturn(12); when(view.getSizeY()).thenReturn(12);
			when(view.getScene()).thenReturn(scene); tiles[0][6][6] = tile; when(scene.getTiles()).thenReturn(tiles);
			LocalPoint localLocation = new LocalPoint(6 * 128 + 64, 6 * 128 + 64, view);
			when(tile.getLocalLocation()).thenReturn(localLocation);
			when(tile.getWorldLocation()).thenReturn(new WorldPoint(3206, 3206, 0)); when(tile.getPlane()).thenReturn(0);
			currentItems.add(item); when(tile.getGroundItems()).thenReturn(currentItems);
			when(item.getId()).thenReturn(100); when(item.getQuantity()).thenReturn(2); when(item.getOwnership()).thenReturn(TileItem.OWNERSHIP_SELF);
			when(item.getVisibleTime()).thenReturn(110); when(item.getDespawnTime()).thenReturn(200); when(item.isPrivate()).thenReturn(true);
			when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory); when(inventory.getItems()).thenReturn(new Item[0]);
			observer.setPresence(true);
		}
		private ScenarioSnapshot frame(long tick)
		{
			ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder(); frame.tick = tick; frame.presentSinceTick = 1;
			frame.player = new Position(3205, 3205, 0); frame.worldViewId = -1;
			frame.inventoryIds = new int[28]; Arrays.fill(frame.inventoryIds, -1); frame.inventoryQuantities = new int[28]; return frame.build();
		}
		private Loot report() { return new Loot(1, "npc:1", true, Collections.singletonList(new Stack(100, 2, "Exact item", 250_000))); }
		private void baseline() { assertTrue(observer.closeGameTick(frame(1), Collections.emptyList()).isEmpty()); }
		private GroundOutcomeProof close(long tick, List<Loot> reports) { return observer.closeGameTick(frame(tick), reports).get(0); }
		private GroundOutcomeProof record()
		{
			baseline(); observer.onItemSpawned(new ItemSpawned(tile, item)); return close(2, Collections.singletonList(report()));
		}
	}
}
