package com.malfoid.speech;

import com.malfoid.presence.ModalOcclusionSnapshot;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Local overhead only. Head projection and ordered native text are copied by the client thread. */
public final class MalfoidSpeechOverlay extends Overlay
{
    private final SpeechDirector director;
    private final ClientThread clientThread;
    private final Consumer<Throwable> failure;
    private volatile RenderSnapshot snapshot;

    public MalfoidSpeechOverlay(SpeechDirector director, ClientThread clientThread, Consumer<Throwable> failure)
    {
        this.director = director;
        this.clientThread = clientThread;
        this.failure = failure;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    /** Must run on the client thread; no mutable Player/Model/WorldView is retained by paint. */
    public synchronized void publish(Point headAnchor, Rectangle viewport, Rectangle playerStatusZone, long frameId)
    {
        publish(headAnchor, viewport, playerStatusZone, frameId, null, 0, null);
    }

    public synchronized void publish(Point headAnchor, Rectangle viewport, Rectangle playerStatusZone, long frameId,
        String playerOverheadText, int playerOverheadCycles, Point playerTextBaseline)
    {
        publish(headAnchor, viewport, playerStatusZone, frameId, playerOverheadText, playerOverheadCycles,
            playerTextBaseline, ModalOcclusionSnapshot.CLEAR);
    }

    public synchronized void publish(Point headAnchor, Rectangle viewport, Rectangle playerStatusZone, long frameId,
        String playerOverheadText, int playerOverheadCycles, Point playerTextBaseline, ModalOcclusionSnapshot modalOcclusion)
    {
        List<NativeOverheadText> nativeTexts = Collections.emptyList();
        if (playerOverheadCycles > 0)
        {
            nativeTexts = playerOverheadText == null || playerTextBaseline == null
                || playerOverheadText.codePointCount(0, playerOverheadText.length()) > 256 ? null
                : Collections.singletonList(new NativeOverheadText(playerTextBaseline.x, playerTextBaseline.y, playerOverheadText));
        }
        publish(headAnchor, viewport, frameId, nativeTexts, modalOcclusion);
    }

    /** Native texts are ordered local player, other players, then NPCs; Malfoid is placed last. */
    public synchronized void publish(Point headAnchor, Rectangle viewport, long frameId,
        List<NativeOverheadText> nativeTexts, ModalOcclusionSnapshot modalOcclusion)
    {
        snapshot = new RenderSnapshot(director.getPacket(), headAnchor, viewport,
            frameId, director.getTick(), nativeTexts, modalOcclusion);
    }

    public synchronized void clear()
    {
        RenderSnapshot old = snapshot;
        snapshot = null;
        if (old != null && old.packet != null)
        {
            old.packet.markUndrawable(old.frameId);
        }
    }

    public boolean isDrawable()
    {
        RenderSnapshot current = snapshot;
        SpeechPacket.PresentationObservation observation = current == null || current.packet == null
            ? null : current.packet.getObservation();
        long now = director.now();
        return observation != null && observation.isDrawable()
            && now - observation.getLastPaintNanos() <= 100_000_000L
            && now - observation.getLifetimeEndNanos() < 0;
    }

    public boolean isHeadVisible()
    {
        RenderSnapshot current = snapshot;
        return current != null && current.anchor != null && current.viewport != null
            && current.viewport.contains(current.anchor) && current.modalOcclusion.isKnown()
            && !current.modalOcclusion.intersects(new Rectangle(current.anchor.x, current.anchor.y, 1, 1));
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        RenderSnapshot attempted = snapshot;
        long attemptedGeneration = attempted == null || attempted.packet == null ? director.getSessionGeneration()
            : attempted.packet.getCandidate().getSessionGeneration();
        try
        {
            paintSafely(graphics, attempted);
        }
        catch (Throwable error)
        {
            if (attempted != null && attempted.packet != null)
            {
                attempted.packet.revoke(true);
            }
            clearIfCurrent(attempted);
            // Failure handling itself must not escape another plugin's frame.
            try
            {
                clientThread.invokeLater(() -> {
                    if (director.getSessionGeneration() != attemptedGeneration) { return; }
                    try { failure.accept(error); }
                    catch (Throwable ignored) { /* The composition root normally logs/switches off. */ }
                });
            }
            catch (Throwable ignored)
            {
                // The frame is still contained if the client has already shut down.
            }
        }
        return null;
    }

    private synchronized void clearIfCurrent(RenderSnapshot attempted)
    {
        if (snapshot == attempted) { clear(); }
    }

    private void paintSafely(Graphics2D graphics, RenderSnapshot current)
    {
        if (current == null || current.packet == null)
        {
            return;
        }
        SpeechPacket packet = current.packet;
        long now = director.now();
        if (current.anchor == null || current.viewport == null || current.viewport.width <= 0
            || current.viewport.height <= 0 || current.nativeTexts == null
            || !current.viewport.contains(current.anchor) || !current.modalOcclusion.isKnown()
            || current.modalOcclusion.intersects(new Rectangle(current.anchor.x, current.anchor.y, 1, 1))
            || !packet.canPaint(now))
        {
            packet.markUndrawable(current.frameId);
            return;
        }
        Font oldFont = graphics.getFont();
        Color oldColor = graphics.getColor();
        Composite oldComposite = graphics.getComposite();
        try
        {
            graphics.setFont(FontManager.getRunescapeBoldFont());
            FontMetrics metrics = graphics.getFontMetrics();
            OverheadLayout layout = OverheadLayout.create(packet.getText(), metrics, current.anchor,
                current.viewport, current.nativeTexts);
            if (layout == null || current.modalOcclusion.intersects(layout.bounds)
                || graphics.getClip() != null && !graphics.getClip().contains(layout.bounds))
            {
                packet.markUndrawable(current.frameId);
                return;
            }
            long paintedAt = director.now();
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, packet.alpha(paintedAt)));
            SpeechPacket.PaintReceipt receipt = packet.paint(paintedAt, current.tick, current.frameId, () -> {
                for (int index = 0; index < layout.lines.size(); index++)
                {
                    String line = layout.lines.get(index);
                    int x = layout.centerX - metrics.stringWidth(line) / 2;
                    int y = layout.firstBaselineY + index * metrics.getHeight();
                    graphics.setColor(Color.BLACK);
                    graphics.drawString(line, x + 1, y + 1);
                    graphics.setColor(Color.YELLOW);
                    graphics.drawString(line, x, y);
                }
            });
            if (receipt != null)
            {
                // invokeLater cannot execute a director mutation inside render.
                clientThread.invokeLater(() -> {
                    try { director.acknowledge(packet, receipt); }
                    catch (Throwable error) {
                        if (director.getSessionGeneration() != packet.getCandidate().getSessionGeneration()) { return; }
                        try { failure.accept(error); }
                        catch (Throwable ignored) { /* Frame/client-thread boundary is fail closed. */ }
                    }
                });
            }
        }
        finally
        {
            graphics.setFont(oldFont);
            graphics.setColor(oldColor);
            graphics.setComposite(oldComposite);
        }
    }

    private static final class RenderSnapshot
    {
        private final SpeechPacket packet;
        private final Point anchor;
        private final Rectangle viewport;
        private final long frameId;
        private final long tick;
        private final List<NativeOverheadText> nativeTexts;
        private final ModalOcclusionSnapshot modalOcclusion;

        private RenderSnapshot(SpeechPacket packet, Point anchor, Rectangle viewport,
            long frameId, long tick, List<NativeOverheadText> nativeTexts,
            ModalOcclusionSnapshot modalOcclusion)
        {
            this.packet = packet;
            this.anchor = anchor == null ? null : new Point(anchor);
            this.viewport = viewport == null ? null : new Rectangle(viewport);
            this.frameId = frameId;
            this.tick = tick;
            this.nativeTexts = nativeTexts == null ? null : Collections.unmodifiableList(new ArrayList<>(
                nativeTexts.subList(0, Math.min(nativeTexts.size(), NativeOverheadText.MAX_NATIVE_TEXTS))));
            this.modalOcclusion = modalOcclusion == null ? ModalOcclusionSnapshot.UNKNOWN : modalOcclusion;
        }
    }
}
