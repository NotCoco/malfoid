package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Input-only current-tile copy. The observer proves its existing committed view/scene lease first. */
public final class TzhaarActivityCopy
{
	public final long tick, presenceEpoch;
	public final SceneKey scene;
	public final Truth known;
	public final ActivityKind activity;
	public final int viewId, plane, templateRegion;
	public TzhaarActivityCopy(long tick, SceneKey scene, long presenceEpoch, Truth known,
		ActivityKind activity, int viewId, int plane, int templateRegion)
	{
		this.tick = tick; this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch;
		this.known = Objects.requireNonNull(known); this.activity = Objects.requireNonNull(activity);
		this.viewId = viewId; this.plane = plane; this.templateRegion = templateRegion;
	}
	public boolean scopeMatches(long tick, SceneKey scene, long epoch)
	{ return this.tick == tick && this.scene.equals(scene) && presenceEpoch == epoch; }
	public boolean active()
	{ return known == Truth.TRUE && viewId == WorldView.TOPLEVEL && plane >= 0 && plane < 4
		&& (activity == ActivityKind.FIGHT_CAVE && templateRegion == 9551 || activity == ActivityKind.INFERNO && templateRegion == 9043); }
	public static TzhaarActivityCopy unknown(long tick, SceneKey scene, long epoch)
	{ return new TzhaarActivityCopy(tick, scene, epoch, Truth.UNKNOWN, ActivityKind.UNKNOWN, -1, -1, -1); }
	/** O(1): one top-level current-tile chunk. No region list, template scan or retained actor/view. */
	public static TzhaarActivityCopy capture(Client client, long tick, SceneKey scene, long epoch, boolean sameCommittedWorldScope)
	{
		if (!sameCommittedWorldScope || client.getGameState() != GameState.LOGGED_IN) { return unknown(tick, scene, epoch); }
		Player player = client.getLocalPlayer(); WorldView view = player == null ? null : player.getWorldView();
		LocalPoint local = player == null ? null : player.getLocalLocation();
		if (player == null || player.isDead() || view == null || !view.isTopLevel() || view != client.getTopLevelWorldView()
			|| view.getScene() == null || local == null || local.getWorldView() != view.getId()) { return unknown(tick, scene, epoch); }
		int plane = view.getPlane(), x = local.getSceneX(), y = local.getSceneY(); WorldPoint point = player.getWorldLocation();
		if (point == null || point.getPlane() != plane || plane < 0 || plane >= 4 || x < 0 || y < 0 || x >= view.getSizeX() || y >= view.getSizeY()) { return unknown(tick, scene, epoch); }
		if (!view.isInstance()) { return new TzhaarActivityCopy(tick, scene, epoch, Truth.FALSE, ActivityKind.UNKNOWN, view.getId(), plane, -1); }
		int[][][] chunks = view.getInstanceTemplateChunks(); int cx = x / 8, cy = y / 8;
		if (chunks == null || plane >= chunks.length || chunks[plane] == null || cx >= chunks[plane].length
			|| chunks[plane][cx] == null || cy >= chunks[plane][cx].length || chunks[plane][cx][cy] < 0) { return unknown(tick, scene, epoch); }
		int chunk = chunks[plane][cx][cy];
		int templateX = ((chunk >>> 14) & 1023) * 8, templateY = ((chunk >>> 3) & 2047) * 8;
		int region = ((templateX >>> 6) << 8) | (templateY >>> 6);
		ActivityKind activity = region == 9551 ? ActivityKind.FIGHT_CAVE : region == 9043 ? ActivityKind.INFERNO : ActivityKind.UNKNOWN;
		return new TzhaarActivityCopy(tick, scene, epoch, activity == ActivityKind.UNKNOWN ? Truth.FALSE : Truth.TRUE,
			activity, view.getId(), plane, region);
	}
}
