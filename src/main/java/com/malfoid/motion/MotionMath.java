package com.malfoid.motion;

public final class MotionMath
{
    private MotionMath() {}

    public static int bearing(double dx, double dy)
    {
        return ((int) Math.round(Math.atan2(-dx, -dy) * 1024 / Math.PI)) & 2047;
    }

    public static int quantize(int orientation)
    {
        return ((orientation + 16) & ~31) & 2047;
    }

    public static int turn(int orientation, int desired)
    {
        int difference = ((desired - orientation + 1024) & 2047) - 1024;
        return (orientation + Math.max(-32, Math.min(32, difference))) & 2047;
    }

    public static int distance(int x, int y, int otherX, int otherY)
    {
        return Math.max(Math.abs(x - otherX), Math.abs(y - otherY));
    }

    public static int cameraBearing(int cameraYaw) { return (-cameraYaw) & 2047; }

    public static int angleDifference(int first, int second)
    {
        return Math.abs(((first - second + 1024) & 2047) - 1024);
    }

    public static int footprintDistance(int x, int y, int minX, int minY, int maxX, int maxY)
    {
        int dx = x < minX ? minX - x : x > maxX ? x - maxX : 0;
        int dy = y < minY ? minY - y : y > maxY ? y - maxY : 0;
        return Math.max(dx, dy);
    }
}
