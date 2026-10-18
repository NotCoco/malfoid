package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.find;
import static org.junit.Assert.*;

public class InspectionCompletionRulesTest
{
	@Test public void fullTypedCompletionDecoratesOneCurrentInterestWithItsOriginalCauseAndDeadline()
	{
		ScenarioReplay base = completedFountain(), variant = completedFountain(); base.rules.setScenarioEnabled(id -> !"M69".equals(id));
		for (ScenarioReplay r : new ScenarioReplay[]{base, variant}) { r.frame.objects = Collections.singletonList(object("tree", "Tree")); r.frame.directorIdle = true; }
		SceneCandidate a = find(base.next(), "M08"), b = find(variant.next(), "M69"); assertNotNull(a); assertNotNull(b);
		assertEquals(a.getId(), b.getId()); assertEquals(a.getCausalToken(), b.getCausalToken()); assertEquals(a.getCreatedTick(), b.getCreatedTick());
		assertEquals(a.getExpiresTick(), b.getExpiresTick()); assertEquals(a.getHoldTicks(), b.getHoldTicks());
	}

	@Test public void priorFountainCompletionSelectsOneTreeVariantAndKeepsObjectLeaseValidation()
	{
		ScenarioReplay r = completedFountain(); r.frame.objects = Collections.singletonList(object("tree", "Oak tree")); r.frame.directorIdle = true;
		List<SceneCandidate> output = r.next(); SceneCandidate c = find(output, "M69");
		assertNotNull(c); assertNull(find(output, "M08")); assertEquals(10, c.getPriority()); assertEquals(20, c.getExpiresTick() - c.getCreatedTick());
		assertEquals("FOUNTAIN", c.getConditionFacts().get("priorInspectionKind")); assertEquals("Fountain", c.getConditionFacts().get("priorInspectionName"));
		assertTrue(r.rules.stillValid(c)); r.frame.objects = Collections.emptyList(); r.next(); assertFalse(r.rules.continuationValid(c));
	}

	@Test public void identityOnlyAndFirstPaintAcknowledgementsCannotInventCompletedCategoryContext()
	{
		for (boolean painted : new boolean[]{false, true})
		{
			ScenarioReplay r = fountain(); SceneCandidate c = nextFountain(r);
			if (painted) { r.rules.firstPaint("fountain-line", "M08", r.frame.tick, 1, 1, c.getExpiresTick()); }
			else { r.rules.inspectionCompleted("fountain"); }
			r.frame.objects = Collections.singletonList(object("tree", "Tree"));
			List<SceneCandidate> result = r.next(); assertNull(find(result, "M69")); assertNotNull(find(result, "M08"));
		}
	}

	@Test public void typedCompletionRequiresCurrentRevalidatedObject()
	{
		ScenarioReplay r = fountain(); SceneCandidate c = nextFountain(r); r.frame.directorIdle = false;
		for (int i = 0; i < 9; i++) { r.next(); }
		r.frame.objects = Collections.emptyList(); r.next(); r.rules.inspectionCompleted(c);
		r.frame.directorIdle = true; r.frame.objects = Collections.singletonList(object("tree", "Tree"));
		List<SceneCandidate> result = r.next(); assertNull(find(result, "M69")); assertNotNull(find(result, "M08"));
	}

	@Test public void completedCategoryAgeIsInclusiveAtOneHundredEighty()
	{
		for (int age : new int[]{180, 181})
		{
			ScenarioReplay r = completedFountain(); r.frame.objects = Collections.emptyList(); r.frame.directorIdle = false;
			for (int i = 1; i < age; i++) { r.next(); }
			r.frame.directorIdle = true; r.frame.objects = Collections.singletonList(object("tree", "Tree"));
			assertEquals(age == 180, find(r.next(), "M69") != null);
		}
	}

	@Test public void presenceLossAndSceneChangeClearCompletionContext()
	{
		for (boolean scene : new boolean[]{false, true})
		{
			ScenarioReplay r = completedFountain(); r.frame.objects = Collections.emptyList();
			if (scene) { r.frame.sceneRevision++; }
			else { r.frame.present = false; r.next(); r.frame.present = true; r.frame.presentSinceTick = r.frame.tick + 1; }
			for (int i = 0; i < 15; i++) { r.next(); }
			r.frame.directorIdle = true; r.frame.objects = Collections.singletonList(object("tree", "Tree"));
			List<SceneCandidate> output = r.next(); assertNull(find(output, "M69")); assertNotNull(find(output, "M08"));
		}
	}

	@Test public void newestCompletedTreePreventsOlderFountainFromBeingUsedAsLatestInterest()
	{
		ScenarioReplay r = completedFountain(); r.frame.objects = Collections.singletonList(object("first-tree", "Tree")); r.frame.directorIdle = true;
		SceneCandidate tree = find(r.next(), "M69"); completeHold(r, tree);
		r.frame.objects = Collections.singletonList(object("second-tree", "Willow tree")); r.frame.directorIdle = true;
		List<SceneCandidate> output = r.next(); assertNull(find(output, "M69")); assertNotNull(find(output, "M08"));
	}

	@Test public void completedContextDoesNotRestoreAnExpiredOpportunityButKeepsTruthfulHold()
	{
		ScenarioReplay r = completedFountain(); r.frame.objects = Collections.singletonList(object("tree", "Tree")); r.frame.directorIdle = true;
		SceneCandidate c = find(r.next(), "M69"); r.frame.directorIdle = false;
		while (r.frame.tick < c.getExpiresTick()) { r.next(); }
		assertFalse(r.rules.stillValid(c)); assertTrue(r.rules.continuationValid(c));
	}

	private static ScenarioReplay fountain()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.objects = Collections.singletonList(object("fountain", "Fountain")); return r;
	}
	private static SceneCandidate nextFountain(ScenarioReplay r)
	{
		r.next(); r.next(); SceneCandidate c = find(r.next(), "M08"); assertNotNull(c); return c;
	}
	private static ScenarioReplay completedFountain()
	{
		ScenarioReplay r = fountain(); completeHold(r, nextFountain(r)); return r;
	}
	private static void completeHold(ScenarioReplay r, SceneCandidate c)
	{
		assertNotNull(c); r.frame.directorIdle = false; for (int i = 0; i < c.getHoldTicks(); i++) { r.next(); }
		r.rules.inspectionCompleted(c);
	}
	private static InspectionTarget object(String key, String name)
	{
		return new InspectionTarget(key, name, 3106, 3200, 3106, 3200, 0, new Position(3105, 3200, 0), 8);
	}
}
