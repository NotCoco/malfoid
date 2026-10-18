package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.NpcDeath;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.scenario.ScenarioSnapshot.Target;
import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import net.runelite.api.HitsplatID;
import net.runelite.api.Skill;
import org.junit.Test;
import static com.malfoid.scenario.ScenarioReplay.*;
import static org.junit.Assert.*;

/** Source-shaped replays use the production reducer, including priority/cause coalescing. */
public class CombatExpansionRulesTest
{
	@Test public void m14DirectDamageBackedReturnUsesLiveSpawnIdentitiesAndOneTerminalFact()
	{
		ScenarioReplay r = combat(); own(r, "A", 2); r.next();
		change(r, "B", r.hit(false, "B", HitsplatID.DAMAGE_ME, 3, true)); r.next();
		Hit returned = r.hit(false, "A", HitsplatID.DAMAGE_ME, 4, true);
		SceneCandidate c = find(change(r, "A", returned, returned), "M14"); assertNotNull(c);
		assertEquals("A,B,A", c.getConditionFacts().get("engagementSequence"));
		assertEquals("owned:" + returned.id, c.getCausalToken()); assertTrue(r.rules.stillValid(c));
		assertNull(find(r.next(hits(returned)), "M14"));
		change(r, "B"); assertFalse(r.rules.stillValid(c)); change(r, "A"); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m14RejectsNullIntermediateThirdSpawnAndNoncombatMiddleSegment()
	{
		ScenarioReplay nul = combat(); own(nul, "A", 2); change(nul, null); own(nul, "B", 2);
		assertNull(find(change(nul, "A", nul.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
		ScenarioReplay third = combat(); own(third, "A", 2); change(third, "B", third.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true));
		change(third, "C", third.hit(false, "C", HitsplatID.DAMAGE_ME, 2, true));
		assertNull(find(change(third, "A", third.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
		ScenarioReplay talk = combat(); own(talk, "A", 2); change(talk, "B");
		assertNull(find(change(talk, "A", talk.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
	}

	@Test public void m14RejectsHiddenNullTransitionUnknownLiveSetAndReusedIndex()
	{
		ScenarioReplay hidden = combat(); own(hidden, "A", 2); target(hidden, "B", true);
		hidden.next(batch(Collections.singletonList(hidden.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true)), Collections.emptySet(),
			Collections.emptyList(), Arrays.asList(null, "B"), false));
		assertNull(find(change(hidden, "A", hidden.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
		ScenarioReplay unknown = combat(); own(unknown, "A", 2); change(unknown, "B", unknown.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true));
		unknown.frame.liveNpcKeys = null; unknown.next(); unknown.frame.liveNpcKeys = new HashSet<>(Arrays.asList("A", "B"));
		assertNull(find(change(unknown, "A", unknown.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
		ScenarioReplay reused = combat(); own(reused, "A", 2); change(reused, "B", reused.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true));
		reused.frame.liveNpcKeys.remove("A"); reused.frame.liveNpcKeys.add("newA");
		assertNull(find(change(reused, "newA", reused.hit(false, "newA", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
	}

	@Test public void m14WindowIncludesNineteenTicksAndExcludesTwenty()
	{
		for (int gap : new int[]{19, 20})
		{
			ScenarioReplay r = combat(); own(r, "A", 2); long first = r.frame.tick;
			change(r, "B", r.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true));
			while (r.frame.tick < first + gap - 1) { r.next(); }
			assertEquals(gap == 19, find(change(r, "A", r.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14") != null);
		}
	}

	@Test public void m15MatchesLaterLargestNumberWithStableEngagementAndFrozenFacts()
	{
		ScenarioReplay r = combat(); own(r, "A", 2);
		Hit early = r.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false), late = r.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false);
		r.next(hits(early, late, r.hit(true, null, HitsplatID.DAMAGE_OTHER, 8, false))); r.next(); r.next();
		Hit outgoing = r.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true);
		SceneCandidate c = find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 8, true), outgoing, outgoing)), "M15"); assertNotNull(c);
		assertEquals("11", c.getConditionFacts().get("matchingAmount")); assertEquals(Long.toString(early.id), c.getConditionFacts().get("incomingFactId"));
		assertEquals("owned:" + outgoing.id, c.getCausalToken()); assertFalse(c.getConditionFacts().containsKey("attacker"));
		r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 1, false))); assertTrue(r.rules.stillValid(c));
		assertNull(find(r.next(hits(outgoing)), "M15"));
	}

	@Test public void m15RejectsSameCommitExcludedTypesUnownedDamageAndM01Threshold()
	{
		for (int incomingType : new int[]{HitsplatID.POISON, HitsplatID.HEAL, HitsplatID.DAMAGE_ME_POISE})
		{
			ScenarioReplay r = combat(); own(r, "A", 2); r.next(hits(r.hit(true, null, incomingType, 11, false)));
			assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
		}
		ScenarioReplay same = combat(); own(same, "A", 2);
		assertNull(find(same.next(hits(same.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false), same.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
		ScenarioReplay other = combat(); own(other, "A", 2); other.next(hits(other.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false)));
		assertNull(find(other.next(hits(other.hit(false, "A", HitsplatID.DAMAGE_OTHER, 11, false))), "M15"));
		ScenarioReplay heavy = combat(); own(heavy, "A", 2); heavy.next(hits(heavy.hit(true, null, HitsplatID.DAMAGE_OTHER, 42, false)));
		assertNull(find(heavy.next(hits(heavy.hit(false, "A", HitsplatID.DAMAGE_ME, 42, true))), "M15"));
	}

	@Test public void m15SixTickLimitRealHpChangeTargetChangeAndOverflowDiscardAttempt()
	{
		for (int gap : new int[]{6, 7})
		{
			ScenarioReplay r = combat(); own(r, "A", 2); r.next(hits(r.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false)));
			for (int i = 1; i < gap; i++) { r.next(); }
			assertEquals(gap == 6, find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15") != null);
		}
		ScenarioReplay hp = combat(); own(hp, "A", 2); hp.next(hits(hp.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false))); hp.frame.realHp = 61;
		assertNull(find(hp.next(hits(hp.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
		ScenarioReplay changed = combat(); own(changed, "A", 2); changed.next(hits(changed.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false)));
		assertNull(find(change(changed, "B", changed.hit(false, "B", HitsplatID.DAMAGE_ME, 11, true)), "M15"));
		ScenarioReplay overflow = combat(); own(overflow, "A", 2); Hit[] incoming = new Hit[9];
		for (int i = 0; i < incoming.length; i++) { incoming[i] = overflow.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false); }
		overflow.next(hits(incoming)); assertNull(find(overflow.next(hits(overflow.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
	}

	@Test public void m15DuplicateIncomingCannotBeConsumedTwiceOrTurnEightFactsIntoOverflow()
	{
		ScenarioReplay r = combat(); own(r, "A", 2); Hit incoming = r.hit(true, null, HitsplatID.DAMAGE_OTHER, 11, false);
		Hit[] duplicates = new Hit[9]; Arrays.fill(duplicates, incoming); r.next(hits(duplicates));
		assertNotNull(find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
		r.next(hits(incoming)); assertNull(find(r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 11, true))), "M15"));
	}

	@Test public void m17CountsThreeDistinctPriorEngagedDeathsAndSurvivesCorpseDespawn()
	{
		ScenarioReplay r = combat(); SceneCandidate c = threeDeaths(r); assertNotNull(c);
		assertEquals("3", c.getConditionFacts().get("observedEngagedDeaths"));
		assertEquals("A,B,C", c.getConditionFacts().get("deadNpcKeys"));
		assertFalse(c.getConditionFacts().containsKey("killCount")); assertFalse(c.getConditionFacts().containsKey("targetKey"));
		change(r, null); assertTrue(r.rules.stillValid(c));
		own(r, "D", 2); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m17RequiresEarlierCommitEvidenceAndDeduplicatesCorpseCallbacks()
	{
		ScenarioReplay r = combat(); own(r, "A", 2); NpcDeath dead = new NpcDeath(++r.fact, "A", true);
		target(r, "A", false); r.frame.liveNpcKeys.remove("A");
		assertNull(find(r.next(batch(Collections.emptyList(), Collections.emptySet(), Arrays.asList(dead, dead, new NpcDeath(++r.fact, "A", true)), Collections.emptyList(), false)), "M17"));
		assertNull(find(r.next(batch(Collections.emptyList(), Collections.emptySet(), Collections.singletonList(dead), Collections.emptyList(), false)), "M17"));
		own(r, "B", 2); death(r, "B"); own(r, "C", 2); assertNotNull(find(death(r, "C"), "M17"));
		ScenarioReplay firstInDeath = combat();
		for (String key : new String[]{"A", "B", "C"})
		{
			target(firstInDeath, key, true); firstInDeath.next(); target(firstInDeath, key, false);
			assertNull(find(firstInDeath.next(batch(Collections.singletonList(firstInDeath.hit(false, key, HitsplatID.DAMAGE_ME, 2, true)), Collections.emptySet(),
				Collections.singletonList(new NpcDeath(++firstInDeath.fact, key, true)), Collections.emptyList(), false)), "M17"));
		}
	}

	@Test public void m17OwnedEvidenceAgeOneToFourAndEightyTickHistoryAreExact()
	{
		for (int age : new int[]{1, 4, 5})
		{
			ScenarioReplay r = combat();
			for (String key : new String[]{"A", "B"}) { own(r, key, 2); death(r, key); }
			own(r, "C", 2); for (int i = 1; i < age; i++) { r.next(); }
			assertEquals(age <= 4, find(death(r, "C"), "M17") != null);
		}
		for (int span : new int[]{79, 80})
		{
			ScenarioReplay r = combat(); own(r, "A", 2); death(r, "A"); long firstDeath = r.frame.tick;
			own(r, "B", 2); death(r, "B"); while (r.frame.tick < firstDeath + span - 2) { r.next(); }
			own(r, "C", 2); assertEquals(span == 79, find(death(r, "C"), "M17") != null);
		}
	}

	@Test public void m17LocalDeathAlwaysWinsOverNpcDeathInTerminalBatch()
	{
		ScenarioReplay r = combat(); own(r, "A", 2); death(r, "A"); own(r, "B", 2); death(r, "B"); own(r, "C", 2);
		NpcDeath third = new NpcDeath(++r.fact, "C", true);
		assertNull(find(r.next(new ScenarioBatch(true, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet(),
			Collections.singletonList(third), Collections.emptyList(), false)), "M17"));
	}

	@Test public void m18FortyToOneHundredTwentySamplesNeedEightDistinctOwnedTicks()
	{
		for (int duration : new int[]{39, 40, 120, 121})
		{
			ScenarioReplay r = fight(duration, 8, true); SceneCandidate c = find(death(r, "A"), "M18");
			assertEquals(duration >= 40 && duration <= 120, c != null);
			if (c != null)
			{
				assertEquals(Integer.toString(duration), c.getConditionFacts().get("observedEngagementCommits"));
				assertEquals("60", c.getConditionFacts().get("startHp")); assertFalse(c.getConditionFacts().containsKey("targetKey"));
			}
		}
		ScenarioReplay few = fight(40, 7, true); assertNull(find(death(few, "A"), "M18"));
		ScenarioReplay oneTick = combat(); target(oneTick, "A", true); Hit[] eight = new Hit[8];
		for (int i = 0; i < eight.length; i++) { eight[i] = oneTick.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true); }
		oneTick.next(hits(eight)); for (int i = 0; i < 39; i++) { oneTick.next(); }
		assertNull(find(death(oneTick, "A"), "M18"));
	}

	@Test public void m18LocalPositiveDamageOrUnknownEffectInvalidatesMatchingEndpoints()
	{
		for (int type : new int[]{HitsplatID.DAMAGE_OTHER, HitsplatID.POISON, HitsplatID.HEAL, 123456})
		{
			ScenarioReplay r = fight(40, 8, true); r.next(hits(r.hit(true, null, type, 1, false)));
			assertNull(find(death(r, "A"), "M18"));
		}
		ScenarioReplay hp = fight(40, 8, true); hp.frame.realHp = 61; assertNull(find(death(hp, "A"), "M18"));
		ScenarioReplay endpoint = fight(40, 8, true); endpoint.frame.currentHp = 59; assertNull(find(death(endpoint, "A"), "M18"));
		ScenarioReplay stale = fight(40, 8, true); stale.next(); stale.next(); assertNull(find(death(stale, "A"), "M18"));
	}

	@Test public void m18PaintRetainsImmutableDeathNameButCancelsAnyHpChangeOrNewFight()
	{
		ScenarioReplay r = fight(40, 8, true); SceneCandidate c = find(death(r, "A"), "M18"); assertNotNull(c);
		change(r, null); assertTrue(r.rules.stillValid(c)); assertEquals("Goblin", c.getConditionFacts().get("npcResolvedName"));
		r.frame.currentHp = 59; r.next(); assertFalse(r.rules.stillValid(c)); r.frame.currentHp = 60; r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay newFight = fight(40, 8, true); SceneCandidate ended = find(death(newFight, "A"), "M18");
		own(newFight, "B", 2); assertFalse(newFight.rules.stillValid(ended));
	}

	@Test public void m17AndM18ShareDeathEpisodeAndHigherPriorityWins()
	{
		ScenarioReplay r = combat(); own(r, "B", 2); death(r, "B"); own(r, "C", 2); death(r, "C");
		for (int i = 0; i < 40; i++)
		{
			target(r, "A", true); if (i < 7 || i == 39) { r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true))); } else { r.next(); }
		}
		List<SceneCandidate> output = death(r, "A"); assertNotNull(find(output, "M17")); assertNull(find(output, "M18"));
	}

	@Test public void m19SeparateConcurrentOwnedXpFactsSelectThreeFieldsOncePerEngagement()
	{
		ScenarioReplay r = combat(); target(r, "A", true);
		assertNull(find(gain(r, Skill.ATTACK), "M19")); r.next(); assertNull(find(gain(r, Skill.DEFENCE), "M19")); r.next();
		SceneCandidate c = find(gain(r, Skill.MAGIC), "M19"); assertNotNull(c);
		assertEquals("ATTACK,DEFENCE,MAGIC", c.getConditionFacts().get("skillSet"));
		assertEquals("ATTACK,DEFENCE,MAGIC", c.getConditionFacts().get("fieldOrder"));
		assertEquals("20,20,20", c.getConditionFacts().get("gainDeltas")); assertEquals("1,2,3", c.getConditionFacts().get("gainOwnedFactIds"));
		assertFalse(c.getConditionFacts().containsKey("combatStyle")); assertTrue(r.rules.stillValid(c));
		assertNull(find(gain(r, Skill.RANGED), "M19")); assertNull(find(gain(r, Skill.STRENGTH), "M19"));
	}

	@Test public void m19RejectsRewardBatchMissingOwnedWitnessAndActualLevelGain()
	{
		ScenarioReplay reward = combat(); target(reward, "A", true);
		for (Skill s : new Skill[]{Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE}) { reward.xp(s, 20); }
		assertNull(find(reward.next(batch(Collections.singletonList(reward.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)),
			EnumSet.of(Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE), Collections.emptyList(), Collections.emptyList(), false)), "M19"));
		ScenarioReplay missing = combat(); target(missing, "A", true);
		for (Skill s : new Skill[]{Skill.ATTACK, Skill.DEFENCE, Skill.MAGIC}) { missing.xp(s, 20); assertNull(find(missing.next(stats(s)), "M19")); }
		ScenarioReplay level = combat(); target(level, "A", true); gain(level, Skill.ATTACK); gain(level, Skill.DEFENCE); level.level(Skill.MAGIC, 11);
		assertNull(find(gain(level, Skill.MAGIC), "M19"));
	}

	@Test public void m19TwentyTickWindowNewestFieldRecordAndTargetContinuityAreExact()
	{
		for (int gap : new int[]{19, 20})
		{
			ScenarioReplay r = combat(); target(r, "A", true); gain(r, Skill.ATTACK); long first = r.frame.tick; gain(r, Skill.DEFENCE);
			while (r.frame.tick < first + gap - 1) { r.next(); }
			assertEquals(gap == 19, find(gain(r, Skill.MAGIC), "M19") != null);
		}
		ScenarioReplay newest = combat(); target(newest, "A", true); gain(newest, Skill.ATTACK); gain(newest, Skill.DEFENCE); gain(newest, Skill.ATTACK);
		assertEquals("DEFENCE,ATTACK,MAGIC", find(gain(newest, Skill.MAGIC), "M19").getConditionFacts().get("fieldOrder"));
		ScenarioReplay changed = combat(); target(changed, "A", true); gain(changed, Skill.ATTACK); gain(changed, Skill.DEFENCE); change(changed, "B");
		assertNull(find(gain(changed, Skill.MAGIC), "M19"));
	}

	@Test public void m19SelectedRealLevelBeforePaintPermanentlyCancelsCandidate()
	{
		ScenarioReplay r = combat(); target(r, "A", true); gain(r, Skill.ATTACK); gain(r, Skill.DEFENCE); SceneCandidate c = find(gain(r, Skill.MAGIC), "M19");
		r.level(Skill.COOKING, 11); r.xp(Skill.COOKING, 20); r.next(stats(Skill.COOKING)); assertFalse(r.rules.stillValid(c));
	}

	@Test public void m20InheritedGuardCalmAtNPlusTenThroughNPlusNineteenKeepsFixedDeadline()
	{
		ScenarioReplay r = fight(35, 6, true); r.frame.currentHp = 38; Hit injury = r.hit(true, null, HitsplatID.DAMAGE_OTHER, 22, false);
		change(r, null, injury); long n = r.frame.tick;
		for (int i = 1; i < 19; i++) { assertNull(find(r.next(), "M20")); }
		SceneCandidate c = find(r.next(), "M20"); assertNotNull(c); assertEquals(n + 19, c.getCreatedTick()); assertEquals(n + 31, c.getExpiresTick());
		assertEquals(Long.toString(n + 20), c.getConditionFacts().get("armExpiresTick")); assertEquals("injury:" + injury.id, c.getCausalToken());
		assertTrue(r.rules.stillValid(c)); assertNull(find(r.next(), "M20"));
	}

	@Test public void m20AlreadyAgedGuardQualifiesOnTenthCompleteQuietSnapshot()
	{
		ScenarioReplay r = fight(35, 6, false); r.frame.currentHp = 45; change(r, null); long n = r.frame.tick;
		for (int i = 1; i < 10; i++) { assertNull(find(r.next(), "M20")); }
		SceneCandidate c = find(r.next(), "M20"); assertNotNull(c); assertEquals(n + 10, c.getCreatedTick()); assertEquals(n + 22, c.getExpiresTick());
	}

	@Test public void m20EngagementDurationHitTicksAndQuarterHpDeficitAreRequired()
	{
		for (int duration : new int[]{29, 30, 120, 121})
		{
			ScenarioReplay r = fight(duration, 6, false); r.frame.currentHp = 45; change(r, null); SceneCandidate c = null;
			for (int i = 0; i < 20; i++) { SceneCandidate found = find(r.next(), "M20"); if (found != null) { c = found; } }
			assertEquals(duration >= 30 && duration <= 120, c != null);
		}
		for (int deficit : new int[]{14, 15})
		{
			ScenarioReplay r = fight(35, 6, false); r.frame.currentHp = 60 - deficit; change(r, null); SceneCandidate c = null;
			for (int i = 0; i < 20; i++) { SceneCandidate found = find(r.next(), "M20"); if (found != null) { c = found; } }
			assertEquals(deficit == 15, c != null);
		}
		ScenarioReplay few = fight(35, 5, false); few.frame.currentHp = 38; change(few, null);
		for (int i = 0; i < 25; i++) { assertNull(find(few.next(), "M20")); }
	}

	@Test public void m20FreshEvidenceUnknownRealHpAndEmergencyDiscardWithoutRearming()
	{
		for (int interruption = 0; interruption < 4; interruption++)
		{
			ScenarioReplay r = fight(35, 6, true); r.frame.currentHp = 38; change(r, null, r.hit(true, null, HitsplatID.DAMAGE_OTHER, 22, false));
			for (int i = 0; i < 4; i++) { r.next(); }
			if (interruption == 0) { r.next(hits(r.hit(true, null, HitsplatID.BLOCK_OTHER, 0, false))); }
			else if (interruption == 1) { r.frame.targetUnknown = true; r.next(); r.frame.targetUnknown = false; }
			else if (interruption == 2) { r.frame.realHp = 61; r.next(); r.frame.realHp = 60; }
			else { r.frame.emergencySceneActive = true; r.next(); r.frame.emergencySceneActive = false; }
			for (int i = 0; i < 30; i++) { assertNull(find(r.next(), "M20")); }
		}
	}

	@Test public void m20RealHpChangeDuringEngagementCannotBeUndoneByRestoringIt()
	{
		ScenarioReplay r = fight(30, 6, false); r.frame.realHp = 61; r.next(); r.frame.realHp = 60; r.next();
		r.frame.currentHp = 38; change(r, null);
		for (int i = 0; i < 25; i++) { assertNull(find(r.next(), "M20")); }
	}

	@Test public void m20TransientTargetInsideBatchCancelsArmAndUnpaintedCandidate()
	{
		ScenarioReplay arm = fight(35, 6, false); arm.frame.currentHp = 38; change(arm, null);
		for (int i = 0; i < 4; i++) { arm.next(); }
		arm.next(batch(Collections.emptyList(), Collections.emptySet(), Collections.emptyList(), Arrays.asList("B", null), false));
		for (int i = 0; i < 25; i++) { assertNull(find(arm.next(), "M20")); }
		ScenarioReplay issued = fight(35, 6, false); issued.frame.currentHp = 38; change(issued, null);
		SceneCandidate c = null; for (int i = 0; i < 10; i++) { c = find(issued.next(), "M20"); } assertNotNull(c);
		issued.next(batch(Collections.emptyList(), Collections.emptySet(), Collections.emptyList(), Arrays.asList("B", null), false));
		assertFalse(issued.rules.stillValid(c)); issued.next(); assertFalse(issued.rules.stillValid(c));
	}

	@Test public void m20PaintDeficitLossCannotReviveAndExpiryIsExclusive()
	{
		ScenarioReplay r = fight(35, 6, false); r.frame.currentHp = 38; change(r, null);
		SceneCandidate c = null; for (int i = 0; i < 10; i++) { c = find(r.next(), "M20"); } assertNotNull(c);
		r.frame.currentHp = 50; r.next(); assertFalse(r.rules.stillValid(c)); r.frame.currentHp = 38; r.next(); assertFalse(r.rules.stillValid(c));
		ScenarioReplay expiry = fight(35, 6, false); expiry.frame.currentHp = 38; change(expiry, null);
		SceneCandidate expiring = null; for (int i = 0; i < 10; i++) { expiring = find(expiry.next(), "M20"); } assertNotNull(expiring);
		while (expiry.frame.tick < expiring.getExpiresTick() - 1) { expiry.next(); } assertTrue(expiry.rules.stillValid(expiring));
		expiry.next(); assertFalse(expiry.rules.stillValid(expiring));
	}

	@Test public void presenceSceneUnknownAndDiscontinuityResetCombatHistoriesAndIssuedFacts()
	{
		for (int reset = 0; reset < 4; reset++)
		{
			ScenarioReplay r = combat(); own(r, "A", 2); change(r, "B", r.hit(false, "B", HitsplatID.DAMAGE_ME, 2, true));
			if (reset == 0) { r.frame.present = false; r.next(); r.frame.present = true; r.frame.presentSinceTick = r.frame.tick + 1; }
			else if (reset == 1) { r.frame.sceneRevision++; r.next(); }
			else if (reset == 2) { r.frame.valid = false; r.next(); r.frame.valid = true; }
			else { r.frame.player = new Position(3120, 3220, 0); r.next(); }
			assertNull(find(change(r, "A", r.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true)), "M14"));
		}
		ScenarioReplay absent = fight(40, 8, true); SceneCandidate c = find(death(absent, "A"), "M18"); assertNotNull(c);
		absent.frame.present = false; absent.next(); absent.frame.present = true; absent.next(); assertFalse(absent.rules.stillValid(c));
	}

	private static ScenarioReplay combat()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.realHp = 60; r.frame.currentHp = 60;
		r.frame.liveNpcKeys = new HashSet<>(Arrays.asList("A", "B", "C", "D")); return r;
	}
	private static void target(ScenarioReplay r, String key, boolean alive)
	{
		r.frame.target = key == null ? null : new Target(key, "Goblin", alive, new Position(3101, 3200, 0));
	}
	private static List<SceneCandidate> own(ScenarioReplay r, String key, int amount)
	{
		if (r.frame.target == null || !r.frame.target.alive || !key.equals(r.frame.target.key))
		{
			return change(r, key, r.hit(false, key, HitsplatID.DAMAGE_ME, amount, true));
		}
		return r.next(hits(r.hit(false, key, HitsplatID.DAMAGE_ME, amount, true)));
	}
	private static List<SceneCandidate> change(ScenarioReplay r, String key, Hit... hits)
	{
		target(r, key, true);
		return r.next(batch(Arrays.asList(hits), Collections.emptySet(), Collections.emptyList(), Collections.singletonList(key), false));
	}
	private static List<SceneCandidate> death(ScenarioReplay r, String key)
	{
		target(r, key, false); r.frame.liveNpcKeys.remove(key);
		return r.next(batch(Collections.emptyList(), Collections.emptySet(), Collections.singletonList(new NpcDeath(++r.fact, key, r.frame.present)), Collections.emptyList(), false));
	}
	private static ScenarioBatch batch(List<Hit> hits, java.util.Set<Skill> skills, List<NpcDeath> deaths, List<String> changes, boolean unknown)
	{
		return new ScenarioBatch(false, false, !changes.isEmpty(), hits, Collections.emptyList(), skills, deaths, changes, unknown);
	}
	private static SceneCandidate threeDeaths(ScenarioReplay r)
	{
		own(r, "A", 2); death(r, "A"); own(r, "B", 2); death(r, "B"); own(r, "C", 2); return find(death(r, "C"), "M17");
	}
	private static ScenarioReplay fight(int commits, int hitTicks, boolean recent)
	{
		ScenarioReplay r = combat(); target(r, "A", true);
		for (int i = 0; i < commits; i++)
		{
			boolean hit = recent ? i < hitTicks - 1 || i == commits - 1 : i < hitTicks;
			if (hit) { r.next(hits(r.hit(false, "A", HitsplatID.DAMAGE_ME, 2, true))); } else { r.next(); }
		}
		return r;
	}
	private static List<SceneCandidate> gain(ScenarioReplay r, Skill skill)
	{
		r.xp(skill, 20);
		return r.next(batch(Collections.singletonList(r.hit(false, r.frame.target.key, HitsplatID.DAMAGE_ME, 2, true)),
			EnumSet.of(skill), Collections.emptyList(), Collections.emptyList(), false));
	}
}
