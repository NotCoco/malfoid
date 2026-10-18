package com.malfoid.motion;

import com.malfoid.motion.CreatureAwareness.Behaviour;
import com.malfoid.motion.CreatureAwareness.Classification;
import com.malfoid.motion.CreatureAwareness.Creature;
import com.malfoid.motion.CreatureAwareness.Sample;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CreatureAwarenessTest
{
    @Test public void namesUseExactlyTheAuthoredTagStrippedPredicates()
    {
        for (String name : new String[]{"Rat", "<col=00ff00>Giant rat</col>", "Scurrius-rat"})
            assertEquals("F01", CreatureAwareness.classifyFear(CreatureAwareness.normalizeName(name), 0).groupId);
        for (String name : new String[]{"wolf", "werewolf", "white wolf", "bear", "grizzly bear", "hellhound", "giant spider", "spider",
            "scorpion", "king scorpion", "snake", "swamp snake", "crocodile", "desert crocodile", "bat", "giant bat",
            "jackal", "guard dog", "wild dog", "terror dog", "shadow hound", "jaguar", "spined larupia", "horned graahk",
            "sabre-toothed kyatt"})
            assertEquals(name, "F02", CreatureAwareness.classifyFear(name, 1).groupId);
        for (String name : new String[]{"ratcatcher", "brat", "rats", "wolf cub", "bear cub", "spiders", "hellhounds",
            "scorpions", "snakes", "crocodiles", "bats", "batman", "snake charmer", "bat (pet)", "jaguar warrior", "stray dog", "dog"})
            assertNull(name, CreatureAwareness.classifyFear(name, 30));
    }

    @Test public void nearSixIsInclusiveAndNearestRatOrBeastWinsDespiteTablePriority()
    {
        CreatureAwareness a = new CreatureAwareness();
        Creature wolf = creature("wolf", "wolf", 0, 1), rat = creature("rat", "rat", 6, 0);
        a.commit(sample(1, true, wolf, rat), 0, 0);
        assertEquals("F02", a.getGroupId()); assertEquals("wolf", a.getTarget().key); assertEquals(1, a.getBeginTick());
        assertEquals(Behaviour.AFRAID, a.getTarget().classification.behaviour);
        a.commit(sample(2, true, creature("wolf", "wolf", 5, 0), creature("rat", "rat", 1, 0)), 0, 0);
        assertEquals("F01", a.getGroupId()); assertEquals(1, a.getBeginTick());
        assertEquals("The original nearest creature selects the one speech key", "F02", a.getBeginCreature().classification.groupId);
        Creature outside = creature("far", "rat", 7, 0);
        CreatureAwareness b = new CreatureAwareness(); b.commit(sample(1, true, outside), 0, 0); assertNull(b.getGroupId());
    }

    @Test public void changingFearKindOrReturningBeforeFiveKnownCalmTicksKeepsTheEpisode()
    {
        CreatureAwareness a = new CreatureAwareness(); a.commit(sample(1, true, creature("rat", "rat", 2, 0)), 0, 0);
        for (int tick = 2; tick <= 5; tick++) { a.commit(sample(tick, true), 0, 0); }
        a.commit(sample(6, true, creature("bear", "bear", 6, 0)), 0, 0);
        assertEquals("F02", a.getGroupId()); assertEquals(1, a.getBeginTick()); assertEquals("rat", a.getBeginCreature().key);
        a.commit(sample(7, true, creature("bear", "bear", 8, 0)), 0, 0); assertEquals(0, a.getQuietTicks());
        for (int tick = 8; tick <= 12; tick++) { a.commit(sample(tick, true), 0, 0); }
        assertFalse(a.isKeepingAway());
        a.commit(sample(13, true, creature("bear", "bear", 1, 0)), 0, 0);
        assertEquals(13, a.getBeginTick()); assertEquals("F02", a.getBeginCreature().classification.groupId);
    }

    @Test public void eightTileRetentionAndFiveKnownQuietTicksSuppressLowerGroups()
    {
        CreatureAwareness a = new CreatureAwareness(); a.commit(sample(1, true, creature("rat", "rat", 6, 0)), 0, 0);
        a.commit(sample(2, true, creature("rat", "rat", 8, 0)), 0, 0); assertTrue(a.isKeepingAway());
        Creature cat = new Creature("cat", new Classification("CR-FOND", 5, Behaviour.FOND, 0, 0), "cat", 0, 1, 0, 0);
        for (int tick = 3; tick <= 6; tick++) { a.commit(sample(tick, true, cat), 0, 0); assertTrue(a.isKeepingAway()); }
        a.commit(sample(7, true, cat), 0, 0); assertFalse(a.isKeepingAway()); assertTrue(a.didEndKeepingAway());
        assertEquals("CR-FOND", a.getGroupId());
    }

    @Test public void unknownAndMissingCommitNeverProveTheEnding()
    {
        CreatureAwareness a = new CreatureAwareness(); a.commit(sample(1, true, creature("rat", "rat", 2, 0)), 0, 0);
        a.commit(sample(2, true), 0, 0); a.commit(sample(3, true), 0, 0); a.commit(sample(4, false), 0, 0);
        assertTrue(a.isKeepingAway()); assertEquals(0, a.getQuietTicks());
        a.commit(sample(7, true), 0, 0); assertTrue(a.isKeepingAway()); assertEquals(1, a.getQuietTicks());
        for (int tick = 8; tick <= 10; tick++) { a.commit(sample(tick, true), 0, 0); assertTrue(a.isKeepingAway()); }
        a.commit(sample(11, true), 0, 0); assertFalse(a.isKeepingAway()); assertTrue(a.didEndKeepingAway());
    }

    @Test public void dragonBandAndHigherPriorityRatUseTheSameState()
    {
        Classification dragon = new Classification("CR-DRAGON", 1, Behaviour.AFRAID, 2, 3);
        CreatureAwareness a = new CreatureAwareness();
        a.commit(sample(1, true, new Creature("dragon", dragon, "green dragon", 79, 4, 0, 0)), 0, 0);
        assertEquals(2, a.getTarget().classification.minPlayerDistance); assertEquals(3, a.getTarget().classification.maxPlayerDistance);
        a.commit(sample(2, true, creature("rat", "rat", 5, 0)), 0, 0);
        assertEquals("F01", a.getGroupId()); assertEquals(2, a.getBeginTick());
        a.commit(sample(3, true, creature("other-rat", "rat", 1, 0)), 0, 0);
        assertEquals("Changing nearest rat does not begin a new kind", 2, a.getBeginTick());
    }

    private static Creature creature(String key, String name, int x, int y)
    { return new Creature(key, CreatureAwareness.classifyFear(name, 30), name, 30, x, y, 0); }
    private static Sample sample(long tick, boolean known, Creature... creatures)
    { return new Sample(tick, 0, 0, 0, 0, known, Arrays.asList(creatures)); }
}
