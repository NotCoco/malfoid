package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.world.LivingWorldData;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldSpeechDirectorTest
{
    @Test public void exactResourceProvidesItsOwnRuntimeLinesAndFiniteGuardKeys() throws Exception
    {
        DialogueCatalog base = DialogueCatalog.load(getClass());
        LivingWorldData data = LivingWorldData.load(getClass());
        DialogueCatalog catalog = base.withLivingWorld(data);
        assertEquals(base.size() + 446, catalog.size());
        assertEquals(base.getMaximumInteractionKeys() + 52, catalog.getMaximumInteractionKeys());      // H.4: eleven more creature groups, each its own key
        assertEquals("W-PLACE", catalog.repeatKey("PL-LUMBRIDGE"));
        assertEquals("W-PLACE", catalog.repeatKey(data.placeKinds.get(0).id));
        assertEquals("CR-DRAGON", catalog.repeatKey("CR-DRAGON"));
        assertEquals("W-AFTER-PVP", catalog.repeatKey("W-AFTER-PVP-HURT"));
        assertEquals("G-WAITING", catalog.repeatKey("G-WAITING-LONG"));
        for (LivingWorldData.Moment moment : data.moments)
        {
            assertTrue(catalog.isRuntimeScenarioEnabled(moment.id));
            assertEquals("MOMENT", catalog.livingWorldFamily(moment.id));
            assertEquals(moment.logicalKey, catalog.repeatKey(moment.id));
            for (LivingWorldData.Line line : moment.lines)
            {
                assertEquals(line.text, catalog.getLine(line.id).getText());
                assertEquals(line.emotion, catalog.getLine(line.id).getEmotion());
            }
        }
        for (LivingWorldData.SceneryGroup group : data.scenery)
        {
            assertTrue(catalog.isRuntimeScenarioEnabled(group.id));
            assertEquals(group.id, catalog.repeatKey(group.id));
            assertEquals(group.lines.get(0).text, catalog.getLine(group.lines.get(0).id).getText());
        }
        assertEquals(base.isRuntimeScenarioEnabled("F01"), catalog.isRuntimeScenarioEnabled("F01"));
        assertEquals(base.isRuntimeScenarioEnabled("F02"), catalog.isRuntimeScenarioEnabled("F02"));
        assertEquals(base.getLines("F01"), catalog.getLines("F01"));
        assertEquals(base.getLines("F02"), catalog.getLines("F02"));
        assertFalse(catalog.isLivingWorldScenario("F01"));
        assertFalse(catalog.isLivingWorldScenario("F02"));
    }

    @Test public void aWorldOpportunityCannotWaitBehindAnActiveOrQueuedNumberedReaction() throws Exception
    {
        Fixture f = new Fixture(0); f.at(10);
        SceneCandidate numbered = candidate(1, "M10", null, 10, 20);
        assertTrue(f.director.offer(numbered));
        assertFalse(f.director.offer(candidate(2, "PL-LUMBRIDGE", "PLACE", 10, 1)));
        assertSame(numbered, f.director.getActiveCandidate()); assertEquals(0, f.director.pendingCount());

        f.director.invalidate(true);
        Map<String, String> facts = new HashMap<>(); facts.put("settlingNotBeforeTick", "100");
        SceneCandidate queued = new SceneCandidate(3, "M10", 10, 10, 120, 1, 1, "pending:3", facts, false, 0);
        assertTrue(f.director.offer(queued)); assertNull(f.director.getActiveCandidate());
        assertFalse(f.director.offer(candidate(4, "CR-GOBLIN", "CREATURE", 10, 1)));
        assertEquals(1, f.director.pendingCount());
    }

    @Test public void worldSpacingAccruesOnlyFromSuccessfulPaintAcrossAllThreeFamilies() throws Exception
    {
        Fixture f = new Fixture(0); f.at(10);
        SceneCandidate place = candidate(1, "PL-LUMBRIDGE", "PLACE", 10, 1);
        assertTrue(f.director.offer(place)); f.director.arrive(1, 10);
        SpeechPacket unpainted = f.director.getPacket(); assertNotNull(unpainted);
        f.director.invalidate(true); assertFalse(unpainted.canPaint(f.clock.get()));
        f.at(11);
        SceneCandidate creature = candidate(2, "CR-GOBLIN", "CREATURE", 11, 1);
        assertTrue(f.director.offer(creature)); f.director.arrive(2, 11); f.paint();
        assertFalse(f.director.livingWorldSpacingReady());
        f.director.invalidate(true); f.at(110);
        assertFalse(f.director.offer(candidate(3, "PL-VARROCK", "PLACE", 110, 1)));
        assertFalse(f.director.offer(candidate(4, "SC-BOOKS", "SCENERY", 110, 20)));
        f.at(111);
        assertTrue(f.director.livingWorldSpacingReady());
        assertTrue(f.director.offer(candidate(5, "PL-VARROCK", "PLACE", 111, 1)));
    }

    @Test public void worldSelectionIsUniformOverAlternativesToOnlyTheLastSpokenGroupLine() throws Exception
    {
        Fixture f = new Fixture(0);
        String first = f.speak(1, "CR-GOBLIN", "CREATURE", 10);
        String second = f.speak(2, "CR-GOBLIN", "CREATURE", 110);
        String third = f.speak(3, "CR-GOBLIN", "CREATURE", 210);
        assertNotEquals(first, second); assertEquals(first, third);
    }

    @Test public void sceneryDrawsOnceAtArrivalAndSilentArrivalDebitsNoSpeechHistory() throws Exception
    {
        Fixture f = new Fixture(99); f.at(10);
        SceneCandidate scenery = candidate(1, "SC-BOOKS", "SCENERY", 10, 20);
        assertTrue(f.director.offer(scenery)); assertEquals(0, f.choices.draws);
        assertNull(f.director.getPacket()); assertTrue(f.guard.hasPendingReservation());
        f.director.arrive(1, 14); assertEquals(1, f.choices.draws);
        assertNull(f.director.getPacket()); assertFalse(f.guard.hasPendingReservation());
        assertEquals(0, f.guard.interactionCount()); assertEquals(0, f.guard.textCount());
        assertTrue(f.director.livingWorldSpacingReady());
        f.director.arrive(1, 14); assertEquals(1, f.choices.draws);
    }

    @Test public void everyNumberedReactionCanPreemptAnAlreadyPaintedWorldSentence() throws Exception
    {
        Fixture f = new Fixture(0); f.at(10);
        assertTrue(f.director.offer(candidate(1, "PL-LUMBRIDGE", "PLACE", 10, 1)));
        f.director.arrive(1, 10); SpeechPacket packet = f.director.getPacket(); f.paint();
        f.at(11); SceneCandidate numbered = candidate(2, "M10", null, 11, 20);
        assertTrue(f.director.offer(numbered)); assertSame(numbered, f.director.getActiveCandidate());
        assertFalse(packet.canPaint(f.clock.get()));
    }

    private static SceneCandidate candidate(long id, String scenario, String family, long tick, long lifetime)
    {
        Map<String, String> facts = new HashMap<>();
        if (family != null) { facts.put("livingWorld", family); }
        return new SceneCandidate(id, scenario, family == null ? 10 : -100, tick, tick + lifetime,
            1, 1, "test:" + id, facts, false, "SCENERY".equals(family) ? 9 : 0);
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final Draws choices;
        final SpeechGuard guard;
        final SpeechDirector director;
        Fixture(int draw) throws Exception
        {
            DialogueCatalog catalog = DialogueCatalog.load(getClass()).withLivingWorld(LivingWorldData.load(getClass()));
            choices = new Draws(draw);
            guard = new SpeechGuard(clock::get, new Random(0), catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            director = new SpeechDirector(catalog, clock::get, choices, ignored -> {}, guard);
        }
        void at(long tick)
        { clock.set(tick * 600_000_000L); director.tick(tick, 1, 1, true, true, false); }
        void paint()
        {
            SpeechPacket packet = director.getPacket(); assertNotNull(packet);
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), director.getTick(), director.getTick(), () -> {});
            assertNotNull(receipt); director.acknowledge(packet, receipt);
        }
        String speak(long id, String scenario, String family, long tick)
        {
            at(tick); assertTrue(director.offer(candidate(id, scenario, family, tick, 1)));
            director.arrive(id, tick); String line = director.getPacket().getLineId(); paint(); return line;
        }
    }
    private static final class Draws extends Random
    {
        private final int value; int draws;
        Draws(int value) { this.value = value; }
        @Override public int nextInt(int bound) { draws++; return Math.min(value, bound - 1); }
    }
}
