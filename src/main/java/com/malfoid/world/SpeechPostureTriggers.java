package com.malfoid.world;

import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.Random;

/** G.3 observes genuine first paints and the authored silent fourth-answer arrival. */
public final class SpeechPostureTriggers
{
    private final Random random;
    private long lastPaintToken = -1, lastTalkAccepted = -1, talkUntil = -1, holdUntil = -1;
    private long heldCandidate = -1;
    private boolean talkFolded;
    private SpeechPacket readablePacket;
    private String gesture;
    private long gestureUntil = -1;

    /** H.6: an emote of her own asked for by a line that has just been painted. */
    public static final class GestureRequest
    {
        public final String name;
        public final long untilTick;
        GestureRequest(String name, long untilTick) { this.name = name; this.untilTick = untilTick; }
    }

    public SpeechPostureTriggers(Random random) { this.random = random; }

    public void onTalkAccepted(long tick)
    {
        if (lastTalkAccepted >= 0 && tick - lastTalkAccepted >= 50) { talkFolded = false; }
        lastTalkAccepted = tick;
        if (talkFolded) { talkUntil = tick + 50; }
    }

    public void onFourthAnswerArrival(SceneCandidate candidate, long tick, boolean allowed)
    {
        if (allowed && "U01".equals(candidate.getScenarioId())
            && "4".equals(candidate.getConditionFacts().get("talkStreak")))
        {
            talkFolded = true; talkUntil = (lastTalkAccepted >= 0 ? lastTalkAccepted : tick) + 50;
        }
    }

    public void onPaint(SpeechPacket.PaintReceipt receipt, SpeechPacket packet, int holdTicks,
        boolean allowed, boolean sceneWalks)
    {
        onPaint(receipt, packet, holdTicks, allowed, sceneWalks, false);
    }

    /** `gestures`: her model has the emotes of her own (H.6). Without them every draw is as it was under Addendum G. */
    public void onPaint(SpeechPacket.PaintReceipt receipt, SpeechPacket packet, int holdTicks,
        boolean allowed, boolean sceneWalks, boolean gestures)
    {
        if (receipt == null || packet == null || receipt != packet.getFirstPaint()
            || receipt.getAdmissionToken() == lastPaintToken) { return; }
        lastPaintToken = receipt.getAdmissionToken();
        String scenario = receipt.getCandidate().getScenarioId();
        if (!allowed || "M01".equals(scenario) || "M02".equals(scenario)) { return; }
        long tick = receipt.getPaintedAtTick();
        if ("M09".equals(scenario) && ("Hmph.".equals(packet.getText()) || "...Hmph.".equals(packet.getText())))
        {
            heldCandidate = receipt.getCandidate().getId(); holdUntil = tick + holdTicks; readablePacket = null;
        }
        else if ("U01".equals(scenario) && "3".equals(receipt.getCandidate().getConditionFacts().get("talkStreak")))
        {
            talkFolded = true; talkUntil = (lastTalkAccepted >= 0 ? lastTalkAccepted : tick) + 50;
        }
        else if ("U01-G28".equals(receipt.getLineId()))
        {
            heldCandidate = receipt.getCandidate().getId(); holdUntil = tick + 4; readablePacket = null;
        }
        else if (!sceneWalks && "INDIGNANT".equals(packet.getEmotion()))
        {
            // One draw, as before: a quarter fold her arms (G.3). With the emotes of her own, a fifth more put her
            // hands on her hips for as long as the line holds, and an exclamation may be stamped.
            int draw = random.nextInt(100);
            if (draw < 25) { readablePacket = packet; heldCandidate = -1; holdUntil = -1; }
            else if (gestures && draw < 45) { gesture = "HANDS_ON_HIPS"; gestureUntil = tick + Math.max(4, holdTicks); }
            else if (gestures && draw < 60 && packet.getText() != null && packet.getText().endsWith("!"))
            {
                gesture = "STOMP"; gestureUntil = tick;
            }
        }
        else if (gestures && !sceneWalks && "TEASING".equals(packet.getEmotion()) && random.nextInt(100) < 22)
        {
            gesture = "HAIR_FLICK"; gestureUntil = tick;
        }
        else if (gestures && !sceneWalks && "CURIOUS".equals(packet.getEmotion()) && random.nextInt(100) < 25)
        {
            gesture = "PEER"; gestureUntil = tick + Math.max(4, holdTicks);
        }
    }

    /** The emote the last painted line asked for, once. */
    public GestureRequest consumeGesture()
    {
        if (gesture == null) { return null; }
        GestureRequest request = new GestureRequest(gesture, gestureUntil);
        gesture = null; gestureUntil = -1;
        return request;
    }

    public boolean shouldFold(long tick, long now, SceneCandidate active, boolean allowed)
    {
        if (!allowed || active != null && ("M01".equals(active.getScenarioId()) || "M02".equals(active.getScenarioId())))
        {
            clearPosture(); return false;
        }
        if (talkFolded && tick >= talkUntil) { talkFolded = false; }
        if (readablePacket != null && (!readablePacket.isPainted() || !readablePacket.canPaint(now)))
        {
            readablePacket = null;
        }
        return talkFolded || active != null && active.getId() == heldCandidate && tick < holdUntil
            || readablePacket != null && active != null && readablePacket.getCandidate().getId() == active.getId();
    }

    private void clearPosture()
    {
        gesture = null; gestureUntil = -1;
        talkFolded = false; talkUntil = holdUntil = -1; heldCandidate = -1; readablePacket = null;
    }

    public void resetSession()
    {
        clearPosture(); lastPaintToken = lastTalkAccepted = -1;
    }
}
