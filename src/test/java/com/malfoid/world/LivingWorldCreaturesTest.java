package com.malfoid.world;

import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldCreaturesTest
{
    private final LivingWorldData data = load();

    @Test public void aRatOutranksADragonAndCatAndKeepsItsUnreviewedSpeechSilent()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        assertNull(observer.observe(10, 1, 1, true, true, true,
            Arrays.asList(sight("cat:1", "CR-FOND", 1), sight("dragon:1", "CR-DRAGON", 2), sight("rat:1", "F01", 6)),
            "F01", 10, "cat:1", 10));
        assertEquals("F01", observer.getCurrent().group.id); assertEquals(0, random.draws);
        SceneCandidate dragon = observer.observe(11, 1, 1, true, true, true,
            Arrays.asList(sight("cat:1", "CR-FOND", 1), sight("dragon:1", "CR-DRAGON", 2)),
            "CR-DRAGON", 11, null, -1);
        assertNotNull(dragon); assertEquals("CR-DRAGON", dragon.getScenarioId());
        assertEquals(12, dragon.getExpiresTick()); assertEquals(0, dragon.getHoldTicks());
        assertEquals(1, random.draws);
        assertNull(observer.observe(12, 1, 1, true, true, true,
            Collections.singletonList(sight("dragon:1", "CR-DRAGON", 2)), "CR-DRAGON", 11, null, -1));
        assertEquals(1, random.draws);
    }

    @Test public void aSilentOrBlockedFirstSightHasNoRetryWhileTheCreatureStays()
    {
        Draws random = new Draws(99); LivingWorldCreatures observer = observer(random);
        assertNull(observe(observer, 10, true, true, sight("frog:1", "CR-DISGUST", 4)));
        for (int tick = 11; tick <= 350; tick++)
        {
            assertNull(observe(observer, tick, true, true, sight("frog:1", "CR-DISGUST", 4)));
        }
        assertEquals(1, random.draws);
        observe(observer, 351, true, true, null);
        assertNull(observe(observer, 352, true, true, sight("frog:1", "CR-DISGUST", 4)));
        assertEquals(2, random.draws);

        LivingWorldCreatures blocked = observer(new Draws(0));
        assertNull(observe(blocked, 10, true, false, sight("goblin:1", "CR-GOBLIN", 3)));
        assertNull(observe(blocked, 11, true, true, sight("goblin:1", "CR-GOBLIN", 3)));
    }

    @Test public void wizardIdentitySurvivesScopeLossAndResetsOnlyForANewSession()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        assertNotNull(observe(observer, 10, true, true, sight("wizard:1", "CR-WIZARD", 3)));
        assertEquals("wizard:1", observer.getWizardLook().key);
        assertNull(observe(observer, 11, true, true, sight("wizard:1", "CR-WIZARD", 3)));
        assertNull(observer.getWizardLook());
        observer.resetObservation();
        assertNull(observe(observer, 600, true, true, sight("wizard:1", "CR-WIZARD", 3)));
        assertNotNull(observe(observer, 601, true, true, sight("wizard:2", "CR-WIZARD", 3)));
        observer.resetSession();
        assertNotNull(observe(observer, 602, true, true, sight("wizard:1", "CR-WIZARD", 3)));
        assertEquals(3, random.draws);
    }

    @Test public void fondSpeechRequiresAMatchingActualArrivalAndCannotUseAnotherGroupsArrival()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        LivingWorldCreatures.Sighting cat = sight("cat:1", "CR-FOND", 1);
        assertNull(observe(observer, 10, true, true, cat));
        SceneCandidate arrival = observer.observe(11, 1, 1, true, true, true,
            Collections.singletonList(cat), null, -1, "cat:1", 11);
        assertNotNull(arrival); assertEquals("CR-FOND", arrival.getScenarioId());
        assertNull(observer.observe(12, 1, 1, true, true, true,
            Collections.singletonList(cat), null, -1, "cat:1", 12));
        assertNull(observer.observe(13, 1, 1, true, true, true,
            Arrays.asList(cat, sight("rat:1", "F01", 3)), "F01", 13, "cat:1", 13));
        assertEquals(1, random.draws);
    }

    @Test public void unknownAndOutsideRadiusCopiesCannotCauseSpeech()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        assertNull(observe(observer, 10, false, true, sight("goblin:1", "CR-GOBLIN", 3)));
        assertNull(observe(observer, 11, true, true, sight("goblin:1", "CR-GOBLIN", 7)));
        assertEquals(0, random.draws);
    }

    @Test public void unknownDoesNotInventAnEndingOrRetryAnAlreadyConsumedSafetyOnset()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        LivingWorldCreatures.Sighting dragon = sight("dragon:1", "CR-DRAGON", 3);
        assertNotNull(observer.observe(10, 1, 1, true, true, true,
            Collections.singletonList(dragon), "CR-DRAGON", 10, null, -1));
        for (int tick = 11; tick < 120; tick++) { observe(observer, tick, false, false, null); }
        assertNull(observer.observe(120, 1, 1, true, true, true,
            Collections.singletonList(dragon), "CR-DRAGON", 10, null, -1));
        assertEquals(1, random.draws);

        Draws quiet = new Draws(99); LivingWorldCreatures disgust = observer(quiet);
        observe(disgust, 10, true, true, sight("frog:1", "CR-DISGUST", 3));
        for (int tick = 11; tick < 312; tick++) { observe(disgust, tick, false, false, null); }
        assertNull(observe(disgust, 312, true, true, sight("frog:1", "CR-DISGUST", 3)));
        assertEquals(1, quiet.draws);
        observe(disgust, 313, true, true, null);
        observe(disgust, 314, true, true, sight("frog:1", "CR-DISGUST", 3));
        assertEquals(2, quiet.draws);
    }

    @Test public void aFullWizardLedgerSuppressesNewIdentitiesWithoutEvictingSeenOnesUntilSessionReset()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        long tick = 10;
        for (int i = 0; i < LivingWorldCreatures.MAX_WIZARD_IDENTITIES; i++, tick += 500)
        {
            assertNotNull(observe(observer, tick, true, true, sight("wizard:" + i, "CR-WIZARD", 3)));
            assertNotNull(observer.getWizardLook());
        }
        assertEquals(256, observer.getWizardIdentityCount());
        assertNull(observe(observer, tick, true, true, sight("wizard:overflow", "CR-WIZARD", 3)));
        assertNull(observer.getWizardLook()); assertEquals(256, observer.getWizardIdentityCount());
        assertNull(observe(observer, tick + 500, true, true, sight("wizard:0", "CR-WIZARD", 3)));
        assertNull(observer.getWizardLook()); assertEquals(256, random.draws);
        observer.resetSession(); assertEquals(0, observer.getWizardIdentityCount());
        assertNotNull(observe(observer, tick + 1000, true, true, sight("wizard:overflow", "CR-WIZARD", 3)));
        assertEquals(1, observer.getWizardIdentityCount());
    }

    @Test public void hisPetNeedsTwentyKnownTicksAndAnAppearanceHasOnlyOneAttempt()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        LivingWorldCreatures.Sighting pet = sight("pet:1", "CR-PET", 3);
        for (int tick = 1; tick < 20; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
        assertNotNull(observe(observer, 20, true, true, pet));
        assertSame(pet, observer.getWizardLook()); assertEquals(0, observer.getWizardIdentityCount());
        for (int tick = 21; tick <= 2000; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
        assertEquals(1, random.draws);
        observe(observer, 2001, true, true, null);
        for (int tick = 2002; tick < 2021; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
        assertNotNull(observe(observer, 2021, true, true, pet)); assertEquals(2, random.draws);
    }

    @Test public void aBlockedOrHigherPriorityTwentiethPetTickHasNoDeferredRetry()
    {
        for (boolean higherGroup : new boolean[]{false, true})
        {
            Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
            LivingWorldCreatures.Sighting pet = sight("pet:1", "CR-PET", 3);
            for (int tick = 1; tick < 20; tick++) { observe(observer, tick, true, true, pet); }
            if (higherGroup)
            {
                assertNull(observer.observe(20, 1, 1, true, true, true,
                    Arrays.asList(pet, sight("rat:1", "F01", 4)), "F01", 20, null, -1));
            }
            else { assertNull(observe(observer, 20, true, false, pet)); }
            for (int tick = 21; tick <= 50; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
            assertEquals(0, random.draws);
        }
    }

    @Test public void unknownRestartsPetDwellWithoutInventingAFreshConsumedAppearance()
    {
        Draws random = new Draws(0); LivingWorldCreatures observer = observer(random);
        LivingWorldCreatures.Sighting pet = sight("pet:1", "CR-PET", 3);
        for (int tick = 1; tick <= 10; tick++) { observe(observer, tick, true, true, pet); }
        observe(observer, 11, false, false, null);
        for (int tick = 12; tick < 31; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
        assertNotNull(observe(observer, 31, true, true, pet));
        observe(observer, 32, false, false, null);
        for (int tick = 33; tick <= 60; tick++) { assertNull(observe(observer, tick, true, true, pet)); }
        assertEquals(1, random.draws);
    }

    private SceneCandidate observe(LivingWorldCreatures observer, long tick, boolean known, boolean eligible,
        LivingWorldCreatures.Sighting sighting)
    {
        return observer.observe(tick, 1, 1, known, eligible, eligible,
            sighting == null ? Collections.emptyList() : Collections.singletonList(sighting), null, -1, null, -1);
    }
    private LivingWorldCreatures observer(Random random)
    { AtomicLong ids = new AtomicLong(1000); return new LivingWorldCreatures(random, ids::incrementAndGet); }
    private LivingWorldCreatures.Sighting sight(String key, String group, int distance)
    { return new LivingWorldCreatures.Sighting(key, (LivingWorldData.CreatureGroup) data.group(group), 3250, 3250, 0, distance); }
    private static LivingWorldData load()
    { try { return LivingWorldData.load(LivingWorldCreaturesTest.class); } catch (Exception failure) { throw new AssertionError(failure); } }
    private static final class Draws extends Random
    {
        private final int value; int draws;
        Draws(int value) { this.value = value; }
        @Override public int nextInt(int bound) { draws++; return Math.min(value, bound - 1); }
    }
}
