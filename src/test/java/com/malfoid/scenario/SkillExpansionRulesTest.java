package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioRules.SkillDelta;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.find;
import static org.junit.Assert.*;

public class SkillExpansionRulesTest
{
	@Test public void m22MatchesOnlyOneRecentDistinctGainAndFreezesTheExactNumber()
	{
		Replay r = equality(); r.add(Skill.SMITHING, 30); r.next(); r.waitTicks(2); r.add(Skill.CRAFTING, 10);
		SceneCandidate c = find(r.next(), "M22"); assertNotNull(c);
		assertEquals("56780", c.getConditionFacts().get("matchedTotal")); assertContract(c, 30, 8, 4, 4);
		assertEquals("progress:" + c.getCreatedTick(), c.getCausalToken()); assertTrue(r.valid(c));
		assertNull(find(r.next(), "M22")); r.add(Skill.CRAFTING, 1); r.next(); assertFalse(r.valid(c));
		r.add(Skill.CRAFTING, -1); r.next(); assertFalse("Broken equality never revives its old opportunity", r.valid(c));
	}

	@Test public void m22RecentGainLookbackIncludesNineButNotTenTicks()
	{
		for (int age : new int[]{9, 10})
		{
			Replay r = equality(); r.add(Skill.SMITHING, 30); r.next(); r.waitTicks(age - 1); r.add(Skill.CRAFTING, 10);
			assertEquals("other gain age " + age, age == 9, find(r.next(), "M22") != null);
		}
	}

	@Test public void m22ExcludesStartupEqualityBulkRewardsAndAnUnwitnessedOtherGain()
	{
		Replay startup = new Replay(); startup.waitTicks(20); assertNull(find(startup.next(), "M22"));
		Replay bulk = equality(); bulk.add(Skill.CRAFTING, 10); bulk.add(Skill.SMITHING, 30);
		assertNull(find(bulk.next(), "M22"));
		Replay onlyOne = equality(); onlyOne.frame.xp[Skill.SMITHING.ordinal()] = 56780;
		onlyOne.next(); onlyOne.waitTicks(10); onlyOne.add(Skill.CRAFTING, 10); assertNull(find(onlyOne.next(), "M22"));
	}

	@Test public void m22RequiresTenKnownUnchangedSelectedLevelSamples()
	{
		Replay r = equality(); r.add(Skill.SMITHING, 30); r.next(); r.frame.levels[Skill.CRAFTING.ordinal()]++;
		r.next(); r.add(Skill.CRAFTING, 10); assertNull(find(r.next(), "M22"));
		Replay missing = equality(); missing.add(Skill.SMITHING, 30); missing.next();
		missing.frame.xp[Skill.SMITHING.ordinal()] = -1; missing.next(); missing.frame.xp[Skill.SMITHING.ordinal()] = 56780;
		missing.next(); missing.add(Skill.CRAFTING, 10); assertNull(find(missing.next(), "M22"));
	}

	@Test public void m24QuietGapBoundariesAreFortyThroughFiftyNine()
	{
		for (int gap : new int[]{39, 40, 59, 60})
		{
			Replay r = new Replay(); burst(r, Skill.FISHING, 6, 4); r.waitTicks(gap); r.add(Skill.FISHING, 1);
			SceneCandidate c = find(r.next(), "M24"); assertEquals("gap " + gap, gap == 40 || gap == 59, c != null);
			if (c != null)
			{
				assertEquals(Integer.toString(gap), c.getConditionFacts().get("quietGapTicks")); assertContract(c, 24, 12, 5, 8);
				r.add(Skill.FISHING, 1); assertNull("The return consumes the old gap", find(r.next(), "M24"));
			}
		}
	}

	@Test public void m24RequiresSixDistinctGainCommitsInsideAnInclusiveThirtyTickWindow()
	{
		Replay five = new Replay(); burst(five, Skill.FISHING, 5, 1); five.waitTicks(40); five.add(Skill.FISHING, 1);
		assertNull(find(five.next(), "M24"));
		for (int lastOffset : new int[]{29, 30})
		{
			Replay r = new Replay(); burst(r, Skill.FISHING, 5, 1); r.waitTicks(lastOffset - 5); r.add(Skill.FISHING, 1);
			r.next(); r.waitTicks(40); r.add(Skill.FISHING, 1);
			assertEquals(lastOffset == 29, find(r.next(), "M24") != null);
		}
		Replay oneBatch = new Replay(); oneBatch.add(Skill.FISHING, 20); oneBatch.next(ScenarioReplay.stats(Skill.FISHING));
		oneBatch.waitTicks(40); oneBatch.add(Skill.FISHING, 1); assertNull(find(oneBatch.next(), "M24"));
	}

	@Test public void m24CountsPositionsWithoutXpAndUsesTheEntireFrozenFootprint()
	{
		for (int span : new int[]{7, 8})
		{
			Replay r = new Replay(); burst(r, Skill.FISHING, 6, 1);
			for (int i = 1; i <= span; i++) { r.position(i, 0); r.next(); }
			for (int i = span - 1; i >= 0; i--) { r.position(i, 0); r.next(); }
			r.waitTicks(40 - 2 * span); r.add(Skill.FISHING, 1);
			assertEquals("no-XP excursion span " + span, span == 7, find(r.next(), "M24") != null);
		}
	}

	@Test public void m24SelectsNewestPreGapGainThenSkillOrdinalAndConsumesOtherReturns()
	{
		for (boolean newerFishing : new boolean[]{false, true})
		{
			Replay r = new Replay();
			for (int i = 0; i < 6; i++) { r.add(Skill.FISHING, 1); r.add(Skill.MINING, 1); r.next(); }
			if (newerFishing) { r.add(Skill.FISHING, 1); r.next(); }
			r.waitTicks(40); r.add(Skill.FISHING, 1); r.add(Skill.MINING, 1);
			List<SceneCandidate> output = r.next(); SceneCandidate c = find(output, "M24"); assertNotNull(c);
			assertEquals("FISHING", c.getConditionFacts().get("skill")); assertEquals(1, ScenarioReplay.count(output, "M24"));
			r.add(Skill.MINING, 1); assertNull(find(r.next(), "M24"));
		}
	}

	@Test public void m24EpisodeIncludesOneHundredSamplesButNotOneHundredAndOne()
	{
		for (int lastGainOffset : new int[]{58, 59})
		{
			Replay r = new Replay();
			for (int i = 0; i <= lastGainOffset; i++) { r.add(Skill.FISHING, 1); r.next(); }
			r.waitTicks(40); r.add(Skill.FISHING, 1);
			assertEquals(lastGainOffset == 58, find(r.next(), "M24") != null);
		}
	}

	@Test public void m24UnknownDataAndCombatCannotBecomeFortyQuietTicks()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			Replay r = new Replay(); burst(r, Skill.FISHING, 6, 1); r.waitTicks(20);
			if (interruption == 0) { r.frame.xp[Skill.FISHING.ordinal()] = -1; }
			if (interruption == 1) { r.override = CombatState.UNKNOWN; }
			if (interruption == 2) { r.override = CombatState.CONFIRMED; }
			if (interruption == 3) { r.frame.tick++; }
			r.next(); r.override = null; r.frame.xp[Skill.FISHING.ordinal()] = 106;
			r.waitTicks(19); r.add(Skill.FISHING, 1); assertNull(find(r.next(), "M24"));
		}
	}

	@Test public void m25ExactFortySampleTrackUsesBothDistanceAndEitherAxisSpan()
	{
		for (boolean yAxis : new boolean[]{false, true})
		{
			Replay r = new Replay(); r.rules.reset(); SceneCandidate c = movingTrack(r, 40, 8, 0, 8, yAxis);
			assertNotNull(c); assertContract(c, 22, 12, 5, 8);
			assertEquals("16", c.getConditionFacts().get("observedDistance")); assertEquals("8", c.getConditionFacts().get("gainTicks"));
			assertEquals("8", c.getConditionFacts().get(yAxis ? "ySpan" : "xSpan"));
			r.position(1, 0); r.next(); assertTrue("Ordinary later movement does not rewrite its observed track", r.valid(c));
		}
	}

	@Test public void m25RejectsThirtyNineSamplesSevenGainsAndDistanceOrSpanBelowThreshold()
	{
		Replay shortTrack = new Replay(); shortTrack.rules.reset(); assertNull(movingTrack(shortTrack, 39, 8, 0, 8, false));
		Replay seven = new Replay(); seven.rules.reset(); assertNull(movingTrack(seven, 40, 8, 0, 7, false));
		Replay sum15 = new Replay(); sum15.rules.reset(); assertNull(movingTrack(sum15, 40, 8, 1, 8, true));
		Replay span7 = new Replay(); span7.rules.reset(); assertNull(movingTrack(span7, 40, 7, 0, 8, false));
		Replay sum8 = new Replay(); sum8.rules.reset(); assertNull(movingTrack(sum8, 40, 8, 8, 8, true));
	}

	@Test public void m25RequiresSixDistinctGainTilesAndATerminalGain()
	{
		Replay r = new Replay(); r.rules.reset();
		for (int i = 0; i < 40; i++)
		{
			int offset = i < 8 ? 0 : i <= 16 ? i - 8 : i <= 24 ? 24 - i : 0;
			r.position(offset, 0); if (i < 7 || i == 39) { r.add(Skill.FIREMAKING, 1); }
			assertNull(find(r.next(), "M25"));
		}
		Replay noTerminal = new Replay(); noTerminal.rules.reset();
		for (int i = 0; i < 40; i++)
		{
			noTerminal.position(i <= 8 ? i : i <= 16 ? 16 - i : 0, 0);
			if (i < 8) { noTerminal.add(Skill.FIREMAKING, 1); } assertNull(find(noTerminal.next(), "M25"));
		}
	}

	@Test public void m25SixGainTilesAreEnoughButFiveAreNotAndSpanSevenFailsDespiteEnoughDistance()
	{
		for (int distinct : new int[]{5, 6})
		{
			Replay r = new Replay(); r.rules.reset();
			for (int i = 0; i < 40; i++)
			{
				r.position(i <= 8 ? i : i <= 16 ? 16 - i : 0, 0);
				if (i < distinct || i >= 20 && i < 27 - distinct || i == 39) { r.add(Skill.FIREMAKING, 1); }
				SceneCandidate c = find(r.next(), "M25");
				if (i == 39) { assertEquals(distinct == 6, c != null); } else { assertNull(c); }
			}
		}
		Replay span7 = new Replay(); span7.rules.reset();
		for (int i = 0; i < 40; i++)
		{
			int offset = i <= 7 ? i : i <= 14 ? 14 - i : i <= 16 ? i - 14 : i <= 18 ? 18 - i : 0;
			span7.position(offset, 0); if (i < 7 || i == 39) { span7.add(Skill.FIREMAKING, 1); }
			assertNull("18 steps cannot rescue coordinate span 7", find(span7.next(), "M25"));
		}
	}

	@Test public void m25MissingNoXpSamplesOrLargeJumpsResetRatherThanShrinkTheTrack()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			Replay r = new Replay(); r.rules.reset();
			for (int i = 0; i < 40; i++)
			{
				r.position(i <= 8 ? i : i <= 16 ? 16 - i : 0, 0);
				if (i < 7 || i == 39) { r.add(Skill.FIREMAKING, 1); }
				if (i == 20)
				{
					if (interruption == 0) { r.frame.player = null; }
					if (interruption == 1) { r.position(3, 0); }
					if (interruption == 2) { r.frame.xp[Skill.FIREMAKING.ordinal()] = -1; }
					if (interruption == 3) { r.frame.levels[Skill.FIREMAKING.ordinal()]++; }
				}
				assertNull(find(r.next(), "M25"));
				if (i == 20 && interruption == 2) { r.frame.xp[Skill.FIREMAKING.ordinal()] = 107; }
			}
		}
	}

	@Test public void m25ConsumesItsWindowAndRequiresFortyNewSamples()
	{
		Replay r = new Replay(); r.rules.reset(); SceneCandidate first = movingTrack(r, 40, 8, 0, 8, false); assertNotNull(first);
		SceneCandidate next = movingTrack(r, 40, 8, 0, 8, false); assertNotNull(next);
		assertEquals(40, next.getCreatedTick() - first.getCreatedTick()); assertNotEquals(first.getCausalToken(), next.getCausalToken());
	}

	@Test public void m27TenCommittedDrainedSamplesRestoreOnceWithUnchangedXp()
	{
		Replay r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 8; r.waitTicks(10);
		r.frame.boosted[Skill.MINING.ordinal()] = 10; SceneCandidate c = find(r.next(), "M27"); assertNotNull(c);
		assertContract(c, 23, 8, 4, 4); assertEquals("10", c.getConditionFacts().get("drainedTicks")); assertTrue(r.valid(c));
		assertNull(find(r.next(), "M27")); r.frame.boosted[Skill.MINING.ordinal()] = 9; r.next(); assertFalse(r.valid(c));
		r.frame.boosted[Skill.MINING.ordinal()] = 10; r.next(); assertFalse(r.valid(c));
	}

	@Test public void m27StartupDrainAboveBaseBoostAndNineSamplesDoNotQualify()
	{
		Replay startup = new Replay(0); startup.frame.boosted[Skill.MINING.ordinal()] = 8; startup.waitTicks(12);
		startup.frame.boosted[Skill.MINING.ordinal()] = 10; assertNull(find(startup.next(), "M27"));
		Replay boost = new Replay(); boost.frame.boosted[Skill.MINING.ordinal()] = 12; boost.waitTicks(10);
		boost.frame.boosted[Skill.MINING.ordinal()] = 10; assertNull(find(boost.next(), "M27"));
		Replay nine = new Replay(); nine.frame.boosted[Skill.MINING.ordinal()] = 8; nine.waitTicks(9);
		nine.frame.boosted[Skill.MINING.ordinal()] = 10; assertNull(find(nine.next(), "M27"));
	}

	@Test public void m27ARealKnownZeroCurrentLevelIsDrainedRatherThanUnknown()
	{
		Replay r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 0; r.waitTicks(10);
		r.frame.boosted[Skill.MINING.ordinal()] = 10; assertNotNull(find(r.next(), "M27"));
	}

	@Test public void m27RestorationAtDrainStartPlusTwentyNineIsAllowedButThirtyIsNot()
	{
		for (int samples : new int[]{29, 30})
		{
			Replay r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 8; r.waitTicks(samples);
			r.frame.boosted[Skill.MINING.ordinal()] = 10; assertEquals(samples == 29, find(r.next(), "M27") != null);
		}
	}

	@Test public void m27XpRealLevelUnknownCurrentAndOvershootCancelTheAttempt()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			Replay r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 8; r.waitTicks(10);
			if (interruption == 0) { r.add(Skill.MINING, 1); }
			if (interruption == 1) { r.frame.levels[Skill.MINING.ordinal()]++; }
			if (interruption == 2) { r.frame.boosted[Skill.MINING.ordinal()] = -1; }
			if (interruption == 3) { r.frame.boosted[Skill.MINING.ordinal()] = 11; }
			r.next(); r.frame.boosted[Skill.MINING.ordinal()] = r.frame.levels[Skill.MINING.ordinal()]; assertNull(find(r.next(), "M27"));
		}
	}

	@Test public void m30AllowsEarlierIncomingDamageButRequiresTenFullCalmTicks()
	{
		Replay r = agility(); r.frame.currentHp = 52; r.add(Skill.AGILITY, 1);
		Hit higher = new Hit(42, true, null, HitsplatID.DAMAGE_OTHER, 3, false, true);
		Hit lower = new Hit(41, true, null, HitsplatID.DAMAGE_OTHER, 5, false, true);
		r.next(ScenarioReplay.hits(higher, lower)); long h = r.current.tick;
		for (int age = 1; age <= 10; age++)
		{
			if (age == 5 || age == 10) { r.add(Skill.AGILITY, 1); }
			SceneCandidate c = find(r.next(), "M30"); assertEquals(age == 10, c != null);
			if (c != null)
			{
				assertContract(c, 50, 10, 6, 6); assertEquals("hit:41", c.getCausalToken());
				assertEquals(Long.toString(h), c.getConditionFacts().get("dropTick")); assertEquals("8", c.getConditionFacts().get("hpDrop"));
				assertEquals("60", c.getConditionFacts().get("priorHp")); assertEquals("52", c.getConditionFacts().get("observedHp"));
				assertFalse(c.getConditionFacts().containsKey("damageSource")); assertTrue(r.valid(c));
			}
		}
		r.add(Skill.AGILITY, 1); assertNull(find(r.next(), "M30"));
	}

	@Test public void m30DropAgeIncludesTenAndNineteenButNotNineOrTwenty()
	{
		for (int age : new int[]{9, 10, 19, 20})
		{
			Replay r = agility(); SceneCandidate c = agilityEpisode(r, age, 8);
			assertEquals("drop age " + age, age == 10 || age == 19, c != null);
		}
	}

	@Test public void m30NetDropIncludesFiveAndThirtyPercentAndRejectsValuesOutside()
	{
		for (int amount : new int[]{4, 5, 18, 19})
		{
			assertEquals("HP decrease " + amount, amount == 5 || amount == 18, agilityEpisode(agility(), 10, amount) != null);
		}
	}

	@Test public void m30FirstPaintNeedsAtLeastCeilingOfHalfTheUnchangedRealHp()
	{
		for (int hp : new int[]{30, 31})
		{
			Replay r = new Replay(0); r.frame.realHp = 61; r.frame.currentHp = 61; r.waitTicks(12);
			r.frame.currentHp = 53; r.add(Skill.AGILITY, 1); r.next();
			for (int age = 1; age <= 10; age++)
			{
				if (age == 8) { r.frame.currentHp = hp; }
				if (age == 5 || age == 10) { r.add(Skill.AGILITY, 1); }
				SceneCandidate c = find(r.next(), "M30");
				if (age == 10) { assertEquals(hp == 31, c != null); }
				if (c != null) { r.frame.currentHp = 30; r.next(); assertFalse(r.valid(c)); }
			}
		}
	}

	@Test public void m30RejectsOwnedNpcDamageAndBlocksAcrossTheWholeSelectedWindow()
	{
		for (int type : new int[]{HitsplatID.DAMAGE_ME, HitsplatID.BLOCK_ME})
		{
			Replay r = agility(); r.next(ScenarioReplay.hits(new Hit(5, false, "npc", type, type == HitsplatID.BLOCK_ME ? 0 : 1, true, true)));
			assertNull(agilityEpisode(r, 10, 8));
		}
	}

	@Test public void m30RequiresSameCommitAgilityGainAndThreeSeparateCommittedGains()
	{
		Replay noSameCommit = agility(); noSameCommit.frame.currentHp = 52; noSameCommit.next();
		for (int age = 1; age <= 15; age++)
		{
			if (age % 5 == 0) { noSameCommit.add(Skill.AGILITY, 1); } assertNull(find(noSameCommit.next(), "M30"));
		}
		Replay two = agility(); two.frame.currentHp = 52; two.add(Skill.AGILITY, 20); two.next(ScenarioReplay.stats(Skill.AGILITY));
		two.waitTicks(9); two.add(Skill.AGILITY, 1); assertNull(find(two.next(), "M30"));
	}

	@Test public void m30MissingHpRealHpChangeAndUnknownCombatResetTheHistory()
	{
		for (int interruption = 0; interruption < 3; interruption++)
		{
			Replay r = agility(); r.frame.currentHp = 52; r.add(Skill.AGILITY, 1); r.next();
			for (int age = 1; age <= 10; age++)
			{
				if (age == 5 || age == 10) { r.add(Skill.AGILITY, 1); }
				if (age == 7)
				{
					if (interruption == 0) { r.frame.currentHp = -1; }
					if (interruption == 1) { r.frame.realHp = 61; }
					if (interruption == 2) { r.override = CombatState.UNKNOWN; }
				}
				assertNull(find(r.next(), "M30")); r.frame.currentHp = 52; r.override = null;
			}
		}
	}

	@Test public void m30OverlappingDropsAreConservativelySilentWithinTheAuthoredStorageBound()
	{
		Replay r = agility(); r.frame.currentHp = 52; r.add(Skill.AGILITY, 1); r.next();
		r.frame.currentHp = 44; r.add(Skill.AGILITY, 1); r.next();
		for (int age = 1; age <= 19; age++)
		{
			if (age == 5 || age >= 9) { r.add(Skill.AGILITY, 1); } assertNull(find(r.next(), "M30"));
		}
		r.next(); r.frame.currentHp = 60; r.next(); assertNotNull(agilityEpisode(r, 10, 8));
	}

	@Test public void qualifyingOtherSkillLevelOrEmergencySceneSuppressesAllFiveSkillVariants()
	{
		for (boolean emergency : new boolean[]{false, true})
		{
			Replay equal = equality(); equal.add(Skill.SMITHING, 30); equal.next(); equal.add(Skill.CRAFTING, 10); blockTerminal(equal, emergency);
			assertNull(find(equal.next(), "M22"));
			Replay returning = new Replay(); burst(returning, Skill.FISHING, 6, 1); returning.waitTicks(40);
			returning.add(Skill.FISHING, 1); blockTerminal(returning, emergency); assertNull(find(returning.next(), "M24"));
			Replay moving = new Replay(); moving.rules.reset();
			for (int i = 0; i < 40; i++)
			{
				moving.position(i <= 8 ? i : i <= 16 ? 16 - i : 0, 0);
				if (i < 7 || i == 39) { moving.add(Skill.FIREMAKING, 1); } if (i == 39) { blockTerminal(moving, emergency); }
				assertNull(find(moving.next(), "M25"));
			}
			Replay mining = new Replay(); mining.frame.boosted[Skill.MINING.ordinal()] = 8; mining.waitTicks(10);
			mining.frame.boosted[Skill.MINING.ordinal()] = 10; blockTerminal(mining, emergency); assertNull(find(mining.next(), "M27"));
			Replay agility = agility(); agility.frame.currentHp = 52; agility.add(Skill.AGILITY, 1); agility.next();
			agility.waitTicks(4); agility.add(Skill.AGILITY, 1); agility.next(); agility.waitTicks(4);
			agility.add(Skill.AGILITY, 1); blockTerminal(agility, emergency); assertNull(find(agility.next(), "M30"));
		}
	}

	@Test public void presenceScenePlaneSessionAndSkippedCommitResetWitnesses()
	{
		for (int interruption = 0; interruption < 5; interruption++)
		{
			Replay r = equality(); r.add(Skill.SMITHING, 30); r.next();
			if (interruption == 0) { r.frame.present = false; }
			if (interruption == 1) { r.frame.sceneRevision++; }
			if (interruption == 2) { r.frame.sessionGeneration++; }
			if (interruption == 3) { r.frame.player = new Position(3100, 3200, 1); }
			if (interruption == 4) { r.frame.tick++; }
			r.next(); r.frame.present = true;
			if (interruption == 0) { r.frame.presentSinceTick = r.frame.tick + 1; }
			r.frame.player = new Position(3100, 3200, 0); r.next(); r.add(Skill.CRAFTING, 10); assertNull(find(r.next(), "M22"));
		}
	}

	@Test public void replacingAWorldViewCannotReuseItsGainHistoryOrPendingOpportunity()
	{
		Replay history = equality(); history.frame.worldViewId = 0; history.waitTicks(11); history.add(Skill.SMITHING, 30); history.next();
		history.frame.worldViewId = 1; history.add(Skill.CRAFTING, 10); assertNull(find(history.next(), "M22"));
		Replay pending = equality(); pending.frame.worldViewId = 0; pending.waitTicks(11); pending.add(Skill.SMITHING, 30); pending.next();
		pending.add(Skill.CRAFTING, 10); SceneCandidate c = find(pending.next(), "M22"); assertNotNull(c);
		pending.frame.worldViewId = 1; pending.next(); assertFalse(pending.valid(c));
		pending.frame.worldViewId = 0; pending.next(); assertFalse(pending.valid(c));
	}

	@Test public void duplicateCommitCannotCountTwiceOrIssueAnotherCandidate()
	{
		Replay r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 8;
		for (int i = 0; i < 9; i++) { r.next(); assertTrue(r.duplicate().isEmpty()); }
		r.frame.boosted[Skill.MINING.ordinal()] = 10; assertNull(find(r.next(), "M27"));
		Replay equal = equality(); equal.add(Skill.SMITHING, 30); equal.next(); equal.add(Skill.CRAFTING, 10);
		assertNotNull(find(equal.next(), "M22")); assertTrue(equal.duplicate().isEmpty());
	}

	@Test public void everyFirstPaintOpportunityExpiresAtEqualityAndNeverRebinds()
	{
		for (String id : new String[]{"M22", "M24", "M25", "M27", "M30"})
		{
			Replay r; SceneCandidate c;
			if (id.equals("M22")) { r = equality(); r.add(Skill.SMITHING, 30); r.next(); r.add(Skill.CRAFTING, 10); c = find(r.next(), id); }
			else if (id.equals("M24")) { r = new Replay(); burst(r, Skill.FISHING, 6, 1); r.waitTicks(40); r.add(Skill.FISHING, 1); c = find(r.next(), id); }
			else if (id.equals("M25")) { r = new Replay(); r.rules.reset(); c = movingTrack(r, 40, 8, 0, 8, false); }
			else if (id.equals("M27")) { r = new Replay(); r.frame.boosted[Skill.MINING.ordinal()] = 8; r.waitTicks(10); r.frame.boosted[Skill.MINING.ordinal()] = 10; c = find(r.next(), id); }
			else { r = agility(); c = agilityEpisode(r, 10, 8); }
			assertNotNull(id, c); assertTrue(r.valid(c));
			while (r.current.tick < c.getExpiresTick() - 1) { r.next(); assertTrue(id, r.valid(c)); }
			r.next(); assertFalse(id, r.valid(c));
			assertTrue("The deadline alone does not revoke committed continuation " + id,
				r.rules.continuationValid(c, r.current, r.combat, r.lastDamage));
			r.frame.emergencySceneActive = true; r.next();
			assertFalse("Emergency preemption revokes continuation " + id, r.rules.continuationValid(c, r.current, r.combat, r.lastDamage));
			r.frame.emergencySceneActive = false; r.next();
			assertFalse("Ended preemption cannot revive the old opportunity " + id, r.rules.continuationValid(c, r.current, r.combat, r.lastDamage));
			r.rules.reset(); assertFalse(id, r.valid(c));
		}
	}

	@Test public void m24TransientFootprintDepartureCannotReviveAPendingReturn()
	{
		Replay r = new Replay(); burst(r, Skill.FISHING, 6, 1); r.waitTicks(40); r.add(Skill.FISHING, 1);
		SceneCandidate c = find(r.next(), "M24"); assertNotNull(c);
		for (int i = 1; i <= 4; i++) { r.position(2 * i, 0); r.next(); }
		assertFalse(r.valid(c)); for (int i = 3; i >= 0; i--) { r.position(2 * i, 0); r.next(); }
		assertFalse(r.valid(c));
	}

	@Test public void reducerIntegratesTheHelperAndRechecksFrozenEquality()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.xp[Skill.CRAFTING.ordinal()] = 56770; r.frame.xp[Skill.SMITHING.ordinal()] = 56750;
		for (int i = 0; i < 15; i++) { r.next(); }
		r.xp(Skill.SMITHING, 30); r.next(ScenarioReplay.stats(Skill.SMITHING)); r.xp(Skill.CRAFTING, 10);
		SceneCandidate c = find(r.next(ScenarioReplay.stats(Skill.CRAFTING)), "M22"); assertNotNull(c); assertTrue(r.rules.stillValid(c));
		r.xp(Skill.CRAFTING, 1); r.next(ScenarioReplay.stats(Skill.CRAFTING)); assertFalse(r.rules.stillValid(c));
	}

	private static Replay equality()
	{
		Replay r = new Replay(0); r.frame.xp[Skill.CRAFTING.ordinal()] = 56770; r.frame.xp[Skill.SMITHING.ordinal()] = 56750;
		r.waitTicks(12); return r;
	}
	private static Replay agility()
	{
		Replay r = new Replay(0); r.frame.realHp = 60; r.frame.currentHp = 60; r.waitTicks(12); return r;
	}
	private static void levelOther(Replay r) { r.frame.levels[Skill.COOKING.ordinal()]++; r.add(Skill.COOKING, 1); }
	private static void blockTerminal(Replay r, boolean emergency)
	{
		if (emergency) { r.frame.emergencySceneActive = true; } else { levelOther(r); }
	}
	private static void burst(Replay r, Skill skill, int count, int interval)
	{
		for (int i = 0; i < count; i++) { r.add(skill, 1); r.next(); if (i < count - 1) { r.waitTicks(interval - 1); } }
	}
	private static SceneCandidate movingTrack(Replay r, int samples, int span, int end, int gains, boolean yAxis)
	{
		SceneCandidate candidate = null;
		for (int i = 0; i < samples; i++)
		{
			int offset = i <= span ? i : Math.max(end, 2 * span - i);
			r.position(yAxis ? 0 : offset, yAxis ? offset : 0);
			if (i < gains - 1 || i == samples - 1) { r.add(Skill.FIREMAKING, 1); }
			SceneCandidate next = find(r.next(), "M25");
			if (i < 39) { assertNull("No forty-sample window yet", next); } else { candidate = next; }
		}
		return candidate;
	}
	private static SceneCandidate agilityEpisode(Replay r, int terminalAge, int decrease)
	{
		r.frame.currentHp -= decrease; r.add(Skill.AGILITY, 1); r.next(); SceneCandidate terminal = null;
		for (int age = 1; age <= terminalAge; age++)
		{
			if (age == 5 || age == terminalAge) { r.add(Skill.AGILITY, 1); }
			SceneCandidate c = find(r.next(), "M30"); if (age < terminalAge) { assertNull(c); } else { terminal = c; }
		}
		return terminal;
	}
	private static void assertContract(SceneCandidate c, int priority, int life, int hold, int approach)
	{
		assertEquals(priority, c.getPriority()); assertEquals(life, c.getExpiresTick() - c.getCreatedTick()); assertEquals(hold, c.getHoldTicks());
		assertEquals(Integer.toString(approach), c.getConditionFacts().get("maximumApproachTicks"));
	}

	/** Same immutable boundary as production, with an explicit combat copy and final per-commit stats. */
	private static final class Replay
	{
		final SkillExpansionRules rules = new SkillExpansionRules();
		final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		ScenarioSnapshot current, prior; CombatState override, combat = CombatState.NO_CONFIRMED_EVIDENCE;
		long lastDamage = Long.MIN_VALUE / 2, lastCombat = Long.MIN_VALUE / 2, candidateId;
		Replay() { this(12); }
		Replay(int baselines)
		{
			frame.xp = new int[Skill.values().length]; Arrays.fill(frame.xp, 100);
			frame.levels = new int[Skill.values().length]; Arrays.fill(frame.levels, 10); frame.boosted = frame.levels.clone();
			frame.player = new Position(3100, 3200, 0); frame.companion = new Position(3103, 3200, 0); waitTicks(baselines);
		}
		void add(Skill skill, int amount) { frame.xp[skill.ordinal()] += amount; }
		void position(int x, int y) { frame.player = new Position(3100 + x, 3200 + y, 0); }
		void waitTicks(int ticks) { for (int i = 0; i < ticks; i++) { next(); } }
		List<SceneCandidate> next() { return next(ScenarioBatch.empty()); }
		List<SceneCandidate> next(ScenarioBatch source)
		{
			frame.tick++; prior = current; current = frame.build(); List<SkillDelta> deltas = deltas();
			EnumSet<Skill> witnessed = EnumSet.noneOf(Skill.class); witnessed.addAll(source.witnessedSkills);
			for (SkillDelta delta : deltas) { if (delta.delta > 0 && current.present) { witnessed.add(delta.skill); } }
			for (Hit hit : source.hits)
			{
				if (hit.local && DamageTypes.hpDamage(hit.type) && hit.amount > 0) { lastDamage = current.tick; }
				if ((hit.local || hit.mine) && DamageTypes.combat(hit.type)) { lastCombat = current.tick; }
			}
			combat = override != null ? override : current.tick - lastCombat < 10 ? CombatState.CONFIRMED : CombatState.NO_CONFIRMED_EVIDENCE;
			ScenarioBatch batch = new ScenarioBatch(source.localDeath, source.deathWhileUserHidden, source.engagementInterrupted,
				source.hits, source.loot, witnessed);
			return rules.close(current, prior, batch, deltas, combat, lastDamage, this::candidate);
		}
		List<SceneCandidate> duplicate()
		{
			return rules.close(current, prior, ScenarioBatch.empty(), deltas(), combat, lastDamage, this::candidate);
		}
		boolean valid(SceneCandidate c) { return rules.stillValid(c, current, combat, lastDamage); }
		private List<SkillDelta> deltas()
		{
			List<SkillDelta> result = new ArrayList<>(); if (prior == null) { return result; }
			for (Skill skill : Skill.values())
			{
				if (prior.xp(skill) >= 0 && prior.level(skill) > 0 && current.xp(skill) >= 0 && current.level(skill) > 0)
				{
					result.add(new SkillDelta(skill, prior.xp(skill), current.xp(skill), prior.level(skill), current.level(skill)));
				}
			}
			return result;
		}
		private SceneCandidate candidate(ScenarioSnapshot f, String id, int priority, int life, String cause,
			java.util.Map<String, String> facts, boolean silent, int hold)
		{
			return new SceneCandidate(++candidateId, id, priority, f.tick, f.tick + life, f.sessionGeneration, f.sceneRevision, cause, facts, silent, hold);
		}
	}
}
