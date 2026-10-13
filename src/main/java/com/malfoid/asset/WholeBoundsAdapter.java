package com.malfoid.asset;

import com.malfoid.motion.WholeBoundsVisibility;
import net.runelite.api.Client;

/** Camera-space math follows the public stock Perspective CPU/GPU projection convention. */
final class WholeBoundsAdapter
{
    private WholeBoundsAdapter() {}

    static WholeBoundsVisibility.Visibility classify(Client client, NativeActor.PlacementSnapshot placement,
        WholeBoundsVisibility.Bounds bounds)
    {
        if (placement == null || !placement.getWorldView().isTopLevel())
        {
            // Moving subviews remain absent until their separate native placement/projection gate passes.
            return WholeBoundsVisibility.Visibility.UNKNOWN;
        }
        boolean gpu = client.isGpu();
        double cameraX = gpu ? client.getCameraFpX() : client.getCameraX();
        double cameraY = gpu ? client.getCameraFpY() : client.getCameraY();
        double cameraZ = gpu ? client.getCameraFpZ() : client.getCameraZ();
        double yaw = gpu ? client.getCameraFpYaw() : client.getCameraYaw() * Math.PI / 1024;
        double pitch = gpu ? client.getCameraFpPitch() : client.getCameraPitch() * Math.PI / 1024;
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)
            || !Double.isFinite(yaw) || !Double.isFinite(pitch) || Math.abs(cameraX) > 65536
            || Math.abs(cameraY) > 65536 || Math.abs(cameraZ) > 65536)
        {
            return WholeBoundsVisibility.Visibility.UNKNOWN;
        }
        double yawSin = Math.sin(yaw), yawCos = Math.cos(yaw), pitchSin = Math.sin(pitch), pitchCos = Math.cos(pitch);
        WholeBoundsVisibility.Projector projector = (x, y, z, output) ->
        {
            double fx = x - cameraX, fy = y - cameraY, fz = z - cameraZ;
            double side = fx * yawCos + fy * yawSin;
            double forward = fy * yawCos - fx * yawSin;
            output[0] = side;
            output[1] = fz * pitchCos - forward * pitchSin;
            output[2] = forward * pitchCos + fz * pitchSin;
            return true;
        };
        return WholeBoundsVisibility.classify(bounds, placement.getLocation().getX(), placement.getLocation().getY(),
            placement.getDrawZ(), placement.getOrientation(), projector, client.getViewportXOffset(), client.getViewportYOffset(),
            client.getViewportWidth(), client.getViewportHeight(), client.getScale());
    }
}
