package com.malfoid.asset;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.Perspective;

/** The pinned public NPC clickbox recipe, called only on a right-click menu opening. */
final class ClickboxAdapter
{
    private ClickboxAdapter() {}

    static Shape get(Client client, NativeActor.PlacementSnapshot placement)
    {
        if (placement == null)
        {
            return null;
        }
        Shape box = Perspective.getClickbox(client, placement.getWorldView(), placement.getModel(), placement.getOrientation(),
            placement.getLocation().getX(), placement.getLocation().getY(), placement.getDrawZ());
        if (box == null)
        {
            return null;
        }
        Area clipped = new Area(box);
        clipped.intersect(new Area(new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
            client.getViewportWidth(), client.getViewportHeight())));
        return clipped.isEmpty() ? null : clipped;
    }
}
