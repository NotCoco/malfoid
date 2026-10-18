package com.malfoid.asset;

import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.motion.WholeBoundsVisibility;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.coords.LocalPoint;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static com.malfoid.asset.NativeRecoveryRootFixture.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real native outcomes through root presence, observer, director and rolling-log publication. */
public class NativeRecoveryRootAcceptanceTest
{
    @Test public void aRealRelocationIsSilentKeepsScopeAndLogsItsFreshObservationExactlyOnce() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline();
        Scope scope = new Scope(f); long originalGeneration = f.actor.getPlacementGeneration();
        LocalPoint original = f.actor.getLocation(); History history = new History(f);
        RecoveryObservation observation = f.relocate();

        assertEquals(RecoveryObservation.Cause.OFF_SCREEN, observation.cause);
        assertEquals(RoutePlanner.Status.ROUTE, observation.plannerStatus);
        assertEquals(5, observation.offScreenTicksBefore);
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, observation.decisionVisibility);
        assertEquals(WholeBoundsVisibility.Visibility.OFF_SCREEN, observation.originRecheckVisibility);
        assertTrue(observation.destinationRechecked && observation.destinationLegal && observation.heightRechecked && observation.heightValid);
        assertTrue(observation.visitedCells <= 625 && observation.plannedSteps <= 24);
        assertTrue(f.actor.getDistanceToPlayer() >= 2 && f.actor.getDistanceToPlayer() <= 4);
        assertNotEquals(original, f.actor.getLocation());
        assertEquals(originalGeneration + 1, f.actor.getPlacementGeneration());
        assertSame(f.view, f.actor.getPlacementSnapshot().getWorldView());
        assertSame(f.scene, f.actor.getPlacementSnapshot().getScene());
        assertEquals(120, f.actor.getPlacementSnapshot().getDrawZ());
        scope.assertUnchanged(f); history.assertUnchanged(f); assertSilent(f);
        assertSameObjectReregistered(f);
        assertOneCorrelatedLog(f, observation);

        long searches = f.actor.getPlanningCount(); LocalPoint destination = f.actor.getLocation();
        f.commit(); f.commit(); f.clientTicks(90);
        assertEquals("Two real idle commits cannot start another search", searches, f.actor.getPlanningCount());
        assertEquals(destination, f.actor.getLocation()); assertEquals("IDLE", f.actor.getLastClip());
        assertFalse(f.actor.isMoving()); assertSame(observation, f.actor.getRecoveryObservation());
        assertOneCorrelatedLog(f, observation); scope.assertUnchanged(f); history.assertUnchanged(f); assertSilent(f);
    }

    @Test public void wholeBodyRevealAtAppearanceAgeNineResetsTheRealEpisodeWithoutAReaction() throws Exception
    {
        revealAtAge(9);
    }

    @Test public void wholeBodyRevealAtAppearanceAgeTenHasTheSameSafetyResetAndNoInventedAnnotation() throws Exception
    {
        revealAtAge(10);
    }

    @Test public void unknownProjectionIsLoggedAsUnknownAndCannotCertifyTheVisibleCancellationCase() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline();
        Scope scope = new Scope(f); f.armOffScreenFour(); long searches = f.actor.getPlanningCount();
        when(f.client.getViewportWidth()).thenReturn(0); f.commit();
        RecoveryObservation observation = f.actor.getRecoveryObservation();
        assertNotNull(observation); assertEquals(RecoveryObservation.Disposition.ORIGIN_UNKNOWN, observation.disposition);
        assertEquals(WholeBoundsVisibility.Visibility.UNKNOWN, observation.decisionVisibility);
        assertEquals(4, observation.offScreenTicksBefore); assertEquals(0, observation.offScreenTicksAfter);
        assertNull(observation.plannerStatus); assertFalse(observation.originRechecked);
        assertEquals(searches, f.actor.getPlanningCount()); assertEquals(0, f.actor.getRecoveryCount());
        long id = observation.observationId, episode = observation.episodeId;
        f.clientTicks(90); f.commit(); f.commit();
        assertSame(observation, f.actor.getRecoveryObservation());
        assertEquals(id, observation.observationId); assertEquals(episode, observation.episodeId);
        scope.assertUnchanged(f); assertSilent(f); assertOneCorrelatedLog(f, observation);
        assertTrue(f.recoveryLogs().get(0).contains("result=ORIGIN_UNKNOWN"));
        assertFalse(f.recoveryLogs().get(0).contains("result=ORIGIN_VISIBLE"));
    }

    @Test public void aRealUnpaintedLocalRequestSuppressesArmedRecoveryUntilItsExecutionEnds() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline(); f.armOffScreenFour();
        Scope scope = new Scope(f); LocalPoint source = f.actor.getLocation(); long generation = f.actor.getPlacementGeneration();
        f.requestTalk(); SceneCandidate request = f.director.getActiveCandidate();
        assertNotNull(request); assertEquals("U01", request.getScenarioId());
        f.commit();
        assertEquals(5, f.policy().getOffScreenTicks()); assertTrue(f.actor.getDistanceToPlayer() >= 16);
        assertFalse((boolean) get(f.actor, "recoveryAllowed"));
        assertEquals(request.getId(), f.director.getActiveCandidate().getId());
        assertNull(f.director.getPacket()); assertFalse(f.director.hasArrived());
        assertEquals(0, f.actor.getRecoveryCount()); assertEquals(source, f.actor.getLocation());
        assertEquals(generation, f.actor.getPlacementGeneration());
        scope.assertUnchanged(f); assertNoCommittedSpeech(f);

        f.waitUntilIdle(); assertNoCommittedSpeech(f);
        History history = new History(f);
        while (f.tick() + 1 < f.policy().getPlanNotBefore()) f.commit();
        f.openFreshSourceNeighbor(); f.commit();
        RecoveryObservation observation = f.actor.getRecoveryObservation();
        assertNotNull(observation); assertEquals(RecoveryObservation.Disposition.RELOCATED, observation.disposition);
        scope.assertUnchanged(f); history.assertUnchanged(f); assertSilent(f); assertOneCorrelatedLog(f, observation);
    }

    @Test public void genuinelyPaintedDeathReunionHoldSuppressesRecoveryWithoutDiscardingItsToken() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline();
        SceneCandidate reunion = f.deathReunion(); SpeechPacket.PaintReceipt paint = f.paintCurrent();
        assertEquals(reunion.getId(), paint.getCandidate().getId());
        long deathToken = f.rules().getDeathToken(); assertTrue(deathToken > 0);
        Scope scope = new Scope(f); History history = new History(f);
        LocalPoint source = f.actor.getLocation(); long generation = f.actor.getPlacementGeneration();
        f.hideOrigin(); f.boxSource();
        for (int i = 0; f.policy().getOffScreenTicks() < 5 && i < 16; i++)
        {
            f.walkAwayFromSource(); f.commit();
            assertEquals(reunion.getId(), f.director.getActiveCandidate().getId());
            assertEquals(0, f.actor.getRecoveryCount());
        }
        assertEquals(5, f.policy().getOffScreenTicks()); assertTrue(f.actor.getDistanceToPlayer() >= 16);
        assertFalse((boolean) get(f.actor, "recoveryAllowed"));
        assertEquals(source, f.actor.getLocation()); assertEquals(generation, f.actor.getPlacementGeneration());
        assertSame(paint, f.director.getLastPaint()); assertEquals(deathToken, f.rules().getDeathToken());
        scope.assertUnchanged(f); history.assertCommittedUnchanged(f); assertTrue(f.recoveryLogs().isEmpty());
    }

    @Test public void relocationAfterGenuineReunionCompletionPreservesPositiveSpeechDeathRegionAndCooldownHistory() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline();
        SceneCandidate reunion = f.deathReunion(); SpeechPacket.PaintReceipt paint = f.paintCurrent();
        f.waitUntilIdle();
        long reunionTick = (long) get(f.rules(), "reunionTick"); assertTrue(reunionTick >= paint.getPaintedAtTick());
        assertTrue(f.trace.contains("SCENE_COMPLETED scenario=M02"));
        assertTrue(f.rules().getDeathToken() > 0); assertNotNull(get(f.rules(), "region"));
        assertEquals(1, f.guard.textCount()); assertEquals(1, f.guard.interactionCount());
        String repeatKey = f.catalogue.repeatKey(reunion.getScenarioId());
        assertFalse(f.guard.interactionEligible(repeatKey));
        Scope scope = new Scope(f); f.armOffScreenFour(); History history = new History(f);
        RecoveryObservation observation = f.finishArmedRelocation();
        scope.assertUnchanged(f); history.assertUnchanged(f);
        assertSame(paint, f.director.getLastPaint()); assertFalse(f.guard.interactionEligible(repeatKey));
        assertEquals(reunionTick, (long) get(f.rules(), "reunionTick"));
        assertNull(f.director.getActiveCandidate()); assertNull(f.director.getPacket()); assertEquals(0, f.director.pendingCount());
        assertEquals(0, pixels(f.render())); assertNull(f.actor.getLatestMotionCompletion());
        assertOneCorrelatedLog(f, observation);
    }

    @Test public void aRealDepartureQueueExpiresBeforeRecoveryAndItsCommittedParentHistorySurvives() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline(); f.showOrigin();
        f.requestTalk(); f.commit(); SpeechPacket packet = f.director.getPacket();
        SpeechPacket.PaintReceipt paint = f.paintCurrent(); assertNotNull(get(f.rules(), "parent"));
        f.commit(); f.boxSource(); f.playerX = 48; f.commit(); f.playerX = 46; f.commit();
        assertEquals("U01", f.director.getActiveCandidate().getScenarioId());
        @SuppressWarnings("unchecked") List<SceneCandidate> pending = (List<SceneCandidate>) get(f.director, "pending");
        SceneCandidate departure = pending.stream().filter(c -> "M07".equals(c.getScenarioId())).findFirst().orElse(null);
        assertNotNull("Actual painted-parent departure must create the queued M07", departure);
        assertEquals(Long.toString(paint.getAdmissionToken()), departure.getConditionFacts().get("parentUtteranceId"));
        assertFalse((boolean) get(f.actor, "recoveryAllowed")); assertEquals(0, f.actor.getRecoveryCount());
        assertEquals("A queued departure has not committed an interaction cooldown", 0, f.guard.interactionCount());
        assertFalse("The painted request settled its reservation; queued M07 has not reserved", f.guard.hasPendingReservation());
        f.waitUntilIdle(); assertTrue(f.tick() >= departure.getExpiresTick());
        assertNull(get(f.rules(), "parent")); assertNull(f.director.getPacket());
        assertSame(paint, f.director.getLastPaint()); assertEquals(0, pixels(f.render()));
        assertEquals(1, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
        Scope scope = new Scope(f); History history = new History(f);
        RecoveryObservation observation = f.relocate();
        scope.assertUnchanged(f); history.assertUnchanged(f); assertSame(paint, f.director.getLastPaint());
        assertNull(f.director.getPacket()); assertEquals(0, f.director.pendingCount());
        assertEquals(0, pixels(f.render())); assertNotNull(packet.getFirstPaint()); assertOneCorrelatedLog(f, observation);
    }

    @Test public void confirmedNpcCombatCannotCertifyM64EvenWhenExistingSafetyRecoveryPhysicallyRelocates() throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture(); f.baseline(); f.boxSource();
        f.confirmNpcCombat(); assertTrue(f.director.isIdle());
        Scope scope = new Scope(f); RecoveryObservation observation = f.relocate();
        assertEquals(ScenarioRules.CombatState.CONFIRMED, f.observer.getCombatState());
        assertEquals(RecoveryObservation.Disposition.RELOCATED, observation.disposition);
        scope.assertUnchanged(f); assertSilent(f); assertOneCorrelatedLog(f, observation);
        // This is physical safety-policy characterization, not proof of M64 eligibility.
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M64"));
    }

    private static void revealAtAge(int age) throws Exception
    {
        NativeRecoveryRootFixture f = new NativeRecoveryRootFixture();
        long expectedTick = f.presence.getPresentSinceTick() + age;
        f.commitsThrough(expectedTick - 9); Scope scope = new Scope(f); History history = new History(f);
        f.boxSource();
        for (int x : new int[]{48, 46, 44, 42, 40, 38, 39}) { f.playerX = x; f.commit(); }
        f.commit(); assertEquals(expectedTick - 1, f.tick()); assertEquals(4, f.policy().getOffScreenTicks());
        long searches = f.actor.getPlanningCount(), generation = f.actor.getPlacementGeneration();
        LocalPoint source = f.actor.getLocation(); f.showOrigin(); when(f.client.getCameraX()).thenReturn(7140);
        double anchorX = 400d + (source.getX() - f.client.getCameraX()) * 512d / (source.getY() - f.client.getCameraY());
        assertTrue("Position anchor is outside while approved whole-body union is revealed", anchorX < 0);
        assertEquals(WholeBoundsVisibility.Visibility.VISIBLE, f.actor.getWholeBoundsVisibility()); f.commit();
        RecoveryObservation observation = f.actor.getRecoveryObservation(); assertNotNull(observation);
        assertEquals(expectedTick, observation.committedTick); assertEquals(age, f.tick() - f.presence.getPresentSinceTick());
        assertEquals(RecoveryObservation.Disposition.ORIGIN_VISIBLE, observation.disposition);
        assertEquals(4, observation.offScreenTicksBefore); assertEquals(0, observation.offScreenTicksAfter);
        assertNull(observation.plannerStatus); assertFalse(observation.originRechecked);
        assertEquals(searches, f.actor.getPlanningCount()); assertEquals(0, f.actor.getRecoveryCount());
        assertEquals(source, f.actor.getLocation()); assertEquals(generation, f.actor.getPlacementGeneration());
        f.clientTicks(90); f.commit(); f.commit(); assertSame(observation, f.actor.getRecoveryObservation());
        scope.assertUnchanged(f); history.assertUnchanged(f); assertSilent(f); assertOneCorrelatedLog(f, observation);
        assertFalse("Age ten does not open unpassed source/projection/hardware gates", f.catalogue.isRuntimeScenarioEnabled("M65"));
    }

    private static void assertSilent(NativeRecoveryRootFixture f) throws Exception
    {
        assertNoCommittedSpeech(f);
        assertNull(f.director.getActiveCandidate()); assertEquals(0, f.director.pendingCount());
        assertFalse(f.guard.hasPendingReservation()); assertNull(f.actor.getMotionObservation());
        assertNull(f.actor.getLatestMotionCompletion());
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M64"));
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M65"));
        assertFalse(f.catalogue.isRuntimeScenarioEnabled("M66"));
    }

    private static void assertNoCommittedSpeech(NativeRecoveryRootFixture f)
    {
        assertNull(f.director.getLastPaint()); assertEquals(0, f.guard.textCount());
        assertEquals(0, f.guard.interactionCount()); assertEquals(0, f.guard.situationCount());
        assertNull(f.director.getPacket()); assertEquals(0, pixels(f.render()));
    }

    private static void assertOneCorrelatedLog(NativeRecoveryRootFixture f, RecoveryObservation observation) throws Exception
    {
        List<String> logs = f.recoveryLogs(); assertEquals(1, logs.size()); String line = logs.get(0);
        assertTrue(line.contains(" id=" + observation.observationId + " tick=" + observation.committedTick));
        assertTrue(line.contains(" session=" + get(f.session, "sessionGeneration") + " scene=" + get(f.session, "sceneRevision")));
        assertTrue(line.contains(" nativeScene=" + observation.sceneRevision));
        assertTrue(line.contains(" present=true epoch=" + f.presence.getPresenceEpoch() + " since=" + f.presence.getPresentSinceTick()));
        assertTrue(line.contains(" view=" + observation.worldViewId + " plane=" + observation.plane));
        assertTrue(line.contains(" generation=" + observation.placementGenerationBefore + "->" + observation.placementGenerationAfter));
        assertTrue(line.contains(" episode=" + observation.episodeId + " start=" + observation.episodeStartedTick));
        assertTrue(line.contains(" result=" + observation.disposition));
        assertTrue(line.contains(" raw=" + observation.plannerStatus + " limited=" + observation.searchLimited));
        assertTrue(line.contains(" pauseUntil=" + observation.planNotBefore + " retryAt=" + observation.retryAt));
        assertEquals(observation.observationId, (long) get(f.session, "lastRecoveryObservationId"));
    }

    private static void assertSameObjectReregistered(NativeRecoveryRootFixture f)
    {
        ArgumentCaptor<RuneLiteObjectController> registered = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        ArgumentCaptor<RuneLiteObjectController> removed = ArgumentCaptor.forClass(RuneLiteObjectController.class);
        verify(f.client, times(2)).registerRuneLiteObject(registered.capture());
        verify(f.client, times(1)).removeRuneLiteObject(removed.capture());
        assertSame(registered.getAllValues().get(0), registered.getAllValues().get(1));
        assertSame(registered.getAllValues().get(0), removed.getValue());
    }

    private static final class Scope
    {
        final long generation, scene, epoch, since, presenceScene, account;
        final int world;
        Scope(NativeRecoveryRootFixture f) throws Exception
        {
            generation = (long) get(f.session, "sessionGeneration"); scene = (long) get(f.session, "sceneRevision");
            account = (long) get(f.session, "accountHash"); world = (int) get(f.session, "world");
            epoch = f.presence.getPresenceEpoch(); since = f.presence.getPresentSinceTick();
            presenceScene = f.presence.getSceneGeneration();
        }
        void assertUnchanged(NativeRecoveryRootFixture f) throws Exception
        {
            assertEquals(generation, (long) get(f.session, "sessionGeneration"));
            assertEquals(scene, (long) get(f.session, "sceneRevision"));
            assertEquals(account, (long) get(f.session, "accountHash")); assertEquals(world, (int) get(f.session, "world"));
            assertEquals(epoch, f.presence.getPresenceEpoch()); assertEquals(since, f.presence.getPresentSinceTick());
            assertEquals(presenceScene, f.presence.getSceneGeneration()); assertTrue(f.presence.isPresent());
            assertSame(f.view, f.actor.getPlacementSnapshot().getWorldView()); assertSame(f.scene, f.actor.getPlacementSnapshot().getScene());
        }
    }

    private static final class History
    {
        final long deathToken, reunionTick;
        final Object region;
        final SpeechPacket.PaintReceipt lastPaint;
        final List<?> recentLines;
        final Map<?, ?> texts, interactions, situations, retry;
        History(NativeRecoveryRootFixture f) throws Exception
        {
            deathToken = f.rules().getDeathToken(); reunionTick = (long) get(f.rules(), "reunionTick"); region = get(f.rules(), "region");
            lastPaint = f.director.getLastPaint(); recentLines = new ArrayList<>((Collection<?>) get(f.director, "recentLines"));
            texts = copyMap(f.guard, "texts"); interactions = copyMap(f.guard, "interactions");
            situations = copyMap(f.guard, "situations"); retry = copyMap(f.director, "retryUntil");
        }
        void assertUnchanged(NativeRecoveryRootFixture f) throws Exception
        {
            assertEquals(region, get(f.rules(), "region"));
            assertCommittedUnchanged(f);
        }
        void assertCommittedUnchanged(NativeRecoveryRootFixture f) throws Exception
        {
            assertEquals(deathToken, f.rules().getDeathToken()); assertEquals(reunionTick, (long) get(f.rules(), "reunionTick"));
            assertSame(lastPaint, f.director.getLastPaint());
            assertEquals(recentLines, new ArrayList<>((Collection<?>) get(f.director, "recentLines")));
            assertEquals(texts, copyMap(f.guard, "texts")); assertEquals(interactions, copyMap(f.guard, "interactions"));
            assertEquals(situations, copyMap(f.guard, "situations")); assertEquals(retry, copyMap(f.director, "retryUntil"));
        }
        private static Map<?, ?> copyMap(Object target, String name) throws Exception
        { return new HashMap<>((Map<?, ?>) get(target, name)); }
    }
}
