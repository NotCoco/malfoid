package com.malfoid.asset;

import com.google.gson.Gson;
import com.malfoid.motion.MotionMath;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RouteMotion;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.SkullIcon;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.WorldType;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NativeActorTest
{
    @Test public void aFreshSuccessfulZeroStepApproachHasPositiveSceneTranslationEvidence() throws Exception
    {
        PlacementFixture f = new PlacementFixture(); f.actor.onGameTick(true, 1);
        NativeActor.SceneTranslationSnapshot snapshot = f.actor.captureSceneTranslation(700);
        assertNotNull(snapshot);
        f.actor.requestApproachPlayer(1, 8, false); f.actor.onGameTick(true, 2);
        assertEquals(com.malfoid.motion.RoutePlanner.Status.AT_GOAL, f.actor.getLastPlannerStatus());
        assertTrue(f.actor.hasArrived()); assertNull(f.actor.getMotionObservation());
        assertTrue(f.actor.hasNotTranslated(snapshot, 700));
        f.actor.hold(); f.actor.turnAway();
        for (int cycle = 1; cycle <= 16; cycle++) f.tick(cycle);
        assertTrue("turning/holding changes no position", f.actor.hasNotTranslated(snapshot, 700));
        assertFalse(f.actor.hasNotTranslated(snapshot, 701)); assertFalse(f.actor.hasNotTranslated(null, 700));
    }

    @Test public void sceneTranslationEvidenceCountsInterpolationBeforeAnyCompletedEdgeAndNeverRewinds() throws Exception
    {
        PlacementFixture f = new PlacementFixture(); f.actor.onGameTick(true, 1);
        NativeActor.SceneTranslationSnapshot snapshot = f.actor.captureSceneTranslation(700);
        LocalPoint origin = f.actor.getLocation();
        RouteMotion motion = (RouteMotion) field(NativeActor.class, "motion").get(f.actor);
        long edges = motion.getCompletedEdges();
        f.actor.requestDestination(3200 + origin.getSceneX() + 1, 3200 + origin.getSceneY());
        f.actor.onGameTick(true, 2); f.tick(1);
        assertEquals(edges, motion.getCompletedEdges()); assertTrue(f.actor.isMoving());
        assertFalse("the first interpolation already translated", f.actor.hasNotTranslated(snapshot, 700));
        for (int cycle = 2; cycle <= 30; cycle++) f.tick(cycle);
        f.actor.requestDestination(3200 + origin.getSceneX(), 3200 + origin.getSceneY());
        f.actor.onGameTick(true, 3);
        for (int cycle = 31; cycle <= 60; cycle++) f.tick(cycle);
        assertEquals(origin, f.actor.getLocation());
        assertFalse("returning to the origin cannot erase real movement", f.actor.hasNotTranslated(snapshot, 700));
        NativeActor.SceneTranslationSnapshot fresh = f.actor.captureSceneTranslation(702);
        assertNotNull(fresh); assertTrue(f.actor.hasNotTranslated(fresh, 702));
    }

    @Test public void runningInterpolationAlsoInvalidatesTheSceneBeforeItsFirstCompletedEdge() throws Exception
    {
        PlacementFixture f = new PlacementFixture(); f.actor.onGameTick(true, 1);
        NativeActor.SceneTranslationSnapshot snapshot = f.actor.captureSceneTranslation(700);
        LocalPoint origin = f.actor.getLocation();
        RouteMotion motion = (RouteMotion) field(NativeActor.class, "motion").get(f.actor);
        long edges = motion.getCompletedEdges();
        f.actor.requestDestination(3200 + origin.getSceneX() + 1, 3200 + origin.getSceneY(), true);
        f.actor.onGameTick(true, 2); f.tick(1);
        assertEquals("RUN", f.actor.getLastClip()); assertEquals(edges, motion.getCompletedEdges());
        assertFalse(f.actor.hasNotTranslated(snapshot, 700));
    }

    @Test public void sceneTranslationEvidenceFailsForUnknownOrChangedPlacementAndSession() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        assertNull(f.actor.captureSceneTranslation(700));
        f.actor.onGameTick(true, 1);
        NativeActor.SceneTranslationSnapshot snapshot = f.actor.captureSceneTranslation(700);
        assertTrue(f.actor.hasNotTranslated(snapshot, 700)); assertNull(f.actor.captureSceneTranslation(-1));
        when(f.view.getTileHeights()).thenReturn(null);
        assertNull(f.actor.captureSceneTranslation(700)); assertFalse(f.actor.hasNotTranslated(snapshot, 700));
        when(f.view.getTileHeights()).thenReturn(f.heights);
        f.actor.hide(); assertFalse(f.actor.hasNotTranslated(snapshot, 700));
        f.actor.onGameTick(true, 2); assertTrue(f.actor.isVisible());
        assertFalse("same scene after re-placement is a new scope", f.actor.hasNotTranslated(snapshot, 700));
        NativeActor.SceneTranslationSnapshot current = f.actor.captureSceneTranslation(701);
        assertNotNull(current);
        PlacementFixture other = new PlacementFixture(); other.actor.onGameTick(true, 1);
        assertFalse(other.actor.hasNotTranslated(current, 701));
        when(f.view.getScene()).thenReturn(mock(Scene.class));
        assertFalse(f.actor.hasNotTranslated(current, 701));
        f.actor.resetSession(); assertFalse(f.actor.hasNotTranslated(current, 701));
    }

    @Test public void skippedCallbacksAnchorTheLoopToItsFirstSuccessfullyPublishedSample() throws Exception
    {
        for (boolean missingHeight : new boolean[]{false, true})
        {
            PlacementFixture f = new PlacementFixture(); f.installEmoteModels();
            f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
            f.tick(53); assertTrue(f.actor.startImpatient(8));
            f.actor.onGameTick(true, 4);
            if (missingHeight) when(f.view.getTileHeights()).thenReturn(null);
            f.tick(130);
            if (missingHeight)
            {
                assertFalse(f.actor.isVisible()); assertEquals(-1, f.actor.consumeImpatientLoopStartTick());
                assertEquals(Integer.MIN_VALUE, f.actor.getImpatientLoopStartCycle());
                assertEquals(4, f.actor.consumeImpatientEndTick()); assertEquals(130, f.actor.getImpatientEndCycle());
            }
            else
            {
                assertSame(f.actor.getPoseModel("IMPATIENT", 0), f.actor.getModel());
                assertEquals(130, f.actor.getImpatientLoopStartCycle());
                assertEquals(4, f.actor.consumeImpatientLoopStartTick()); assertEquals(-1, f.actor.consumeImpatientLoopStartTick());
                f.tick(369); assertEquals("IMPATIENT", f.actor.getLastClip());
                assertEquals(-1, f.actor.consumeImpatientEndTick());
                f.tick(370); assertSame(f.actor.getPoseModel("CROSS_ARMS", 9), f.actor.getModel());
                assertEquals(370, f.actor.getImpatientEndCycle()); assertEquals(4, f.actor.consumeImpatientEndTick());
            }
        }
    }

    @Test public void expiredStrollDeadlineUnderIdleSuppressionStillAllowsWaiting() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
        f.actor.setIdleMovementSuppressed(true);
        f.actor.onGameTick(true, 500);
        assertFalse(f.actor.isMoving()); assertTrue(f.actor.isStandingInRest());
        assertTrue(f.actor.startImpatient(8));
    }

    @Test public void aStationaryBankHoldAllowsWaitingWhileMotionScenesAndTransitionsDoNot() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
        net.runelite.api.widgets.Widget bank = mock(net.runelite.api.widgets.Widget.class);
        when(bank.isHidden()).thenReturn(false);
        when(f.client.getWidget(net.runelite.api.gameval.InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
        f.actor.onGameTick(true, 4);
        for (int cycle = 1; cycle <= 90; cycle++) f.tick(cycle);
        assertTrue(f.actor.isInteractionHolding()); assertFalse(f.actor.isMoving());
        assertTrue(f.actor.isStandingInRest()); assertTrue(f.actor.startImpatient(8));

        PlacementFixture scene = new PlacementFixture();
        scene.actor.onGameTick(true, 1); scene.actor.onGameTick(true, 2); scene.actor.onGameTick(true, 3);
        scene.actor.hold(); assertFalse(scene.actor.isStandingInRest()); assertFalse(scene.actor.startImpatient(8));
        scene.actor.clearIntent();
        assertTrue(scene.actor.foldArms(Long.MAX_VALUE));
        assertFalse(scene.actor.isStandingInRest()); assertFalse(scene.actor.startImpatient(8));

        PlacementFixture moving = new PlacementFixture();
        moving.actor.onGameTick(true, 1); moving.actor.onGameTick(true, 2); moving.actor.onGameTick(true, 3);
        LocalPoint location = moving.actor.getLocation();
        moving.actor.requestDestination(3200 + location.getSceneX() + 1, 3200 + location.getSceneY());
        moving.actor.onGameTick(true, 4);
        assertTrue(moving.actor.isMoving()); assertFalse(moving.actor.isStandingInRest());
        assertFalse(moving.actor.startImpatient(8));
    }

    @Test public void emotePosesArePaintedWithoutChangingFacingAndMovementCutsOnThatCycle() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.installEmoteModels();
        f.actor.onGameTick(true, 1);
        long generation = f.actor.getPlacementGeneration();
        int facing = f.actor.getOrientation();
        assertTrue(f.actor.foldArms(Long.MAX_VALUE));
        assertSame(f.actor.getPoseModel("CROSS_ARMS", 0), f.actor.getModel());
        for (int cycle = 1; cycle < 20; cycle++)
        {
            f.tick(cycle);
            assertSame(f.actor.getPoseModel("CROSS_ARMS", cycle / 2), f.actor.getModel());
            assertEquals(facing, f.actor.getOrientation());
        }
        f.tick(20);
        assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        assertSame(f.actor.getPoseModel("CROSS_ARMS", 9), f.actor.getModel());
        f.actor.hold(); f.actor.turnAway();
        for (int cycle = 21; cycle <= 40; cycle++) f.tick(cycle);
        assertNotEquals(facing, f.actor.getOrientation());
        assertEquals(PosturePlayback.Posture.ARMS_FOLDED, f.actor.getPosture());
        LocalPoint location = f.actor.getLocation();
        f.actor.requestDestination(3200 + location.getSceneX() + 1, 3200 + location.getSceneY());
        f.actor.onGameTick(true, 2); f.tick(41);
        assertEquals("WALK", f.actor.getLastClip()); assertSame(f.walk, f.actor.getModel());
        assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
        assertEquals(generation, f.actor.getPlacementGeneration());
        for (int cycle = 42; cycle <= 70; cycle++) f.tick(cycle);
        assertEquals("IDLE", f.actor.getLastClip());
        assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
    }

    @Test public void actualLoopEntryAnchorsDurationAndLengthensTheExistingRest() throws Exception
    {
        for (int duration : new int[]{9, 16})
        {
            PlacementFixture f = new PlacementFixture(); f.installEmoteModels();
            f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
            f.tick(53); assertTrue(f.actor.isStandingInRest());
            assertTrue(f.actor.startImpatient(duration));
            assertEquals(duration, f.actor.getImpatientDurationTicks());
            assertEquals(-1, f.actor.consumeImpatientLoopStartTick());
            int loop = 73, end = loop + (duration * 30 + 39) / 40 * 40;
            long tick = 3;
            for (int cycle = 54; cycle < loop; cycle++) f.tick(cycle);
            assertEquals(-1, f.actor.consumeImpatientLoopStartTick()); f.tick(loop);
            assertSame(f.actor.getPoseModel("IMPATIENT", 0), f.actor.getModel());
            assertEquals(loop, f.actor.getImpatientLoopStartCycle());
            assertEquals(3, f.actor.consumeImpatientLoopStartTick()); assertEquals(-1, f.actor.consumeImpatientLoopStartTick());
            for (int cycle = loop + 1; cycle <= end + 20; cycle++)
            {
                if (cycle % 30 == 0) f.actor.onGameTick(true, ++tick);
                f.tick(cycle); assertFalse("rest covers the complete posture", f.actor.isMoving());
                if (cycle < end) assertEquals("IMPATIENT", f.actor.getLastClip());
            }
            assertEquals(end, f.actor.getImpatientEndCycle());
            assertEquals(3 + duration, f.actor.consumeImpatientEndTick()); assertEquals(-1, f.actor.consumeImpatientEndTick());
            assertEquals("IDLE", f.actor.getLastClip());
            assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
            assertTrue(end - loop <= 16 * 30);
        }
    }

    @Test public void stillStandingCancellationLeavesOnlyAtTheLoopBoundary() throws Exception
    {
        PlacementFixture f = new PlacementFixture(); f.installEmoteModels();
        f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
        f.tick(30); assertTrue(f.actor.startImpatient(8));
        f.tick(50); assertEquals(3, f.actor.consumeImpatientLoopStartTick());
        f.tick(56); f.actor.endImpatient();
        assertEquals("IMPATIENT", f.actor.getLastClip()); assertEquals(-1, f.actor.consumeImpatientEndTick());
        f.tick(89); assertEquals("IMPATIENT", f.actor.getLastClip());
        f.tick(90); assertSame(f.actor.getPoseModel("CROSS_ARMS", 9), f.actor.getModel());
        assertEquals(90, f.actor.getImpatientEndCycle()); assertEquals(3, f.actor.consumeImpatientEndTick());
        f.tick(110); assertEquals("IDLE", f.actor.getLastClip());
    }

    @Test public void hidingAPendingEmotePublishesOneEndAndNeverALoopStart() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1); f.actor.onGameTick(true, 2); f.actor.onGameTick(true, 3);
        assertTrue(f.actor.startImpatient(8)); f.tick(7); f.actor.hide();
        assertEquals(3, f.actor.consumeImpatientEndTick()); assertEquals(7, f.actor.getImpatientEndCycle());
        assertEquals(-1, f.actor.consumeImpatientLoopStartTick());
        f.actor.hide(); assertEquals(-1, f.actor.consumeImpatientEndTick());
        assertFalse(f.actor.isVisible()); assertEquals(PosturePlayback.Posture.STANDING, f.actor.getPosture());
    }

    @Test public void aValidatedThreeClipAssetHasNoEmotesOrNewMovementBehaviour() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        com.google.gson.JsonObject source = NativeEmoteAssetTest.withoutEmotes(NativeEmoteAssetTest.bake());
        source.addProperty("assetRevision", "claude07");
        NativeAsset asset = NativeEmoteAssetTest.decode(source);
        field(NativeActor.class, "decodedAsset").set(f.actor, asset);
        NativePoseBank bank = new NativePoseBank(f.client, asset);
        Model[] models = (Model[]) field(NativePoseBank.class, "models").get(bank);
        models[0] = f.idle; Arrays.fill(models, 1, 51, f.idle);
        Arrays.fill(models, 51, 81, f.walk); Arrays.fill(models, 81, 101, f.run);
        field(NativePoseBank.class, "readyAt").setLong(bank, 1); field(NativeActor.class, "bank").set(f.actor, bank);
        f.actor.onGameTick(true, 1);
        assertFalse(f.actor.hasEmotes()); assertFalse(f.actor.foldArms(Long.MAX_VALUE));
        assertFalse(f.actor.startImpatient(8)); assertEquals(100, f.actor.getPoseCount());
        assertSame(f.idle, f.actor.getModel());
        LocalPoint location = f.actor.getLocation();
        f.actor.requestDestination(3200 + location.getSceneX() + 1, 3200 + location.getSceneY());
        f.actor.onGameTick(true, 2); f.tick(1);
        assertSame(f.walk, f.actor.getModel()); assertEquals("WALK", f.actor.getLastClip());
    }

    @Test public void preparationDoesOneDonorChunkPerDistinctClientCycle() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameCycle()).thenReturn(10);
        NativeActor actor = new NativeActor(client, new Gson());
        actor.loadResources();
        actor.onClientTick();
        actor.onClientTick();
        verify(client, times(1)).loadModelData(823);
        verify(client, times(1)).loadModelData(6733);
        verify(client, times(1)).loadModelData(18871);
        assertFalse(actor.isReady());
        assertFalse(actor.isVisible());
        when(client.getGameCycle()).thenReturn(11);
        actor.onClientTick();
        verify(client, times(2)).loadModelData(823);
        actor.dispose();
        when(client.getGameCycle()).thenReturn(12);
        actor.onClientTick();
        verify(client, times(2)).loadModelData(823);
        assertEquals(0, actor.getPreparedModelCount());
    }

    @Test public void hardSessionResetReleasesTheOldBankAndRetriesWithTheValidatedBake() throws Exception
    {
        Client client = mock(Client.class);
        when(client.getGameCycle()).thenReturn(1);
        NativeActor actor = new NativeActor(client, new Gson());
        actor.loadResources();
        actor.onClientTick();
        verify(client, times(1)).loadModelData(823);
        actor.resetSession();
        assertFalse(actor.isReady());
        assertEquals(0, actor.getPoseCount());
        when(client.getGameCycle()).thenReturn(2);
        actor.onClientTick();
        verify(client, times(2)).loadModelData(823);
        assertEquals(204, actor.getPoseCount());
        actor.dispose();
    }

    @Test public void loggedOutHideAndDisposeAreIdempotentAndNeverRegister()
    {
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        NativeActor actor = new NativeActor(client, new Gson());
        actor.onGameTick(true);
        actor.hide();
        actor.hide();
        actor.dispose();
        actor.dispose();
        assertFalse(actor.isVisible());
        assertNull(actor.getPlacementSnapshot());
        assertNull(actor.getClickbox());
        assertEquals(0, actor.getPlacementGeneration());
        verify(client, never()).registerRuneLiteObject(any());
        verify(client, never()).removeRuneLiteObject(any());
    }

    @Test public void firstPlacementRegistersTheCompleteRecipeAndPoseSwapsKeepGeneration() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true);
        assertTrue(f.actor.isVisible());
        NativeActor.PlacementSnapshot placed = f.actor.getPlacementSnapshot();
        assertSame(f.view, placed.getWorldView());
        assertSame(f.scene, placed.getScene());
        assertEquals(0, placed.getPlane());
        assertEquals(120, placed.getDrawZ());
        assertEquals(f.idle.getModelHeight(), placed.getHeadHeight());
        assertEquals(173, placed.getHeadHeight());
        int x = placed.getLocation().getSceneX(), y = placed.getLocation().getSceneY();
        assertEquals(MotionMath.quantize(MotionMath.bearing(50 - x, 50 - y)), placed.getOrientation());
        assertSame(f.idle, placed.getModel());
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(f.client).registerRuneLiteObject(registered.capture());
        RuneLiteObjectController controller = registered.getValue();
        assertEquals(placed.getLocation(), controller.getLocation());
        assertEquals(0, controller.getLevel());
        assertEquals(120, controller.getZ());
        assertEquals(placed.getOrientation(), controller.getOrientation());
        assertSame(placed.getModel(), controller.getModel());

        long generation = placed.getPlacementGeneration();
        f.actor.requestDestination(3200 + x + 1, 3200 + y);
        f.actor.onGameTick(true);
        f.tick(1);
        assertSame(f.walk, f.actor.getModel());
        assertFalse(f.actor.hasArrived());
        assertEquals(generation, f.actor.getPlacementGeneration());
        for (int cycle = 2; cycle <= 30; cycle++) f.tick(cycle);
        assertTrue(f.actor.hasArrived());
        assertSame(f.idle, f.actor.getModel());
        assertEquals(x + 1, f.actor.getLocation().getSceneX());
        assertEquals(generation, f.actor.getPlacementGeneration());
        f.actor.holdPosition();
        assertTrue(f.actor.hasArrived());
        f.actor.hide();
        f.actor.hide();
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getPlacementSnapshot());
        assertNull(controller.getModel());
        verify(f.client, times(1)).removeRuneLiteObject(controller);
    }

    @Test public void aMissingCandidateBridgePlaneNeverRegistersAfterThePlayerHeightPassed() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        int[][][] onlyGroundPlane = {new int[105][105]};
        for (int[] row : onlyGroundPlane[0]) Arrays.fill(row, 120);
        for (byte[] row : f.settings[1]) Arrays.fill(row, (byte) 2);
        f.settings[1][50][50] = 0;
        when(f.view.getTileHeights()).thenReturn(onlyGroundPlane);
        f.actor.onGameTick(true);
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getPlacementSnapshot());
        verify(f.client, never()).registerRuneLiteObject(any());
        verify(f.client, never()).removeRuneLiteObject(any());
    }

    @Test public void missingHeightsAndMismatchedWorldViewLookupPreventRegistration() throws Exception
    {
        PlacementFixture missing = new PlacementFixture();
        when(missing.view.getTileHeights()).thenReturn(null);
        missing.actor.onGameTick(true);
        verify(missing.client, never()).registerRuneLiteObject(any());
        PlacementFixture lookup = new PlacementFixture();
        when(lookup.client.getWorldView(-1)).thenReturn(mock(WorldView.class));
        lookup.actor.onGameTick(true);
        verify(lookup.client, never()).registerRuneLiteObject(any());
    }

    @Test public void bridgeHeightIsUsedAndSceneChangeRemovesTheRegisteredController() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        for (byte[] row : f.settings[1]) Arrays.fill(row, (byte) 2);
        for (int[] row : f.heights[1]) Arrays.fill(row, 360);
        f.actor.onGameTick(true);
        assertEquals(360, f.actor.getPlacementSnapshot().getDrawZ());
        when(f.view.getScene()).thenReturn(mock(Scene.class));
        f.tick(1);
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getPlacementSnapshot());
        verify(f.client).removeRuneLiteObject(any());
    }

    @Test public void confirmedCombatRestsOutsideTheFootprintAndDoesNotRechooseWithinEightTicks() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true);
        f.actor.setCombatTarget(true, new LocalPoint(51 * 128 + 64, 50 * 128 + 64, -1), 3);
        f.actor.onGameTick(true);
        for (int cycle = 1; cycle <= 90; cycle++) f.tick(cycle);
        assertFalse(f.actor.isMoving());
        LocalPoint resting = f.actor.getLocation();
        int separation = MotionMath.distance(resting.getSceneX(), resting.getSceneY(), 50, 50);
        assertTrue(separation >= 3 && separation <= 6);
        assertTrue(MotionMath.footprintDistance(resting.getSceneX(), resting.getSceneY(), 50, 49, 52, 51) > 1);
        assertEquals(2, f.actor.getPlanningCount());
        for (int tick = 0; tick < 7; tick++) f.actor.onGameTick(true);
        assertEquals(2, f.actor.getPlanningCount());
        f.actor.onGameTick(true);
        assertEquals(3, f.actor.getPlanningCount());
    }

    @Test public void sceneRunBurstIsCappedAtSixCommittedTicksAndDemotesTheNextEdge() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true);
        LocalPoint start = f.actor.getLocation();
        f.actor.requestDestination(3200 + start.getSceneX() + 6, 3200 + start.getSceneY(), true);
        f.actor.limitRunBurst(6);
        for (int tick = 1; tick <= 6; tick++)
        {
            f.actor.onGameTick(true);
            f.tick(tick);
            assertEquals("RUN", f.actor.getLastClip());
        }
        f.actor.onGameTick(true);
        f.tick(7);
        assertEquals("RUN", f.actor.getLastClip());
        for (int cycle = 8; cycle <= 16; cycle++) f.tick(cycle);
        assertEquals("WALK", f.actor.getLastClip());
        assertSame(f.walk, f.actor.getModel());
        assertEquals(1, f.actor.getMotionObservation().completedEdges);
        assertEquals(1, f.actor.getMotionObservation().completedRunEdges);
    }

    @Test public void inspectionPublishesAnActualNextTickRouteAndInvalidatesChangedCollision() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true);
        f.actor.onGameTick(true);
        f.actor.onGameTick(true);
        LocalPoint start = f.actor.getLocation();
        int objectX = 3200 + start.getSceneX() + 9, objectY = 3200 + start.getSceneY();
        assertTrue(f.actor.requestInspectionProbe("object:1", objectX, objectY, objectX, objectY, 0));
        assertFalse(f.actor.requestInspectionProbe("object:2", objectX, objectY, objectX, objectY, 0));
        assertNull(f.actor.getInspectionRouteProof());
        f.actor.onGameTick(true);
        NativeActor.InspectionRouteProof proof = f.actor.getInspectionRouteProof();
        assertNotNull(proof);
        assertEquals("object:1", proof.getKey());
        assertEquals(8, proof.getPathSteps());
        assertSame(f.view, proof.getWorldView());
        assertSame(f.scene, proof.getScene());
        assertEquals(f.actor.getPlacementGeneration(), proof.getPlacementGeneration());
        assertTrue(f.actor.isInspectionProofCurrent(proof));
        assertFalse(f.actor.isMoving());
        assertTrue(f.actor.getLastPlannerVisited() <= 625);
        f.flags[proof.getStandWorldX() - 3200][proof.getStandWorldY() - 3200] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        assertFalse(f.actor.isInspectionProofCurrent(proof));
        f.actor.hide();
        assertNull(f.actor.getInspectionRouteProof());
    }

    @Test public void committedRootTicksPublishActualArrivalAndIgnoreDuplicateDecisions() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 101);
        LocalPoint start = f.actor.getLocation();
        int goalX = 3200 + start.getSceneX() + 1, goalY = 3200 + start.getSceneY();
        f.actor.requestDestination(goalX, goalY);
        f.actor.onGameTick(true, 102);
        MotionObservation accepted = f.actor.getMotionObservation();
        assertNotNull(accepted);
        assertEquals(102, accepted.startedTick);
        assertEquals(0, accepted.completedEdges);
        f.actor.onGameTick(true, 102);
        f.actor.onGameTick(true, 101);
        assertEquals(2, f.actor.getPlanningCount());
        for (int cycle = 1; cycle <= 30; cycle++) f.tick(cycle);
        assertNull(f.actor.getLatestMotionCompletion());
        assertEquals(1, f.actor.getMotionObservation().completedEdges);
        f.actor.onGameTick(true, 103);
        MotionObservation receipt = f.actor.getLatestMotionCompletion();
        assertNotNull(receipt);
        assertEquals(MotionObservation.Status.ARRIVED, receipt.status);
        assertEquals(103, receipt.completedTick);
        assertEquals(103, receipt.lastCompletedEdgeTick);
        assertEquals(goalX, receipt.goalWorldX);
        assertEquals(goalY, receipt.goalWorldY);
        assertEquals(1, receipt.completedEdges);
        assertEquals(0, receipt.completedRunEdges);
        assertTrue(receipt.legalGoal);
        assertFalse(receipt.ordinary);
        assertEquals(MotionObservation.Status.ARRIVED, f.actor.getMotionObservation().status);
        f.actor.hide();
        assertNull(f.actor.getLatestMotionCompletion());
        assertNull(f.actor.getMotionObservation());
    }

    @Test public void acceptedEdgeChangingToBlockedProducesPhysicalEvidenceButMissingCellsDoNot() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint start = f.actor.getLocation();
        int goalX = start.getSceneX() + 1, goalY = start.getSceneY();
        f.actor.requestDestination(3200 + goalX, 3200 + goalY);
        f.actor.onGameTick(true, 2);
        f.flags[goalX][goalY] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        f.tick(1);
        MotionObservation blocked = f.actor.getMotionObservation();
        assertEquals(MotionObservation.Status.BLOCKED, blocked.status);
        assertTrue(blocked.physicalBlock);
        assertTrue(blocked.previouslyLegalEdge);
        assertEquals(0, blocked.completedEdges);
        assertEquals(3, blocked.blockedTick);
        assertFalse(f.actor.isMoving());

        PlacementFixture unknown = new PlacementFixture();
        unknown.actor.onGameTick(true);
        LocalPoint origin = unknown.actor.getLocation();
        unknown.actor.requestDestination(3200 + origin.getSceneX() + 1, 3200 + origin.getSceneY());
        unknown.actor.onGameTick(true);
        unknown.flags[origin.getSceneX() + 1] = null;
        unknown.tick(1);
        assertFalse(unknown.actor.isVisible());
        assertNull(unknown.actor.getMotionObservation());
    }

    @Test public void inspectionCancelsInsteadOfTakingALaterDetourBeyondItsAdmittedEightEdges() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true);
        f.actor.onGameTick(true);
        f.actor.onGameTick(true);
        LocalPoint origin = f.actor.getLocation();
        int objectX = 3200 + origin.getSceneX() + 9, objectY = 3200 + origin.getSceneY();
        assertTrue(f.actor.requestInspectionProbe("inspection:budget", objectX, objectY, objectX, objectY, 0));
        f.actor.onGameTick(true);
        NativeActor.InspectionRouteProof proof = f.actor.getInspectionRouteProof();
        assertEquals(8, proof.getPathSteps());
        assertTrue(f.actor.requestInspectionDestination(proof.getStandWorldX(), proof.getStandWorldY()));
        assertTrue(f.actor.isInspectionDestinationValid());
        Arrays.fill(f.flags[origin.getSceneX() + 1], CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        f.flags[origin.getSceneX() + 1][origin.getSceneY() + 10] = 0;
        f.actor.onGameTick(true);
        assertFalse(f.actor.isInspectionDestinationValid());
        assertFalse(f.actor.isMoving());
        assertFalse(f.actor.hasArrived());
        assertTrue(f.actor.getLastPlannerSteps() > 8);
        assertTrue(f.actor.getLastPlannerVisited() <= 625);
    }

    @Test public void onlyAnExactAdmissionCanPreserveItsGenuineArrivalThroughOwnedHold() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        long plansBeforeHold = f.actor.getPlanningCount();
        assertTrue(f.actor.admitMotionScene(700, original.requestId));
        assertTrue(f.actor.clearIntent(700));
        assertEquals(MotionObservation.Status.INVALIDATED, f.actor.getMotionObservation().status);
        assertTrue(f.actor.holdPosition(700));
        NativeActor.PlacementSnapshot before = f.actor.getPlacementSnapshot();
        for (int i = 0; i < 10; i++)
        {
            MotionHoldReceipt hold = f.actor.getMotionHoldReceipt(700);
            assertNotNull(hold);
            assertSame(original, hold.completion);
            assertEquals(original.requestId, hold.requestId);
            assertEquals(700, hold.candidateId);
            assertTrue(hold.legalStanding);
            assertEquals(MotionHoldReceipt.Mode.HOLD, hold.mode);
            assertEquals(3200 + before.getLocation().getSceneX(), hold.worldX);
            assertEquals(3200 + before.getLocation().getSceneY(), hold.worldY);
            assertEquals(before.getPlacementGeneration(), hold.placementGeneration);
            assertNull(f.actor.getMotionHoldReceipt(701));
        }
        assertSame(before, f.actor.getPlacementSnapshot());
        assertSame(before.getModel(), f.actor.getModel());
        f.actor.onGameTick(true, 4);
        assertNotNull(f.actor.getMotionHoldReceipt(700));
        assertEquals(plansBeforeHold, f.actor.getPlanningCount());
        f.actor.clearIntent();
        assertNull(f.actor.getMotionHoldReceipt(700));
        assertFalse(f.actor.admitMotionScene(701, original.requestId));
    }

    @Test public void genericForeignAndRecoveredPlacementControlsCannotBecomeOwnedHoldProof() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        assertTrue(f.actor.admitMotionScene(800, original.requestId));
        assertTrue(f.actor.holdPosition(800));
        assertFalse(f.actor.holdPosition(801));
        assertNull(f.actor.getMotionHoldReceipt(800));
        assertFalse(f.actor.clearIntent(800));
        assertNull(f.actor.getMotionHoldReceipt(800));
        f.actor.hide();
        f.actor.onGameTick(true, 4);
        assertFalse(f.actor.admitMotionScene(800, original.requestId));
        assertNull(f.actor.getMotionHoldReceipt(800));
    }

    @Test public void ownedHoldMustStillHaveLegalCollisionBridgeHeightAndSceneIdentity() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        assertTrue(f.actor.admitMotionScene(900, original.requestId));
        assertTrue(f.actor.holdPosition(900));
        assertTrue(f.actor.hasMotionSceneArrived(900));
        LocalPoint stand = f.actor.getLocation();
        f.flags[stand.getSceneX()][stand.getSceneY()] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        assertNull(f.actor.getMotionHoldReceipt(900));
        assertFalse(f.actor.hasMotionSceneArrived(900));
        f.flags[stand.getSceneX()][stand.getSceneY()] = 0;
        f.settings[1][stand.getSceneX()][stand.getSceneY()] = 2;
        f.heights[1] = null;
        assertNull(f.actor.getMotionHoldReceipt(900));
        assertFalse(f.actor.hasMotionSceneArrived(900));
        f.settings[1][stand.getSceneX()][stand.getSceneY()] = 0;
        when(f.view.getScene()).thenReturn(mock(Scene.class));
        assertNull(f.actor.getMotionHoldReceipt(900));
        assertFalse(f.actor.hasMotionSceneArrived(900));
        f.tick(200);
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getMotionHoldReceipt(900));
    }

    @Test public void exactOwnedNormalResumeBridgesOnlyUntilTheNextNativeDecisionWithoutInventingArrival() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        assertTrue(f.actor.admitMotionScene(950, original.requestId));
        assertTrue(f.actor.holdPosition(950));
        assertTrue(f.actor.clearIntent(950));
        MotionHoldReceipt resume = f.actor.getMotionHoldReceipt(950);
        assertNotNull(resume);
        assertEquals(MotionHoldReceipt.Mode.NORMAL_RESUME, resume.mode);
        assertSame(original, resume.completion);
        assertEquals(3, resume.heldTick);
        assertEquals(MotionObservation.Status.INVALIDATED, f.actor.getMotionObservation().status);
        assertSame(original, f.actor.getLatestMotionCompletion());
        f.actor.onGameTick(true, 4);
        assertNull(f.actor.getMotionHoldReceipt(950));
        assertSame(original, f.actor.getLatestMotionCompletion());
        assertTrue(f.actor.holdPosition(950));
        assertEquals(MotionHoldReceipt.Mode.HOLD, f.actor.getMotionHoldReceipt(950).mode);
        f.actor.clearIntent();
        assertNull(f.actor.getMotionHoldReceipt(950));
    }

    @Test public void failedOffScreenRecoveryUsesTheSameBoundedPlanningBackoff() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        when(f.view.isTopLevel()).thenReturn(true);
        when(f.client.getViewportWidth()).thenReturn(800);
        when(f.client.getViewportHeight()).thenReturn(600);
        when(f.client.getScale()).thenReturn(512);
        when(f.client.getCameraY()).thenReturn(10000);
        f.actor.onGameTick(true, 1);
        long generation = f.actor.getPlacementGeneration();
        LocalPoint origin = f.actor.getLocation();
        for (int[] row : f.flags) Arrays.fill(row, CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        f.flags[origin.getSceneX()][origin.getSceneY()] = 0;
        f.flags[66][50] = 0;
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint(66 * 128 + 64, 50 * 128 + 64, -1));
        for (int tick = 2; tick <= 33; tick++) f.actor.onGameTick(true, tick);
        // Three ineffective catch-up searches, followed by suspension despite recovery eligibility.
        assertEquals(4, f.actor.getPlanningCount());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(generation, f.actor.getPlacementGeneration());
        f.actor.onGameTick(true, 34);
        // The first player-rooted recovery search also fails and starts the next bounded retry.
        assertEquals(5, f.actor.getPlanningCount());
        for (int tick = 35; tick <= 63; tick++) f.actor.onGameTick(true, tick);
        assertEquals(5, f.actor.getPlanningCount());
        f.actor.onGameTick(true, 64);
        assertEquals(6, f.actor.getPlanningCount());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(generation, f.actor.getPlacementGeneration());
        verify(f.client, never()).removeRuneLiteObject(any());
    }

    @Test public void aCurrentGenuineArrivalAcknowledgesOnlyItsExactAdmissionAndOwnedHold() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        assertFalse(f.actor.hasMotionSceneArrived(700));
        assertTrue(f.actor.admitMotionScene(700, original.requestId));
        long plans = f.actor.getPlanningCount();
        assertTrue(f.actor.hasMotionSceneArrived(700));
        assertFalse(f.actor.hasMotionSceneArrived(701));
        LocalPoint standing = f.actor.getLocation();
        f.flags[standing.getSceneX()][standing.getSceneY()] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        assertFalse(f.actor.hasMotionSceneArrived(700));
        f.flags[standing.getSceneX()][standing.getSceneY()] = 0;
        assertTrue(f.actor.holdPosition(700));
        assertTrue(f.actor.hasMotionSceneArrived(700));
        assertTrue(f.actor.clearIntent(700));
        assertTrue(f.actor.hasMotionSceneArrived(700));
        assertEquals(plans, f.actor.getPlanningCount());
        f.actor.onGameTick(true, 4);
        assertFalse(f.actor.hasMotionSceneArrived(700));
        assertTrue(f.actor.holdPosition(700));
        assertTrue(f.actor.hasMotionSceneArrived(700));
        f.actor.clearIntent();
        assertFalse(f.actor.hasMotionSceneArrived(700));
    }

    @Test public void aMovingOrForeignGoalCannotAcknowledgeTheEarlierScenesArrival() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation original = f.completeOrdinaryCatchup();
        assertTrue(f.actor.admitMotionScene(700, original.requestId));
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint(66 * 128 + 64, 50 * 128 + 64, -1));
        f.actor.onGameTick(true, 4);
        assertTrue(f.actor.isMoving());
        assertFalse(f.actor.hasMotionSceneArrived(700));
        f.actor.requestDestination(original.goalWorldX, original.goalWorldY);
        assertFalse(f.actor.hasMotionSceneArrived(700));
        f.actor.hide();
        assertFalse(f.actor.hasMotionSceneArrived(700));
    }

    @Test public void anExactUnarrivedInspectionStartsASeparateRealWalkingReturnAndOwnsItsArrival() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation inspection = f.startKeyedInspection(800);
        for (int cycle = 1; cycle <= 180; cycle++) f.tick(cycle);
        assertEquals(6, f.actor.getMotionObservation().completedEdges);
        assertFalse(f.actor.requestReturnFromInspection(801, inspection.requestId));
        assertFalse(f.actor.requestReturnFromInspection(800, inspection.requestId + 1));
        long plans = f.actor.getPlanningCount();
        long lastVoluntary = f.actor.getLastVoluntaryMovementTick();
        assertTrue(f.actor.requestReturnFromInspection(800, inspection.requestId));
        assertFalse(f.actor.isMoving());
        assertEquals(-1, f.actor.getInspectionReturnRequestId(800));
        assertEquals(plans, f.actor.getPlanningCount());
        f.actor.onGameTick(true, 6);
        MotionObservation returning = f.actor.getMotionObservation();
        assertEquals(plans + 1, f.actor.getPlanningCount());
        assertTrue(returning.known && returning.ordinary && returning.legalGoal);
        assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, returning.purpose);
        assertEquals(800, returning.sourceCandidateId);
        assertNotEquals(inspection.requestId, returning.requestId);
        assertEquals(returning.requestId, f.actor.getInspectionReturnRequestId(800));
        assertEquals(-1, f.actor.getInspectionReturnRequestId(801));
        assertFalse(f.actor.hasArrived());
        for (int cycle = 181; cycle <= 600 && f.actor.isMoving(); cycle++) f.tick(cycle);
        assertFalse(f.actor.isMoving());
        f.actor.onGameTick(true, 7);
        MotionObservation arrived = f.actor.getLatestMotionCompletion();
        assertEquals(returning.requestId, arrived.requestId);
        assertEquals(800, arrived.sourceCandidateId);
        assertEquals(MotionObservation.Purpose.APPROACH_PLAYER, arrived.purpose);
        assertEquals(MotionObservation.Status.ARRIVED, arrived.status);
        assertTrue(arrived.completedEdges > 0);
        assertEquals(0, arrived.completedRunEdges);
        assertTrue(arrived.arrivalDistance >= 2 && arrived.arrivalDistance <= 5);
        assertEquals(lastVoluntary, f.actor.getLastVoluntaryMovementTick());
        assertTrue(f.actor.admitMotionScene(900, arrived.requestId));
        assertTrue(f.actor.hasMotionSceneArrived(900));
        assertTrue(f.actor.clearIntent(900));
        assertTrue(f.actor.holdPosition(900));
        assertSame(arrived, f.actor.getMotionHoldReceipt(900).completion);
        assertTrue(f.actor.hasMotionSceneArrived(900));
        assertEquals(arrived.requestId, f.actor.getInspectionReturnRequestId(800));
        f.actor.clearIntent();
        assertEquals(-1, f.actor.getInspectionReturnRequestId(800));
        assertFalse(f.actor.hasMotionSceneArrived(900));
    }

    @Test public void aStationaryNearPlayerReturnCannotInventAnAcceptedRouteOrArrival() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation inspection = f.startKeyedInspection(800);
        assertTrue(f.actor.getDistanceToPlayer() >= 2 && f.actor.getDistanceToPlayer() <= 5);
        assertTrue(f.actor.requestReturnFromInspection(800, inspection.requestId));
        f.actor.onGameTick(true, 6);
        assertFalse(f.actor.isMoving());
        assertEquals(-1, f.actor.getInspectionReturnRequestId(800));
        assertNull(f.actor.getLatestMotionCompletion());
        assertFalse(f.actor.admitMotionScene(900, inspection.requestId));
        assertFalse(f.actor.hasMotionSceneArrived(900));
    }

    @Test public void aReturnNeverCreditsTheInspectionStepAlreadyInFlight() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        MotionObservation inspection = f.startKeyedInspection(800);
        for (int cycle = 1; cycle <= 181; cycle++) f.tick(cycle);
        assertTrue(f.actor.requestReturnFromInspection(800, inspection.requestId));
        f.actor.onGameTick(true, 6);
        long returnId = f.actor.getInspectionReturnRequestId(800);
        assertTrue(returnId >= 0);
        for (int cycle = 182; cycle <= 210; cycle++) f.tick(cycle);
        assertEquals(returnId, f.actor.getMotionObservation().requestId);
        assertEquals(0, f.actor.getMotionObservation().completedEdges);
        assertNull(f.actor.getLatestMotionCompletion());
        for (int cycle = 211; cycle <= 240; cycle++) f.tick(cycle);
        assertEquals(1, f.actor.getMotionObservation().completedEdges);
        assertEquals(0, f.actor.getMotionObservation().completedRunEdges);
    }

    @Test public void genericControlsScopeLossAndAnInspectedArrivalCannotBecomeAKeyedReturn() throws Exception
    {
        PlacementFixture held = new PlacementFixture();
        MotionObservation selected = held.startKeyedInspection(800);
        held.actor.holdPosition();
        assertFalse(held.actor.requestReturnFromInspection(800, selected.requestId));
        assertEquals(-1, held.actor.getInspectionReturnRequestId(800));

        PlacementFixture changed = new PlacementFixture();
        selected = changed.startKeyedInspection(800);
        when(changed.view.getScene()).thenReturn(mock(Scene.class));
        assertFalse(changed.actor.requestReturnFromInspection(800, selected.requestId));

        PlacementFixture finished = new PlacementFixture();
        selected = finished.startKeyedInspection(800);
        for (int cycle = 1; cycle <= 240; cycle++) finished.tick(cycle);
        assertTrue(finished.actor.hasArrived());
        assertEquals(MotionObservation.Status.MOVING, finished.actor.getMotionObservation().status);
        assertFalse(finished.actor.requestReturnFromInspection(800, selected.requestId));
        finished.actor.onGameTick(true, 6);
        assertEquals(MotionObservation.Status.ARRIVED, finished.actor.getMotionObservation().status);
        assertFalse(finished.actor.requestReturnFromInspection(800, selected.requestId));

        PlacementFixture replaced = new PlacementFixture();
        selected = replaced.startKeyedInspection(800);
        for (int cycle = 1; cycle <= 180; cycle++) replaced.tick(cycle);
        assertTrue(replaced.actor.requestReturnFromInspection(800, selected.requestId));
        replaced.actor.onGameTick(true, 6);
        assertTrue(replaced.actor.getInspectionReturnRequestId(800) >= 0);
        replaced.actor.requestApproachPlayer(2, 5, false);
        assertEquals(-1, replaced.actor.getInspectionReturnRequestId(800));
        replaced.actor.onGameTick(true, 7);
        assertEquals(MotionObservation.Purpose.OTHER, replaced.actor.getMotionObservation().purpose);
        assertEquals(-1, replaced.actor.getMotionObservation().sourceCandidateId);
    }

    @Test public void firstRestIsEightTicksAndLaterRestIsSampledOnceWithoutAnInspectionStamp() throws Exception
    {
        IntervalRandom random = new IntervalRandom();
        PlacementFixture f = new PlacementFixture(random);
        f.actor.onGameTick(true, 1);
        for (int tick = 2; tick <= 10; tick++)
        {
            f.actor.onGameTick(true, tick);
            assertFalse(f.actor.isMoving());
        }
        f.actor.onGameTick(true, 11);
        assertTrue(f.actor.isMoving());
        assertEquals(1, f.actor.getLastPlannerSteps());
        for (int cycle = 1; cycle <= 30; cycle++) f.tick(cycle);
        assertFalse(f.actor.isMoving());
        assertEquals("IDLE", f.actor.getLastClip());
        assertTrue(f.actor.getDistanceToPlayer() >= 2 && f.actor.getDistanceToPlayer() <= 4);
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
        assertEquals(1, random.shiftIntervals);
        for (int tick = 12; tick <= 23; tick++)
        {
            f.actor.onGameTick(true, tick);
            assertFalse(f.actor.isMoving());
            assertEquals(1, random.shiftIntervals);
        }
        f.actor.onGameTick(true, 24);
        assertTrue(f.actor.isMoving());
        for (int cycle = 31; cycle <= 60; cycle++) f.tick(cycle);
        assertFalse(f.actor.isMoving());
        assertEquals(2, random.shiftIntervals);
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
    }

    @Test public void blockedStrollIsSkippedWithoutDetouringAndTakesAShortRest() throws Exception
    {
        PlacementFixture f = new PlacementFixture(new IntervalRandom());
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        for (int tick = 2; tick <= 11; tick++) f.actor.onGameTick(true, tick);
        MotionObservation chosen = f.actor.getMotionObservation();
        assertEquals(MotionObservation.Purpose.ROAM, chosen.purpose);
        assertEquals(1, chosen.plannedSteps);
        f.flags[chosen.goalWorldX - 3200][chosen.goalWorldY - 3200] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        f.tick(1);
        assertFalse(f.actor.isMoving());
        assertTrue(f.actor.getMotionObservation().physicalBlock);
        long plans = f.actor.getPlanningCount();
        for (int tick = 12; tick <= 23; tick++)
        {
            f.actor.onGameTick(true, tick);
            assertFalse(f.actor.isMoving());
            assertEquals(origin, f.actor.getLocation());
            assertEquals(plans, f.actor.getPlanningCount());
        }
        f.actor.onGameTick(true, 24);
        assertEquals(plans + 1, f.actor.getPlanningCount());
        assertTrue(f.actor.isMoving());
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
    }

    @Test public void seededFiveHundredTickStrollsKeepTheAuthoredRhythmAndBand() throws Exception
    {
        int total = 0, calmRuns = 0;
        for (int seed = 0; seed < 32; seed++)
        {
            PlacementFixture f = new PlacementFixture(new java.util.Random(seed), true);
            int completed = 0, cycle = 0, priorEnd = 3;
            long request = -1;
            boolean calm = false;
            f.actor.onGameTick(true, 1);
            for (int tick = 2; tick <= 500; tick++)
            {
                f.actor.onGameTick(true, tick);
                MotionObservation observation = f.actor.getMotionObservation();
                if (f.actor.isMoving() && observation.requestId != request)
                {
                    assertEquals(MotionObservation.Purpose.ROAM, observation.purpose);
                    assertTrue(observation.plannedSteps >= 1 && observation.plannedSteps <= 4);
                    int rest = tick - priorEnd;
                    assertTrue("rest seed=" + seed + " ticks=" + rest, rest >= 8);
                    calm |= rest >= 60;
                    request = observation.requestId;
                }
                boolean moving = f.actor.isMoving();
                for (int frame = 0; frame < (moving ? 30 : 1); frame++)
                {
                    cycle += moving ? 1 : 30;
                    f.tick(cycle);
                    assertNotEquals("RUN", f.actor.getLastClip());
                }
                if (moving && !f.actor.isMoving())
                {
                    completed++;
                    priorEnd = tick + 1;
                    assertTrue(f.actor.getDistanceToPlayer() >= 2 && f.actor.getDistanceToPlayer() <= 4);
                    assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
                }
            }
            assertTrue("strolls seed=" + seed + " count=" + completed, completed >= 6 && completed <= 16);
            total += completed;
            if (calm) calmRuns++;
        }
        double mean = total / 32d;
        assertTrue("mean=" + mean, mean >= 8.5 && mean <= 11.5);
        assertTrue("calm runs=" + calmRuns, calmRuns >= 29);
        System.out.println("E2 actual 32 x 500 ticks: stroll mean=" + mean + ", calm runs=" + calmRuns);
    }

    @Test public void sceneFacingIsDiscardedAtEndAndAfterAChangedSceneBase() throws Exception
    {
        PlacementFixture f = new PlacementFixture(new IntervalRandom());
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int playerFacing = f.actor.getOrientation();
        f.actor.holdPosition();
        f.actor.turnAway();
        for (int cycle = 1; cycle <= 40; cycle++) f.tick(cycle);
        assertEquals((playerFacing + 1024) & 2047, f.actor.getOrientation());
        f.actor.clearIntent();
        // H.3: the scene's facing is dropped at once, and she comes round to him by turning, never in one frame.
        assertEquals((playerFacing + 1024) & 2047, f.actor.getOrientation());
        for (int cycle = 41; cycle <= 72; cycle++)
        {
            int before = f.actor.getOrientation();
            f.tick(cycle);
            assertEquals("frame " + cycle, 32, MotionMath.angleDifference(before, f.actor.getOrientation()));
        }
        assertEquals(playerFacing, f.actor.getOrientation());
        f.actor.holdPosition();
        f.actor.faceTarget(new LocalPoint(origin.getX() + 128, origin.getY(), -1));
        when(f.view.getBaseX()).thenReturn(3300);
        f.actor.onGameTick(true, 2);
        assertTrue(f.actor.isVisible());
        LocalPoint replaced = f.actor.getLocation();
        assertEquals(MotionMath.quantize(MotionMath.bearing(50 * 128 + 64 - replaced.getX(),
            50 * 128 + 64 - replaced.getY())), f.actor.getOrientation());
    }

    @Test public void aPlayerMoreThanNinetyDegreesBehindIsTurnedToAtOnceAndNeverInOneFrame() throws Exception
    {
        PlacementFixture f = new PlacementFixture(new IntervalRandom());
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int placed = f.actor.getOrientation();
        int dx = Integer.signum(50 - origin.getSceneX()), dy = Integer.signum(50 - origin.getSceneY());
        LocalPoint behind = new LocalPoint((origin.getSceneX() - 2 * dx) * 128 + 64,
            (origin.getSceneY() - 2 * dy) * 128 + 64, -1);
        when(f.player.getLocalLocation()).thenReturn(behind);
        f.actor.onGameTick(true, 2);
        int wanted = MotionMath.quantize(MotionMath.bearing(behind.getX() - origin.getX(), behind.getY() - origin.getY()));
        // H.3: the tick itself moves nothing; the turn begins on the next frame, without E.3's wait of 8 to 20 ticks,
        // at the game's own 32 units a client cycle. Half a turn is 32 cycles.
        assertEquals(placed, f.actor.getOrientation());
        assertEquals(1024, MotionMath.angleDifference(placed, wanted));
        for (int cycle = 1; cycle <= 32; cycle++)
        {
            int before = f.actor.getOrientation();
            f.tick(cycle);
            assertEquals("frame " + cycle, 32, MotionMath.angleDifference(before, f.actor.getOrientation()));
        }
        assertEquals(wanted, f.actor.getOrientation());
        assertFalse(f.actor.isMoving());
    }

    @Test public void busyRestNeverStartsAnIdleShiftEvenAfterTheFirstLongWait() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        f.actor.setIdleMovementSuppressed(true);
        for (int tick = 2; tick <= 300; tick++) f.actor.onGameTick(true, tick);
        assertFalse(f.actor.isMoving());
        assertEquals(origin, f.actor.getLocation());
        assertEquals(1, f.actor.getPlanningCount());
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
        f.actor.setIdleMovementSuppressed(false);
        f.actor.onGameTick(true, 301);
        assertTrue(f.actor.isMoving());
        assertTrue(f.actor.getLastPlannerSteps() >= 1 && f.actor.getLastPlannerSteps() <= 4);
    }

    @Test public void twoMovingTicksWithinTwoTilesNeverStartACloserEdge() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        LocalPoint first = adjacentAtTwo(origin, 50, 50);
        when(f.player.getLocalLocation()).thenReturn(first);
        f.actor.onGameTick(true, 2);
        assertFalse(f.actor.isMoving());
        assertEquals(1, f.actor.getPlanningCount());
        LocalPoint second = adjacentAtTwo(origin, first.getSceneX(), first.getSceneY());
        when(f.player.getLocalLocation()).thenReturn(second);
        f.actor.onGameTick(true, 3);
        assertFalse(f.actor.isMoving());
        assertEquals(1, f.actor.getPlanningCount());
        f.tick(1);
        f.actor.onGameTick(true, 4);
        f.actor.onGameTick(true, 5);
        for (int cycle = 2; cycle <= 30; cycle++) f.tick(cycle);
        assertFalse(f.actor.isMoving());
        assertTrue(f.actor.getDistanceToPlayer() <= 2);
        assertEquals(origin, f.actor.getLocation());
        assertEquals(1, f.actor.getPlanningCount());
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
    }

    @Test public void normalIdleFacingKeepsItsTargetUntilTheSampledInterval() throws Exception
    {
        PlacementFixture f = new PlacementFixture(new IntervalRandom());
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int initial = f.actor.getOrientation();
        LocalPoint first = adjacentAtTwo(origin, 50, 50);
        when(f.player.getLocalLocation()).thenReturn(first);
        for (int tick = 2; tick <= 8; tick++)
        {
            f.actor.onGameTick(true, tick);
            f.tick(tick);
            assertEquals(initial, f.actor.getOrientation());
        }
        f.actor.onGameTick(true, 9);
        for (int cycle = 9; cycle <= 64; cycle++) f.tick(cycle);
        int firstFacing = MotionMath.quantize(MotionMath.bearing(first.getX() - origin.getX(), first.getY() - origin.getY()));
        assertEquals(firstFacing, f.actor.getOrientation());
        LocalPoint second = adjacentAtTwo(origin, first.getSceneX(), first.getSceneY());
        when(f.player.getLocalLocation()).thenReturn(second);
        for (int tick = 10; tick <= 16; tick++) f.actor.onGameTick(true, tick);
        for (int cycle = 65; cycle <= 100; cycle++) f.tick(cycle);
        assertEquals(firstFacing, f.actor.getOrientation());
        f.actor.onGameTick(true, 17);
        for (int cycle = 101; cycle <= 140; cycle++) f.tick(cycle);
        assertEquals(MotionMath.quantize(MotionMath.bearing(second.getX() - origin.getX(), second.getY() - origin.getY())),
            f.actor.getOrientation());
        assertEquals(1, f.actor.getPlanningCount());
    }

    @Test public void aPlayerReversingBetweenTwoAdjacentTilesDoesNotMakeHerChase() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        LocalPoint adjacent = adjacentAtTwo(origin, 50, 50);
        LocalPoint original = new LocalPoint(50 * 128 + 64, 50 * 128 + 64, -1);
        for (int tick = 2; tick <= 301; tick++)
        {
            when(f.player.getLocalLocation()).thenReturn(tick % 2 == 0 ? adjacent : original);
            f.actor.onGameTick(true, tick);
            f.tick(tick);
            assertFalse(f.actor.isMoving());
        }
        assertEquals(origin, f.actor.getLocation());
        assertEquals(1, f.actor.getPlanningCount());
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
    }

    @Test public void anAdjacentReversalAcrossTheThreeTileThresholdStillDoesNotMakeHerChase() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int dx = Integer.signum(50 - origin.getSceneX()), dy = dx == 0 ? Integer.signum(50 - origin.getSceneY()) : 0;
        LocalPoint farther = new LocalPoint((50 + dx) * 128 + 64, (50 + dy) * 128 + 64, -1);
        LocalPoint original = new LocalPoint(50 * 128 + 64, 50 * 128 + 64, -1);
        assertEquals(3, MotionMath.distance(origin.getSceneX(), origin.getSceneY(), farther.getSceneX(), farther.getSceneY()));
        for (int tick = 2; tick <= 301; tick++)
        {
            when(f.player.getLocalLocation()).thenReturn(tick % 2 == 0 ? farther : original);
            f.actor.onGameTick(true, tick);
            f.tick(tick);
            assertFalse(f.actor.isMoving());
        }
        assertEquals(origin, f.actor.getLocation());
        assertEquals(1, f.actor.getPlanningCount());
    }

    @Test public void walkingAwayStartsOnTheSecondProgressTickAfterReachingThreeTiles() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int dx = Integer.signum(50 - origin.getSceneX()), dy = dx == 0 ? Integer.signum(50 - origin.getSceneY()) : 0;
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint((50 + dx) * 128 + 64, (50 + dy) * 128 + 64, -1));
        f.actor.onGameTick(true, 2);
        assertFalse(f.actor.isMoving());
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint((50 + 2 * dx) * 128 + 64, (50 + 2 * dy) * 128 + 64, -1));
        f.actor.onGameTick(true, 3);
        assertTrue(f.actor.isMoving());
        assertEquals(2, f.actor.getPlanningCount());
        MotionObservation following = f.actor.getMotionObservation();
        int goalDistance = MotionMath.distance(following.goalWorldX - 3200, following.goalWorldY - 3200,
            50 + 2 * dx, 50 + 2 * dy);
        assertTrue(goalDistance >= 2 && goalDistance <= 3);
        f.tick(1);
        assertEquals("WALK", f.actor.getLastClip());
    }

    @Test public void followingKeepsItsLateralSideUntilAnAcceptedEdgeBecomesBlocked() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int dx = -4 * (origin.getSceneY() - 50), dy = 4 * (origin.getSceneX() - 50);
        int playerX = 50 + dx, playerY = 50 + dy;
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint(playerX * 128 + 64, playerY * 128 + 64, -1));
        f.actor.onGameTick(true, 2);
        MotionObservation first = f.actor.getMotionObservation();
        assertNotNull(first);
        assertTrue((first.goalWorldX - 3200 - playerX) * dy - (first.goalWorldY - 3200 - playerY) * dx >= 0);
        for (int x = playerX - 3; x <= playerX + 3; x++)
        {
            for (int y = playerY - 3; y <= playerY + 3; y++)
            {
                if ((x - playerX) * dy - (y - playerY) * dx >= 0) f.flags[x][y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
            }
        }
        RouteMotion route = (RouteMotion) field(NativeActor.class, "motion").get(f.actor);
        com.malfoid.motion.RoutePlanner.Node next = route.nextUnenteredNode();
        f.flags[next.x][next.y] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        f.tick(1);
        assertFalse(f.actor.isMoving());
        assertTrue(f.actor.getMotionObservation().physicalBlock);
        f.actor.onGameTick(true, 3);
        MotionObservation replanned = f.actor.getMotionObservation();
        assertTrue(f.actor.isMoving());
        assertTrue((replanned.goalWorldX - 3200 - playerX) * dy - (replanned.goalWorldY - 3200 - playerY) * dx < 0);
    }

    @Test public void aCompletedVoluntaryEdgeKeepsItsOwnershipWhenTheSceneStopsMidStep() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        f.actor.setSceneMotionVoluntary(true);
        f.actor.requestDestination(3200 + origin.getSceneX() + 1, 3200 + origin.getSceneY());
        f.actor.onGameTick(true, 2);
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
        f.tick(1);
        f.actor.holdPosition();
        for (int cycle = 2; cycle <= 30; cycle++) f.tick(cycle);
        assertEquals(3, f.actor.getLastVoluntaryMovementTick());
        assertFalse(f.actor.isMoving());
        f.actor.hide();
        assertEquals(3, f.actor.getLastVoluntaryMovementTick());
        f.actor.resetForLogin();
        assertEquals(-1, f.actor.getLastVoluntaryMovementTick());
    }

    @Test public void theFirstPausedFollowTickKeepsItsSideDespiteOppositeSideCourtesyScores() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        LocalPoint origin = f.actor.getLocation();
        int dx = -4 * (origin.getSceneY() - 50), dy = 4 * (origin.getSceneX() - 50);
        int playerX = 50 + dx, playerY = 50 + dy;
        when(f.player.getLocalLocation()).thenReturn(new LocalPoint(playerX * 128 + 64, playerY * 128 + 64, -1));
        f.actor.onGameTick(true, 2);
        Tile[][][] tiles = new Tile[4][104][104];
        for (int x = playerX - 3; x <= playerX + 3; x++)
        {
            for (int y = playerY - 3; y <= playerY + 3; y++)
            {
                if ((x - playerX) * dy - (y - playerY) * dx >= 0)
                {
                    Tile tile = mock(Tile.class);
                    when(tile.getGroundItems()).thenReturn(Collections.singletonList(mock(TileItem.class)));
                    tiles[0][x][y] = tile;
                }
            }
        }
        when(f.scene.getTiles()).thenReturn(tiles);
        f.actor.onGameTick(true, 3);
        MotionObservation paused = f.actor.getMotionObservation();
        assertTrue(f.actor.isMoving());
        assertTrue((paused.goalWorldX - 3200 - playerX) * dy - (paused.goalWorldY - 3200 - playerY) * dx >= 0);
        assertEquals(3, f.actor.getPlanningCount());
    }

    @Test public void aDistantIncompleteCollisionRowStaysUnknownAndNeverTriggersRecovery() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        f.actor.onGameTick(true, 1);
        long generation = f.actor.getPlacementGeneration();
        f.flags[100] = null;
        for (int tick = 2; tick <= 70; tick++) f.actor.onGameTick(true, tick);
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getPlacementSnapshot());
        assertNull(f.actor.getMotionObservation());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(1, f.actor.getPlanningCount());
        assertEquals(generation + 1, f.actor.getPlacementGeneration());
        verify(f.client, times(1)).registerRuneLiteObject(any());
        verify(f.client, times(1)).removeRuneLiteObject(any());
    }

    private static LocalPoint adjacentAtTwo(LocalPoint origin, int oldX, int oldY)
    {
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                if ((dx != 0 || dy != 0) && MotionMath.distance(origin.getSceneX(), origin.getSceneY(), oldX + dx, oldY + dy) == 2)
                    return new LocalPoint((oldX + dx) * 128 + 64, (oldY + dy) * 128 + 64, -1);
            }
        }
        throw new AssertionError("No neighboring player step remains two tiles away");
    }

    @Test public void privateZoneBystandersAndNoncombatPlayerTargetsDoNotChangeActualMotionOrFacing() throws Exception
    {
        for (int zone = 0; zone < 3; zone++)
        {
            PlacementFixture alone = new PlacementFixture(new java.util.Random(7), true);
            PlacementFixture surrounded = new PlacementFixture(new java.util.Random(7), true);
            for (PlacementFixture f : Arrays.asList(alone, surrounded))
            {
                when(f.client.getWorldType()).thenReturn(zone == 0 ? EnumSet.noneOf(WorldType.class)
                    : EnumSet.of(zone == 1 ? WorldType.PVP : WorldType.DEADMAN));
                when(f.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(zone == 0 ? 1 : 0);
                doReturn(actorSet(Collections.emptyList())).when(f.view).npcs();
                f.actor.setCombatTarget(false, null, 0);
            }
            Player other = mock(Player.class);
            doReturn(actorSet(Collections.singletonList(other))).when(surrounded.view).players();
            doReturn(actorSet(Collections.emptyList())).when(alone.view).players();
            for (int tick = 1; tick <= 80; tick++)
            {
                // Follow/trade-style local interactions have no Attack/hitsplat evidence.
                when(surrounded.player.getInteracting()).thenReturn(tick % 3 == 0 ? null : other);
                when(other.getLocalLocation()).thenReturn(new LocalPoint((45 + tick % 10) * 128 + 64,
                    (45 + tick % 7) * 128 + 64, -1));
                when(other.getWorldLocation()).thenReturn(new WorldPoint(3245 + tick % 10, 3245 + tick % 7, 0));
                when(other.getAnimation()).thenReturn(tick % 2 == 0 ? 819 : 824);
                when(other.getPlayerComposition()).thenReturn(mock(PlayerComposition.class));
                when(other.getSkullIcon()).thenReturn(tick % 2 == 0 ? SkullIcon.SKULL : SkullIcon.NONE);
                alone.actor.onGameTick(true, tick); surrounded.actor.onGameTick(true, tick);
                assertEquals(alone.actor.isInteractionHolding(), surrounded.actor.isInteractionHolding());
                assertFalse(surrounded.actor.isCrowded());
                assertEquals(alone.actor.isInspectionSuppressed(), surrounded.actor.isInspectionSuppressed());
                assertEquals(alone.actor.getPlanningCount(), surrounded.actor.getPlanningCount());
                for (int cycle = (tick - 1) * 30 + 1; cycle <= tick * 30; cycle++)
                {
                    alone.tick(cycle); surrounded.tick(cycle);
                    assertEquals(alone.actor.getLocation(), surrounded.actor.getLocation());
                    assertEquals(alone.actor.getOrientation(), surrounded.actor.getOrientation());
                    assertEquals(alone.actor.getLastClip(), surrounded.actor.getLastClip());
                    assertEquals(alone.actor.getPlacementGeneration(), surrounded.actor.getPlacementGeneration());
                }
            }
            verify(other, never()).getLocalLocation(); verify(other, never()).getWorldLocation();
            verify(other, never()).getPlayerComposition(); verify(other, never()).getSkullIcon();
            verify(other, never()).getAnimation();
        }
    }

    @Test public void enteringAPrivateZoneRevokesAnOldCrowdSuppressionBeforeTheNextCommit() throws Exception
    {
        PlacementFixture f = new PlacementFixture();
        field(NativeActor.class, "crowds").setBoolean(f.actor, true);
        assertTrue(f.actor.isInspectionSuppressed());
        when(f.client.getVarbitValue(VarbitID.INSIDE_WILDERNESS)).thenReturn(1);
        assertFalse(f.actor.isCrowded()); assertFalse(f.actor.isInspectionSuppressed());
        assertNull(field(NativeActor.class, "interactionTarget").get(f.actor));
        assertNull(field(NativeActor.class, "oldCombatTarget").get(f.actor));
    }

    private static <T> IndexedObjectSet<T> actorSet(List<T> actors)
    {
        return new IndexedObjectSet<T>()
        {
            public T byIndex(int index) { return actors.get(index); }
            public Iterator<T> iterator() { return actors.iterator(); }
        };
    }

    private static final class IntervalRandom extends java.util.Random
    {
        int shiftIntervals;
        @Override public int nextInt(int bound)
        {
            if (bound == 25 || bound == 81) shiftIntervals++;
            return 0;
        }
    }

    /** Bank preparation and rendering have their own native harness; these tests isolate public placement callbacks. */
    private static final class PlacementFixture
    {
        final Client client;
        final Player player;
        final WorldView view;
        final Scene scene;
        final byte[][][] settings = new byte[4][104][104];
        final int[][][] heights = new int[4][105][105];
        final int[][] flags = new int[104][104];
        final NativeActor actor;
        final Model idle, walk, run;
        int currentCycle;

        PlacementFixture() throws Exception
        {
            this(new java.util.Random(7));
        }

        PlacementFixture(java.util.Random random) throws Exception
        {
            this(random, false);
        }

        PlacementFixture(java.util.Random random, boolean lightweight) throws Exception
        {
            org.mockito.MockSettings mockSettings = lightweight ? withSettings().stubOnly() : withSettings();
            client = mock(Client.class, mockSettings);
            player = mock(Player.class, mockSettings);
            view = mock(WorldView.class, mockSettings);
            scene = mock(Scene.class, mockSettings);
            actor = new NativeActor(client, new Gson(), random);
            when(client.getGameCycle()).thenAnswer(call -> currentCycle);
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getWorldView(-1)).thenReturn(view);
            when(view.getId()).thenReturn(-1);
            when(view.getScene()).thenReturn(scene);
            when(view.getSizeX()).thenReturn(104);
            when(view.getSizeY()).thenReturn(104);
            when(view.getBaseX()).thenReturn(3200);
            when(view.getBaseY()).thenReturn(3200);
            when(view.contains(any(LocalPoint.class))).thenReturn(true);
            when(view.getTileSettings()).thenReturn(settings);
            when(view.getTileHeights()).thenReturn(heights);
            for (int[][] level : heights) for (int[] row : level) Arrays.fill(row, 120);
            CollisionData collision = mock(CollisionData.class, mockSettings);
            when(collision.getFlags()).thenReturn(flags);
            when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
            when(player.getWorldView()).thenReturn(view);
            when(player.getLocalLocation()).thenReturn(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, -1));
            actor.loadResources();
            NativeAsset asset = (NativeAsset) field(NativeActor.class, "decodedAsset").get(actor);
            NativePoseBank bank = new NativePoseBank(client, asset);
            idle = pose(asset.clips.get("IDLE").samples[0], mockSettings);
            walk = pose(asset.clips.get("WALK").samples[0], mockSettings);
            run = pose(asset.clips.get("RUN").samples[0], mockSettings);
            Model[] models = (Model[]) field(NativePoseBank.class, "models").get(bank);
            models[0] = idle;
            Arrays.fill(models, 1, 51, idle);
            Arrays.fill(models, 51, 81, walk);
            Arrays.fill(models, 81, 101, run);
            Arrays.fill(models, 101, models.length, idle);
            field(NativePoseBank.class, "readyAt").setLong(bank, 1);
            field(NativeActor.class, "bank").set(actor, bank);
        }

        void tick(int cycle)
        {
            currentCycle = cycle;
            actor.onClientTick();
        }

        void installEmoteModels() throws Exception
        {
            NativeAsset asset = (NativeAsset) field(NativeActor.class, "decodedAsset").get(actor);
            NativePoseBank bank = (NativePoseBank) field(NativeActor.class, "bank").get(actor);
            Model[] models = (Model[]) field(NativePoseBank.class, "models").get(bank);
            for (int sample = 0; sample < 10; sample++)
                models[101 + sample] = pose(asset.clips.get("CROSS_ARMS").samples[sample], withSettings().stubOnly());
            for (int sample = 0; sample < 20; sample++)
                models[111 + sample] = pose(asset.clips.get("IMPATIENT").samples[sample], withSettings().stubOnly());
        }

        MotionObservation completeOrdinaryCatchup()
        {
            actor.onGameTick(true, 1);
            when(player.getLocalLocation()).thenReturn(new LocalPoint(58 * 128 + 64, 50 * 128 + 64, -1));
            actor.onGameTick(true, 2);
            for (int cycle = 1; cycle <= 300 && actor.isMoving(); cycle++) tick(cycle);
            assertFalse(actor.isMoving());
            actor.onGameTick(true, 3);
            MotionObservation result = actor.getLatestMotionCompletion();
            assertNotNull(result);
            assertTrue(result.known);
            assertTrue(result.ordinary);
            assertEquals(MotionObservation.Purpose.CATCHUP, result.purpose);
            assertTrue(result.startDistance >= 10);
            assertTrue(result.completedRunEdges >= 2);
            return result;
        }

        MotionObservation startKeyedInspection(long candidateId)
        {
            actor.onGameTick(true, 1);
            actor.onGameTick(true, 2);
            actor.onGameTick(true, 3);
            LocalPoint origin = actor.getLocation();
            int dx = Integer.signum(origin.getSceneX() - 50);
            int dy = dx == 0 ? Integer.signum(origin.getSceneY() - 50) : 0;
            int objectX = 3200 + origin.getSceneX() + 9 * dx;
            int objectY = 3200 + origin.getSceneY() + 9 * dy;
            assertTrue(actor.requestInspectionProbe("inspection:" + candidateId, objectX, objectY, objectX, objectY, 0));
            actor.onGameTick(true, 4);
            NativeActor.InspectionRouteProof proof = actor.getInspectionRouteProof();
            assertNotNull(proof);
            assertEquals(8, proof.getPathSteps());
            assertTrue(actor.requestInspectionDestination(candidateId, proof.getStandWorldX(), proof.getStandWorldY()));
            actor.setSceneMotionVoluntary(true);
            long plans = actor.getPlanningCount();
            actor.onGameTick(true, 5);
            MotionObservation selected = actor.getMotionObservation();
            assertEquals(plans + 1, actor.getPlanningCount());
            assertNotNull(selected);
            assertEquals(candidateId, selected.sourceCandidateId);
            assertEquals(MotionObservation.Purpose.OTHER, selected.purpose);
            assertEquals(MotionObservation.Status.MOVING, selected.status);
            assertTrue(selected.known && selected.legalGoal && selected.fixedGoal);
            assertFalse(selected.ordinary);
            assertEquals(8, selected.plannedSteps);
            assertEquals(0, selected.completedEdges);
            assertEquals(0, selected.completedRunEdges);
            return selected;
        }

        private static Model pose(float[] coordinates, org.mockito.MockSettings settings)
        {
            Model model = mock(Model.class, settings);
            float[] y = new float[coordinates.length / 3];
            for (int index = 0; index < y.length; index++) y[index] = coordinates[index * 3 + 1];
            when(model.getVerticesY()).thenReturn(y);
            when(model.getModelHeight()).thenReturn(173);
            return model;
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
