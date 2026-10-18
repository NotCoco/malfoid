package com.malfoid.acceptance;

import com.malfoid.MalfoidPlugin;
import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ScenarioSnapshot;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.GameObject;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.events.GameObjectSpawned;
import org.junit.Test;
import static com.malfoid.acceptance.AdditionalProducerFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The existing native M08 proof, route, edge completion and root arrival hooks deliver scenery. */
public class LivingWorldSceneryDeliveryAcceptanceTest
{
    @Test public void aBookcaseUsesARealWalkArrivalAndItsPaintDoesNotCreateM69() throws Exception
    {
        Fixture f = new Fixture("Bookcase"); f.start();
        SceneCandidate candidate = f.world.director.getActiveCandidate();
        assertEquals("SC-BOOKS", candidate.getScenarioId()); assertNull(f.world.director.getPacket());
        long deadline = candidate.getExpiresTick();
        while (f.world.director.getPacket() == null && f.world.f.tick() < deadline - 1)
        {
            f.world.cycles(30); f.world.commit();
        }
        SpeechPacket packet = f.world.issued("SC-BOOKS");
        MotionObservation arrival = f.world.f.actor.getLatestMotionCompletion();
        assertNotNull("Native motion owns the actual edge receipt", arrival);
        assertEquals(MotionObservation.Status.ARRIVED, arrival.status);
        assertEquals(candidate.getId(), arrival.sourceCandidateId);
        assertTrue(arrival.completedEdges >= 1 && arrival.completedEdges <= 8);
        assertEquals(0, arrival.completedRunEdges);
        assertEquals(9, f.world.director.getExecutionHoldTicks());
        f.world.paint(packet); f.world.cycles(30); f.world.hold(12);
        assertTrue(f.world.director.isIdle());
        f.world.hold(110);
        assertTrue("The visited physical object cannot produce a second trip or an M69 follow-up", f.world.director.isIdle());
    }

    @Test public void aChangedResolvedNameBeforeArrivalCannotIssueOrDebitASceneryLine() throws Exception
    {
        Fixture f = new Fixture("Bookcase"); f.start();
        SceneCandidate original = f.world.director.getActiveCandidate(); assertNotNull(original);
        when(f.definition.getName()).thenReturn("Bookcase (empty)");
        f.world.f.inspect();
        assertNull(f.world.director.getPacket()); assertNull(f.world.director.getActiveCandidate());
        assertEquals(0, f.world.guard.interactionCount()); assertEquals(0, f.world.guard.textCount());
        f.world.cycles(30); f.world.hold(4);
        assertTrue(f.world.director.isIdle()); assertNull(f.world.director.getLastPaint());
    }

    @Test public void anAcceptedSilentZeroEdgeVisitStillKeepsInspectionStartsOneHundredTicksApart() throws Exception
    {
        Fixture f = new Fixture("Bookcase", true);
        when(f.world.f.config.showDialogue()).thenReturn(false);
        f.start(); long started = f.world.f.tick();
        assertEquals("0", f.world.director.getActiveCandidate().getConditionFacts().get("pathSteps"));
        assertTrue(f.world.director.hasArrived()); assertTrue(f.world.director.isCommitted());
        assertEquals(started, f.world.f.actor.getLastInspectionStartTick());
        assertEquals("No edge was invented for an already legal stand", -1, f.world.f.actor.getLastVoluntaryMovementTick());
        assertNull(f.world.director.getLastPaint()); assertTrue(f.world.director.livingWorldSpacingReady());
        f.world.hold(9); assertTrue(f.world.director.isIdle());
        // Keep the native fixture resting so only the inspection spacing gate is under test.
        set(f.world.f.actor, "settledSinceTick", f.world.f.tick());
        set(f.world.f.actor, "nextIdleShiftTick", Long.MAX_VALUE);
        while (f.world.f.tick() < started + 99)
        {
            f.world.commit();
            assertTrue("A second zero-edge visit cannot bypass P27", f.world.director.isIdle());
        }
        assertEquals(started, f.world.f.actor.getLastInspectionStartTick());
        f.world.commit();
        assertEquals(started + 100, f.world.f.tick());
        assertNotNull("At 100 ticks the real native route probe is accepted", get(f.world.f.actor, "inspectionRequest"));
        f.world.commit();
        SceneCandidate second = f.world.director.getActiveCandidate(); assertNotNull(second);
        assertEquals("SC-STATUE", second.getScenarioId());
        assertTrue(f.world.f.actor.getLastInspectionStartTick() - started >= 100);
        assertEquals(-1, f.world.f.actor.getLastVoluntaryMovementTick());
        assertEquals(0, f.world.guard.textCount()); assertEquals(0, f.world.guard.interactionCount());
    }

    private static final class Fixture
    {
        final LivingWorldDeliveryFixture world = new LivingWorldDeliveryFixture();
        final ObjectComposition definition = mock(ObjectComposition.class);
        Fixture(String name) throws Exception { this(name, false); }
        Fixture(String name, boolean zeroEdgePair) throws Exception
        {
            doReturn(indexedSet(new ArrayList<NPC>())).when(world.f.view).npcs();
            doReturn(indexedSet(new ArrayList<Player>())).when(world.f.view).players();
            world.enableNative(); world.baseline();
            world.f.observer.setScenarioEnabled(id -> "M147".equals(id) || world.cat.isLivingWorldScenario(id));
            world.f.observer.setInspectionSuppression(world.f.actor::isInspectionSuppressed);
            Method eligible = MalfoidPlugin.class.getDeclaredMethod("livingWorldBaseEligible", world.f.session.getClass());
            eligible.setAccessible(true);
            world.f.observer.setSceneryStartAllowed(() ->
            {
                try { return (Boolean) eligible.invoke(world.f.plugin, world.f.session)
                    && world.director.livingWorldSpacingReady() && !world.f.observer.isPlayerMoving()
                    && !world.f.actor.isInspectionSuppressed(); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            Method inspect = MalfoidPlugin.class.getDeclaredMethod("inspectionTarget", world.f.session.getClass(),
                String.class, String.class, int.class, int.class, int.class, int.class, int.class);
            inspect.setAccessible(true);
            world.f.observer.setInspectionReachability((key, resolved, minX, minY, maxX, maxY, plane) ->
            {
                try { return (ScenarioSnapshot.InspectionTarget) inspect.invoke(world.f.plugin, world.f.session,
                    key, resolved, minX, minY, maxX, maxY, plane); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            });
            Tile[][][] tiles = new Tile[4][104][104];
            when(world.f.view.getScene().getTiles()).thenReturn(tiles);
            object(100, name, zeroEdgePair ? 53 : 52, zeroEdgePair ? 50 : 52, definition, tiles);
            if (zeroEdgePair) { object(101, "Statue", 54, 51, mock(ObjectComposition.class), tiles); }
        }
        private void object(int id, String name, int x, int y, ObjectComposition definition, Tile[][][] tiles)
        {
            when(definition.getId()).thenReturn(id); when(definition.getName()).thenReturn(name);
            when(world.f.client.getObjectDefinition(id)).thenReturn(definition);
            GameObject object = mock(GameObject.class);
            when(object.getId()).thenReturn(id); when(object.getWorldView()).thenReturn(world.f.view);
            when(object.getPlane()).thenReturn(0);
            when(object.getSceneMinLocation()).thenReturn(new Point(x, y));
            when(object.getSceneMaxLocation()).thenReturn(new Point(x, y));
            Tile tile = mock(Tile.class); when(tile.getGameObjects()).thenReturn(new GameObject[]{object}); tiles[0][x][y] = tile;
            GameObjectSpawned event = new GameObjectSpawned(); event.setGameObject(object); event.setTile(tile);
            world.f.plugin.onGameObjectSpawned(event);
        }
        void start() throws Exception
        {
            for (int i = 0; i < 6 && world.director.isIdle(); i++) { world.commit(); }
            assertNotNull("Root must admit the proven scenery trip", world.director.getActiveCandidate());
            assertEquals("SC-BOOKS", world.director.getActiveCandidate().getScenarioId());
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
