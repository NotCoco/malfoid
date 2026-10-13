package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.TileItem;

/** Bounded C.4 proof state. It issues no scene, cooldown, pickup or provenance claim. */
public final class GroundOutcomeRules
{
	public static final int MAX_EPISODES = 64, MAX_STACKS = 128, MAX_PILES = 256, REPORT_LIFETIME = 12;
	private final Map<Long, Episode> episodes = new LinkedHashMap<>();
	private long sessionGeneration = Long.MIN_VALUE, sceneRevision = Long.MIN_VALUE, presentSinceTick = Long.MIN_VALUE;
	private long lastTick = Long.MIN_VALUE;
	private long reportHighWater = Long.MIN_VALUE;
	private int worldViewId = Integer.MIN_VALUE, plane = Integer.MIN_VALUE;

	/** Only fresh reports are returned; later evidence cannot create a second opportunity. */
	public List<GroundOutcomeProof> close(ScenarioSnapshot frame, List<Loot> reports, List<Pile> currentPiles)
	{
		if (!eligible(frame)) { discardReports(reports); resetScene(); return Collections.emptyList(); }
		if (!scopeMatches(frame))
		{
			resetScene(); sessionGeneration = frame.sessionGeneration; sceneRevision = frame.sceneRevision; presentSinceTick = frame.presentSinceTick;
			worldViewId = frame.worldViewId; plane = frame.player.plane;
		}
		if (frame.tick <= lastTick) { return Collections.emptyList(); }
		lastTick = frame.tick; expire(frame.tick);
		List<GroundOutcomeProof> out = new ArrayList<>();
		if (reports == null || reports.size() > MAX_EPISODES) { return out; }
		List<Loot> ordered = new ArrayList<>(reports);
		ordered.removeIf(report -> report == null);
		ordered.sort(Comparator.comparingLong(report -> report.id));
		for (Loot report : ordered)
		{
			if (report.id <= 0 || report.id <= reportHighWater) { continue; }
			reportHighWater = report.id;
			if (!report.witnessed || report.npcKey == null || report.npcKey.isEmpty()
				|| report.stacks.isEmpty() || report.stacks.size() > MAX_STACKS || episodes.containsKey(report.id)) { continue; }
			List<Stack> sorted = new ArrayList<>(report.stacks);
			if (sorted.stream().anyMatch(stack -> stack == null || stack.id < 0 || stack.quantity <= 0)) { continue; }
			sorted.sort(Comparator.comparingInt((Stack stack) -> stack.id).thenComparingInt(stack -> stack.quantity));
			StringBuilder key = new StringBuilder(report.npcKey);
			Stack selected = null;
			for (Stack stack : sorted)
			{
				key.append('/').append(stack.id).append(':').append(stack.quantity);
				if (stack.total() >= 500_000 && (selected == null || stack.total() > selected.total())) { selected = stack; }
			}
			if (selected == null || duplicate(key.toString(), frame.tick) || episodes.size() >= MAX_EPISODES
				|| frame.tick > Long.MAX_VALUE - REPORT_LIFETIME) { continue; }
			Episode episode = new Episode(report.id, key.toString(), frame, selected);
			Pile matched = uniqueFreshPile(frame, selected, currentPiles);
			if (matched != null && available(frame, episode, matched)) { episode.pileIdentity = matched.identity; }
			episodes.put(report.id, episode);
			out.add(proof(episode, frame, currentPiles, frame.inventoryIds(), frame.inventoryQuantities()));
		}
		return Collections.unmodifiableList(out);
	}

	/** Used immediately before paint, with freshly copied current inventory and a current exact pile identity. */
	public GroundOutcomeProof revalidate(long reportId, ScenarioSnapshot frame, List<Pile> currentPiles,
		int[] currentInventoryIds, int[] currentInventoryQuantities)
	{
		if (!eligible(frame) || !scopeMatches(frame)) { resetScene(); return null; }
		if (frame.tick < lastTick) { return null; }
		expire(frame.tick);
		Episode episode = episodes.get(reportId);
		if (episode == null || episode.settled || frame.tick < episode.reportTick) { return null; }
		return proof(episode, frame, currentPiles, currentInventoryIds, currentInventoryQuantities);
	}

	/** Root commits the parent M05 logical guard separately on first paint or intentional silent arrival. */
	public void settled(long reportId)
	{
		Episode episode = episodes.get(reportId); if (episode != null) { episode.settled = true; }
	}

	long pileIdentity(long reportId)
	{
		Episode episode = episodes.get(reportId); return episode == null || episode.settled ? 0 : episode.pileIdentity;
	}

	/** Same-session absence discards proof without allowing an old report ID to be replayed on return. */
	public void resetScene()
	{
		long highWater = reportHighWater; reset(); reportHighWater = highWater;
	}

	void discardReports(List<Loot> reports)
	{
		if (reports == null || reports.size() > MAX_EPISODES) { return; }
		for (Loot report : reports) { if (report != null && report.id > 0) { reportHighWater = Math.max(reportHighWater, report.id); } }
	}

	public void reset()
	{
		episodes.clear(); sessionGeneration = Long.MIN_VALUE; sceneRevision = Long.MIN_VALUE;
		presentSinceTick = Long.MIN_VALUE; lastTick = Long.MIN_VALUE; reportHighWater = Long.MIN_VALUE;
		worldViewId = Integer.MIN_VALUE; plane = Integer.MIN_VALUE;
	}

	private boolean scopeMatches(ScenarioSnapshot frame)
	{
		return sessionGeneration == frame.sessionGeneration && sceneRevision == frame.sceneRevision && presentSinceTick == frame.presentSinceTick
			&& worldViewId == frame.worldViewId && plane == frame.player.plane;
	}
	private static boolean eligible(ScenarioSnapshot frame)
	{
		return frame != null && frame.valid && frame.present && !frame.userHidden && !frame.autoAbsent && !frame.dead
			&& frame.currentHp > 0 && frame.topLevel && frame.player != null && frame.worldViewId != Integer.MIN_VALUE
			&& frame.tick >= 0 && frame.presentSinceTick >= 0 && frame.tick >= frame.presentSinceTick;
	}
	private void expire(long tick) { episodes.entrySet().removeIf(entry -> tick >= entry.getValue().deadline); }
	private boolean duplicate(String key, long tick)
	{
		for (Episode episode : episodes.values()) { if (tick - episode.reportTick <= 2 && episode.key.equals(key)) { return true; } }
		return false;
	}
	private static Pile uniqueFreshPile(ScenarioSnapshot frame, Stack selected, List<Pile> piles)
	{
		if (piles == null || piles.size() > MAX_PILES) { return null; }
		Pile match = null;
		for (Pile pile : piles)
		{
			if (pile == null || pile.identity <= 0 || pile.itemId != selected.id || pile.spawnQuantity != selected.quantity
				|| pile.spawnTick != frame.tick || !pileScope(frame, pile)) { continue; }
			if (match != null) { return null; }
			match = pile;
		}
		return match;
	}
	private static boolean pileScope(ScenarioSnapshot frame, Pile pile)
	{
		return pile.sessionGeneration == frame.sessionGeneration && pile.sceneRevision == frame.sceneRevision
			&& pile.presentSinceTick == frame.presentSinceTick && pile.worldViewId == frame.worldViewId
			&& pile.position != null && pile.position.plane == frame.player.plane && pile.spawnTick >= frame.presentSinceTick;
	}
	private static boolean available(ScenarioSnapshot frame, Episode episode, Pile pile)
	{
		// visibleTime describes visibility to OTHER players. A current SELF-owned private pile is visible to its owner.
		return !frame.inventoryBlocked && pileScope(frame, pile) && pile.currentlyInTile
			&& pile.itemId == episode.selected.id && pile.spawnQuantity == episode.selected.quantity
			&& pile.quantity == episode.selected.quantity && pile.ownership == TileItem.OWNERSHIP_SELF
			&& pile.serverTick >= 0 && pile.visibleTime >= 0 && pile.despawnTime > pile.serverTick;
	}
	private GroundOutcomeProof proof(Episode episode, ScenarioSnapshot frame, List<Pile> piles, int[] ids, int[] quantities)
	{
		Pile matched = null;
		if (piles != null && piles.size() <= MAX_PILES)
		{
			for (Pile pile : piles)
			{
				if (pile != null && episode.pileIdentity > 0 && pile.identity == episode.pileIdentity)
				{
					if (matched != null) { matched = null; break; }
					matched = pile;
				}
			}
		}
		boolean available = matched != null && available(frame, episode, matched);
		boolean possessed = !frame.inventoryBlocked && exactPossession(episode.selected.id, ids, quantities);
		return new GroundOutcomeProof(episode.id, episode.reportTick, episode.deadline, episode.sessionGeneration,
			episode.sceneRevision, episode.presentSinceTick, episode.selected.id, episode.selected.quantity,
			episode.selected.name == null ? "" : episode.selected.name, episode.selected.total(), available, possessed,
			available ? episode.pileIdentity : 0, available ? matched.position : null);
	}
	private static boolean exactPossession(int itemId, int[] ids, int[] quantities)
	{
		if (ids == null || quantities == null || ids.length != 28 || quantities.length != 28) { return false; }
		boolean found = false;
		for (int i = 0; i < 28; i++)
		{
			if (ids[i] < -1 || quantities[i] < 0 || (ids[i] == -1 && quantities[i] != 0)
				|| (ids[i] >= 0 && quantities[i] <= 0)) { return false; }
			if (ids[i] == itemId && quantities[i] > 0) { found = true; }
		}
		return found;
	}

	/** Copied public API facts only; raw Tile/TileItem references remain inside the adapter. */
	public static final class Pile
	{
		public final long identity, spawnTick, sessionGeneration, sceneRevision, presentSinceTick;
		public final int worldViewId, itemId, spawnQuantity, quantity, ownership, visibleTime, despawnTime, serverTick;
		public final Position position;
		public final boolean privateItem, currentlyInTile;
		public Pile(long identity, long spawnTick, long sessionGeneration, long sceneRevision, long presentSinceTick,
			int worldViewId, Position position, int itemId, int spawnQuantity, int quantity, int ownership,
			int visibleTime, int despawnTime, int serverTick, boolean privateItem, boolean currentlyInTile)
		{
			this.identity = identity; this.spawnTick = spawnTick; this.sessionGeneration = sessionGeneration;
			this.sceneRevision = sceneRevision; this.presentSinceTick = presentSinceTick; this.worldViewId = worldViewId;
			this.position = position; this.itemId = itemId; this.spawnQuantity = spawnQuantity; this.quantity = quantity;
			this.ownership = ownership; this.visibleTime = visibleTime; this.despawnTime = despawnTime;
			this.serverTick = serverTick; this.privateItem = privateItem; this.currentlyInTile = currentlyInTile;
		}
	}
	private static final class Episode
	{
		private final long id, reportTick, deadline, sessionGeneration, sceneRevision, presentSinceTick;
		private final String key;
		private final Stack selected;
		private long pileIdentity;
		private boolean settled;
		private Episode(long id, String key, ScenarioSnapshot frame, Stack selected)
		{
			this.id = id; this.key = key; this.selected = selected; reportTick = frame.tick;
			deadline = frame.tick + REPORT_LIFETIME; sessionGeneration = frame.sessionGeneration;
			sceneRevision = frame.sceneRevision; presentSinceTick = frame.presentSinceTick;
		}
	}
}
