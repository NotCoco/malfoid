package com.malfoid.acceptance;

import com.malfoid.asset.GesturePlayback;
import com.malfoid.asset.NativeActor;
import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.MotionMath;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RouteMotion;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.runelite.api.ChatMessageType;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.NpcSpawned;
import org.junit.Test;
import static com.malfoid.acceptance.AdditionalProducerFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** NPC callbacks, the native shared intake, actual root closure and overhead first-paint settlement. */
public class LivingWorldCreatureDeliveryAcceptanceTest
{
    @Test public void aDataMatchedGoblinSpeaksFromHerNativePositionWithoutAnApproachOrHold() throws Exception
    {
        Fixture f = new Fixture(); f.npc("<col=ffffff>Goblin</col>", 54, 52, 0, false);
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-GOBLIN");
        assertEquals(0, f.world.director.getExecutionHoldTicks());
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
        assertEquals(-1L, ((Number) get(f.world.f.session, "directorSceneId")).longValue());
        assertTrue(packet.getLineId().startsWith("CR-GOBLIN-"));
        f.world.paint(packet); assertFalse(f.world.director.livingWorldSpacingReady());
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
    }

    @Test public void aWizardTurnsHerOnceForFourTicksAndThenTheSourceCannotRepeat() throws Exception
    {
        Fixture f = new Fixture(); f.npc("Wizard", 54, 52, 0, false);
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-WIZARD");
        long offered = f.world.f.tick();
        assertEquals(offered + 4, ((Number) get(f.world.f.actor, "creatureFacingUntilTick")).longValue());
        assertNotNull(get(f.world.f.actor, "timedFacingCreature"));
        assertFalse(f.world.f.actor.isMoving());
        f.world.paint(packet); f.world.hold(4);
        assertNull(get(f.world.f.actor, "timedFacingCreature"));
        f.world.director.invalidate(true); f.world.hold(501);
        assertTrue("The same native NPC cannot become a fresh wizard opportunity", f.world.director.isIdle());
        assertNull(get(f.world.f.actor, "timedFacingCreature"));
    }

    @Test public void aCatLineWaitsForHerActualNativeStrollArrivalBesideTheAnimal() throws Exception
    {
        Fixture f = new Fixture(); NPC cat = f.npc("Cat", 54, 54, 0, false);
        ((Random) get(f.world.f.actor, "random")).setSeed(7);
        f.world.commit();
        assertEquals("CR-FOND", f.world.f.actor.getCreatureAwareness().getGroupId());
        assertNull("Sight alone is not an arrival", f.world.director.getPacket());
        assertEquals(0, f.world.worldRandom.draws);
        long deadline = f.world.f.tick() + 160;
        while (f.world.director.getPacket() == null && f.world.f.tick() < deadline)
        {
            f.world.cycles(30); f.world.commit();
        }
        SpeechPacket packet = f.world.issued("CR-FOND");
        CreatureAwareness.Creature arrived = f.world.f.actor.getCreatureStrollArrival();
        assertNotNull("The native final edge supplies the animal arrival", arrived);
        assertEquals(f.world.f.tick(), f.world.f.actor.getCreatureStrollArrivalTick());
        assertEquals(arrived.key, packet.getCandidate().getConditionFacts().get("creatureKey"));
        MotionObservation motion = f.world.f.actor.getLatestMotionCompletion();
        assertNotNull(motion); assertEquals(MotionObservation.Status.ARRIVED, motion.status);
        assertEquals(MotionObservation.Purpose.ROAM, motion.purpose);
        assertTrue(motion.completedEdges >= 1 && motion.completedEdges <= 4);
        assertEquals(0, motion.completedRunEdges);
        LocalPoint stand = f.world.f.actor.getLocation(); WorldPoint animal = cat.getWorldLocation();
        assertEquals(1, MotionMath.distance(3200 + stand.getSceneX(), 3200 + stand.getSceneY(),
            animal.getX(), animal.getY()));
        assertTrue(MotionMath.distance(stand.getSceneX(), stand.getSceneY(), 50, 50) <= 5);
        long settled = ((Number) get(f.world.f.actor, "settledSinceTick")).longValue();
        long nextStroll = ((Number) get(f.world.f.actor, "nextIdleShiftTick")).longValue();
        assertTrue("The animal rest lasts at least twelve ticks", nextStroll - settled >= 12);
        assertEquals(0, f.world.director.getExecutionHoldTicks());
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
        f.world.paint(packet); assertEquals(1, f.world.worldRandom.draws);
        f.world.cycles(30); f.world.commit(); f.world.cycles(30);
        assertFalse("Native rest continues independently of speech", f.world.f.actor.isMoving());
        int facing = MotionMath.bearing((animal.getX() - 3200) * 128 + 64 - stand.getX(),
            (animal.getY() - 3200) * 128 + 64 - stand.getY());
        assertEquals(facing, ((Number) get(f.world.f.actor, "orientation")).intValue());
        assertEquals("A retained arrival cannot draw again", 1, f.world.worldRandom.draws);
    }

    @Test public void anAuthoritativeNpcDeathRevokesTheUnpaintedSentenceOnThatCallback() throws Exception
    {
        Fixture f = new Fixture(); NPC goblin = f.npc("Goblin", 54, 52, 0, false);
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-GOBLIN"); f.world.publish();
        long tick = f.world.f.tick(); ActorDeath event = new ActorDeath(goblin);
        // The event itself is authoritative even before the native isDead field catches up.
        f.world.f.plugin.onActorDeath(event);
        assertEquals(tick, f.world.f.tick()); f.world.assertNoPaint(packet);
    }

    @Test public void creatureSourceLossRevokesSpeechWithoutStoppingActualNativeFollowing() throws Exception
    {
        Fixture f = new Fixture();
        for (int x : new int[]{3252, 3254, 3256})
        {
            f.world.f.point = new WorldPoint(x, 3250, 0); f.world.commit(); f.world.cycles(30);
        }
        LocalPoint companion = f.world.f.actor.getLocation(); assertNotNull(companion);
        NPC goblin = f.npc("Goblin", companion.getSceneX(), companion.getSceneY() + 2, 0, false);
        f.world.f.point = new WorldPoint(3258, 3250, 0); f.world.commit();
        SpeechPacket packet = f.world.issued("CR-GOBLIN");
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
        assertTrue(f.world.f.actor.isFollowing()); assertTrue(f.world.f.actor.isMoving());
        RouteMotion motion = (RouteMotion) get(f.world.f.actor, "motion");
        long completed = motion.getCompletedEdges(), tick = f.world.f.tick();
        f.world.publish(); f.world.f.plugin.onActorDeath(new ActorDeath(goblin));
        assertEquals(tick, f.world.f.tick()); f.world.assertNoPaint(packet);
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
        assertTrue("Source loss preserves ordinary following", f.world.f.actor.isFollowing());
        assertTrue("Source loss preserves the native route", f.world.f.actor.isMoving());
        f.world.cycles(30);
        assertTrue("Real ClientTick edges keep advancing after revocation", motion.getCompletedEdges() > completed);
        assertEquals("NORMAL", get(f.world.f.actor, "intent").toString());
    }

    @Test public void theSameNativeWizardCannotTurnAgainAfterARealSameSceneAbsenceBeyondFiveHundredTicks() throws Exception
    {
        Fixture f = new Fixture(); f.npc("Wizard", 54, 52, 0, false);
        f.world.commit(); SpeechPacket first = f.world.issued("CR-WIZARD"); f.world.paint(first);
        String incarnation = f.world.f.actor.getCreatureSample().creatures.get(0).key;
        long generation = ((Number) get(f.world.f.session, "sessionGeneration")).longValue();
        long revision = ((Number) get(f.world.f.session, "sceneRevision")).longValue();
        when(f.world.f.config.showMalfoid()).thenReturn(false); f.world.commit();
        assertFalse("Root really removes the native visual", f.world.f.actor.isVisible());
        assertFalse(f.world.f.presence.isPresent()); assertNull(f.world.director.getPacket());
        f.world.hold(501);
        // The shared delivery fixture prepares a visual, without creating any motion receipt.
        set(f.world.f.session, "resourcesLoaded", false); f.world.f.restorePresent();
        f.world.clearReplayMockHistory(); f.world.enableNative();
        f.world.commit();
        assertEquals(generation, ((Number) get(f.world.f.session, "sessionGeneration")).longValue());
        assertEquals(revision, ((Number) get(f.world.f.session, "sceneRevision")).longValue());
        assertEquals("The unchanged live NPC retains its scoped incarnation", incarnation,
            f.world.f.actor.getCreatureSample().creatures.get(0).key);
        assertTrue(f.world.director.isIdle()); assertNull(get(f.world.f.actor, "timedFacingCreature"));
        assertEquals("No new probability draw follows re-show", 1, f.world.worldRandom.draws);
        f.world.assertHealthy();
    }

    @Test public void followerDeadWrongPlaneViewRadiusAndWholeWordFailuresStayQuiet() throws Exception
    {
        Fixture f = new Fixture();
        NPC follower = f.npc("Goblin", 54, 52, 0, true);
        NPC dead = f.npc("Goblin", 54, 52, 0, false); when(dead.isDead()).thenReturn(true);
        f.npc("Goblin", 54, 52, 1, false);
        NPC otherView = f.npc("Goblin", 54, 52, 0, false); when(otherView.getWorldView()).thenReturn(mock(WorldView.class));
        f.npc("Goblin", 62, 50, 0, false);
        f.npc("Hobgoblin", 54, 52, 0, false);
        when(f.world.f.client.getFollower()).thenReturn(null);
        f.world.commit(); assertTrue(f.world.director.isIdle());
        assertNull(f.world.creatures.getCurrent()); assertEquals(0, f.world.worldRandom.draws);
    }

    @Test public void onlyHisOwnFollowerTurnsHerAfterTwentyTicksAndARealPaintDebitsTheSharedWorldCap() throws Exception
    {
        Fixture f = new Fixture(); NPC mine = f.npc("An arbitrary pet name", 54, 52, 0, true);
        NPC theirs = f.npc("Another pet", 54, 53, 0, true);
        when(f.world.f.client.getFollower()).thenReturn(mine);
        int[][] flags = f.world.f.view.getCollisionMaps()[0].getFlags();
        for (int x = 53; x <= 55; x++) for (int y = 49; y <= 51; y++)
        {
            if (x != 54 || y != 50) { flags[x][y] = net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL; }
        }
        for (int i = 0; i < 19; i++) { f.world.commit(); }
        assertNull(f.world.director.getPacket()); assertNull(get(f.world.f.actor, "timedFacingCreature"));
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-PET");
        assertNotNull(get(f.world.f.actor, "timedFacingCreature"));
        assertEquals(f.world.f.tick() + 4, ((Number) get(f.world.f.actor, "creatureFacingUntilTick")).longValue());
        assertEquals(1, f.world.f.actor.getCreatureSample().creatures.size());
        assertEquals(0, f.world.creatures.getWizardIdentityCount());
        f.world.paint(packet); assertFalse(f.world.director.livingWorldSpacingReady());
        f.world.director.invalidate(true); f.world.hold(5);
        assertNull(get(f.world.f.actor, "timedFacingCreature"));
        // A new appearance before 1,500 ticks may turn her but cannot offer another pet sentence.
        when(f.world.f.client.getFollower()).thenReturn(null); f.world.commit();
        when(f.world.f.client.getFollower()).thenReturn(mine); f.world.hold(20);
        assertNull(f.world.director.getPacket()); assertEquals(1, f.world.worldRandom.draws);
        verify(theirs, never()).getInteracting();
    }

    @Test public void replacingTheOwnedFollowerRevokesTheUnpaintedPetCapabilityImmediately() throws Exception
    {
        Fixture f = new Fixture(); NPC mine = f.npc("Pet", 54, 52, 0, true);
        NPC replacement = f.npc("Other pet", 54, 53, 0, true);
        when(f.world.f.client.getFollower()).thenReturn(mine); f.world.hold(20);
        SpeechPacket packet = f.world.issued("CR-PET"); f.world.publish();
        when(f.world.f.client.getFollower()).thenReturn(replacement);
        f.world.f.plugin.onClientTick(new net.runelite.api.events.ClientTick());
        f.world.assertNoPaint(packet);
    }

    @Test public void ratPriorityKeepsTheCatAndGoblinWorldLineSilent() throws Exception
    {
        Fixture f = new Fixture(); f.npc("Cat", 54, 52, 0, false);
        f.npc("Goblin", 54, 53, 0, false); f.npc("Rat", 54, 51, 0, false);
        f.world.commit();
        CreatureAwareness awareness = f.world.f.actor.getCreatureAwareness();
        assertEquals("F01", awareness.getGroupId()); assertTrue(awareness.isKeepingAway());
        SceneCandidate active = f.world.director.getActiveCandidate();
        assertTrue(active == null || !f.world.director.isLivingWorld(active));
        assertEquals(0, f.world.worldRandom.draws);
    }

    @Test public void aRealNumberedOutcomeWinsTheSameTickAndTheSightCannotReplayAfterIt() throws Exception
    {
        Fixture f = new Fixture(); f.npc("Goblin", 54, 52, 0, false);
        f.world.f.report(15, ChatMessageType.GAMEMESSAGE, "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.");
        f.world.commit(); SceneCandidate numbered = f.world.director.getActiveCandidate();
        assertNotNull(numbered); assertEquals("M147", numbered.getScenarioId());
        f.world.director.invalidate(true); f.world.hold(3);
        assertTrue(f.world.director.isIdle()); assertEquals(0, f.world.worldRandom.draws);
    }

    @Test public void aNumberedOutcomeWinsEvenWhenADataMatchedDragonStartsKeepingAway() throws Exception
    {
        Fixture f = new Fixture(); NPC dragon = f.npc("Green dragon", 54, 52, 0, false);
        when(dragon.getTransformedComposition().getCombatLevel()).thenReturn(79);
        f.world.f.report(16, ChatMessageType.GAMEMESSAGE, "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.");
        f.world.commit();
        CreatureAwareness awareness = f.world.f.actor.getCreatureAwareness();
        assertEquals("CR-DRAGON", awareness.getGroupId()); assertTrue(awareness.isKeepingAway());
        long begin = awareness.getBeginTick();
        SceneCandidate numbered = f.world.director.getActiveCandidate();
        assertNotNull("F.1 keeps the numbered scene ahead of the living-world response", numbered);
        assertEquals("M147", numbered.getScenarioId()); assertFalse(f.world.director.isLivingWorld(numbered));
        assertFalse("The higher scene masks WORLD physical safety", f.world.f.actor.isCreatureSafetyActive());
        SpeechPacket packet = f.world.director.getPacket();
        assertTrue(packet == null || !f.world.director.isLivingWorld(packet.getCandidate()));
        assertEquals("Numbered precedence consumes the WORLD offer without probability", 0, f.world.worldRandom.draws);
        while (f.world.director.getPacket() == null && f.world.f.tick() < numbered.getExpiresTick() - 1)
        {
            f.world.cycles(30); f.world.commit();
            assertFalse(f.world.f.actor.isCreatureSafetyActive());
        }
        packet = f.world.director.getPacket();
        assertNotNull("The real native numbered approach must reach its speech arrival", packet);
        assertEquals("M147", packet.getCandidate().getScenarioId());
        SpeechPacket.PaintReceipt paint = f.world.paint(packet);
        assertEquals(1, f.world.guard.textCount()); assertEquals(1, f.world.guard.interactionCount());
        assertTrue("A numbered paint does not debit the WORLD cap", f.world.director.livingWorldSpacingReady());
        long deadline = f.world.f.tick() + 30;
        while (!f.world.director.isIdle() && f.world.f.tick() < deadline)
        {
            f.world.cycles(30); f.world.commit();
        }
        assertTrue("The actual numbered hold completes", f.world.director.isIdle());
        f.world.commit(); assertTrue("WORLD safety may resume after higher delivery", f.world.f.actor.isCreatureSafetyActive());
        RouteMotion motion = (RouteMotion) get(f.world.f.actor, "motion"); long edges = motion.getCompletedEdges();
        for (int tick = 0; tick < 6 && motion.getCompletedEdges() == edges; tick++)
        {
            f.world.cycles(30); f.world.commit();
        }
        assertTrue("Resumed WORLD safety advances genuine native edges", motion.getCompletedEdges() > edges);
        assertEquals("Masking did not create a new fear episode", begin, awareness.getBeginTick());
        assertEquals("Resuming the consumed WORLD onset cannot draw again", 0, f.world.worldRandom.draws);
        assertSame("Only the numbered sentence has actually painted", paint, f.world.director.getLastPaint());
        assertEquals(1, f.world.guard.textCount()); assertEquals(1, f.world.guard.interactionCount());
    }

    @Test public void aWorldDragonSentenceKeepsItsOwnPhysicalSafetyOnTheFollowingCommit() throws Exception
    {
        Fixture f = new Fixture(); NPC dragon = f.npc("Green dragon", 54, 52, 0, false);
        when(dragon.getTransformedComposition().getCombatLevel()).thenReturn(79);
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-DRAGON");
        assertTrue(f.world.f.actor.isCreatureSafetyActive());
        assertEquals(0, f.world.director.getExecutionHoldTicks());
        assertNull(get(f.world.f.session, "plan"));
        long begin = f.world.f.actor.getCreatureAwareness().getBeginTick();
        SpeechPacket.PaintReceipt paint = f.world.paint(packet);
        RouteMotion motion = (RouteMotion) get(f.world.f.actor, "motion"); long edges = motion.getCompletedEdges();
        f.world.commit(); assertTrue("Her own passive WORLD sentence keeps physical safety eligible",
            f.world.f.actor.isCreatureSafetyActive());
        for (int tick = 0; tick < 6 && motion.getCompletedEdges() == edges; tick++)
        {
            f.world.cycles(30); f.world.commit();
        }
        assertTrue("Actual native safety edges continue while the sentence remains readable",
            motion.getCompletedEdges() > edges);
        assertEquals(begin, f.world.f.actor.getCreatureAwareness().getBeginTick());
        assertEquals(1, f.world.worldRandom.draws); assertSame(paint, f.world.director.getLastPaint());
        assertFalse(f.world.director.livingWorldSpacingReady());
        assertEquals(1, f.world.guard.textCount()); assertEquals(1, f.world.guard.interactionCount());
    }

    @Test public void aDragonFirstSeenDuringANumberedSceneWaitsForItsFirstEligibleSafetyEpisode() throws Exception
    {
        Fixture f = new Fixture(); f.world.f.report(17, ChatMessageType.GAMEMESSAGE, "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.");
        f.world.commit(); SceneCandidate numbered = f.world.director.getActiveCandidate();
        assertNotNull(numbered); assertEquals("M147", numbered.getScenarioId());
        NPC dragon = f.npc("Green dragon", 54, 52, 0, false);
        when(dragon.getTransformedComposition().getCombatLevel()).thenReturn(79); f.world.commit();
        assertFalse("A new WORLD safety episode cannot begin during the higher scene",
            f.world.f.actor.getCreatureAwareness().isKeepingAway());
        assertFalse(f.world.f.actor.isCreatureSafetyActive()); assertSame(numbered, f.world.director.getActiveCandidate());
        assertEquals(0, f.world.worldRandom.draws);
        while (f.world.director.getPacket() == null && f.world.f.tick() < numbered.getExpiresTick() - 1)
        {
            f.world.cycles(30); f.world.commit();
        }
        SpeechPacket packet = f.world.director.getPacket(); assertNotNull(packet);
        assertEquals("M147", packet.getCandidate().getScenarioId());
        SpeechPacket.PaintReceipt paint = f.world.paint(packet); long deadline = f.world.f.tick() + 30;
        while (!f.world.director.isIdle() && f.world.f.tick() < deadline)
        {
            f.world.cycles(30); f.world.commit();
        }
        assertTrue(f.world.director.isIdle()); f.world.commit();
        assertTrue(f.world.f.actor.isCreatureSafetyActive());
        long begin = f.world.f.actor.getCreatureAwareness().getBeginTick();
        assertEquals("The first native safety onset follows higher-scene completion", f.world.f.tick(), begin);
        f.world.issued("CR-DRAGON"); assertEquals(1, f.world.worldRandom.draws);
        assertSame(paint, f.world.director.getLastPaint()); assertTrue(f.world.director.livingWorldSpacingReady());
        assertEquals(1, f.world.guard.textCount()); assertEquals(1, f.world.guard.interactionCount());
        f.world.commit(); assertEquals(begin, f.world.f.actor.getCreatureAwareness().getBeginTick());
        assertTrue(f.world.f.actor.isCreatureSafetyActive()); assertEquals(1, f.world.worldRandom.draws);
    }

    /** H.4 and H.6 (P39): a statue that moves. She turns to it and leans in to look for as long as the look lasts. */
    @Test public void aGargoyleTurnsHerAndSheLeansInToLookAtIt() throws Exception
    {
        Fixture f = new Fixture(); NPC gargoyle = f.npc("Gargoyle", 54, 52, 0, false);
        when(gargoyle.getTransformedComposition().getCombatLevel()).thenReturn(111);
        f.world.commit(); SpeechPacket packet = f.world.issued("CR-GARGOYLE");
        NativeActor actor = f.world.f.actor;
        assertTrue(packet.getLineId().startsWith("CR-GARGOYLE-"));
        assertNotNull(get(actor, "timedFacingCreature")); assertFalse("It does not frighten her", actor.isCreatureSafetyActive());
        assertTrue(actor.hasGestures()); assertNull("She has not turned to it yet", actor.getGesture());
        f.world.paint(packet);
        f.world.cycles(30); f.world.commit(); f.world.cycles(30);
        assertEquals(GesturePlayback.Gesture.PEER, actor.getGesture()); assertEquals("PEER", actor.getLastClip());
        LocalPoint stand = actor.getLocation();
        int facing = MotionMath.bearing(54 * 128 + 64 - stand.getX(), 52 * 128 + 64 - stand.getY());
        assertTrue("She peers at it, not past it",
            MotionMath.angleDifference(facing, ((Number) get(actor, "orientation")).intValue()) <= 64);
        for (int tick = 0; tick < 4; tick++) { f.world.commit(); f.world.cycles(30); }
        assertNull(get(actor, "timedFacingCreature"));
        assertNull("The look is over and she stands as she did", actor.getGesture());
        assertNotEquals("PEER", actor.getLastClip());
    }

    /** H.7: "if i stand near the bear she would just not come near the bear". She says so from where she has stopped. */
    @Test public void stoppedShortOfABearHeStandsBySheSaysSoOnceFromWhereSheIs() throws Exception
    {
        Fixture f = new Fixture(); NativeActor actor = f.world.f.actor;
        f.world.moveTo(3252, 3250);     // he walks over to her: she is standing by him when the bear turns up
        for (int tick = 0; tick < 6; tick++) { f.world.cycles(30); f.world.commit(); }
        LocalPoint start = actor.getLocation();
        assertFalse(actor.isMoving()); assertTrue(actor.getDistanceToPlayer() <= 2);
        // Beside him, on the far side from her: she is standing on the ground round it.
        int bearX = 52 + Integer.signum(52 - start.getSceneX()), bearY = 50 + Integer.signum(50 - start.getSceneY());
        f.npc("Grizzly bear", bearX, bearY, 0, false);
        SpeechPacket said = null; long deadline = f.world.f.tick() + 40;
        while (said == null && f.world.f.tick() < deadline)
        {
            f.world.commit();
            SpeechPacket packet = f.world.director.getPacket();
            if (packet != null && packet.getFirstPaint() == null)
            {
                // E.5's own line, when she says one as she runs to hide, comes first; this one waits for it.
                if ("W-KEPT-BACK".equals(packet.getCandidate().getScenarioId())) { said = packet; }
                else { f.world.paint(packet); }
            }
            if (said == null) { f.world.cycles(30); }
        }
        assertNotNull("Kept back for four ticks, she says so", said);
        f.world.issued("W-KEPT-BACK");
        assertTrue(said.getLineId().startsWith("W-KEPT-BACK-"));
        assertEquals("KEPT_BACK_BY_BEAST", said.getCandidate().getConditionFacts().get("momentFact"));
        assertEquals("It has no hold and no walk of its own", 0, f.world.director.getExecutionHoldTicks());
        assertNull(get(f.world.f.session, "plan")); assertEquals("NORMAL", get(actor, "intent").toString());
        assertTrue(actor.isKeptBackByBeast()); assertFalse(actor.isMoving());
        LocalPoint stand = actor.getLocation();
        assertEquals("Every nearer tile is within three of the bear", 3, actor.getDistanceToPlayer());
        assertEquals(4, MotionMath.distance(stand.getSceneX(), stand.getSceneY(), bearX, bearY));
        assertTrue("Behind him", CreatureAwareness.farSide(stand.getSceneX(), stand.getSceneY(), 52, 50, bearX, bearY));
        assertTrue("She backed away to get there", MotionMath.distance(start.getSceneX(), start.getSceneY(), bearX, bearY) <= 3);
        SpeechPacket.PaintReceipt paint = f.world.paint(said);
        assertEquals("Saying it does not straighten her up", GesturePlayback.Gesture.COWER, actor.getGesture());
        for (int tick = 0; tick < 30; tick++)
        {
            f.world.cycles(30); f.world.commit();
            SpeechPacket later = f.world.director.getPacket();
            assertTrue("Once, for as long as he stays there", later == null || later == said);
            assertTrue(actor.isKeptBackByBeast());
        }
        assertSame(paint, f.world.director.getLastPaint());
    }

    private static final class Fixture
    {
        final LivingWorldDeliveryFixture world = new LivingWorldDeliveryFixture();
        final List<NPC> npcs = new ArrayList<>();
        Fixture() throws Exception
        {
            doReturn(indexedSet(npcs)).when(world.f.view).npcs();
            doReturn(indexedSet(new ArrayList<Player>())).when(world.f.view).players();
            world.enableNative(); world.baseline();
        }
        NPC npc(String name, int x, int y, int plane, boolean follower)
        {
            NPC npc = mock(NPC.class); NPCComposition definition = mock(NPCComposition.class);
            when(definition.getName()).thenReturn(name); when(definition.getCombatLevel()).thenReturn(40);
            when(definition.getSize()).thenReturn(1); when(definition.isFollower()).thenReturn(follower);
            when(npc.getComposition()).thenReturn(definition); when(npc.getTransformedComposition()).thenReturn(definition);
            when(npc.getWorldView()).thenReturn(world.f.view);
            int worldViewId = world.f.view.getId();
            when(npc.getLocalLocation()).thenReturn(new LocalPoint(x * 128 + 64, y * 128 + 64, worldViewId));
            when(npc.getWorldLocation()).thenReturn(new WorldPoint(3200 + x, 3200 + y, plane));
            npcs.add(npc); world.f.plugin.onNpcSpawned(new NpcSpawned(npc)); return npc;
        }
    }
    private static <T> IndexedObjectSet<T> indexedSet(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
}
