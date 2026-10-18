package com.malfoid.interaction;

import java.awt.Rectangle;
import org.junit.Test;

import static org.junit.Assert.*;

public class MenuPolicyTest
{
	private final Rectangle viewport = new Rectangle(30, 60, 500, 400);
	private final Rectangle clickbox = new Rectangle(80, 100, 30, 70);

	@Test
	public void menuNeedsPresentPlacementSceneWalkAndExactClickboxHit()
	{
		assertTrue(MenuPolicy.mayOpen(true, true, true, false, 90, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(false, true, true, false, 90, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(true, false, true, false, 90, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(true, true, false, false, 90, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(true, true, true, true, 90, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(true, true, true, false, 79, 110, viewport, clickbox));
		assertFalse(MenuPolicy.mayOpen(true, true, true, false, 90, 110, viewport, null));
	}

	@Test
	public void viewportOffsetsAreAppliedIndependently()
	{
		Rectangle overhang = new Rectangle(0, 0, 100, 100);
		assertFalse(MenuPolicy.mayOpen(true, true, true, false, 40, 50, viewport, overhang));
		assertTrue(MenuPolicy.mayOpen(true, true, true, false, 40, 70, viewport, overhang));
	}

	@Test
	public void animationAndWalkingDoNotInvalidateAnOpenedMenuButPlacementOrLifecycleDoes()
	{
		MenuPolicy.Lease opened = new MenuPolicy.Lease(3, 7, 5, 19);
		for (int poseRevision = 0; poseRevision < 100; poseRevision++)
		{
			assertTrue(opened.isCurrent(3, 7, 5, 19, true));
		}
		assertFalse(opened.isCurrent(4, 7, 5, 19, true));
		assertFalse(opened.isCurrent(3, 8, 5, 19, true));
		assertFalse(opened.isCurrent(3, 7, 6, 19, true));
		assertFalse(opened.isCurrent(3, 7, 5, 20, true));
		assertFalse(opened.isCurrent(3, 7, 5, 19, false));
	}
}
