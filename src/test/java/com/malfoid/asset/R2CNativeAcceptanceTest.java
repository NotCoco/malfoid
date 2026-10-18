package com.malfoid.asset;

import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.MotionMath;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RouteMotion;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.scenario.ScenarioSnapshot;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.lang.reflect.Method;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Model;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;
import org.junit.BeforeClass;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual root commits, adjacent native segments and overlay rasterization; no manufactured receipts. */
public class R2CNativeAcceptanceTest
{
    @BeforeClass public static void recordActualVerificationVm()
    {
        System.out.println("C_NATIVE_TEST_VM version=" + System.getProperty("java.version") + " vendor="
            + System.getProperty("java.vendor") + " home=" + System.getProperty("java.home"));
    }

    @Test public void blockedFarSideFallsBackInTheSameTickWithOnlyOneActualBfs() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.playerX = 48; f.commit();
        for (int x = 0; x < 48; x++) { Arrays.fill(f.flags[x], CollisionDataFlag.BLOCK_MOVEMENT_FULL); }
        NativeNpc rat = new NativeNpc(f, "Rat", 57, 50, 1); creatures(f, rat);
        long searches = f.actor.getPlanningCount(); f.commit();
        assertEquals(1, f.actor.getPlanningCount() - searches);
        assertEquals(2, f.actor.getLastPlannerSteps()); assertEquals(2, f.actor.getMotionObservation().completedRunEdges);
        assertTrue(f.actor.getDistanceToPlayer() >= 1 && f.actor.getDistanceToPlayer() <= 2);
        assertFalse("The entire legal far side is blocked", farSide(f, rat));
        f.commit(); assertEquals(1, f.actor.getPlanningCount() - searches);
    }
    @Test public void ratFiveTilesAwayTakesFiveShortestRunEdgesInThreeTicks() throws Exception
    {
        NativeRecoveryRootFixture f = ready();
        f.playerX = 48; f.commit();
        assertEquals(4, f.actor.getDistanceToPlayer()); assertEquals(52, f.actor.getLocation().getSceneX());
        NativeNpc rat = new NativeNpc(f, "<col=00ff00>Rat</col>", 57, 50, 1);
        creatures(f, rat); long before = motion(f).getCompletedEdges();
        f.commit();
        assertEquals("The legal far-side minimum needs five RUN edges", 5, f.actor.getLastPlannerSteps());
        assertEquals(2, f.actor.getMotionObservation().completedRunEdges);
        f.commit();
        assertEquals(4, motion(f).getCompletedEdges() - before);
        assertFalse("Two native RUN ticks complete only four of the five edges", farSide(f, rat));
        f.commit(); assertEquals(5, motion(f).getCompletedEdges() - before); assertTrue(farSide(f, rat));
        assertTrue(f.actor.getDistanceToPlayer() >= 1 && f.actor.getDistanceToPlayer() <= 2);
        f.clientTicks(40); assertFaces(f, rat.x, rat.y, false);       // H.3: she turns to look at it; half a turn is 32 cycles
        SceneCandidate fear = f.director.getActiveCandidate();
        if (fear != null) { assertNull("An optional line owns no physical hold", f.actor.getMotionHoldReceipt(fear.getId())); }
        assertEquals("NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
    }

    @Test public void beastsAndRatsRunUndeadWalkAndFearPlansAtMostEveryFourTicks() throws Exception
    {
        NativeRecoveryRootFixture wolfFixture = ready();
        NativeNpc wolf = new NativeNpc(wolfFixture, "Wolf", 54, 50, 30); creatures(wolfFixture, wolf);
        wolfFixture.commit(); assertEquals(2, wolfFixture.actor.getMotionObservation().completedRunEdges);
        wolfFixture.commit();
        assertTrue(farSide(wolfFixture, wolf)); assertTrue(wolfFixture.actor.getDistanceToPlayer() <= 2);
        wolfFixture.clientTicks(40); assertFaces(wolfFixture, wolf.x, wolf.y, false);

        NativeRecoveryRootFixture ghostFixture = ready();
        LivingWorldData worldData = LivingWorldData.load(MalfoidPlugin.class);
        NativeRecoveryRootFixture.set(ghostFixture.session, "worldData", worldData);
        assertSame(worldData, NativeRecoveryRootFixture.get(ghostFixture.session, "worldData"));
        Method worldAllowed = MalfoidPlugin.class.getDeclaredMethod("worldCreatureDecisionsAllowed", ghostFixture.session.getClass());
        worldAllowed.setAccessible(true);
        assertEquals("The bound WORLD dependency and real root gate admit WARY", Boolean.TRUE,
            worldAllowed.invoke(ghostFixture.plugin, ghostFixture.session));
        ghostFixture.actor.setCreatureClassifier((name, level) -> "ghost".equals(name)
            ? new CreatureAwareness.Classification("CR-UNDEAD", 3, CreatureAwareness.Behaviour.WARY, 1, 2)
            : CreatureAwareness.classifyFear(name, level));
        NativeNpc ghost = new NativeNpc(ghostFixture, "Ghost", 54, 50, 30); creatures(ghostFixture, ghost);
        for (int i = 0; i < 4; i++)
        {
            ghostFixture.commit();
            MotionObservation observation = ghostFixture.actor.getMotionObservation();
            assertNotNull("An eligible WARY route produces a genuine native receipt", observation);
            assertEquals(0, observation.completedRunEdges);
            assertTrue("WARY completes actual walking edges", observation.completedEdges > 0);
        }
        assertTrue(farSide(ghostFixture, ghost)); assertTrue(ghostFixture.actor.getDistanceToPlayer() <= 2);
        assertFaces(ghostFixture, ghost.x, ghost.y, false);

        NativeRecoveryRootFixture ratFixture = ready();
        NativeNpc rat = new NativeNpc(ratFixture, "Giant rat", 54, 50, 1); creatures(ratFixture, rat);
        ratFixture.commit(); assertEquals(2, ratFixture.actor.getMotionObservation().completedRunEdges);
        long planned = ratFixture.actor.getPlanningCount();
        for (int i = 0; i < 3; i++)
        {
            rat.x = ratFixture.actor.getLocation().getSceneX() + 2;
            ratFixture.commit(); assertEquals(planned, ratFixture.actor.getPlanningCount());
        }
        rat.x = ratFixture.actor.getLocation().getSceneX() + 2;
        ratFixture.commit(); assertEquals(planned + 1, ratFixture.actor.getPlanningCount());
    }

    @Test public void blackBearFiveTilesAwayUsesTheSameFiveRunEdgesAndThreeTicks() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.playerX = 48; f.commit();
        assertEquals(4, f.actor.getDistanceToPlayer()); NativeNpc bear = new NativeNpc(f, "Black bear", 57, 50, 19);
        creatures(f, bear); long before = motion(f).getCompletedEdges(); f.commit();
        assertEquals("F02", f.actor.getCreatureAwareness().getGroupId()); assertEquals(5, f.actor.getLastPlannerSteps());
        assertEquals(2, f.actor.getMotionObservation().completedRunEdges); f.commit();
        assertEquals(4, motion(f).getCompletedEdges() - before); assertFalse(farSide(f, bear));
        f.commit(); assertEquals(5, motion(f).getCompletedEdges() - before); assertTrue(farSide(f, bear));
        f.clientTicks(40); assertFaces(f, bear.x, bear.y, false);
        assertEquals("NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
    }

    /** H.2 (P33 to P35): the owner's bear. She used to take the straight way to her place behind him, through it. */
    @Test public void aBearBetweenThemIsWalkedRoundAndNeverComeNearerThanFourTiles() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.playerX = 48; f.commit();
        assertEquals(52, f.actor.getLocation().getSceneX()); assertEquals(50, f.actor.getLocation().getSceneY());
        NativeNpc bear = new NativeNpc(f, "Black bear", 50, 50, 19); creatures(f, bear);
        long before = motion(f).getCompletedEdges();
        f.commit();     // it has appeared two tiles from her: she leaves on this tick, away from it (P34)
        assertEquals("F02", f.actor.getCreatureAwareness().getGroupId());
        assertTrue("She is off the ground round it after one tick's run", bearDistance(f, bear) >= 4);
        for (int tick = 0; tick < 14 && (f.actor.isMoving() || !farSide(f, bear)); tick++)
        {
            f.commit();
            assertTrue("tick " + tick + " at " + f.actor.getLocation().getSceneX() + "," + f.actor.getLocation().getSceneY(),
                bearDistance(f, bear) >= 4);
        }
        assertFalse(f.actor.isMoving());
        assertTrue("She ends behind him", farSide(f, bear));
        assertTrue(f.actor.getDistanceToPlayer() >= 1 && f.actor.getDistanceToPlayer() <= 2);
        assertTrue("The way round is longer than the five tiles straight through",
            motion(f).getCompletedEdges() - before >= 9);
        f.clientTicks(40); assertFaces(f, bear.x, bear.y, false);
    }

    /** H.2 (P36): "if i stand near the bear she would just not come near the bear". */
    @Test public void withHimBesideTheBearSheStaysFourTilesFromItBehindHim() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.playerX = 48; f.commit();
        assertEquals(52, f.actor.getLocation().getSceneX());
        NativeNpc bear = new NativeNpc(f, "Grizzly bear", 49, 50, 21); creatures(f, bear);
        for (int tick = 0; tick < 14; tick++)
        {
            f.commit();
            assertTrue("tick " + tick, bearDistance(f, bear) >= 3);
        }
        assertFalse(f.actor.isMoving());
        assertTrue(farSide(f, bear));
        assertEquals("One and two tiles behind him are within three of the bear, so she stands three behind him", 3, f.actor.getDistanceToPlayer());
        assertEquals(4, bearDistance(f, bear));
        assertTrue("That is being kept back (H.7)", f.actor.isKeptBackByBeast());
    }

    /** H.6 (P38): "i want her to have her own little emotes". Stopped short of the bear, she cowers. */
    @Test public void stoppedShortOfTheBearSheCowersUntilSheHasToMove() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); NativeNpc bear = cowering(f);
        assertEquals("Held on its last sample for as long as she is afraid", (GesturePlayback.Gesture.COWER.samples - 1) * 2,
            ((Number) NativeRecoveryRootFixture.get(f.actor, "clipCycle")).intValue());
        for (int tick = 0; tick < 6; tick++)
        {
            f.commit();
            assertEquals(GesturePlayback.Gesture.COWER, f.actor.getGesture()); assertEquals(4, bearDistance(f, bear));
        }
        boolean moved = false;
        for (int step = 0; step < 4; step++)
        {
            f.playerY += 2; f.commit();
            if (!f.actor.isMoving()) { continue; }
            moved = true;
            assertNull("Having to move ends it on that cycle", f.actor.getGesture());
            assertTrue(f.actor.getLastClip(), "WALK".equals(f.actor.getLastClip()) || "RUN".equals(f.actor.getLastClip()));
        }
        assertTrue("She goes after him", moved);
    }

    @Test public void whenTheBearHasGoneSheStandsUpAgain() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); cowering(f);
        doReturn(indexed(Collections.<NPC>emptyList())).when(f.view).npcs();
        for (int quiet = 0; quiet < 4; quiet++)
        {
            f.commit(); assertTrue(f.actor.getCreatureAwareness().isKeepingAway());
            assertEquals("Still afraid for four quiet ticks, still cowering", GesturePlayback.Gesture.COWER, f.actor.getGesture());
        }
        f.commit(); assertFalse(f.actor.getCreatureAwareness().isKeepingAway());
        for (int tick = 0; tick < 8 && (f.actor.isMoving() || f.actor.getGesture() != null); tick++) { f.commit(); }
        assertFalse(f.actor.isMoving()); assertNull(f.actor.getGesture());
        assertNotEquals("COWER", f.actor.getLastClip());
    }

    /** He stands beside a bear; she has stopped three tiles behind him and has been standing for over a second. */
    private static NativeNpc cowering(NativeRecoveryRootFixture f) throws Exception
    {
        f.playerX = 48; f.commit();
        NativeNpc bear = new NativeNpc(f, "Grizzly bear", 49, 50, 21); creatures(f, bear);
        for (int tick = 0; tick < 14 && (f.actor.isMoving() || !f.actor.isKeptBackByBeast()); tick++) { f.commit(); }
        assertTrue(f.actor.isKeptBackByBeast()); assertTrue(f.actor.hasGestures());
        f.commit();
        assertEquals("Within a second of stopping she is cowering", GesturePlayback.Gesture.COWER, f.actor.getGesture());
        assertEquals("COWER", f.actor.getLastClip());
        f.commit();
        return bear;
    }

    private static int bearDistance(NativeRecoveryRootFixture f, NativeNpc creature)
    {
        LocalPoint p = f.actor.getLocation();
        return MotionMath.distance(p.getSceneX(), p.getSceneY(), creature.x, creature.y);
    }

    @Test public void endingNeedsFiveKnownQuietCommitsThenTheShortRest() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); NativeNpc rat = new NativeNpc(f, "Rat", 54, 50, 1); creatures(f, rat);
        f.commit(); assertTrue(f.actor.isInspectionSuppressed());
        doReturn(indexed(Collections.<NPC>emptyList())).when(f.view).npcs();
        for (int i = 0; i < 4; i++) { f.commit(); assertTrue(f.actor.getCreatureAwareness().isKeepingAway()); }
        f.commit(); assertFalse(f.actor.getCreatureAwareness().isKeepingAway());
        assertFalse(f.actor.isInspectionSuppressed());
        long rest = (long) NativeRecoveryRootFixture.get(f.actor, "nextIdleShiftTick") - f.tick();
        assertTrue("Ending starts P24's short rest", rest >= 12 && rest <= 36);
        assertFalse(f.actor.isMoving());
    }

    @Test public void inputExcludesDeadFollowerOtherPlaneAndOtherViewAndRevokesLiveLease() throws Exception
    {
        NativeRecoveryRootFixture f = ready();
        NativeNpc dead = new NativeNpc(f, "Rat", 53, 50, 1), follower = new NativeNpc(f, "Rat", 53, 50, 1);
        NativeNpc otherPlane = new NativeNpc(f, "Rat", 53, 50, 1), otherView = new NativeNpc(f, "Rat", 53, 50, 1);
        dead.dead = true; when(follower.definition.isFollower()).thenReturn(true); otherPlane.plane = 1;
        when(otherView.npc.getWorldView()).thenReturn(mock(net.runelite.api.WorldView.class));
        creatures(f, dead, follower, otherPlane, otherView); f.commit();
        assertTrue(f.actor.getCreatureSample().known); assertTrue(f.actor.getCreatureSample().creatures.isEmpty());
        assertFalse(f.actor.getCreatureAwareness().isKeepingAway());
        NativeNpc live = new NativeNpc(f, "Rat", 54, 50, 1); creatures(f, live); f.commit();
        String key = f.actor.getCreatureSample().creatures.get(0).key;
        assertTrue(f.actor.isCreatureCurrent(key, "F01"));
        live.dead = true; assertFalse("Paint-time live recheck refuses death without a commit", f.actor.isCreatureCurrent(key, "F01"));
        live.dead = false; f.plugin.onActorDeath(new ActorDeath(live.npc));
        assertFalse("Authoritative death callback revokes the key before isDead catches up", f.actor.isCreatureCurrent(key, "F01"));
        f.commit(); assertTrue(f.actor.getCreatureSample().creatures.isEmpty());
    }

    @Test public void nearestOnsetFearLinePaintsWithoutAcquiringMotionAndKindsNeverReoffer() throws Exception
    {
        for (boolean nearestBear : new boolean[]{false, true})
        {
            NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(CreatureAwareness::isE5);
            NativeNpc rat = new NativeNpc(f, "Rat", 54, 50, 1), bear = new NativeNpc(f, "Black bear", 53, 50, 19);
            if (nearestBear) { creatures(f, rat, bear); } else { creatures(f, rat); }
            f.commit(); SceneCandidate candidate = f.director.getActiveCandidate(); assertNotNull(candidate);
            String group = nearestBear ? "F02" : "F01"; assertEquals(group, candidate.getScenarioId());
            assertNull(candidate.getMilestone()); assertNull(f.actor.getMotionHoldReceipt(candidate.getId()));
            assertEquals("NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
            SpeechPacket.PaintReceipt paint = f.paintCurrent(); assertTrue(paint.getLineId().startsWith(group + "-G"));
            assertFalse(f.guard.interactionEligible(f.catalogue.repeatKey(group)));
            NativeNpc nextKind = nearestBear ? new NativeNpc(f, "Giant rat", f.playerX + 1, f.playerY, 1)
                : new NativeNpc(f, "Grizzly bear", f.playerX + 1, f.playerY, 30);
            creatures(f, nextKind); f.commit();
            assertEquals("Switching kind keeps the original fear begin", candidate.getCreatedTick(), f.actor.getCreatureAwareness().getBeginTick());
            for (int i = 0; i < 15; i++) { f.commit(); }
            assertSame("Only the onset's genuine paint is recorded", paint, f.director.getLastPaint());
        }
    }

    @Test public void authoritativeCreatureLossRevokesUnpaintedFearAndRetainsGenuinePaintHistory() throws Exception
    {
        for (boolean painted : new boolean[]{false, true})
        {
            NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(CreatureAwareness::isE5);
            NativeNpc rat = new NativeNpc(f, "Rat", 54, 50, 1); creatures(f, rat); f.commit();
            SceneCandidate candidate = f.director.getActiveCandidate(); assertNotNull(candidate);
            SpeechPacket packet = f.director.getPacket(); assertNotNull(packet);
            SpeechPacket.PaintReceipt receipt = painted ? f.paintCurrent() : null;
            f.plugin.onActorDeath(new ActorDeath(rat.npc)); // Authoritative before the live isDead flag updates.
            assertFalse(f.actor.isCreatureCurrent(candidate.getConditionFacts().get("creatureKey"), "F01"));
            assertFalse(packet.isContinuationAllowed()); assertEquals(receipt, f.director.getLastPaint());
            assertEquals(!painted, f.guard.interactionEligible(f.catalogue.repeatKey("F01")));
            assertEquals("A source cancellation does not acquire the safety movement lane", "NORMAL",
                NativeRecoveryRootFixture.get(f.actor, "intent").toString());
            if (!painted)
            {
                f.clientTicks(1); assertEquals(0, NativeRecoveryRootFixture.pixels(f.render()));
                assertNull(packet.getFirstPaint()); assertTrue(f.director.isIdle());
            }
            f.commit();
            if (!painted) { assertNull("The unpainted onset cannot reoffer during the calm tail", f.director.getPacket()); }
            else { assertSame("The genuine earlier paint keeps its readable bubble", packet, f.director.getPacket()); }
        }
    }

    @Test public void unchangedSceneHidePreservesIncarnationButAuthoritativeLoadingRetiresIt() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); NativeNpc rat = new NativeNpc(f, "Rat", 54, 50, 1); creatures(f, rat); f.commit();
        String key = f.actor.getCreatureSample().creatures.get(0).key;
        MalfoidConfig config = (MalfoidConfig) NativeRecoveryRootFixture.get(f.plugin, "config");
        when(config.showMalfoid()).thenReturn(false); f.commit(); assertFalse(f.actor.isVisible());
        when(config.showMalfoid()).thenReturn(true);
        for (int i = 0; f.actor.getCreatureSample() == null && i < 8; i++) { f.commit(); }
        assertNotNull(f.actor.getCreatureSample()); assertEquals(key, f.actor.getCreatureSample().creatures.get(0).key);
        GameStateChanged loading = new GameStateChanged(); loading.setGameState(GameState.LOADING);
        when(f.client.getGameState()).thenReturn(GameState.LOADING); f.plugin.onGameStateChanged(loading);
        GameStateChanged loggedIn = new GameStateChanged(); loggedIn.setGameState(GameState.LOGGED_IN);
        when(f.client.getGameState()).thenReturn(GameState.LOGGED_IN); f.plugin.onGameStateChanged(loggedIn);
        for (int i = 0; f.actor.getCreatureSample() == null && i < 8; i++) { f.commit(); }
        assertNotNull(f.actor.getCreatureSample()); assertNotEquals(key, f.actor.getCreatureSample().creatures.get(0).key);
    }

    @Test public void unknownNpcPassCannotRetireAnUnchangedScopedIncarnation() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); NativeNpc rat = new NativeNpc(f, "Rat", 54, 50, 1); creatures(f, rat); f.commit();
        String key = f.actor.getCreatureSample().creatures.get(0).key; long begin = f.actor.getCreatureAwareness().getBeginTick();
        doReturn(null).when(f.view).npcs(); f.commit();
        assertFalse(f.actor.getCreatureSample().known); assertEquals(begin, f.actor.getCreatureAwareness().getBeginTick());
        doReturn(indexed(Arrays.asList(rat.npc))).when(f.view).npcs(); f.commit();
        assertTrue(f.actor.getCreatureSample().known); assertEquals(key, f.actor.getCreatureSample().creatures.get(0).key);
        assertEquals(begin, f.actor.getCreatureAwareness().getBeginTick());
    }

    @Test public void zeroEdgeAcceptedInspectionStartsSpacingWithoutInventingMovement() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); LocalPoint source = f.actor.getLocation();
        long edgesBefore = motion(f).getCompletedEdges();
        int objectX = 3200 + source.getSceneX() + 1, objectY = 3200 + source.getSceneY();
        assertEquals(-1, f.actor.getLastInspectionStartTick());
        assertTrue(f.actor.requestInspectionProbe("zero-edge", objectX, objectY, objectX, objectY, 0)); f.commit();
        NativeActor.InspectionRouteProof proof = f.actor.getInspectionRouteProof(); assertNotNull(proof); assertEquals(0, proof.getPathSteps());
        assertEquals("A successful probe is not a trip", -1, f.actor.getLastInspectionStartTick());
        assertFalse(f.actor.requestInspectionDestination(proof.getStandWorldX() + 2, proof.getStandWorldY()));
        assertEquals(-1, f.actor.getLastInspectionStartTick());
        assertTrue(f.actor.requestInspectionDestination(7, proof.getStandWorldX(), proof.getStandWorldY()));
        long started = f.actor.getLastInspectionStartTick(); assertEquals(f.tick(), started); f.commit();
        assertEquals("Zero edges retain the actual-movement statistic", -1, f.actor.getLastVoluntaryMovementTick());
        assertEquals(edgesBefore, motion(f).getCompletedEdges());
        assertNull("Zero-edge acceptance does not invent a movement receipt", f.actor.getMotionObservation());
        f.actor.clearIntent(); f.boxSource();
        f.commitsThrough(started + 99);
        assertFalse(f.actor.requestInspectionProbe("too-early", objectX, objectY, objectX, objectY, 0));
        f.commit(); assertTrue(f.actor.requestInspectionProbe("eligible-at-100", objectX, objectY, objectX, objectY, 0));
        assertEquals(started, f.actor.getLastInspectionStartTick());
    }

    @Test public void bothHmphLinesTurnAfterTwoTicksFromTheirActualDelayedPaint() throws Exception
    {
        for (String line : new String[]{"M09-H01", "M09-H02"})
        {
            NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(id -> "M09".equals(id));
            f.director.setLineValidator((candidate, selected) -> !"M09".equals(candidate.getScenarioId()) || line.equals(selected));
            ItemContainer equipment = mock(ItemContainer.class); Item[] items = new Item[8]; items[4] = new Item(100, 1);
            when(equipment.getItems()).thenReturn(items); when(f.client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
            f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment)); f.commit(); f.commit();
            assertNotNull(f.director.getPacket()); assertEquals(line, f.director.getPacket().getLineId());
            long admitted = f.tick();
            for (int i = 0; i < 4; i++) { f.commit(); assertFalse(turnAway(f)); }
            assertNull(f.director.getPacket().getFirstPaint());
            SpeechPacket.PaintReceipt paint = f.paintCurrent(); assertTrue(paint.getPaintedAtTick() >= admitted + 4);
            assertFalse(turnAway(f)); f.commit(); assertFalse(turnAway(f));
            f.commit(); assertTrue(turnAway(f)); f.clientTicks(2); assertFaces(f, f.playerX, f.playerY, true);
            f.commit(); assertTrue(turnAway(f)); f.waitUntilIdle();
            assertFalse("Scene end restores R2-A facing", turnAway(f));
            f.clientTicks(40); assertFaces(f, f.playerX, f.playerY, false);      // H.3: by turning, within two ticks
        }
    }

    @Test public void realLongRunProducerPaintsWhileNativeFollowingContinuesAndStartsTheFiveHundredTickClock() throws Exception
    {
        NativeRecoveryRootFixture f = ready(); f.observer.setScenarioEnabled(id -> "F03".equals(id));
        Object run = NativeRecoveryRootFixture.get(f.rules(), "longRun");
        ((Random) NativeRecoveryRootFixture.get(run, "random")).setSeed(0L);
        f.commitsThrough(52);
        for (int step = 0; step < 30; step++) { runStep(f, step); f.commit(); }
        SceneCandidate c = f.director.getActiveCandidate(); assertNotNull(c); assertEquals("F03", c.getScenarioId());
        assertTrue(f.actor.isFollowing()); assertEquals("NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
        assertNull(f.actor.getMotionHoldReceipt(c.getId())); assertFalse(turnAway(f));
        assertEquals(Long.MIN_VALUE, (long) NativeRecoveryRootFixture.get(run, "lastSpoken"));
        long edges = motion(f).getCompletedEdges(); SpeechPacket.PaintReceipt paint = f.paintCurrent();
        assertEquals("Where are you taking me, Potter?!", f.director.getPacket().getText());
        assertEquals(paint.getPaintedAtTick(), (long) NativeRecoveryRootFixture.get(run, "lastSpoken"));
        for (int step = 30; step < 230; step++)
        {
            runStep(f, step); f.commit();
            SceneCandidate active = f.director.getActiveCandidate();
            assertEquals("Presentation never takes the movement lane; tick=" + f.tick() + " active="
                + (active == null ? "none" : active.getScenarioId()) + " scene="
                + NativeRecoveryRootFixture.get(f.session, "directorSceneId") + " trace=" + f.logs().stream()
                    .filter(line -> line.contains("SCENE_")).collect(java.util.stream.Collectors.joining(" | ")),
                "NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
            assertFalse(turnAway(f));
        }
        assertTrue(motion(f).getCompletedEdges() > edges);
        assertSame("The continuing run produces no second spoken receipt", paint, f.director.getLastPaint());
        assertTrue((boolean) NativeRecoveryRootFixture.get(run, "consumedRun"));
        for (int i = 0; i < 10; i++) { f.commit(); }
        assertFalse((boolean) NativeRecoveryRootFixture.get(run, "consumedRun"));
        int freshStep = 0;
        while (f.tick() < paint.getPaintedAtTick() + 499)
        {
            runStep(f, freshStep++); f.commit();
            assertFalse("No eligible run is consumed before the 500-tick boundary", (boolean) NativeRecoveryRootFixture.get(run, "consumedRun"));
            assertSame(paint, f.director.getLastPaint());
        }
        runStep(f, freshStep); f.commit();
        SceneCandidate next = f.director.getActiveCandidate(); assertNotNull(next); assertEquals("F03", next.getScenarioId());
        assertEquals(paint.getPaintedAtTick() + 500, next.getCreatedTick());
        SpeechPacket.PaintReceipt second = f.paintCurrent(); assertNotSame(paint, second);
        assertEquals(second.getPaintedAtTick(), (long) NativeRecoveryRootFixture.get(run, "lastSpoken"));
    }

    @Test public void fearCancelsUnpaintedAndPaintedOrdinaryHoldsWithoutErasingSpeechHistory() throws Exception
    {
        for (boolean painted : new boolean[]{false, true})
        {
            NativeRecoveryRootFixture f = ready(); startOutfit(f, "M09-H01");
            SpeechPacket packet = f.director.getPacket();
            SpeechPacket.PaintReceipt receipt = painted ? f.paintCurrent() : null;
            assertFalse("The active reservation or genuine paint owns the repeat guard", f.guard.interactionEligible(f.catalogue.repeatKey("M09")));
            NativeNpc rat = new NativeNpc(f, "Rat", 57, 50, 1); creatures(f, rat);
            f.commit();
            assertEquals("NORMAL", NativeRecoveryRootFixture.get(f.actor, "intent").toString());
            assertFalse("The interrupted scene cannot continue", packet.isContinuationAllowed());
            assertEquals("Cancellation preserves only a genuine prior paint", receipt, f.director.getLastPaint());
            assertEquals(!painted, f.guard.interactionEligible(f.catalogue.repeatKey("M09")));
            f.commit(); f.commit(); assertTrue(farSide(f, rat));
            assertTrue(f.logs().stream().anyMatch(line -> line.contains("CREATURE_PREEMPTED_SCENE scenario=M09")));
        }
    }

    @Test public void ratInterruptsAnActualInspectionTripAndAnEmergencyKeepsItsMotion() throws Exception
    {
        NativeRecoveryRootFixture inspection = ready(); inspection.observer.setScenarioEnabled(id -> "M08".equals(id));
        SceneCandidate trip = startFountain(inspection); assertEquals("M08", trip.getScenarioId());
        NativeNpc rat = new NativeNpc(inspection, "Rat", 57, 50, 1); creatures(inspection, rat);
        inspection.commit(); assertEquals("NORMAL", NativeRecoveryRootFixture.get(inspection.actor, "intent").toString());
        for (int i = 0; i < 3 && !farSide(inspection, rat); i++) { inspection.commit(); }
        assertTrue(farSide(inspection, rat));
        assertTrue(inspection.logs().stream().anyMatch(line -> line.contains("CREATURE_PREEMPTED_SCENE scenario=M08")));

        NativeRecoveryRootFixture emergency = ready(); NativeNpc nearby = new NativeNpc(emergency, "Rat", 54, 50, 1);
        creatures(emergency, nearby); emergency.commit(); SceneCandidate reunion = emergency.deathReunion();
        assertEquals("M02", reunion.getScenarioId()); assertFalse(emergency.director.isContinuationCancelled());
        assertTrue((boolean) NativeRecoveryRootFixture.get(emergency.actor, "sceneEmergency"));
        assertFalse(emergency.logs().stream().anyMatch(line -> line.contains("CREATURE_PREEMPTED_SCENE scenario=M02")));
    }

    private static NativeRecoveryRootFixture ready() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture();
        doReturn(indexed(Arrays.asList(f.player))).when(f.view).players();
        doReturn(indexed(Collections.<NPC>emptyList())).when(f.view).npcs();
        f.observer.setCreatureCurrentValidator(f.actor::isCreatureCurrent);
        // The E.9 bucket is an independent presentation draw: choose Hmph/the owner's F03 line here.
        NativeRecoveryRootFixture.set(f.director, "random", new Random(0L)
        {
            @Override public int nextInt(int bound) { return 0; }
        });
        f.showOrigin(); f.baseline(); return f;
    }
    private static void startOutfit(NativeRecoveryRootFixture f, String line) throws Exception
    {
        f.observer.setScenarioEnabled(id -> "M09".equals(id));
        f.director.setLineValidator((candidate, selected) -> !"M09".equals(candidate.getScenarioId()) || line.equals(selected));
        ItemContainer equipment = mock(ItemContainer.class); Item[] items = new Item[8]; items[4] = new Item(100, 1);
        when(equipment.getItems()).thenReturn(items); when(f.client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
        f.plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment)); f.commit(); f.commit();
        assertNotNull(f.director.getPacket()); assertEquals(line, f.director.getPacket().getLineId());
    }
    private static SceneCandidate startFountain(NativeRecoveryRootFixture f) throws Exception
    {
        MalfoidConfig config = (MalfoidConfig) NativeRecoveryRootFixture.get(f.plugin, "config"); when(config.showDialogue()).thenReturn(false);
        Method probe = MalfoidPlugin.class.getDeclaredMethod("inspectionTarget", f.session.getClass(), String.class, String.class,
            int.class, int.class, int.class, int.class, int.class); probe.setAccessible(true);
        f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) ->
        {
            try { return (ScenarioSnapshot.InspectionTarget) probe.invoke(f.plugin, f.session, key, name, minX, minY, maxX, maxY, plane); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        Tile tile = mock(Tile.class); GameObject fountain = mock(GameObject.class);
        f.scene.getTiles()[0][54][50] = tile; when(tile.getGameObjects()).thenReturn(new GameObject[]{fountain});
        when(fountain.getId()).thenReturn(40001); when(fountain.getWorldView()).thenReturn(f.view); when(fountain.getPlane()).thenReturn(0);
        when(fountain.getSceneMinLocation()).thenReturn(new net.runelite.api.Point(54, 50));
        when(fountain.getSceneMaxLocation()).thenReturn(new net.runelite.api.Point(54, 50));
        ObjectComposition definition = mock(ObjectComposition.class); when(definition.getName()).thenReturn("Fountain");
        when(f.client.getObjectDefinition(40001)).thenReturn(definition); f.flags[54][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        GameObjectSpawned spawned = new GameObjectSpawned(); spawned.setTile(tile); spawned.setGameObject(fountain); f.plugin.onGameObjectSpawned(spawned);
        for (int i = 0; f.director.getActiveCandidate() == null && i < 8; i++) { f.commit(); }
        SceneCandidate candidate = f.director.getActiveCandidate(); assertNotNull(candidate); return candidate;
    }
    private static RouteMotion motion(NativeRecoveryRootFixture f) throws Exception
    { return (RouteMotion) NativeRecoveryRootFixture.get(f.actor, "motion"); }
    private static boolean turnAway(NativeRecoveryRootFixture f) throws Exception
    { return (boolean) NativeRecoveryRootFixture.get(f.actor, "turnAway"); }
    private static void assertFaces(NativeRecoveryRootFixture f, int x, int y, boolean away)
    {
        LocalPoint p = f.actor.getLocation(); int desired = MotionMath.bearing(x * 128 + 64 - p.getX(), y * 128 + 64 - p.getY());
        if (away) { desired = (desired + 1024) & 2047; }
        assertTrue("Native orientation matches the authored facing", MotionMath.angleDifference(desired, f.actor.getOrientation()) <= 32);
    }
    private static boolean farSide(NativeRecoveryRootFixture f, NativeNpc creature)
    {
        LocalPoint p = f.actor.getLocation(); return CreatureAwareness.farSide(p.getSceneX(), p.getSceneY(), f.playerX, f.playerY, creature.x, creature.y);
    }
    private static void runStep(NativeRecoveryRootFixture f, int step) throws Exception
    {
        clearReplayMockHistory(f);
        // Stay inside one actual region so an unrelated M10 arrival cannot acquire motion.
        int leg = step / 10 % 4; f.playerX += leg == 0 ? -2 : leg == 2 ? 2 : 0;
        f.playerY += leg == 1 ? -2 : leg == 3 ? 2 : 0;
    }
    private static void clearReplayMockHistory(NativeRecoveryRootFixture f) throws Exception
    {
        // The long replay checks actual fields, motion and receipts, rather than recorded mock calls.
        // Preserve stubs while keeping thirty client-cycle calls per commit bounded in the fixture.

        for (net.runelite.api.CollisionData collision : f.view.getCollisionMaps()) { clearInvocations(collision); }
        NativePoseBank bank = (NativePoseBank) NativeRecoveryRootFixture.get(f.actor, "bank");
        for (Model model : (Model[]) NativeRecoveryRootFixture.get(bank, "models"))
        {
            if (model != null) { clearInvocations(model); }
        }
        clearInvocations(f.client.getItemContainer(InventoryID.INV), f.client.getItemContainer(InventoryID.WORN));
        clearInvocations(f.client, f.clientThread, f.player, f.view, f.scene);
    }
    private static void creatures(NativeRecoveryRootFixture f, NativeNpc... copies)
    {
        List<NPC> npcs = new ArrayList<>();
        for (NativeNpc copy : copies) { npcs.add(copy.npc); f.plugin.onNpcSpawned(new NpcSpawned(copy.npc)); }
        doReturn(indexed(npcs)).when(f.view).npcs();
    }
    private static <T> IndexedObjectSet<T> indexed(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
    private static final class NativeNpc
    {
        final NPC npc = mock(NPC.class); final NPCComposition definition = mock(NPCComposition.class);
        int x, y, plane; boolean dead;
        NativeNpc(NativeRecoveryRootFixture f, String name, int x, int y, int level)
        {
            this.x = x; this.y = y;
            when(definition.getName()).thenReturn(name); when(definition.getCombatLevel()).thenReturn(level); when(definition.getSize()).thenReturn(1);
            when(npc.getTransformedComposition()).thenReturn(definition); when(npc.getComposition()).thenReturn(definition);
            when(npc.getWorldView()).thenReturn(f.view); when(npc.isDead()).thenAnswer(call -> dead);
            when(npc.getLocalLocation()).thenAnswer(call -> NativeRecoveryRootFixture.point(this.x, this.y));
            when(npc.getWorldLocation()).thenAnswer(call -> new WorldPoint(3200 + this.x, 3200 + this.y, plane));
        }
    }
}
