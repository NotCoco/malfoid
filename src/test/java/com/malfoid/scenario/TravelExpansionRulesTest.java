package com.malfoid.scenario;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation.Purpose;
import com.malfoid.motion.MotionObservation.Status;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.find;
import static org.junit.Assert.*;

public class TravelExpansionRulesTest
{
	@Test public void m52NeedsFiveStableAlternatingEndpointsAndConsumesOnlyTheMatchedSequence()
	{
		Replay r = new Replay(); r.rules.reset(); stop(r, 0, 0); stop(r, 2, 0); stop(r, 0, 0); stop(r, 2, 0);
		r.position(0, 0); assertNull(find(r.next(), "M52")); SceneCandidate c = find(r.next(), "M52");
		assertNotNull(c); contract(c, 17, 12, 8, 4); assertEquals("2", c.getConditionFacts().get("endpointDistance"));
		for (int i = 0; i < 10; i++) { assertNull(find(r.next(), "M52")); }
	}

	@Test public void m52EndpointDistancesIncludeTwoAndFourButExcludeOneAndFive()
	{
		for (int distance : new int[]{1, 2, 4, 5})
		{
			Replay r = new Replay(); r.rules.reset(); stop(r, 0, 0);
			for (int leg = 0; leg < 3; leg++) { walkTo(r, (leg & 1) == 0 ? distance : 0, 0); r.next(); }
			walkTo(r, 0, 0); SceneCandidate c = find(r.next(), "M52"); assertEquals("endpoint distance " + distance, distance == 2 || distance == 4, c != null);
		}
	}

	@Test public void m52TheFirstEndpointMayBeFortyNineTicksOldButNotFifty()
	{
		for (int extra : new int[]{40, 41})
		{
			Replay r = new Replay(); r.rules.reset(); stop(r, 0, 0); r.waitTicks(extra);
			stop(r, 2, 0); stop(r, 0, 0); stop(r, 2, 0); r.position(0, 0); r.next();
			assertEquals(extra == 40, find(r.next(), "M52") != null);
		}
	}

	@Test public void m52ThirdEndpointAndAnIncompleteShuttleCannotQualify()
	{
		Replay partial = new Replay(); stop(partial, 0, 0); stop(partial, 2, 0); stop(partial, 0, 0);
		assertNull(find(partial.next(), "M52"));
		Replay third = new Replay(); stop(third, 0, 0); stop(third, 2, 0); stop(third, 2, 2); stop(third, 2, 0); stop(third, 0, 0);
		assertNull(find(third.next(), "M52"));
	}

	@Test public void m53RequiresTwoCopiesOfFourToEightDistinctVertices()
	{
		for (int vertices : new int[]{3, 4, 8, 9})
		{
			Replay r = new Replay(); r.rules.reset(); int[][] points = circuitPoints(vertices); r.position(0, 0); r.next();
			SceneCandidate c = repeatCircuit(r, points, false); assertEquals("distinct vertices " + vertices, vertices == 4 || vertices == 8, c != null);
			if (c != null) { contract(c, 16, 12, 8, 6); assertEquals(Integer.toString(vertices), c.getConditionFacts().get("cycleVertices")); }
		}
	}

	@Test public void m53ReorderedOrInsertedMovedSamplesInvalidateTheFirstCycleTemplate()
	{
		Replay reordered = new Replay(); reordered.rules.reset(); reordered.next();
		assertNull(repeatCircuit(reordered, circuitPoints(4), true));
		Replay inserted = new Replay(); inserted.rules.reset(); inserted.next();
		int[][] points = circuitPoints(4); loop(inserted, points); inserted.position(1, 1); inserted.next();
		assertNull(loop(inserted, points));
	}

	@Test public void m53TheSecondReturnMustFitSixtyFourFullyObservedSnapshots()
	{
		for (int still : new int[]{55, 56})
		{
			Replay r = new Replay(); r.rules.reset(); r.next(); int[][] points = circuitPoints(4); assertNull(loop(r, points));
			r.waitTicks(still); assertEquals(still == 55, loop(r, points) != null);
		}
	}

	@Test public void m54RequiresTwoPerpendicularCardinalRetracesAndAtMostFourStillTicksBetweenThem()
	{
		for (int still : new int[]{0, 4, 5})
		{
			Replay r = new Replay(); r.rules.reset(); r.next(); dogleg(r, 1, 1); r.waitTicks(still);
			SceneCandidate c = dogleg(r, 1, 1); assertEquals("between-trace pause " + still, still <= 4, c != null);
			if (c != null) { contract(c, 16, 10, 6, 4); assertEquals(Integer.toString(still), c.getConditionFacts().get("betweenTracesStillTicks")); }
		}
	}

	@Test public void m54OneTraceStraightExtensionsAndDiagonalLegsDoNotQualify()
	{
		for (int[] corner : new int[][]{{2, 0}, {2, 1}})
		{
			Replay r = new Replay(); r.rules.reset(); r.next(); assertNull(dogleg(r, corner[0], corner[1])); assertNull(dogleg(r, corner[0], corner[1]));
		}
		Replay one = new Replay(); one.rules.reset(); one.next(); assertNull(dogleg(one, 1, 1)); assertNull(find(one.next(), "M54"));
	}

	@Test public void m55OnlyTheSecondSampleOfTheThirdStillRunCreatesAnOpportunity()
	{
		Replay r = startStopPrefix(2, 2); move(r, 3); r.next(); assertNull(find(r.output, "M55"));
		SceneCandidate c = find(r.next(), "M55"); assertNotNull(c); contract(c, 15, 8, 4, 4); assertTrue(r.valid(c));
		assertNull(find(r.next(), "M55")); assertTrue(r.valid(c)); assertNull(find(r.next(), "M55")); assertTrue(r.valid(c));
		r.next(); assertFalse("The current still run has reached five", r.valid(c));
	}

	@Test public void m55MoveLengthsOneAndThreeAndStillLengthsTwoAndFourAreInclusive()
	{
		for (int moving : new int[]{1, 3, 4})
		{
			for (int still : new int[]{1, 2, 4, 5})
			{
				Replay r = startStopPrefix(moving, still); move(r, 1); r.next();
				assertEquals("MOVE" + moving + "/STILL" + still, moving <= 3 && still >= 2 && still <= 4, find(r.next(), "M55") != null);
			}
		}
	}

	@Test public void m56RequiresTheWholeSixtyFourFrameTravelWindowThenExactlyTwelveStillCommits()
	{
		Replay r = travel(24, 12); r.waitTicks(11); assertNull(find(r.output, "M56"));
		SceneCandidate c = find(r.next(), "M56"); assertNotNull(c); contract(c, 14, 12, 8, 8);
		assertEquals("24", c.getConditionFacts().get("movingSamples")); assertEquals("12", c.getConditionFacts().get("xSpan"));
		for (int i = 0; i < 12; i++) { assertNull(find(r.next(), "M56")); }
	}

	@Test public void m56RejectsTwentyThreeMovesAnElevenTileSpanOrAShortenedObservedWindow()
	{
		for (int[] bound : new int[][]{{23, 12}, {24, 11}})
		{
			Replay r = travel(bound[0], bound[1]); r.waitTicks(12); assertNull(find(r.output, "M56"));
		}
		Replay shortHistory = travel(24, 12); shortHistory.rules.reset(); shortHistory.waitTicks(12); assertNull(find(shortHistory.output, "M56"));
	}

	@Test public void m57DecoratesTheThirdCurrentM10CauseWithoutExtendingItsDeadline()
	{
		Replay r = new Replay(3199, 3210, 0); assertEquals("M10", crossing(r).getScenarioId()); assertEquals("M10", crossing(r).getScenarioId());
		SceneCandidate original = crossingBase(r), c = r.rules.decorateArrival(r.current, original, r::candidate);
		assertEquals("M57", c.getScenarioId()); assertEquals(original.getId(), c.getId()); assertEquals(original.getCausalToken(), c.getCausalToken());
		assertEquals(original.getCreatedTick(), c.getCreatedTick()); assertEquals(original.getExpiresTick(), c.getExpiresTick());
		assertEquals("3", c.getConditionFacts().get("witnessedArrivalCount")); assertTrue(r.valid(c));
		assertEquals("M10", r.rules.decorateArrival(r.current, original, r::candidate).getScenarioId());
	}

	@Test public void m57ArrivalAgeTwoThousandFourHundredIsIncludedButTwoThousandFourHundredOneIsNot()
	{
		for (int age : new int[]{2400, 2401})
		{
			Replay r = new Replay(3199, 3210, 0); crossing(r); long first = r.current.tick; crossing(r);
			r.waitTicks((int) (first + age - r.current.tick - 5));
			assertEquals(age == 2400 ? "M57" : "M10", crossing(r).getScenarioId());
		}
	}

	@Test public void m57StartupInsideAbsentOutsideAndPlaneOneAnchorsNeverContributeWitnessedArrivals()
	{
		Replay inside = new Replay(3200, 3210, 0); inside.waitTicks(4);
		assertEquals("M10", inside.rules.decorateArrival(inside.current, inside.baseArrival(), inside::candidate).getScenarioId());
		Replay absent = new Replay(3199, 3210, 0); crossing(absent); crossing(absent);
		absent.frame.present = false; absent.absolute(3199, 3210, 0); absent.next(); absent.frame.present = true;
		absent.frame.presentSinceTick = absent.frame.tick + 1; absent.absolute(3200, 3210, 0); absent.waitTicks(12);
		assertEquals("M10", absent.rules.decorateArrival(absent.current, absent.baseArrival(), absent::candidate).getScenarioId());
		assertEquals("M10", crossing(absent).getScenarioId());
		Replay plane = new Replay(3199, 3210, 1); plane.absolute(3200, 3210, 0); plane.waitTicks(4);
		assertEquals("M10", plane.rules.decorateArrival(plane.current, plane.baseArrival(), plane::candidate).getScenarioId());
	}

	@Test public void m57LoadingSceneChangeAndPresenceLossClearTheBoundedArrivalHistory()
	{
		for (int interruption = 0; interruption < 3; interruption++)
		{
			Replay r = new Replay(3199, 3210, 0); crossing(r); crossing(r);
			if (interruption == 0) { r.frame.valid = false; }
			if (interruption == 1) { r.frame.sceneRevision++; }
			if (interruption == 2) { r.frame.present = false; }
			r.next(); r.frame.valid = true; r.frame.present = true; r.waitTicks(12); assertEquals("M10", crossing(r).getScenarioId());
		}
	}

	@Test public void m59FourStableCardinalBinsAllowBothCyclicDirectionsAndWrappingTolerance()
	{
		for (int direction : new int[]{1, -1})
		{
			for (int start = 0; start < 4; start++)
			{
				Replay r = new Replay(); SceneCandidate c = null;
				for (int i = 0; i < 4; i++) { c = heading(r, (((start + direction * i) & 3) * 512 + 32) & 2047); }
				assertNotNull(c); contract(c, 16, 10, 6, 4); assertEquals(Integer.toString(direction), c.getConditionFacts().get("headingDirection"));
			}
		}
		Replay wrapped = new Replay(); heading(wrapped, 2016); heading(wrapped, 512); heading(wrapped, 1024); assertNotNull(heading(wrapped, 1536));
	}

	@Test public void m59RejectsThirtyThreeJAUToleranceOneTickBinsReversalsAndSkippedBins()
	{
		Replay tolerance = new Replay(); heading(tolerance, 33); heading(tolerance, 512); heading(tolerance, 1024); assertNull(heading(tolerance, 1536));
		Replay brief = new Replay(); for (int angle : new int[]{0, 512, 1024, 1536}) { brief.frame.observedFacing = angle; assertNull(find(brief.next(), "M59")); }
		Replay reversal = new Replay(); heading(reversal, 0); heading(reversal, 512); heading(reversal, 0); assertNull(heading(reversal, 1536));
		Replay skipped = new Replay(); heading(skipped, 0); heading(skipped, 1024); heading(skipped, 1536); assertNull(heading(skipped, 512));
	}

	@Test public void m59IntermediateNoncardinalSamplesRemainObservedAndTheWindowIsThirtyTwoFrames()
	{
		for (int extra : new int[]{24, 25})
		{
			Replay r = new Replay(); heading(r, 0); r.frame.observedFacing = 200; r.waitTicks(extra);
			heading(r, 512); heading(r, 1024); assertEquals(extra == 24, heading(r, 1536) != null);
		}
	}

	@Test public void m59UnknownFacingAndActualMovementClearEarlierStableBins()
	{
		for (boolean unknown : new boolean[]{false, true})
		{
			Replay r = new Replay(); heading(r, 0); heading(r, 512);
			if (unknown) { r.frame.observedFacing = -1; } else { r.position(1, 0); }
			r.next(); heading(r, 1024); assertNull(heading(r, 1536));
		}
	}

	@Test public void m51SixImmediateMovingCopiesAfterARealCatchupAreRequired()
	{
		Replay r = caughtUp(42, 2); SceneCandidate c = null;
		for (int i = 1; i <= 6; i++) { r.position(i * 2, 0); c = find(r.next(), "M51"); assertEquals(i == 6, c != null); }
		contract(c, 18, 12, 6, 6); assertEquals("catchup:42", c.getCausalToken()); assertEquals("2", c.getConditionFacts().get("completedRunEdges"));
		for (int i = 7; i <= 12; i++) { r.position(i * 2, 0); assertNull(find(r.next(), "M51")); }
	}

	@Test public void m51RunPlansZeroActualEdgesAndOnlyOneCompletedRunEdgeNeverProveCatchup()
	{
		for (int runs : new int[]{0, 1})
		{
			Replay r = caughtUp(42, runs); assertNull(movingSix(r));
		}
		Replay planned = new Replay(); planned.frame.motion = motion(planned, 42, Purpose.CATCHUP, Status.MOVING, 2, 4, 10, -1, 12, 3, 0); planned.next();
		assertNull(movingSix(planned));
		Replay noEdges = new Replay(); receipt(noEdges, 42, Purpose.CATCHUP, 2, 0); noEdges.next(); assertNull(movingSix(noEdges));
	}

	@Test public void m51AStopSeparationSixDiscontinuousJumpOrRecoveryCancelsTheLatchedStretch()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			Replay r = caughtUp(42, 2); r.position(2, 0); r.next(); r.position(3, 0); r.next();
			if (interruption == 0) { r.next(); }
			if (interruption == 1) { r.position(4, 0); r.frame.companion = new Position(r.frame.player.x + 6, r.frame.player.y, 0); r.next(); }
			if (interruption == 2) { r.position(6, 0); r.next(); }
			if (interruption == 3) { r.frame.motion = motion(r, 90, Purpose.OTHER, Status.RECOVERED, 0, 0, r.frame.tick, -1, -1, 3, 0); r.next(); }
			assertNull(movingSix(r));
		}
	}

	@Test public void m60RequiresFourStillCommitsAndActualEdgeProgressAfterThePauseBegan()
	{
		for (int still : new int[]{3, 4})
		{
			Replay r = new Replay(); r.rules.reset(); r.waitTicks(still - 1); receipt(r, 42, Purpose.CATCHUP, 0, 3);
			SceneCandidate c = find(r.next(), "M60"); assertEquals(still == 4, c != null);
			if (c != null) { contract(c, 18, 10, 4, 6); assertEquals("catchup:42", c.getCausalToken()); }
		}
		Replay beforePause = new Replay(); beforePause.rules.reset(); beforePause.waitTicks(3);
		MotionObservation.Builder old = builder(beforePause, 42, Purpose.CATCHUP, Status.ARRIVED); old.lastCompletedEdgeTick = beforePause.frame.tick - 2;
		beforePause.frame.motion = old.build(); beforePause.frame.motionCompletion = beforePause.frame.motion;
		assertNull(find(beforePause.next(), "M60"));
	}

	@Test public void m60AnOlderRequestFrontierAndEmotionalPlacementAreNotAnOrdinaryArrival()
	{
		for (int error = 0; error < 4; error++)
		{
			Replay r = new Replay(); r.rules.reset(); r.waitTicks(4); receipt(r, 42, Purpose.CATCHUP, 2, 3);
			if (error == 0) { r.frame.motion = motion(r, 99, Purpose.CATCHUP, Status.MOVING, 1, 1, 10, -1, 12, 3, 0); }
			if (error == 1) { MotionObservation.Builder b = builder(r, 42, Purpose.CATCHUP, Status.MOVING); r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion; }
			if (error == 2) { MotionObservation.Builder b = builder(r, 42, Purpose.OTHER, Status.ARRIVED); b.ordinary = false; r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion; }
			if (error == 3) { MotionObservation.Builder b = builder(r, 42, Purpose.CATCHUP, Status.ARRIVED); b.completedEdges = 0; r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion; }
			assertNull(find(r.next(), "M60"));
		}
	}

	@Test public void m51AndM60UseTheIdenticalCompletedRequestCause()
	{
		Replay moving = caughtUp(73, 2); SceneCandidate stretch = movingSix(moving);
		Replay stopped = new Replay(); stopped.rules.reset(); stopped.waitTicks(3); receipt(stopped, 73, Purpose.CATCHUP, 0, 3);
		SceneCandidate arrival = find(stopped.next(), "M60"); assertNotNull(stretch); assertNotNull(arrival);
		assertEquals(stretch.getCausalToken(), arrival.getCausalToken());
	}

	@Test public void oldNativeCountersCannotCrossAnUnknownMissingInvalidOrBlockedInterval()
	{
		for (String id : new String[]{"M51", "M60"})
		{
			for (int interruption = 0; interruption < 4; interruption++)
			{
				ScenarioReplay r = new ScenarioReplay(0); r.frame.placementGeneration = 1; r.frame.worldViewId = -1;
				for (int i = 0; i < 12; i++) { r.next(); }
				long oldStart = r.frame.tick; r.frame.motion = productionReceipt(r, 42, Purpose.CATCHUP, Status.MOVING, oldStart); r.next();
				if (interruption == 0) { r.frame.targetUnknown = true; }
				if (interruption == 1) { r.frame.tick++; }
				if (interruption == 2) { r.frame.valid = false; }
				if (interruption == 3) { r.frame.inventoryBlocked = true; }
				r.next(); r.frame.targetUnknown = false; r.frame.valid = true; r.frame.inventoryBlocked = false;
				for (int i = 0; i < 3; i++) { r.next(); }
				if ("M51".equals(id)) { advancePlayer(r); }
				r.frame.motion = productionReceipt(r, 42, Purpose.CATCHUP, Status.ARRIVED, oldStart); r.frame.motionCompletion = r.frame.motion;
				assertNull("Old terminal counters " + id + "/" + interruption, find(r.next(), id));
				if ("M51".equals(id)) { for (int i = 0; i < 6; i++) { advancePlayer(r); assertNull("Old moving counters " + id + "/" + interruption + "/" + i, find(r.next(), id)); } }
				long newStart = r.frame.tick + 1; r.frame.motion = productionReceipt(r, 43, Purpose.CATCHUP, Status.MOVING, newStart);
				for (int i = 0; i < 3; i++) { r.next(); }
				if ("M51".equals(id)) { advancePlayer(r); }
				r.frame.motion = productionReceipt(r, 43, Purpose.CATCHUP, Status.ARRIVED, newStart); r.frame.motionCompletion = r.frame.motion;
				SceneCandidate c = find(r.next(), id);
				if ("M51".equals(id)) { for (int i = 0; i < 6; i++) { advancePlayer(r); c = find(r.next(), id); } }
				assertNotNull("A new fully witnessed route " + id + "/" + interruption, c); assertEquals("catchup:43", c.getCausalToken());
			}
		}
	}

	@Test public void aGenuineCompletionSurvivesTheNextOrdinaryPlanAcceptedInThatSameCommit()
	{
		Replay r = new Replay(); r.rules.reset(); r.waitTicks(3); receipt(r, 42, Purpose.CATCHUP, 0, 3);
		MotionObservation.Builder next = builder(r, 43, Purpose.ROAM, Status.MOVING); next.startedTick = r.frame.tick + 1;
		next.completedTick = -1; next.lastCompletedEdgeTick = -1; next.completedEdges = 0; r.frame.motion = next.build();
		SceneCandidate c = find(r.next(), "M60"); assertNotNull(c); assertEquals("catchup:42", c.getCausalToken());
	}

	@Test public void nativeArrivalsRequireFreshKnownLegalFactsInTheCopiedCurrentSceneAndPresence()
	{
		for (int invalid = 0; invalid < 9; invalid++)
		{
			Replay r = new Replay(); r.rules.reset(); r.waitTicks(3); MotionObservation.Builder b = builder(r, 42, Purpose.CATCHUP, Status.ARRIVED);
			if (invalid == 0) { b.completedTick = r.frame.tick; }
			if (invalid == 1) { b.startedTick = r.frame.presentSinceTick - 1; }
			if (invalid == 2) { b.worldViewId = 7; }
			if (invalid == 3) { b.placementGeneration = 2; }
			if (invalid == 4) { b.plane = 1; }
			if (invalid == 5) { b.known = false; }
			if (invalid == 6) { b.legalGoal = false; }
			if (invalid == 7) { b.ordinary = false; }
			if (invalid == 8) { b.status = Status.SCENE_UNAVAILABLE; }
			r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion; assertNull("invalid native identity " + invalid, find(r.next(), "M60"));
		}
	}

	@Test public void m58OnlyThreeDistinctFixedOrdinaryRoamArrivalsWithAFullSixtyFrameFootprintQualify()
	{
		Replay r = wandering(60, false, false, false); SceneCandidate c = find(r.output, "M58");
		assertNotNull(c); contract(c, 14, 12, 4, 6); assertEquals("3", c.getConditionFacts().get("wanderCount")); assertEquals("1", c.getConditionFacts().get("observedPlane"));
		assertTrue(r.valid(c)); r.next(); assertNull(find(r.output, "M58"));
		Replay shortWindow = wandering(59, false, false, false); assertNull(find(shortWindow.output, "M58")); shortWindow.next(); assertNull(find(shortWindow.output, "M58"));
	}

	@Test public void m58DuplicateRequestDuplicateGoalAndANoXpFootprintExcursionAreRejected()
	{
		assertNull(find(wandering(60, true, false, false).output, "M58"));
		assertNull(find(wandering(60, false, true, false).output, "M58"));
		assertNull(find(wandering(60, false, false, true).output, "M58"));
	}

	@Test public void m58LeavingItsFrozenFootprintBeforeFirstPaintIrreversiblyRevokesTheTriple()
	{
		Replay r = wandering(60, false, false, false); SceneCandidate c = find(r.output, "M58"); assertNotNull(c); assertTrue(r.valid(c));
		r.position(1, 0); r.next(); assertFalse(r.valid(c)); r.position(0, 0); r.next(); assertFalse(r.valid(c));
	}

	@Test public void m58RoamPlansBlockedGoalsAndLifecyclePlacementCannotSupplyACompletion()
	{
		for (int error = 0; error < 4; error++)
		{
			Replay r = new Replay(3100, 3200, 1); r.rules.reset();
			for (int sample = 1; sample <= 60; sample++)
			{
				if (sample == 10 || sample == 35 || sample == 60)
				{
					MotionObservation.Builder b = builder(r, sample, Purpose.ROAM, Status.ARRIVED); b.goalWorldX = r.frame.player.x + (sample == 10 ? 3 : sample == 35 ? -3 : 0); b.goalWorldY = r.frame.player.y + (sample == 60 ? 3 : 0);
					if (sample == 60)
					{
						if (error == 0) { b.status = Status.MOVING; }
						if (error == 1) { b.status = Status.BLOCKED; }
						if (error == 2) { b.purpose = Purpose.OTHER; b.ordinary = false; }
						if (error == 3) { b.fixedGoal = false; }
					}
					r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion;
				}
				r.next();
			}
			assertNull(find(r.output, "M58"));
		}
	}

	@Test public void m70OneHundredNineteenCompleteQuietSnapshotsAreInsufficientAndContinuedStillnessNeverRerolls()
	{
		Replay r = new Replay(); r.rules.reset(); r.waitTicks(119); assertNull(find(r.output, "M70"));
		SceneCandidate c = find(r.next(), "M70"); assertNotNull(c); contract(c, 13, 12, 8, 12);
		for (int i = 0; i < 150; i++) { assertNull(find(r.next(), "M70")); }
	}

	@Test public void m70PositiveXpMissingOrTruncatedSkillsInteractionAndUnknownCombatResetTheWholeWindow()
	{
		for (int interruption = 0; interruption < 6; interruption++)
		{
			Replay r = new Replay(); r.rules.reset(); r.waitTicks(118); int[] full = r.frame.xp.clone();
			if (interruption == 0) { r.frame.xp[Skill.FISHING.ordinal()]++; }
			if (interruption == 1) { r.frame.xp = null; }
			if (interruption == 2) { r.frame.xp = new int[]{100}; }
			if (interruption == 3) { r.frame.interactionPresent = true; }
			if (interruption == 4) { r.combat = CombatState.UNKNOWN; }
			if (interruption == 5) { r.frame.interactionKnown = false; }
			assertNull(find(r.next(), "M70"));
			r.frame.xp = full; r.frame.interactionPresent = false; r.frame.interactionKnown = true; r.combat = CombatState.NO_CONFIRMED_EVIDENCE;
			r.waitTicks(119); assertNull(find(r.output, "M70"));
		}
	}

	@Test public void m70AConsumedStationaryEpisodeNeedsActualMovementEvenAfterXpOrBankActivity()
	{
		Replay r = new Replay(); r.rules.reset(); r.waitTicks(120); assertNotNull(find(r.output, "M70"));
		r.frame.xp[Skill.FISHING.ordinal()]++; r.next(); r.waitTicks(120); assertNull(find(r.output, "M70"));
		r.frame.inventoryBlocked = true; r.next(); r.frame.inventoryBlocked = false; r.waitTicks(120); assertNull(find(r.output, "M70"));
		r.position(1, 0); r.next(); r.waitTicks(119); assertNull(find(r.output, "M70")); assertNotNull(find(r.next(), "M70"));
	}

	@Test public void m56AndM70AreCompetingContextsOfTheSameObservedPause()
	{
		Replay r = travel(24, 12); r.waitTicks(12); SceneCandidate afterTravel = find(r.output, "M56"); assertNotNull(afterTravel);
		r.waitTicks(108); SceneCandidate longPause = find(r.output, "M70"); assertNotNull(longPause); assertEquals(afterTravel.getCausalToken(), longPause.getCausalToken());
	}

	@Test public void duplicatesCannotAdvanceAStillRunOrRepeatACandidate()
	{
		Replay r = startStopPrefix(2, 2); move(r, 3); r.next(); assertTrue(r.duplicate().isEmpty()); assertTrue(r.duplicate().isEmpty());
		assertNotNull(find(r.next(), "M55")); assertTrue(r.duplicate().isEmpty());
	}

	@Test public void missingTicksPresenceSceneSessionPlaneAndUnknownCombatResetPositionPatterns()
	{
		for (int interruption = 0; interruption < 8; interruption++)
		{
			Replay r = startStopPrefix(2, 2); move(r, 3); r.next();
			if (interruption == 0) { r.frame.tick++; }
			if (interruption == 1) { r.frame.present = false; }
			if (interruption == 2) { r.frame.sceneRevision++; }
			if (interruption == 3) { r.frame.sessionGeneration++; }
			if (interruption == 4) { r.absolute(r.frame.player.x, r.frame.player.y, 1); }
			if (interruption == 5) { r.combat = CombatState.UNKNOWN; }
			if (interruption == 6) { r.frame.valid = false; }
			if (interruption == 7) { r.frame.companion = null; }
			assertNull(find(r.next(), "M55")); r.frame.present = true; r.frame.valid = true; r.combat = CombatState.NO_CONFIRMED_EVIDENCE;
			r.absolute(r.frame.player.x, r.frame.player.y, 0); assertNull(find(r.next(), "M55"));
		}
	}

	@Test public void transientDepartureAndEmergencyPreemptionIrreversiblyRevokeAnUnpaintedTravelCause()
	{
		for (boolean emergency : new boolean[]{false, true})
		{
			Replay r = new Replay(); r.rules.reset(); stop(r, 0, 0); stop(r, 2, 0); stop(r, 0, 0); stop(r, 2, 0); r.position(0, 0); r.next();
			SceneCandidate c = find(r.next(), "M52"); assertNotNull(c); assertTrue(r.valid(c));
			if (emergency) { r.frame.emergencySceneActive = true; } else { r.position(1, 0); }
			r.next(); assertFalse(r.valid(c)); r.frame.emergencySceneActive = false; r.position(0, 0); r.next(); assertFalse(r.valid(c));
		}
	}

	@Test public void deadlineEqualityEndsFirstPaintButAnAlreadyCommittedValidHoldCanContinue()
	{
		for (String id : new String[]{"M52", "M53", "M54", "M56", "M57", "M59", "M60", "M70"})
		{
			Replay r; SceneCandidate c;
			if (id.equals("M52")) { r = new Replay(); r.rules.reset(); stop(r, 0, 0); stop(r, 2, 0); stop(r, 0, 0); stop(r, 2, 0); r.position(0, 0); r.next(); c = find(r.next(), id); }
			else if (id.equals("M53")) { r = new Replay(); r.rules.reset(); r.next(); c = repeatCircuit(r, circuitPoints(4), false); }
			else if (id.equals("M54")) { r = new Replay(); r.rules.reset(); r.next(); dogleg(r, 1, 1); c = dogleg(r, 1, 1); }
			else if (id.equals("M56")) { r = travel(24, 12); r.waitTicks(12); c = find(r.output, id); }
			else if (id.equals("M57")) { r = new Replay(3199, 3210, 0); crossing(r); crossing(r); c = crossing(r); }
			else if (id.equals("M59")) { r = new Replay(); heading(r, 0); heading(r, 512); heading(r, 1024); c = heading(r, 1536); }
			else if (id.equals("M60")) { r = new Replay(); r.rules.reset(); r.waitTicks(3); receipt(r, 42, Purpose.CATCHUP, 0, 3); c = find(r.next(), id); }
			else { r = new Replay(); r.rules.reset(); r.waitTicks(120); c = find(r.output, id); }
			assertNotNull(id, c); assertTrue(id, r.valid(c));
			while (r.current.tick < c.getExpiresTick() - 1) { r.next(); assertTrue(id, r.valid(c)); }
			r.next(); assertFalse(id, r.valid(c)); assertTrue(id, r.rules.continuationValid(c, r.current, r.combat, Long.MIN_VALUE / 2));
			r.rules.reset(); assertFalse(id, r.rules.continuationValid(c, r.current, r.combat, Long.MIN_VALUE / 2));
		}
	}

	@Test public void p10BusySuppressionDoesNotMistakeACrowdInspectionSuppressionForInteractionHold()
	{
		Replay r = new Replay(); r.rules.reset(); r.frame.inspectionSuppressed = true; r.waitTicks(120); assertNotNull(find(r.output, "M70"));
		Replay busy = new Replay(); busy.rules.reset(); busy.frame.inventoryBlocked = true; busy.waitTicks(120); assertNull(find(busy.output, "M70"));
	}

	@Test public void thePresenceSettleBoundaryIsAgeTenAndDeferralKeepsTheOriginalDeadline()
	{
		for (int age : new int[]{9, 10})
		{
			Replay r = new Replay(); r.frame.presentSinceTick = r.frame.tick + 1; r.next(); if (age == 10) { r.next(); }
			move(r, 1); r.waitTicks(2); move(r, 1); r.waitTicks(2); move(r, 1); r.next();
			SceneCandidate c = find(r.next(), "M55"); assertNotNull(c); assertEquals(age, r.current.tick - r.current.presentSinceTick);
			assertEquals(age == 10, r.valid(c)); long deadline = c.getExpiresTick();
			assertNull(find(r.next(), "M55")); assertTrue(r.valid(c)); assertEquals(deadline, c.getExpiresTick());
		}
	}

	@Test public void productionReducerUsesTheSameQuietWindowAndRechecksNewXpBeforePaint()
	{
		ScenarioReplay r = new ScenarioReplay(0);
		for (int i = 0; i < 121; i++) { assertNull(find(r.next(), "M70")); }
		SceneCandidate c = find(r.next(), "M70"); assertNotNull(c); assertTrue(r.rules.stillValid(c));
		r.xp(Skill.FISHING, 1); r.next(ScenarioReplay.stats(Skill.FISHING)); assertFalse(r.rules.stillValid(c));
		r.next(); assertFalse("The lost unpainted context cannot return", r.rules.stillValid(c));
	}

	@Test public void productionReducerDecoratesOnlyTheThirdRealM10AndRevokesItOnDeparture()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.player = new Position(3199, 3210, 0); r.frame.companion = new Position(3202, 3210, 0);
		for (int i = 0; i < 12; i++) { r.next(); }
		for (int arrival = 1; arrival <= 3; arrival++)
		{
			r.frame.player = new Position(3199, 3210, 0); r.frame.companion = new Position(3202, 3210, 0); r.next();
			r.frame.player = new Position(3200, 3210, 0); r.frame.companion = new Position(3203, 3210, 0);
			for (int i = 0; i < 3; i++) { assertNull(find(r.next(), "M57")); }
			List<SceneCandidate> output = r.next(); String expected = arrival == 3 ? "M57" : "M10";
			SceneCandidate c = find(output, expected); assertNotNull(expected, c);
			assertEquals(1, output.stream().filter(candidate -> candidate.getScenarioId().equals("M10") || candidate.getScenarioId().equals("M57")).count());
			if (arrival == 3)
			{
				assertTrue(r.rules.stillValid(c)); r.frame.player = new Position(3199, 3210, 0); r.next(); assertFalse(r.rules.stillValid(c));
			}
		}
	}

	@Test public void safePositionalContinuationAllowsItsOwnNativeHoldAndNormalFollowing()
	{
		Replay r = new Replay(); r.rules.reset(); r.waitTicks(120); SceneCandidate c = find(r.output, "M70"); assertNotNull(c);
		r.frame.motion = motion(r, 123, Purpose.OTHER, Status.INVALIDATED, 0, 0, r.frame.tick, -1, -1, 3, 0);
		r.frame.companion = new Position(r.baseX + 4, r.baseY, 0); r.next(); assertTrue(r.valid(c));
		r.frame.motion = motion(r, 124, Purpose.ROAM, Status.MOVING, 0, 0, r.frame.tick, -1, -1, 5, 0);
		r.frame.companion = new Position(r.baseX + 5, r.baseY, 0); r.next(); assertTrue(r.valid(c));
	}

	@Test public void productionNativeReactionsSurviveOnlyTheirOwnAdmittedGenuineHold()
	{
		for (String id : new String[]{"M51", "M58", "M60"})
		{
			NativeReplay replay = productionNative(id); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate; assertNotNull(id, c);
			MotionObservation original = r.frame.motionCompletion; long deadline = c.getExpiresTick();
			assertEquals(original.requestId, r.rules.motionRequestId(c));
			r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original);
			if ("M51".equals(id)) { r.frame.player = new Position(r.frame.player.x + 1, r.frame.player.y, r.frame.player.plane); }
			r.frame.motionHold = hold(c, original, r.frame, true); r.next();
			assertTrue(id, r.rules.stillValid(c)); assertEquals(deadline, c.getExpiresTick());
			r.frame.motionHold = new MotionHoldReceipt(c.getId() + 1, original, c.getCreatedTick(), r.frame.tick,
				original.placementGeneration, original.worldViewId, original.plane, r.frame.companion.x, r.frame.companion.y, true);
			if ("M51".equals(id)) { r.frame.player = new Position(r.frame.player.x - 1, r.frame.player.y, r.frame.player.plane); }
			r.next(); assertFalse("A foreign hold cannot borrow " + id, r.rules.stillValid(c));
			r.frame.motionHold = hold(c, original, r.frame, true);
			if ("M51".equals(id)) { r.frame.player = new Position(r.frame.player.x + 1, r.frame.player.y, r.frame.player.plane); }
			r.next(); assertFalse("A revoked cause never returns", r.rules.stillValid(c));
			assertEquals(-1, r.rules.motionRequestId(c));
		}
	}

	@Test public void productionHoldRejectsMissingOwnershipForgedCompletionInvalidStandingAndChangedScope()
	{
		for (int invalid = 0; invalid < 16; invalid++)
		{
			NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate; assertNotNull(c);
			MotionObservation original = r.frame.motionCompletion; r.frame.activeSceneCandidateId = c.getId();
			r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, true);
			MotionHoldReceipt held = r.frame.motionHold;
			if (invalid == 0) { r.frame.motionHold = null; }
			if (invalid == 1) { r.frame.activeSceneCandidateId = -1; }
			if (invalid == 2) { r.frame.activeSceneCandidateId++; }
			if (invalid == 3) { r.frame.motionHold = hold(c, original, r.frame, false); }
			if (invalid == 4) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), null, held.admittedTick, held.heldTick, 1, -1, 0, held.worldX, held.worldY, true); }
			if (invalid == 5) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, c.getCreatedTick() - 1, held.heldTick, 1, -1, 0, held.worldX, held.worldY, true); }
			if (invalid == 6) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, c.getExpiresTick(), c.getExpiresTick(), 1, -1, 0, held.worldX, held.worldY, true); }
			if (invalid == 7) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, held.admittedTick, r.frame.tick + 2, 1, -1, 0, held.worldX, held.worldY, true); }
			if (invalid == 8) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, held.admittedTick, held.heldTick, 2, -1, 0, held.worldX, held.worldY, true); }
			if (invalid == 9) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, held.admittedTick, held.heldTick, 1, 7, 0, held.worldX, held.worldY, true); }
			if (invalid == 10) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, held.admittedTick, held.heldTick, 1, -1, 1, held.worldX, held.worldY, true); }
			if (invalid == 11) { r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, held.admittedTick, held.heldTick, 1, -1, 0, held.worldX + 1, held.worldY, true); }
			if (invalid == 12) { r.frame.placementGeneration++; }
			if (invalid == 13) { r.frame.sceneRevision++; }
			if (invalid == 14) { r.frame.presentSinceTick++; }
			if (invalid == 15) { r.frame.motion = invalidated(productionReceipt(r, original.requestId, Purpose.OTHER, Status.RECOVERED)); }
			r.next(); assertFalse("invalid owned hold " + invalid, r.rules.stillValid(c));
		}
	}

	@Test public void exactCandidateLeaseCannotSubstituteAnotherCompletedRouteWithTheSameRequestNumber()
	{
		NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate;
		MotionObservation original = r.frame.motionCompletion; r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original);
		MotionObservation differentCompletion = productionReceipt(r, original.requestId, Purpose.CATCHUP, Status.ARRIVED);
		r.frame.motionHold = hold(c, differentCompletion, r.frame, true); r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void ownedNativeHoldPreservesFirstPaintExpiryWhileAnAlreadyAdmittedHoldMayContinue()
	{
		NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate; MotionObservation original = r.frame.motionCompletion;
		r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, true);
		while (r.frame.tick < c.getExpiresTick() - 1) { r.next(); assertTrue(r.rules.stillValid(c)); }
		r.next(); assertFalse(r.rules.stillValid(c)); assertTrue(r.rules.continuationValid(c));
		r.frame.inventoryBlocked = true; r.next(); assertFalse(r.rules.continuationValid(c));
	}

	@Test public void paintedAndSilentTravelKeepsOnlyItsOwnLegalHoldAfterThePrepaintMovementConditionEnds() throws Exception
	{
		for (String id : new String[]{"M51", "M60"})
		{
			for (boolean silent : new boolean[]{false, true})
			{
				NativeReplay nativeReplay = productionNative(id); ScenarioReplay r = nativeReplay.replay; SceneCandidate c = nativeReplay.candidate;
				MotionObservation original = r.frame.motionCompletion; SpeechDirector director = director(r); long deadline = c.getExpiresTick();
				director.tick(r.frame.tick, 1, 1, true, !silent, false); assertTrue(director.offer(c)); assertFalse(director.isCommitted());
				r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, true);
				r.rules.setMotionObservations(r.frame.motion, original, 1); r.rules.setActiveSceneMotion(c.getId(), r.frame.motionHold, false);
				director.arrive(c.getId(), r.frame.tick); SpeechPacket packet = director.getPacket(); SpeechPacket.PaintReceipt paint = null;
				if (silent) { assertNull(packet); }
				else
				{
					assertNotNull(packet); paint = packet.paint(0, r.frame.tick, 1, () -> {}); assertNotNull(paint);
					assertNull("Paint can commit before the callback acknowledges it", director.getLastPaint());
				}
				assertTrue(director.isCommitted()); r.frame.activeSceneCommitted = director.isCommitted();
				if ("M60".equals(id)) { r.frame.player = new Position(r.frame.player.x + 1, r.frame.player.y, 0); }
				r.next(); assertTrue(r.rules.continuationValid(c));
				director.tick(r.frame.tick, 1, 1, true, !silent, false); assertFalse(director.isContinuationCancelled());
				if (paint != null) { director.acknowledge(packet, paint); assertSame(paint, director.getLastPaint()); }
				while (r.frame.tick < director.getHoldUntilTick() - 1)
				{
					r.next(); assertTrue(r.rules.continuationValid(c)); director.tick(r.frame.tick, 1, 1, true, !silent, false);
					assertEquals(c.getId(), director.getActiveCandidate().getId());
				}
				assertEquals(deadline, c.getExpiresTick()); r.frame.motionHold = null; r.next(); assertFalse(r.rules.continuationValid(c));
			}
		}
	}

	@Test public void selectingSilenceOrOwningAPhysicalHoldCannotCommitThePrepaintTravelFact() throws Exception
	{
		for (String id : new String[]{"M51", "M60"})
		{
			for (boolean silent : new boolean[]{false, true})
			{
				NativeReplay nativeReplay = productionNative(id); ScenarioReplay r = nativeReplay.replay; SceneCandidate c = nativeReplay.candidate;
				MotionObservation original = r.frame.motionCompletion; SpeechDirector director = director(r);
				director.tick(r.frame.tick, 1, 1, true, !silent, false); assertTrue(director.offer(c));
				r.frame.activeSceneCandidateId = c.getId(); r.frame.activeSceneCommitted = director.isCommitted();
				r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, true);
				if ("M60".equals(id)) { r.frame.player = new Position(r.frame.player.x + 1, r.frame.player.y, 0); }
				r.next(); assertFalse(r.rules.stillValid(c)); assertFalse(r.rules.continuationValid(c));
				director.arrive(c.getId(), r.frame.tick); assertFalse(director.isCommitted()); assertNull(director.getPacket());
				assertEquals(-1, r.rules.motionRequestId(c));
			}
		}
	}

	@Test public void committedTravelStillRejectsForeignOwnershipUnknownCombatBadStandingAndChangedScope() throws Exception
	{
		for (int invalid = 0; invalid < 6; invalid++)
		{
			NativeReplay nativeReplay = productionNative("M51"); ScenarioReplay r = nativeReplay.replay; SceneCandidate c = nativeReplay.candidate;
			MotionObservation original = r.frame.motionCompletion; SpeechDirector director = director(r);
			director.tick(r.frame.tick, 1, 1, true, false, false); assertTrue(director.offer(c)); director.arrive(c.getId(), r.frame.tick);
			assertTrue(director.isCommitted()); r.frame.activeSceneCandidateId = c.getId(); r.frame.activeSceneCommitted = director.isCommitted();
			r.frame.motion = invalidated(original); r.frame.motionHold = hold(c, original, r.frame, true);
			if (invalid == 0) { r.frame.activeSceneCandidateId++; }
			if (invalid == 1) { r.frame.motionHold = null; }
			if (invalid == 2) { r.frame.motionHold = hold(c, original, r.frame, false); }
			if (invalid == 3) { r.frame.targetUnknown = true; }
			if (invalid == 4) { r.frame.placementGeneration++; }
			if (invalid == 5) { r.frame.player = new Position(r.frame.companion.x - 6, r.frame.player.y, 0); }
			r.next(); assertFalse("Committed proof failure " + invalid, r.rules.continuationValid(c));
		}
	}

	@Test public void ownedNormalResumeHasOnlyOneTickToBecomeHealthyFollowing()
	{
		NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate; MotionObservation original = r.frame.motionCompletion;
		r.frame.activeSceneCandidateId = c.getId(); r.frame.motion = invalidated(original);
		r.frame.motionHold = new MotionHoldReceipt(c.getId(), original, c.getCreatedTick(), r.frame.tick,
			original.placementGeneration, original.worldViewId, original.plane, r.frame.companion.x, r.frame.companion.y, true, MotionHoldReceipt.Mode.NORMAL_RESUME);
		r.next(); assertTrue(r.rules.stillValid(c)); r.next(); assertFalse("A resume bridge cannot become a perpetual normal lease", r.rules.stillValid(c));
	}

	@Test public void aNewBudgetFailureOrFrontierCannotFinishAnOlderSuccessfulCatchupStretch()
	{
		for (boolean budget : new boolean[]{false, true})
		{
			Replay r = caughtUp(42, 2); MotionObservation.Builder replacement = builder(r, 43, Purpose.CATCHUP, budget ? Status.BUDGET_EXHAUSTED : Status.MOVING);
			replacement.legalGoal = false; replacement.completedTick = -1; replacement.completedEdges = 0;
			r.frame.motion = replacement.build(); assertNull(movingSix(r));
		}
	}

	@Test public void borrowingACandidateNumberCannotChangeItsCauseFactsOrOriginalDeadline()
	{
		NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate original = replay.candidate;
		for (int altered = 0; altered < 3; altered++)
		{
			Map<String, String> facts = new java.util.HashMap<>(original.getConditionFacts()); if (altered == 2) { facts.clear(); }
			SceneCandidate forged = new SceneCandidate(original.getId(), original.getScenarioId(), original.getPriority(), original.getCreatedTick(),
				original.getExpiresTick() + (altered == 0 ? 1 : 0), original.getSessionGeneration(), original.getSceneRevision(),
				altered == 1 ? "catchup:99" : original.getCausalToken(), facts, false, original.getHoldTicks());
			assertFalse(r.rules.stillValid(forged)); assertEquals(-1, r.rules.motionRequestId(forged));
		}
		assertTrue(r.rules.stillValid(original));
	}

	@Test public void betweenCommitNativeInvalidationIsVisibleBeforePaintAndOwnedStandingUsesTheCompletedNode()
	{
		NativeReplay replay = productionNative("M60"); ScenarioReplay r = replay.replay; SceneCandidate c = replay.candidate;
		MotionObservation original = r.frame.motionCompletion; long tick = r.rules.getCurrent().tick;
		r.rules.setMotionObservations(invalidated(original), original, 1); assertFalse("Current generic invalidation must not use a stale ARRIVED frame", r.rules.stillValid(c));
		MotionHoldReceipt own = new MotionHoldReceipt(c.getId(), original, c.getCreatedTick(), tick, 1, -1, 0,
			r.frame.player.x + 4, r.frame.player.y, true);
		r.rules.setActiveSceneMotion(c.getId(), own); assertTrue(r.rules.stillValid(c));
		assertEquals(tick, r.rules.getCurrent().tick); assertEquals(c.getExpiresTick(), replay.candidate.getExpiresTick());
		assertEquals(new Position(own.worldX, own.worldY, own.plane), r.rules.getCurrent().companion);
		r.rules.setActiveSceneMotion(-1, null); assertFalse(r.rules.stillValid(c));
	}

	private static NativeReplay productionNative(String id)
	{
		ScenarioReplay r = new ScenarioReplay(0); int plane = "M58".equals(id) ? 1 : 0;
		List<SceneCandidate> output = Collections.emptyList();
		r.frame.player = new Position(3100, 3200, plane); r.frame.companion = new Position(3103, 3200, plane);
		r.frame.placementGeneration = 1; r.frame.worldViewId = -1; for (int i = 0; i < 12; i++) { r.next(); }
		if ("M58".equals(id))
		{
			for (int i = 1; i <= 60; i++)
			{
				if (i == 10 || i == 35 || i == 60)
				{
					r.frame.companion = new Position(i == 10 ? 3103 : i == 35 ? 3097 : 3100, i == 60 ? 3203 : 3200, 1);
					r.frame.motion = productionReceipt(r, i, Purpose.ROAM, Status.ARRIVED); r.frame.motionCompletion = r.frame.motion;
				}
				output = r.next();
			}
		}
		else
		{
			if ("M51".equals(id)) { r.frame.player = new Position(3101, 3200, 0); r.frame.companion = new Position(3104, 3200, 0); }
			r.frame.motion = productionReceipt(r, 42, Purpose.CATCHUP, Status.ARRIVED); r.frame.motionCompletion = r.frame.motion; output = r.next();
			if ("M51".equals(id))
			{
				for (int i = 1; i <= 6; i++) { r.frame.player = new Position(3101 + i, 3200, 0); r.frame.companion = new Position(3104 + i, 3200, 0); output = r.next(); }
			}
		}
		return new NativeReplay(r, find(output, id));
	}
	private static MotionObservation productionReceipt(ScenarioReplay r, long requestId, Purpose purpose, Status status)
	{
		return productionReceipt(r, requestId, purpose, status, Math.max(r.frame.presentSinceTick, r.frame.tick - 2));
	}
	private static MotionObservation productionReceipt(ScenarioReplay r, long requestId, Purpose purpose, Status status, long startedTick)
	{
		MotionObservation.Builder b = new MotionObservation.Builder(); b.requestId = requestId; b.purpose = purpose; b.status = status;
		b.startedTick = startedTick; b.completedTick = status == Status.ARRIVED ? r.frame.tick + 1 : -1;
		b.lastCompletedEdgeTick = b.completedTick;
		b.startDistance = 11; b.arrivalDistance = 3; b.completedEdges = status == Status.ARRIVED ? 4 : 0; b.completedRunEdges = status == Status.ARRIVED ? 2 : 0;
		b.placementGeneration = r.frame.placementGeneration; b.worldViewId = r.frame.worldViewId; b.plane = r.frame.player.plane;
		b.goalWorldX = r.frame.companion.x; b.goalWorldY = r.frame.companion.y; b.startWorldX = b.goalWorldX + 10; b.startWorldY = b.goalWorldY;
		b.ordinary = true; b.known = true; b.legalGoal = true; b.fixedGoal = purpose == Purpose.ROAM; return b.build();
	}
	private static void advancePlayer(ScenarioReplay r)
	{
		r.frame.player = new Position(r.frame.player.x + 1, r.frame.player.y, 0);
		r.frame.companion = new Position(r.frame.player.x + 3, r.frame.player.y, 0);
	}
	private static SpeechDirector director(ScenarioReplay r) throws Exception
	{
		Gson gson = new Gson(); JsonObject copy;
		try (InputStream input = TravelExpansionRulesTest.class.getResourceAsStream("/com/malfoid/dialogue.json"))
		{ copy = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class); }
		copy.getAsJsonArray("runtimeEnabledScenarios").add("M51"); copy.getAsJsonArray("runtimeEnabledScenarios").add("M60");
		copy.getAsJsonObject("silencePercentByScenario").addProperty("M51", 0); copy.getAsJsonObject("silencePercentByScenario").addProperty("M60", 0);
		for (JsonElement element : copy.getAsJsonArray("entries"))
		{
			JsonObject entry = element.getAsJsonObject(); String id = entry.get("scenario").getAsString();
			if ("M51".equals(id) || "M60".equals(id)) { entry.addProperty("status", "enabled"); }
		}
		DialogueCatalog catalog = DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
		SpeechGuard guard = new SpeechGuard(() -> 0L, new Random(1), catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
		Random spoken = new Random() { @Override public int nextInt(int bound) { return bound - 1; } };
		SpeechDirector director = new SpeechDirector(catalog, () -> 0L, spoken, ignored -> {}, guard);
		director.setCandidateValidator(r.rules::continuationValid); return director;
	}
	private static MotionObservation invalidated(MotionObservation original)
	{
		MotionObservation.Builder b = new MotionObservation.Builder(); b.requestId = original.requestId; b.purpose = original.purpose;
		b.status = original.status == Status.RECOVERED ? Status.RECOVERED : Status.INVALIDATED; b.known = original.known;
		b.ordinary = original.ordinary; b.legalGoal = original.legalGoal; b.placementGeneration = original.placementGeneration; b.worldViewId = original.worldViewId; b.plane = original.plane;
		return b.build();
	}
	private static MotionHoldReceipt hold(SceneCandidate c, MotionObservation original, ScenarioSnapshot.Builder frame, boolean legal)
	{
		return new MotionHoldReceipt(c.getId(), original, c.getCreatedTick(), frame.tick, original.placementGeneration,
			original.worldViewId, original.plane, frame.companion.x, frame.companion.y, legal);
	}
	private static final class NativeReplay
	{
		final ScenarioReplay replay; final SceneCandidate candidate;
		NativeReplay(ScenarioReplay replay, SceneCandidate candidate) { this.replay = replay; this.candidate = candidate; }
	}

	private static void stop(Replay r, int x, int y) { r.position(x, y); r.next(); r.next(); }
	private static void walkTo(Replay r, int x, int y)
	{
		while (r.frame.player.x != r.baseX + x || r.frame.player.y != r.baseY + y)
		{
			int nextX = r.frame.player.x + Math.max(-2, Math.min(2, r.baseX + x - r.frame.player.x));
			int nextY = r.frame.player.y + Math.max(-2, Math.min(2, r.baseY + y - r.frame.player.y)); r.absolute(nextX, nextY, r.frame.player.plane); r.next();
		}
	}
	private static int[][] circuitPoints(int count)
	{
		if (count == 3) { return new int[][]{{0, 0}, {1, 0}, {1, 1}}; }
		if (count == 4) { return new int[][]{{0, 0}, {2, 0}, {2, 2}, {0, 2}}; }
		int[][] eight = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}, {1, 1}};
		return Arrays.copyOf(eight, count);
	}
	private static SceneCandidate loop(Replay r, int[][] points)
	{
		for (int i = 1; i < points.length; i++) { r.position(points[i][0], points[i][1]); assertNull(find(r.next(), "M53")); }
		r.position(points[0][0], points[0][1]); return find(r.next(), "M53");
	}
	private static SceneCandidate repeatCircuit(Replay r, int[][] points, boolean reordered)
	{
		assertNull(loop(r, points));
		if (reordered) { int[] swap = points[1]; points[1] = points[2]; points[2] = swap; }
		return loop(r, points);
	}
	private static SceneCandidate dogleg(Replay r, int cornerX, int cornerY)
	{
		for (int[] p : new int[][]{{1, 0}, {cornerX, cornerY}, {1, 0}, {0, 0}}) { r.position(p[0], p[1]); r.next(); }
		return find(r.output, "M54");
	}
	private static void move(Replay r, int count)
	{
		for (int i = 0; i < count; i++) { r.absolute(r.frame.player.x + 1, r.frame.player.y, r.frame.player.plane); r.next(); }
	}
	private static Replay startStopPrefix(int moving, int still)
	{
		Replay r = new Replay(); r.rules.reset(); move(r, moving); r.waitTicks(still); move(r, 1); r.waitTicks(still); return r;
	}
	private static Replay travel(int moves, int span)
	{
		Replay r = new Replay(); r.rules.reset(); int offset = 0;
		for (int sample = 0; sample < 64; sample++)
		{
			int step = sample - (64 - moves);
			if (step >= 0) { offset = step < span ? step + 1 : step < span * 2 ? span * 2 - step - 1 : (step & 1) + 1; }
			r.position(offset, 0); r.next();
		}
		return r;
	}
	private static SceneCandidate crossing(Replay r)
	{
		SceneCandidate original = crossingBase(r); return r.rules.decorateArrival(r.current, original, r::candidate);
	}
	private static SceneCandidate crossingBase(Replay r)
	{
		r.absolute(3199, 3210, 0); r.next(); r.absolute(3200, 3210, 0); r.waitTicks(4); return r.baseArrival();
	}
	private static SceneCandidate heading(Replay r, int angle)
	{
		r.frame.observedFacing = angle; assertNull(find(r.next(), "M59")); return find(r.next(), "M59");
	}
	private static Replay caughtUp(long id, int runs)
	{
		Replay r = new Replay(); r.position(1, 0); receipt(r, id, Purpose.CATCHUP, runs, 4); r.next(); return r;
	}
	private static SceneCandidate movingSix(Replay r)
	{
		int start = r.frame.player.x - r.baseX; SceneCandidate c = null;
		for (int i = 1; i <= 6; i++) { r.position(start + i, 0); c = find(r.next(), "M51"); }
		return c;
	}
	private static void receipt(Replay r, long id, Purpose purpose, int runs, int edges)
	{
		MotionObservation.Builder b = builder(r, id, purpose, Status.ARRIVED); b.completedRunEdges = runs; b.completedEdges = edges;
		r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion;
	}
	private static MotionObservation.Builder builder(Replay r, long id, Purpose purpose, Status status)
	{
		MotionObservation.Builder b = new MotionObservation.Builder(); b.requestId = id; b.purpose = purpose; b.status = status;
		b.startedTick = Math.max(r.frame.presentSinceTick, r.frame.tick - 2); b.completedTick = r.frame.tick + 1; b.lastCompletedEdgeTick = r.frame.tick + 1;
		b.startDistance = 11; b.arrivalDistance = 3; b.completedEdges = 3; b.completedRunEdges = 2;
		b.placementGeneration = 1; b.worldViewId = -1; b.plane = r.frame.player.plane;
		b.goalWorldX = r.frame.companion.x; b.goalWorldY = r.frame.companion.y;
		b.startWorldX = r.frame.companion.x + 10; b.startWorldY = r.frame.companion.y;
		b.ordinary = true; b.known = true; b.legalGoal = true; b.fixedGoal = purpose == Purpose.ROAM; return b;
	}
	private static MotionObservation motion(Replay r, long id, Purpose purpose, Status status, int runs, int edges,
		long started, long completed, long lastEdge, int goalX, int goalY)
	{
		MotionObservation.Builder b = builder(r, id, purpose, status); b.completedRunEdges = runs; b.completedEdges = edges;
		b.startedTick = started; b.completedTick = completed; b.lastCompletedEdgeTick = lastEdge; b.goalWorldX = r.baseX + goalX; b.goalWorldY = r.baseY + goalY; return b.build();
	}
	private static Replay wandering(int lastSample, boolean duplicateGoal, boolean duplicateRequest, boolean excursion)
	{
		Replay r = new Replay(3100, 3200, 1); r.rules.reset();
		for (int sample = 1; sample <= lastSample; sample++)
		{
			if (excursion) { r.position(sample == 20 ? 3 : sample == 19 || sample == 21 ? 2 : 0, 0); }
			if (sample == 10 || sample == 35 || sample == lastSample)
			{
				long id = duplicateRequest ? 1 : sample; MotionObservation.Builder b = builder(r, id, Purpose.ROAM, Status.ARRIVED);
				b.goalWorldX = r.baseX + (sample == 10 || duplicateGoal && sample == lastSample ? 3 : sample == 35 ? -3 : 0);
				b.goalWorldY = r.baseY + (sample == lastSample && !duplicateGoal ? 3 : 0);
				r.frame.companion = new Position(b.goalWorldX, b.goalWorldY, 1); r.frame.motion = b.build(); r.frame.motionCompletion = r.frame.motion;
			}
			r.next();
		}
		return r;
	}
	private static void contract(SceneCandidate c, int priority, int life, int approach, int hold)
	{
		assertNotNull(c); assertEquals(priority, c.getPriority()); assertEquals(life, c.getExpiresTick() - c.getCreatedTick());
		assertEquals(Integer.toString(approach), c.getConditionFacts().get("maximumApproachTicks")); assertEquals(hold, c.getHoldTicks());
	}

	/** Feeds immutable final copies; no rendering or simulated animation supplies movement facts. */
	private static final class Replay
	{
		final TravelExpansionRules rules = new TravelExpansionRules(); final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		final int baseX, baseY; ScenarioSnapshot current, prior; long candidateId, arrivalId;
		CombatState combat = CombatState.NO_CONFIRMED_EVIDENCE; List<SceneCandidate> output = Collections.emptyList();
		Replay() { this(3100, 3200, 0); }
		Replay(int x, int y, int plane)
		{
			baseX = x; baseY = y; frame.xp = new int[Skill.values().length]; Arrays.fill(frame.xp, 100);
			frame.placementGeneration = 1; frame.worldViewId = -1;
			frame.levels = new int[Skill.values().length]; Arrays.fill(frame.levels, 10); frame.boosted = frame.levels.clone();
			absolute(x, y, plane); waitTicks(12);
		}
		void absolute(int x, int y, int plane) { frame.player = new Position(x, y, plane); frame.companion = new Position(x + 3, y, plane); }
		void position(int x, int y) { absolute(baseX + x, baseY + y, frame.player.plane); }
		void waitTicks(int ticks) { for (int i = 0; i < ticks; i++) { next(); } }
		List<SceneCandidate> next()
		{
			frame.tick++; prior = current; current = frame.build();
			output = rules.close(current, prior, ScenarioBatch.empty(), Collections.emptyList(), combat, Long.MIN_VALUE / 2, this::candidate); return output;
		}
		List<SceneCandidate> duplicate() { return rules.close(current, prior, ScenarioBatch.empty(), Collections.emptyList(), combat, Long.MIN_VALUE / 2, this::candidate); }
		boolean valid(SceneCandidate c) { return rules.stillValid(c, current, combat, Long.MIN_VALUE / 2); }
		SceneCandidate baseArrival()
		{
			Map<String, String> facts = new java.util.HashMap<>(); facts.put("actualRegion", "12850"); facts.put("arrivalToken", Long.toString(++arrivalId));
			facts.put("occurredTick", Long.toString(current.tick)); facts.put("witnessPresenceEpoch", Long.toString(current.presentSinceTick));
			return candidate(current, "M10", 20, 20, "arrival:" + arrivalId, facts, false, 6);
		}
		SceneCandidate candidate(ScenarioSnapshot f, String id, int priority, int life, String cause, Map<String, String> facts, boolean silent, int hold)
		{
			return new SceneCandidate(++candidateId, id, priority, f.tick, f.tick + life, f.sessionGeneration, f.sceneRevision, cause, facts, silent, hold);
		}
	}
}
