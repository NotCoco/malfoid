package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.scenario.ScenarioSnapshot.Target;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.*;
import static org.junit.Assert.*;

public class ScenarioRulesTest
{
	@Test public void silentStartupNeverCreatesProgressOutfitDeathOrArrival()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.player = new Position(3200, 3200, 0);
		r.level(Skill.FISHING, 99);
		for (int i = 0; i < 20; i++) { assertTrue(r.next().isEmpty()); }
		assertFalse(r.rules.hasPendingDeath());
	}

	@Test public void oneHitThresholdUsesRealHpAndNeverSumsSplats()
	{
		ScenarioReplay r = new ScenarioReplay();
		assertNull(find(r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 69, false))), "M01"));
		assertNull(find(r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 35, false), r.hit(true, null, HitsplatID.DAMAGE_OTHER, 35, false))), "M01"));
		assertNotNull(find(r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 70, false))), "M01"));
		assertEquals(7, DamageTypes.largeHitThreshold(10)); assertEquals(1, DamageTypes.largeHitThreshold(1));
		assertEquals(Integer.MAX_VALUE, DamageTypes.largeHitThreshold(0));
	}

	@Test public void nonHpOtherActorAndPoiseCannotBecomeHeavyHit()
	{
		for (int type : new int[]{HitsplatID.HEAL, HitsplatID.POISON, HitsplatID.BURN, HitsplatID.DAMAGE_ME_POISE})
		{
			ScenarioReplay r = new ScenarioReplay(); assertNull(find(r.next(hits(r.hit(true, null, type, 90, true))), "M01"));
		}
		ScenarioReplay r = new ScenarioReplay(); assertNull(find(r.next(hits(r.hit(false, "other", HitsplatID.DAMAGE_OTHER, 90, false))), "M01"));
	}

	@Test public void deathWinsOverHeavyHitsInEitherCallbackOrder()
	{
		for (boolean deathFirst : new boolean[]{false, true})
		{
			ScenarioReplay r = new ScenarioReplay();
			List<Hit> copied = new ArrayList<>(); copied.add(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 90, false));
			ScenarioBatch batch = new ScenarioBatch(true, false, false, copied, Collections.emptyList(), Collections.emptySet());
			if (deathFirst) { r.rules.recordDeath(r.frame.tick + 1, false); }
			assertTrue(r.next(batch).isEmpty()); assertTrue(r.rules.hasPendingDeath());
		}
	}

	@Test public void duplicateDeathSurvivesOrdinaryLoadingAndReturnsOnceAfterTwoAliveCommits()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.dead = true; r.frame.currentHp = 0;
		ScenarioBatch death = new ScenarioBatch(true, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet());
		r.next(death); long token = r.rules.getDeathToken(); r.next(death); assertEquals(token, r.rules.getDeathToken());
		r.rules.resetScene(); r.frame.sceneRevision++; r.frame.valid = false; r.frame.present = false;
		assertTrue(r.next().isEmpty()); assertTrue(r.rules.hasPendingDeath());
		r.frame.valid = true; r.frame.present = true; r.frame.dead = false; r.frame.currentHp = 99;
		assertNull(find(r.next(), "M02")); assertNotNull(find(r.next(), "M02"));
		for (int i = 0; i < 5; i++) { assertNull(find(r.next(), "M02")); }
	}

	@Test public void worldSessionResetAndZeroHpWithoutDeathCannotProduceReunion()
	{
		ScenarioReplay r = new ScenarioReplay(); r.next(new ScenarioBatch(true, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet()));
		r.frame.sessionGeneration++; for (int i = 0; i < 5; i++) { assertNull(find(r.next(), "M02")); }
		ScenarioReplay zero = new ScenarioReplay(); zero.frame.currentHp = 0; zero.next(); zero.frame.currentHp = 99;
		assertNull(find(zero.next(), "M02")); assertNull(find(zero.next(), "M02"));
	}

	@Test public void dismissedDeathIsSilentButAutoAbsentDeathWaitsForAllowedPresence()
	{
		ScenarioReplay dismissed = new ScenarioReplay(); dismissed.frame.present = false; dismissed.frame.userHidden = true;
		dismissed.next(new ScenarioBatch(true, true, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet()));
		dismissed.frame.present = true; dismissed.frame.userHidden = false;
		assertNull(find(dismissed.next(), "M02")); assertNull(find(dismissed.next(), "M02"));
		ScenarioReplay auto = new ScenarioReplay(); auto.frame.present = false; auto.frame.autoAbsent = true;
		auto.next(new ScenarioBatch(true, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet()));
		assertNull(find(auto.next(), "M02")); assertNull(find(auto.next(), "M02")); assertTrue(auto.rules.hasPendingDeath());
		auto.frame.autoAbsent = false; auto.frame.present = true; assertNotNull(find(auto.next(), "M02"));
	}

	@Test public void threeOwnedCallbacksOnOneLiveChickenCountWithinOneTick()
	{
		ScenarioReplay r = chicken();
		assertEquals(1, count(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03"));
		assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03"));
	}

	@Test public void otherBlocksPositiveHitsAndInterruptedEngagementHaveExactSemantics()
	{
		ScenarioReplay r = chicken();
		r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_OTHER, 0, false), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
		r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 1, true)));
		assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03"));
		ScenarioBatch switchAwayAndBack = new ScenarioBatch(false, false, true, Collections.singletonList(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)), Collections.emptyList(), Collections.emptySet());
		assertNull(find(r.next(switchAwayAndBack), "M03"));
	}

	@Test public void chickenWindowIncludesAge29AndExcludesAge30()
	{
		for (int age : new int[]{29, 30})
		{
			ScenarioReplay r = chicken(); r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
			for (int i = 1; i < age; i++) { r.next(); }
			SceneCandidate c = find(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03");
			assertEquals(age == 29, c != null);
		}
	}

	@Test public void selectedChickenCannotPaintAfterDeathOrTransformation()
	{
		ScenarioReplay r = chicken(); SceneCandidate candidate = find(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03");
		assertTrue(r.rules.stillValid(candidate));
		r.frame.target = new Target("A", "Rooster", true, new Position(3101, 3200, 0)); r.next(); assertFalse(r.rules.stillValid(candidate));
	}

	@Test public void levelsCoalesceWith99FirstAndIgnoreBoosts()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.boosted[Skill.FISHING.ordinal()] = 20;
		assertNull(find(r.next(stats(Skill.FISHING)), "M04"));
		r.level(Skill.FISHING, 99); r.xp(Skill.FISHING, 100); r.level(Skill.MINING, 20); r.xp(Skill.MINING, 1000);
		SceneCandidate selected = find(r.next(stats(Skill.MINING, Skill.FISHING)), "M04");
		assertEquals("FISHING", selected.getConditionFacts().get("skill")); assertEquals(90, selected.getPriority());
		assertNull(find(r.next(stats(Skill.MINING, Skill.FISHING)), "M04"));
	}

	@Test public void ordinaryLevelSpeechUsesFirstSkillLevelMultipleTenOr99Only()
	{
		ScenarioReplay r = new ScenarioReplay(); r.level(Skill.FISHING, 11); r.xp(Skill.FISHING, 1);
		assertFalse(find(r.next(stats(Skill.FISHING)), "M04").isIntentionalSilent());
		r.level(Skill.FISHING, 12); r.xp(Skill.FISHING, 1);
		assertTrue(find(r.next(stats(Skill.FISHING)), "M04").isIntentionalSilent());
		r.level(Skill.FISHING, 20); r.xp(Skill.FISHING, 1);
		assertFalse(find(r.next(stats(Skill.FISHING)), "M04").isIntentionalSilent());
	}

	@Test public void absentOrUnknownOccurrenceProgressOnlyUpdatesBaseline()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.present = false; r.level(Skill.FISHING, 20); r.xp(Skill.FISHING, 100);
		assertNull(find(r.next(stats(Skill.FISHING)), "M04")); r.frame.present = true;
		assertNull(find(r.next(stats(Skill.FISHING)), "M04"));
		r.level(Skill.FISHING, 30); r.xp(Skill.FISHING, 100); assertNull(find(r.next(), "M04"));
	}

	@Test public void lootUsesOneStackLongArithmeticImmutablePriceAndSpawnMultisetDedupe()
	{
		ScenarioReplay r = new ScenarioReplay();
		Stack a = new Stack(1, 2, "A", 250_000), b = new Stack(2, 1, "B", 1);
		SceneCandidate first = find(r.next(loot(new Loot(1, "spawn-A", true, Arrays.asList(a, b)))), "M05");
		assertNotNull(first); assertTrue(first.isIntentionalSilent()); assertEquals("500000", first.getConditionFacts().get("reportedValue"));
		r.next(); assertNull(find(r.next(loot(new Loot(2, "spawn-A", true, Arrays.asList(b, a)))), "M05"));
		assertNotNull(find(r.next(loot(new Loot(3, "spawn-A", true, Arrays.asList(a, b)))), "M05"));
		assertNotNull(find(r.next(loot(new Loot(4, "spawn-B", true, Arrays.asList(a, b)))), "M05"));
		assertEquals(-1, new Stack(1, 2, "Overflow", Long.MAX_VALUE).total());
	}

	@Test public void lootDoesNotSumDifferentStacksOrInventPricePossessionOrOwnership()
	{
		ScenarioReplay r = new ScenarioReplay();
		assertNull(find(r.next(loot(new Loot(1, "A", true, Arrays.asList(new Stack(1, 1, "A", 250_000), new Stack(2, 1, "B", 250_000))))), "M05"));
		assertNull(find(r.next(loot(new Loot(2, "B", true, Collections.singletonList(new Stack(1, 99, "A", 0))))), "M05"));
		r.item(0, 1, 99); assertNull(find(r.next(), "M05"));
	}

	@Test public void grindRequires300ObservedCommitsAndDistinctGainTicks()
	{
		ScenarioReplay r = new ScenarioReplay(2); r.frame.presentSinceTick = -100;
		for (int i = 1; i <= 299; i++)
		{
			if (i % 10 == 0) { r.xp(Skill.FISHING, 1); }
			assertNull(find(r.next(stats(Skill.FISHING)), "M06"));
		}
		r.xp(Skill.FISHING, 1); assertNotNull(find(r.next(stats(Skill.FISHING)), "M06"));
		assertNull(find(r.next(), "M06"));
		ScenarioReplay reward = new ScenarioReplay(2); reward.frame.presentSinceTick = -100;
		for (int i = 0; i < 300; i++) { if (i == 299) { reward.xp(Skill.FISHING, 100_000); } assertNull(find(reward.next(stats(Skill.FISHING)), "M06")); }
	}

	@Test public void fullFootprintIncludesMovementOnNoXpTicks()
	{
		for (int span : new int[]{7, 8})
		{
			ScenarioReplay r = new ScenarioReplay(2); r.frame.presentSinceTick = -100;
			SceneCandidate result = null;
			for (int i = 0; i < 300; i++)
			{
				int cycle = i % (2 * span); int offset = cycle <= span ? cycle : 2 * span - cycle;
				r.frame.player = new Position(3100 + offset, 3200, 0); if (i % 10 == 9) { r.xp(Skill.MINING, 1); }
				SceneCandidate c = find(r.next(stats(Skill.MINING)), "M06"); if (c != null) { result = c; }
			}
			assertEquals(span == 7, result != null);
		}
	}

	@Test public void completeFishingInteractionDoesNotPretendToBeCombat()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.target = new Target("fish", "Fishing spot", true, new Position(3101, 3200, 0)); r.next();
		assertEquals(ScenarioRules.CombatState.NO_CONFIRMED_EVIDENCE, r.rules.getCombatState());
	}

	@Test public void paintedParentNearLatchSurvivesIntermediateDistancesAndIsHandledOnce()
	{
		ScenarioReplay r = new ScenarioReplay(); r.rules.firstPaint("utterance", "M04", r.frame.tick, 1, 1);
		for (int step = 1; step <= 2; step++) { r.frame.player = new Position(3100 - step, 3200, 0); assertNull(find(r.next(), "M07")); }
		r.frame.player = new Position(3097, 3200, 0); SceneCandidate complaint = find(r.next(), "M07"); assertNotNull(complaint);
		assertEquals(r.frame.tick - 3 + 20, complaint.getExpiresTick()); r.frame.player = new Position(3096, 3200, 0); assertNull(find(r.next(), "M07"));
	}

	@Test public void movingParentCompanionOnlySeparationAndEmergencySpeechCannotArmM07()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.player = new Position(3099, 3200, 0); r.next();
		r.rules.firstPaint("walking", "M04", r.frame.tick, 1, 1); r.frame.player = new Position(3098, 3200, 0); r.next();
		r.frame.player = new Position(3097, 3200, 0); assertNull(find(r.next(), "M07"));
		ScenarioReplay still = new ScenarioReplay(); still.rules.firstPaint("still", "M04", still.frame.tick, 1, 1);
		still.frame.companion = new Position(3105, 3200, 0); still.next(); still.frame.companion = new Position(3106, 3200, 0); assertNull(find(still.next(), "M07"));
		for (String id : new String[]{"M01", "M02", "M07"})
		{
			ScenarioReplay emergency = new ScenarioReplay(); emergency.rules.firstPaint(id, id, emergency.frame.tick, 1, 1);
			emergency.frame.player = new Position(3098, 3200, 0); emergency.next(); emergency.frame.player = new Position(3097, 3200, 0); assertNull(find(emergency.next(), "M07"));
		}
	}

	@Test public void m07DetectionAgeTwoAndTenPermitElevenRejects()
	{
		for (int age : new int[]{2, 10, 11})
		{
			ScenarioReplay r = new ScenarioReplay(); r.rules.firstPaint("age", "M04", r.frame.tick, 1, 1);
			for (int i = 1; i < age; i++) { if (i == age - 1) { r.frame.player = new Position(3098, 3200, 0); } r.next(); }
			r.frame.player = new Position(3097, 3200, 0); assertEquals(age <= 10, find(r.next(), "M07") != null);
		}
	}

	@Test public void observedMovementRequiresConsecutiveValidSameSceneOneOrTwoTileDisplacement()
	{
		ScenarioReplay r = new ScenarioReplay(); assertFalse(r.rules.isPlayerMoving());
		r.frame.player = new Position(3101, 3200, 0); r.next(); assertTrue(r.rules.isPlayerMoving());
		r.frame.player = new Position(3103, 3200, 0); r.next(); assertTrue(r.rules.isPlayerMoving());
		r.frame.player = new Position(3106, 3200, 0); r.next(); assertFalse(r.rules.isPlayerMoving());
		r.frame.player = new Position(3107, 3200, 0); r.frame.tick++; r.next(); assertFalse(r.rules.isPlayerMoving());
		r.frame.player = new Position(3108, 3200, 0); r.frame.valid = false; r.next(); assertFalse(r.rules.isPlayerMoving());
		r.frame.valid = true; r.frame.player = new Position(3109, 3200, 1); r.next(); assertFalse(r.rules.isPlayerMoving());
		r.frame.player = new Position(3110, 3200, 1); r.frame.sceneRevision++; r.next(); assertFalse(r.rules.isPlayerMoving());
	}

	@Test public void bankGearChangesSettleOnceAfterTwoClosedSnapshotsAgainstPreBankOutfit()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.outfitBlocked = true;
		for (int i = 0; i < 4; i++) { r.frame.equipment[i]++; assertTrue(r.next().isEmpty()); assertTrue(r.next().isEmpty()); }
		r.frame.outfitBlocked = false; assertTrue(r.next().isEmpty()); SceneCandidate c = find(r.next(), "M34");
		assertNotNull(c); assertEquals("100,200,300,400", c.getConditionFacts().get("priorEquipmentIds"));
		assertEquals("101,201,301,401", c.getConditionFacts().get("equipmentIds")); assertTrue(r.next().isEmpty());
	}

	@Test public void reopeningBankRestartsOutfitConfirmationAndUnknownBankCopyCannotBacklog()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.outfitBlocked = true; r.frame.equipment[2] = 301; r.next();
		r.frame.outfitBlocked = false; assertTrue(r.next().isEmpty()); r.frame.outfitBlocked = true; r.frame.equipment[2] = 302; assertTrue(r.next().isEmpty());
		r.frame.outfitBlocked = false; assertTrue(r.next().isEmpty()); SceneCandidate c = find(r.next(), "M09");
		assertNotNull(c); assertEquals("100,200,302,400", c.getConditionFacts().get("equipmentIds"));
		ScenarioReplay unknown = new ScenarioReplay(); unknown.frame.outfitBlocked = true; unknown.frame.equipment = null; unknown.next();
		unknown.frame.equipment = new int[]{101,201,301,401}; unknown.next(); unknown.frame.outfitBlocked = false;
		for (int i = 0; i < 4; i++) { assertTrue(unknown.next().isEmpty()); }
	}

	@Test public void objectInspectionRequiresStable15AndARealAtMostEightEdgeNeighborRoute()
	{
		for (int path : new int[]{8, 9})
		{
			ScenarioReplay r = new ScenarioReplay(); r.frame.objects = Collections.singletonList(new InspectionTarget("tree", "Oak tree", 3106, 3200, 3106, 3200, 0, new Position(3105, 3200, 0), path));
			assertNull(find(r.next(), "M08")); assertNull(find(r.next(), "M08"));
			SceneCandidate c = find(r.next(), "M08"); assertEquals(path == 8, c != null);
			if (c != null)
			{
				r.frame.objects = Collections.singletonList(new InspectionTarget("tree", "Tree stump", 3106, 3200, 3106, 3200, 0, new Position(3105, 3200, 0), path));
				r.next(); assertFalse(r.rules.stillValid(c));
			}
		}
		assertNull(ScenarioRules.objectKind("Oak")); assertEquals("TREE", ScenarioRules.objectKind("Oak tree")); assertEquals("FOUNTAIN", ScenarioRules.objectKind("Fountain"));
	}

	@Test public void outfitTwoCommittedSnapshotsNetRealChangesAndCurrentRevisionAreRequired()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.equipment[2] = 301;
		assertNull(find(r.next(), "M09")); SceneCandidate c = find(r.next(), "M09"); assertNotNull(c); assertTrue(r.rules.stillValid(c));
		r.frame.equipment[2] = 302; r.next(); assertFalse(r.rules.stillValid(c)); assertNotNull(find(r.next(), "M09"));
		ScenarioReplay removal = new ScenarioReplay(); removal.frame.equipment[2] = -1; removal.next(); assertNull(find(removal.next(), "M09"));
		ScenarioReplay weapon = new ScenarioReplay(); weapon.frame.weaponId = 999; weapon.next(); assertNull(find(weapon.next(), "M09"));
	}

	@Test public void combatOutfitSuppressionUpdatesBaselineAndNeverBacklogs()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.equipment[2] = 301; r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 1, false)));
		for (int i = 0; i < 15; i++) { assertNull(find(r.next(), "M09")); }
	}

	@Test public void knownOutsideArrivalNeedsFourActualUninstancedPlaneZeroSnapshots()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.player = new Position(3199, 3200, 0); for (int i = 0; i < 12; i++) { r.next(); }
		r.frame.player = new Position(3200, 3200, 0);
		for (int i = 0; i < 3; i++) { assertNull(find(r.next(), "M10")); }
		SceneCandidate c = find(r.next(), "M10"); assertNotNull(c); assertTrue(r.rules.stillValid(c));
		r.frame.player = new Position(3199, 3200, 0); r.next(); assertFalse(r.rules.stillValid(c));
		for (boolean instance : new boolean[]{false, true})
		{
			ScenarioReplay invalid = new ScenarioReplay(); invalid.frame.player = new Position(3200, 3200, instance ? 0 : 1); invalid.frame.instanced = instance;
			for (int i = 0; i < 6; i++) { assertNull(find(invalid.next(), "M10")); }
		}
	}

	@Test public void showingAgainInsideLumbridgeIsNotAnArrival()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.present = false; r.frame.userHidden = true; r.frame.player = new Position(3200, 3200, 0);
		for (int i = 0; i < 5; i++) { r.next(); } r.frame.present = true; r.frame.userHidden = false;
		for (int i = 0; i < 5; i++) { assertNull(find(r.next(), "M10")); }
	}

	@Test public void sceneBoundCandidatesExpireAtEqualityAndNeverSurviveARebuild()
	{
		ScenarioReplay r = new ScenarioReplay(); SceneCandidate c = find(r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 70, false))), "M01");
		while (r.frame.tick < c.getExpiresTick() - 1) { r.next(); } assertTrue(r.rules.stillValid(c)); r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay other = new ScenarioReplay(); SceneCandidate current = find(other.next(hits(other.hit(true, null, HitsplatID.DAMAGE_OTHER, 70, false))), "M01");
		other.frame.sceneRevision++; other.next(); assertFalse(other.rules.stillValid(current));
	}

	@Test public void scenePlanSilenceAndEmergencyExceptionsFollowAuthoredMotion()
	{
		ScenarioReplay r = new ScenarioReplay(); SceneCandidate ordinary = new SceneCandidate(1, "M09", 25, 12, 24, 1, 1, "outfit", Collections.emptyMap(), false, 6);
		assertFalse(ScenePlan.forCandidate(ordinary, 10, false, true).approach);
		assertTrue(ScenePlan.forCandidate(ordinary, 10, false, false).approach);
		SceneCandidate emergency = new SceneCandidate(2, "M01", 100, 12, 24, 1, 1, "damage", Collections.emptyMap(), true, 20);
		ScenePlan watch = ScenePlan.forCandidate(emergency, 5, true, true); assertTrue(watch.approach); assertTrue(watch.run); assertEquals(20, watch.holdTicks);
	}
	@Test public void witnessedPreGraceLevelKeepsItsOriginalDeadlineAndWaitTick()
	{
		ScenarioReplay r = new ScenarioReplay(2); r.level(Skill.FISHING, 11); r.xp(Skill.FISHING, 1);
		SceneCandidate c = find(r.next(stats(Skill.FISHING)), "M04");
		assertNotNull(c); assertEquals(3, c.getCreatedTick()); assertEquals(13, c.getExpiresTick());
		assertEquals("10", c.getConditionFacts().get("settlingNotBeforeTick"));
		assertNull(find(r.next(stats(Skill.FISHING)), "M04"));
	}
	@Test public void ordinaryLoadingArrivalSurvivesSettlingGraceWithoutExtendingExpiry()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.player = new Position(3199, 3200, 0); r.next();
		r.rules.resetScene(); r.frame.sceneRevision++; r.frame.player = new Position(3200, 3200, 0);
		r.frame.present = false; r.frame.presentSinceTick = r.frame.tick + 2; r.next();
		r.frame.present = true; assertNull(find(r.next(), "M10")); assertNull(find(r.next(), "M10"));
		SceneCandidate c = find(r.next(), "M10"); assertNotNull(c);
		assertEquals(c.getCreatedTick() + 20, c.getExpiresTick());
		assertTrue(Long.parseLong(c.getConditionFacts().get("settlingNotBeforeTick")) > c.getCreatedTick());
	}
	@Test public void midConfirmationSceneInvalidationCancelsArrivalHistory()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.player = new Position(3200, 3200, 0); r.next(); r.next();
		r.frame.sceneRevision++;
		for (int i = 0; i < 8; i++) { assertNull(find(r.next(), "M10")); }
	}
	@Test public void committedHoldIgnoresOnlyDeadlineAndStillRevokesOnEquipmentLoss()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.equipment[2] = 301; r.next(); SceneCandidate c = find(r.next(), "M09");
		while (r.frame.tick < c.getExpiresTick()) { r.next(); }
		assertFalse(r.rules.stillValid(c)); assertTrue(r.rules.continuationValid(c));
		r.frame.equipment[2] = 302; r.next(); assertFalse(r.rules.continuationValid(c));
	}
	@Test public void immediatePresenceLossBreaksChickenHistoryEvenIfShownBeforeNextCommit()
	{
		ScenarioReplay r = chicken(); r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
		r.rules.presenceLost(); assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03"));
	}
	@Test public void latestHeavyHitRequiresAQualifyingWitnessAndSurvivingFrame()
	{
		ScenarioReplay r = new ScenarioReplay(); long initial = r.rules.getLatestHeavyHitTick();
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 69, false))); assertEquals(initial, r.rules.getLatestHeavyHitTick());
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 70, false))); long heavy = r.frame.tick;
		assertEquals(heavy, r.rules.getLatestHeavyHitTick());
		r.frame.dead = true; r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 90, false))); assertEquals(heavy, r.rules.getLatestHeavyHitTick());
		r.rules.resetSession(); assertEquals(initial, r.rules.getLatestHeavyHitTick());
	}
	@Test public void originalParentExpiryRevokesDepartureAtEqualityEvenAfterCompletion()
	{
		ScenarioReplay r = new ScenarioReplay(); long expires = r.frame.tick + 3;
		r.rules.firstPaint("finished-parent", "M04", r.frame.tick, 1, 1, expires);
		r.rules.presentationVisible("finished-parent", false);
		r.frame.player = new Position(3099, 3200, 0); r.next(); r.frame.player = new Position(3098, 3200, 0); r.next();
		r.frame.player = new Position(3097, 3200, 0); assertNull(find(r.next(), "M07"));
	}
	private static ScenarioReplay chicken()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.target = new Target("A", "Chicken", true, new Position(3101, 3200, 0)); return r;
	}
}
