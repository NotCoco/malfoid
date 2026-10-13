package com.malfoid.motion;

/** Conservative projection of the complete pose-bank union, rather than only a foot or head anchor. */
public final class WholeBoundsVisibility
{
    public enum Visibility { UNKNOWN, VISIBLE, OFF_SCREEN }

    public interface Projector
    {
        /** Outputs camera x, camera y and depth for scene x, scene y and vertical z. */
        boolean project(double x, double y, double z, double[] output);
    }

    public static final class Bounds
    {
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
        {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
    }

    private WholeBoundsVisibility() {}

    public static Visibility classify(Bounds bounds, double sceneX, double sceneY, double drawZ, int orientation,
        Projector projector, int viewportX, int viewportY, int width, int height, int scale)
    {
        if (bounds == null || projector == null || width <= 0 || height <= 0 || scale <= 0
            || !Double.isFinite(sceneX) || !Double.isFinite(sceneY) || !Double.isFinite(drawZ))
        {
            return Visibility.UNKNOWN;
        }
        double radians = (orientation & 2047) * Math.PI / 1024;
        double sin = Math.sin(radians), cos = Math.cos(radians);
        double minCanvasX = Double.POSITIVE_INFINITY, minCanvasY = Double.POSITIVE_INFINITY;
        double maxCanvasX = Double.NEGATIVE_INFINITY, maxCanvasY = Double.NEGATIVE_INFINITY;
        int inFront = 0;
        double[] projection = new double[3];
        for (int corner = 0; corner < 8; corner++)
        {
            double x = (corner & 1) == 0 ? bounds.minX : bounds.maxX;
            double y = (corner & 2) == 0 ? bounds.minY : bounds.maxY;
            double z = (corner & 4) == 0 ? bounds.minZ : bounds.maxZ;
            if (!projector.project(sceneX + x * cos + z * sin, sceneY + z * cos - x * sin, drawZ + y, projection)
                || !Double.isFinite(projection[0]) || !Double.isFinite(projection[1]) || !Double.isFinite(projection[2]))
            {
                return Visibility.UNKNOWN;
            }
            if (projection[2] < 50)
            {
                continue;
            }
            inFront++;
            double canvasX = viewportX + width / 2d + projection[0] * scale / projection[2];
            double canvasY = viewportY + height / 2d + projection[1] * scale / projection[2];
            if (!Double.isFinite(canvasX) || !Double.isFinite(canvasY))
            {
                return Visibility.UNKNOWN;
            }
            minCanvasX = Math.min(minCanvasX, canvasX);
            maxCanvasX = Math.max(maxCanvasX, canvasX);
            minCanvasY = Math.min(minCanvasY, canvasY);
            maxCanvasY = Math.max(maxCanvasY, canvasY);
        }
        if (inFront == 0)
        {
            return Visibility.OFF_SCREEN;
        }
        if (inFront != 8)
        {
            // Near-plane crossings can expose a clipped face even when every projected corner is outside.
            return Visibility.UNKNOWN;
        }
        // The bank union is already padded in model space; retain a screen margin for native rounding.
        return maxCanvasX < viewportX - 4 || minCanvasX > viewportX + width + 4
            || maxCanvasY < viewportY - 4 || minCanvasY > viewportY + height + 4
            ? Visibility.OFF_SCREEN : Visibility.VISIBLE;
    }
}
