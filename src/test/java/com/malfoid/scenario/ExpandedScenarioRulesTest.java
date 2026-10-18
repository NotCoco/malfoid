package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.scenario.ScenarioSnapshot.Target;
import com.malfoid.speech.SceneCandidate;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.*;
import static org.junit.Assert.*;

public class ExpandedScenarioRulesTest
{
	@Test public void m11RequiresWitnessedInjuryAndTwoCalmRecoverySamples()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.realHp = 60; r.frame.currentHp = 12;
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 20, false)));
		for (int i = 0; i < 7; i++) { assertNull(find(r.next(), "M11")); }
		r.frame.currentHp = 31; assertNull(find(r.next(), "M11")); r.frame.currentHp = 32;
		SceneCandidate recovered = find(r.next(), "M11"); assertNotNull(recovered);
		assertEquals("12", recovered.getConditionFacts().get("lowWater"));
		assertNull(find(r.next(), "M11"));
		ScenarioReplay baseline = new ScenarioReplay(); baseline.frame.realHp = 60; baseline.frame.currentHp = 32;
		assertNull(find(baseline.next(), "M11")); assertNull(find(baseline.next(), "M11"));
	}

	@Test public void m11FreshDamageAndRealHpChangesCancelRecovery()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.realHp = 60; r.frame.currentHp = 12;
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 20, false))); for (int i = 0; i < 5; i++) { r.next(); }
		r.frame.currentHp = 31; r.next(); r.frame.realHp = 61; assertNull(find(r.next(), "M11"));
		ScenarioReplay fresh = new ScenarioReplay(); fresh.frame.realHp = 60; fresh.frame.currentHp = 12;
		fresh.next(hits(fresh.hit(true, null, HitsplatID.DAMAGE_OTHER, 20, false))); for (int i = 0; i < 5; i++) { fresh.next(); }
		fresh.frame.currentHp = 31; fresh.next(); assertNull(find(fresh.next(hits(fresh.hit(true, null, HitsplatID.DAMAGE_OTHER, 1, false))), "M11"));
	}

	@Test public void m12ThreeOneHpSamplesNeedDamageAndWorkBelowRealHpTwenty()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.realHp = 10; r.frame.currentHp = 1;
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 2, false))); assertNull(find(r.next(), "M12"));
		assertNotNull(find(r.next(), "M12")); assertNull(find(r.next(), "M12"));
		ScenarioReplay unknown = new ScenarioReplay(); unknown.frame.currentHp = 1;
		for (int i = 0; i < 3; i++) { assertNull(find(unknown.next(), "M12")); }
	}

	@Test public void m13NeedsThreeEarlierDistinctBlockCommitsAndRevalidatesSpawn()
	{
		ScenarioReplay r = target("Goblin");
		for (int i = 0; i < 3; i++) { r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))); }
		SceneCandidate c = find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true))), "M13"); assertNotNull(c);
		r.frame.target = new Target("B", "Goblin", true, new Position(3101, 3200, 0)); r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay simultaneous = target("Goblin"); simultaneous.next(hits(simultaneous.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), simultaneous.hit(false, "A", HitsplatID.BLOCK_ME, 0, true), simultaneous.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
		assertNull(find(simultaneous.next(hits(simultaneous.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true))), "M13"));
	}

	@Test public void m16RequiresOneIncreasingOwnedNumberPerCommitAndRejectsMultiHitBatch()
	{
		ScenarioReplay r = target("Goblin");
		for (int amount = 1; amount < 4; amount++) { assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, amount, true))), "M16")); }
		assertNotNull(find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true))), "M16"));
		ScenarioReplay multi = target("Goblin"); multi.next(hits(multi.hit(false, "A", HitsplatID.DAMAGE_ME, 1, true)));
		multi.next(hits(multi.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true), multi.hit(false, "A", HitsplatID.DAMAGE_ME, 3, true)));
		multi.next(hits(multi.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true))); assertNull(find(multi.next(hits(multi.hit(false, "A", HitsplatID.DAMAGE_ME, 5, true))), "M16"));
	}

	@Test public void oneOwnedFactCannotStartBothLowerAndHigherSpecializations()
	{
		ScenarioReplay r = target("Goblin");
		for (int i = 0; i < 3; i++) { r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))); }
		r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 1, true)));
		r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)));
		r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 3, true)));
		assertEquals(1, count(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true))), "M16"));
	}

	@Test public void m21SixAlternatingGainsAreConsumedAndConcurrentFieldsReset()
	{
		ScenarioReplay r = new ScenarioReplay();
		for (int i = 0; i < 5; i++) { Skill skill = i % 2 == 0 ? Skill.CONSTRUCTION : Skill.FARMING; r.xp(skill, 1); assertNull(find(r.next(stats(skill)), "M21")); }
		r.xp(Skill.FARMING, 1); assertNotNull(find(r.next(stats(Skill.FARMING)), "M21"));
		r.xp(Skill.CONSTRUCTION, 1); assertNull(find(r.next(stats(Skill.CONSTRUCTION)), "M21"));
		r.xp(Skill.FARMING, 1); r.xp(Skill.CONSTRUCTION, 1); assertNull(find(r.next(stats(Skill.CONSTRUCTION, Skill.FARMING)), "M21"));
	}

	@Test public void m23KnownReal99NeedsTenGainCommitsAndOneThousandXp()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.level(Skill.FLETCHING, 99); for (int i = 0; i < 12; i++) { r.next(); }
		for (int i = 0; i < 9; i++) { r.xp(Skill.FLETCHING, 100); assertNull(find(r.next(stats(Skill.FLETCHING)), "M23")); }
		r.xp(Skill.FLETCHING, 100); assertNotNull(find(r.next(stats(Skill.FLETCHING)), "M23"));
		ScenarioReplay transition = new ScenarioReplay(); transition.level(Skill.FLETCHING, 99); transition.xp(Skill.FLETCHING, 1000); transition.next(stats(Skill.FLETCHING));
		for (int i = 0; i < 9; i++) { transition.xp(Skill.FLETCHING, 100); assertNull(find(transition.next(stats(Skill.FLETCHING)), "M23")); }
	}

	@Test public void m26RequiresTwoSeparateGainsInEachOfThreeFields()
	{
		ScenarioReplay r = new ScenarioReplay(); Skill[] sequence = {Skill.COOKING, Skill.COOKING, Skill.FISHING, Skill.FISHING, Skill.FIREMAKING, Skill.FIREMAKING};
		for (int i = 0; i < sequence.length; i++)
		{
			r.xp(sequence[i], 1); assertEquals(i == 5, find(r.next(stats(sequence[i])), "M26") != null);
		}
		assertNull(find(r.next(), "M26"));
		ScenarioReplay same = new ScenarioReplay(); same.xp(Skill.COOKING, 1); same.xp(Skill.FISHING, 1); same.xp(Skill.FIREMAKING, 1);
		assertNull(find(same.next(stats(Skill.COOKING, Skill.FISHING, Skill.FIREMAKING)), "M26"));
	}

	@Test public void m28ExactMillionEdgeRequiresNoRealLevelGain()
	{
		ScenarioReplay r = fishingMillion(); r.xp(Skill.FISHING, 1); assertNotNull(find(r.next(stats(Skill.FISHING)), "M28"));
		assertNull(find(r.next(stats(Skill.FISHING)), "M28"));
		ScenarioReplay level = fishingMillion(); level.xp(Skill.FISHING, 1); level.level(Skill.FISHING, 76); assertNull(find(level.next(stats(Skill.FISHING)), "M28"));
	}

	@Test public void m29TerminalFarmingDeltaIsComparedToThreePriorDistinctCommits()
	{
		ScenarioReplay r = new ScenarioReplay(); for (int amount : new int[]{10, 20, 30}) { r.xp(Skill.FARMING, amount); r.next(stats(Skill.FARMING)); }
		r.xp(Skill.FARMING, 1000); assertNotNull(find(r.next(stats(Skill.FARMING)), "M29"));
		ScenarioReplay small = new ScenarioReplay(); for (int amount : new int[]{10, 20, 30}) { small.xp(Skill.FARMING, amount); small.next(stats(Skill.FARMING)); }
		small.xp(Skill.FARMING, 999); assertNull(find(small.next(stats(Skill.FARMING)), "M29"));
	}

	@Test public void outfitSpecializationsUseOneBaseRevisionAndExactChangedSlots()
	{
		ScenarioReplay cape = new ScenarioReplay(); for (int i = 0; i < 4; i++) { cape.next(); }
		cape.frame.equipment[1] = 201; cape.next(); SceneCandidate c = find(cape.next(), "M31"); assertNotNull(c); assertEquals("CAPE", c.getConditionFacts().get("changedSlot"));
		ScenarioReplay hat = new ScenarioReplay();
		for (int body = 301; body < 303; body++) { hat.frame.equipment[2] = body; hat.next(); assertNull(find(hat.next(), "M32")); }
		hat.frame.equipment[2] = 303; hat.next(); assertNotNull(find(hat.next(), "M32"));
		ScenarioReplay back = new ScenarioReplay(); back.frame.equipment[2] = 301; back.next(); back.next(); back.frame.equipment[2] = 300; back.next(); assertNotNull(find(back.next(), "M33"));
		ScenarioReplay all = new ScenarioReplay(); all.frame.equipment = new int[]{101, 201, 301, 401}; all.next(); List<SceneCandidate> output = all.next(); assertNotNull(find(output, "M34")); assertNull(find(output, "M09"));
	}

	@Test public void capeSpecializationRequiresOtherWatchedSlotsToKeepTheirIds()
	{
		for (int removed : new int[]{0, 2, 3})
		{
			ScenarioReplay r = new ScenarioReplay(); for (int i = 0; i < 4; i++) { r.next(); }
			r.frame.equipment[1] = 201; r.frame.equipment[removed] = -1; r.next();
			List<SceneCandidate> output = r.next(); assertNull(find(output, "M31")); assertNotNull(find(output, "M09"));
		}
	}

	@Test public void stagedFullOutfitWithinEightTicksReplacesUnpaintedFragments()
	{
		ScenarioReplay r = new ScenarioReplay();
		r.frame.equipment[2] = 301; r.next(); SceneCandidate fragment = find(r.next(), "M09");
		r.frame.equipment[3] = 401; r.next(); r.next(); r.frame.equipment[1] = 201; r.next(); r.next();
		r.frame.equipment[0] = 101; r.next(); SceneCandidate complete = find(r.next(), "M34"); assertNotNull(complete);
		assertFalse(r.rules.stillValid(fragment)); assertTrue(r.rules.stillValid(complete));
	}

	@Test public void disabledSpecializationsFallBackToCoreWithoutLosingItsCause()
	{
		ScenarioReplay r = new ScenarioReplay(); r.rules.setScenarioEnabled(id -> id.matches("M0[1-9]|M10"));
		r.frame.equipment = new int[]{101, 201, 301, 401}; r.next(); assertNotNull(find(r.next(), "M09"));
		r.item(0, 7954, 1); r.next(); r.level(Skill.COOKING, 11); r.xp(Skill.COOKING, 1); assertNotNull(find(r.next(stats(Skill.COOKING)), "M04"));
	}

	@Test public void m35NeedsTenTrueSamplesAfterFiveFalseAndSilentlySeedsInitialTrue()
	{
		ScenarioReplay r = new ScenarioReplay(); r.item(0, 100, 1);
		for (int i = 0; i < 9; i++) { assertNull(find(r.next(), "M35")); } assertNotNull(find(r.next(), "M35"));
		ScenarioReplay initial = new ScenarioReplay(0); initial.item(0, 100, 1); for (int i = 0; i < 30; i++) { assertNull(find(initial.next(), "M35")); }
		initial.item(0, -1, 0); for (int i = 0; i < 5; i++) { initial.next(); } initial.item(0, 100, 1);
		for (int i = 0; i < 9; i++) { assertNull(find(initial.next(), "M35")); } assertNotNull(find(initial.next(), "M35"));
	}

	@Test public void m36ChefHatAndBurntShrimpNeedThreeCookingGainCommits()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.equipment[0] = 1949; r.item(0, 7954, 1); r.next();
		for (int i = 0; i < 2; i++) { r.xp(Skill.COOKING, 1); assertNull(find(r.next(stats(Skill.COOKING)), "M36")); }
		r.xp(Skill.COOKING, 1); SceneCandidate c = find(r.next(stats(Skill.COOKING)), "M36"); assertNotNull(c); assertTrue(r.rules.stillValid(c));
		r.item(0, -1, 0); r.next(); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m37CountsTwentySlotsAndNeverOneStackOfTwenty()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.equipment[0] = 1949; r.item(0, 1965, 20);
		for (int i = 0; i < 12; i++) { assertNull(find(r.next(), "M37")); }
		for (int i = 0; i < 20; i++) { r.item(i, 1965, 1); }
		for (int i = 0; i < 9; i++) { assertNull(find(r.next(), "M37")); } assertNotNull(find(r.next(), "M37"));
	}

	@Test public void m38AndM40OnlyCountGainTicksWithTheirExactItemSets()
	{
		ScenarioReplay fire = new ScenarioReplay(); fire.item(0, 590, 1); fire.item(1, 592, 1); fire.next();
		for (int i = 0; i < 9; i++) { fire.xp(Skill.FIREMAKING, 1); assertNull(find(fire.next(stats(Skill.FIREMAKING)), "M38")); }
		fire.xp(Skill.FIREMAKING, 1); assertNotNull(find(fire.next(stats(Skill.FIREMAKING)), "M38"));
		ScenarioReplay cooking = new ScenarioReplay(); int slot = 0; for (int id : new int[]{1925, 1931, 1935, 1980}) { cooking.item(slot++, id, 1); } cooking.next();
		for (int i = 0; i < 5; i++) { cooking.xp(Skill.COOKING, 1); assertNull(find(cooking.next(stats(Skill.COOKING)), "M40")); }
		cooking.xp(Skill.COOKING, 1); SceneCandidate c = find(cooking.next(stats(Skill.COOKING)), "M40"); assertNotNull(c);
		cooking.item(3, -1, 0); cooking.next(); assertFalse(cooking.rules.stillValid(c));
	}

	@Test public void m42NeedsTenFishingGainCommitsOneFeatherAndAtLeastOneThousandBait()
	{
		ScenarioReplay r = new ScenarioReplay(); r.item(0, 314, 1); r.item(1, 313, 1000); r.next();
		for (int i = 0; i < 9; i++) { r.xp(Skill.FISHING, 1); assertNull(find(r.next(stats(Skill.FISHING)), "M42")); }
		r.xp(Skill.FISHING, 1); SceneCandidate c = find(r.next(stats(Skill.FISHING)), "M42"); assertNotNull(c);
		r.item(0, 314, 2); r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay wrong = new ScenarioReplay(); wrong.item(0, 314, 2); wrong.item(1, 313, 1000);
		for (int i = 0; i < 12; i++) { wrong.xp(Skill.FISHING, 1); assertNull(find(wrong.next(stats(Skill.FISHING)), "M42")); }
	}

	@Test public void m48DecoratesOneCookingLevelBut99RemainsCoreMilestone()
	{
		ScenarioReplay r = new ScenarioReplay(); r.item(0, 7954, 1); r.next(); r.level(Skill.COOKING, 11); r.xp(Skill.COOKING, 1);
		List<SceneCandidate> output = r.next(stats(Skill.COOKING)); SceneCandidate c = find(output, "M48"); assertNotNull(c); assertNull(find(output, "M04"));
		assertEquals("true", c.getConditionFacts().get("witnessedPresent"));
		r.level(Skill.COOKING, 99); r.xp(Skill.COOKING, 100); assertEquals(90, find(r.next(stats(Skill.COOKING)), "M04").getPriority());
	}

	@Test public void m50NeedsStableRubberChickenAtEachDistinctOwnedBlock()
	{
		ScenarioReplay r = target("Chicken"); r.frame.weaponId = 4566; r.next(); r.next();
		Hit first = r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true);
		assertNull(find(r.next(hits(first, first, first)), "M50"));
		r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
		List<SceneCandidate> output = r.next(hits(r.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))); assertNotNull(find(output, "M50")); assertNull(find(output, "M03"));
		ScenarioReplay late = target("Chicken"); late.next(hits(late.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))); late.next(hits(late.hit(false, "A", HitsplatID.BLOCK_ME, 0, true)));
		late.frame.weaponId = 4566; assertNotNull(find(late.next(hits(late.hit(false, "A", HitsplatID.BLOCK_ME, 0, true))), "M03"));
	}

	@Test public void presenceLossAndBankUnknownFactsResetExpansionHistories()
	{
		ScenarioReplay r = new ScenarioReplay(); r.xp(Skill.CONSTRUCTION, 1); r.next(stats(Skill.CONSTRUCTION)); r.xp(Skill.FARMING, 1); r.next(stats(Skill.FARMING));
		r.frame.present = false; r.next(); r.frame.present = true;
		for (int i = 0; i < 4; i++) { Skill s = i % 2 == 0 ? Skill.CONSTRUCTION : Skill.FARMING; r.xp(s, 1); assertNull(find(r.next(stats(s)), "M21")); }
		ScenarioReplay inventory = new ScenarioReplay(); inventory.item(0, 590, 1); inventory.item(1, 592, 1);
		for (int i = 0; i < 9; i++) { inventory.xp(Skill.FIREMAKING, 1); inventory.next(stats(Skill.FIREMAKING)); }
		inventory.frame.inventoryBlocked = true; inventory.next(); inventory.frame.inventoryBlocked = false;
		inventory.xp(Skill.FIREMAKING, 1); assertNull(find(inventory.next(stats(Skill.FIREMAKING)), "M38"));
	}
	private static ScenarioReplay target(String name)
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.target = new Target("A", name, true, new Position(3101, 3200, 0)); return r;
	}
	private static ScenarioReplay fishingMillion()
	{
		ScenarioReplay r = new ScenarioReplay(0); r.frame.xp[Skill.FISHING.ordinal()] = 999_999; r.level(Skill.FISHING, 75);
		for (int i = 0; i < 12; i++) { r.next(); } return r;
	}
}
