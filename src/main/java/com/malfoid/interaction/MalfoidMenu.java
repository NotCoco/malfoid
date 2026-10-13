package com.malfoid.interaction;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import com.malfoid.asset.NativeActor;
import com.malfoid.presence.PresenceController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.Player;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Preferred MenuOpened-only local controls. No mouse/key listeners or menu-default changes. */
public final class MalfoidMenu
{
	private static final String TARGET = "<col=ffff00>Malfoid";
	private static final int[] INTERFACE_HIT_WIDGETS = {
		InterfaceID.Inventory.ITEMS, InterfaceID.InventoryNoops.ITEMS, InterfaceID.Chatbox.UNIVERSE,
		InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE, InterfaceID.Chatmenu.UNIVERSE,
		InterfaceID.Messagebox.UNIVERSE, InterfaceID.Objectbox.UNIVERSE, InterfaceID.Bankmain.UNIVERSE,
		InterfaceID.BankDepositbox.UNIVERSE, InterfaceID.BankpinKeypad.UNIVERSE,
		InterfaceID.Trademain.UNIVERSE, InterfaceID.Tradeconfirm.UNIVERSE, InterfaceID.GeOffers.UNIVERSE,
		InterfaceID.Shopmain.UNIVERSE, InterfaceID.Skillmulti.UNIVERSE, InterfaceID.Worldmap.UNIVERSE
	};
	private final Client client;
	private Consumer<Throwable> failureHandler;
	private Consumer<PresenceController.Decision> absenceHandler;

	@Inject
	public MalfoidMenu(Client client)
	{
		this.client = client;
	}

	public void setFailureHandler(Consumer<Throwable> failureHandler)
	{
		this.failureHandler = Objects.requireNonNull(failureHandler);
	}

	public void setAbsenceHandler(Consumer<PresenceController.Decision> absenceHandler)
	{
		this.absenceHandler = Objects.requireNonNull(absenceHandler);
	}

	public void onMenuOpened(MenuOpened event, NativeActor actor, PresenceController presence,
		long generation, LongSupplier currentGeneration, Runnable talk, Runnable dismiss, Runnable examine)
	{
		if (failureHandler == null)
		{
			return;
		}
		NativeActor.PlacementSnapshot snapshot = actor.getPlacementSnapshot();
		if (!permitsLocalControls(presence) || !placementCurrent(snapshot))
		{
			return;
		}
		Point mouse = client.getMouseCanvasPosition();
		MenuEntry[] entries = event.getMenuEntries();
		if (mouse == null || entries == null)
		{
			return;
		}
		boolean hasWalk = false;
		boolean widgetEntry = false;
		for (MenuEntry entry : entries)
		{
			if (entry == null)
			{
				continue;
			}
			hasWalk |= entry.getType() == MenuAction.WALK;
			widgetEntry |= entry.getWidget() != null;
			if (entry.getType() == MenuAction.RUNELITE && TARGET.equals(entry.getTarget())
				&& ("Talk-to".equals(entry.getOption()) || "Dismiss".equals(entry.getOption())
					|| "Examine".equals(entry.getOption())))
			{
				return;
			}
		}
		Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
			client.getViewportWidth(), client.getViewportHeight());
		if (!hasWalk || widgetEntry || interfaceHit(mouse) || !viewport.contains(mouse.getX(), mouse.getY()))
		{
			return;
		}
		Shape clickbox = actor.getClickbox();
		if (!MenuPolicy.mayOpen(true, true, hasWalk, false, mouse.getX(), mouse.getY(), viewport, clickbox))
		{
			return;
		}
		MenuPolicy.Lease lease = new MenuPolicy.Lease(generation, presence.getSceneGeneration(),
			presence.getPresenceEpoch(), snapshot.getPlacementGeneration());
		Menu menu = client.getMenu();
		// The array is bottom-to-top. Repeated index 1 insertion leaves Cancel at zero.
		add(menu, "Talk-to", snapshot, lease, actor, presence, currentGeneration, talk);
		add(menu, "Dismiss", snapshot, lease, actor, presence, currentGeneration, dismiss);
		add(menu, "Examine", snapshot, lease, actor, presence, currentGeneration, examine);
	}

	private void add(Menu menu, String option, NativeActor.PlacementSnapshot opened,
		MenuPolicy.Lease lease, NativeActor actor, PresenceController presence,
		LongSupplier generation, Runnable command)
	{
		Consumer<Throwable> onFailure = failureHandler;
		menu.createMenuEntry(1).setOption(option).setTarget(TARGET).setType(MenuAction.RUNELITE)
			.setWorldViewId(opened.getWorldViewId()).onClick(entry ->
			{
				try
				{
					NativeActor.PlacementSnapshot current = actor.getPlacementSnapshot();
					if (permitsLocalControls(presence) && placementCurrent(current) && current.getWorldView() == opened.getWorldView()
						&& current.getScene() == opened.getScene()
						&& current.getPlane() == opened.getPlane()
						&& lease.isCurrent(generation.getAsLong(), presence.getSceneGeneration(),
							presence.getPresenceEpoch(), current.getPlacementGeneration(), presence.isPresent()))
					{
						command.run();
					}
				}
				catch (Throwable failure)
				{
					onFailure.accept(failure);
				}
			});
	}

	private boolean permitsLocalControls(PresenceController presence)
	{
		boolean wasPresent = presence.isPresent();
		boolean permitted = presence.permitsLocalControls();
		if (wasPresent && !permitted && !presence.isPresent() && absenceHandler != null)
		{
			absenceHandler.accept(presence.getDecision());
		}
		return permitted;
	}

	private boolean placementCurrent(NativeActor.PlacementSnapshot snapshot)
	{
		Player player = client.getLocalPlayer();
		return snapshot != null && client.getGameState() == GameState.LOGGED_IN && player != null
			&& player.getWorldView() == snapshot.getWorldView()
			&& snapshot.getWorldView().getScene() == snapshot.getScene()
			&& snapshot.getWorldView().getPlane() == snapshot.getPlane();
	}

	private boolean interfaceHit(Point mouse)
	{
		for (int id : INTERFACE_HIT_WIDGETS)
		{
			Widget widget = client.getWidget(id);
			if (widget != null && !widget.isHidden())
			{
				Rectangle bounds = widget.getBounds();
				if (bounds != null && bounds.contains(mouse.getX(), mouse.getY()))
				{
					return true;
				}
			}
		}
		return false;
	}
}
