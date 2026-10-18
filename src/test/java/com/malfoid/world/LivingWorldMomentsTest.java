package com.malfoid.world;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldMomentsTest
{
    @Test public void fiftySampledOutsideTicksQualifyOnceAndSkippedTicksDoNotInventAnEdge() throws Exception
    {
        Fixture f = new Fixture(0);
        for (int tick = 1; tick <= 50; tick++) { assertNull(f.sample(tick, false, true)); }
        for (int tick = 51; tick <= 100; tick++)
        {
            assertNull(f.moments.observe(tick, 1, 1, false, -1, false, true, false, true, 80, 100, true));
        }
        SceneCandidate entered = f.sample(101, true, true);
        assertNotNull(entered); assertEquals("W-WILD-ENTER", entered.getScenarioId());
        assertEquals(102, entered.getExpiresTick()); assertEquals(0, entered.getHoldTicks());
        assertTrue(f.moments.matches(entered, 101)); assertFalse(f.moments.matches(entered, 102));
        assertNull(f.sample(102, true, true)); assertEquals(1, f.random.draws);
    }

    @Test public void anIneligibleOrSilentCrossingCannotWaitOrDrawAgain() throws Exception
    {
        for (int chance : new int[]{0, 99})
        {
            Fixture f = new Fixture(chance);
            for (int tick = 1; tick <= 50; tick++) { f.sample(tick, false, true); }
            assertNull(f.sample(51, true, chance != 0));
            for (int tick = 52; tick <= 70; tick++) { assertNull(f.sample(tick, true, true)); }
            assertEquals(chance == 0 ? 0 : 1, f.random.draws);
        }
    }

    @Test public void leavingNeedsTwoHundredInsideSamplesAndAnIntervalWithoutLocalDeath() throws Exception
    {
        for (int length : new int[]{60, 200})
        {
            for (boolean died : new boolean[]{false, true})
            {
                Fixture f = new Fixture(0);
                for (int tick = 1; tick <= length; tick++) { f.sample(tick, true, true); }
                if (died) { f.moments.onLocalDeath(); }
                SceneCandidate leave = f.sample(length + 1, false, true);
                assertEquals(length == 200 && !died, leave != null);
                if (leave != null) { assertEquals("W-WILD-LEAVE", leave.getScenarioId()); }
            }
        }
    }

    @Test public void returnUsesOnlyPresenceTimingAndTheExactLocalHpRatio() throws Exception
    {
        for (int hp : new int[]{40, 50, 51, 80})
        {
            Fixture f = new Fixture(0);
            f.moments.observe(1, 1, 1, false, -1, true, true, false, false, hp, 101, false);
            for (int tick = 2; tick < 12; tick++)
            {
                assertNull(f.moments.observe(tick, 1, 1, true, 2, false, true, false, false, hp, 101, true));
            }
            SceneCandidate returned = f.moments.observe(12, 1, 1, true, 2, false, true, false, false, hp, 101, true);
            assertNotNull(returned); assertEquals(hp <= 50 ? "W-AFTER-PVP-HURT" : "W-AFTER-PVP", returned.getScenarioId());
            assertNull(f.moments.observe(13, 1, 1, true, 2, false, true, false, false, hp, 101, true));
            assertEquals(1, f.random.draws);
        }
        Fixture died = new Fixture(0);
        died.moments.observe(1, 1, 1, false, -1, true, true, false, false, 80, 100, false);
        died.moments.onLocalDeath();
        for (int tick = 2; tick <= 20; tick++)
        {
            assertNull(died.moments.observe(tick, 1, 1, true, 2, false, true, false, false, 80, 100, true));
        }
        assertEquals(0, died.random.draws);
    }

    @Test public void onlyRealFirstPaintStartsWildernessAndSharedAfterCombatPoolSpacing() throws Exception
    {
        Fixture f = new Fixture(0);
        for (int tick = 1; tick <= 50; tick++) { f.sample(tick, false, true); }
        SceneCandidate first = f.sample(51, true, true);
        assertNotNull(first);
        for (int tick = 52; tick <= 102; tick++) { f.sample(tick, false, true); }
        SceneCandidate unpaintedRetry = f.sample(103, true, true);
        assertNotNull("Offering without a real draw starts no 1,500-tick spacing", unpaintedRetry);
        f.paint(unpaintedRetry);
        for (int tick = 104; tick <= 154; tick++) { f.sample(tick, false, true); }
        assertNull(f.sample(155, true, true));

        Fixture after = new Fixture(0);
        after.moments.observe(1, 1, 1, false, -1, true, true, false, false, 80, 100, false);
        SceneCandidate normal = after.moments.observe(12, 1, 1, true, 2, false, true, false, false, 80, 100, true);
        after.paint(normal);
        after.moments.observe(13, 1, 1, false, -1, true, true, false, false, 40, 100, false);
        assertNull(after.moments.observe(25, 1, 1, true, 15, false, true, false, false, 40, 100, true));
        assertEquals("The hurt alias shares normal's painted cooldown", 1, after.random.draws);
    }

    @Test public void waitingUsesTheThirdActualEmotePoolAndSessionResetClearsOnlySessionMemory() throws Exception
    {
        Fixture f = new Fixture(0);
        assertEquals("G-WAITING", f.moments.offerWaiting(10, 1, 1, 2, true).getScenarioId());
        assertEquals("G-WAITING-LONG", f.moments.offerWaiting(11, 1, 1, 3, true).getScenarioId());
        assertNull(f.moments.offerWaiting(12, 1, 1, 3, false));
        assertEquals(2, f.random.draws);
    }

    /** H.7: stopped short of a beast he stands by. */
    @Test public void beingKeptBackIsOfferedOnceFromItsFourthTickAndWaitsForItsTurn() throws Exception
    {
        Fixture f = new Fixture(0);
        for (int tick = 1; tick <= 3; tick++) { assertNull(f.moments.observeKeptBack(tick, 1, 1, true, true)); }
        SceneCandidate said = f.moments.observeKeptBack(4, 1, 1, true, true);
        assertNotNull(said); assertEquals("W-KEPT-BACK", said.getScenarioId());
        assertEquals("KEPT_BACK_BY_BEAST", said.getConditionFacts().get("momentFact"));
        assertEquals(5, said.getExpiresTick()); assertEquals(0, said.getHoldTicks());
        assertTrue(f.moments.matches(said, 4)); assertFalse(f.moments.matches(said, 5));
        for (int tick = 5; tick <= 40; tick++)
        {
            assertNull("Once for each time it happens", f.moments.observeKeptBack(tick, 1, 1, true, true));
        }
        assertEquals(1, f.random.draws);

        // Something else is still being said on its fourth tick: it is still true afterwards, so it waits.
        Fixture waiting = new Fixture(0);
        for (int tick = 1; tick <= 9; tick++) { assertNull(waiting.moments.observeKeptBack(tick, 1, 1, true, false)); }
        assertEquals(0, waiting.random.draws);
        assertNotNull(waiting.moments.observeKeptBack(10, 1, 1, true, true));
        assertNull(waiting.moments.observeKeptBack(11, 1, 1, true, true));

        // She moved before it could be said: nothing is owed, and four ticks are counted again.
        Fixture moved = new Fixture(0);
        for (int tick = 1; tick <= 6; tick++) { assertNull(moved.moments.observeKeptBack(tick, 1, 1, true, false)); }
        assertNull(moved.moments.observeKeptBack(7, 1, 1, false, true));
        for (int tick = 8; tick <= 10; tick++) { assertNull(moved.moments.observeKeptBack(tick, 1, 1, true, true)); }
        assertNotNull(moved.moments.observeKeptBack(11, 1, 1, true, true));
    }

    @Test public void beingKeptBackIsSaidSixTimesInTenAndNotAgainForFiveHundredTicks() throws Exception
    {
        Fixture silent = new Fixture(60);
        for (int tick = 1; tick <= 20; tick++) { assertNull(silent.moments.observeKeptBack(tick, 1, 1, true, true)); }
        assertEquals("One draw for each time it happens", 1, silent.random.draws);

        Fixture f = new Fixture(59);
        for (int tick = 1; tick <= 3; tick++) { f.moments.observeKeptBack(tick, 1, 1, true, true); }
        SceneCandidate first = f.moments.observeKeptBack(4, 1, 1, true, true); f.paint(first);
        // He steps away and back: it has only just been said.
        assertNull(f.moments.observeKeptBack(5, 1, 1, false, true));
        for (int tick = 6; tick <= 20; tick++) { assertNull(f.moments.observeKeptBack(tick, 1, 1, true, true)); }
        assertNull(f.moments.observeKeptBack(499, 1, 1, false, true));
        for (int tick = 500; tick <= 503; tick++) { assertNull(f.moments.observeKeptBack(tick, 1, 1, true, true)); }
        assertNull(f.moments.observeKeptBack(600, 1, 1, false, true));
        for (int tick = 601; tick <= 603; tick++) { assertNull(f.moments.observeKeptBack(tick, 1, 1, true, true)); }
        SceneCandidate second = f.moments.observeKeptBack(604, 1, 1, true, true);
        assertNotNull("Five hundred ticks after the last one was painted", second);
        assertEquals("W-KEPT-BACK", second.getScenarioId());
    }

    private static final class Fixture
    {
        final LivingWorldData data = load();
        final Draws random;
        final AtomicLong ids = new AtomicLong(1000);
        final LivingWorldMoments moments;
        Fixture(int draw) { random = new Draws(draw); moments = new LivingWorldMoments(data, random, ids::incrementAndGet); }
        SceneCandidate sample(long tick, boolean inside, boolean eligible)
        { return moments.observe(tick, 1, 1, true, 0, false, true, true, inside, 80, 100, eligible); }
        void paint(SceneCandidate candidate) throws Exception
        {
            assertNotNull(candidate);
            AtomicLong clock = new AtomicLong(candidate.getCreatedTick() * 600_000_000L);
            DialogueCatalog catalog = DialogueCatalog.load(getClass()).withLivingWorld(data);
            SpeechGuard guard = new SpeechGuard(clock::get, new Draws(0), catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            SpeechDirector director = new SpeechDirector(catalog, clock::get, new Draws(0), ignored -> {}, guard);
            director.tick(candidate.getCreatedTick(), 1, 1, true, true, false);
            director.setFirstPaintListener(moments::onPaint);
            assertTrue(director.offer(candidate)); director.arrive(candidate.getId(), candidate.getCreatedTick());
            SpeechPacket packet = director.getPacket(); assertNotNull(packet);
            AtomicLong draws = new AtomicLong();
            SpeechPacket.PaintReceipt receipt = packet.paint(clock.get(), candidate.getCreatedTick(), 1, draws::incrementAndGet);
            assertEquals(1, draws.get()); assertNotNull(receipt); director.acknowledge(packet, receipt);
        }
        private static LivingWorldData load()
        { try { return LivingWorldData.load(LivingWorldMomentsTest.class); } catch (Exception error) { throw new AssertionError(error); } }
    }
    private static final class Draws extends Random
    {
        final int value; int draws;
        Draws(int value) { this.value = value; }
        @Override public int nextInt(int bound) { draws++; return Math.min(value, bound - 1); }
    }
}
