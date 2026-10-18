package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.scenario.ScenarioBatch;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.scenario.ScenarioSnapshot;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.*;

public class LevelRebindIntegrationTest
{
    @Test
    public void actualWitnessedLevelFactPassesOnlyItsOneDirectorOwnedRebindAndOriginalGrace() throws Exception
    {
        ScenarioRules rules = new ScenarioRules();
        ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
        frame.player = new ScenarioSnapshot.Position(3100, 3200, 0);
        frame.companion = new ScenarioSnapshot.Position(3103, 3200, 0);
        frame.xp = new int[Skill.values().length]; Arrays.fill(frame.xp, 100);
        frame.levels = new int[Skill.values().length]; Arrays.fill(frame.levels, 10);
        frame.boosted = frame.levels.clone();
        for (frame.tick = 1; frame.tick < 10; frame.tick++)
        {
            rules.close(frame.build(), ScenarioBatch.empty());
        }
        frame.xp[Skill.COOKING.ordinal()] += 100;
        frame.levels[Skill.COOKING.ordinal()] = 20;
        ScenarioBatch observedProgress = new ScenarioBatch(false, false, false, Collections.emptyList(),
            Collections.emptyList(), Collections.singleton(Skill.COOKING));
        List<SceneCandidate> output = rules.close(frame.build(), observedProgress);
        SceneCandidate original = output.stream().filter(candidate -> "M04".equals(candidate.getScenarioId())).findFirst().orElse(null);
        assertNotNull(original);
        assertEquals("10", original.getConditionFacts().get("occurredTick"));
        assertEquals("10", original.getConditionFacts().get("settlingNotBeforeTick"));
        SpeechGuard guard = new SpeechGuard(() -> 0, new Random(1), 630, 141);
        SpeechDirector director = new SpeechDirector(DialogueCatalog.load(getClass()), () -> 0, new Random(1), ignored -> {}, guard);
        director.setCandidateValidator(rules::stillValid);
        director.tick(10, 1, 1, true, true, false);
        assertTrue(director.offer(original));
        director.suspendForOrdinaryLoading(11, 1);
        frame.tick = 11; frame.sceneRevision = 2; frame.valid = false; frame.present = false;
        rules.close(frame.build(), ScenarioBatch.empty());
        director.tick(11, 1, 2, false, true, false);
        frame.tick = 12; frame.valid = true; frame.present = true; frame.presentSinceTick = 12;
        assertTrue(rules.close(frame.build(), ScenarioBatch.empty()).stream().noneMatch(candidate -> "M04".equals(candidate.getScenarioId())));
        director.tick(12, 1, 2, true, true, false);
        director.resumeAfterLoading(12, 1, 2, true);
        assertNull(director.getActiveCandidate());
        frame.tick = 13;
        assertTrue(rules.close(frame.build(), ScenarioBatch.empty()).stream().noneMatch(candidate -> "M04".equals(candidate.getScenarioId())));
        director.tick(13, 1, 2, true, true, false);
        director.resumeAfterLoading(13, 1, 2, true);
        SceneCandidate rebound = director.getActiveCandidate();
        assertNotNull(rebound);
        assertEquals(original.getId(), rebound.getId());
        assertEquals(original.getExpiresTick(), rebound.getExpiresTick());
        assertEquals(1, rebound.getRebindCount());
        assertTrue(rules.stillValid(rebound));
        SceneCandidate forgedSceneUpdate = new SceneCandidate(original.getId(), "M04", original.getPriority(),
            original.getCreatedTick(), original.getExpiresTick(), 1, 2, original.getCausalToken(),
            original.getConditionFacts(), false, original.getHoldTicks());
        assertFalse(rules.stillValid(forgedSceneUpdate));
        director.arrive(rebound.getId(), 13);
        assertNotNull(director.getPacket());
        frame.tick = 14; frame.player = new ScenarioSnapshot.Position(3200, 3200, 0);
        rules.close(frame.build(), ScenarioBatch.empty());
        assertFalse(rules.stillValid(rebound));
        director.invalidate(false);
        assertTrue(director.isIdle());
        assertEquals(0, guard.interactionCount());
        assertEquals(0, guard.textCount());
    }
}
