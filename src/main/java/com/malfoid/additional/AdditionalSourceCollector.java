package com.malfoid.additional;

import com.malfoid.additional.AdditionalNumericSnapshot.Watch;
import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.ItemForm;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import com.malfoid.scenario.ScenarioSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatMessageType;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MessageNode;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;

/** Unwired client-thread collector. The central observer alone may call it; no subscribers or polling in render. */
public final class AdditionalSourceCollector
{
	public static final int MAX_METADATA = 64, MAX_RESOLUTIONS = 8, MAX_SEEN_MESSAGES = 128;
	private static final int[] ROOTS = {InterfaceID.Bankmain.UNIVERSE, InterfaceID.BankDepositbox.UNIVERSE,
		InterfaceID.Trademain.UNIVERSE, InterfaceID.Tradeconfirm.UNIVERSE, InterfaceID.Shopmain.UNIVERSE,
		InterfaceID.Worldmap.UNIVERSE, InterfaceID.Fairyrings.ROOT_RECT0, InterfaceID.TrailSlidepuzzle.UNIVERSE};
	private static final UiKind[] UI_KINDS = {UiKind.BANK, UiKind.DEPOSIT, UiKind.TRADE, UiKind.TRADE_CONFIRM,
		UiKind.SHOP, UiKind.WORLD_MAP, UiKind.FAIRY_RING, UiKind.CLUE_PUZZLE};
	private static final ChatMessageType[] MESSAGE_TYPES = {ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM, ChatMessageType.MESBOX};
	private static final Map<ChatMessageType, Map<String, Rule>> EXACT = exactRules();
	private static final java.util.Set<OutcomeKind> AUDITED_OUTCOMES = auditedOutcomes();
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern CHEMISTRY_HELP = Pattern.compile("Your amulet of chemistry helps you create a [0-9]-dose potion\\. (?:It has (?:[0-9]|one) charges? left\\.|It then crumbles to dust\\.)");
	private static final Pattern BOUNTY_HELP = Pattern.compile("Your amulet of bounty saves some seeds for you\\. It has [0-9] charges? left\\.");
	// Narrow bounded subsets of the pinned whole CrowdsourcingThieving/Woodcutting matchers.
	private static final Pattern PICKPOCKET_SUCCESS = Pattern.compile("You pick [A-Za-z0-9][A-Za-z0-9 .'-]{0,63}'s pocket\\.");
	private static final Pattern PICKPOCKET_FAILURE = Pattern.compile("You fail to pick [A-Za-z0-9][A-Za-z0-9 .'-]{0,63}'s pocket\\.");
	private static final Pattern LOG_GATHERED = Pattern.compile("You get (?:some|an)[\\w ]{1,64}logs?\\.");
	private static final Pattern DODGY_PROTECTION = Pattern.compile("Your dodgy necklace protects you\\. It has ([1-9]|10) charges? left\\.");
	private static final Pattern DODGY_CHECK = Pattern.compile("Your dodgy necklace has ([0-9]+) charges? left\\.");
	private static final Pattern GOLEM_GIFT = Pattern.compile("As you complete the golem it leaves a gift (?:on the ground|in your gem (?:bag|sack)) for you: 1 x (?:Uncut diamond|Uncut emerald|Uncut ruby|Uncut sapphire|Jeweller's chisel)\\.?");
	private static final Pattern BIRDHOUSE_DISMANTLED = Pattern.compile("You dismantle and discard the trap, retrieving (?:(?:a|\\d{1,2}) nests?, )?10 dead birds, \\d{1,3} feathers and \\d,?\\d{1,3} Hunter XP\\.");
	private static final Pattern COLLECTION_LOG_RAW = Pattern.compile("New item added to your collection log: <col=ef1020>([^<>@\\p{Cntrl}]{1,96})</col>");
	private static final Pattern ACHIEVEMENT_RAW = Pattern.compile("Congratulations, you've completed (an easy|a medium|a hard|an elite|a master|a grandmaster) combat task: @ach_comp@([^<>@\\p{Cntrl}]{1,120})</col>\\.");
	private static final Pattern ACHIEVEMENT_POINTS_RAW = Pattern.compile("Congratulations, you've completed a grandmaster combat task: @ach_comp@([^<>@\\p{Cntrl}]{1,120})</col> \\(6 points\\)\\.");
	private static final Pattern SEPULCHRE_RAW = Pattern.compile("You have completed Floor ([1-5]) of the Hallowed Sepulchre! Total completions: <col=ff0000>([1-9][0-9]{0,9}|[1-9][0-9]{0,2}(?:,[0-9]{3}){1,3})</col>\\.");
	private static final Pattern TZHAAR_RAW = Pattern.compile("<col=ef1020>Wave: ([1-9][0-9]?)</col>");
	// Two pinned full GAME Theatre bodies; generic duration/PB queries remain unsupported.
	private static final Pattern THEATRE_PB_RAW = Pattern.compile("Wave 'The Final Challenge' \\((Normal|Entry) Mode\\) complete!<br>Duration: <col=ff0000>"
		+ "((?:0|[1-9][0-9]{0,9}):[0-5][0-9](?:\\.[0-9]{1,2})?)</col><br>Theatre of Blood completion time: <col=ff0000>"
		+ "((?:0|[1-9][0-9]{0,9}):[0-5][0-9](?:\\.[0-9]{2})?)</col> \\(new personal best\\)");
	private static final Pattern PATCH_TREATED = Pattern.compile("You treat the [A-Za-z][A-Za-z0-9 '-]{0,63} with (?:ultra|super|)compost\\.");
	// The pinned test proves this one raw style token/subtype, not a general style-token remover.
	private static final String THRALL_RAW_MESSAGE = "@mes_hl_mag@You resurrect a greater zombified thrall.</col>";
	private final Client client;
	private final List<PersonalOutcomeFact> facts = new ArrayList<>(AdditionalSnapshot.MAX_FACTS);
	private final EnumSet<SourceKind> unknown = EnumSet.noneOf(SourceKind.class);
	private final EnumSet<SourceKind> changed = EnumSet.allOf(SourceKind.class);
	private final Map<Integer, Metadata> metadata = new LinkedHashMap<>();
	private final RunePouchSource pouch = new RunePouchSource();
	private final UiState[] ui = new UiState[ROOTS.length];
	private final long[] seen = new long[MAX_SEEN_MESSAGES];
	private final int[] seenSemantics = new int[MAX_SEEN_MESSAGES];
	// Optional typed descriptor widens the SAME 128 records; never a second history or raw transcript.
	private final String[] seenReportDetails = new String[MAX_SEEN_MESSAGES];
	private final int[] ids = new int[SharedItemSummary.SLOT_COUNT], quantities = new int[SharedItemSummary.SLOT_COUNT];
	private int seenSize, metadataResolutions;
	private int seenNext, seenGame = -1, seenSpam = -1, seenBox = -1;
	private long sequence, tick, epoch, visibilityEpoch, contentsCauseSerial;
	private SceneKey scene;
	private boolean witnessed, baseline = true, inventoryDirty = true, equipmentDirty = true, numericDirty = true, varDirty = true, reducerOverflow;
	private int[] watched;
	private AdditionalSnapshot last;
	private List<AdditionalUiSnapshot> capturedUi;
	private SharedItemSummary cachedItems;
	private AdditionalNumericSnapshot cachedNumeric;
	private boolean unresolvedItemMetadata;
	private int cachedEnergyRead = AdditionalNumericSnapshot.UNKNOWN, cachedWeightRead = AdditionalNumericSnapshot.UNKNOWN;
	// M162 source lease uses its existing one-record reservation; no input-copy object is retained.
	private Truth tzhaarKnown = Truth.UNKNOWN;
	private ActivityKind tzhaarActivity = ActivityKind.UNKNOWN;
	private long tzhaarActivitySerial, tzhaarWaveSerial;
	private int tzhaarWave = -1, tzhaarHighestWave, tzhaarViewId = -1, tzhaarPlane = -1;
	public AdditionalSourceCollector(Client client)
	{
		this.client = Objects.requireNonNull(client);
		for (int i = 0; i < ui.length; i++) { ui[i] = new UiState(); }
		Arrays.fill(ids, SharedItemSummary.UNKNOWN_ID); Arrays.fill(quantities, -1);
	}
	/** Call before callbacks for this original committed interval, including immediate presence changes. */
	public void beginInterval(long occurrenceTick, SceneKey scene, long presenceEpoch, boolean presentWitness)
	{
		Objects.requireNonNull(scene);
		if (this.scene != null && this.scene.sessionGeneration != scene.sessionGeneration)
		{ seenSize = seenNext = 0; seenGame = seenSpam = seenBox = -1; Arrays.fill(seenReportDetails, null); }
		boolean discontinuity = this.scene == null || !this.scene.equals(scene) || epoch != presenceEpoch
			|| tick != occurrenceTick && occurrenceTick != tick + 1 || witnessed != presentWitness;
		if (discontinuity) { reset(); }
		else if (this.tick != occurrenceTick) { last = null; capturedUi = null; metadataResolutions = 0; changed.clear(); }
		this.tick = occurrenceTick; this.scene = scene; epoch = presenceEpoch; witnessed = presentWitness;
		pouch.beginInterval(occurrenceTick, scene, presenceEpoch, presentWitness);
	}
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		pouch.onItemContainerChanged(event);
		if (event.getContainerId() == InventoryID.INV) { inventoryDirty = true; changed.add(SourceKind.INVENTORY); }
		if (event.getContainerId() == InventoryID.WORN) { equipmentDirty = true; changed.add(SourceKind.EQUIPMENT); }
	}
	public boolean onVarbitChanged(VarbitChanged event) { varDirty = true; return pouch.onVarbitChanged(event); }
	/** One pinned on-load invocation; CALL690/RETURN precede the root RETURN and loaded callback. */
	public boolean onPuzzleScriptPre(ScriptPreFired event)
	{
		if (event == null || event.getScriptId() != 688 && event.getScriptId() != 690) { return false; }
		UiState state = puzzleState();
		if (event.getScriptId() == 690)
		{
			if (!state.puzzleInitPending) { return false; }
			if (state.puzzleInitPhase == 1 && puzzleInitScope(state) && event.getScriptEvent() == null)
			{ state.puzzleInitPhase = 2; return false; }
			retirePuzzle(state); return true;
		}
		if (event.getScriptEvent() == null)
		{
			// Pinned981 needs no resumed Pre. Only one optional same-invocation resume is admissible.
			if (state.puzzleInitPending && state.puzzleInitPhase == 3 && puzzleInitScope(state))
			{ state.puzzleInitPhase = 4; return false; }
			retirePuzzle(state); return true;
		}
		boolean newView = state.visibility == Truth.FALSE && (state.serial == 0
			|| state.closeTick >= 0 && state.closeTick == state.puzzleCloseTick);
		retirePuzzle(state);
		state.puzzleInvalid = !newView;
		Widget root = client.getWidget(0x01320000);
		state.puzzleInitPending = !puzzleSourceUnknown() && puzzleArguments(event.getScriptEvent().getArguments())
			&& root != null && root.getId() == 0x01320000 && event.getScriptEvent().getSource() == root
			&& puzzleArguments(root.getOnLoadListener());
		state.puzzleInitTick = tick; state.puzzleInitPhase = state.puzzleInitPending ? 1 : 0;
		return true;
	}
	public boolean onPuzzleScriptPost(ScriptPostFired event)
	{
		if (event == null || event.getScriptId() != 688 && event.getScriptId() != 690) { return false; }
		UiState state = puzzleState();
		if (event.getScriptId() == 690)
		{
			if (!state.puzzleInitPending) { return false; }
			if (state.puzzleInitPhase == 2 && puzzleInitScope(state))
			{ state.puzzleInitPhase = 3; return false; }
			retirePuzzle(state); return true;
		}
		boolean paired = state.puzzleInitPending && (state.puzzleInitPhase == 3 || state.puzzleInitPhase == 4)
			&& puzzleInitScope(state);
		int selector = paired ? client.getVarpValue(261) : -1;
		if (!paired || !puzzleSelectorKnown(selector) || !currentPuzzleKind(selector))
		{ retirePuzzle(state); return true; }
		state.puzzleSelector = selector; state.puzzleInitialized = true; state.puzzleInitPhase = 5;
		changed.add(SourceKind.UI);
		return state.puzzleInvalid;
	}
	private boolean puzzleInitScope(UiState state)
	{ return state.puzzleInitTick == tick && !puzzleSourceUnknown(); }
	/** An unload is only a close witness; the shared committed root copy must also close. */
	public boolean onPuzzleWidgetClosed(WidgetClosed event)
	{
		if (event == null || event.getGroupId() != 306) { return false; }
		UiState state = puzzleState();
		if (puzzleSourceUnknown() || !event.isUnload()
			|| event.getModalMode() != 0 && event.getModalMode() != 1 && event.getModalMode() != 3)
		{ retirePuzzle(state); state.puzzleCloseTick = -1; return true; }
		if (state.visibility == Truth.TRUE && state.serial > 0)
		{ state.puzzleCloseTick = tick; changed.add(SourceKind.UI); return false; }
		if (state.visibility == Truth.FALSE && state.puzzleCloseVerified) { return false; }
		// A later real unload can establish a new closed boundary, never recover the old offer.
		Widget root = client.getWidget(0x01320000);
		if (state.visibility == Truth.FALSE && state.serial > 0 && (root == null || root.isHidden()))
		{ state.puzzleCloseTick = state.closeTick = tick; }
		retirePuzzle(state); return true;
	}
	/** Only the one loaded callback completing the matched on-load invocation is positive metadata. */
	public boolean onPuzzleWidgetLoaded(WidgetLoaded event)
	{
		if (event == null || event.getGroupId() != 306) { return false; }
		UiState state = puzzleState();
		if (state.puzzleInitPending && state.puzzleInitPhase == 5 && state.puzzleInitialized && !state.puzzleInvalid
			&& state.visibility == Truth.FALSE && puzzleInitScope(state) && currentPuzzleKind(state.puzzleSelector))
		{
			Widget root = client.getWidget(0x01320000);
			if (root != null && puzzleArguments(root.getOnLoadListener()))
			{ state.puzzleInitPending = false; state.puzzleInitPhase = 6; changed.add(SourceKind.UI); return false; }
		}
		retirePuzzle(state); return true;
	}
	/** Client-thread current guard also retires an unissued open arm; restoration cannot revive it. */
	public boolean refreshPuzzleLease()
	{
		UiState state = puzzleState();
		if (puzzleSourceUnknown()) { retirePuzzle(state); return false; }
		if (state.puzzleCloseVerified)
		{
			Widget root = client.getWidget(0x01320000);
			if (root != null && !root.isHidden()) { retirePuzzle(state); return false; }
		}
		else if (state.visibility == Truth.TRUE && state.puzzleInitialized && !state.puzzleInvalid)
		{
			Widget root = client.getWidget(0x01320000);
			boolean pendingClose = state.puzzleCloseTick == tick && (root == null || root.isHidden());
			if (!pendingClose && !currentPuzzleKind(state.puzzleSelector)) { retirePuzzle(state); return false; }
		}
		return !state.puzzleInvalid;
	}
	private UiState puzzleState() { return ui[7]; }
	private boolean puzzleSourceUnknown() { return sourceInvalidated(SourceKind.UI) || client.getGameState() != GameState.LOGGED_IN; }
	private void retirePuzzle(UiState state)
	{
		boolean change = state.puzzleInitPending || state.puzzleInitialized || state.puzzleCloseVerified || !state.puzzleInvalid;
		state.puzzleInitPending = state.puzzleInitialized = state.puzzleCloseVerified = false; state.puzzleInitPhase = 0;
		state.puzzleInvalid = true; if (change) { changed.add(SourceKind.UI); }
	}
	private static boolean puzzleArguments(Object[] args)
	{
		return args != null && args.length == 5 && Integer.valueOf(688).equals(args[0])
			&& Integer.valueOf(0x01320004).equals(args[1]) && Integer.valueOf(0x01320002).equals(args[2])
			&& Integer.valueOf(0x01320003).equals(args[3]) && Integer.valueOf(0x01320001).equals(args[4]);
	}
	private static boolean puzzleSelectorKnown(int selector) { return selector >= 1 && selector <= 6 || selector == 9; }
	private boolean currentPuzzleKind(int selector)
	{
		if (!puzzleSelectorKnown(selector) || client.getVarpValue(261) != selector) { return false; }
		Widget root = client.getWidget(0x01320000), standard = client.getWidget(0x01320002), mm = client.getWidget(0x01320003);
		return root != null && root.getId() == 0x01320000 && !root.isHidden()
			&& standard != null && standard.getId() == 0x01320002 && standard.getParentId() == 0x01320000 && !standard.isHidden()
			&& mm != null && mm.getId() == 0x01320003 && mm.getParentId() == 0x01320000 && mm.isSelfHidden();
	}
	public void onStatChanged(StatChanged event) { numericDirty = true; changed.add(SourceKind.NUMERIC); }
	/** Shared-index copied ground occurrence, assigned a global ingress serial before selection. */
	public boolean onMarkSpawn(MarkOfGraceSpawn copy)
	{
		if (copy == null) { return false; }
		changed.add(SourceKind.GROUND_ITEM_SPAWN);
		if (!witnessed || baseline || reducerOverflow || unknown.contains(SourceKind.GROUND_ITEM_SPAWN)
			|| scene == null || !scene.equals(copy.scene) || epoch != copy.presenceEpoch || tick != copy.occurrenceTick) { return false; }
		if (facts.size() >= AdditionalSnapshot.MAX_FACTS || sequence == Long.MAX_VALUE)
		{ invalidateGroundMark(); return false; }
		long serial = ++sequence;
		facts.add(new PersonalOutcomeFact(OutcomeKind.MARK_OF_GRACE, SourceKind.GROUND_ITEM_SPAWN,
			serial, serial, copy.occurrenceTick, copy.occurrenceTick + 12, copy.scene, copy.presenceEpoch,
			true, false, false, true, true, false,
			new PersonalOutcomeFact.Payload(Branch.ORDINARY, ActivityKind.UNKNOWN, "", -1, -1,
				ItemID.GRACE, -1, copy.entitySerial, -1, copy.position, true)));
		return true;
	}
	/** Negative current lease uses the same source invalidation/fact removal path as other inputs. */
	public void invalidateGroundMark() { invalidate(SourceKind.GROUND_ITEM_SPAWN); }
	/** The observer compares the flat follower tuple with its existing previous envelope. */
	public void markFollowerChanged() { changed.add(SourceKind.FOLLOWER); }
	/** Immediate pre-paint invalidation for the open callback interval, without client reads. */
	public boolean sourceInvalidated(SourceKind source)
	{
		if (source == SourceKind.UNKNOWN || scene == null || !witnessed || baseline || reducerOverflow || unknown.contains(source)) { return true; }
		if (source == SourceKind.INVENTORY) { return inventoryDirty; }
		if (source == SourceKind.EQUIPMENT) { return equipmentDirty; }
		if (source == SourceKind.NUMERIC) { return numericDirty; }
		if (source == SourceKind.VARBIT || source == SourceKind.VARP) { return varDirty; }
		return false;
	}
	/** Dedicated dependencies: unrelated varbits do not revoke the carried pouch's source. */
	public boolean pouchSourceInvalidated()
	{
		return scene == null || !witnessed || baseline || reducerOverflow || inventoryDirty
			|| unknown.contains(SourceKind.UI) || unknown.contains(SourceKind.INVENTORY)
			|| cachedItems == null || !cachedItems.pouchKnown || !cachedItems.pouchAligned || pouch.invalidated();
	}
	public void invalidatePouchUi() { pouch.invalidateUi(); }
	/** Negative-only common current guard, including an unissued e -> e+1 edge. */
	public boolean refreshPouchLease()
	{
		if (pouchSourceInvalidated()) { pouch.invalidateUi(); return false; }
		if (!currentPouchUiClear()) { pouch.invalidateUi(); return false; }
		return !pouch.hasCurrentEmptyCause() || pouch.currentEmptyLeaseMatches(client, cachedItems);
	}
	/** Client-thread first-paint/continuation guard; render never calls a source getter. */
	public boolean currentPouchLeaseMatches(AdditionalSceneContext context)
	{
		if (pouchSourceInvalidated()) { pouch.invalidateUi(); return false; }
		if (!currentPouchUiClear()) { pouch.invalidateUi(); return false; }
		return pouch.currentLeaseMatches(client, cachedItems, context);
	}
	private boolean currentPouchUiClear()
	{
		if (client.getGameState() != GameState.LOGGED_IN || unknown.contains(SourceKind.UI)) { return false; }
		for (int i = 0; i < 4; i++)
		{
			Widget widget = client.getWidget(ROOTS[i]);
			if (widget != null && !widget.isHidden()) { return false; }
		}
		return true;
	}
	/** Client-thread pre-paint guard: one existing root probe, without advancing its episode or tick. */
	public boolean currentUiLeaseMatches(AdditionalSceneContext context)
	{
		if (context == null || context.sourceKind != SourceKind.UI || context.subject.type != AdditionalSceneContext.Subject.Type.WIDGET_KIND
			|| sourceInvalidated(SourceKind.UI) || !scene.equals(context.scene) || epoch != context.presenceEpoch
			|| tick < context.occurrenceTick) { return false; }
		UiKind expected = context.ruleIndex == 153 ? UiKind.WORLD_MAP : context.ruleIndex == 154 ? UiKind.SHOP
			: context.ruleIndex == 156 ? UiKind.FAIRY_RING : context.ruleIndex == 170 ? UiKind.CLUE_PUZZLE : UiKind.UNKNOWN;
		if (expected == UiKind.UNKNOWN || context.subject.uiKind != expected) { return false; }
		for (int i = 0; i < UI_KINDS.length; i++)
		{
			if (UI_KINDS[i] != expected) { continue; }
			UiState state = ui[i];
			if (state.baselineSnapshot || state.visibility != Truth.FALSE || state.serial != context.sourceSerial
				|| state.closeTick < 0 || state.closeTick > tick) { return false; }
			if (expected == UiKind.CLUE_PUZZLE && (!state.puzzleCloseVerified || state.puzzleInvalid
				|| state.openTick != context.occurrenceTick || !refreshPuzzleLease())) { return false; }
			Widget widget = client.getWidget(ROOTS[i]);
			return widget == null || widget.isHidden();
		}
		return false;
	}
	/** Client-thread factual scalar guard; first-paint expiry remains the consumer's separate check. */
	public boolean currentScalarLeaseMatches(AdditionalSceneContext context)
	{
		if (context == null || context.sourceKind != SourceKind.NUMERIC || sourceInvalidated(SourceKind.NUMERIC)
			|| cachedNumeric == null || !scene.equals(context.scene) || epoch != context.presenceEpoch || tick < context.occurrenceTick) { return false; }
		if (context.ruleIndex == 93 || context.ruleIndex == 131)
		{
			int energy = client.getEnergy();
			return energy >= 0 && energy <= 10000 && cachedNumeric.energy() >= 0 && cachedNumeric.energy() == energy;
		}
		return context.ruleIndex == 132 && cachedNumeric.weight() != AdditionalNumericSnapshot.UNKNOWN && cachedNumeric.weight() == client.getWeight();
	}
	/** An unrelated VARBIT dirty callback is not a collection-option contradiction. */
	public boolean currentCollectionLogLeaseMatches(AdditionalSceneContext context)
	{ return context != null && context.ruleIndex == 142 && context.sourceKind == SourceKind.GAME_MESSAGE
		&& currentReportScope(context) && messageSourceKnown(SourceKind.GAME_MESSAGE) && collectionOption() == 1; }
	/** Negative-only: option restoration cannot recover a consumed pending fact. */
	public boolean refreshCollectionLogLease()
	{
		if (collectionOption() == 1) { return true; }
		if (facts.removeIf(fact -> fact.kind == OutcomeKind.COLLECTION_LOG_ENTRY)) { changed.add(SourceKind.GAME_MESSAGE); }
		return false;
	}
	private int collectionOption()
	{
		if (reducerOverflow || unknown.contains(SourceKind.VARBIT)) { return AdditionalNumericSnapshot.UNKNOWN; }
		VarbitComposition composition = client.getVarbit(Watch.COLLECTION_LOG_CHAT.id); int[] varps = client.getVarps();
		if (composition == null || varps == null || composition.getIndex() < 0 || composition.getIndex() >= varps.length
			|| composition.getLeastSignificantBit() < 0 || composition.getMostSignificantBit() < composition.getLeastSignificantBit()
			|| composition.getMostSignificantBit() >= 32) { return AdditionalNumericSnapshot.UNKNOWN; }
		return client.getVarbitValue(Watch.COLLECTION_LOG_CHAT.id);
	}
	/** Later pending floor terminals revoke wording without renewing its occurrence or expiry. */
	public boolean currentSepulchreLeaseMatches(AdditionalSceneContext context)
	{
		if (context == null || context.ruleIndex != 160 || !currentReportScope(context) || !messageSourceKnown(SourceKind.GAME_MESSAGE)) { return false; }
		for (PersonalOutcomeFact fact : facts)
		{ if (fact.kind == OutcomeKind.SEPULCHRE_FLOOR && fact.sourceSerial > context.sourceSerial) { return false; } }
		return true;
	}
	/** Observed activity transitions retire pending wave facts and their original current lease. */
	public boolean refreshTzhaarActivity(TzhaarActivityCopy copy)
	{
		boolean active = copy != null && copy.scopeMatches(tick, scene, epoch) && copy.active() && witnessed && !reducerOverflow;
		Truth next = active ? Truth.TRUE : copy != null && copy.scopeMatches(tick, scene, epoch) && copy.known == Truth.FALSE ? Truth.FALSE : Truth.UNKNOWN;
		boolean same = active && tzhaarActivitySerial > 0 && tzhaarActivity == copy.activity && tzhaarViewId == copy.viewId && tzhaarPlane == copy.plane;
		if (same && tzhaarKnown == Truth.TRUE) { return true; }
		if (!active && tzhaarKnown == next && tzhaarWaveSerial == 0) { return false; }
		facts.removeIf(fact -> fact.kind == OutcomeKind.TZHAAR_WAVE); changed.add(SourceKind.GAME_MESSAGE);
		tzhaarWave = -1; tzhaarWaveSerial = 0;
		if (active && tzhaarActivitySerial == 0)
		{
			tzhaarKnown = Truth.TRUE; tzhaarActivity = copy.activity; tzhaarViewId = copy.viewId; tzhaarPlane = copy.plane;
			tzhaarActivitySerial = ++sequence; tzhaarHighestWave = 0;
		}
		else { tzhaarKnown = same ? Truth.TRUE : active ? Truth.UNKNOWN : next; }
		// A transient loss preserves this same lease's max-wave discriminator. No occurrence/cause is renewed.
		return false;
	}
	public boolean currentTzhaarLeaseMatches(AdditionalSceneContext context, TzhaarActivityCopy copy)
	{
		return context != null && context.ruleIndex == 162 && currentReportScope(context) && messageSourceKnown(SourceKind.GAME_MESSAGE)
			&& copy != null && copy.scopeMatches(tick, scene, epoch) && copy.active() && tzhaarKnown == Truth.TRUE
			&& tzhaarActivity == copy.activity && tzhaarViewId == copy.viewId && tzhaarPlane == copy.plane
			&& context.payload.activity == tzhaarActivity && context.payload.activitySerial == tzhaarActivitySerial
			&& context.payload.value == tzhaarWave && context.sourceSerial == tzhaarWaveSerial;
	}
	public int retainedActivityRecords() { return 1; }
	/** Historical low-charge wording is revoked by either supported channel's newer contradiction. */
	public boolean currentDodgyLeaseMatches(AdditionalSceneContext context)
	{
		if (context == null || context.ruleIndex != 118 || context.subject.itemFamily != AdditionalTypes.ItemFamily.DODGY_NECKLACE
			|| (context.sourceKind != SourceKind.GAME_MESSAGE && context.sourceKind != SourceKind.SPAM_MESSAGE)
			|| (context.payload.value != 1 && context.payload.value != 2) || !currentReportScope(context)
			|| !messageSourceKnown(SourceKind.GAME_MESSAGE) || !messageSourceKnown(SourceKind.SPAM_MESSAGE)) { return false; }
		return !dodgyContradiction(facts, context) && (last == null || !dodgyContradiction(last.outcomes, context));
	}
	/** Input-only current owner probe; no follower object or copy is retained. */
	public boolean currentOwnCatLeaseMatches(AdditionalSceneContext context, OwnFollowerCopy copy)
	{
		if (context == null || context.ruleIndex != 128 || context.sourceKind != SourceKind.GAME_MESSAGE
			|| !context.payload.currentLive || (context.payload.value != 1 && context.payload.value != 2) || !currentReportScope(context)
			|| !messageSourceKnown(SourceKind.GAME_MESSAGE) || sourceInvalidated(SourceKind.FOLLOWER)
			|| last != null && !last.sourceKnown(SourceKind.FOLLOWER) || !currentKitten(copy)
			|| copy.incarnationSerial != context.payload.entitySerial || copy.resolvedDefinitionId != context.payload.definitionId) { return false; }
		return !catFedAfter(facts, context) && (last == null || !catFedAfter(last.outcomes, context));
	}
	/** Lets the observer cancel an unissued arm during the same callback interval. */
	public boolean hasPendingOwnCatFed(OwnFollowerCopy copy)
	{
		if (!currentKitten(copy)) { return false; }
		for (PersonalOutcomeFact fact : facts)
		{
			if (fact.kind == OutcomeKind.CAT_NEEDS_FOOD && fact.payload.value == 0 && copiedPersonalReport(fact)
				&& fact.payload.entitySerial == copy.incarnationSerial && fact.payload.definitionId == copy.resolvedDefinitionId) { return true; }
		}
		return false;
	}
	/** Negative-only ingress refresh. A restored actor cannot recover a consumed hunger witness. */
	public void refreshOwnCatLease(OwnFollowerCopy copy)
	{
		boolean current = currentKitten(copy);
		if (copy == null || !copy.scopeMatches(tick, scene, epoch) || copy.ownLive == Truth.UNKNOWN)
		{ unknown.add(SourceKind.FOLLOWER); changed.add(SourceKind.FOLLOWER); }
		boolean removed = facts.removeIf(fact -> fact.kind == OutcomeKind.CAT_NEEDS_FOOD && fact.payload.value > 0
			&& (!current || fact.payload.entitySerial != copy.incarnationSerial || fact.payload.definitionId != copy.resolvedDefinitionId));
		if (removed) { changed.add(SourceKind.GAME_MESSAGE); changed.add(SourceKind.FOLLOWER); }
	}
	private boolean currentKitten(OwnFollowerCopy copy)
	{ return scene != null && copy != null && copy.scopeMatches(tick, scene, epoch) && copy.knownKitten(); }
	private boolean currentReportScope(AdditionalSceneContext context)
	{ return scene != null && witnessed && !baseline && !reducerOverflow && scene.equals(context.scene) && epoch == context.presenceEpoch && tick >= context.occurrenceTick; }
	private boolean messageSourceKnown(SourceKind source)
	{ return !sourceInvalidated(source) && (last == null || last.sourceKnown(source)); }
	private boolean copiedPersonalReport(PersonalOutcomeFact fact)
	{
		return fact.witnessed && fact.personal && fact.localAttributionKnown && fact.sourceVerified && fact.fullGrammarVerified
			&& fact.scene.equals(scene) && fact.presenceEpoch == epoch && fact.occurrenceTick <= tick;
	}
	private boolean dodgyContradiction(List<PersonalOutcomeFact> reports, AdditionalSceneContext context)
	{
		for (PersonalOutcomeFact fact : reports)
		{
			if ((fact.kind == OutcomeKind.DODGY_CHARGES || fact.kind == OutcomeKind.DODGY_PROTECTION)
				&& fact.sourceSerial > context.sourceSerial && copiedPersonalReport(fact)
				&& (fact.payload.value <= 0 || fact.payload.value > context.payload.value)) { return true; }
		}
		return false;
	}
	private boolean catFedAfter(List<PersonalOutcomeFact> reports, AdditionalSceneContext context)
	{
		for (PersonalOutcomeFact fact : reports)
		{
			if (fact.kind == OutcomeKind.CAT_NEEDS_FOOD && fact.payload.value == 0 && fact.sourceSerial > context.sourceSerial
				&& fact.payload.entitySerial == context.payload.entitySerial && copiedPersonalReport(fact)) { return true; }
		}
		return false;
	}
	public void onChatMessage(ChatMessage event)
	{ onChatMessage(event, null, null); }
	public void onChatMessage(ChatMessage event, OwnFollowerCopy follower)
	{ onChatMessage(event, follower, null); }
	public void onChatMessage(ChatMessage event, OwnFollowerCopy follower, TzhaarActivityCopy activity)
	{
		Map<String, Rule> table = EXACT.get(event.getType());
		if (table == null || scene == null) { return; }
		String raw = event.getMessage();
		if (!boundedRaw(raw)) { invalidate(source(event.getType())); return; }
		// Validate exact known raw markup before any sanitation.
		Rule rule = event.getType() == ChatMessageType.GAMEMESSAGE ? worldRawRule(raw) : null;
		String text = normalize(raw);
		if (rule == null && event.getType() == ChatMessageType.GAMEMESSAGE && worldRawShape(raw, text)) { rule = new Rule(OutcomeKind.UNKNOWN, Branch.UNKNOWN, 20); }
		if (rule == null) { rule = table.get(text); }
		if (rule == null && event.getType() == ChatMessageType.GAMEMESSAGE)
		{
			if (CHEMISTRY_HELP.matcher(text).matches()) { rule = new Rule(OutcomeKind.CHEMISTRY_HELP, Branch.ORDINARY, 12); }
			else if (BOUNTY_HELP.matcher(text).matches()) { rule = new Rule(OutcomeKind.BOUNTY_SEEDS, Branch.ORDINARY, 12); }
			else if (THRALL_RAW_MESSAGE.equals(raw)) { rule = new Rule(OutcomeKind.THRALL_RESURRECTED, Branch.ORDINARY, 12); }
			else if (GOLEM_GIFT.matcher(text).matches()) { rule = new Rule(OutcomeKind.GOLEM_GIFT, Branch.ORDINARY, 12); }
			else if (BIRDHOUSE_DISMANTLED.matcher(text).matches()) { rule = new Rule(OutcomeKind.BIRDHOUSE_DISMANTLED, Branch.ORDINARY, 12); }
		}
		if (rule == null && event.getType() == ChatMessageType.SPAM)
		{
			if (PICKPOCKET_SUCCESS.matcher(text).matches()) { rule = new Rule(OutcomeKind.PICKPOCKET_SUCCESS, Branch.ORDINARY, 12); }
			else if (PICKPOCKET_FAILURE.matcher(text).matches()) { rule = new Rule(OutcomeKind.PICKPOCKET_FAILURE, Branch.ORDINARY, 12); }
		}
		if (rule == null && (event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM))
		{
			if (LOG_GATHERED.matcher(text).matches()) { rule = new Rule(OutcomeKind.LOG_GATHERED, Branch.ORDINARY, 12); }
			else if (PATCH_TREATED.matcher(text).matches()) { rule = new Rule(OutcomeKind.PATCH_TREATED, Branch.ORDINARY, 12); }
			else
			{
				Matcher protection = DODGY_PROTECTION.matcher(text);
				if (protection.matches()) { rule = new Rule(OutcomeKind.DODGY_PROTECTION, Branch.ORDINARY, 12, Integer.parseInt(protection.group(1))); }
				else
				{
					Matcher check = DODGY_CHECK.matcher(text);
					if (check.matches())
					{
						String count = check.group(1);
						rule = new Rule(OutcomeKind.DODGY_CHARGES, Branch.ORDINARY, 10, count.length() <= 2 ? Integer.parseInt(count) : 99);
					}
				}
			}
		}
		if (rule == null) { return; }
		if (rule.kind == OutcomeKind.CAT_NEEDS_FOOD) { refreshOwnCatLease(follower); }
		if (rule.kind == OutcomeKind.TZHAAR_WAVE) { refreshTzhaarActivity(activity); }
		SourceKind source = source(event.getType());
		MessageNode node = event.getMessageNode();
		if (node == null || node.getType() != event.getType()) { invalidate(source); return; }
		int timestamp = event.getTimestamp();
		if (node.getTimestamp() != timestamp || !raw.equals(node.getValue())) { invalidate(source); return; }
		Map<Integer, ChatLineBuffer> buffers = client.getChatLineMap();
		ChatLineBuffer buffer = buffers == null ? null : buffers.get(event.getType().getType());
		MessageNode[] lines = buffer == null ? null : buffer.getLines();
		int length = buffer == null ? 0 : buffer.getLength();
		if (lines == null || length <= 0 || length > lines.length) { invalidate(source); return; }
		// The pinned native callback copies this exact head and its timestamp before posting ChatMessage.
		if (lines[0] != node) { return; }
		long identity = ((long) node.getId() << 32) | (timestamp & 0xffffffffL);
		int semantics = (source.ordinal() << 24) | (rule.kind.ordinal() << 16) | (rule.branch.ordinal() << 8) | (rule.value + 1);
		String detail = rule.detail != null ? rule.detail : rule.payload == null ? null : rule.kind == OutcomeKind.SEPULCHRE_FLOOR ? Integer.toString(rule.payload.secondaryValue)
			: rule.kind == OutcomeKind.TZHAAR_WAVE ? null : rule.payload.displayText;
		for (int i = 0; i < seenSize; i++)
		{
			if ((seen[i] >>> 32) != (identity >>> 32)) { continue; }
			// A changed timestamp alone cannot certify a new emission of an already consumed node ID.
			if (seen[i] != identity || ((seenSemantics[i] >>> 16) & 255) != OutcomeKind.UNKNOWN.ordinal()
				&& (seenSemantics[i] != semantics || !Objects.equals(seenReportDetails[i], detail)))
			{ invalidate(SourceKind.values()[seenSemantics[i] >>> 24]); invalidate(source); }
			return;
		}
		rememberMessage(identity, semantics, source, detail);
		if (rule.kind == OutcomeKind.UNKNOWN) { invalidate(source); return; }
		if (rule.kind == OutcomeKind.DODGY_CHARGES && rule.value > 10) { invalidate(source); return; }
		if (unknown.contains(source)) { return; }
		if (rule.kind == OutcomeKind.COLLECTION_LOG_ENTRY && !refreshCollectionLogLease()) { return; }
		if (rule.kind == OutcomeKind.TZHAAR_WAVE && (tzhaarKnown != Truth.TRUE || rule.value > (tzhaarActivity == ActivityKind.FIGHT_CAVE ? 63 : 69)))
		{ if (tzhaarKnown == Truth.TRUE) { invalidate(source); } return; }
		if (rule.kind == OutcomeKind.CAT_NEEDS_FOOD && (!currentKitten(follower) || unknown.contains(SourceKind.FOLLOWER))) { return; }
		if (witnessed && !baseline && facts.size() >= AdditionalSnapshot.MAX_FACTS) { invalidate(source); return; }
		if (!witnessed || baseline) { return; }
		if (rule.kind == OutcomeKind.TZHAAR_WAVE && rule.value <= tzhaarHighestWave)
		{
			if (rule.value < tzhaarHighestWave) { invalidate(source); }
			else if (tzhaarWave != rule.value) { tzhaarWave = rule.value; tzhaarWaveSerial = 0; changed.add(source); }
			return;
		}
		long serial = ++sequence;
		if (rule.kind == OutcomeKind.TZHAAR_WAVE)
		{
			tzhaarWave = tzhaarHighestWave = rule.value; tzhaarWaveSerial = serial;
			facts.removeIf(fact -> fact.kind == OutcomeKind.TZHAAR_WAVE); changed.add(source);
			// Ordinary waves update the same current lease, with no presentation/join/shared cause.
			if (!notableWave(tzhaarActivity, tzhaarWave)) { return; }
		}
		long cause = serial;
		if (rule.kind == OutcomeKind.COOK_BURN)
		{
			for (PersonalOutcomeFact fact : facts) { if (fact.kind == OutcomeKind.COOK_BURN) { cause = fact.causeSerial; break; } }
		}
		facts.add(new PersonalOutcomeFact(rule.kind, source, serial, cause, tick, tick + rule.window,
			scene, epoch, true, true, true, true, true, rule.kind != OutcomeKind.COLLECTION_LOG_ENTRY && rule.kind != OutcomeKind.TZHAAR_WAVE
				&& (rule.kind != OutcomeKind.CANNON_ASSEMBLED || rule.branch == Branch.FURNACE),
			rule.kind == OutcomeKind.TZHAAR_WAVE ? new PersonalOutcomeFact.Payload(Branch.ORDINARY, tzhaarActivity, "", rule.value, -1, -1, -1, -1, tzhaarActivitySerial, null, false)
				: rule.payload != null ? rule.payload : new PersonalOutcomeFact.Payload(rule.branch, ActivityKind.UNKNOWN, "", rule.value, -1,
				rule.kind == OutcomeKind.CAT_NEEDS_FOOD ? follower.resolvedDefinitionId : -1, -1,
				rule.kind == OutcomeKind.CAT_NEEDS_FOOD ? follower.incarnationSerial : -1, -1, null, rule.kind == OutcomeKind.CAT_NEEDS_FOOD)));
		changed.add(source);
	}
	private void rememberMessage(long identity, int semantics, SourceKind source)
	{ rememberMessage(identity, semantics, source, null); }
	private void rememberMessage(long identity, int semantics, SourceKind source, String detail)
	{
		int slot;
		if (seenSize < MAX_SEEN_MESSAGES) { slot = seenSize++; }
		else
		{
			slot = seenNext;
			// At most two other channels' current consumed heads are protected in this same 128-slot ledger.
			while (slot == seenGame && source != SourceKind.GAME_MESSAGE
				|| slot == seenSpam && source != SourceKind.SPAM_MESSAGE
				|| slot == seenBox && source != SourceKind.MESSAGE_BOX) { slot = (slot + 1) % MAX_SEEN_MESSAGES; }
		}
		seen[slot] = identity; seenSemantics[slot] = semantics; seenReportDetails[slot] = detail; seenNext = (slot + 1) % MAX_SEEN_MESSAGES;
		seenHead(slot, source);
	}
	private void seenHead(int slot, SourceKind source)
	{
		if (source == SourceKind.GAME_MESSAGE) { seenGame = slot; }
		else if (source == SourceKind.SPAM_MESSAGE) { seenSpam = slot; }
		else { seenBox = slot; }
	}
	/** Negative baseline only: pre-existing heads cannot become post-settle report witnesses. */
	private void seedMessageHeads()
	{
		Map<Integer, ChatLineBuffer> buffers = client.getChatLineMap();
		if (buffers == null) { return; }
		for (ChatMessageType type : MESSAGE_TYPES)
		{
			ChatLineBuffer buffer = buffers.get(type.getType());
			MessageNode[] lines = buffer == null ? null : buffer.getLines(); int length = buffer == null ? 0 : buffer.getLength();
			if (lines == null || length <= 0 || length > lines.length || lines[0] == null || lines[0].getType() != type) { continue; }
			MessageNode node = lines[0]; long identity = ((long) node.getId() << 32) | (node.getTimestamp() & 0xffffffffL);
			SourceKind source = source(type); boolean consumed = false;
			for (int i = 0; i < seenSize; i++)
			{
				if ((seen[i] >>> 32) == (identity >>> 32)) { seenHead(i, source); consumed = true; break; }
			}
			if (!consumed) { rememberMessage(identity, source.ordinal() << 24, source); }
		}
	}
	private void invalidate(SourceKind source)
	{
		unknown.add(source); changed.add(source); facts.removeIf(fact -> fact.sourceKind == source);
		if (source == SourceKind.GAME_MESSAGE) { tzhaarWave = -1; tzhaarWaveSerial = 0; }
	}
	public AdditionalSnapshot commit(ScenarioSnapshot frame)
	{
		return commit(frame, null, null);
	}
	/** The observer supplies its one shared inventory/equipment pass, inventory first then equipment. */
	public AdditionalSnapshot commit(ScenarioSnapshot frame, int[] copiedIds42, int[] copiedQuantities42)
	{ return commit(frame, copiedIds42, copiedQuantities42, null, null); }
	public AdditionalSnapshot commit(ScenarioSnapshot frame, int[] copiedIds42, int[] copiedQuantities42, OwnFollowerCopy follower)
	{ return commit(frame, copiedIds42, copiedQuantities42, follower, null); }
	public AdditionalSnapshot commit(ScenarioSnapshot frame, int[] copiedIds42, int[] copiedQuantities42, OwnFollowerCopy follower, TzhaarActivityCopy activity)
	{
		SceneKey key = new SceneKey(frame.sessionGeneration, frame.sceneRevision);
		if (last != null && last.tick == frame.tick && last.scene.equals(key) && last.presenceEpoch == frame.presentSinceTick) { return last; }
		if (scene == null || !scene.equals(key) || epoch != frame.presentSinceTick || tick != frame.tick)
		{ beginInterval(frame.tick, key, frame.presentSinceTick, frame.present && frame.valid && !frame.dead && !frame.userHidden && !frame.autoAbsent); }
		if (reducerOverflow)
		{
			facts.clear(); last = AdditionalSnapshot.unknown(frame.tick, key, epoch); return last;
		}
		if (!frame.valid || !frame.present || frame.dead || frame.userHidden || frame.autoAbsent || !witnessed)
		{
			reset(); this.scene = key; tick = frame.tick; epoch = frame.presentSinceTick;
			last = AdditionalSnapshot.unknown(frame.tick, key, epoch); return last;
		}
		refreshOwnCatLease(follower); refreshTzhaarActivity(activity);
		if (baseline) { seedMessageHeads(); }
		List<AdditionalUiSnapshot> roots = captureUi();
		SharedItemSummary items = copyItems(roots, copiedIds42, copiedQuantities42);
		items = pouch.commit(client, items); cachedItems = items;
		if (pouch.changed()) { changed.add(SourceKind.VARBIT); }
		AdditionalNumericSnapshot numeric = copyNumeric(frame);
		boolean followerScope = follower != null && follower.scopeMatches(frame.tick, key, epoch);
		Truth followerKnown = followerScope ? follower.ownLive : Truth.UNKNOWN;
		if (followerKnown == Truth.UNKNOWN) { unknown.add(SourceKind.FOLLOWER); }
		last = new AdditionalSnapshot(frame.tick, key, epoch, true, baseline, facts, roots, items, numeric,
			unknown, false, followerKnown, followerKnown == Truth.TRUE ? follower.incarnationSerial : 0,
			followerKnown == Truth.TRUE ? follower.resolvedDefinitionId : -1, changed, tzhaarKnown, tzhaarActivity, tzhaarActivitySerial, tzhaarWave, tzhaarWaveSerial);
		facts.clear(); unknown.clear(); baseline = false; return last;
	}
	/** Client-thread closure only. Observer guards and the committed envelope share these same root probes. */
	public List<AdditionalUiSnapshot> captureUi()
	{
		if (capturedUi != null) { return capturedUi; }
		List<AdditionalUiSnapshot> roots = new ArrayList<>(ROOTS.length);
		for (int i = 0; i < ROOTS.length; i++)
		{
			Widget widget = client.getWidget(ROOTS[i]);
			Truth visible = widget == null || widget.isHidden() ? Truth.FALSE : Truth.TRUE;
			UiState state = ui[i]; boolean seed = baseline || state.visibility == Truth.UNKNOWN;
			if (state.visibility != visible || state.baselineSnapshot != seed) { changed.add(SourceKind.UI); }
			if (UI_KINDS[i] == UiKind.CLUE_PUZZLE) { roots.add(capturePuzzle(visible, state, seed)); continue; }
			if (seed) { state.serial = 0; state.openTick = -1; state.closeTick = -1; }
			else if (state.visibility == Truth.FALSE && visible == Truth.TRUE)
			{ state.serial = ++sequence; state.openTick = tick; state.closeTick = -1; visibilityEpoch++; }
			else if (state.visibility == Truth.TRUE && visible == Truth.FALSE)
			{ state.closeTick = state.serial > 0 ? tick : -1; visibilityEpoch++; }
			state.visibility = visible; state.baselineSnapshot = seed;
			roots.add(new AdditionalUiSnapshot(UI_KINDS[i], ROOTS[i], visible, state.serial, state.openTick,
				state.closeTick, "", 0, false, true, seed));
		}
		capturedUi = Collections.unmodifiableList(roots); return capturedUi;
	}
	private AdditionalUiSnapshot capturePuzzle(Truth visible, UiState state, boolean seed)
	{
		if (seed)
		{
			state.serial = 0; state.openTick = state.closeTick = -1; state.clearPuzzle();
			state.puzzleInvalid = visible == Truth.TRUE;
		}
		else if (state.visibility == Truth.FALSE && visible == Truth.TRUE)
		{
			boolean newView = state.serial == 0 || state.closeTick >= 0 && state.closeTick == state.puzzleCloseTick;
			if (newView && sequence != Long.MAX_VALUE)
			{ state.serial = ++sequence; state.openTick = tick; }
			else { state.puzzleInvalid = true; }
			state.closeTick = state.puzzleCloseTick = -1; state.puzzleCloseVerified = false; visibilityEpoch++;
		}
		else if (state.visibility == Truth.TRUE && visible == Truth.FALSE)
		{
			state.closeTick = state.serial > 0 ? tick : -1;
			long duration = tick - state.openTick;
			state.puzzleCloseVerified = state.serial > 0 && state.puzzleInitialized && !state.puzzleInvalid
				&& state.puzzleInitPhase == 6 && state.puzzleInitTick == state.openTick && state.puzzleCloseTick == tick && duration >= 1 && duration <= 10;
			if (!state.puzzleCloseVerified) { state.puzzleInvalid = true; }
			visibilityEpoch++;
		}
		state.visibility = visible; state.baselineSnapshot = seed;
		if (visible == Truth.TRUE && state.puzzleInitialized && !state.puzzleInvalid
			&& (state.puzzleInitPhase != 6 || state.puzzleInitTick != state.openTick || !currentPuzzleKind(state.puzzleSelector))) { retirePuzzle(state); }
		boolean verified = visible == Truth.FALSE ? state.serial == 0 || state.puzzleCloseVerified
			: state.puzzleInitialized && !state.puzzleInvalid && state.puzzleInitPhase == 6 && state.puzzleInitTick == state.openTick;
		return new AdditionalUiSnapshot(UiKind.CLUE_PUZZLE, 0x01320000, visible, state.serial, state.openTick,
			state.closeTick, "", 0, false, verified, seed, state.puzzleInitialized ? state.puzzleSelector : -1, state.puzzleCloseVerified);
	}
	private SharedItemSummary copyItems(List<AdditionalUiSnapshot> roots, int[] copiedIds42, int[] copiedQuantities42)
	{
		Truth bank = or(roots.get(0).visibility, roots.get(1).visibility), trade = or(roots.get(2).visibility, roots.get(3).visibility);
		boolean tupleCopy = cachedItems == null || !cachedItems.complete || inventoryDirty || equipmentDirty;
		if (!tupleCopy && (!unresolvedItemMetadata || metadataResolutions == MAX_RESOLUTIONS))
		{
			cachedItems = cachedItems.withVisibility(bank, trade, visibilityEpoch, contentsCauseSerial);
			if (!cachedItems.inventoryKnown) { unknown.add(SourceKind.INVENTORY); }
			if (!cachedItems.equipmentKnown) { unknown.add(SourceKind.EQUIPMENT); }
			return cachedItems;
		}
		if (tupleCopy)
		{
			Arrays.fill(ids, SharedItemSummary.UNKNOWN_ID); Arrays.fill(quantities, -1);
			if (copiedIds42 != null && copiedQuantities42 != null && copiedIds42.length == ids.length && copiedQuantities42.length == ids.length)
			{ System.arraycopy(copiedIds42, 0, ids, 0, ids.length); System.arraycopy(copiedQuantities42, 0, quantities, 0, quantities.length); }
		}
		if (inventoryDirty) { inventoryDirty = false; contentsCauseSerial = ++sequence; }
		equipmentDirty = false;
		ItemForm[] forms = new ItemForm[ids.length]; String[] names = new String[ids.length]; Truth[] stackable = new Truth[ids.length];
		unresolvedItemMetadata = false;
		for (int i = 0; i < ids.length; i++)
		{
			forms[i] = ids[i] == SharedItemSummary.EMPTY_ID ? ItemForm.EMPTY : ItemForm.UNKNOWN; stackable[i] = Truth.UNKNOWN;
			if (ids[i] < 0) { continue; }
			Metadata entry = resolveMetadata(ids[i]);
			if (entry != null)
			{
				forms[i] = entry.form; names[i] = entry.name;
				if (entry.form != ItemForm.UNKNOWN) { stackable[i] = entry.stackable ? Truth.TRUE : Truth.FALSE; }
			}
			if (forms[i] == ItemForm.UNKNOWN) { unresolvedItemMetadata = true; }
			if (cachedItems != null && forms[i] != cachedItems.form(i))
			{ changed.add(i < SharedItemSummary.INVENTORY_SIZE ? SourceKind.INVENTORY : SourceKind.EQUIPMENT); }
		}
		SharedItemSummary summary = new SharedItemSummary(ids, quantities, forms, bank, trade, visibilityEpoch, contentsCauseSerial,
			Truth.UNKNOWN, null, names, stackable);
		if (!summary.inventoryKnown) { unknown.add(SourceKind.INVENTORY); }
		if (!summary.equipmentKnown) { unknown.add(SourceKind.EQUIPMENT); }
		if (cachedItems != null && summary.inventoryKnown != cachedItems.inventoryKnown) { changed.add(SourceKind.INVENTORY); }
		if (cachedItems != null && summary.equipmentKnown != cachedItems.equipmentKnown) { changed.add(SourceKind.EQUIPMENT); }
		cachedItems = summary; return summary;
	}
	/** Client-thread closure only; shares the inventory metadata cache and eight-read allowance. */
	public String resolveReportedName(int definitionId)
	{
		Metadata entry = resolveMetadata(definitionId);
		return entry == null || entry.name.isEmpty() ? null : entry.name;
	}
	private Metadata resolveMetadata(int definitionId)
	{
		if (definitionId < 0 || scene == null) { return null; }
		Metadata entry = metadata.get(definitionId);
		if (entry != null && (entry.form != ItemForm.UNKNOWN || entry.attemptTick == tick)) { return entry; }
		if (metadataResolutions == MAX_RESOLUTIONS) { return entry; }
		metadataResolutions++;
		ItemComposition definition = client.getItemDefinition(definitionId);
		if (definition != null && definition.getId() == definitionId)
		{
			String name = normalize(definition.getName());
			entry = new Metadata(definition.getPlaceholderTemplateId() != -1 ? ItemForm.PLACEHOLDER
				: definition.getNote() != -1 ? ItemForm.NOTE : ItemForm.REAL, name == null ? "" : name, definition.isStackable(), tick);
		}
		else { entry = new Metadata(ItemForm.UNKNOWN, "", false, tick); }
		if (metadata.size() == MAX_METADATA && !metadata.containsKey(definitionId)) { metadata.remove(metadata.keySet().iterator().next()); }
		metadata.put(definitionId, entry); return entry;
	}
	private AdditionalNumericSnapshot copyNumeric(ScenarioSnapshot frame)
	{
		int energy = client.getEnergy(), weight = client.getWeight(); int[] beforeWatched = watched;
		if (watched == null || varDirty || hasUnknownWatch())
		{
			watched = new int[Watch.values().length]; Arrays.fill(watched, AdditionalNumericSnapshot.UNKNOWN);
			int[] varps = client.getVarps();
			if (varps != null)
			{
				for (Watch watch : Watch.values())
				{
					if (watch.varbit)
					{
						VarbitComposition composition = client.getVarbit(watch.id);
						if (composition != null && composition.getIndex() >= 0 && composition.getIndex() < varps.length
							&& composition.getLeastSignificantBit() >= 0 && composition.getMostSignificantBit() >= composition.getLeastSignificantBit()
							&& composition.getMostSignificantBit() < 32) { watched[watch.ordinal()] = client.getVarbitValue(watch.id); }
					}
					else if (watch.id >= 0 && watch.id < varps.length) { watched[watch.ordinal()] = client.getVarpValue(watch.id); }
				}
			}
			varDirty = false;
		}
		boolean watchesChanged = !Arrays.equals(beforeWatched, watched);
		if (watchesChanged)
		{
			for (Watch watch : Watch.values())
			{
				if (beforeWatched == null || beforeWatched[watch.ordinal()] != watched[watch.ordinal()])
				{ changed.add(watch.varbit ? SourceKind.VARBIT : SourceKind.VARP); }
			}
		}
		if (cachedNumeric != null && cachedNumeric.skillsKnown && frame.skillsKnown() && !numericDirty && !watchesChanged
			&& energy == cachedEnergyRead && weight == cachedWeightRead) { return cachedNumeric; }
		if (cachedNumeric == null || energy != cachedEnergyRead || weight != cachedWeightRead) { changed.add(SourceKind.NUMERIC); }
		int[] xp = new int[24], levels = new int[24], boosted = new int[24];
		for (Skill skill : Skill.values()) { xp[skill.ordinal()] = frame.xp(skill); levels[skill.ordinal()] = frame.level(skill); boosted[skill.ordinal()] = frame.boosted(skill); }
		numericDirty = false; cachedEnergyRead = energy; cachedWeightRead = weight;
		AdditionalNumericSnapshot next = new AdditionalNumericSnapshot(energy, weight, xp, levels, boosted, watched);
		if (cachedNumeric != null && next.skillsKnown != cachedNumeric.skillsKnown) { changed.add(SourceKind.NUMERIC); }
		cachedNumeric = next; return cachedNumeric;
	}
	private boolean hasUnknownWatch()
	{
		if (watched == null) { return true; }
		for (int value : watched) { if (value == AdditionalNumericSnapshot.UNKNOWN) { return true; } }
		return false;
	}
	/** Fail closed for this epoch without making consumed reports eligible for replay. */
	public void invalidateForReducerOverflow()
	{
		pouch.reset();
		reducerOverflow = true; facts.clear(); unknown.addAll(EnumSet.allOf(SourceKind.class));
		changed.addAll(EnumSet.allOf(SourceKind.class)); last = null; capturedUi = null; baseline = true;
	}
	/** Scene/presence rebaseline; consumed message keys survive until the session generation changes. */
	public void reset()
	{
		pouch.reset();
		facts.clear(); unknown.clear(); metadataResolutions = 0; reducerOverflow = false; last = null; capturedUi = null; baseline = true;
		changed.clear(); changed.addAll(EnumSet.allOf(SourceKind.class)); cachedItems = null; cachedNumeric = null; unresolvedItemMetadata = false;
		cachedEnergyRead = cachedWeightRead = AdditionalNumericSnapshot.UNKNOWN;
		tzhaarKnown = Truth.UNKNOWN; tzhaarActivity = ActivityKind.UNKNOWN; tzhaarActivitySerial = tzhaarWaveSerial = 0;
		tzhaarWave = tzhaarViewId = tzhaarPlane = -1; tzhaarHighestWave = 0;
		inventoryDirty = equipmentDirty = numericDirty = varDirty = true; watched = null; contentsCauseSerial = 0;
		Arrays.fill(ids, SharedItemSummary.UNKNOWN_ID); Arrays.fill(quantities, -1);
		for (UiState state : ui) { state.visibility = Truth.UNKNOWN; state.serial = 0; state.openTick = state.closeTick = -1; state.baselineSnapshot = true; state.clearPuzzle(); }
	}
	public int metadataEntries() { return metadata.size(); }
	public int metadataResolutions() { return metadataResolutions; }
	public AdditionalSnapshot retainedSnapshot() { return last; }
	public int retainedEnvelopeCount() { return last == null ? 0 : 1; }
	public SharedItemSummary retainedItems() { return cachedItems; }
	public AdditionalNumericSnapshot retainedNumeric() { return cachedNumeric; }
	public int retainedNonSnapshotRecords() { return metadata.size() + seenSize + facts.size() + ui.length + 4 + retainedActivityRecords() + pouch.retainedWatcherRecords()
		+ (last == null && capturedUi != null ? capturedUi.size() : 0); }
	public int retainedRecords() { return metadata.size() + seenSize + facts.size() + (last == null ? 0 : last.outcomes.size())
		+ (capturedUi == null ? 0 : capturedUi.size()) + ui.length + 4 + retainedActivityRecords() + pouch.retainedWatcherRecords(); }
	public static boolean sourceGateSupported(OutcomeKind kind) { return AUDITED_OUTCOMES.contains(kind); }
	public static java.util.Set<OutcomeKind> sourceGatedOutcomeKinds() { return AUDITED_OUTCOMES; }
	private static java.util.Set<OutcomeKind> auditedOutcomes()
	{
		EnumSet<OutcomeKind> kinds = EnumSet.of(OutcomeKind.MARK_OF_GRACE);
		for (Map<String, Rule> table : EXACT.values()) { for (Rule rule : table.values()) { kinds.add(rule.kind); } }
		kinds.add(OutcomeKind.CHEMISTRY_HELP); kinds.add(OutcomeKind.PERSONAL_BEST);
		kinds.add(OutcomeKind.COLLECTION_LOG_ENTRY); kinds.add(OutcomeKind.COMBAT_ACHIEVEMENT); kinds.add(OutcomeKind.SEPULCHRE_FLOOR); kinds.add(OutcomeKind.TZHAAR_WAVE);
		kinds.add(OutcomeKind.PICKPOCKET_SUCCESS); kinds.add(OutcomeKind.PICKPOCKET_FAILURE); kinds.add(OutcomeKind.LOG_GATHERED);
		kinds.add(OutcomeKind.THRALL_RESURRECTED);
		kinds.add(OutcomeKind.GOLEM_GIFT); kinds.add(OutcomeKind.BIRDHOUSE_DISMANTLED); kinds.add(OutcomeKind.PATCH_TREATED); kinds.add(OutcomeKind.DODGY_CHARGES);
		return Collections.unmodifiableSet(kinds);
	}
	private static Rule worldRawRule(String raw)
	{
		if (raw.startsWith("Wave 'The Final Challenge' "))
		{
			Matcher best = THEATRE_PB_RAW.matcher(raw);
			if (best.matches())
			{
				String waveTime = best.group(2), performanceTime = best.group(3);
				boolean preciseNormal = best.group(1).equals("Normal");
				if (preciseNormal ? waveTime.indexOf('.') != waveTime.length() - 2 || performanceTime.indexOf('.') != performanceTime.length() - 3
					: waveTime.indexOf('.') >= 0 || performanceTime.indexOf('.') >= 0) { return null; }
				long waveMillis = reportedTimeMillis(waveTime), performanceMillis = reportedTimeMillis(performanceTime);
				if (waveMillis <= 0 || performanceMillis <= 0) { return null; }
				Branch mode = best.group(1).equals("Normal") ? Branch.NORMAL : Branch.ENTRY;
				PersonalOutcomeFact.Payload payload = new PersonalOutcomeFact.Payload(mode, ActivityKind.THEATRE, "", -1, -1, -1,
					performanceMillis, -1, -1, null, false);
				// Both clocks are copied numeric semantics in the existing seen ring, never raw text or a timing join.
				return new Rule(OutcomeKind.PERSONAL_BEST, mode, 20, -1, payload, waveMillis + "/" + performanceMillis);
			}
		}
		Matcher collection = COLLECTION_LOG_RAW.matcher(raw);
		if (collection.matches() && display(collection.group(1), 96))
		{ return reported(OutcomeKind.COLLECTION_LOG_ENTRY, Branch.ORDINARY, ActivityKind.UNKNOWN, collection.group(1), -1, -1); }
		Matcher achievement = ACHIEVEMENT_RAW.matcher(raw);
		if (achievement.matches() && display(achievement.group(2), 120))
		{
			String tier = achievement.group(1); Branch branch = tier.equals("an easy") ? Branch.EASY : tier.equals("a medium") ? Branch.MEDIUM
				: tier.equals("a hard") ? Branch.HARD : tier.equals("an elite") ? Branch.ELITE : tier.equals("a master") ? Branch.MASTER : Branch.GRANDMASTER;
			return reported(OutcomeKind.COMBAT_ACHIEVEMENT, branch, ActivityKind.UNKNOWN, achievement.group(2), -1, -1);
		}
		Matcher points = ACHIEVEMENT_POINTS_RAW.matcher(raw);
		if (points.matches() && display(points.group(1), 120)) { return reported(OutcomeKind.COMBAT_ACHIEVEMENT, Branch.GRANDMASTER, ActivityKind.UNKNOWN, points.group(1), -1, -1); }
		Matcher floor = SEPULCHRE_RAW.matcher(raw);
		if (floor.matches())
		{
			String count = floor.group(2).replace(",", ""); if (count.length() > 10) { return null; }
			long value = Long.parseLong(count); if (value > Integer.MAX_VALUE) { return null; }
			return reported(OutcomeKind.SEPULCHRE_FLOOR, Branch.ORDINARY, ActivityKind.SEPULCHRE, "", Integer.parseInt(floor.group(1)), (int) value);
		}
		Matcher wave = TZHAAR_RAW.matcher(raw);
		return wave.matches() ? reported(OutcomeKind.TZHAAR_WAVE, Branch.ORDINARY, ActivityKind.UNKNOWN, "", Integer.parseInt(wave.group(1)), -1) : null;
	}
	/** Narrow mm:ss(.d/.dd) source notation; the minute primitive matches the pinned consumer's int domain. */
	private static long reportedTimeMillis(String value)
	{
		int colon = value.indexOf(':'), dot = value.indexOf('.');
		try
		{
			int minutes = Integer.parseInt(value.substring(0, colon));
			int seconds = Integer.parseInt(value.substring(colon + 1, dot < 0 ? value.length() : dot));
			int fraction = dot < 0 ? 0 : Integer.parseInt(value.substring(dot + 1)) * (value.length() - dot == 2 ? 100 : 10);
			return (long) minutes * 60000 + seconds * 1000L + fraction;
		}
		catch (NumberFormatException ex) { return -1; }
	}
	private static Rule reported(OutcomeKind kind, Branch branch, ActivityKind activity, String display, int value, int secondary)
	{ return new Rule(kind, branch, 20, value, new PersonalOutcomeFact.Payload(branch, activity, display, value, secondary, -1, -1, -1, -1, null, false)); }
	private static boolean worldRawShape(String raw, String text)
	{ return raw.startsWith("New item added to your collection log: ") || raw.startsWith("Congratulations, you've completed ")
		|| raw.startsWith("You have completed Floor ") || text.startsWith("Wave: ")
		|| raw.startsWith("Wave 'The Final Challenge' ") || raw.startsWith("Theatre of Blood completion time: "); }
	private static boolean display(String text, int limit)
	{
		if (text.trim().isEmpty() || text.codePointCount(0, text.length()) > limit) { return false; }
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i); if (Character.isISOControl(c) || c == '<' || c == '>' || c == '@') { return false; }
			if (Character.isHighSurrogate(c)) { if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) { return false; } }
			else if (Character.isLowSurrogate(c)) { return false; }
		}
		return true;
	}
	private static boolean notableWave(ActivityKind activity, int wave)
	{ return activity == ActivityKind.FIGHT_CAVE ? wave == 31 || wave == 61 || wave == 63 : activity == ActivityKind.INFERNO && (wave == 35 || wave >= 67 && wave <= 69); }
	private static Truth or(Truth a, Truth b) { return a == Truth.TRUE || b == Truth.TRUE ? Truth.TRUE : a == Truth.FALSE && b == Truth.FALSE ? Truth.FALSE : Truth.UNKNOWN; }
	private static SourceKind source(ChatMessageType type)
	{ return type == ChatMessageType.SPAM ? SourceKind.SPAM_MESSAGE : type == ChatMessageType.MESBOX ? SourceKind.MESSAGE_BOX : SourceKind.GAME_MESSAGE; }
	/** Source text is rejected, never truncated. This exact tag syntax is the pinned Text.removeTags behavior. */
	private static String normalize(String value)
	{
		if (!boundedRaw(value)) { return null; }
		return TAGS.matcher(value).replaceAll("");
	}
	private static boolean boundedRaw(String value)
	{ return value != null && value.length() <= 512 && value.codePointCount(0, value.length()) <= 256
		&& value.indexOf('\n') < 0 && value.indexOf('\r') < 0; }
	/** Only an exact bounded GAME wave requires the separate current-tile activity input. */
	public static boolean requiresTzhaarBinding(ChatMessage event)
	{ return event.getType() == ChatMessageType.GAMEMESSAGE && boundedRaw(event.getMessage()) && TZHAAR_RAW.matcher(event.getMessage()).matches(); }
	public boolean hasPendingCollectionLogReport()
	{ for (PersonalOutcomeFact fact : facts) { if (fact.kind == OutcomeKind.COLLECTION_LOG_ENTRY) { return true; } } return false; }
	public boolean hasTzhaarActivityLease() { return tzhaarActivitySerial > 0; }
	public long currentTzhaarWaveSerial() { return tzhaarWaveSerial; }
	private static Map<ChatMessageType, Map<String, Rule>> exactRules()
	{
		Map<ChatMessageType, Map<String, Rule>> rules = new EnumMap<>(ChatMessageType.class);
		add(rules, OutcomeKind.COOK_SUCCESS, "You successfully cook a shark.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.COOK_SUCCESS, "You successfully cook an anglerfish.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.COOK_SUCCESS, "You manage to cook a tuna.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.COOK_SUCCESS, "You successfully bake a tasty garden pie.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.COOK_BURN, "You burn the mushroom in the fire.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.MINING_CLAY, "You manage to mine some clay.", Branch.ORDINARY, ChatMessageType.SPAM, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.MINING_CLAY, "You manage to mine some soft clay.", Branch.ORDINARY, ChatMessageType.SPAM, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.GEM_FIND, "You just found a Diamond!", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.GEM_FIND, "You just found a Ruby!", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.GEM_FIND, "You just found an Emerald!", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.GEM_FIND, "You just found a Sapphire!", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.BIRD_NEST_FALL, "A bird's nest falls out of the tree.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.POUCH_DECAY, "Your pouch has decayed through use.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.LIQUID_ADRENALINE, "You drink some of the potion, reducing the energy cost of your special attacks.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.BAR_RETRIEVED, "You retrieve a bar of iron.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNONBALLS_MOULD, "You remove the cannonballs from the mould", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.HERBIBOAR_HARVEST, "You harvest herbs from the herbiboar, whereupon it escapes.", Branch.ORDINARY, ChatMessageType.SPAM, ChatMessageType.GAMEMESSAGE, ChatMessageType.MESBOX);
		add(rules, OutcomeKind.BOUNTY_SEEDS, "Your amulet of bounty saves some seeds for you. It then crumbles to dust.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.FROZEN_REPORT, "You have been frozen!", Branch.ORDINARY, ChatMessageType.SPAM, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.STASH_TRANSFER, "You deposit your items into the STASH unit.", Branch.DEPOSIT, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.STASH_TRANSFER, "You withdraw your items from the STASH unit.", Branch.WITHDRAW, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.FORGING_MELTED, "Your Ring of Forging has melted.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.BINDING_DISINTEGRATED, "Your Binding necklace has disintegrated.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.SLAUGHTER_CRUMBLED, "Your Bracelet of Slaughter has crumbled to dust.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.EXPEDITIOUS_CRUMBLED, "Your Expeditious Bracelet has crumbled to dust.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.CANNON_EMPTY, "Your cannon is out of ammo!", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.ENDURANCE_EFFECT, "Your Ring of endurance doubles the duration of your stamina potion's effect.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.RECOIL_SHATTERED, "Your Ring of Recoil has shattered.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.BLOOD_ESSENCE, "You activate the blood essence.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.WOUND_DRESSING, "You quickly apply the dressing to your wounds.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNON_REPAIRED, "You repair your cannon, restoring it to working order.", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.PROTECTIVE_SPIRITS, "Spirits of deceased evildoers offer you their protection", Branch.ORDINARY, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.SUPERIOR_FOE, "A superior foe has appeared...", Branch.ORDINARY, 20, ChatMessageType.GAMEMESSAGE);
		add(rules, OutcomeKind.BLACKJACK_SUCCESS, "You smack the bandit over the head and render them unconscious.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.BLACKJACK_GLANCE, "Your blow only glances off the bandit's head.", Branch.ORDINARY, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNON_ASSEMBLED, "You place the cannon base on the ground.", Branch.BASE, 20, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNON_ASSEMBLED, "You add the stand.", Branch.STAND, 20, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNON_ASSEMBLED, "You add the barrels.", Branch.BARRELS, 20, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		add(rules, OutcomeKind.CANNON_ASSEMBLED, "You add the furnace.", Branch.FURNACE, 20, ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM);
		for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.GAMEMESSAGE, ChatMessageType.SPAM})
		{
			rules.get(type).put("Your dodgy necklace protects you. It then crumbles to dust.", new Rule(OutcomeKind.DODGY_PROTECTION, Branch.ORDINARY, 12, 0));
		}
		rules.get(ChatMessageType.GAMEMESSAGE).put("Your kitten is hungry.", new Rule(OutcomeKind.CAT_NEEDS_FOOD, Branch.ORDINARY, 10, 1));
		rules.get(ChatMessageType.GAMEMESSAGE).put("Your kitten is very hungry.", new Rule(OutcomeKind.CAT_NEEDS_FOOD, Branch.ORDINARY, 10, 2));
		rules.get(ChatMessageType.GAMEMESSAGE).put("The kitten gobbles up the fish.", new Rule(OutcomeKind.CAT_NEEDS_FOOD, Branch.ORDINARY, 10, 0));
		rules.get(ChatMessageType.GAMEMESSAGE).put("The kitten laps up the milk.", new Rule(OutcomeKind.CAT_NEEDS_FOOD, Branch.ORDINARY, 10, 0));
		for (Map.Entry<ChatMessageType, Map<String, Rule>> entry : rules.entrySet()) { entry.setValue(Collections.unmodifiableMap(entry.getValue())); }
		return Collections.unmodifiableMap(rules);
	}
	private static void add(Map<ChatMessageType, Map<String, Rule>> table, OutcomeKind kind, String text, Branch branch, ChatMessageType... types)
	{ add(table, kind, text, branch, kind == OutcomeKind.RECOIL_SHATTERED ? 10 : 12, types); }
	private static void add(Map<ChatMessageType, Map<String, Rule>> table, OutcomeKind kind, String text, Branch branch, int window, ChatMessageType... types)
	{
		for (ChatMessageType type : types)
		{
			Rule previous = table.computeIfAbsent(type, key -> new LinkedHashMap<>()).put(text, new Rule(kind, branch, window));
			if (previous != null) { throw new IllegalStateException("conflicting exact source rule"); }
		}
	}
	private static final class Rule
	{
		final OutcomeKind kind; final Branch branch; final int window, value; final PersonalOutcomeFact.Payload payload; final String detail;
		Rule(OutcomeKind kind, Branch branch, int window) { this(kind, branch, window, -1); }
		Rule(OutcomeKind kind, Branch branch, int window, int value)
		{ this(kind, branch, window, value, null); }
		Rule(OutcomeKind kind, Branch branch, int window, int value, PersonalOutcomeFact.Payload payload)
		{ this(kind, branch, window, value, payload, null); }
		Rule(OutcomeKind kind, Branch branch, int window, int value, PersonalOutcomeFact.Payload payload, String detail)
		{ this.kind = kind; this.branch = branch; this.window = window; this.value = value; this.payload = payload; this.detail = detail; }
	}
	private static final class Metadata
	{
		final ItemForm form; final String name; final boolean stackable; final long attemptTick;
		Metadata(ItemForm form, String name, boolean stackable, long attemptTick)
		{ this.form = form; this.name = name; this.stackable = stackable; this.attemptTick = attemptTick; }
	}
	private static final class UiState
	{
		Truth visibility = Truth.UNKNOWN; long serial, openTick = -1, closeTick = -1;
		boolean baselineSnapshot = true;
		// 28 primitive bytes per existing UI watcher, within its unchanged 384-byte reservation.
		long puzzleInitTick = -1, puzzleCloseTick = -1; int puzzleSelector = -1, puzzleInitPhase;
		boolean puzzleInitPending, puzzleInitialized, puzzleInvalid, puzzleCloseVerified;
		void clearPuzzle()
		{
			puzzleInitTick = puzzleCloseTick = -1; puzzleSelector = -1; puzzleInitPhase = 0;
			puzzleInitPending = puzzleInitialized = puzzleInvalid = puzzleCloseVerified = false;
		}
	}
}
