package com.malfoid.speech;

import java.awt.FontMetrics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The client's ordered overhead-text overlap rule, followed by complete-paint bounds. */
final class OverheadLayout
{
    final int centerX;
    final int baselineY;
    final int firstBaselineY;
    final List<String> lines;
    final Rectangle bounds;

    private OverheadLayout(int centerX, int baselineY, int firstBaselineY, List<String> lines, Rectangle bounds)
    {
        this.centerX = centerX;
        this.baselineY = baselineY;
        this.firstBaselineY = firstBaselineY;
        this.lines = Collections.unmodifiableList(lines);
        this.bounds = bounds;
    }

    static OverheadLayout create(String text, FontMetrics metrics, Point anchor, Rectangle viewport,
        List<NativeOverheadText> nativeTexts)
    {
        if (anchor == null || viewport == null || viewport.width <= 0 || viewport.height <= 0
            || !viewport.contains(anchor) || nativeTexts == null)
        {
            return null;
        }
        List<String> lines = wrap(text, metrics, Math.min(280, viewport.width - 4));
        int width = 0;
        for (String line : lines) { width = Math.max(width, metrics.stringWidth(line)); }
        int fontAscent = metrics.getAscent();
        int ascent = (lines.size() - 1) * metrics.getHeight() + fontAscent;
        if (lines.isEmpty() || width <= 0 || fontAscent <= 0 || width + 1L > viewport.width)
        {
            return null;
        }
        int halfWidth = width / 2;
        int centerX = Math.max(viewport.x + halfWidth,
            Math.min(anchor.x, viewport.x + viewport.width - width + halfWidth - 1));
        int nativeCount = Math.min(nativeTexts.size(), NativeOverheadText.MAX_NATIVE_TEXTS);
        List<PlacedText> earlier = new ArrayList<>(nativeCount);
        for (int index = 0; index < nativeCount; index++)
        {
            NativeOverheadText nativeText = nativeTexts.get(index);
            if (nativeText == null) { return null; }
            int nativeHalfWidth = metrics.stringWidth(nativeText.getText()) / 2;
            long baseline = stack(nativeText.getCenterX(), nativeText.getBaselineY(), nativeHalfWidth, fontAscent, earlier);
            earlier.add(new PlacedText(nativeText.getCenterX(), baseline, nativeHalfWidth, fontAscent));
        }
        long baseline = stack(centerX, anchor.y, halfWidth, ascent, earlier);
        long top = baseline - ascent;
        long bottom = baseline + metrics.getDescent() + 1L;
        if (top < viewport.y || bottom > (long) viewport.y + viewport.height
            || baseline < Integer.MIN_VALUE || baseline > Integer.MAX_VALUE)
        {
            return null;
        }
        Rectangle bounds = new Rectangle(centerX - halfWidth, (int) top, width + 1, (int) (bottom - top));
        return new OverheadLayout(centerX, (int) baseline,
            (int) baseline - (lines.size() - 1) * metrics.getHeight(), lines, bounds);
    }

    private static List<String> wrap(String text, FontMetrics metrics, int maximumWidth)
    {
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("(?U)\\s+"))
        {
            if (line.length() > 0 && metrics.stringWidth(line + " " + word) > maximumWidth)
            {
                result.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) { line.append(' '); }
            line.append(word);
        }
        if (line.length() > 0) { result.add(line.toString()); }
        return result;
    }

    private static long stack(int x, long y, int halfWidth, int ascent, List<PlacedText> earlier)
    {
        boolean overlap;
        do
        {
            overlap = false;
            for (PlacedText previous : earlier)
            {
                if (y + 2 > previous.y - previous.ascent && y - ascent < previous.y + 2
                    && (long) x - halfWidth < (long) previous.x + previous.halfWidth
                    && (long) x + halfWidth > (long) previous.x - previous.halfWidth
                    && previous.y - previous.ascent < y)
                {
                    y = previous.y - previous.ascent;
                    overlap = true;
                }
            }
        }
        while (overlap);
        return y;
    }

    private static final class PlacedText
    {
        private final int x, halfWidth, ascent;
        private final long y;

        private PlacedText(int x, long y, int halfWidth, int ascent)
        {
            this.x = x;
            this.y = y;
            this.halfWidth = halfWidth;
            this.ascent = ascent;
        }
    }
}
