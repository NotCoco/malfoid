package com.malfoid.asset;

import com.google.gson.Gson;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RecoveryPolicy;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.motion.WholeBoundsVisibility;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Only the already-validated model bank is supplied by a test fixture; placement,
 * routes, collision results, counters and recovery decisions use public callbacks.
 */
public class NativeRecoveryConformanceTest
{
    @Test public void separationSixteenReachesRecoveryWithoutInventingThreePriorFailures() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        assertEquals(16, f.actor.getDistanceToPlayer());
        assertEquals(5, f.policy().getOffScreenTicks());
        assertEquals(0, f.policy().getIneffectivePlans());
        assertEquals(1, f.actor.getPlanningCount());
        assertNull(f.actor.getMotionObservation());

        f.isolateBothOrigins();
        f.actor.clearIntent();
        f.commit(11);
        f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
        assertEquals(1, f.policy().getIneffectivePlans());
        assertEquals(0, f.policy().getBlockedTicks());
        assertEquals(2, f.actor.getPlanningCount());
        assertNull(f.actor.getMotionObservation());
        f.assertNoRelocation();
    }

    @Test public void firstSecondAndThirdFailuresApplyTheExplicitThreeFailureFiveThirtyThreshold() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.isolateBothOrigins();
        f.actor.clearIntent();
        for (int tick = 11; tick <= 13; tick++)
        {
            f.commit(tick);
            assertEquals(tick - 10, f.policy().getIneffectivePlans());
            assertEquals(tick - 9, f.actor.getPlanningCount());
            f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
            long searches = f.actor.getPlanningCount();
            f.actor.onGameTick(true, tick); // A duplicate committed ordinal cannot retry.
            f.clientTicks(90);            // Neither preparation nor render frames plan a route.
            assertEquals(searches, f.actor.getPlanningCount());
            f.assertNoRelocation();
        }
        assertEquals(18, f.gate("planNotBefore"));
        assertEquals(43, f.gate("retryAt"));
        f.commitsThrough(42);
        assertEquals(4, f.actor.getPlanningCount());
        f.commit(43);
        assertEquals(5, f.actor.getPlanningCount());
        assertEquals(48, f.gate("planNotBefore"));
        assertEquals(73, f.gate("retryAt"));
        f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
        f.assertNoRelocation();
    }

    @Test public void meaningfulCollisionChangeCannotBypassFiveTicksButCanReplaceThirtyTickWait() throws Exception
    {
        Fixture f = Fixture.withThreeFailedOffScreenSearches();
        f.flags[53][50] = 0; // Change beside the suspended origin, not a fabricated result.
        f.commit(14);
        f.commitsThrough(17);
        assertEquals(4, f.actor.getPlanningCount());
        f.flags[52][51] = 0; // A fresh change on the first permitted tick.
        f.commit(18);
        assertEquals(5, f.actor.getPlanningCount());
        f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
        assertEquals(23, f.gate("planNotBefore"));
        assertEquals(48, f.gate("retryAt"));
        f.assertNoRelocation();
    }

    @Test public void reachableInnerPocketWithoutARecoveryDestinationKeepsItsActualFrontierClassification() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.isolateBothOrigins();
        for (int x = 35; x <= 37; x++) for (int y = 49; y <= 51; y++) f.flags[x][y] = 0;
        f.actor.clearIntent();
        f.commit(11);
        f.assertFailedSearch(RoutePlanner.Status.FRONTIER, 9, 1);
        assertEquals(0, f.policy().getBlockedTicks());
        assertEquals(1, f.policy().getIneffectivePlans());
        assertNull(f.actor.getMotionObservation());
        f.assertNoRelocation();
    }

    @Test public void aFull625CellSearchStillPreservesFrontierInsteadOfInventingBudgetExhaustion() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        // This copied, known footprint excludes every 2..4 candidate through the
        // existing combat courtesy scorer. The raw collision expansion remains open.
        f.actor.clearIntent();
        // E.3 clears scene-owned points; this search uses a newly sampled current footprint.
        f.actor.setCombatTarget(true, point(36, 50), 9);
        f.commit(11);
        f.assertFailedSearch(RoutePlanner.Status.FRONTIER, 625, 2);
        assertEquals(0, f.policy().getBlockedTicks());
        assertNull(f.actor.getLatestMotionCompletion());
        f.assertNoRelocation();
    }

    @Test public void unavailableCollisionHidesAndResetsEvidenceWithoutCreatingARecoveryFailure() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.flags[100] = null; // Unrelated missing row makes the copied scene incomplete.
        f.actor.clearIntent();
        f.commit(11);
        assertFalse(f.actor.isVisible());
        assertNull(f.actor.getPlacementSnapshot());
        assertEquals(1, f.actor.getPlanningCount());
        assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(0, f.policy().getBlockedTicks());
        assertEquals(0, f.policy().getIneffectivePlans());
        assertNull(f.actor.getLatestMotionCompletion());
        verify(f.client, times(1)).removeRuneLiteObject(any());
    }

    @Test public void anIllegalPlayerRootKeepsSceneUnavailableDistinctFromPhysicalBlockage() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.isolateBothOrigins();
        f.flags[36][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        f.actor.clearIntent();
        f.commit(11);
        f.assertFailedSearch(RoutePlanner.Status.SCENE_UNAVAILABLE, 0, 0);
        assertEquals(0, f.policy().getBlockedTicks());
        assertNull(f.actor.getMotionObservation());
        f.assertNoRelocation();
    }

    @Test public void knownVisibleAndUnknownWholeBoundsBothResetTheOffScreenRunWithoutSearching() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilTickNine();
        assertEquals(4, f.policy().getOffScreenTicks());
        f.showOrigin();
        when(f.client.getCameraX()).thenReturn(7140);
        // The position anchor is left of the viewport, while part of the real
        // decoded bank union remains visible. An anchor-only gate would be wrong.
        double anchorX = 400d + (f.actor.getLocation().getX() - f.client.getCameraX()) * 512d
            / (f.actor.getLocation().getY() - f.client.getCameraY());
        assertTrue(anchorX < 0);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility());
        f.commit(10);
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(1, f.actor.getPlanningCount());
        f.hideOrigin();
        f.commitsThrough(15);
        assertEquals(5, f.policy().getOffScreenTicks());
        when(f.client.getViewportWidth()).thenReturn(0);
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, f.actor.getWholeBoundsVisibility());
        f.commit(16);
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(1, f.actor.getPlanningCount());
        f.assertNoRelocation();
    }

    @Test public void cameraRevealBetweenTheActualSearchAndPlacementCancelsRecovery() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        when(f.client.getCameraY()).thenReturn(10000, 5952);
        f.commit(11);
        assertEquals(RoutePlanner.Status.ROUTE, f.actor.getLastPlannerStatus());
        assertEquals(2, f.actor.getLastPlannerSteps());
        assertEquals(2, f.actor.getPlanningCount());
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility());
        assertEquals(0, f.policy().getOffScreenTicks());
        assertNull(f.actor.getLatestMotionCompletion());
        f.assertNoRelocation();
    }

    @Test public void visibleP15NeedsFiftyRealBlockedCommitsThenSharesOutstandingRetryGates() throws Exception
    {
        Fixture f = Fixture.withVisibleBlockedRoute();
        f.commitsThrough(60);
        assertEquals(49, f.policy().getBlockedTicks());
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(5, f.actor.getPlanningCount()); // Spawn + normal searches 11,12,13,43.
        f.commit(61);
        assertEquals(50, f.policy().getBlockedTicks());
        assertEquals(5, f.actor.getPlanningCount()); // Recovery is eligible, its retry gate is 73.
        f.commitsThrough(72);
        assertEquals(5, f.actor.getPlanningCount());
        f.commit(73);
        assertEquals(6, f.actor.getPlanningCount());
        f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
        assertEquals(78, f.gate("planNotBefore"));
        assertEquals(103, f.gate("retryAt"));
        f.commitsThrough(102);
        assertEquals(6, f.actor.getPlanningCount());
        f.commit(103);
        assertEquals(7, f.actor.getPlanningCount());
        f.commitsThrough(132);
        assertEquals(7, f.actor.getPlanningCount());
        f.commit(133);
        assertEquals(8, f.actor.getPlanningCount());
        assertEquals(138, f.gate("planNotBefore"));
        assertEquals(163, f.gate("retryAt"));
        f.assertNoRelocation();
    }

    @Test public void aCompletedPhysicalFrontierEdgeResetsTheVisibleBlockedRunWithoutInventingArrival() throws Exception
    {
        Fixture f = Fixture.withVisibleBlockedRoute();
        f.commitsThrough(40);
        assertEquals(29, f.policy().getBlockedTicks());
        f.flags[51][50] = 0;
        f.commit(41); // Exactly one real 30-cycle WALK edge is completed after this decision.
        assertEquals(RoutePlanner.Status.FRONTIER, f.actor.getLastPlannerStatus());
        assertEquals(point(51, 50), f.actor.getLocation());
        MotionObservation progress = f.actor.getMotionObservation();
        assertNotNull(progress);
        assertEquals(1, progress.completedEdges);
        assertFalse(progress.legalGoal);
        assertNull(f.actor.getLatestMotionCompletion());
        f.commit(42);
        assertEquals(0, f.policy().getBlockedTicks());
        assertEquals(0, f.actor.getRecoveryCount());
    }

    @Test public void visibleP15RelocationUsesTheSameObjectAndLegalPlayerRouteThenHoldsTwoTicks() throws Exception
    {
        Fixture f = Fixture.withVisibleBlockedRoute();
        f.commitsThrough(61);
        assertEquals(50, f.policy().getBlockedTicks());
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility());
        f.flags[53][50] = 0; // Fresh meaningful change after the five-tick pause.
        f.flags[35][50] = f.flags[34][50] = 0;
        f.commit(62);
        assertEquals(RoutePlanner.Status.ROUTE, f.actor.getLastPlannerStatus());
        assertEquals(2, f.actor.getLastPlannerSteps());
        assertEquals(1, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration + 1, f.actor.getPlacementGeneration());
        f.assertRecoveredPlacement(point(34, 50));
        long searches = f.actor.getPlanningCount();
        f.commit(63);
        f.commit(64);
        assertEquals(searches, f.actor.getPlanningCount());
        assertEquals(point(34, 50), f.actor.getLocation());
        assertEquals("IDLE", f.actor.getLastClip());
        assertFalse(f.actor.isMoving());
        assertNull(f.actor.getMotionObservation());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    @Test public void offScreenRelocationIsActualPlacementAndNeverAMotionArrival() throws Exception
    {
        Fixture f = new Fixture(false);
        f.holdUntilSeparationSixteen();
        f.openOnlyPlayerDestination();
        f.actor.clearIntent();
        f.commit(11);
        assertEquals(1, f.actor.getRecoveryCount());
        assertEquals(f.originalGeneration + 1, f.actor.getPlacementGeneration());
        f.assertRecoveredPlacement(point(34, 50));
        assertEquals(0, f.policy().getOffScreenTicks());
        assertEquals(0, f.policy().getIneffectivePlans());
        assertNull(f.actor.getMotionObservation());
        assertNull(f.actor.getLatestMotionCompletion());
    }

    private static LocalPoint point(int x, int y)
    {
        return new LocalPoint(x * 128 + 64, y * 128 + 64, -1);
    }

    private static Field field(Class<?> owner, String name) throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static final class Fixture
    {
        final Client client = mock(Client.class);
        final Player player = mock(Player.class);
        final WorldView view = mock(WorldView.class);
        final Scene scene = mock(Scene.class);
        final int[][] flags = new int[104][104];
        final byte[][][] settings = new byte[4][104][104];
        final int[][][] heights = new int[4][105][105];
        final NativeActor actor;
        final long originalGeneration;
        final LocalPoint originalLocation;
        int cycle;
        long tick;
        int playerX = 50, playerY = 50;

        Fixture(boolean visibleOrigin) throws Exception
        {
            actor = new NativeActor(client, new Gson());
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getWorldView(-1)).thenReturn(view);
            when(client.getGameCycle()).thenAnswer(invocation -> cycle);
            when(view.getId()).thenReturn(-1);
            when(view.isTopLevel()).thenReturn(true);
            when(view.getScene()).thenReturn(scene);
            when(view.getSizeX()).thenReturn(104);
            when(view.getSizeY()).thenReturn(104);
            when(view.getBaseX()).thenReturn(3200);
            when(view.getBaseY()).thenReturn(3200);
            when(view.contains(any(LocalPoint.class))).thenReturn(true);
            when(view.getTileSettings()).thenReturn(settings);
            when(view.getTileHeights()).thenReturn(heights);
            for (int[][] plane : heights) for (int[] row : plane) Arrays.fill(row, 120);
            CollisionData collision = mock(CollisionData.class);
            when(collision.getFlags()).thenReturn(flags);
            when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
            when(player.getWorldView()).thenReturn(view);
            when(player.getLocalLocation()).thenAnswer(invocation -> point(playerX, playerY));
            when(client.getViewportWidth()).thenReturn(800);
            when(client.getViewportHeight()).thenReturn(600);
            when(client.getScale()).thenReturn(512);
            when(client.getCameraX()).thenReturn(52 * 128 + 64);
            when(client.getCameraZ()).thenReturn(120);
            if (visibleOrigin) showOrigin(); else hideOrigin();
            actor.loadResources();
            NativeAsset asset = (NativeAsset) field(NativeActor.class, "decodedAsset").get(actor);
            NativePoseBank bank = new NativePoseBank(client, asset);
            Model model = mock(Model.class);
            float[] coordinates = asset.clips.get("IDLE").samples[0];
            float[] y = new float[coordinates.length / 3];
            float minY = 0;
            for (int i = 0; i < y.length; i++) { y[i] = coordinates[i * 3 + 1]; minY = Math.min(minY, y[i]); }
            when(model.getVerticesY()).thenReturn(y);
            when(model.getModelHeight()).thenReturn((int) Math.ceil(-minY));
            // Model preparation is independently exercised by the frozen native harness.
            // These are the only fixture writes into actor/bank internals.
            Arrays.fill((Model[]) field(NativePoseBank.class, "models").get(bank), model);
            field(NativePoseBank.class, "readyAt").setLong(bank, 1);
            field(NativeActor.class, "bank").set(actor, bank);
            actor.setCombatTarget(false, null, 0);
            actor.setIdleMovementSuppressed(true);
            fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
            for (int x = 49; x <= 51; x++) for (int yy = 49; yy <= 51; yy++) flags[x][yy] = 0;
            flags[52][50] = 0; // Exactly one legal initial destination at distance >=2.
            commit(1);
            assertTrue(actor.isVisible());
            assertEquals(point(52, 50), actor.getLocation());
            assertEquals(1, actor.getPlanningCount());
            originalGeneration = actor.getPlacementGeneration();
            originalLocation = actor.getLocation();
            fill(0); // The later player walk is actually legal, not a fabricated teleport.
        }

        static Fixture withThreeFailedOffScreenSearches() throws Exception
        {
            Fixture f = new Fixture(false);
            f.holdUntilSeparationSixteen();
            f.isolateBothOrigins();
            f.actor.clearIntent();
            f.commitsThrough(13);
            assertEquals(3, f.policy().getIneffectivePlans());
            assertEquals(4, f.actor.getPlanningCount());
            return f;
        }

        static Fixture withVisibleBlockedRoute() throws Exception
        {
            Fixture f = new Fixture(true);
            f.holdUntilSeparationSixteen();
            assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility());
            assertEquals(0, f.policy().getOffScreenTicks());
            f.isolateBothOrigins();
            f.actor.clearIntent();
            f.commit(11);
            f.assertFailedSearch(RoutePlanner.Status.BLOCKED, 1, 0);
            assertEquals(0, f.policy().getBlockedTicks());
            return f;
        }

        void holdUntilTickNine() throws Exception
        {
            actor.holdPosition();
            for (int x = 48; x >= 36; x -= 2)
            {
                playerX = x;
                commit(tick + 1);
            }
            commit(9);
            assertEquals(16, actor.getDistanceToPlayer());
            assertEquals(0, policy().getIneffectivePlans());
            assertEquals(1, actor.getPlanningCount());
        }

        void holdUntilSeparationSixteen() throws Exception
        {
            holdUntilTickNine();
            commit(10);
            assertEquals(16, actor.getDistanceToPlayer());
            assertEquals(0, policy().getIneffectivePlans());
        }

        void commit(long ordinal)
        {
            assertTrue(ordinal > tick);
            tick = ordinal;
            long searches = actor.getPlanningCount();
            actor.onGameTick(true, ordinal);
            assertTrue("At most one actual BFS per committed GameTick", actor.getPlanningCount() - searches <= 1);
            clientTicks(30);
        }

        void commitsThrough(long ordinal)
        {
            while (tick < ordinal) commit(tick + 1);
        }

        void clientTicks(int count)
        {
            long searches = actor.getPlanningCount();
            for (int i = 0; i < count; i++) { cycle++; actor.onClientTick(); }
            assertEquals("ClientTick never searches", searches, actor.getPlanningCount());
        }

        void fill(int value)
        {
            for (int[] row : flags) Arrays.fill(row, value);
        }

        void isolateBothOrigins()
        {
            fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
            flags[52][50] = flags[playerX][playerY] = 0;
        }

        void openOnlyPlayerDestination()
        {
            isolateBothOrigins();
            flags[35][50] = flags[34][50] = 0;
        }

        void showOrigin() { when(client.getCameraY()).thenReturn(5952); }
        void hideOrigin() { when(client.getCameraY()).thenReturn(10000); }

        RecoveryPolicy policy() throws Exception
        {
            // Read-only diagnostics: neither policy state nor motion evidence is injected.
            return (RecoveryPolicy) field(NativeActor.class, "recovery").get(actor);
        }

        long gate(String name) throws Exception
        {
            return field(RecoveryPolicy.class, name).getLong(policy());
        }

        void assertFailedSearch(RoutePlanner.Status status, int visited, int steps)
        {
            assertEquals(status, actor.getLastPlannerStatus());
            assertEquals(visited, actor.getLastPlannerVisited());
            assertEquals(steps, actor.getLastPlannerSteps());
            assertTrue(actor.getLastPlannerVisited() <= 625);
            assertTrue(actor.getLastPlannerSteps() <= 24);
            assertFalse(actor.isMoving());
            assertNull(actor.getLatestMotionCompletion());
        }

        void assertNoRelocation()
        {
            assertTrue(actor.isVisible());
            assertEquals(originalLocation, actor.getLocation());
            assertEquals(originalGeneration, actor.getPlacementGeneration());
            assertEquals(0, actor.getRecoveryCount());
            assertNull(actor.getLatestMotionCompletion());
            verify(client, times(1)).registerRuneLiteObject(any());
            verify(client, never()).removeRuneLiteObject(any());
        }

        void assertRecoveredPlacement(LocalPoint destination)
        {
            NativeActor.PlacementSnapshot snapshot = actor.getPlacementSnapshot();
            assertNotNull(snapshot);
            assertEquals(destination, snapshot.getLocation());
            assertSame(view, snapshot.getWorldView());
            assertSame(scene, snapshot.getScene());
            assertEquals(0, snapshot.getPlane());
            assertEquals(120, snapshot.getDrawZ());
            assertEquals(actor.getModel().getModelHeight(), snapshot.getHeadHeight());
            assertEquals("IDLE", actor.getLastClip());
            assertFalse(actor.isMoving());
            ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
            ArgumentCaptor<RuneLiteObjectController> removed = ArgumentCaptor.forClass(RuneLiteObjectController.class);
            verify(client, times(2)).registerRuneLiteObject(registered.capture());
            verify(client, times(1)).removeRuneLiteObject(removed.capture());
            assertSame(registered.getAllValues().get(0), registered.getAllValues().get(1));
            assertSame(registered.getAllValues().get(0), removed.getValue());
        }
    }
}
