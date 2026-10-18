package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class E9TalkSelectionTest
{
    @Test
    public void fiftyNineFirstAnswersPaintWithoutRepeatingAndTheSixtiethAvoidsTheLast() throws Exception
    {
        Fixture f = new Fixture(0);
        Set<String> painted = new HashSet<>();
        String last = null;
        for (int index = 0; index < 59; index++)
        {
            SpeechPacket packet = f.request();
            assertNotNull(packet);
            assertTrue(packet.getLineId(), painted.add(packet.getLineId()));
            assertEquals(1, f.catalogue.getLine(packet.getLineId()).getConditions().size());
            f.paint(packet);
            last = packet.getLineId();
        }
        SpeechPacket next = f.request();
        assertNotNull(next);
        assertTrue(painted.contains(next.getLineId()));
        assertNotEquals(last, next.getLineId());
    }

    @Test
    public void oneOfFourNightDrawsSelectsNightAndRefreshDoesNotRedraw() throws Exception
    {
        int night = 0;
        for (int draw = 0; draw < 4; draw++)
        {
            Fixture f = new Fixture(draw);
            SpeechPacket packet = f.request("localNight", "true");
            assertNotNull(packet);
            assertEquals(4, f.choices.firstBound);
            if (f.catalogue.getLine(packet.getLineId()).getConditions().containsKey("localNight")) { night++; }
            int choices = f.choices.draws;
            f.director.refreshPresentation(false);
            f.director.arrive(packet.getCandidate().getId(), f.tick);
            assertSame(packet, f.director.getPacket());
            assertEquals(choices, f.choices.draws);
        }
        assertEquals(1, night);
    }

    @Test
    public void fightAndLowHpAlwaysPrecedeNightWithoutANightDraw() throws Exception
    {
        Fixture fight = new Fixture(0);
        SpeechPacket packet = fight.request("combatConfirmed", "true", "playerHpAtMostPercent", "10", "localNight", "true");
        assertTrue(fight.catalogue.getLine(packet.getLineId()).getConditions().containsKey("combatConfirmed"));
        assertEquals(10, fight.choices.firstBound);
        Fixture hurt = new Fixture(0);
        packet = hurt.request("playerHpAtMostPercent", "10", "localNight", "true");
        assertTrue(hurt.catalogue.getLine(packet.getLineId()).getConditions().containsKey("playerHpAtMostPercent"));
        assertEquals(13, hurt.choices.firstBound);
    }

    @Test
    public void guardEmptyFightOrHurtFallsOnlyToGenericAndBothEmptyStayQuiet() throws Exception
    {
        for (String group : new String[] {"combatConfirmed", "playerHpAtMostPercent"})
        {
            Fixture f = new Fixture(0);
            f.director.setLineValidator((candidate, lineId) -> !f.catalogue.getLine(lineId).getConditions().containsKey(group));
            SpeechPacket packet = f.request("combatConfirmed", "combatConfirmed".equals(group) ? "true" : "false",
                "playerHpAtMostPercent", "10", "localNight", "true");
            assertNotNull(packet);
            assertEquals(1, f.catalogue.getLine(packet.getLineId()).getConditions().size());
            assertEquals(59, f.choices.firstBound);
            f.director.setLineValidator((candidate, lineId) -> false);
            assertNull(f.request("combatConfirmed", "combatConfirmed".equals(group) ? "true" : "false",
                "playerHpAtMostPercent", "10", "localNight", "true"));
            assertTrue(f.director.isIntentionalSilent());
        }
    }

    @Test
    public void selectedNightWithNoEligibleNightFallsToGenericWithoutAnotherProbabilityDraw() throws Exception
    {
        Fixture f = new Fixture(0);
        f.director.setLineValidator((candidate, lineId) -> !f.catalogue.getLine(lineId).getConditions().containsKey("localNight"));
        SpeechPacket packet = f.request("localNight", "true");
        assertNotNull(packet);
        assertEquals(4, f.choices.firstBound);
        assertEquals(1, f.catalogue.getLine(packet.getLineId()).getConditions().size());
        assertEquals(59, f.choices.draws); // one night draw, then 58 draws to shuffle the generic bag
    }

    private static final class Fixture
    {
        final AtomicLong clock = new AtomicLong();
        final DialogueCatalog catalogue;
        final FirstDraw choices;
        final SpeechDirector director;
        long tick;
        long id;
        Fixture(int draw) throws Exception
        {
            catalogue = DialogueCatalog.load(getClass());
            choices = new FirstDraw(draw);
            SpeechGuard guard = new SpeechGuard(clock::get, new Random(0), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
            director = new SpeechDirector(catalogue, clock::get, choices, new Random(0), ignored -> {}, guard);
        }
        SpeechPacket request(String... pairs)
        {
            director.invalidate(true);
            clock.addAndGet(61_000_000_000L);
            tick += 21;
            director.tick(tick, 1, 1, true, true, false);
            Map<String, String> facts = new HashMap<>();
            facts.put("talkStreak", "1");
            for (int index = 0; index < pairs.length; index += 2) { facts.put(pairs[index], pairs[index + 1]); }
            SceneCandidate candidate = new SceneCandidate(++id, "U01", 85, tick, tick + 8, 1, 1, "talk:" + id, facts, false, 4);
            assertTrue(director.offer(candidate));
            director.arrive(id, tick);
            return director.getPacket();
        }
        void paint(SpeechPacket packet)
        {
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), tick, id, () -> {});
            assertNotNull(receipt);
            director.acknowledge(packet, receipt);
        }
    }

    private static final class FirstDraw extends Random
    {
        final int first;
        int firstBound;
        int draws;
        FirstDraw(int first) { this.first = first; }
        @Override public int nextInt(int bound)
        {
            draws++;
            if (draws == 1) { firstBound = bound; return first; }
            return bound - 1;
        }
    }
}
