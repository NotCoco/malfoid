package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.*;
import static org.junit.Assert.*;

public class InventoryExpansionRulesTest
{
	@Test public void m39NeedsTenUnchangedLayoutsAndEightObservedTiles()
	{
		ScenarioReplay r = new ScenarioReplay(); kit(r);
		for (int i = 0; i < 9; i++) { move(r, 1); assertNull(find(r.next(), "M39")); }
		SceneCandidate c = find(r.next(), "M39"); assertNotNull(c);
		assertEquals("954,1925,1944", c.getConditionFacts().get("namedItemIds"));
		assertEquals(21, c.getPriority()); assertEquals(12, c.getExpiresTick() - c.getCreatedTick());
		assertTrue(r.rules.stillValid(c)); assertNull(find(r.next(), "M39"));
		r.item(2, 1944, 2); r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m39MovementDeadlineIsInclusiveAndSevenTilesNeverQualifies()
	{
		for (int finalAge : new int[]{12, 13})
		{
			ScenarioReplay r = new ScenarioReplay(); kit(r); long start = r.frame.tick + 1; r.next();
			for (int i = 0; i < 7; i++) { move(r, 1); assertNull(find(r.next(), "M39")); }
			while (r.frame.tick < start + finalAge - 1) { assertNull(find(r.next(), "M39")); }
			move(r, 1); assertEquals(finalAge == 12, find(r.next(), "M39") != null);
		}
	}

	@Test public void m39RejectsStartupQuantityVariantsFourthItemsAndLayoutChanges()
	{
		ScenarioReplay startup = new ScenarioReplay(0); kit(startup);
		for (int i = 0; i < 20; i++) { move(startup, 1); assertNull(find(startup.next(), "M39")); }
		for (int kind = 0; kind < 3; kind++)
		{
			ScenarioReplay r = new ScenarioReplay(); kit(r);
			if (kind == 0) { r.item(2, 1944, 2); }
			if (kind == 1) { r.item(2, 1945, 1); }
			if (kind == 2) { r.item(3, 1000, 1); }
			for (int i = 0; i < 15; i++) { move(r, 1); assertNull(find(r.next(), "M39")); }
		}
		ScenarioReplay moved = new ScenarioReplay(); kit(moved);
		for (int i = 0; i < 5; i++) { move(moved, 1); moved.next(); }
		swap(moved, 0, 1);
		for (int i = 0; i < 10; i++) { move(moved, 1); assertNull(find(moved.next(), "M39")); }
	}

	@Test public void m39NeedsFiveKnownFalseCommitsToRearmAndRejectsLargeSteps()
	{
		ScenarioReplay r = new ScenarioReplay(); kit(r);
		for (int i = 0; i < 10; i++) { move(r, 1); r.next(); }
		r.item(2, -1, 0); for (int i = 0; i < 4; i++) { r.next(); } kit(r);
		for (int i = 0; i < 10; i++) { move(r, 1); assertNull(find(r.next(), "M39")); }
		r.item(2, -1, 0); for (int i = 0; i < 5; i++) { r.next(); } kit(r);
		SceneCandidate c = null;
		for (int i = 0; i < 10; i++) { move(r, 1); c = find(r.next(), "M39"); }
		assertNotNull(c);
		ScenarioReplay jump = new ScenarioReplay(); kit(jump); jump.next(); move(jump, 3); jump.next();
		for (int i = 0; i < 12; i++) { move(jump, 1); assertNull(find(jump.next(), "M39")); }
	}

	@Test public void m41RequiresThreeContentsChangesAndTenWitnessedFishingCommits()
	{
		ScenarioReplay r = anchor(1965, 5); SceneCandidate c = null;
		for (int i = 0; i < 10; i++)
		{
			if (i < 3) { r.item(0, 1000 + i, 1); }
			r.xp(Skill.FISHING, 1);
			if (i == 9) { r.xp(Skill.COOKING, 1); }
			c = find(r.next(i == 9 ? stats(Skill.FISHING, Skill.COOKING) : stats(Skill.FISHING)), "M41");
			assertEquals(i == 9, c != null);
		}
		assertEquals("5", c.getConditionFacts().get("anchorSlot")); assertTrue(r.rules.stillValid(c));
		assertNull(find(r.next(), "M41"));
		for (int i = 0; i < 6; i++) { r.next(); }
		assertFalse(r.rules.stillValid(c));
	}

	@Test public void m41RearrangementsAndUnwitnessedOrOneLargeGainDoNotCount()
	{
		ScenarioReplay arranged = anchor(1965, 5); arranged.item(0, 1000, 1); arranged.item(1, 1001, 1); arranged.next();
		for (int i = 0; i < 12; i++) { swap(arranged, 0, 1); arranged.xp(Skill.FISHING, 1); assertNull(find(arranged.next(stats(Skill.FISHING)), "M41")); }
		ScenarioReplay large = anchor(1965, 5); threeContents(large);
		large.xp(Skill.FISHING, 1000); assertNull(find(large.next(stats(Skill.FISHING)), "M41"));
		for (int i = 0; i < 9; i++) { large.xp(Skill.FISHING, 1); assertNull(find(large.next(stats(Skill.COOKING)), "M41")); }
	}

	@Test public void m41MovingOrDuplicatingTheAnchorResetsItsEpisode()
	{
		for (boolean move : new boolean[]{false, true})
		{
			ScenarioReplay r = anchor(1965, 5); threeContents(r); gains(r, Skill.FISHING, 9, "M41");
			if (move) { r.item(5, -1, 0); r.item(6, 1965, 1); }
			else { r.item(5, 1965, 2); }
			r.xp(Skill.FISHING, 1); assertNull(find(r.next(stats(Skill.FISHING)), "M41"));
		}
	}

	@Test public void persistentPropsRequireRecentGainsAtFiveTicksButNotSix()
	{
		for (int item : new int[]{1965, 3695})
		{
			Skill skill = item == 1965 ? Skill.FISHING : Skill.MINING;
			String id = item == 1965 ? "M41" : "M49"; int threshold = item == 1965 ? 10 : 15;
			for (int age : new int[]{5, 6})
			{
				ScenarioReplay r = anchor(item, 5); r.item(0, 1000, 1); r.next(); r.item(0, 1001, 1); r.next();
				gains(r, skill, threshold, id);
				for (int i = 1; i < age; i++) { r.next(); }
				r.item(0, 1002, 1); assertEquals(age == 5, find(r.next(), id) != null);
			}
		}
	}

	@Test public void persistentPropWindowIncludesItsLastCommitAndResetsAtTheNext()
	{
		for (int item : new int[]{1965, 3695})
		{
			Skill skill = item == 1965 ? Skill.FISHING : Skill.MINING;
			String id = item == 1965 ? "M41" : "M49";
			int duration = item == 1965 ? 90 : 120, threshold = item == 1965 ? 10 : 15;
			for (int age : new int[]{duration - 1, duration})
			{
				ScenarioReplay r = anchor(item, 5); long start = r.frame.tick;
				r.item(0, 1000, 1); r.next(); r.item(0, 1001, 1); r.next(); gains(r, skill, threshold, id);
				while (r.frame.tick < start + age - 1) { r.next(); }
				r.item(0, 1002, 1); r.xp(skill, 1);
				assertEquals(age == duration - 1, find(r.next(stats(skill)), id) != null);
			}
		}
	}

	@Test public void m43NeedsThirtyFullCommitsThenTwoEqualOneSpaceCopies()
	{
		ScenarioReplay r = new ScenarioReplay(); full(r); repeat(r, 30, "M43"); r.item(27, -1, 0);
		assertNull(find(r.next(), "M43")); SceneCandidate c = find(r.next(), "M43"); assertNotNull(c);
		assertEquals(18, c.getPriority()); assertTrue(r.rules.stillValid(c));
		assertNull(find(r.next(), "M43")); r.item(0, 2000, 1); r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m43RejectsTwentyNineFullCommitsTwoSpacesAndUnstableFinalLayout()
	{
		for (int kind = 0; kind < 3; kind++)
		{
			ScenarioReplay r = new ScenarioReplay(); full(r); repeat(r, kind == 0 ? 29 : 30, "M43");
			r.item(27, -1, 0); if (kind == 1) { r.item(26, -1, 0); } r.next();
			if (kind == 2) { r.item(0, 1000, 2); }
			assertNull(find(r.next(), "M43")); assertNull(find(r.next(), "M43"));
		}
	}

	@Test public void startupSnapshotsDoNotCountTowardFullBagHistory()
	{
		ScenarioReplay r = new ScenarioReplay(0); full(r); repeat(r, 30, "M43");
		r.item(27, -1, 0); r.next(); assertNull(find(r.next(), "M43"));
		ScenarioReplay mining = new ScenarioReplay(0); full(mining);
		for (int i = 0; i < 60; i++) { mining.xp(Skill.MINING, 1); assertNull(find(mining.next(stats(Skill.MINING)), "M45")); }
		mining.xp(Skill.MINING, 1); assertNull(find(mining.next(stats(Skill.MINING)), "M45"));
		mining.xp(Skill.MINING, 1); assertNotNull(find(mining.next(stats(Skill.MINING)), "M45"));
	}

	@Test public void visibleInventoryInterfaceDuringAFullRunPreventsDelayedRetelling()
	{
		ScenarioReplay r = new ScenarioReplay(); full(r); repeat(r, 29, "M43");
		r.frame.inventoryBlocked = true; r.next(); r.frame.inventoryBlocked = false;
		r.next(); r.next(); r.item(27, -1, 0); r.next(); assertNull(find(r.next(), "M43"));
	}

	@Test public void m44DecoratesOnlyTheExistingHeadOnlyM09CauseAfterThirtyEarlierFullCommits()
	{
		ScenarioReplay r = fullProps(30); r.frame.equipment[0] = 101;
		assertNull(find(r.next(), "M44")); List<SceneCandidate> output = r.next();
		SceneCandidate c = find(output, "M44"); assertNotNull(c); assertNull(find(output, "M09"));
		assertTrue(c.getCausalToken().startsWith("outfit:")); assertEquals(27, c.getPriority());
		assertEquals("101,200,300,400", c.getConditionFacts().get("equipmentIds"));
		assertEquals("HEAD", c.getConditionFacts().get("changedSlots")); assertTrue(r.rules.stillValid(c));
		r.item(0, 2000, 1); r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m44DoesNotDecorateShortRunsMissingToolsOrMultiSlotChanges()
	{
		for (int kind = 0; kind < 4; kind++)
		{
			ScenarioReplay r = fullProps(kind == 0 ? 29 : 30);
			if (kind == 1) { r.item(0, 955, 1); }
			if (kind == 2) { r.item(27, -1, 0); }
			if (kind == 3) { r.frame.equipment[1] = 201; }
			r.frame.equipment[0] = 101; r.next(); assertNull(find(r.next(), "M44"));
		}
		ScenarioReplay disabled = fullProps(30); disabled.rules.setScenarioEnabled(id -> !"M44".equals(id));
		disabled.frame.equipment[0] = 101; disabled.next(); assertNotNull(find(disabled.next(), "M09"));
	}

	@Test public void m44CannotReviveWhenItsFullToolBagOrEquipmentReturns()
	{
		ScenarioReplay r = fullProps(30); r.frame.equipment[0] = 101; r.next();
		SceneCandidate c = find(r.next(), "M44"); assertNotNull(c);
		r.item(0, 2000, 1); r.next(); r.item(0, 954, 1); r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay worn = fullProps(30); worn.frame.equipment[0] = 101; worn.next();
		SceneCandidate hat = find(worn.next(), "M44"); assertNotNull(hat);
		worn.frame.equipment[0] = 102; worn.next(); worn.frame.equipment[0] = 101; worn.next();
		assertFalse(worn.rules.stillValid(hat));
	}

	@Test public void m44RejectsAStagedOtherSlotRemovalHiddenByTheChangedToRealItemSet()
	{
		ScenarioReplay r = fullProps(30); r.frame.equipment[1] = -1; r.next();
		r.frame.equipment[0] = 101; r.next(); List<SceneCandidate> settled = r.next();
		assertNull(find(settled, "M44")); SceneCandidate base = find(settled, "M09"); assertNotNull(base);
		assertEquals("HEAD", base.getConditionFacts().get("changedSlots"));
		assertEquals("100,200,300,400", base.getConditionFacts().get("priorEquipmentIds"));
	}

	@Test public void m45NeedsSixtyFullCommitsAndTwelveSeparateMiningGainsOncePerFullRun()
	{
		ScenarioReplay r = new ScenarioReplay(); full(r); SceneCandidate c = null;
		for (int i = 0; i < 60; i++)
		{
			if (i >= 48) { r.xp(Skill.MINING, 1); }
			c = find(r.next(i >= 48 ? stats(Skill.MINING) : ScenarioBatch.empty()), "M45");
			assertEquals(i == 59, c != null);
		}
		assertEquals(17, c.getPriority()); assertTrue(r.rules.stillValid(c));
		for (int i = 0; i < 70; i++) { r.xp(Skill.MINING, 1); assertNull(find(r.next(stats(Skill.MINING)), "M45")); }
		r.item(27, -1, 0); r.next(); assertFalse(r.rules.stillValid(c)); full(r);
		for (int i = 0; i < 59; i++) { r.xp(Skill.MINING, 1); assertNull(find(r.next(stats(Skill.MINING)), "M45")); }
		r.xp(Skill.MINING, 1); assertNotNull(find(r.next(stats(Skill.MINING)), "M45"));
	}

	@Test public void m45ElevenGainsOneStackAndAFullnessBreakOnANoXpTickDoNotQualify()
	{
		ScenarioReplay eleven = new ScenarioReplay(); full(eleven);
		for (int i = 0; i < 60; i++)
		{
			if (i >= 49) { eleven.xp(Skill.MINING, 1); }
			assertNull(find(eleven.next(i >= 49 ? stats(Skill.MINING) : ScenarioBatch.empty()), "M45"));
		}
		ScenarioReplay stack = new ScenarioReplay(); stack.item(0, 1000, 28);
		for (int i = 0; i < 65; i++) { stack.xp(Skill.MINING, 1); assertNull(find(stack.next(stats(Skill.MINING)), "M45")); }
		ScenarioReplay interrupted = new ScenarioReplay(); full(interrupted); repeat(interrupted, 59, "M45");
		interrupted.item(27, -1, 0); interrupted.next(); full(interrupted);
		for (int i = 0; i < 59; i++) { interrupted.xp(Skill.MINING, 1); assertNull(find(interrupted.next(stats(Skill.MINING)), "M45")); }
	}

	@Test public void m45LatestGainAgeFiveQualifiesAndSixDoesNot()
	{
		for (int age : new int[]{5, 6})
		{
			ScenarioReplay r = new ScenarioReplay(); full(r); SceneCandidate last = null;
			for (int n = 1; n <= 60; n++)
			{
				boolean gain = n >= 60 - age - 11 && n <= 60 - age;
				if (gain) { r.xp(Skill.MINING, 1); }
				last = find(r.next(gain ? stats(Skill.MINING) : ScenarioBatch.empty()), "M45");
				if (n < 60) { assertNull(last); }
			}
			assertEquals(age == 5, last != null);
		}
	}

	@Test public void m45OneLargeRewardAndUnknownXpDoNotSupplyTwelveGainTicks()
	{
		ScenarioReplay large = new ScenarioReplay(); full(large); repeat(large, 59, "M45");
		large.xp(Skill.MINING, 1200); assertNull(find(large.next(stats(Skill.MINING)), "M45"));
		ScenarioReplay unknown = new ScenarioReplay(); full(unknown); repeat(unknown, 59, "M45");
		unknown.frame.xp[Skill.MINING.ordinal()] = -1; unknown.next();
		unknown.frame.xp[Skill.MINING.ordinal()] = 200; unknown.next();
		for (int i = 0; i < 59; i++) { unknown.xp(Skill.MINING, 1); assertNull(find(unknown.next(stats(Skill.MINING)), "M45")); }
	}

	@Test public void m46NeedsThreeDifferentTwiceSettledExactMultisetLayouts()
	{
		ScenarioReplay r = arrangementAnchor(); SceneCandidate c = null;
		for (int i = 1; i <= 3; i++)
		{
			rotate(r); assertNull(find(r.next(), "M46")); c = find(r.next(), "M46"); assertEquals(i == 3, c != null);
		}
		assertEquals("1000,1001,1002,1003", c.getConditionFacts().get("anchorBagItemIds"));
		assertEquals(16, c.getPriority()); assertTrue(r.rules.stillValid(c)); assertNull(find(r.next(), "M46"));
		rotate(r); r.next(); assertFalse(r.rules.stillValid(c));
		for (int i = 0; i < 3; i++) { rotate(r); r.next(); }
		assertFalse(r.rules.stillValid(c));
	}

	@Test public void m46RepeatedUnsettledOrLowDiversityLayoutsDoNotQualify()
	{
		ScenarioReplay repeated = arrangementAnchor(); rotate(repeated); repeated.next(); repeated.next();
		rotate(repeated); repeated.next(); repeated.next(); rotate(repeated); rotate(repeated); rotate(repeated);
		repeated.next(); assertNull(find(repeated.next(), "M46"));
		ScenarioReplay unsettled = arrangementAnchor();
		for (int i = 0; i < 8; i++) { rotate(unsettled); assertNull(find(unsettled.next(), "M46")); }
		ScenarioReplay sparse = new ScenarioReplay();
		for (int i = 0; i < 8; i++) { sparse.item(i, 1000 + i % 3, 1); } sparse.next();
		for (int i = 0; i < 3; i++) { rotate(sparse); sparse.next(); assertNull(find(sparse.next(), "M46")); }
	}

	@Test public void m46QuantityAndItemMultisetChangesResetRatherThanGuessingRearrangement()
	{
		for (boolean quantity : new boolean[]{true, false})
		{
			ScenarioReplay r = arrangementAnchor(); rotate(r); r.next(); r.next(); rotate(r); r.next(); r.next();
			r.item(0, quantity ? r.frame.inventoryIds[0] : 2000, quantity ? 2 : 1); r.next();
			rotate(r); r.next(); assertNull(find(r.next(), "M46"));
			rotate(r); r.next(); assertNull(find(r.next(), "M46"));
		}
	}

	@Test public void m46FirstConfirmationAgeNinetyNineIsInsideAndOneHundredIsOutside()
	{
		for (int age : new int[]{99, 100})
		{
			ScenarioReplay r = arrangementAnchor(); rotate(r); r.next(); r.next(); long first = r.frame.tick;
			rotate(r); r.next(); r.next(); while (r.frame.tick < first + age - 2) { r.next(); }
			rotate(r); r.next(); assertEquals(age == 99, find(r.next(), "M46") != null);
		}
	}

	@Test public void m49NeedsExactRockAndFifteenMiningCommitsIncludingAMultiSkillTerminal()
	{
		ScenarioReplay r = anchor(3695, 5); threeContents(r); gains(r, Skill.MINING, 14, "M49");
		r.xp(Skill.MINING, 1); r.xp(Skill.COOKING, 1); SceneCandidate c = find(r.next(stats(Skill.MINING, Skill.COOKING)), "M49");
		assertNotNull(c); assertEquals("3695", c.getConditionFacts().get("namedItemIds")); assertTrue(r.rules.stillValid(c));
		r.item(5, -1, 0); r.item(6, 3695, 1); r.next(); assertFalse(r.rules.stillValid(c));
		for (int id : new int[]{3355, 3696})
		{
			ScenarioReplay wrong = anchor(id, 5); threeContents(wrong); gains(wrong, Skill.MINING, 16, "M49");
		}
		ScenarioReplay two = anchor(3695, 5); two.item(6, 3695, 1); threeContents(two); gains(two, Skill.MINING, 16, "M49");
	}

	@Test public void duplicateCallbacksAndLargeXpDeltasNeverSupplyExtraGainOrdinals()
	{
		Skill[] callbacks = new Skill[15]; for (int i = 0; i < callbacks.length; i++) { callbacks[i] = Skill.MINING; }
		ScenarioBatch batch = stats(callbacks); assertEquals(1, batch.witnessedSkills.size());
		ScenarioReplay r = anchor(3695, 5); threeContents(r); r.xp(Skill.MINING, 1500);
		assertNull(find(r.next(batch), "M49"));
		gains(r, Skill.MINING, 13, "M49"); r.xp(Skill.MINING, 1); assertNotNull(find(r.next(stats(Skill.MINING)), "M49"));
	}

	@Test public void uiUnknownCapacityPresenceAndSceneChangesDiscardHistoryAndPendingProof()
	{
		for (int interruption = 0; interruption < 5; interruption++)
		{
			ScenarioReplay r = anchor(1965, 5); threeContents(r); gains(r, Skill.FISHING, 9, "M41");
			int[] savedIds = r.frame.inventoryIds.clone(), savedQuantities = r.frame.inventoryQuantities.clone();
			if (interruption == 0) { r.frame.inventoryBlocked = true; }
			if (interruption == 1) { r.frame.inventoryIds = null; }
			if (interruption == 2) { r.frame.inventoryIds = new int[27]; r.frame.inventoryQuantities = new int[27]; }
			if (interruption == 3) { r.frame.present = false; }
			if (interruption == 4) { r.frame.sceneRevision++; }
			r.next(); r.frame.inventoryBlocked = false; r.frame.present = true;
			r.frame.inventoryIds = savedIds; r.frame.inventoryQuantities = savedQuantities;
			if (interruption == 3) { r.frame.presentSinceTick = r.frame.tick + 1; }
			r.xp(Skill.FISHING, 1); assertNull(find(r.next(stats(Skill.FISHING)), "M41"));
		}
		ScenarioReplay r = new ScenarioReplay(); full(r); repeat(r, 30, "M43"); r.item(27, -1, 0); r.next();
		SceneCandidate c = find(r.next(), "M43"); assertNotNull(c);
		r.frame.inventoryBlocked = true; r.next(); r.frame.inventoryBlocked = false; r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void missingXpBaselineAndUnwitnessedMultiSkillTerminalCannotFinishProgress()
	{
		ScenarioReplay r = anchor(3695, 5); threeContents(r); gains(r, Skill.MINING, 14, "M49");
		r.xp(Skill.MINING, 1); r.xp(Skill.COOKING, 1); assertNull(find(r.next(stats(Skill.COOKING)), "M49"));
		r.frame.xp[Skill.MINING.ordinal()] = -1; r.next(); r.frame.xp[Skill.MINING.ordinal()] = 200;
		r.next(stats(Skill.MINING)); r.xp(Skill.MINING, 1); assertNull(find(r.next(stats(Skill.MINING)), "M49"));
	}

	@Test public void firstPaintRevalidationKeepsTheOriginalTwelveTickDeadline()
	{
		ScenarioReplay r = new ScenarioReplay(); full(r); repeat(r, 30, "M43"); r.item(27, -1, 0); r.next();
		SceneCandidate c = find(r.next(), "M43"); assertNotNull(c);
		for (int i = 0; i < 11; i++) { r.next(); } assertTrue(r.rules.stillValid(c));
		r.next(); assertFalse(r.rules.stillValid(c)); assertTrue(r.rules.continuationValid(c));
		r.item(0, 2000, 1); r.next(); assertFalse(r.rules.continuationValid(c));
	}

	private static void kit(ScenarioReplay r) { r.item(0, 954, 1); r.item(1, 1925, 1); r.item(2, 1944, 1); }
	private static void move(ScenarioReplay r, int distance)
	{
		r.frame.player = new Position(r.frame.player.x + distance, r.frame.player.y, r.frame.player.plane);
		r.frame.companion = new Position(r.frame.player.x + 3, r.frame.player.y, r.frame.player.plane);
	}
	private static ScenarioReplay anchor(int item, int slot)
	{
		ScenarioReplay r = new ScenarioReplay(); r.item(slot, item, 1); r.next(); return r;
	}
	private static void threeContents(ScenarioReplay r)
	{
		for (int id = 1000; id < 1003; id++) { r.item(0, id, 1); r.next(); }
	}
	private static void gains(ScenarioReplay r, Skill skill, int count, String scenario)
	{
		for (int i = 0; i < count; i++) { r.xp(skill, 1); assertNull(find(r.next(stats(skill)), scenario)); }
	}
	private static void repeat(ScenarioReplay r, int count, String scenario)
	{
		for (int i = 0; i < count; i++) { assertNull(find(r.next(), scenario)); }
	}
	private static void full(ScenarioReplay r)
	{
		for (int i = 0; i < 28; i++) { r.item(i, 1000 + i, 1); }
	}
	private static ScenarioReplay fullProps(int ticks)
	{
		ScenarioReplay r = new ScenarioReplay(); full(r); r.item(0, 954, 1); r.item(1, 952, 1); repeat(r, ticks, "M44"); return r;
	}
	private static ScenarioReplay arrangementAnchor()
	{
		ScenarioReplay r = new ScenarioReplay(); for (int i = 0; i < 8; i++) { r.item(i, 1000 + i % 4, 1); } r.next(); return r;
	}
	private static void swap(ScenarioReplay r, int a, int b)
	{
		int id = r.frame.inventoryIds[a], quantity = r.frame.inventoryQuantities[a];
		r.item(a, r.frame.inventoryIds[b], r.frame.inventoryQuantities[b]); r.item(b, id, quantity);
	}
	private static void rotate(ScenarioReplay r)
	{
		int id = r.frame.inventoryIds[7], quantity = r.frame.inventoryQuantities[7];
		for (int i = 7; i > 0; i--) { r.item(i, r.frame.inventoryIds[i - 1], r.frame.inventoryQuantities[i - 1]); }
		r.item(0, id, quantity);
	}
}
