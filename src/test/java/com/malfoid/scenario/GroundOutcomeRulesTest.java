package com.malfoid.scenario;

import com.malfoid.scenario.GroundOutcomeRules.Pile;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.TileItem;
import org.junit.Test;
import static org.junit.Assert.*;

public class GroundOutcomeRulesTest
{
	@Test public void reportAloneSuppliesNeitherGroundAvailabilityNorPossession()
	{
		GroundOutcomeProof proof = record(new GroundOutcomeRules(), Collections.emptyList());
		assertFalse(proof.currentGroundAvailable); assertFalse(proof.itemPossessionConfirmed);
		assertEquals(10, proof.reportTick); assertEquals(22, proof.originalDeadline); assertEquals(500_000, proof.reportedValue);
	}
	@Test public void freshSelfOwnedPrivatePileIsAvailableBeforeItBecomesVisibleToOtherPlayers()
	{
		GroundOutcomeProof proof = record(new GroundOutcomeRules(), Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)));
		assertTrue(proof.currentGroundAvailable); assertFalse(proof.itemPossessionConfirmed);
		assertEquals(1, proof.pileIdentity); assertEquals(new Position(3206, 3206, 0), proof.groundPosition);
	}
	@Test public void groupPublicOtherAndUnknownOwnershipDoNotProveLocalAvailability()
	{
		for (int ownership : new int[]{TileItem.OWNERSHIP_NONE, TileItem.OWNERSHIP_GROUP, TileItem.OWNERSHIP_OTHER, -1, 99})
		{
			assertFalse(record(new GroundOutcomeRules(), Collections.singletonList(pile(1, 10, 2, ownership, 0, 200, false))).currentGroundAvailable);
		}
	}
	@Test public void staleOrFutureSpawnsCannotCorrelateWithTheCommittedReport()
	{
		for (long spawn : new long[]{0, 9, 11})
		{
			assertFalse(record(new GroundOutcomeRules(), Collections.singletonList(pile(1, spawn, 2, TileItem.OWNERSHIP_SELF, 110, 200, true))).currentGroundAvailable);
		}
	}
	@Test public void ambiguousFreshPilesCannotBecomeUniqueProofAfterOneDisappears()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); Pile first = pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true);
		GroundOutcomeProof proof = record(rules, Arrays.asList(first, pile(2, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)));
		assertFalse(proof.currentGroundAvailable);
		assertFalse(current(rules, 11, Collections.singletonList(first), emptyIds(), emptyQuantities()).currentGroundAvailable);
	}
	@Test public void highestKnownValuedStackIsSelectedWithoutFallingBackToAnAvailableCheaperItem()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules();
		Loot report = new Loot(1, "npc:1", true, Arrays.asList(stack(), new Stack(101, 1, "Higher item", 600_000)));
		GroundOutcomeProof proof = rules.close(frame(10).build(), Collections.singletonList(report), Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 0, 200, false))).get(0);
		assertEquals(101, proof.itemId); assertFalse(proof.currentGroundAvailable);
		int[] ids = emptyIds(), quantities = emptyQuantities(); ids[0] = 100; quantities[0] = 2;
		assertFalse(current(rules, 11, Collections.emptyList(), ids, quantities).itemPossessionConfirmed);
		ids[0] = 101; quantities[0] = 1;
		assertTrue(current(rules, 11, Collections.emptyList(), ids, quantities).itemPossessionConfirmed);
	}
	@Test public void unknownPriceOverflowInvalidQuantityAndUnwitnessedReportsCreateNoProof()
	{
		for (Stack selected : Arrays.asList(new Stack(100, 2, "Item", 0), new Stack(100, 2, "Item", -1),
			new Stack(100, 2, "Item", Long.MAX_VALUE), new Stack(100, 0, "Item", 500_000), new Stack(100, -1, "Item", 500_000)))
		{
			assertTrue(new GroundOutcomeRules().close(frame(10).build(), Collections.singletonList(new Loot(1, "npc:1", true, Collections.singletonList(selected))), Collections.emptyList()).isEmpty());
		}
		assertTrue(new GroundOutcomeRules().close(frame(10).build(), Collections.singletonList(new Loot(1, "npc:1", false, Collections.singletonList(stack()))), Collections.emptyList()).isEmpty());
	}
	@Test public void reportDuplicatesUseTheSameMultisetAndNeverExtendTheOriginalDeadline()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); Stack cheap = new Stack(102, 1, "Cheap", 1);
		Loot first = new Loot(1, "npc:1", true, Arrays.asList(stack(), cheap));
		assertEquals(1, rules.close(frame(10).build(), Collections.singletonList(first), Collections.emptyList()).size());
		assertTrue(rules.close(frame(12).build(), Collections.singletonList(new Loot(2, "npc:1", true, Arrays.asList(cheap, stack()))), Collections.emptyList()).isEmpty());
		assertEquals(22, current(rules, 21, Collections.emptyList(), emptyIds(), emptyQuantities()).originalDeadline);
		assertNull(current(rules, 22, Collections.emptyList(), emptyIds(), emptyQuantities()));
		assertTrue(rules.close(frame(23).build(), Collections.singletonList(first), Collections.emptyList()).isEmpty());
	}
	@Test public void differentSpawnKeysOrFreshReportsAfterTheTwoTickMergeWindowAreDistinctEpisodes()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList());
		assertEquals(1, rules.close(frame(11).build(), Collections.singletonList(new Loot(2, "npc:2", true, Collections.singletonList(stack()))), Collections.emptyList()).size());
		assertEquals(1, rules.close(frame(13).build(), Collections.singletonList(new Loot(3, "npc:1", true, Collections.singletonList(stack()))), Collections.emptyList()).size());
	}
	@Test public void despawnAndIndependentExactIdInventoryGainProvePossessionOnly()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)));
		int[] ids = emptyIds(), quantities = emptyQuantities(); ids[4] = 100; quantities[4] = 1;
		GroundOutcomeProof current = current(rules, 11, Collections.emptyList(), ids, quantities);
		assertTrue(current.itemPossessionConfirmed); assertFalse(current.currentGroundAvailable); assertEquals(0, current.pileIdentity);
	}
	@Test public void possessionDoesNotRequireAnEarlierBubbleOrInventoryIncreaseButAlwaysRequiresAReport()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); ScenarioSnapshot.Builder frame = frame(10);
		frame.inventoryIds[0] = 100; frame.inventoryQuantities[0] = 1;
		assertTrue(rules.close(frame.build(), Collections.singletonList(loot(1)), Collections.emptyList()).get(0).itemPossessionConfirmed);
		assertNull(new GroundOutcomeRules().revalidate(1, frame.build(), Collections.emptyList(), frame.inventoryIds, frame.inventoryQuantities));
	}
	@Test public void unknownTruncatedOrMalformedInventoryCannotBecomePossession()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList());
		int[] ids = emptyIds(), quantities = emptyQuantities(); ids[0] = 100; quantities[0] = 1;
		assertFalse(current(rules, 11, Collections.emptyList(), null, quantities).itemPossessionConfirmed);
		assertFalse(current(rules, 11, Collections.emptyList(), Arrays.copyOf(ids, 27), quantities).itemPossessionConfirmed);
		quantities[5] = 1; assertFalse(current(rules, 11, Collections.emptyList(), ids, quantities).itemPossessionConfirmed);
		quantities[5] = -1; assertFalse(current(rules, 11, Collections.emptyList(), ids, quantities).itemPossessionConfirmed);
	}
	@Test public void currentOwnershipQuantityMembershipAndAuditedDespawnTimeAreRechecked()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)));
		for (Pile changed : Arrays.asList(pile(1, 10, 1, TileItem.OWNERSHIP_SELF, 110, 200, true),
			pile(1, 10, 2, TileItem.OWNERSHIP_OTHER, 110, 200, true), pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 100, true),
			pile(1, 10, 2, TileItem.OWNERSHIP_SELF, -1, 200, true)))
		{
			assertFalse(current(rules, 11, Collections.singletonList(changed), emptyIds(), emptyQuantities()).currentGroundAvailable);
		}
		Pile absent = new Pile(1, 10, 1, 1, 1, -1, new Position(3206, 3206, 0), 100, 2, 2, TileItem.OWNERSHIP_SELF, 110, 200, 100, true, false);
		assertFalse(current(rules, 11, Collections.singletonList(absent), emptyIds(), emptyQuantities()).currentGroundAvailable);
	}
	@Test public void originalReportDeadlineIsExclusiveAndPossessionCannotReviveIt()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList());
		int[] ids = emptyIds(), quantities = emptyQuantities(); ids[0] = 100; quantities[0] = 1;
		assertTrue(current(rules, 21, Collections.emptyList(), ids, quantities).itemPossessionConfirmed);
		assertNull(current(rules, 22, Collections.emptyList(), ids, quantities));
		assertNull(current(rules, 23, Collections.emptyList(), ids, quantities));
	}
	@Test public void invalidAbsentUserHiddenDeadBoatAndScopeChangesRevokeTheEpisode()
	{
		for (int change = 0; change < 10; change++)
		{
			GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList()); ScenarioSnapshot.Builder next = frame(11);
			switch (change)
			{
				case 0: next.valid = false; break; case 1: next.present = false; break; case 2: next.userHidden = true; break;
				case 3: next.dead = true; break; case 4: next.topLevel = false; break; case 5: next.sceneRevision++; break;
				case 6: next.sessionGeneration++; break; case 7: next.presentSinceTick++; break; case 8: next.worldViewId = 1; break;
				default: next.player = new Position(3205, 3205, 1);
			}
			assertNull(rules.revalidate(1, next.build(), Collections.emptyList(), emptyIds(), emptyQuantities()));
			assertNull(current(rules, 12, Collections.emptyList(), emptyIds(), emptyQuantities()));
		}
	}
	@Test public void bankOrTradeGuardSuppressesBothRoutesWithoutExtendingTheEpisode()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)));
		ScenarioSnapshot.Builder blocked = frame(11); blocked.inventoryBlocked = true; int[] ids = emptyIds(), quantities = emptyQuantities(); ids[0] = 100; quantities[0] = 1;
		GroundOutcomeProof current = rules.revalidate(1, blocked.build(), Collections.singletonList(pile(1, 10, 2, TileItem.OWNERSHIP_SELF, 110, 200, true)), ids, quantities);
		assertFalse(current.currentGroundAvailable); assertFalse(current.itemPossessionConfirmed); assertEquals(22, current.originalDeadline);
	}
	@Test public void absenceAndSceneResetCannotReissueAnOldReportOnReturn()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList());
		ScenarioSnapshot.Builder absent = frame(11); absent.present = false;
		assertTrue(rules.close(absent.build(), Collections.emptyList(), Collections.emptyList()).isEmpty());
		ScenarioSnapshot.Builder returned = frame(12); returned.presentSinceTick = 12; returned.sceneRevision++;
		assertTrue(rules.close(returned.build(), Collections.singletonList(loot(1)), Collections.emptyList()).isEmpty());
		assertNull(rules.revalidate(1, returned.build(), Collections.emptyList(), emptyIds(), emptyQuantities()));
		returned.tick = 13;
		assertEquals(1, rules.close(returned.build(), Collections.singletonList(loot(2)), Collections.emptyList()).size());
	}
	@Test public void aRejectedInvalidFrameCannotCatchUpItsReportButSessionResetMayStartNewIds()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); ScenarioSnapshot.Builder invalid = frame(10); invalid.valid = false;
		assertTrue(rules.close(invalid.build(), Collections.singletonList(loot(1)), Collections.emptyList()).isEmpty());
		assertTrue(rules.close(frame(11).build(), Collections.singletonList(loot(1)), Collections.emptyList()).isEmpty());
		rules.reset(); ScenarioSnapshot.Builder nextSession = frame(12); nextSession.sessionGeneration++;
		assertEquals(1, rules.close(nextSession.build(), Collections.singletonList(loot(1)), Collections.emptyList()).size());
	}
	@Test public void firstPaintOrSilentArrivalSettlementPreventsLaterPossessionDelivery()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList()); rules.settled(1);
		int[] ids = emptyIds(), quantities = emptyQuantities(); ids[0] = 100; quantities[0] = 1;
		assertNull(current(rules, 11, Collections.emptyList(), ids, quantities));
		assertTrue(rules.close(frame(11).build(), Collections.singletonList(loot(2)), Collections.emptyList()).isEmpty());
	}
	@Test public void capacityIsFixedAndAnOversizedSourceBatchProducesNoDependentProofs()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); List<Loot> reports = new ArrayList<>();
		for (int i = 1; i <= GroundOutcomeRules.MAX_EPISODES; i++) { reports.add(new Loot(i, "npc:" + i, true, Collections.singletonList(stack()))); }
		assertEquals(GroundOutcomeRules.MAX_EPISODES, rules.close(frame(10).build(), reports, Collections.emptyList()).size());
		assertTrue(rules.close(frame(11).build(), Collections.singletonList(new Loot(65, "npc:65", true, Collections.singletonList(stack()))), Collections.emptyList()).isEmpty());
		reports.add(loot(65)); assertTrue(new GroundOutcomeRules().close(frame(10).build(), reports, Collections.emptyList()).isEmpty());
	}
	@Test public void duplicateCommittedTickCannotIssueAnotherOpportunity()
	{
		GroundOutcomeRules rules = new GroundOutcomeRules(); record(rules, Collections.emptyList());
		assertTrue(rules.close(frame(10).build(), Collections.singletonList(new Loot(2, "npc:2", true, Collections.singletonList(stack()))), Collections.emptyList()).isEmpty());
	}

	private static GroundOutcomeProof record(GroundOutcomeRules rules, List<Pile> piles)
	{ return rules.close(frame(10).build(), Collections.singletonList(loot(1)), piles).get(0); }
	private static GroundOutcomeProof current(GroundOutcomeRules rules, long tick, List<Pile> piles, int[] ids, int[] quantities)
	{ return rules.revalidate(1, frame(tick).build(), piles, ids, quantities); }
	private static Loot loot(long id) { return new Loot(id, "npc:1", true, Collections.singletonList(stack())); }
	private static Stack stack() { return new Stack(100, 2, "Exact item", 250_000); }
	private static int[] emptyIds() { int[] result = new int[28]; Arrays.fill(result, -1); return result; }
	private static int[] emptyQuantities() { return new int[28]; }
	private static ScenarioSnapshot.Builder frame(long tick)
	{
		ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder(); frame.tick = tick; frame.presentSinceTick = 1;
		frame.worldViewId = -1; frame.player = new Position(3205, 3205, 0); frame.inventoryIds = emptyIds(); frame.inventoryQuantities = emptyQuantities();
		return frame;
	}
	private static Pile pile(long identity, long spawnTick, int currentQuantity, int ownership, int visibleTime, int despawnTime, boolean privateItem)
	{
		return new Pile(identity, spawnTick, 1, 1, 1, -1, new Position(3206, 3206, 0), 100, 2, currentQuantity, ownership, visibleTime, despawnTime, 100, privateItem, true);
	}
}
