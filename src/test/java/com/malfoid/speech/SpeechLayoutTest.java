package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import net.runelite.client.ui.FontManager;
import static org.junit.Assert.*;

public class SpeechLayoutTest
{
    @Test
    public void completeAuthoredTextKeepsItsWrappingAndNativeLowestBaseline() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        Graphics2D graphics = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            for (Font font : new Font[] {FontManager.getRunescapeBoldFont(), new Font("SansSerif", Font.BOLD, 16)})
            {
                graphics.setFont(font);
                FontMetrics metrics = graphics.getFontMetrics();
                for (int width : new int[] {512, 765})
                {
                    Rectangle viewport = new Rectangle(0, 0, width, 334);
                    for (int i = 1; i <= 70; i++)
                    {
                        for (com.malfoid.dialogue.DialogueLine line : catalogue.getLines(String.format("M%02d", i)))
                        {
                            if (line.isEnabled())
                            {
                                OverheadLayout layout = OverheadLayout.create(line.getText(), metrics,
                                    new Point(width / 2, 200), viewport, Collections.emptyList());
                                assertNotNull(line.getId(), layout);
                                assertEquals(line.getText(), String.join(" ", layout.lines));
                                assertEquals(200, layout.baselineY);
                                assertEquals(200 - (layout.lines.size() - 1) * metrics.getHeight(), layout.firstBaselineY);
                                assertTrue(viewport.contains(layout.bounds));
                            }
                        }
                    }
                }
            }
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void wrappedLinesGrowUpwardAndNativeOverlapMovesOnlyTheLowestBaseline()
    {
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            String text = ("Every word stays in this complete sentence above the same projected head. ").repeat(3).trim();
            Rectangle viewport = new Rectangle(0, 0, 512, 334);
            OverheadLayout plain = OverheadLayout.create(text, metrics, new Point(256, 220), viewport, Collections.emptyList());
            assertNotNull(plain);
            assertTrue(plain.lines.size() > 1);
            assertEquals(text, String.join(" ", plain.lines));
            assertEquals(220, plain.baselineY);
            assertEquals(220 - (plain.lines.size() - 1) * metrics.getHeight(), plain.firstBaselineY);
            OverheadLayout stacked = OverheadLayout.create(text, metrics, new Point(256, 220), viewport,
                Collections.singletonList(new NativeOverheadText(256, 220, "Native text")));
            assertNotNull(stacked);
            assertEquals(220 - metrics.getAscent(), stacked.baselineY);
            assertEquals(plain.firstBaselineY - metrics.getAscent(), stacked.firstBaselineY);
            assertEquals(plain.lines, stacked.lines);
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void onlyTheFirstFiftyOrderedNativeTextsParticipate()
    {
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            List<NativeOverheadText> nativeTexts = new ArrayList<>();
            for (int index = 0; index < NativeOverheadText.MAX_NATIVE_TEXTS; index++)
            {
                nativeTexts.add(new NativeOverheadText(10, 200, "Far"));
            }
            nativeTexts.add(new NativeOverheadText(256, 200, "Beyond the native limit"));
            OverheadLayout layout = OverheadLayout.create("Hello there", graphics.getFontMetrics(), new Point(256, 200),
                new Rectangle(0, 0, 512, 334), nativeTexts);
            assertNotNull(layout);
            assertEquals(50, NativeOverheadText.MAX_NATIVE_TEXTS);
            assertEquals(200, layout.baselineY);
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void offscreenOrVerticallyOverflowingTextIsNeverRepositioned()
    {
        Graphics2D graphics = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            FontMetrics metrics = graphics.getFontMetrics();
            Rectangle viewport = new Rectangle(0, 0, 300, 200);
            assertNull(OverheadLayout.create("Hello there", metrics, new Point(-1, 50), viewport, Collections.emptyList()));
            assertNull(OverheadLayout.create("Hello there", metrics, new Point(150, 1), viewport, Collections.emptyList()));
            assertNull(OverheadLayout.create("Hello there", metrics, new Point(150, 199), viewport, Collections.emptyList()));
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void nativeTextsAreStackedInOrderBeforeMalfoidWithExactlyOneAscentPerOverlap()
    {
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            Rectangle viewport = new Rectangle(0, 0, 512, 334);
            OverheadLayout layout = OverheadLayout.create("Hello there", metrics, new Point(256, 200), viewport,
                Arrays.asList(new NativeOverheadText(256, 200, "Local player"),
                    new NativeOverheadText(256, 200, "Other player"), new NativeOverheadText(256, 200, "NPC")));
            assertNotNull(layout);
            assertEquals(200 - 3 * metrics.getAscent(), layout.baselineY);
            assertEquals(256, layout.centerX);
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void touchingNativeTextEdgesDoNotMoveTheBaseline()
    {
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            Rectangle viewport = new Rectangle(0, 0, 512, 334);
            String text = "Hello there";
            int half = metrics.stringWidth(text) / 2, ascent = metrics.getAscent();
            for (NativeOverheadText earlier : new NativeOverheadText[] {
                new NativeOverheadText(256 + 2 * half, 200, text),
                new NativeOverheadText(256, 200 - ascent - 2, text),
                new NativeOverheadText(256, 200 + ascent, text)})
            {
                OverheadLayout layout = OverheadLayout.create(text, metrics, new Point(256, 200), viewport,
                    Collections.singletonList(earlier));
                assertNotNull(layout);
                assertEquals(200, layout.baselineY);
            }
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void movingPastALaterTextRechecksAnEarlierText()
    {
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            int ascent = metrics.getAscent();
            OverheadLayout layout = OverheadLayout.create("Hello there", metrics, new Point(256, 200 + ascent - 2),
                new Rectangle(0, 0, 512, 334), Arrays.asList(
                    new NativeOverheadText(256, 200 - 2 * ascent, "First"),
                    new NativeOverheadText(256, 200, "Second")));
            assertNotNull(layout);
            assertEquals(200 - 3 * ascent, layout.baselineY);
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void onlyHorizontalClampingChangesAnUnobstructedHeadProjection()
    {
        Graphics2D graphics = new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            Rectangle viewport = new Rectangle(0, 0, 300, 200);
            for (int x : new int[] {0, 299})
            {
                OverheadLayout layout = OverheadLayout.create("Hello there", metrics, new Point(x, 100),
                    viewport, Collections.emptyList());
                assertNotNull(layout);
                assertEquals(100, layout.baselineY);
                assertTrue(viewport.contains(layout.bounds));
            }
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void readableHoldIsLengthScaledAndFadeNeverRecommits() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        SpeechGuard guard = new SpeechGuard(clock::get, new Random(1), 349, 52);
        SceneCandidate candidate = new SceneCandidate(1, "U01", 85, 0, 10, 1, 1, "talk", Collections.emptyMap(), false, 4);
        com.malfoid.dialogue.DialogueLine line = catalogue.getLine("U01-01");
        SpeechPacket packet = new SpeechPacket(candidate, line, guard.reserve(null, line.getNormalizedText(), null), 0);
        assertNotNull(packet.paint(0, 0, 1, () -> {}));
        long holdMs = Math.max(3000, Math.min(7000, 2000 + 60 * line.getText().codePointCount(0, line.getText().length())));
        assertEquals(1f, packet.alpha(holdMs * 1_000_000L), 0f);
        assertEquals(.5f, packet.alpha(holdMs * 1_000_000L + 250_000_000L), .0001f);
        assertFalse(packet.canPaint(holdMs * 1_000_000L + 500_000_000L));
    }
}
