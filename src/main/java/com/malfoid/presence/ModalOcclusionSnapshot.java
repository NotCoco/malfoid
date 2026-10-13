package com.malfoid.presence;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable bounded client-thread copy. Rendering never dereferences a live widget. */
public final class ModalOcclusionSnapshot
{
	public static final int MAX_ROOTS = 16;
	public static final ModalOcclusionSnapshot CLEAR = new ModalOcclusionSnapshot(true, Collections.emptyList());
	public static final ModalOcclusionSnapshot UNKNOWN = new ModalOcclusionSnapshot(false, Collections.emptyList());
	private final boolean known;
	private final List<Rectangle> bounds;

	public ModalOcclusionSnapshot(boolean known, List<Rectangle> copiedBounds)
	{
		List<Rectangle> copy = new ArrayList<>();
		boolean valid = known && copiedBounds != null && copiedBounds.size() <= MAX_ROOTS;
		if (valid)
		{
			for (Rectangle rectangle : copiedBounds)
			{
				if (!validBounds(rectangle)) { valid = false; break; }
				copy.add(new Rectangle(rectangle));
			}
		}
		this.known = valid;
		bounds = valid ? Collections.unmodifiableList(copy) : Collections.emptyList();
	}
	public boolean isKnown() { return known; }

	/** Unknown or invalid queried pixel geometry cannot authorize a first paint. */
	public boolean intersects(Rectangle canvasBounds)
	{
		if (!known || !validBounds(canvasBounds)) { return true; }
		for (Rectangle rectangle : bounds) { if (rectangle.intersects(canvasBounds)) { return true; } }
		return false;
	}
	/** For inspection/tests only. Renderers can call intersects without making further copies. */
	public List<Rectangle> getBounds()
	{
		List<Rectangle> copy = new ArrayList<>(bounds.size());
		for (Rectangle rectangle : bounds) { copy.add(new Rectangle(rectangle)); }
		return Collections.unmodifiableList(copy);
	}
	static boolean validBounds(Rectangle rectangle)
	{
		return rectangle != null && rectangle.width > 0 && rectangle.height > 0
			&& (long) rectangle.x + rectangle.width <= Integer.MAX_VALUE && (long) rectangle.y + rectangle.height <= Integer.MAX_VALUE;
	}
}
