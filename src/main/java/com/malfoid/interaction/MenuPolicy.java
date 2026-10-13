package com.malfoid.interaction;

import java.awt.Rectangle;
import java.awt.Shape;

/** Shared eligibility/identity checks; no menu construction runs on hover or ordinary left-click. */
final class MenuPolicy
{
	static boolean mayOpen(boolean present, boolean hasPlacement, boolean hasWalk,
		boolean interfaceHit, int mouseX, int mouseY, Rectangle viewport, Shape clickbox)
	{
		return present && hasPlacement && hasWalk && !interfaceHit && viewport != null
			&& viewport.contains(mouseX, mouseY) && clickbox != null && clickbox.contains(mouseX, mouseY);
	}

	static final class Lease
	{
		private final long session;
		private final long scene;
		private final long epoch;
		private final long placement;

		Lease(long session, long scene, long epoch, long placement)
		{
			this.session = session;
			this.scene = scene;
			this.epoch = epoch;
			this.placement = placement;
		}

		boolean isCurrent(long session, long scene, long epoch, long placement, boolean present)
		{
			return present && this.session == session && this.scene == scene && this.epoch == epoch
				&& this.placement == placement;
		}
	}
}
