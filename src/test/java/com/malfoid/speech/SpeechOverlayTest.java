package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.presence.ModalOcclusionSnapshot;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.FontManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SpeechOverlayTest
{
    @Test
    public void copiedModalHeadAndCompleteTextOcclusionNeverPaintOrDebit() throws Exception
    {
        for (boolean coversHead : new boolean[] {true, false})
        {
            Fixture f = new Fixture();
            f.begin(1, 1);
            BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try
            {
                graphics.setFont(FontManager.getRunescapeBoldFont());
                OverheadLayout layout = OverheadLayout.create(f.director.getPacket().getText(),
                    graphics.getFontMetrics(), new Point(256, 200), new Rectangle(0, 0, 512, 334), Collections.emptyList());
                Rectangle covered = coversHead ? new Rectangle(256, 200, 1, 1)
                    : new Rectangle(layout.bounds.x, layout.bounds.y, 1, 1);
                f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 2, null, 0, null,
                    new ModalOcclusionSnapshot(true, Collections.singletonList(covered)));
                f.overlay.render(graphics);
                assertFalse(f.director.getPacket().isPainted());
                assertFalse(f.overlay.isDrawable());
                assertEquals(0, f.guard.textCount());
                assertEquals(0, f.cooldowns.draws);
                assertTrue(f.callbacks.isEmpty());
            }
            finally { graphics.dispose(); }
        }
    }

    @Test
    public void unknownModalBoundsFailClosedUntilACompleteClearCopyIsPublished() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 2, null, 0, null,
                ModalOcclusionSnapshot.UNKNOWN);
            f.overlay.render(graphics);
            assertFalse(f.overlay.isHeadVisible());
            assertFalse(f.director.getPacket().isPainted());
            assertTrue(f.callbacks.isEmpty());
            f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 3, null, 0, null,
                new ModalOcclusionSnapshot(true, Collections.singletonList(new Rectangle(0, 0, 20, 20))));
            f.overlay.render(graphics);
            assertTrue(f.overlay.isHeadVisible());
            assertTrue(f.director.getPacket().isPainted());
            assertEquals(1, f.callbacks.size());
            f.callbacks.get(0).run();
            assertEquals(1, f.cooldowns.draws);
            assertEquals(1, f.guard.textCount());
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void laterModalOcclusionCannotEraseARealQueuedFirstPaintOrCreateAnotherReceipt() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            f.overlay.render(graphics);
            assertTrue(f.director.getPacket().isPainted());
            assertEquals(1, f.callbacks.size());
            assertEquals(0, f.cooldowns.draws);
            f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 2, null, 0, null,
                new ModalOcclusionSnapshot(true, Collections.singletonList(new Rectangle(0, 0, 512, 334))));
            f.overlay.render(graphics);
            assertFalse(f.overlay.isDrawable());
            assertEquals(1, f.callbacks.size());
            f.callbacks.get(0).run();
            f.callbacks.get(0).run();
            assertEquals(1, f.cooldowns.draws);
            assertEquals(1, f.guard.textCount());
            assertNotNull(f.director.getLastPaint());
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void queuedRenderFailureCannotDisableAReusedOverlayInANewerSession() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        f.overlay.render(null);
        assertEquals(1, f.callbacks.size());
        f.director.resetSession(2);
        f.callbacks.get(0).run();
        assertTrue(f.failures.isEmpty());
        assertEquals(2, f.director.getSessionGeneration());
    }

    @Test
    public void failingOldSnapshotDoesNotRevokeOrClearANewerPublishedPacket() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        SpeechPacket old = f.director.getPacket();
        Graphics2D graphics = mock(Graphics2D.class);
        when(graphics.getFont()).thenAnswer(invocation -> {
            f.director.resetSession(2);
            f.begin(2, 2);
            throw new IllegalStateException("Old frame failed after session change");
        });
        f.overlay.render(graphics);
        assertFalse(old.canPaint(0));
        assertTrue(f.director.getPacket().canPaint(0));
        assertTrue(f.overlay.isHeadVisible());
        assertEquals(1, f.callbacks.size());
        f.callbacks.get(0).run();
        assertTrue(f.failures.isEmpty());
    }

    @Test
    public void currentSessionRenderFailureIsContainedAndRoutedOnce() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        SpeechPacket packet = f.director.getPacket();
        assertNull(f.overlay.render(null));
        assertFalse(packet.canPaint(0));
        assertFalse(f.overlay.isHeadVisible());
        assertTrue(f.failures.isEmpty());
        f.callbacks.get(0).run();
        assertEquals(1, f.failures.size());
    }

    @Test
    public void clippedBubbleNeverProducesFirstPaintReceiptOrAnonymousHistory() throws Exception
    {
        Fixture f = new Fixture();
        f.begin(1, 1);
        Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        Font original = graphics.getFont();
        try
        {
            graphics.setClip(new Rectangle(250, 195, 3, 3));
            f.overlay.render(graphics);
            assertEquals(original, graphics.getFont());
            assertFalse(f.director.getPacket().isPainted());
            assertFalse(f.director.getPacket().getObservation().isDrawable());
            assertEquals(0, f.guard.textCount());
            assertTrue(f.callbacks.isEmpty());
        }
        finally { graphics.dispose(); }
    }

    @Test
    public void statusRectangleAddsNoOffsetBeyondTheExactNativeTextOverlap() throws Exception
    {
        Fixture f = new Fixture();
        f.director.setLineValidator((candidate, lineId) -> "U01-01".equals(lineId));
        f.begin(1, 1);
        String playerText = "Native player text";
        Graphics2D metricsGraphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            metricsGraphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = metricsGraphics.getFontMetrics();
            Graphics2D graphics = mock(Graphics2D.class);
            when(graphics.getFontMetrics()).thenReturn(metrics);
            f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), new Rectangle(0, 0, 512, 334),
                1, playerText, 5, new Point(256, 200));
            f.overlay.render(graphics);
            assertTrue(f.director.getPacket().isPainted());
            assertEquals(1, f.callbacks.size());
            String completeText = f.director.getPacket().getText();
            verify(graphics).drawString(completeText, 256 - metrics.stringWidth(completeText) / 2,
                200 - metrics.getAscent());
        }
        finally { metricsGraphics.dispose(); }
    }

    @Test
    public void publishedOrderedNativeTextsAreCopiedBeforeProducerListMutation() throws Exception
    {
        Fixture f = new Fixture();
        f.director.setLineValidator((candidate, lineId) -> "U01-01".equals(lineId));
        f.begin(1, 1);
        Graphics2D metricsGraphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try
        {
            metricsGraphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = metricsGraphics.getFontMetrics();
            List<NativeOverheadText> nativeTexts = new ArrayList<>();
            nativeTexts.add(new NativeOverheadText(256, 200, "Local player"));
            nativeTexts.add(new NativeOverheadText(256, 200, "Other player"));
            nativeTexts.add(new NativeOverheadText(256, 200, "NPC"));
            Point head = new Point(256, 200);
            Rectangle viewport = new Rectangle(0, 0, 512, 334);
            f.overlay.publish(head, viewport, 2, nativeTexts, ModalOcclusionSnapshot.CLEAR);
            nativeTexts.clear();
            head.y = 300;
            viewport.height = 1;
            Graphics2D graphics = mock(Graphics2D.class);
            when(graphics.getFontMetrics()).thenReturn(metrics);
            f.overlay.render(graphics);
            assertTrue(f.director.getPacket().isPainted());
            String completeText = f.director.getPacket().getText();
            verify(graphics).drawString(completeText, 256 - metrics.stringWidth(completeText) / 2,
                200 - 3 * metrics.getAscent());
            assertEquals(1, f.callbacks.size());
        }
        finally { metricsGraphics.dispose(); }
    }

    @Test
    public void unknownNativeTextSnapshotCannotProduceAFirstPaint() throws Exception
    {
        for (List<NativeOverheadText> unknown : java.util.Arrays.<List<NativeOverheadText>>asList(
            null, Collections.singletonList(null)))
        {
            Fixture f = new Fixture();
            f.begin(1, 1);
            Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try
            {
                f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), 2, unknown,
                    ModalOcclusionSnapshot.CLEAR);
                f.overlay.render(graphics);
                assertFalse(f.director.getPacket().isPainted());
                assertTrue(f.callbacks.isEmpty());
                assertEquals(0, f.cooldowns.draws);
            }
            finally { graphics.dispose(); }
        }
    }

    @Test
    public void missingPlayerTextAnchorAndOverlongCopySuppressTheWholeBubble() throws Exception
    {
        for (boolean missingAnchor : new boolean[] {true, false})
        {
            Fixture f = new Fixture();
            f.begin(1, 1);
            Graphics2D graphics = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try
            {
                f.overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 1,
                    missingAnchor ? "Known text" : "x".repeat(257), 5, missingAnchor ? null : new Point(256, 200));
                f.overlay.render(graphics);
                assertFalse(f.director.getPacket().isPainted());
                assertTrue(f.callbacks.isEmpty());
            }
            finally { graphics.dispose(); }
        }
    }

    private static final class Fixture
    {
        final List<Runnable> callbacks = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();
        final SpeechGuardTest.CountingRandom cooldowns = new SpeechGuardTest.CountingRandom(15);
        final SpeechGuard guard = new SpeechGuard(() -> 0, cooldowns, 349, 52);
        final SpeechDirector director;
        final MalfoidSpeechOverlay overlay;
        Fixture() throws Exception
        {
            director = new SpeechDirector(DialogueCatalog.load(getClass()), () -> 0, new Random(1), ignored -> {}, guard);
            ClientThread clientThread = mock(ClientThread.class);
            doAnswer(invocation -> {
                callbacks.add(invocation.getArgument(0));
                return null;
            }).when(clientThread).invokeLater(any(Runnable.class));
            overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
        }
        void begin(long id, long generation)
        {
            director.tick(1, generation, 1, true, true, false);
            SceneCandidate candidate = new SceneCandidate(id, "U01", 85, 1, 9, generation, 1,
                "request:" + id, Collections.singletonMap("talkStreak", "1"), false, 4);
            assertTrue(director.offer(candidate));
            director.arrive(id, 1);
            overlay.publish(new Point(256, 200), new Rectangle(0, 0, 512, 334), null, 1);
        }
    }
}
