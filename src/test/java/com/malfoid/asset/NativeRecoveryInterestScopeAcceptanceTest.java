package com.malfoid.asset;

import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RecoveryObservation;
import com.malfoid.scenario.InspectionCompletionRecord;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.scenario.ScenarioSnapshot;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameObjectSpawned;
import org.junit.Test;

import static com.malfoid.asset.NativeRecoveryRootFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Only actual M08 full completion and actual native recovery supply positive scope evidence. */
public class NativeRecoveryInterestScopeAcceptanceTest
{
    @Test public void actualRecoveryDisposesCompletedInterestWhileKeepingCommittedDialogueAndCooldowns() throws Exception
    {
        runRecovery(false, false);
    }

    @Test public void anInterveningGenuineUnknownCombatObservationCannotCarryOldInterestAcrossRecovery() throws Exception
    {
        runRecovery(true, false);
    }

    @Test public void anUnknownAdapterPlacementMarkerDoesNotForgetTheLastKnownCompletedInterestScope() throws Exception
    {
        runRecovery(false, true);
    }

    private static void runRecovery(boolean unknownCombat, boolean unknownPlacement) throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture();
        f.baseline(); f.showOrigin(); f.requestTalk(); f.commit();
        SpeechPacket.PaintReceipt priorPaint = f.paintCurrent(); f.waitUntilIdle();
        assertEquals(1, f.guard.textCount());
        SceneCandidate inspection = completeFountain(f);
        Deque<InspectionCompletionRecord> records = completionRecords(f);
        assertEquals(1, records.size()); InspectionCompletionRecord completed = records.getFirst();
        assertEquals("FOUNTAIN", completed.category); assertEquals("Fountain", completed.name);
        assertEquals(inspection.getConditionFacts().get("targetKey"), completed.key);
        assertTrue(f.trace.contains("SCENE_COMPLETED scenario=M08"));
        assertFalse(f.rules().canInspectIdentity(completed.key));
        assertTrue(f.director.isIdle()); assertEquals(1, f.guard.interactionCount());
        long oldPlacement = f.actor.getPlacementGeneration();
        long generation = (long) get(f.session, "sessionGeneration"), scene = (long) get(f.session, "sceneRevision");
        long epoch = f.presence.getPresenceEpoch(), since = f.presence.getPresentSinceTick();
        Map<?, ?> textHistory = new HashMap<>((Map<?, ?>) get(f.guard, "texts"));
        Map<?, ?> interactionHistory = new HashMap<>((Map<?, ?>) get(f.guard, "interactions"));
        assertFalse(f.guard.interactionEligible(f.catalogue.repeatKey("M08")));
        f.clientTicks(90);
        assertEquals("Same placement callbacks preserve truthful completed context", 1, records.size());

        if (unknownCombat)
        {
            NPC unresolved = mock(NPC.class);
            when(unresolved.getWorldView()).thenReturn(f.view);
            when(unresolved.getTransformedComposition()).thenReturn(null);
            when(f.player.getInteracting()).thenReturn(unresolved);
            f.commit(); assertEquals(ScenarioRules.CombatState.UNKNOWN, f.observer.getCombatState());
            assertEquals("UNKNOWN is genuinely observed before recovery; it is not a completed-interest reset", 1, records.size());
        }
        f.armOffScreenFour();
        while (f.tick() + 1 < f.policy().getPlanNotBefore()) f.commit();
        if (unknownPlacement)
        {
            // Adapter boundary only: do not mutate NativeActor or fabricate a
            // replacement placement/recovery. The following root commit obtains
            // the real new generation after actual registration succeeds.
            f.rules().setMotionObservations(null, null, -1);
            assertEquals(-1, f.rules().getCurrent().placementGeneration);
            assertEquals(1, records.size());
        }
        LocalPoint source = f.actor.getLocation();
        f.flags[source.getSceneX()][source.getSceneY() + 1] = 0; // Fresh legal change, leaving Fountain body collision intact.
        f.commit();
        RecoveryObservation observation = f.actor.getRecoveryObservation();
        assertNotNull(observation); assertEquals(RecoveryObservation.Disposition.RELOCATED, observation.disposition);
        assertEquals(1, f.actor.getRecoveryCount()); assertEquals(oldPlacement + 1, f.actor.getPlacementGeneration());
        assertEquals(oldPlacement, observation.placementGenerationBefore);
        assertEquals(oldPlacement + 1, observation.placementGenerationAfter);
        assertTrue("Existing two-tick reset is stationary", !f.actor.isMoving() && "IDLE".equals(f.actor.getLastClip()));
        assertTrue("Pre-recovery interest completion is disposed at the new known placement", records.isEmpty());
        assertNull(get(f.rules(), "objectOffered"));
        assertFalse("The permitted last-two identity history is retained", f.rules().canInspectIdentity(completed.key));
        assertEquals(generation, (long) get(f.session, "sessionGeneration"));
        assertEquals(scene, (long) get(f.session, "sceneRevision"));
        assertEquals(epoch, f.presence.getPresenceEpoch()); assertEquals(since, f.presence.getPresentSinceTick());
        assertSame(priorPaint, f.director.getLastPaint());
        assertEquals(textHistory, new HashMap<>((Map<?, ?>) get(f.guard, "texts")));
        assertEquals(interactionHistory, new HashMap<>((Map<?, ?>) get(f.guard, "interactions")));
        assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket()); assertEquals(0, f.director.pendingCount());
        assertEquals(0, pixels(f.render())); assertNull(f.actor.getLatestMotionCompletion());
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M64"));
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M65"));
        assertTrue("Existing M69 behavior remains enabled", f.catalogue.isRuntimeScenarioEnabled("M69"));
        f.assertHealthy();
    }

    private static SceneCandidate completeFountain(NativeRecoveryRootFixture f) throws Exception
    {
        MalfoidConfig config = (MalfoidConfig) get(f.plugin, "config");
        when(config.showDialogue()).thenReturn(false); // Existing valid intentional-silent M08 branch.
        f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) ->
            (ScenarioSnapshot.InspectionTarget) inspectionTarget(f, key, name, minX, minY, maxX, maxY, plane));
        Tile[][][] tiles = f.scene.getTiles(); Tile tile = mock(Tile.class); GameObject fountain = mock(GameObject.class);
        when(tile.getGameObjects()).thenReturn(new GameObject[]{fountain}); tiles[0][54][50] = tile;
        when(fountain.getId()).thenReturn(40001); when(fountain.getWorldView()).thenReturn(f.view);
        when(fountain.getPlane()).thenReturn(0);
        when(fountain.getSceneMinLocation()).thenReturn(new net.runelite.api.Point(54, 50));
        when(fountain.getSceneMaxLocation()).thenReturn(new net.runelite.api.Point(54, 50));
        ObjectComposition definition = mock(ObjectComposition.class); when(definition.getName()).thenReturn("Fountain");
        when(f.client.getObjectDefinition(40001)).thenReturn(definition);
        f.flags[54][50] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        GameObjectSpawned event = new GameObjectSpawned(); event.setTile(tile); event.setGameObject(fountain);
        f.plugin.onGameObjectSpawned(event); f.assertHealthy();
        for (int i = 0; f.director.getActiveCandidate() == null && i < 8; i++) f.commit();
        SceneCandidate candidate = f.director.getActiveCandidate(); assertNotNull(candidate);
        assertEquals("M08", candidate.getScenarioId()); assertTrue(f.director.isIntentionalSilent());
        for (int i = 0; completionRecords(f).isEmpty() && i < 24; i++) f.commit();
        assertEquals("Only actual matching arrival and full hold completion records Fountain", 1, completionRecords(f).size());
        assertTrue(f.director.isIdle()); assertNull(f.director.getPacket());
        MotionObservation original = f.actor.getMotionObservation(); assertNotNull(original);
        assertEquals(candidate.getId(), original.sourceCandidateId);
        assertTrue(original.completedEdges > 0 && original.completedEdges <= 8);
        assertTrue(original.known && original.legalGoal);
        return candidate;
    }

    private static Object inspectionTarget(NativeRecoveryRootFixture f, String key, String name,
        int minX, int minY, int maxX, int maxY, int plane)
    {
        try
        {
            Method method = MalfoidPlugin.class.getDeclaredMethod("inspectionTarget", f.session.getClass(),
                String.class, String.class, int.class, int.class, int.class, int.class, int.class);
            method.setAccessible(true);
            return method.invoke(f.plugin, f.session, key, name, minX, minY, maxX, maxY, plane);
        }
        catch (InvocationTargetException error) { throw new AssertionError(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @SuppressWarnings("unchecked") private static Deque<InspectionCompletionRecord> completionRecords(NativeRecoveryRootFixture f) throws Exception
    { return (Deque<InspectionCompletionRecord>) get(f.rules(), "inspectionCompletions"); }
}
