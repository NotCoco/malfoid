package com.malfoid.presence;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Client-thread adapter. It does not register objects, mutate input or keep account information. */
public final class PresenceController
{
	private static final int[] BUSY_WIDGETS = {
		InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE,
		InterfaceID.Chatmenu.UNIVERSE, InterfaceID.Messagebox.UNIVERSE, InterfaceID.Objectbox.UNIVERSE,
		InterfaceID.Bankmain.UNIVERSE, InterfaceID.BankDepositbox.UNIVERSE,
		InterfaceID.BankpinKeypad.UNIVERSE, InterfaceID.Trademain.UNIVERSE,
		InterfaceID.Tradeconfirm.UNIVERSE, InterfaceID.GeOffers.UNIVERSE,
		InterfaceID.Shopmain.UNIVERSE, InterfaceID.Skillmulti.UNIVERSE
	};
	private static final int[] ADDITIONAL_MODAL_WIDGETS = {
		InterfaceID.Worldmap.UNIVERSE, InterfaceID.Fairyrings.ROOT_RECT0, InterfaceID.TrailSlidepuzzle.UNIVERSE
	};
	private final Client client;
	private final PlayerCombat playerCombat;
	private PresenceGate gate = new PresenceGate();
	private long sceneGeneration;
	private SceneIdentity scene;
	private WorldPoint lastPlayerPoint;
	private int lastTemplateHash;
	private boolean busy;
	private boolean currentOutfitBlocked = true;
	private boolean currentInventoryBlocked = true;

	@Inject
	public PresenceController(Client client)
	{
		this.client = client;
		playerCombat = new PlayerCombat(client);
	}

	public Decision commit(long tick, boolean show, boolean ready, boolean failed, boolean placed)
	{
		boolean playerCombatAbsent = playerCombat.commit(tick);
		PresenceReason guard = sample(true, playerCombatAbsent);
		busy = sampleBusy();
		return gate.commit(tick, sceneGeneration, guard, show, ready, failed, placed);
	}

	public Decision inspect(boolean show, boolean ready, boolean failed, boolean placed)
	{
		PresenceReason guard = sample(false, playerCombat.isActive());
		return gate.inspect(sceneGeneration, guard, show, ready, failed, placed);
	}

	/** A stale menu may revoke live permission, but cannot restore presence or earn committed stability. */
	public boolean permitsLocalControls()
	{
		if (!isPresent()) { return false; }
		PresenceReason guard = sample(false, playerCombat.isActive());
		if (guard != PresenceReason.NONE) { gate.inspect(sceneGeneration, guard, true, true, false, true); }
		return guard == PresenceReason.NONE && isPresent();
	}

	private PresenceReason sample(boolean committed, boolean playerCombatAbsent)
	{
		GameState state = client.getGameState();
		if (state != GameState.LOGGED_IN)
		{
			clearScene();
			return state == GameState.LOADING ? PresenceReason.LOADING
				: state == GameState.CONNECTION_LOST ? PresenceReason.CONNECTION_LOST
				: PresenceReason.LOGGED_OUT;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			clearScene();
			return PresenceReason.INVALID_SCENE;
		}
		WorldView view = player.getWorldView();
		LocalPoint location = player.getLocalLocation();
		WorldPoint world = player.getWorldLocation();
		if (view == null || location == null || world == null || view.getScene() == null
			|| view.getPlane() != world.getPlane() || location.getWorldView() != view.getId())
		{
			clearScene();
			return PresenceReason.INVALID_SCENE;
		}
		SceneIdentity current = new SceneIdentity(view, world.getPlane());
		if (!current.matches(scene))
		{
			playerCombat.invalidateScene();
			scene = current;
			sceneGeneration++;
			gate.invalidateScene();
			lastPlayerPoint = null;
			lastTemplateHash = Arrays.deepHashCode(view.getInstanceTemplateChunks());
		}
		if (committed)
		{
			int templateHash = Arrays.deepHashCode(view.getInstanceTemplateChunks());
			if (templateHash != lastTemplateHash || lastPlayerPoint != null
				&& lastPlayerPoint.distanceTo(world) > 2)
			{
				playerCombat.invalidateScene();
				sceneGeneration++;
				gate.invalidateScene();
			}
			lastTemplateHash = templateHash;
			lastPlayerPoint = world;
		}
		if (player.isDead() || client.getBoostedSkillLevel(Skill.HITPOINTS) <= 0)
		{
			return PresenceReason.DEAD;
		}
		if (PresenceGuards.isDeadSpectating(client.getVarbitValue(VarbitID.TOB_CLIENT_PARTYSTATUS)))
		{
			return PresenceReason.SPECTATING;
		}
		if (client.getWorldType() == null)
		{
			return PresenceReason.INVALID_SCENE;
		}
		if (playerCombatAbsent)
		{
			return PresenceReason.PLAYER_COMBAT;
		}
		if (!view.isTopLevel())
		{
			return PresenceReason.ABOARD;
		}
		PresenceReason pvpArea = OtherPlayerPolicy.ignoreOtherPlayers(client) ? PresenceReason.NONE
			: PresenceGuards.pvpArea(client.getVarbitValue(VarbitID.PVP_AREA_CLIENT), client.getPlayerOptions());
		if (pvpArea != PresenceReason.NONE)
		{
			return pvpArea;
		}
		if (client.getVarbitValue(VarbitID.CUTSCENE_STATUS) != 0)
		{
			return PresenceReason.CUTSCENE;
		}
		int x = location.getSceneX();
		int y = location.getSceneY();
		int plane = world.getPlane();
		CollisionData[] maps = view.getCollisionMaps();
		if (plane < 0 || maps == null || plane >= maps.length || maps[plane] == null
			|| maps[plane].getFlags() == null || x < 0 || y < 0
			|| x >= view.getSizeX() || y >= view.getSizeY()
			|| x >= maps[plane].getFlags().length || maps[plane].getFlags()[x] == null
			|| y >= maps[plane].getFlags()[x].length)
		{
			return PresenceReason.INVALID_SCENE;
		}
		return PresenceReason.NONE;
	}

	private boolean sampleBusy()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return true;
		}
		String typed = client.getVarcStrValue(VarClientID.CHATINPUT);
		if (typed != null && !typed.isEmpty())
		{
			return true;
		}
		for (int id : BUSY_WIDGETS)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden())
			{
				return true;
			}
		}
		return false;
	}

	/** Collect on the client thread immediately before projection publication, never in render. */
	public ModalOcclusionSnapshot sampleModalOcclusion()
	{
		if (client.getGameState() != GameState.LOGGED_IN
			|| BUSY_WIDGETS.length + ADDITIONAL_MODAL_WIDGETS.length != ModalOcclusionSnapshot.MAX_ROOTS)
		{
			busy = true;
			currentOutfitBlocked = true;
			currentInventoryBlocked = true;
			return ModalOcclusionSnapshot.UNKNOWN;
		}
		List<Rectangle> bounds = new ArrayList<>(ModalOcclusionSnapshot.MAX_ROOTS);
		String typed = client.getVarcStrValue(VarClientID.CHATINPUT);
		boolean currentBusy = typed != null && !typed.isEmpty(), known = true;
		boolean outfitBlocked = false, inventoryBlocked = false;
		for (int index = 0; index < ModalOcclusionSnapshot.MAX_ROOTS; index++)
		{
			int id = index < BUSY_WIDGETS.length ? BUSY_WIDGETS[index] : ADDITIONAL_MODAL_WIDGETS[index - BUSY_WIDGETS.length];
			Widget widget = client.getWidget(id);
			if (widget == null || widget.isHidden()) { continue; }
			if (index < BUSY_WIDGETS.length) { currentBusy = true; }
			if (id == InterfaceID.Bankmain.UNIVERSE || id == InterfaceID.BankDepositbox.UNIVERSE)
			{
				outfitBlocked = true;
				inventoryBlocked = true;
			}
			else if (id == InterfaceID.Trademain.UNIVERSE || id == InterfaceID.Tradeconfirm.UNIVERSE)
			{
				inventoryBlocked = true;
			}
			Rectangle rectangle = widget.getBounds();
			if (ModalOcclusionSnapshot.validBounds(rectangle)) { bounds.add(new Rectangle(rectangle)); }
			else { known = false; }
		}
		busy = currentBusy;
		currentOutfitBlocked = outfitBlocked;
		currentInventoryBlocked = inventoryBlocked;
		return known ? bounds.isEmpty() ? ModalOcclusionSnapshot.CLEAR : new ModalOcclusionSnapshot(true, bounds)
			: ModalOcclusionSnapshot.UNKNOWN;
	}

	public void onLocalDeath(long tick)
	{
		gate.onLocalDeath(tick);
	}

	public boolean onMenuOptionClicked(MenuOptionClicked event, long tick)
	{
		return playerCombat.onMenuOptionClicked(event, tick);
	}

	public boolean onHitsplatApplied(HitsplatApplied event, long tick)
	{
		return playerCombat.onHitsplatApplied(event, tick);
	}

	public void onInteractingChanged(InteractingChanged event, long tick)
	{
		playerCombat.onInteractingChanged(event, tick);
	}

	public void invalidateScene()
	{
		sceneGeneration++;
		playerCombat.invalidateScene();
		scene = null;
		lastPlayerPoint = null;
		gate.invalidateScene();
	}

	private void clearScene()
	{
		if (scene != null)
		{
			invalidateScene();
		}
	}

	public void reset()
	{
		gate = new PresenceGate();
		playerCombat.reset();
		sceneGeneration++;
		scene = null;
		lastPlayerPoint = null;
		lastTemplateHash = 0;
		busy = false;
		currentOutfitBlocked = true;
		currentInventoryBlocked = true;
	}

	public Decision placementSucceeded(long tick)
	{
		Decision result = gate.placementSucceeded(tick);
		if (result.isPresent()) { playerCombat.returned(); }
		return result;
	}
	public Decision getDecision() { return gate.getDecision(); }
	public boolean isPresent() { return getDecision().isPresent(); }
	public long getPresentSinceTick() { return getDecision().getPresentSinceTick(); }
	public long getPresenceEpoch() { return getDecision().getPresenceEpoch(); }
	public long getSceneGeneration() { return sceneGeneration; }
	public boolean isBusy() { return busy; }
	public boolean isCurrentOutfitBlocked() { return currentOutfitBlocked; }
	public boolean isCurrentInventoryBlocked() { return currentInventoryBlocked; }

	private static final class SceneIdentity
	{
		private final WorldView view;
		private final Object scene;
		private final int plane;
		private final int baseX;
		private final int baseY;
		private final int sizeX;
		private final int sizeY;
		private final boolean instance;

		private SceneIdentity(WorldView view, int plane)
		{
			this.view = view;
			this.scene = view.getScene();
			this.plane = plane;
			baseX = view.getBaseX();
			baseY = view.getBaseY();
			sizeX = view.getSizeX();
			sizeY = view.getSizeY();
			instance = view.isInstance();
		}

		private boolean matches(SceneIdentity other)
		{
			return other != null && view == other.view && scene == other.scene && plane == other.plane
				&& baseX == other.baseX && baseY == other.baseY && sizeX == other.sizeX
				&& sizeY == other.sizeY && instance == other.instance;
		}
	}

	public static final class Decision
	{
		private final PresenceState state;
		private final PresenceReason reason;
		private final boolean placementAllowed;
		private final boolean preparationAllowed;
		private final long presentSinceTick;
		private final long presenceEpoch;
		private final int stableTicks;
		private final int requiredStableTicks;

		Decision(PresenceState state, PresenceReason reason, boolean placementAllowed,
			boolean preparationAllowed, long presentSinceTick, long presenceEpoch,
			int stableTicks, int requiredStableTicks)
		{
			this.state = state;
			this.reason = reason;
			this.placementAllowed = placementAllowed;
			this.preparationAllowed = preparationAllowed;
			this.presentSinceTick = presentSinceTick;
			this.presenceEpoch = presenceEpoch;
			this.stableTicks = stableTicks;
			this.requiredStableTicks = requiredStableTicks;
		}

		public PresenceState getState() { return state; }
		public PresenceReason getReason() { return reason; }
		public boolean isPlacementAllowed() { return placementAllowed; }
		public boolean isPreparationAllowed() { return preparationAllowed; }
		public boolean isPresent() { return state == PresenceState.PRESENT; }
		public long getPresentSinceTick() { return presentSinceTick; }
		public long getPresenceEpoch() { return presenceEpoch; }
		public int getStableTicks() { return stableTicks; }
		public int getRequiredStableTicks() { return requiredStableTicks; }
	}
}
