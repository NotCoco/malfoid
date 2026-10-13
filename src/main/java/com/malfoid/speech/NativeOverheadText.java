package com.malfoid.speech;

import java.util.Objects;

/** One client-thread copy of a native actor's overhead text and head projection. */
public final class NativeOverheadText
{
    public static final int MAX_NATIVE_TEXTS = 50;

    private final int centerX;
    private final int baselineY;
    private final String text;

    public NativeOverheadText(int centerX, int baselineY, String text)
    {
        this.centerX = centerX;
        this.baselineY = baselineY;
        this.text = Objects.requireNonNull(text);
    }

    public int getCenterX() { return centerX; }
    public int getBaselineY() { return baselineY; }
    public String getText() { return text; }
}
