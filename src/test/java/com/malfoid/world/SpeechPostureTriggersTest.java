package com.malfoid.world;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechPostureTriggersTest
{
    @Test public void theQuarterDrawBelongsToOneActualIndignantFirstPaintAndEndsWithReadability() throws Exception
    {
        for (int chance : new int[]{24, 25})
        {
            Draws random = new Draws(chance); SpeechPostureTriggers triggers = new SpeechPostureTriggers(random);
            Fixture f = new Fixture("U01-S04", 2);
            triggers.onPaint(null, f.packet, 4, true, false); assertEquals(0, random.draws);
            SpeechPacket.PaintReceipt receipt = f.paint();
            assertEquals("INDIGNANT", f.packet.getEmotion());
            triggers.onPaint(receipt, f.packet, 4, true, false);
            triggers.onPaint(receipt, f.packet, 4, true, false);
            assertEquals(1, random.draws); assertEquals(chance == 24, triggers.shouldFold(10, f.clock.get(), f.candidate, true));
            assertFalse(triggers.shouldFold(30, f.clock.get() + 8_000_000_000L, f.candidate, true));
        }
        for (boolean walks : new boolean[]{false, true})
        {
            Draws random = new Draws(0); SpeechPostureTriggers triggers = new SpeechPostureTriggers(random);
            Fixture f = new Fixture("U01-S04", 2);
            triggers.onPaint(f.paint(), f.packet, 4, walks, walks);
            assertEquals(0, random.draws); assertFalse(triggers.shouldFold(10, f.clock.get(), f.candidate, true));
        }
    }

    @Test public void delayedThirdPaintAndFourthArrivalKeepTheOriginalLastCommandResetDeadline() throws Exception
    {
        SpeechPostureTriggers triggers = new SpeechPostureTriggers(new Draws(99));
        Fixture f = new Fixture("U01-T01", 3); triggers.onTalkAccepted(8);
        triggers.onPaint(f.paint(), f.packet, 4, true, true);
        assertTrue(triggers.shouldFold(57, f.clock.get(), null, true));
        assertFalse(triggers.shouldFold(58, f.clock.get(), null, true));
        triggers.onTalkAccepted(70);
        SceneCandidate fourth = new SceneCandidate(2, "U01", 85, 70, 78, 1, 1, "fourth",
            Collections.singletonMap("talkStreak", "4"), true, 5);
        triggers.onFourthAnswerArrival(fourth, 73, true);
        assertTrue(triggers.shouldFold(119, f.clock.get(), null, true));
        assertFalse(triggers.shouldFold(120, f.clock.get(), null, true));
        assertFalse(triggers.shouldFold(120, f.clock.get(), null, false));
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong(6_000_000_000L);
        final SceneCandidate candidate;
        final SpeechPacket packet;
        Fixture(String lineId, int streak) throws Exception
        {
            DialogueCatalog catalog = DialogueCatalog.load(getClass());
            SpeechGuard guard = new SpeechGuard(clock::get, new Draws(0), catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            SpeechDirector director = new SpeechDirector(catalog, clock::get, new Draws(0), ignored -> {}, guard);
            director.tick(10, 1, 1, true, true, false); director.setLineValidator((c, line) -> lineId.equals(line));
            candidate = new SceneCandidate(1, "U01", 85, 10, 18, 1, 1, "talk",
                Collections.singletonMap("talkStreak", Integer.toString(streak)), false, 4);
            assertTrue(director.offer(candidate)); director.arrive(1, 10); packet = director.getPacket(); assertNotNull(packet);
        }
        SpeechPacket.PaintReceipt paint()
        {
            AtomicLong actualDraw = new AtomicLong();
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), 10, 1, actualDraw::incrementAndGet);
            assertEquals(1, actualDraw.get()); assertNotNull(receipt); return receipt;
        }
    }
    private static final class Draws extends Random
    {
        final int chance; int draws;
        Draws(int chance) { this.chance = chance; }
        @Override public int nextInt(int bound) { draws++; return Math.min(chance, bound - 1); }
    }
}
