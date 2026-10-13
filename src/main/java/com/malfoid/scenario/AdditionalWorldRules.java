package com.malfoid.scenario;

import com.malfoid.additional.AdditionalNumericSnapshot;
import com.malfoid.additional.AdditionalNumericSnapshot.Watch;
import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalSceneContext.Subject;
import com.malfoid.additional.AdditionalSnapshot;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import com.malfoid.additional.AdditionalUiSnapshot;
import com.malfoid.additional.PersonalOutcomeFact;
import com.malfoid.additional.PersonalOutcomeFact.Payload;
import com.malfoid.additional.SceneKey;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.speech.SceneCandidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copied personal reports and UI episodes. Unproved source routes remain closed. */
final class AdditionalWorldRules
{
	private static final int MAX_TERMINALS = 16;
	private static final UiKind[] CLOSE_ROOTS = {UiKind.WORLD_MAP, UiKind.SHOP, UiKind.FAIRY_RING, UiKind.CLUE_PUZZLE};
	private static final UiKind[] REWARD_ROOTS = {UiKind.COX_REWARD, UiKind.TOB_REWARD, UiKind.TOA_REWARD, UiKind.BARROWS_REWARD, UiKind.CLUE_REWARD};
	private final long[] consumed = new long[SourceKind.values().length];
	private final EnumMap<UiKind, UiState> uiStates = new EnumMap<>(UiKind.class);
	private final EnumMap<UiKind, PersonalOutcomeFact> rewards = new EnumMap<>(UiKind.class);
	private final EnumMap<OutcomeKind, Integer> counters = new EnumMap<>(OutcomeKind.class);
	private final Map<Long, Evidence> issued = new LinkedHashMap<>();
	private final Map<String, Long> terminals = new LinkedHashMap<>();
	private final int[] floorCounts = new int[5];
	// Current-terminal discriminator belongs to the existing five fixed floor-counter records.
	private long latestFloorSourceSerial;
	private ScenarioSnapshot previous;
	private SceneKey scene;
	private long presence, lastTick = -1, serial, cannonStart = -1, cannonCause, consumedSession = -1;
	private int settleCommits, cannonStage, slayerValue = -1;
	private long slayerTick = -1, slayerSerial, followerTick = -1, followerSerial;
	private Truth follower = Truth.UNKNOWN;
	private int slayerExpected;
	private boolean petReportThisCommit;
	private SourceKind cannonSource = SourceKind.UNKNOWN;

	AdditionalWorldRules() { Arrays.fill(floorCounts, -1); latestFloorSourceSerial = 0; }

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a,
		CombatState combat, ExpandedScenarioRules.Factory factory)
	{ return close(f, batch, a, combat, factory, watchedSources()); }

	List<SceneCandidate> close(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a,
		CombatState combat, ExpandedScenarioRules.Factory factory, Set<SourceKind> dispatch)
	{
		List<Offer> offers = new ArrayList<>();
		if (f != null && consumedSession != f.sessionGeneration)
		{ Arrays.fill(consumed, 0); consumedSession = f.sessionGeneration; }
		if (!complete(f, a) || batch.localDeath || combat == CombatState.UNKNOWN)
		{
			consume(a); reset(); return new ArrayList<>();
		}
		boolean continuous = previous != null && f.tick == lastTick + 1 && a.scene.equals(scene)
			&& a.presenceEpoch == presence && f.player.distance(previous.player) <= 2;
		if (!continuous) { reset(); }
		boolean moved = previous != null && !f.player.equals(previous.player);
		previous = f; scene = a.scene; presence = a.presenceEpoch; lastTick = f.tick;
		settleCommits++;
		petReportThisCommit = false;
		forgetUnknown(f, a, combat);
		if (!continuous || a.baseline)
		{
			consume(a); baseline(a); return new ArrayList<>();
		}
		prune(f.tick);
		List<PersonalOutcomeFact> facts = new ArrayList<>(a.outcomes);
		facts.sort(Comparator.comparingLong(fact -> fact.sourceSerial));
		for (PersonalOutcomeFact fact : facts)
		{
			if (fact.scene.sessionGeneration != consumedSession) { continue; }
			long prior = consumed[fact.sourceKind.ordinal()];
			if (fact.sourceSerial <= prior) { continue; }
			consumed[fact.sourceKind.ordinal()] = fact.sourceSerial;
			if (!validReport(fact, f, a))
			{
				if (fact.kind == OutcomeKind.CANNON_ASSEMBLED) { cannonStage = 0; cannonStart = -1; }
				continue;
			}
			report(fact, f, a, offers);
		}
		EnumSet<SourceKind> sources = armedSources(); sources.addAll(dispatch);
		if (sources.contains(SourceKind.UI)) { closePanels(f, a, combat, moved, offers); rewardPanels(f, a, offers); }
		if (sources.contains(SourceKind.VARBIT)) { slayer(f, a, offers); }
		if (sources.contains(SourceKind.FOLLOWER)) { follower(f, a, offers); }
		if (petPending(a)) { issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 168); }
		offers.removeIf(offer -> offer.context.ruleIndex == 160 && offer.context.sourceSerial != latestFloorSourceSerial);
		offers.sort(Comparator.comparingInt((Offer offer) -> priority(offer.context.ruleIndex)).reversed()
			.thenComparingLong(offer -> offer.context.sourceSerial));
		Map<String, Offer> coalesced = new LinkedHashMap<>();
		for (Offer offer : offers)
		{
			if (!terminals.containsKey(offer.cause) && (priority(offer.context.ruleIndex) >= 50
				|| settleCommits >= 10 && f.tick - f.presentSinceTick >= 10))
			{
				coalesced.putIfAbsent(offer.cause, offer);
			}
		}
		Set<String> joined = joinCauses(); joined.addAll(coalesced.keySet());
		if (joined.size() > MAX_TERMINALS || issued.size() + coalesced.size() > MAX_TERMINALS)
		{
			// Reject the whole interval, never a partial causal set or a delayed replay.
			reset(); return new ArrayList<>();
		}
		List<SceneCandidate> out = new ArrayList<>();
		for (Offer offer : coalesced.values())
		{
			AdditionalSceneContext context = offer.context;
			if (f.tick >= context.originalDeadline) { continue; }
			Map<String, String> projected = project(context);
			projected.put("additionalSourceKind", offer.source.name());
			SceneCandidate candidate = factory.create(f, context.ruleId, priority(context.ruleIndex),
				(int) (context.originalDeadline - f.tick), offer.cause, projected, false, 4);
			issued.put(candidate.getId(), new Evidence(context, offer.cause, offer.source, offer.uiKind, offer.uiEpisode, f.player));
			terminals.put(offer.cause, context.originalDeadline);
			out.add(candidate);
		}
		return out;
	}

	private void report(PersonalOutcomeFact fact, ScenarioSnapshot f, AdditionalSnapshot a, List<Offer> out)
	{
		Payload p = fact.payload;
		switch (fact.kind)
		{
			case PET_ANNOUNCEMENT:
				if (p.branch == Branch.PET_FOLLOWED || p.branch == Branch.PET_BACKPACK || p.branch == Branch.WOULD_HAVE_FOLLOWED)
				{
					petReportThisCommit = true;
					add(out, fact, 141, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0);
				}
				break;
			case COLLECTION_LOG_ENTRY:
				if (a.sourceKnown(SourceKind.VARBIT) && a.numeric.watched(Watch.COLLECTION_LOG_CHAT) == 1 && text(p.displayText, 96))
				{ add(out, fact, 142, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); }
				break;
			case RAID_COMPLETION:
				UiKind raidRoot = raidRoot(p);
				if (fact.completionKnown && p.value > 0 && raidRoot != UiKind.UNKNOWN)
				{
					rewards.put(raidRoot, fact);
					add(out, fact, 144, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, null, 0);
				}
				break;
			case BARROWS_COMPLETION:
				if (fact.completionKnown && p.activity == ActivityKind.BARROWS && p.value > 0) { rewards.put(UiKind.BARROWS_REWARD, fact); }
				break;
			case CLUE_COMPLETION:
				if (fact.completionKnown && p.activity == ActivityKind.CLUE && p.value > 0 && clueTier(p.branch)) { rewards.put(UiKind.CLUE_REWARD, fact); }
				break;
			case COMBAT_ACHIEVEMENT:
				if (fact.completionKnown && achievementTier(p.branch) && text(p.displayText, 120))
				{ add(out, fact, 147, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, null, 0); }
				break;
			case SUPERIOR_FOE:
				add(out, fact, 149, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); break;
			case MUSIC_UNLOCK:
				if (fact.completionKnown && text(p.displayText, 96))
				{ add(out, fact, 151, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); }
				break;
			case BLACKJACK_SUCCESS:
			case BLACKJACK_GLANCE:
				if (fact.completionKnown) { add(out, fact, fact.kind.ruleIndex, Subject.unit(), AdditionalSceneContext.CLAIM_REPORT, null, 0); } break;
			case TRADE_REQUEST:
				// The request is sufficient; no sender name or transcript crosses into dialogue.
				add(out, fact, 155, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); break;
			case CANNON_ASSEMBLED:
				if (cannonSourcesKnown(a)) { cannon(fact, out); } break;
			case GUARDIANS_RIFT_CLOSED:
				if (p.activity == ActivityKind.GUARDIANS_RIFT && count(fact))
				{ add(out, fact, 159, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, null, 0); } break;
			case SEPULCHRE_FLOOR:
				if (p.activity == ActivityKind.SEPULCHRE && p.value >= 1 && p.value <= 5 && p.secondaryValue > 0)
				{
					latestFloorSourceSerial = fact.sourceSerial;
					issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 160
						&& entry.getValue().context.sourceSerial != latestFloorSourceSerial);
					int before = floorCounts[p.value - 1]; floorCounts[p.value - 1] = p.secondaryValue;
					if (fact.completionKnown && (before < 0 || p.secondaryValue > before))
					{ add(out, fact, 160, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, null, 0); }
				} break;
			case TZHAAR_WAVE:
				if (a.tzhaarKnown == Truth.TRUE && a.tzhaarActivity == p.activity && a.tzhaarActivitySerial == p.activitySerial
					&& p.activitySerial > 0 && a.tzhaarWaveSerial == fact.sourceSerial && a.tzhaarWave == p.value && notableWave(p.activity, p.value))
				{ add(out, fact, 162, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); } break;
			case PERSONAL_BEST:
				if (fact.completionKnown && p.activity != ActivityKind.UNKNOWN && p.durationMillis > 0)
				{ add(out, fact, 163, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT, null, 0); } break;
			case TEMPOROSS_SUBDUED:
				if (p.activity == ActivityKind.TEMPOROSS && p.branch == Branch.SUBDUED && count(fact))
				{ add(out, fact, 165, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, null, 0); } break;
			default:
				// M143: no frozen finite boss roster. M146: no exact quest mapping/reopen identity.
				// M150/161/166/167: no canonical current entity/activity table.
				// M164/169: exact result/fire source table remains unaudited.
				// FOLLOWER_JOIN is derived below from the current local relation, never this message.
				break;
		}
	}

	private boolean count(PersonalOutcomeFact fact)
	{
		if (fact.payload.value <= 0) { return false; }
		Integer prior = counters.put(fact.kind, fact.payload.value);
		return fact.completionKnown && (prior == null || fact.payload.value > prior);
	}

	private void cannon(PersonalOutcomeFact fact, List<Offer> out)
	{
		Branch branch = fact.payload.branch;
		if (branch == Branch.BASE)
		{
			cannonStart = fact.occurrenceTick; cannonCause = fact.causeSerial; cannonSource = fact.sourceKind; cannonStage = 1; return;
		}
		Branch expected = cannonStage == 1 ? Branch.STAND : cannonStage == 2 ? Branch.BARRELS : cannonStage == 3 ? Branch.FURNACE : Branch.UNKNOWN;
		if (branch != expected || cannonStart < 0 || fact.occurrenceTick < cannonStart || fact.occurrenceTick - cannonStart >= 20)
		{ cannonStage = 0; cannonStart = -1; return; }
		if (++cannonStage == 4)
		{
			if (fact.completionKnown)
			{
				AdditionalSceneContext context = context(158, cannonSource, fact.sourceSerial, cannonCause, fact.occurrenceTick, deadline(fact, 20),
					fact.scene, fact.presenceEpoch, Subject.announcement(fact.kind), AdditionalSceneContext.CLAIM_REPORT | AdditionalSceneContext.CLAIM_COMPLETION, permittedPayload(158, fact.payload));
				out.add(new Offer(context, reportCause(cannonSource, cannonCause), cannonSource, null, 0));
			}
			cannonStage = 0; cannonStart = -1;
		}
	}

	private void closePanels(ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat, boolean moved, List<Offer> out)
	{
		for (UiKind kind : CLOSE_ROOTS)
		{
			AdditionalUiSnapshot u = a.ui(kind); UiState state = uiStates.computeIfAbsent(kind, ignored -> new UiState());
			if (!knownUi(a, u)) { state.seed(u.visibility); continue; }
			if (state.arm && moved && kind == UiKind.WORLD_MAP) { state.moved = true; }
			if (state.visibility == Truth.FALSE && u.visibility == Truth.TRUE && u.episodeSerial > 0 && u.openTick == f.tick)
			{
				state.arm = true; state.open = u.openTick; state.episode = u.episodeSerial; state.moved = false;
			}
			if (state.visibility == Truth.TRUE && u.visibility == Truth.FALSE)
			{
				long duration = f.tick - state.open;
				boolean shop = kind == UiKind.SHOP;
				if (state.arm && state.episode == u.episodeSerial && u.closeTick == f.tick && duration >= 1
					&& (kind != UiKind.CLUE_PUZZLE || u.puzzleCloseVerified && u.openTick == state.open)
					&& (shop || duration <= 10) && !(kind == UiKind.WORLD_MAP && state.moved)
					&& (!shop || combat == CombatState.NO_CONFIRMED_EVIDENCE))
				{
					int index = kind == UiKind.WORLD_MAP ? 153 : shop ? 154 : kind == UiKind.FAIRY_RING ? 156 : 170;
					long occurrence = shop ? f.tick : state.open, expiry = occurrence + 12;
					AdditionalSceneContext context = context(index, SourceKind.UI, u.episodeSerial, u.episodeSerial, occurrence, expiry, a.scene, a.presenceEpoch,
						Subject.ui(kind), AdditionalSceneContext.CLAIM_UI_EDGE, Payload.EMPTY);
					out.add(new Offer(context, reportCause(SourceKind.UI, u.episodeSerial), SourceKind.UI, kind, u.episodeSerial));
				}
				state.arm = false;
			}
			state.visibility = u.visibility;
		}
	}

	private void rewardPanels(ScenarioSnapshot f, AdditionalSnapshot a, List<Offer> out)
	{
		for (UiKind kind : REWARD_ROOTS)
		{
			AdditionalUiSnapshot u = a.ui(kind); UiState state = uiStates.computeIfAbsent(kind, ignored -> new UiState());
			if (!knownUi(a, u)) { state.seed(u.visibility); continue; }
			PersonalOutcomeFact terminal = rewards.get(kind);
			boolean edge = state.visibility == Truth.FALSE && u.visibility == Truth.TRUE && u.episodeSerial > 0 && u.openTick == f.tick;
			if (edge && terminal != null && u.completionSerial == terminal.sourceSerial && validReport(terminal, f, a))
			{
				if (kind == UiKind.BARROWS_REWARD || kind == UiKind.CLUE_REWARD)
				{ add(out, terminal, 145, Subject.announcement(terminal.kind), AdditionalSceneContext.CLAIM_UI_EDGE | AdditionalSceneContext.CLAIM_COMPLETION, kind, u.episodeSerial); }
				// Raid panels never create another completion, including when M144 was denied.
				rewards.remove(kind);
			}
			state.visibility = u.visibility;
		}
	}

	private void slayer(ScenarioSnapshot f, AdditionalSnapshot a, List<Offer> out)
	{
		int value = a.numeric.watched(Watch.REGULAR_SLAYER);
		if (!a.sourceKnown(SourceKind.VARBIT) || value < 0 || value == AdditionalNumericSnapshot.UNKNOWN)
		{ slayerValue = -1; slayerTick = -1; return; }
		if (slayerTick >= 0)
		{
			if (f.tick == slayerTick + 1 && value == slayerExpected)
			{
				Payload payload = new Payload(Branch.ORDINARY, ActivityKind.UNKNOWN, "", value, -1, Watch.REGULAR_SLAYER.id, -1, -1, -1, null, false);
				AdditionalSceneContext context = context(148, SourceKind.VARBIT, slayerSerial, slayerSerial, slayerTick, slayerTick + 20,
					a.scene, a.presenceEpoch, Subject.announcement(OutcomeKind.REGULAR_SLAYER_FINISHED), AdditionalSceneContext.CLAIM_COUNTER, payload);
				out.add(new Offer(context, "world:NUMERIC:" + slayerSerial, SourceKind.VARBIT, null, 0));
			}
			slayerTick = -1;
		}
		if (slayerValue >= 0 && (long) value == (long) slayerValue + 1)
		{ slayerTick = f.tick; slayerExpected = value; slayerSerial = nextSerial(); }
		slayerValue = value;
	}

	private void follower(ScenarioSnapshot f, AdditionalSnapshot a, List<Offer> out)
	{
		if (!a.sourceKnown(SourceKind.FOLLOWER) || a.followerKnown == Truth.UNKNOWN
			|| a.followerKnown == Truth.FALSE && a.followerSerial != 0 || a.followerKnown == Truth.TRUE && a.followerSerial <= 0)
		{ follower = Truth.UNKNOWN; followerTick = -1; followerSerial = 0; return; }
		if (followerTick >= 0)
		{
			if (f.tick == followerTick + 1 && a.followerKnown == Truth.TRUE && a.followerSerial == followerSerial && !petPending(a))
			{
				Payload payload = new Payload(Branch.ORDINARY, ActivityKind.UNKNOWN, "", -1, -1, -1, -1, followerSerial, -1, null, true);
				long sourceSerial = nextSerial();
				AdditionalSceneContext context = context(168, SourceKind.FOLLOWER, sourceSerial, sourceSerial, followerTick, followerTick + 20, a.scene,
					a.presenceEpoch, Subject.announcement(OutcomeKind.FOLLOWER_JOIN), AdditionalSceneContext.CLAIM_SCENE_EDGE, payload);
				out.add(new Offer(context, "world:FOLLOWER:" + context.sourceSerial, SourceKind.FOLLOWER, null, 0));
			}
			followerTick = -1;
		}
		if (follower == Truth.FALSE && a.followerKnown == Truth.TRUE && !petPending(a))
		{ followerTick = f.tick; followerSerial = a.followerSerial; }
		follower = a.followerKnown;
	}

	boolean stillValid(SceneCandidate candidate, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{ return f != null && f.tick < candidate.getExpiresTick() && continuationValid(candidate, f, a, combat); }

	boolean continuationValid(SceneCandidate candidate, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{
		Evidence evidence = issued.get(candidate.getId());
		return evidence != null && currentEvidence(evidence, f, a, combat);
	}
	private boolean currentEvidence(Evidence evidence, ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{
		if (!complete(f, a) || combat == CombatState.UNKNOWN
			|| !evidence.context.scene.equals(a.scene) || evidence.context.presenceEpoch != a.presenceEpoch
			|| !a.sourceKnown(evidence.source) || a.baseline) { return false; }
		int index = evidence.context.ruleIndex;
		if (index == 158 && !cannonSourcesKnown(a)) { return false; }
		if (index == 148) { return a.numeric.watched(Watch.REGULAR_SLAYER) == evidence.context.payload.value; }
		if (index == 160 && evidence.context.sourceSerial != latestFloorSourceSerial) { return false; }
		if (index == 162)
		{ return a.tzhaarKnown == Truth.TRUE && a.tzhaarActivity == evidence.context.payload.activity
			&& a.tzhaarActivitySerial == evidence.context.payload.activitySerial && a.tzhaarWave == evidence.context.payload.value
			&& a.tzhaarWaveSerial == evidence.context.sourceSerial; }
		if (index == 168)
		{
			return a.followerKnown == Truth.TRUE && a.followerSerial == evidence.context.payload.entitySerial && !petPending(a);
		}
		if (index == 142 && (!a.sourceKnown(SourceKind.VARBIT) || a.numeric.watched(Watch.COLLECTION_LOG_CHAT) != 1)) { return false; }
		if (evidence.uiKind != null)
		{
			AdditionalUiSnapshot u = a.ui(evidence.uiKind);
			return knownUi(a, u) && u.episodeSerial == evidence.uiEpisode
				&& (index == 145 ? u.visibility == Truth.TRUE : u.visibility == Truth.FALSE)
				&& (index != 153 || f.player.equals(evidence.position))
				&& (index != 154 || combat == CombatState.NO_CONFIRMED_EVIDENCE)
				&& (index != 170 || u.openTick == evidence.context.occurrenceTick && u.closeTick > u.openTick && u.closeTick - u.openTick <= 10);
		}
		return true;
	}
	private boolean petPending(AdditionalSnapshot a) { return a.pendingPetAnnouncement || petReportThisCommit; }

	AdditionalSceneContext context(SceneCandidate candidate)
	{ Evidence evidence = issued.get(candidate.getId()); return evidence == null ? null : evidence.context; }
	SourceKind source(SceneCandidate candidate)
	{ Evidence evidence = issued.get(candidate.getId()); return evidence == null ? SourceKind.UNKNOWN : evidence.source; }
	EnumSet<SourceKind> requiredSources(SceneCandidate candidate)
	{
		Evidence evidence = issued.get(candidate.getId());
		if (evidence == null) { return EnumSet.of(SourceKind.UNKNOWN); }
		return evidence.context.ruleIndex == 158 ? EnumSet.of(SourceKind.GAME_MESSAGE, SourceKind.SPAM_MESSAGE)
			: evidence.context.ruleIndex == 142 ? EnumSet.of(SourceKind.GAME_MESSAGE, SourceKind.VARBIT) : EnumSet.of(evidence.source);
	}
	/** Observer calls these on observed transient loss, even when no packet is currently selected. */
	void invalidateCollectionLogEvidence() { issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 142); }
	void invalidateTzhaarEvidence() { issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 162); }
	/** Either assembly channel losing trust permanently retires every affected arm/token. */
	/** Scoped callback-time retirement leaves every other shared UI root and terminal untouched. */
	void invalidatePuzzleEvidence()
	{
		UiState state = uiStates.get(UiKind.CLUE_PUZZLE); if (state != null) { state.arm = false; }
		issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 170);
	}
	void invalidateCannonEvidence()
	{
		cannonStage = 0; cannonStart = -1;
		issued.entrySet().removeIf(entry -> entry.getValue().context.ruleIndex == 158);
	}
	private static boolean cannonSourcesKnown(AdditionalSnapshot a)
	{ return a.sourceKnown(SourceKind.GAME_MESSAGE) && a.sourceKnown(SourceKind.SPAM_MESSAGE); }
	EnumSet<SourceKind> watchedSources()
	{ return EnumSet.of(SourceKind.GAME_MESSAGE, SourceKind.SPAM_MESSAGE, SourceKind.TRADE_REQUEST, SourceKind.UI, SourceKind.VARBIT, SourceKind.FOLLOWER); }
	EnumSet<SourceKind> armedSources()
	{
		EnumSet<SourceKind> sources = EnumSet.noneOf(SourceKind.class);
		if (!rewards.isEmpty()) { sources.add(SourceKind.UI); sources.add(SourceKind.GAME_MESSAGE); }
		for (UiState state : uiStates.values()) { if (state.arm) { sources.add(SourceKind.UI); break; } }
		if (slayerTick >= 0) { sources.add(SourceKind.VARBIT); }
		if (followerTick >= 0) { sources.add(SourceKind.FOLLOWER); }
		if (cannonStage > 0) { sources.add(SourceKind.GAME_MESSAGE); sources.add(SourceKind.SPAM_MESSAGE); }
		for (Evidence evidence : issued.values())
		{
			sources.add(evidence.source);
			if (evidence.context.ruleIndex == 158) { sources.add(SourceKind.GAME_MESSAGE); sources.add(SourceKind.SPAM_MESSAGE); }
			if (evidence.context.ruleIndex == 142) { sources.add(SourceKind.VARBIT); }
		}
		return sources;
	}
	boolean hasPendingWork() { return !armedSources().isEmpty(); }
	void advanceQuiet(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		if (f != null && consumedSession != f.sessionGeneration)
		{ Arrays.fill(consumed, 0); consumedSession = f.sessionGeneration; }
		if (!complete(f, a)) { consume(a); reset(); return; }
		boolean continuous = previous != null && f.tick == lastTick + 1 && a.scene.equals(scene)
			&& a.presenceEpoch == presence && f.player.distance(previous.player) <= 2;
		if (!continuous) { reset(); }
		previous = f; scene = a.scene; presence = a.presenceEpoch; lastTick = f.tick; settleCommits++;
		petReportThisCommit = false;
		if (!continuous || a.baseline) { consume(a); baseline(a); }
		if (!cannonSourcesKnown(a)) { invalidateCannonEvidence(); }
		prune(f.tick);
	}
	int retainedReducerRecords()
	{ return issued.size() + uiStates.size() + counters.size() + 5 + (cannonStage > 0 ? 1 : 0) + (slayerTick >= 0 ? 1 : 0) + (followerTick >= 0 ? 1 : 0); }
	int retainedJoinRecords() { return joinCauses().size(); }
	Set<String> retainedCauseKeys() { return Collections.unmodifiableSet(joinCauses()); }
	private Set<String> joinCauses()
	{
		Set<String> causes = new HashSet<>(terminals.keySet());
		for (Evidence evidence : issued.values()) { causes.add(evidence.cause); }
		for (PersonalOutcomeFact reward : rewards.values()) { causes.add(reportCause(reward.sourceKind, reward.causeSerial)); }
		return causes;
	}

	void reset()
	{
		previous = null; scene = null; lastTick = -1; settleCommits = 0; uiStates.clear(); rewards.clear();
		issued.clear(); terminals.clear(); counters.clear(); Arrays.fill(floorCounts, -1); latestFloorSourceSerial = 0;
		cannonStage = 0; cannonStart = -1; slayerValue = -1; slayerTick = -1; follower = Truth.UNKNOWN; followerTick = -1; followerSerial = 0;
	}

	private void baseline(AdditionalSnapshot a)
	{
		for (UiKind kind : CLOSE_ROOTS) { uiStates.computeIfAbsent(kind, ignored -> new UiState()).seed(a.ui(kind).visibility); }
		for (UiKind kind : REWARD_ROOTS) { uiStates.computeIfAbsent(kind, ignored -> new UiState()).seed(a.ui(kind).visibility); }
		slayerValue = a.sourceKnown(SourceKind.VARBIT) ? a.numeric.watched(Watch.REGULAR_SLAYER) : -1;
		follower = a.sourceKnown(SourceKind.FOLLOWER) ? a.followerKnown : Truth.UNKNOWN;
		followerSerial = a.followerSerial; rewards.clear(); issued.clear(); terminals.clear(); cannonStage = 0; cannonStart = -1;
		counters.clear(); Arrays.fill(floorCounts, -1); latestFloorSourceSerial = 0;
		slayerTick = -1; followerTick = -1;
	}

	private void forgetUnknown(ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat)
	{
		for (Iterator<Map.Entry<Long, Evidence>> it = issued.entrySet().iterator(); it.hasNext();)
		{
			Evidence evidence = it.next().getValue();
			if (!currentEvidence(evidence, f, a, combat)) { it.remove(); }
		}
		if (!a.sourceKnown(SourceKind.UI)) { uiStates.clear(); rewards.clear(); }
		if (!a.sourceKnown(SourceKind.GAME_MESSAGE)) { rewards.clear(); counters.clear(); Arrays.fill(floorCounts, -1); latestFloorSourceSerial = 0; }
		if (!cannonSourcesKnown(a)) { invalidateCannonEvidence(); }
	}

	private void prune(long tick)
	{
		terminals.entrySet().removeIf(entry -> tick >= entry.getValue());
		rewards.entrySet().removeIf(entry -> tick >= deadline(entry.getValue(), 20));
		// SceneCandidate limits every committed hold to40 ticks; expiry itself is never factual loss.
		issued.entrySet().removeIf(entry -> tick > entry.getValue().context.originalDeadline + 40);
		if (cannonStart >= 0 && tick - cannonStart >= 20) { cannonStart = -1; cannonStage = 0; }
	}

	private void consume(AdditionalSnapshot a)
	{
		if (a == null) { return; }
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			if (fact.scene.sessionGeneration == consumedSession)
			{ consumed[fact.sourceKind.ordinal()] = Math.max(consumed[fact.sourceKind.ordinal()], fact.sourceSerial); }
		}
	}

	private boolean validReport(PersonalOutcomeFact fact, ScenarioSnapshot f, AdditionalSnapshot a)
	{
		return a.sourceKnown(fact.sourceKind) && fact.personal && fact.freshAt(f.tick, a.scene, a.presenceEpoch)
			&& fact.occurrenceTick >= f.presentSinceTick && f.tick < deadline(fact, lifetime(fact.kind.ruleIndex)) && channel(fact);
	}
	private static boolean channel(PersonalOutcomeFact fact)
	{
		switch (fact.kind)
		{
			case BLACKJACK_SUCCESS: case BLACKJACK_GLANCE: return fact.sourceKind == SourceKind.SPAM_MESSAGE;
			case TRADE_REQUEST: return fact.sourceKind == SourceKind.TRADE_REQUEST;
			case CANNON_ASSEMBLED: return fact.sourceKind == SourceKind.GAME_MESSAGE || fact.sourceKind == SourceKind.SPAM_MESSAGE;
			default: return fact.sourceKind == SourceKind.GAME_MESSAGE;
		}
	}
	private static boolean complete(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		return f != null && a != null && f.valid && f.present && !f.userHidden && !f.autoAbsent && !f.dead && f.currentHp > 0
			&& f.player != null && f.presentSinceTick >= 0 && a.known && a.tick == f.tick && a.presenceEpoch == f.presentSinceTick
			&& a.scene.sessionGeneration == f.sessionGeneration && a.scene.sceneRevision == f.sceneRevision;
	}
	private static boolean knownUi(AdditionalSnapshot a, AdditionalUiSnapshot u)
	{
		return a.sourceKnown(SourceKind.UI) && u.sourceVerified && !u.baseline && u.visibility != Truth.UNKNOWN && u.rootId == root(u.kind)
			&& (u.kind != UiKind.CLUE_PUZZLE || u.puzzleKindKnown() && (u.visibility == Truth.TRUE || u.puzzleCloseVerified));
	}
	private static int root(UiKind kind)
	{
		switch (kind)
		{
			case WORLD_MAP: return 0x02530000; case SHOP: return 0x012c0000; case FAIRY_RING: return 0x018e0000;
			case CLUE_PUZZLE: return 0x01320000; case COX_REWARD: return 0x021b0000; case TOB_REWARD: return 0x00170000;
			case TOA_REWARD: return 0x03030000; case BARROWS_REWARD: return 0x009b0000; case CLUE_REWARD: return 0x00490000;
			default: return -1;
		}
	}
	private static UiKind raidRoot(Payload p)
	{
		if (p.activity == ActivityKind.CHAMBERS && p.branch == Branch.NORMAL) { return UiKind.COX_REWARD; }
		if (p.activity == ActivityKind.THEATRE && (p.branch == Branch.NORMAL || p.branch == Branch.HARD)) { return UiKind.TOB_REWARD; }
		if (p.activity == ActivityKind.TOMBS && (p.branch == Branch.NORMAL || p.branch == Branch.ENTRY)) { return UiKind.TOA_REWARD; }
		return UiKind.UNKNOWN;
	}
	private static boolean notableWave(ActivityKind activity, int wave)
	{ return activity == ActivityKind.FIGHT_CAVE ? wave == 31 || wave == 61 || wave == 63
		: activity == ActivityKind.INFERNO && (wave == 35 || wave == 67 || wave == 68 || wave == 69); }
	private static boolean achievementTier(Branch branch)
	{ return branch == Branch.EASY || branch == Branch.MEDIUM || branch == Branch.HARD || branch == Branch.ELITE || branch == Branch.MASTER || branch == Branch.GRANDMASTER; }
	private static boolean clueTier(Branch branch)
	{ return branch == Branch.BEGINNER || achievementTier(branch) && branch != Branch.GRANDMASTER; }
	private static boolean text(String value, int limit)
	{
		if (value == null || value.trim().isEmpty() || value.codePointCount(0, value.length()) > limit) { return false; }
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i); if (Character.isISOControl(c) || c == '<' || c == '>' || c == '@') { return false; }
			if (Character.isHighSurrogate(c))
			{ if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { return false; } }
			else if (Character.isLowSurrogate(c)) { return false; }
		}
		return true;
	}
	private static int lifetime(int index) { return index == 152 || index == 155 || index == 157 ? 12 : 20; }
	private static long deadline(PersonalOutcomeFact fact, int life) { return Math.min(fact.originalDeadline, fact.occurrenceTick + life); }
	private static int priority(int index)
	{
		switch (index)
		{
			case 141: return 80; case 142: case 147: return 70; case 143: case 144: return 65;
			case 146: case 163: return 75; case 149: return 60; case 162: return 55;
			case 148: case 159: case 165: return 45; case 145: case 152: case 160: return 40;
			case 164: return 40; case 151: case 158: case 161: case 166: case 167: return 35;
			case 150: case 155: case 157: case 168: case 170: return 30; default: return 25;
		}
	}
	private long nextSerial() { return ++serial; }
	private static String reportCause(SourceKind source, long serial) { return "source:" + source + ":" + serial; }
	private static AdditionalSceneContext context(int index, SourceKind source, long serial, long cause, long occurrence, long deadline, SceneKey scene,
		long presence, Subject subject, long claims, Payload payload)
	{ return new AdditionalSceneContext(index, source, serial, cause, occurrence, deadline, scene, presence, subject, claims, payload); }
	private static void add(List<Offer> out, PersonalOutcomeFact fact, int index, Subject subject, long claims, UiKind ui, long episode)
	{
		out.add(new Offer(context(index, fact.sourceKind, fact.sourceSerial, fact.causeSerial, fact.occurrenceTick, deadline(fact, lifetime(index)), fact.scene,
			fact.presenceEpoch, subject, claims, permittedPayload(index, fact.payload)), reportCause(fact.sourceKind, fact.causeSerial), fact.sourceKind, ui, episode));
	}
	private static Payload permittedPayload(int index, Payload p)
	{
		Branch branch = index == 141 || index == 144 || index == 147 || index == 158 || index == 165 ? p.branch : Branch.UNKNOWN;
		ActivityKind activity = index == 144 || index == 159 || index == 160 || index == 162 || index == 163 || index == 165 ? p.activity : ActivityKind.UNKNOWN;
		String display = index == 142 || index == 147 || index == 151 ? p.displayText : "";
		int value = index == 144 || index == 159 || index == 160 || index == 162 || index == 165 ? p.value : -1;
		return new Payload(branch, activity, display, value, -1, -1, index == 163 ? p.durationMillis : -1, -1, index == 162 ? p.activitySerial : -1, null, false);
	}
	private static Map<String, String> project(AdditionalSceneContext c)
	{
		Map<String, String> facts = new HashMap<>();
		facts.put("additionalSourceSerial", Long.toString(c.sourceSerial)); facts.put("occurredTick", Long.toString(c.occurrenceTick));
		facts.put("witnessPresenceEpoch", Long.toString(c.presenceEpoch)); facts.put("additionalClaimMask", Long.toString(c.permittedClaimMask));
		facts.put("outcomeBranch", c.payload.branch.name()); facts.put("activity", c.payload.activity.name());
		if (!c.payload.displayText.isEmpty()) { facts.put("reportedName", c.payload.displayText); }
		if (c.payload.value >= 0) { facts.put(c.ruleIndex == 160 ? "reportedFloor" : c.ruleIndex == 162 ? "reportedWave" : "reportedCount", Integer.toString(c.payload.value)); }
		if (c.payload.durationMillis > 0) { facts.put("reportedDurationMillis", Long.toString(c.payload.durationMillis)); }
		if (c.ruleIndex == 154) { facts.put("shopClosed", "true"); }
		if (c.subject.uiKind != null) { facts.put("widgetKind", c.subject.uiKind.name()); facts.put("widgetClosed", "true"); }
		return facts;
	}
	private static final class UiState
	{
		Truth visibility = Truth.UNKNOWN;
		boolean arm, moved;
		long open, episode;
		void seed(Truth truth) { visibility = truth; arm = false; moved = false; }
	}
	private static final class Offer
	{
		final AdditionalSceneContext context;
		final String cause;
		final long uiEpisode;
		final SourceKind source;
		final UiKind uiKind;
		Offer(AdditionalSceneContext context, String cause, SourceKind source, UiKind uiKind, long uiEpisode)
		{ this.context = context; this.cause = cause; this.source = source; this.uiKind = uiKind; this.uiEpisode = uiEpisode; }
	}
	private static final class Evidence
	{
		final AdditionalSceneContext context;
		final String cause;
		final SourceKind source;
		final UiKind uiKind;
		final long uiEpisode;
		final ScenarioSnapshot.Position position;
		Evidence(AdditionalSceneContext context, String cause, SourceKind source, UiKind uiKind, long uiEpisode, ScenarioSnapshot.Position position)
		{ this.context = context; this.cause = cause; this.source = source; this.uiKind = uiKind; this.uiEpisode = uiEpisode; this.position = position; }
	}
}
