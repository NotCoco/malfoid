package com.malfoid.scenario;

import com.malfoid.additional.AdditionalNumericSnapshot;
import com.malfoid.additional.AdditionalSceneContext;
import com.malfoid.additional.AdditionalSceneContext.Subject;
import com.malfoid.additional.AdditionalSnapshot;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.ItemFamily;
import com.malfoid.additional.AdditionalTypes.ItemForm;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.RecoveryKind;
import com.malfoid.additional.AdditionalTypes.ResourceKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import com.malfoid.additional.AdditionalUiSnapshot;
import com.malfoid.additional.PersonalOutcomeFact;
import com.malfoid.additional.PersonalOutcomeFact.Payload;
import com.malfoid.additional.SceneKey;
import com.malfoid.additional.SharedItemSummary;
import com.malfoid.additional.SharedItemSummary.InventoryMultiset;
import com.malfoid.scenario.ScenarioRules.CombatState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Skill;

/** Fixed item/report arms over the shared committed copies. Does not acquire client evidence. */
final class AdditionalItemRules
{
	private static final int FIRST = 106, LAST = 140, AMMO = 41, WEAPON = 31, SHIELD = 33, RING = 40;
	private static final Set<SourceKind> WATCHED = Collections.unmodifiableSet(EnumSet.of(SourceKind.UI,
		SourceKind.INVENTORY, SourceKind.EQUIPMENT, SourceKind.NUMERIC, SourceKind.GAME_MESSAGE,
		SourceKind.SPAM_MESSAGE, SourceKind.FOLLOWER, SourceKind.VARBIT));
	private static final Skill[] RESTORABLE = EnumSet.of(Skill.AGILITY, Skill.THIEVING, Skill.CRAFTING,
		Skill.WOODCUTTING, Skill.FISHING, Skill.COOKING, Skill.FARMING).toArray(new Skill[0]);
	private final Edge[] edges = new Edge[LAST - FIRST + 1];
	private final Opportunity[] opportunities = new Opportunity[LAST - FIRST + 1];
	private final long[] seenReports = new long[OutcomeKind.values().length];
	private long lastTick = -1, baselineThrough = -1, serial, kittenInvalidatedSerial, seenPouchSerial;
	private SceneKey scene;
	private long epoch;
	private int baselines;
	private boolean previousNumericKnown, weightHighHandled, uiInvalidated, pouchInvalidated;
	private ItemState previous;
	private AdditionalNumericSnapshot previousNumeric;
	private BankVisit bank;
	private Recovery energy, weight, prayer, restored;

	List<AdditionalSceneContext> close(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a,
		CombatState combat, long lastDamage)
	{
		return close(f, batch, a, combat, lastDamage, WATCHED);
	}

	List<AdditionalSceneContext> close(ScenarioSnapshot f, ScenarioBatch batch, AdditionalSnapshot a,
		CombatState combat, long lastDamage, Set<SourceKind> dispatch)
	{
		List<AdditionalSceneContext> out = new ArrayList<>();
		if (f.tick == lastTick) { return out; }
		boolean continuous = lastTick >= 0 && f.tick == lastTick + 1 && a.scene.equals(scene) && a.presenceEpoch == epoch;
		if (!base(f, a) || batch.localDeath || !continuous || a.baseline)
		{
			reset(); scene = a.scene; epoch = a.presenceEpoch; lastTick = f.tick; baselineThrough = f.tick;
			consumeReports(a);
			if (base(f, a) && !batch.localDeath && !a.baseline)
			{
				previous = new ItemState(a); previousNumeric = a.numeric; previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC); baselines = 1;
			}
			return out;
		}
		lastTick = f.tick;
		ItemState now = new ItemState(a);
		if (baselines < 2)
		{
			consumeReports(a); previous = now; previousNumeric = a.numeric; previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC); baselines++;
			baselineThrough = f.tick; uiInvalidated = false; pouchInvalidated = false; kittenInvalidatedSerial = 0; return out;
		}
		if (uiInvalidated)
		{
			uiInvalidated = false;
			bank(f, a, now);
			consumeReports(a); previous = now; previousNumeric = a.numeric;
			previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC); weightHighHandled = a.numeric.weight() >= 30;
			kittenInvalidatedSerial = 0; pouchInvalidated = false;
			return out;
		}
		for (int i = 0; i < opportunities.length; i++)
		{
			Opportunity opportunity = opportunities[i];
			if (opportunity != null && (!current(opportunity, f, a)
				|| (f.tick >= opportunity.context.originalDeadline && f.tick - opportunity.context.originalDeadline > 40)
				|| (!opportunity.offered && (f.tick >= opportunity.context.originalDeadline || f.tick >= opportunity.armDeadline)))) { opportunities[i] = null; }
		}
		if (dispatch.contains(SourceKind.UI) || dispatch.contains(SourceKind.INVENTORY)) { bank(f, a, now); }
		if (now.guarded)
		{
			clearOrdinary(); consumeReports(a); previous = now; previousNumeric = a.numeric;
			weightHighHandled = a.numeric.weight() >= 30;
			previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC); kittenInvalidatedSerial = 0; pouchInvalidated = false; return out;
		}
		if (dispatch.contains(SourceKind.GAME_MESSAGE) || dispatch.contains(SourceKind.SPAM_MESSAGE)
			|| kittenInvalidatedSerial != 0) { reports(f, a); }
		if (!previous.guarded)
		{
			itemEdges(f, a, now, dispatch);
			if (dispatch.contains(SourceKind.NUMERIC)) { numeric(f, a); }
		}
		else { clearRecoveries(); }
		confirmEdges(f, a, now);
		if (admitted(f, a, combat, lastDamage) && !f.emergencySceneActive && f.directorIdle)
		{
			for (Opportunity opportunity : opportunities)
			{
				if (opportunity == null || opportunity.offered || f.tick >= opportunity.context.originalDeadline || f.tick >= opportunity.armDeadline) { continue; }
				if (!current(opportunity, f, a)) { continue; }
				opportunity.offered = true;
				AdditionalSceneContext c = opportunity.context;
				// Only an explicit copied cause joins siblings; the commit tick never does.
				boolean joined = false;
				for (int i = 0; i < out.size(); i++)
				{
					AdditionalSceneContext old = out.get(i);
					if (old.sourceKind == c.sourceKind && old.causeSerial == c.causeSerial)
					{
						joined = true;
						if (old.ruleIndex == 106 && c.ruleIndex == 125) { out.set(i, c); }
						break;
					}
				}
				if (!joined) { out.add(c); }
			}
		}
		previous = now; previousNumeric = a.numeric; previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC);
		consumePouchSerial(a); pouchInvalidated = false; kittenInvalidatedSerial = 0;
		return out;
	}

	Set<SourceKind> watchedSources() { return WATCHED; }

	Set<SourceKind> armedSources()
	{
		Set<SourceKind> sources = EnumSet.noneOf(SourceKind.class);
		for (Edge edge : edges)
		{
			if (edge == null) { continue; }
			sources.add(edge.context.sourceKind);
			if (edge.context.ruleIndex == 123) { sources.add(SourceKind.INVENTORY); }
		}
		for (Opportunity opportunity : opportunities)
		{
			if (opportunity == null) { continue; }
			sources.add(opportunity.context.sourceKind);
			if (opportunity.context.ruleIndex == 118)
			{ sources.add(SourceKind.GAME_MESSAGE); sources.add(SourceKind.SPAM_MESSAGE); }
			if (opportunity.context.ruleIndex == 128) { sources.add(SourceKind.FOLLOWER); }
			if (opportunity.context.ruleIndex == 123) { sources.add(SourceKind.INVENTORY); }
		}
		if (pouchInvalidated) { sources.add(SourceKind.VARBIT); sources.add(SourceKind.INVENTORY); }
		if (kittenInvalidatedSerial != 0) { sources.add(SourceKind.GAME_MESSAGE); sources.add(SourceKind.FOLLOWER); }
		if (bank != null) { sources.add(SourceKind.UI); sources.add(SourceKind.INVENTORY); }
		if (energy != null || weight != null || prayer != null || restored != null
			|| (baselines >= 2 && previousNumericKnown && !weightHighHandled && previousNumeric.weight() >= 30))
		{ sources.add(SourceKind.NUMERIC); }
		if (!sources.isEmpty() || uiInvalidated) { sources.add(SourceKind.UI); }
		return Collections.unmodifiableSet(sources);
	}

	boolean hasPendingWork() { return !armedSources().isEmpty(); }

	/** Called only for an unchanged source union without an arm or issued proof. */
	void advanceQuiet(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		if (f.tick == lastTick) { return; }
		boolean continuous = lastTick >= 0 && f.tick == lastTick + 1 && a.scene.equals(scene) && a.presenceEpoch == epoch;
		if (!base(f, a) || !continuous || a.baseline)
		{
			reset(); scene = a.scene; epoch = a.presenceEpoch; lastTick = f.tick; baselineThrough = f.tick;
			consumeReports(a);
			if (base(f, a) && !a.baseline) { baselines = 1; }
		}
		else
		{
			lastTick = f.tick;
			if (baselines < 2) { consumeReports(a); baselines++; baselineThrough = f.tick; }
		}
		if (base(f, a) && !a.baseline)
		{
			if (uiInvalidated) { consumeReports(a); weightHighHandled = a.numeric.weight() >= 30; uiInvalidated = false; }
			consumeInvalidatedKittenReports(a); kittenInvalidatedSerial = 0;
			previous = new ItemState(a); previousNumeric = a.numeric; previousNumericKnown = a.sourceKnown(SourceKind.NUMERIC);
		}
		consumePouchSerial(a); pouchInvalidated = false;
	}

	boolean stillValid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a,
		CombatState combat, long lastDamage)
	{
		return valid(c, f, a, combat, lastDamage) && f.tick < c.originalDeadline
			&& f.tick < opportunities[c.ruleIndex - FIRST].armDeadline;
	}

	boolean continuationValid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a,
		CombatState combat, long lastDamage)
	{
		return valid(c, f, a, combat, lastDamage);
	}

	int retainedReducerRecords()
	{
		int count = (previous == null ? 0 : 1) + (previousNumeric == null ? 0 : 1) + (bank == null ? 0 : 1)
			+ (energy == null ? 0 : 1) + (weight == null ? 0 : 1) + (prayer == null ? 0 : 1) + (restored == null ? 0 : 1);
		for (Edge edge : edges) { if (edge != null) { count++; } }
		for (Opportunity opportunity : opportunities) { if (opportunity != null) { count++; } }
		return count;
	}

	Set<String> retainedCauseKeys()
	{
		Set<String> causes = new LinkedHashSet<>();
		for (Edge edge : edges) { if (edge != null) { causes.add(causeKey(edge.context.sourceKind, edge.context.causeSerial)); } }
		for (Opportunity opportunity : opportunities)
		{ if (opportunity != null) { causes.add(causeKey(opportunity.context.sourceKind, opportunity.context.causeSerial)); } }
		if (bank != null) { causes.add(causeKey(SourceKind.UI, bank.uiSerial)); }
		for (Recovery arm : new Recovery[]{energy, weight, prayer, restored})
		{ if (arm != null) { causes.add(causeKey(SourceKind.NUMERIC, arm.cause)); } }
		return Collections.unmodifiableSet(causes);
	}

	private static String causeKey(SourceKind kind, long cause)
	{
		return (kind == SourceKind.NUMERIC || kind == SourceKind.EQUIPMENT || kind == SourceKind.VARBIT ? "items:" : "source:") + kind + ':' + cause;
	}

	private boolean valid(AdditionalSceneContext c, ScenarioSnapshot f, AdditionalSnapshot a,
		CombatState combat, long lastDamage)
	{
		if (c.ruleIndex < FIRST || c.ruleIndex > LAST || !admitted(f, a, combat, lastDamage)) { return false; }
		Opportunity opportunity = opportunities[c.ruleIndex - FIRST];
		return opportunity != null && opportunity.offered && opportunity.context == c && current(opportunity, f, a);
	}

	/** Fresh UI loss cancels this interval without advancing its ordinal or replaying consumed sources. */
	void invalidateUi()
	{
		Arrays.fill(edges, null); Arrays.fill(opportunities, null); bank = null; clearRecoveries(); uiInvalidated = true;
	}

	/** Fresh dependent pouch loss consumes its open interval without changing clocks or other causes. */
	void invalidatePouch()
	{
		edges[123 - FIRST] = null; opportunities[123 - FIRST] = null; pouchInvalidated = true;
	}

	/** Fresh owned-follower loss consumes only its open cat interval, without advancing a committed copy. */
	void invalidateKitten(long incarnationSerial)
	{
		if (incarnationSerial <= 0 || kittenInvalidatedSerial < 0
			|| (kittenInvalidatedSerial > 0 && kittenInvalidatedSerial != incarnationSerial)) { kittenInvalidatedSerial = -1; }
		else { kittenInvalidatedSerial = incarnationSerial; }
		Opportunity cat = opportunities[128 - FIRST];
		if (cat != null && (kittenInvalidatedSerial < 0 || cat.context.payload.entitySerial == incarnationSerial))
		{ opportunities[128 - FIRST] = null; }
	}

	void reset()
	{
		Arrays.fill(edges, null); Arrays.fill(opportunities, null); Arrays.fill(seenReports, 0);
		bank = null; previous = null; previousNumeric = null; previousNumericKnown = false; clearRecoveries();
		lastTick = -1; baselines = 0; baselineThrough = -1; scene = null; weightHighHandled = false; uiInvalidated = false;
		kittenInvalidatedSerial = 0; seenPouchSerial = 0; pouchInvalidated = false;
	}

	private static boolean base(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		return f != null && a != null && a.tick == f.tick && a.known && f.valid && f.present && !f.userHidden
			&& !f.autoAbsent && !f.dead && f.currentHp > 0
			&& f.player != null && f.companion != null && f.player.plane == f.companion.plane
			&& a.scene.sessionGeneration == f.sessionGeneration && a.scene.sceneRevision == f.sceneRevision
			&& a.presenceEpoch == f.presentSinceTick;
	}

	private static boolean admitted(ScenarioSnapshot f, AdditionalSnapshot a, CombatState combat, long lastDamage)
	{
		return base(f, a) && !a.baseline && a.sourceKnown(SourceKind.UI)
			&& f.tick - f.presentSinceTick >= 10 && !f.inventoryBlocked
			&& !a.items.guardedUi() && combat == CombatState.NO_CONFIRMED_EVIDENCE && f.tick - lastDamage >= 10;
	}

	private void clearOrdinary()
	{
		Arrays.fill(edges, null);
		for (int i = 1; i < opportunities.length; i++) { opportunities[i] = null; }
		clearRecoveries();
	}

	private void clearRecoveries() { energy = null; weight = null; prayer = null; restored = null; }

	private void bank(ScenarioSnapshot f, AdditionalSnapshot a, ItemState now)
	{
		SharedItemSummary items = a.items;
		if (!a.sourceKnown(SourceKind.UI) || !a.sourceKnown(SourceKind.INVENTORY) || !items.inventoryMultiset.known
			|| items.bankVisible == Truth.UNKNOWN || items.tradeVisible != Truth.FALSE)
		{
			bank = null; opportunities[0] = null; edges[0] = null; return;
		}
		if (items.bankVisible == Truth.TRUE)
		{
			opportunities[0] = null; edges[0] = null;
			if (previous.bankVisible == Truth.FALSE)
			{
				AdditionalUiSnapshot ui = a.ui(UiKind.BANK);
				if (ui.visibility != Truth.TRUE) { ui = a.ui(UiKind.DEPOSIT); }
				bank = ui.sourceVerified && !ui.baseline && ui.episodeSerial > 0 && ui.openTick == f.tick
					? new BankVisit(f.tick, ui.episodeSerial, items.inventoryMultiset) : null;
			}
			if (bank != null && f.tick - bank.entryTick > 200) { bank = null; }
			return;
		}
		if (previous.bankVisible == Truth.TRUE && bank != null)
		{
			AdditionalUiSnapshot ui = a.ui(UiKind.BANK);
			if (ui.episodeSerial != bank.uiSerial) { ui = a.ui(UiKind.DEPOSIT); }
			if (ui.sourceVerified && !ui.baseline && ui.visibility == Truth.FALSE && ui.episodeSerial == bank.uiSerial
				&& ui.closeTick == f.tick && f.tick - bank.entryTick <= 200
				&& !bank.entry.equals(items.inventoryMultiset) && items.contentsCauseSerial > 0)
			{
				Edge edge = edge(a, 106, SourceKind.INVENTORY, items.contentsCauseSerial, f.tick, 12, Subject.unit(), Payload.EMPTY);
				edge.bag = items.inventoryMultiset; edge.visibility = items.visibilityEpoch; edges[0] = edge;
			}
			bank = null;
		}
	}

	private void consumeReports(AdditionalSnapshot a)
	{
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			if (itemReport(fact.kind)) { seenReports[fact.kind.ordinal()] = Math.max(seenReports[fact.kind.ordinal()], fact.sourceSerial); }
		}
		consumePouchSerial(a);
	}

	private void consumePouchSerial(AdditionalSnapshot a)
	{
		seenPouchSerial = Math.max(seenPouchSerial, a.items.pouchContentSerial);
	}

	private void consumeInvalidatedKittenReports(AdditionalSnapshot a)
	{
		if (kittenInvalidatedSerial == 0) { return; }
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			if (fact.kind == OutcomeKind.CAT_NEEDS_FOOD && (kittenInvalidatedSerial < 0
				|| fact.payload.entitySerial == kittenInvalidatedSerial))
			{ seenReports[fact.kind.ordinal()] = Math.max(seenReports[fact.kind.ordinal()], fact.sourceSerial); }
		}
	}

	private static boolean itemReport(OutcomeKind kind) { return kind.ruleIndex >= FIRST && kind.ruleIndex <= LAST; }

	private void reports(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		for (PersonalOutcomeFact fact : a.outcomes)
		{
			if (!itemReport(fact.kind) || fact.sourceSerial <= seenReports[fact.kind.ordinal()]) { continue; }
			seenReports[fact.kind.ordinal()] = fact.sourceSerial;
			if (fact.kind == OutcomeKind.CAT_NEEDS_FOOD && (kittenInvalidatedSerial < 0
				|| (kittenInvalidatedSerial > 0 && fact.payload.entitySerial == kittenInvalidatedSerial))) { continue; }
			if (!fact.personal || !fact.freshAt(f.tick, a.scene, a.presenceEpoch) || !a.sourceKnown(fact.sourceKind)
				|| fact.occurrenceTick <= baselineThrough || fact.occurrenceTick < f.presentSinceTick || !channel(fact)) { continue; }
			boolean knownCause = knownCause(fact);
			if (fact.kind == OutcomeKind.DODGY_CHARGES || fact.kind == OutcomeKind.DODGY_PROTECTION)
			{
				Opportunity low = opportunities[118 - FIRST];
				if (low != null && fact.sourceSerial > low.context.sourceSerial && (!fact.sourceVerified
					|| !fact.fullGrammarVerified || fact.payload.value <= 0 || fact.payload.value > low.context.payload.value))
				{ opportunities[118 - FIRST] = null; }
			}
			if (fact.kind == OutcomeKind.CAT_NEEDS_FOOD)
			{
				if (!ownKitten(a, fact.payload)) { continue; }
				if (fact.payload.value == 0) { invalidateKitten(fact.payload.entitySerial); continue; }
				if (fact.payload.value != 1 && fact.payload.value != 2) { continue; }
				Opportunity cat = opportunities[128 - FIRST];
				if (cat != null && cat.context.payload.entitySerial == fact.payload.entitySerial
					&& cat.context.payload.definitionId == fact.payload.definitionId) { continue; }
			}
			if (knownCause) { continue; }
			int rule = fact.kind.ruleIndex;
			Subject subject = Subject.unit();
			if (rule == 118)
			{
				if (!a.sourceKnown(SourceKind.GAME_MESSAGE) || !a.sourceKnown(SourceKind.SPAM_MESSAGE)
					|| (fact.payload.value != 1 && fact.payload.value != 2)) { continue; }
				subject = Subject.item(ItemFamily.DODGY_NECKLACE);
			}
			if (rule == 119 || rule == 120)
			{
				// The common collector leaves this source closed until the unique association is audited.
				if (fact.payload.definitionId != 13660 || fact.payload.entitySerial <= 0
					|| (rule == 120 && (fact.payload.value <= 0 || fact.payload.secondaryValue <= 0))) { continue; }
			}
			if (rule == 128)
			{
				subject = Subject.announcement(OutcomeKind.CAT_NEEDS_FOOD);
			}
			int life = rule == 117 || rule == 118 || rule == 119 || rule == 120 || rule == 128 ? 10 : 12;
			long deadline = Math.min(fact.originalDeadline, fact.occurrenceTick + life);
			if (f.tick >= deadline) { continue; }
			AdditionalSceneContext context = new AdditionalSceneContext(rule, fact.sourceKind, fact.sourceSerial,
				fact.causeSerial, fact.occurrenceTick, deadline, a.scene, a.presenceEpoch, subject,
				AdditionalSceneContext.CLAIM_REPORT, fact.payload);
			opportunities[rule - FIRST] = new Opportunity(context);
		}
	}

	private boolean knownCause(PersonalOutcomeFact fact)
	{
		for (Opportunity opportunity : opportunities)
		{
			if (opportunity != null && opportunity.context.sourceKind == fact.sourceKind
				&& opportunity.context.causeSerial == fact.causeSerial) { return true; }
		}
		return false;
	}

	private static boolean channel(PersonalOutcomeFact fact)
	{
		if (fact.sourceKind == SourceKind.GAME_MESSAGE) { return true; }
		int id = fact.kind.ruleIndex;
		return fact.sourceKind == SourceKind.SPAM_MESSAGE && (id == 113 || id == 118 || id == 119
			|| id == 120 || id >= 136);
	}

	private void itemEdges(ScenarioSnapshot f, AdditionalSnapshot a, ItemState n, Set<SourceKind> dispatch)
	{
		if (dispatch.contains(SourceKind.VARBIT) && !pouchInvalidated && previous.pouch == 1 && n.pouch == 0
			&& previous.pouchAssociation == n.pouchAssociation && previous.pouchDomain == n.pouchDomain
			&& n.pouchSerial > seenPouchSerial && n.pouchSerial > previous.pouchSerial
			&& n.pouchTick == f.tick && n.pouchSample == f.tick && f.tick >= 0 && f.tick <= Long.MAX_VALUE - 12)
		{
			Payload payload = new Payload(Branch.ORDINARY, ActivityKind.UNKNOWN, "", -1, -1, 12791, -1,
				n.pouchAssociation, n.pouchDomain, null, true);
			edges[123 - FIRST] = edge(a, 123, SourceKind.VARBIT, n.pouchSerial, n.pouchTick, 12,
				Subject.resource(ResourceKind.STANDARD_RUNE_POUCH), payload);
		}
		if (dispatch.contains(SourceKind.EQUIPMENT) && previous.equipmentKnown && n.equipmentKnown)
		{
			if (previous.ammoReal && n.ammoEmpty) { start(a, 114, SourceKind.EQUIPMENT, 20, Subject.resource(ResourceKind.AMMO_SLOT), Payload.EMPTY); }
			if (previous.ammoEmpty && n.ammoReal)
			{
				Edge edge = start(a, 115, SourceKind.EQUIPMENT, 12, Subject.resource(ResourceKind.AMMO_SLOT), Payload.EMPTY);
				edge.value = n.ammoId; edge.second = n.ammoQuantity;
			}
			if (previous.realHand && n.clearHands) { start(a, 116, SourceKind.EQUIPMENT, 20, Subject.unit(), Payload.EMPTY); }
			if (previous.ringCharge >= 2 && n.ringCharge == 1)
			{
				Edge edge = start(a, 124, SourceKind.EQUIPMENT, 10, Subject.item(ItemFamily.RING_OF_DUELING), Payload.EMPTY);
				edge.value = n.ringQuantity;
			}
		}
		if (!dispatch.contains(SourceKind.INVENTORY) || !previous.inventoryKnown || !n.inventoryKnown || a.items.contentsCauseSerial <= 0) { return; }
		if (previous.lantern == 1 && n.lantern == 2) { start(a, 121, SourceKind.INVENTORY, 10, Subject.item(ItemFamily.OIL_LANTERN), Payload.EMPTY); }
		if (previous.waterskin == 1 && n.waterskin == 2) { start(a, 122, SourceKind.INVENTORY, 10, Subject.resource(ResourceKind.STANDARD_WATERSKIN), Payload.EMPTY); }
		if (previous.prayerBottles > 0 && n.prayerBottles > 0 && n.prayerBottles < previous.prayerBottles
			&& n.prayerDoses == previous.prayerDoses && n.prayerDoses > 0 && n.prayerDoses <= 112)
		{
			Edge edge = start(a, 125, SourceKind.INVENTORY, 10, Subject.item(ItemFamily.STANDARD_PRAYER_POTION), Payload.EMPTY);
			edge.value = n.prayerBottles; edge.second = n.prayerDoses;
		}
		presence(a, 127, previous.horse, n.horse, 8, ItemFamily.TOY_HORSE, n.horseId);
		presence(a, 129, previous.cake, n.cake, 8, ItemFamily.DWARVEN_ROCK_CAKE, n.cakeId);
		presence(a, 130, previous.snack, n.snack, 8, ItemFamily.SMALL_COMFORT_SNACK, n.snackId);
		presence(a, 135, previous.clue, n.clue, 10, ItemFamily.BEGINNER_CLUE, 23182);
	}

	private void presence(AdditionalSnapshot a, int rule, int old, int current, int life, ItemFamily family, int id)
	{
		if (old == 0 && current == 1)
		{
			Edge edge = start(a, rule, SourceKind.INVENTORY, life, Subject.item(family), Payload.EMPTY); edge.value = id;
		}
	}

	private Edge start(AdditionalSnapshot a, int rule, SourceKind source, int life, Subject subject, Payload payload)
	{
		long cause = source == SourceKind.INVENTORY ? a.items.contentsCauseSerial : 0;
		Edge edge = edge(a, rule, source, cause > 0 ? cause : ++serial, a.tick, life, subject, payload);
		edges[rule - FIRST] = edge; return edge;
	}

	private static Edge edge(AdditionalSnapshot a, int rule, SourceKind source, long serial, long tick,
		int life, Subject subject, Payload payload)
	{
		long claims = AdditionalSceneContext.CLAIM_ITEM_FORM | (rule == 106 ? AdditionalSceneContext.CLAIM_UI_EDGE : 0)
			| (rule == 123 ? AdditionalSceneContext.CLAIM_NUMERIC : 0);
		return new Edge(new AdditionalSceneContext(rule, source, serial, serial, tick, tick + life, a.scene,
			a.presenceEpoch, subject, claims, payload));
	}

	private void confirmEdges(ScenarioSnapshot f, AdditionalSnapshot a, ItemState n)
	{
		for (int i = 0; i < edges.length; i++)
		{
			Edge edge = edges[i];
			if (edge == null || f.tick == edge.context.occurrenceTick) { continue; }
			edges[i] = null;
			Opportunity opportunity = new Opportunity(edge);
			// These edges confirm family membership; bind the current named member only on confirmation.
			if (edge.context.ruleIndex == 127 || edge.context.ruleIndex == 130)
			{ opportunity.value = edge.context.ruleIndex == 127 ? n.horseId : n.snackId; }
			if (f.tick != edge.context.occurrenceTick + 1 || !current(opportunity, f, a)) { continue; }
			if (edge.context.ruleIndex == 127 || edge.context.ruleIndex == 130)
			{
				int id = opportunity.value;
				String name = a.items.name(id);
				if (name.isEmpty()) { continue; }
				Payload payload = new Payload(Branch.ORDINARY, ActivityKind.UNKNOWN, name, -1, -1, id, -1, -1, -1, null, false);
				AdditionalSceneContext old = opportunity.context;
				opportunity = new Opportunity(new AdditionalSceneContext(old.ruleIndex, old.sourceKind, old.sourceSerial,
					old.causeSerial, old.occurrenceTick, old.originalDeadline, old.scene, old.presenceEpoch, old.subject,
					old.permittedClaimMask, payload)); opportunity.value = id;
			}
			opportunities[i] = opportunity;
		}
	}

	private void numeric(ScenarioSnapshot f, AdditionalSnapshot a)
	{
		if (!a.sourceKnown(SourceKind.NUMERIC) || !previousNumericKnown)
		{ clearRecoveries(); weightHighHandled = a.numeric.weight() >= 30; return; }
		AdditionalNumericSnapshot n = a.numeric, p = previousNumeric;
		int run = n.energy();
		if (run < 0 || p.energy() < 0) { energy = null; }
		else
		{
			if (p.energy() > 1000 && run <= 1000) { energy = new Recovery(++serial, f.tick, 180, null, 10000); }
			energy = recover(a, 131, energy, run, run == 10000, Subject.recovery(RecoveryKind.RUN_ENERGY, null));
		}
		int carried = n.weight();
		if (carried == AdditionalNumericSnapshot.UNKNOWN || p.weight() == AdditionalNumericSnapshot.UNKNOWN) { weight = null; }
		else
		{
			if (carried < 30) { weightHighHandled = false; }
			if (weight == null && !weightHighHandled && carried >= 30)
			{ weight = new Recovery(++serial, f.tick, 60, null, 0); weightHighHandled = true; }
			weight = recover(a, 132, weight, carried, carried <= 0, Subject.unit());
		}
		int real = n.level(Skill.PRAYER), points = n.boosted(Skill.PRAYER);
		if (real <= 0 || points < 0 || real != p.level(Skill.PRAYER) || p.boosted(Skill.PRAYER) < 0) { prayer = null; }
		else
		{
			if (p.boosted(Skill.PRAYER) > real / 4 && points <= real / 4) { prayer = new Recovery(++serial, f.tick, 60, Skill.PRAYER, real); }
			if (prayer != null && prayer.real != real) { prayer = null; }
			prayer = recover(a, 133, prayer, points, points == real, Subject.recovery(RecoveryKind.PRAYER, null));
		}
		if (restored != null && (n.level(restored.skill) != restored.real || n.boosted(restored.skill) < 0)) { restored = null; }
		if (restored == null)
		{
			for (Skill skill : RESTORABLE)
			{
				int level = n.level(skill), current = n.boosted(skill);
				if (level > 0 && level == p.level(skill) && p.boosted(skill) >= level && current >= 0 && current < level)
					{ restored = new Recovery(++serial, f.tick, 180, skill, level); break; }
			}
		}
		if (restored != null)
		{
			int current = n.boosted(restored.skill);
			restored = recover(a, 134, restored, current, current == restored.real, Subject.recovery(RecoveryKind.SKILL, restored.skill));
		}
	}

	private Recovery recover(AdditionalSnapshot a, int rule, Recovery arm, int value, boolean complete, Subject subject)
	{
		if (arm == null || a.tick - arm.start > arm.window) { return null; }
		if (!complete) { arm.first = -1; return arm; }
		if (arm.first < 0 || arm.value != value) { arm.first = a.tick; arm.value = value; return arm; }
		if (a.tick == arm.first + 1)
		{
			long number = arm.cause;
			Payload payload = new Payload(Branch.FULL, ActivityKind.UNKNOWN, "", value, arm.real, -1, -1, -1, -1, null, false);
			Opportunity opportunity = new Opportunity(new AdditionalSceneContext(rule, SourceKind.NUMERIC, number, number,
				arm.first, arm.first + 10, a.scene, a.presenceEpoch, subject, AdditionalSceneContext.CLAIM_NUMERIC, payload));
			opportunity.value = value; opportunity.second = arm.real; opportunity.armDeadline = arm.start + arm.window + 1;
			opportunities[rule - FIRST] = opportunity; return null;
		}
		return arm;
	}

	private static boolean current(Opportunity o, ScenarioSnapshot f, AdditionalSnapshot a)
	{
		AdditionalSceneContext c = o.context;
		if (!base(f, a) || !a.scene.equals(c.scene) || a.presenceEpoch != c.presenceEpoch || a.items.guardedUi() || !a.sourceKnown(SourceKind.UI)
			|| !a.sourceKnown(c.sourceKind)) { return false; }
		SharedItemSummary items = a.items;
		switch (c.ruleIndex)
		{
			case 106: return a.sourceKnown(SourceKind.UI) && items.inventoryMultiset.known && o.bag != null
				&& o.bag.equals(items.inventoryMultiset) && items.visibilityEpoch == o.visibility;
			case 114: return empty(items, AMMO);
			case 115: return real(items, AMMO) && items.id(AMMO) == o.value && items.quantity(AMMO) == o.second;
			case 116: return empty(items, WEAPON) && empty(items, SHIELD);
			case 118: return a.sourceKnown(SourceKind.GAME_MESSAGE) && a.sourceKnown(SourceKind.SPAM_MESSAGE);
			case 121: return items.count(4537) == 0 && items.count(4539) > 0;
			case 122: return family(items, 1823, 1825, 1827, 1829) == 0 && items.count(1831) > 0;
			case 123: return c.sourceKind == SourceKind.VARBIT && c.sourceSerial == c.causeSerial
				&& c.permittedClaimMask == (AdditionalSceneContext.CLAIM_ITEM_FORM | AdditionalSceneContext.CLAIM_NUMERIC)
				&& c.subject.type == Subject.Type.RESOURCE_KIND && c.subject.resourceKind == ResourceKind.STANDARD_RUNE_POUCH
				&& c.payload.currentLive && c.payload.definitionId == 12791 && pouchState(a) == 0
				&& items.pouchAssociationRevision == c.payload.entitySerial && items.pouchDomainRevision == c.payload.activitySerial
				&& items.pouchContentSerial == c.sourceSerial && items.pouchContentTick == c.occurrenceTick
				&& c.occurrenceTick <= Long.MAX_VALUE - 12 && c.originalDeadline == c.occurrenceTick + 12
				&& items.pouchSampleTick >= c.occurrenceTick + 1 && items.pouchSampleTick <= f.tick;
			case 124: return real(items, RING) && items.id(RING) == 2566 && items.quantity(RING) == o.value;
			case 125: return items.prayerBottlesKnown && items.prayerBottleCount == o.value && items.prayerDoseTotal == o.second;
			case 127: return family(items, 2520, 2522, 2524, 2526) == 1 && (o.value < 0 || items.count(o.value) > 0);
			case 129: return family(items, 7509, 7510) == 1;
			case 130: return family(items, 2309, 1973) == 1 && (o.value < 0 || items.count(o.value) > 0);
			case 135: return items.count(23182) > 0;
			case 128: return c.sourceKind == SourceKind.GAME_MESSAGE && ownKitten(a, c.payload)
				&& (c.payload.value == 1 || c.payload.value == 2);
			case 131: return a.numeric.energy() == 10000;
			case 132: return a.numeric.weight() != AdditionalNumericSnapshot.UNKNOWN && a.numeric.weight() <= 0;
			case 133: return a.numeric.level(Skill.PRAYER) == o.second && a.numeric.boosted(Skill.PRAYER) == o.second;
			case 134: return c.subject.skill != null && a.numeric.level(c.subject.skill) == o.second
				&& a.numeric.boosted(c.subject.skill) == o.second;
			default: return true;
		}
	}

	private static boolean ownKitten(AdditionalSnapshot a, Payload payload)
	{
		return a.sourceKnown(SourceKind.FOLLOWER) && a.followerKnown == Truth.TRUE && payload.currentLive
			&& payload.entitySerial > 0 && a.followerSerial == payload.entitySerial
			&& payload.definitionId >= 5591 && payload.definitionId <= 5597 && a.followerDefinitionId == payload.definitionId;
	}

	private static int pouchState(AdditionalSnapshot a)
	{
		SharedItemSummary items = a.items;
		if (!a.sourceKnown(SourceKind.VARBIT) || !a.sourceKnown(SourceKind.INVENTORY) || !items.pouchKnown
			|| !items.pouchAligned || items.standardPouchAssociation != Truth.TRUE
			|| items.carriedPouchAssociation() != Truth.TRUE || items.pouchSampleTick < 0 || items.pouchSampleTick > a.tick)
		{ return -1; }
		return items.pouchEmpty ? 0 : 1;
	}

	private static boolean real(SharedItemSummary items, int slot)
	{
		return items.equipmentKnown && items.id(slot) >= 0 && items.quantity(slot) > 0 && items.form(slot) == ItemForm.REAL;
	}
	private static boolean empty(SharedItemSummary items, int slot)
	{
		return items.equipmentKnown && items.id(slot) == SharedItemSummary.EMPTY_ID && items.quantity(slot) == 0 && items.form(slot) == ItemForm.EMPTY;
	}
	private static int family(SharedItemSummary items, int... ids)
	{
		boolean present = false;
		for (int id : ids) { long count = items.count(id); if (count < 0) { return -1; } present |= count > 0; }
		return present ? 1 : 0;
	}
	private static int first(SharedItemSummary items, int... ids)
	{
		for (int id : ids) { if (items.count(id) > 0) { return id; } }
		return -1;
	}

	private static final class ItemState
	{
		final boolean guarded, inventoryKnown, equipmentKnown, ammoReal, ammoEmpty, realHand, clearHands;
		final Truth bankVisible;
		final int ammoId, ammoQuantity, ringCharge, ringQuantity, lantern, waterskin;
		final int prayerBottles, prayerDoses, horse, cake, snack, clue, horseId, cakeId, snackId;
		final int pouch;
		final long pouchAssociation, pouchDomain, pouchSerial, pouchTick, pouchSample;
		ItemState(AdditionalSnapshot a)
		{
			SharedItemSummary i = a.items;
			guarded = i.guardedUi() || !a.sourceKnown(SourceKind.UI); bankVisible = a.sourceKnown(SourceKind.UI) ? i.bankVisible : Truth.UNKNOWN;
			inventoryKnown = a.sourceKnown(SourceKind.INVENTORY) && i.inventoryKnown;
			equipmentKnown = a.sourceKnown(SourceKind.EQUIPMENT) && i.equipmentKnown;
			ammoReal = equipmentKnown && real(i, AMMO); ammoEmpty = equipmentKnown && empty(i, AMMO);
			ammoId = i.id(AMMO); ammoQuantity = i.quantity(AMMO);
			realHand = equipmentKnown && (real(i, WEAPON) || real(i, SHIELD));
			clearHands = equipmentKnown && empty(i, WEAPON) && empty(i, SHIELD);
			ringQuantity = i.quantity(RING); int ring = i.id(RING);
			ringCharge = equipmentKnown && real(i, RING) && ring >= 2552 && ring <= 2566 && (ring - 2552) % 2 == 0 ? 8 - (ring - 2552) / 2 : -1;
			int unlit = family(i, 4537), lit = family(i, 4539);
			lantern = unlit < 0 || lit < 0 ? -1 : unlit == 1 && lit == 0 ? 1 : unlit == 0 && lit == 1 ? 2 : 0;
			int positive = family(i, 1823, 1825, 1827, 1829), zero = family(i, 1831);
			waterskin = positive < 0 || zero < 0 ? -1 : positive == 1 && zero == 0 ? 1 : positive == 0 && zero == 1 ? 2 : 0;
			prayerBottles = i.prayerBottlesKnown ? i.prayerBottleCount : -1; prayerDoses = i.prayerBottlesKnown ? i.prayerDoseTotal : -1;
			horse = family(i, 2520, 2522, 2524, 2526); cake = family(i, 7509, 7510); snack = family(i, 2309, 1973); clue = family(i, 23182);
			horseId = first(i, 2520, 2522, 2524, 2526); cakeId = first(i, 7509, 7510); snackId = first(i, 2309, 1973);
			pouch = pouchState(a); pouchAssociation = i.pouchAssociationRevision; pouchDomain = i.pouchDomainRevision;
			pouchSerial = i.pouchContentSerial; pouchTick = i.pouchContentTick; pouchSample = i.pouchSampleTick;
		}
	}
	private static class Edge
	{
		final AdditionalSceneContext context;
		int value = -1, second = -1;
		long visibility;
		InventoryMultiset bag;
		Edge(AdditionalSceneContext context) { this.context = context; }
	}
	private static final class Opportunity extends Edge
	{
		boolean offered;
		long armDeadline = Long.MAX_VALUE;
		Opportunity(AdditionalSceneContext context) { super(context); }
		Opportunity(Edge edge) { super(edge.context); value = edge.value; second = edge.second; visibility = edge.visibility; bag = edge.bag; }
	}
	private static final class BankVisit
	{
		final long entryTick, uiSerial;
		final InventoryMultiset entry;
		BankVisit(long tick, long serial, InventoryMultiset entry) { entryTick = tick; uiSerial = serial; this.entry = entry; }
	}
	private static final class Recovery
	{
		final long cause, start;
		final int window, real;
		final Skill skill;
		long first = -1;
		int value;
		Recovery(long cause, long start, int window, Skill skill, int real)
		{ this.cause = cause; this.start = start; this.window = window; this.skill = skill; this.real = real; }
	}
}
