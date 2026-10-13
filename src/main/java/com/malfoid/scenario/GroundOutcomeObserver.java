package com.malfoid.scenario;

import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.MarkOfGraceSpawn;
import com.malfoid.additional.SceneKey;
import com.malfoid.scenario.GroundOutcomeRules.Pile;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;

/** Shared client-thread pile index. C.4 reports and M91 borrow exact callback attachments, never a scene scan. */
public final class GroundOutcomeObserver
{
	public static final int MAX_PILES = GroundOutcomeRules.MAX_PILES, MAX_ITEMS_PER_TILE = 128;
	private final Client client;
	private final GroundOutcomeRules rules = new GroundOutcomeRules();
	private final Map<TileItem, Tracked> piles = new IdentityHashMap<>();
	private ScenarioSnapshot lastFrame;
	private WorldView pinnedView;
	private Scene pinnedScene;
	private int pinnedBaseX, pinnedBaseY, pinnedSizeX, pinnedSizeY, pinnedPlane;
	private boolean pinnedInstance;
	private int[][][] pinnedTemplates;
	private long sequence;
	private boolean present, incomplete, markSourceUnknown;
	// A borrowed pointer into piles, never a second entity record/map or a retained DTO queue.
	private Tracked selectedMark;

	@Inject public GroundOutcomeObserver(Client client) { this.client = client; }

	/** Must accompany every immediate presence change, before callbacks may be witnessed. */
	public void setPresence(boolean present)
	{
		if (!present) { resetScene(); }
		this.present = present;
	}

	public void onItemSpawned(ItemSpawned event) { copyMarkSpawn(event); }
	public MarkOfGraceSpawn copyMarkSpawn(ItemSpawned event)
	{
		Tile tile = event.getTile(); TileItem item = event.getItem();
		if (tile == null || item == null) { incomplete = markSourceUnknown = true; retireMark(); return null; }
		boolean scope = ensureIndexScope();
		LocalPoint local = tile.getLocalLocation();
		if (scope && local != null && local.getWorldView() != pinnedView.getId()) { return null; }
		if (piles.containsKey(item)) { return null; } // Physical identity outlives every reaction window.
		if (piles.size() >= MAX_PILES || sequence == Long.MAX_VALUE)
		{ incomplete = markSourceUnknown = true; retireMark(); return null; }
		WorldPoint world = tile.getWorldLocation();
		boolean attached = scope && tileCurrent(tile) && world != null && world.getPlane() == pinnedPlane
			&& containsCurrentItem(tile, item);
		Position position = world == null ? null : new Position(world.getX(), world.getY(), world.getPlane());
		boolean witnessed = attached && lastFrame != null && lastFrame.tick < Long.MAX_VALUE && liveMatches(lastFrame);
		Tracked pile = new Tracked(++sequence, tile, item, witnessed ? lastFrame : null, position);
		piles.put(item, pile); // Initial/absent/unknown observations are consumed here, permanently silently.
		if (!attached && scope) { incomplete = markSourceUnknown = true; retireMark(); }
		if (!witnessed || markSourceUnknown || pile.spawnId != ItemID.GRACE || pile.spawnQuantity <= 0
			|| pile.spawnTick - pile.presentSinceTick < 10 || pile.spawnTick > Long.MAX_VALUE - 12) { return null; }
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (player == null || player.getPlane() != position.plane
			|| new Position(player.getX(), player.getY(), player.getPlane()).distance(position) > 5) { return null; }
		if (selectedMark != null && pile.spawnTick >= selectedMark.spawnTick + 12) { retireMark(); }
		if (selectedMark != null) { return null; } // A later insertion never replaces an earlier pending/issued lease.
		selectedMark = pile; pile.markRetired = false;
		return new MarkOfGraceSpawn(pile.identity, pile.spawnTick, pile.presentSinceTick,
			new SceneKey(pile.sessionGeneration, pile.sceneRevision), pile.spawnQuantity, position);
	}

	public void onItemDespawned(ItemDespawned event) { retireDespawnedItem(event); }
	public boolean retireDespawnedItem(ItemDespawned event)
	{
		Tracked pile = piles.get(event.getItem());
		if (pile == null || pile.tile != event.getTile()) { return false; }
		boolean selected = pile == selectedMark;
		if (selected) { retireMark(); }
		piles.remove(event.getItem()); // This exact certified despawn retires the physical incarnation.
		return selected;
	}

	public void onItemQuantityChanged(ItemQuantityChanged event) { retireQuantityChangedItem(event); }
	public boolean retireQuantityChangedItem(ItemQuantityChanged event)
	{
		Tracked pile = piles.get(event.getItem());
		if (pile == null || pile.tile != event.getTile() || event.getOldQuantity() == event.getNewQuantity()) { return false; }
		pile.quantityChanged = true;
		boolean selected = pile == selectedMark;
		if (selected) { retireMark(); }
		return selected;
	}

	/** Integrator calls once per committed tick, after copying the report batch and current snapshot. */
	public List<GroundOutcomeProof> closeGameTick(ScenarioSnapshot frame, List<Loot> reports)
	{
		if (!eligible(frame)) { rules.discardReports(reports); resetScene(); return Collections.emptyList(); }
		Player player = client.getLocalPlayer(); WorldView view = player == null ? null : player.getWorldView();
		if (!liveEligible(frame, player, view) || !ensureIndexScope()) { rules.discardReports(reports); resetScene(); return Collections.emptyList(); }
		if (lastFrame != null && (!scopeMatches(lastFrame, frame) || !sceneMatches(view))) { resetScene(); }
		if (lastFrame != null && frame.tick <= lastFrame.tick) { return Collections.emptyList(); }
		if (lastFrame == null && !pinScene(view)) { rules.discardReports(reports); resetScene(); return Collections.emptyList(); }
		List<Pile> fresh = new ArrayList<>();
		if (!incomplete)
		{
			for (Tracked pile : piles.values())
			{
				if (pile.spawnTick == frame.tick) { fresh.add(copy(pile)); }
			}
		}
		List<GroundOutcomeProof> result = rules.close(frame, reports, incomplete ? null : fresh);
		lastFrame = frame; incomplete = false;
		if (selectedMark != null && frame.tick >= selectedMark.spawnTick + 12) { retireMark(); }
		return result;
	}

	/** Current live state is copied here; a prior inventory sample or despawn callback is never a pickup proof. */
	public GroundOutcomeProof revalidate(long reportId, ScenarioSnapshot frame)
	{
		if (lastFrame == null || !scopeMatches(lastFrame, frame) || frame.tick < lastFrame.tick || !liveMatches(frame))
		{ resetScene(); return null; }
		if (inventoryBlocked()) { return null; }
		List<Pile> current = new ArrayList<>(1);
		long identity = rules.pileIdentity(reportId);
		if (identity > 0)
		{
			for (Tracked pile : piles.values())
			{
				if (pile.identity == identity) { current.add(copy(pile)); break; }
			}
		}
		int[][] inventory = inventory();
		return rules.revalidate(reportId, frame, current, inventory == null ? null : inventory[0], inventory == null ? null : inventory[1]);
	}

	public void settled(long reportId) { rules.settled(reportId); }
	public void resetScene()
	{
		retireMark(); rules.resetScene(); lastFrame = null; incomplete = false;
		WorldView current = indexView();
		if (current != null && current.getScene() != null && pinnedView != null
			&& (current != pinnedView || current.getScene() != pinnedScene)) { clearPhysicalIndex(); }
	}
	public void resetSession()
	{ resetScene(); clearPhysicalIndex(); rules.reset(); present = false; sequence = 0; }
	/** Separate bounded source storage under section 15.5, not a new reducer/envelope record pool. */
	public int retainedPhysicalPileRecords() { return piles.size(); }
	public boolean markSourceKnown() { return !markSourceUnknown; }
	/** A rejected/baseline copied ingress is consumed silently without holding a pending lease. */
	public void retireCopiedMark(long identity)
	{ if (selectedMark != null && selectedMark.identity == identity) { retireMark(); } }
	/** Only armed mark work reads its one selected attachment; quiet commits do not poll tiles. */
	public boolean refreshSelectedMark()
	{
		if (selectedMark == null) { return true; }
		if (selectedMarkCurrent()) { return true; }
		retireMark(); return false;
	}
	/** Client-thread negative lease. Any observed loss permanently retires this original opportunity. */
	public boolean currentMarkLeaseMatches(AdditionalSceneContext context)
	{
		Tracked pile = selectedMark;
		if (pile == null || context == null || context.ruleIndex != 91 || context.sourceKind != SourceKind.GROUND_ITEM_SPAWN
			|| context.subject.type != AdditionalSceneContext.Subject.Type.UNIT || context.permittedClaimMask != AdditionalSceneContext.CLAIM_SCENE_EDGE
			|| context.payload.entitySerial != pile.identity || context.payload.definitionId != ItemID.GRACE || !context.payload.currentLive
			|| context.occurrenceTick != pile.spawnTick || context.originalDeadline != pile.spawnTick + 12
			|| context.presenceEpoch != pile.presentSinceTick || context.scene.sessionGeneration != pile.sessionGeneration
			|| context.scene.sceneRevision != pile.sceneRevision || !pile.position.equals(context.payload.position)) { return false; }
		return refreshSelectedMark();
	}
	private boolean selectedMarkCurrent()
	{
		Tracked pile = selectedMark;
		if (pile == null || pile.markRetired || markSourceUnknown || lastFrame == null || !liveMatches(lastFrame)
			|| pile.sessionGeneration != lastFrame.sessionGeneration || pile.sceneRevision != lastFrame.sceneRevision
			|| pile.presentSinceTick != lastFrame.presentSinceTick || pile.worldViewId != lastFrame.worldViewId
			|| piles.get(pile.item) != pile || pile.quantityChanged || pile.spawnId != ItemID.GRACE
			|| pile.item.getId() != ItemID.GRACE || pile.spawnQuantity <= 0 || pile.item.getQuantity() != pile.spawnQuantity
			|| !tileCurrent(pile.tile) || !containsCurrentItem(pile.tile, pile.item)) { return false; }
		WorldPoint ground = pile.tile.getWorldLocation(), player = client.getLocalPlayer().getWorldLocation();
		return ground != null && player != null && ground.getPlane() == pinnedPlane && player.getPlane() == pinnedPlane
			&& pile.position.equals(new Position(ground.getX(), ground.getY(), ground.getPlane()))
			&& new Position(player.getX(), player.getY(), player.getPlane()).distance(pile.position) <= 5;
	}
	private void retireMark()
	{
		if (selectedMark != null) { selectedMark.markRetired = true; selectedMark = null; }
	}
	private WorldView indexView()
	{
		Player player = client.getLocalPlayer();
		return player != null && player.getWorldView() != null ? player.getWorldView() : client.getTopLevelWorldView();
	}
	private boolean ensureIndexScope()
	{
		WorldView current = indexView();
		if (current == null || current.getScene() == null) { return false; }
		if (pinnedView != null && (current != pinnedView || current.getScene() != pinnedScene))
		{ resetScene(); clearPhysicalIndex(); }
		else if (pinnedView != null && !sceneMatches(current))
		{ retireMark(); rules.resetScene(); lastFrame = null; } // Retain silently consumed identities across in-place metadata loss.
		return pinnedView != null && sceneMatches(current) || pinScene(current);
	}
	private void clearPhysicalIndex()
	{
		piles.clear(); retireMark(); pinnedView = null; pinnedScene = null; pinnedTemplates = null; markSourceUnknown = false;
	}

	private boolean pinScene(WorldView view)
	{
		if (view.getSizeX() <= 0 || view.getSizeY() <= 0 || view.getSizeX() > Constants.SCENE_SIZE
			|| view.getSizeY() > Constants.SCENE_SIZE || view.getPlane() < 0 || view.getPlane() >= Constants.MAX_Z) { return false; }
		pinnedView = view; pinnedScene = view.getScene(); pinnedPlane = view.getPlane();
		pinnedBaseX = view.getBaseX(); pinnedBaseY = view.getBaseY(); pinnedSizeX = view.getSizeX(); pinnedSizeY = view.getSizeY();
		pinnedInstance = view.isInstance(); pinnedTemplates = null;
		if (!pinnedInstance) { return true; }
		int[][][] templates = view.getInstanceTemplateChunks();
		if (!validTemplates(templates, view.getPlane())) { return false; }
		pinnedTemplates = new int[templates.length][][];
		for (int plane = 0; plane < templates.length; plane++)
		{
			pinnedTemplates[plane] = new int[templates[plane].length][];
			for (int x = 0; x < templates[plane].length; x++) { pinnedTemplates[plane][x] = templates[plane][x].clone(); }
		}
		return true;
	}
	private boolean sceneMatches(WorldView view)
	{
		if (view != pinnedView || view.getScene() != pinnedScene || view.getBaseX() != pinnedBaseX || view.getBaseY() != pinnedBaseY
			|| view.getSizeX() != pinnedSizeX || view.getSizeY() != pinnedSizeY || view.getPlane() != pinnedPlane || view.isInstance() != pinnedInstance) { return false; }
		if (!pinnedInstance) { return true; }
		int[][][] templates = view.getInstanceTemplateChunks();
		return validTemplates(templates, view.getPlane()) && Arrays.deepEquals(pinnedTemplates, templates);
	}
	private static boolean validTemplates(int[][][] templates, int currentPlane)
	{
		int chunks = Constants.SCENE_SIZE / Constants.CHUNK_SIZE;
		if (templates == null || templates.length == 0 || templates.length > Constants.MAX_Z || currentPlane < 0 || currentPlane >= templates.length) { return false; }
		for (int[][] plane : templates)
		{
			if (plane == null || plane.length == 0 || plane.length > chunks) { return false; }
			for (int[] row : plane) { if (row == null || row.length == 0 || row.length > chunks) { return false; } }
		}
		return true;
	}

	private Pile copy(Tracked pile)
	{
		WorldPoint current = pile.tile.getWorldLocation();
		boolean inTile = !pile.quantityChanged && tileCurrent(pile.tile) && current != null
			&& pile.position.equals(new Position(current.getX(), current.getY(), current.getPlane()))
			&& containsCurrentItem(pile.tile, pile.item);
		return new Pile(pile.identity, pile.spawnTick, pile.sessionGeneration, pile.sceneRevision, pile.presentSinceTick,
			pile.worldViewId, pile.position, pile.item.getId(), pile.spawnQuantity, pile.item.getQuantity(), pile.item.getOwnership(),
			pile.item.getVisibleTime(), pile.item.getDespawnTime(), client.getTickCount(), pile.item.isPrivate(), inTile);
	}
	private boolean tileCurrent(Tile tile)
	{
		LocalPoint local = tile.getLocalLocation();
		if (local == null || pinnedView == null || local.getWorldView() != pinnedView.getId() || tile.getPlane() != pinnedView.getPlane()) { return false; }
		Tile[][][] tiles = pinnedScene.getTiles(); int plane = tile.getPlane(), x = local.getSceneX(), y = local.getSceneY();
		return tiles != null && plane >= 0 && plane < tiles.length && tiles[plane] != null && x >= 0 && x < tiles[plane].length
			&& tiles[plane][x] != null && y >= 0 && y < tiles[plane][x].length && tiles[plane][x][y] == tile;
	}
	private boolean containsCurrentItem(Tile tile, TileItem item)
	{
		List<TileItem> current = tile.getGroundItems();
		if (current == null || current.size() > MAX_ITEMS_PER_TILE) { return false; }
		for (TileItem candidate : current) { if (candidate == item) { return true; } }
		return false;
	}
	private boolean liveMatches(ScenarioSnapshot frame)
	{
		if (!eligible(frame)) { return false; }
		Player player = client.getLocalPlayer(); WorldView view = player == null ? null : player.getWorldView();
		return liveEligible(frame, player, view) && sceneMatches(view);
	}
	private boolean liveEligible(ScenarioSnapshot frame, Player player, WorldView view)
	{
		WorldPoint location = player == null ? null : player.getWorldLocation();
		return client.getGameState() == GameState.LOGGED_IN && player != null && !player.isDead()
			&& client.getBoostedSkillLevel(Skill.HITPOINTS) > 0 && view != null && view.isTopLevel() && view.getScene() != null
			&& view.getId() == frame.worldViewId && location != null && location.getPlane() == frame.player.plane
			&& view.getPlane() == frame.player.plane;
	}
	private boolean eligible(ScenarioSnapshot frame)
	{
		return present && frame != null && frame.valid && frame.present && !frame.userHidden && !frame.autoAbsent
			&& !frame.dead && frame.currentHp > 0 && frame.topLevel && frame.player != null && frame.tick >= frame.presentSinceTick;
	}
	private static boolean scopeMatches(ScenarioSnapshot first, ScenarioSnapshot second)
	{
		return second != null && first.sessionGeneration == second.sessionGeneration && first.sceneRevision == second.sceneRevision
			&& first.presentSinceTick == second.presentSinceTick && first.worldViewId == second.worldViewId;
	}
	private int[][] inventory()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		Item[] items = container == null ? null : container.getItems();
		if (items == null || items.length > 28) { return null; }
		int[] ids = new int[28], quantities = new int[28]; Arrays.fill(ids, -1);
		for (int i = 0; i < items.length; i++)
		{
			if (items[i] == null) { return null; }
			ids[i] = items[i].getId(); quantities[i] = items[i].getQuantity();
		}
		return new int[][]{ids, quantities};
	}
	private boolean inventoryBlocked()
	{
		return visible(InterfaceID.Bankmain.UNIVERSE) || visible(InterfaceID.BankDepositbox.UNIVERSE)
			|| visible(InterfaceID.Trademain.UNIVERSE) || visible(InterfaceID.Tradeconfirm.UNIVERSE);
	}
	private boolean visible(int id) { Widget widget = client.getWidget(id); return widget != null && !widget.isHidden(); }
	private static final class Tracked
	{
		private final long identity, spawnTick, sessionGeneration, sceneRevision, presentSinceTick;
		private final int worldViewId, spawnQuantity, spawnId;
		private final Tile tile;
		private final TileItem item;
		private final Position position;
		private boolean quantityChanged, markRetired = true;
		private Tracked(long identity, Tile tile, TileItem item, ScenarioSnapshot frame, Position position)
		{
			this.identity = identity; this.tile = tile; this.item = item; this.position = position;
			spawnTick = frame == null ? Long.MIN_VALUE : frame.tick + 1;
			sessionGeneration = frame == null ? Long.MIN_VALUE : frame.sessionGeneration;
			sceneRevision = frame == null ? Long.MIN_VALUE : frame.sceneRevision;
			presentSinceTick = frame == null ? Long.MIN_VALUE : frame.presentSinceTick;
			worldViewId = frame == null ? Integer.MIN_VALUE : frame.worldViewId;
			spawnQuantity = item.getQuantity(); spawnId = item.getId();
		}
	}
}
