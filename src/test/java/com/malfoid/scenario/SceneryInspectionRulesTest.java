package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.world.LivingWorldData;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.find;
import static org.junit.Assert.*;

public class SceneryInspectionRulesTest
{
	@Test public void sceneryUsesTheExistingWalkPlanDeadlineHoldAndActualInspectionSpacing() throws Exception
	{
		ScenarioReplay r = ready(); r.frame.objects = Collections.singletonList(object("books", "Bookcase", 3106, 8));
		SceneCandidate c = find(r.next(), "SC-BOOKS"); assertNotNull(c);
		assertEquals(-100, c.getPriority()); assertEquals(20, c.getExpiresTick() - c.getCreatedTick());
		assertEquals("SCENERY", c.getConditionFacts().get("livingWorld"));
		assertEquals("Shelves of books.", c.getConditionFacts().get("livingWorldFacts"));
		assertEquals("8", c.getConditionFacts().get("pathSteps"));
		ScenePlan plan = ScenePlan.forCandidate(c, 3, true, true);
		assertTrue(plan.approach); assertTrue(plan.voluntaryTrip); assertFalse(plan.run);
		assertEquals(ScenePlan.Facing.OBJECT, plan.facing); assertTrue(plan.holdTicks >= 6 && plan.holdTicks <= 12);
		assertEquals(Integer.valueOf(3105), plan.standWorldX); assertEquals(Integer.valueOf(3106), plan.subjectWorldX);
		assertFalse(plan.voluntaryTripAllowed(199, 100)); assertTrue(plan.voluntaryTripAllowed(200, 100));
	}

	@Test public void nearestUnseenKindWinsRatherThanShortestRoute() throws Exception
	{
		ScenarioReplay r = ready();
		r.frame.objects = Arrays.asList(object("books", "Bookcase", 3106, 1), object("statue", "Statue", 3104, 8));
		assertNotNull(find(r.next(), "SC-STATUE"));
	}

	@Test public void unseenKindComesBeforeANearerPreviouslyVisitedKind() throws Exception
	{
		ScenarioReplay r = ready(); InspectionTarget books = object("first-books", "Bookcase", 3104, 8);
		r.frame.objects = Collections.singletonList(books); SceneCandidate c = find(r.next(), "SC-BOOKS"); arrive(r, c, books);
		r.frame.directorIdle = true;
		r.frame.objects = Arrays.asList(object("other-books", "Bookshelf", 3102, 1), object("statue", "Statue", 3106, 8));
		List<SceneCandidate> output = r.next(); assertNotNull(find(output, "SC-STATUE")); assertNull(find(output, "SC-BOOKS"));
	}

	@Test public void arrivalConsumesTheObjectWithoutAnySpeechPaintAndDoesNotMakeM69() throws Exception
	{
		ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 8);
		r.frame.objects = Collections.singletonList(books); SceneCandidate c = find(r.next(), "SC-BOOKS"); arrive(r, c, books);
		assertTrue(r.rules.sceneryArrived(c)); assertFalse(r.rules.canInspectScenery(books));
		r.frame.directorIdle = true; assertNull(find(r.next(), "SC-BOOKS"));
		r.frame.objects = Collections.singletonList(object("tree", "Tree", 3106, 8));
		List<SceneCandidate> next = r.next(); assertNotNull(find(next, "M08")); assertNull(find(next, "M69"));
	}

	@Test public void reloadAndPresenceLossKeepVisitsButSessionResetClearsThem() throws Exception
	{
		ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 8);
		r.frame.objects = Collections.singletonList(books); arrive(r, find(r.next(), "SC-BOOKS"), books);
		InspectionTarget reloaded = new InspectionTarget("new-object-reference", books.name, books.minX, books.minY,
			books.maxX, books.maxY, books.plane, books.stand, books.pathSteps, books.scenerySessionKey);
		r.frame.objects = Collections.singletonList(reloaded); r.frame.directorIdle = true; r.frame.sceneRevision++;
		for (int i = 0; i < 16; i++) { assertNull(find(r.next(), "SC-BOOKS")); }
		r.frame.present = false; r.next(); r.frame.present = true; r.frame.presentSinceTick = r.frame.tick + 1;
		for (int i = 0; i < 16; i++) { assertNull(find(r.next(), "SC-BOOKS")); }
		r.frame.sessionGeneration++; SceneCandidate fresh = null;
		for (int i = 0; i < 16; i++) { SceneCandidate next = find(r.next(), "SC-BOOKS"); if (next != null) { fresh = next; } }
		assertNotNull(fresh);
	}

	@Test public void routeBoundsAndNeighborProofRejectAnUnsupportedStand() throws Exception
	{
		for (int steps : new int[]{-1, 9})
		{
			ScenarioReplay r = ready(); r.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, steps));
			assertNull(find(r.next(), "SC-BOOKS"));
		}
		ScenarioReplay r = ready(); r.frame.objects = Collections.singletonList(new InspectionTarget("books", "Bookcase",
			3104, 3200, 3104, 3200, 0, new Position(3102, 3200, 0), 1, "stable-books"));
		assertNull(find(r.next(), "SC-BOOKS"));
	}

	@Test public void theSeparateStartGateNeverSuppressesTheOriginalM08() throws Exception
	{
		ScenarioReplay r = ready(); r.rules.setSceneryStartAllowed(() -> false);
		r.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, 8)); assertNull(find(r.next(), "SC-BOOKS"));
		r.frame.objects = Collections.singletonList(object("tree", "Oak tree", 3106, 8)); assertNotNull(find(r.next(), "M08"));
	}

	@Test public void aFreshNumberedReactionWinsTheWholeBatchWithoutConsumingAnUnstartedSceneryVisit() throws Exception
	{
		ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 8);
		r.frame.objects = Collections.singletonList(books); r.level(Skill.FISHING, 11); r.xp(Skill.FISHING, 1);
		List<SceneCandidate> output = r.next(ScenarioReplay.stats(Skill.FISHING));
		assertNotNull(find(output, "M04")); assertNull(find(output, "SC-BOOKS")); assertTrue(r.rules.canInspectScenery(books));
		assertNotNull(find(r.next(), "SC-BOOKS"));
	}

	@Test public void stableSceneAndPresentAgeRetainM08Boundaries() throws Exception
	{
		ScenarioReplay r = configured(new ScenarioReplay(0)); r.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, 8));
		for (int i = 0; i < 14; i++) { assertNull(find(r.next(), "SC-BOOKS")); }
		assertNotNull(find(r.next(), "SC-BOOKS"));
		ScenarioReplay recent = ready(); recent.frame.presentSinceTick = recent.frame.tick - 8;
		recent.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, 8));
		assertNull(find(recent.next(), "SC-BOOKS")); assertNotNull(find(recent.next(), "SC-BOOKS"));
	}

	@Test public void aPlayerStepPreventsStartingAndRevokesAnActiveVisit() throws Exception
	{
		ScenarioReplay start = ready(); start.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, 8));
		start.frame.player = new Position(3101, 3200, 0); assertNull(find(start.next(), "SC-BOOKS"));
		ScenarioReplay active = ready(); active.frame.objects = Collections.singletonList(object("books", "Bookcase", 3104, 8));
		SceneCandidate c = find(active.next(), "SC-BOOKS"); active.frame.directorIdle = false;
		active.frame.player = new Position(3101, 3200, 0); active.next(); assertFalse(active.rules.continuationValid(c));
	}

	@Test public void sceneryUsesWholeNormalizedNames() throws Exception
	{
		ScenarioReplay r = ready(); r.frame.objects = Collections.singletonList(object("books", "Bookcase (empty)", 3104, 8));
		assertNull(find(r.next(), "SC-BOOKS")); r.frame.objects = Collections.singletonList(object("books", "<col=ff00ff>bOoKcAsE</col>", 3104, 8));
		assertNotNull(find(r.next(), "SC-BOOKS"));
	}

	@Test public void missingMismatchedRunningOrOverlongArrivalCannotConsumeAVisit() throws Exception
	{
		for (int defect = 0; defect < 5; defect++)
		{
			ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 8);
			r.frame.objects = Collections.singletonList(books); SceneCandidate c = find(r.next(), "SC-BOOKS"); r.frame.directorIdle = false;
			r.frame.companion = books.stand; MotionObservation.Builder arrival = arrival(r, c, books);
			if (defect == 1) { arrival.sourceCandidateId++; }
			if (defect == 2) { arrival.completedRunEdges = 1; }
			if (defect == 3) { arrival.completedEdges = 9; }
			if (defect == 4) { arrival.status = MotionObservation.Status.MOVING; }
			r.frame.motion = defect == 0 ? null : arrival.build(); r.next();
			assertFalse(r.rules.sceneryArrived(c)); assertTrue(r.rules.canInspectScenery(books));
		}
	}

	@Test public void changedDefinitionFootprintNameOrViewRejectsTheArrival() throws Exception
	{
		for (int change = 0; change < 4; change++)
		{
			ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 8);
			r.frame.objects = Collections.singletonList(books); SceneCandidate c = find(r.next(), "SC-BOOKS");
			r.frame.directorIdle = false; r.frame.companion = books.stand; r.frame.motion = arrival(r, c, books).build();
			if (change == 3) { r.frame.worldViewId++; }
			else { r.frame.objects = Collections.singletonList(new InspectionTarget(books.key, change == 2 ? "Bookshelf" : books.name,
				change == 1 ? books.minX + 1 : books.minX, books.minY, books.maxX, books.maxY, books.plane, books.stand,
				books.pathSteps, books.scenerySessionKey, change == 0 ? 123 : books.resolvedDefinitionId)); }
			r.next(); assertFalse(r.rules.continuationValid(c)); assertFalse(r.rules.sceneryArrived(c));
			assertTrue(r.rules.canInspectScenery(books));
		}
	}

	@Test public void aProvedZeroEdgeVisitConsumesTheActualCurrentStandWithoutInventingAMotionReceipt() throws Exception
	{
		ScenarioReplay r = ready(); InspectionTarget books = object("books", "Bookcase", 3104, 0);
		r.frame.objects = Collections.singletonList(books); SceneCandidate c = find(r.next(), "SC-BOOKS");
		assertNotNull(c); assertNull(r.frame.motion); assertTrue(r.rules.sceneryArrived(c)); assertFalse(r.rules.canInspectScenery(books));
	}

	private static ScenarioReplay ready() throws Exception { return configured(new ScenarioReplay(15)); }
	private static ScenarioReplay configured(ScenarioReplay r) throws Exception
	{
		r.rules.setLivingWorldData(LivingWorldData.load(SceneryInspectionRulesTest.class)); r.rules.setSceneryStartAllowed(() -> true);
		r.frame.worldViewId = -1; r.frame.placementGeneration = 17; return r;
	}
	private static InspectionTarget object(String key, String name, int x, int steps)
	{
		return new InspectionTarget(key, name, x, 3200, x, 3200, 0, new Position(x - 1, 3200, 0), steps, "stable:" + key);
	}
	private static MotionObservation.Builder arrival(ScenarioReplay r, SceneCandidate c, InspectionTarget object)
	{
		assertNotNull(c); MotionObservation.Builder receipt = new MotionObservation.Builder();
		receipt.sourceCandidateId = c.getId(); receipt.requestId = c.getId() + 1; receipt.status = MotionObservation.Status.ARRIVED;
		receipt.purpose = MotionObservation.Purpose.OTHER; receipt.legalGoal = true; receipt.fixedGoal = true;
		receipt.completedEdges = 8; receipt.plannedSteps = 8; receipt.completedTick = r.frame.tick + 1;
		receipt.goalWorldX = object.stand.x; receipt.goalWorldY = object.stand.y; receipt.plane = object.plane;
		receipt.worldViewId = r.frame.worldViewId; receipt.placementGeneration = r.frame.placementGeneration; return receipt;
	}
	private static void arrive(ScenarioReplay r, SceneCandidate c, InspectionTarget object)
	{
		r.frame.directorIdle = false; r.frame.companion = object.stand; r.frame.motion = arrival(r, c, object).build();
		r.next(); assertTrue(r.rules.sceneryArrived(c));
	}
}
