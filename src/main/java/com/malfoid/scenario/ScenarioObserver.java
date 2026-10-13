package com.malfoid.scenario;

import com.malfoid.presence.OtherPlayerPolicy;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.additional.AdditionalSourceCollector;
import com.malfoid.additional.AdditionalSnapshot;
import com.malfoid.additional.AdditionalUiSnapshot;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import com.malfoid.additional.OwnFollowerCopy;
import com.malfoid.additional.TzhaarActivityCopy;
import com.malfoid.additional.SceneKey;
import com.malfoid.additional.MarkOfGraceSpawn;
import com.malfoid.additional.SharedItemSummary;
import com.malfoid.scenario.ScenarioBatch.Hit;
import com.malfoid.scenario.ScenarioBatch.Loot;
import com.malfoid.scenario.ScenarioBatch.NpcDeath;
import com.malfoid.scenario.ScenarioBatch.Stack;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.scenario.ScenarioSnapshot.Target;
import com.malfoid.scenario.AdditionalWorldSensor.VisitorCopy;
import com.malfoid.scenario.AdditionalWorldSensor.RootsCopy;
import com.malfoid.scenario.AdditionalWorldSensor.WorldSnapshot;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.BiPredicate;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/** RuneLite callback adapter. All methods run on the client thread; callbacks only copy facts. */
public final class ScenarioObserver
{
	private static final int MAX_HITS = 256, MAX_REPORTS = 64, MAX_OBJECTS = 512, MAX_NPCS = 2048;
	private final Client client;
	private final ItemManager itemManager;
	private final ScenarioRules rules = new ScenarioRules();
	private final AdditionalSourceCollector additionalSources;
	private final GroundOutcomeObserver groundOutcomes;
	private final Map<NPC, NpcIdentity> npcs = new IdentityHashMap<>();
	private final Map<GameObject, String> objects = new IdentityHashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Loot> loot = new ArrayList<>();
	private final List<NpcDeath> npcDeaths = new ArrayList<>();
	private final List<String> localTargetChanges = new ArrayList<>();
	private final int[] sharedItemIds = new int[SharedItemSummary.SLOT_COUNT], sharedItemQuantities = new int[SharedItemSummary.SLOT_COUNT];
	private boolean inventoryCopyDirty = true, equipmentCopyDirty = true;
	private final EnumSet<Skill> witnessedSkills = EnumSet.noneOf(Skill.class);
	private long tick, sequence, spawnSequence, objectSequence, sessionGeneration = Long.MIN_VALUE, sceneRevision = Long.MIN_VALUE;
	private boolean present, userHidden, autoAbsent, localDeath, deathUserHidden, engagementInterrupted, incomplete;
	private boolean targetChangesUnknown, emergencySceneActive;
	private boolean liveOutfitBlocked, liveInventoryBlocked;
	private Player lastPlayer;
	private Reachability reachability;
	private BooleanSupplier inspectionSuppression = () -> false;
	private BiPredicate<String, String> creatureCurrent = (key, group) -> false;
	private BooleanSupplier sceneryStartAllowed = () -> false;
	private LivingWorldData livingWorldData;
	private MotionObservation motion, motionCompletion;
	private MotionHoldReceipt motionHold;
	private CancelledInterestObservation cancelledInterest;
	private long activeSceneCandidateId = -1;
	private boolean activeSceneCommitted;
	private long placementGeneration = -1;
	private long openTick = -1, openGeneration = -1, openRevision = -1, openPresenceEpoch = -1;
	private boolean openWitness;
	private WorldView worldViewLease;
	private Scene worldSceneLease;
	private int worldBaseX, worldBaseY, worldSizeX, worldSizeY, worldPlane;
	private boolean worldInstanced;
	private NPC visitorNpc;
	private String attentionKey;
	private long attentionSerial, attentionTick = -1;
	private int attentionDefinitionId = -1;
	private boolean firstPersonalAttention, attentionWitnessed, visitorAmbiguous;
	private GameObject rootsObject;
	private long rootsSpawnSerial, rootsSpawnTick = -1;
	private int rootsSpawnDefinitionId = -1;
	private boolean rootsSpawnWitnessed, rootsSpawnNearby, rootsIngressPending;
	private final Map<String, InspectionTarget> inspectionProofs = new LinkedHashMap<>();
	private final Set<String> sceneryProbeAttempts = new HashSet<>();
	// A first missing proof gives the existing actor one more eligible tick to finish its probe.
	private InspectionTarget sceneryPendingProbe;

	@Inject
	public ScenarioObserver(Client client, ItemManager itemManager)
	{
		this.client = client; this.itemManager = itemManager;
		additionalSources = new AdditionalSourceCollector(client);
		groundOutcomes = new GroundOutcomeObserver(client);
		Arrays.fill(sharedItemIds, SharedItemSummary.UNKNOWN_ID); Arrays.fill(sharedItemQuantities, -1);
		rules.setScenarioEnabled(id -> id.matches("M0[1-9]|M10"));
	}
	public void setScenarioEnabled(Predicate<String> enabled) { rules.setScenarioEnabled(enabled); }
	public void setEmergencySceneActive(boolean active) { emergencySceneActive = active; }
	/** Fresh copied UI guards revoke dependent eligibility without advancing committed observation. */
	public void setLiveUiGuards(boolean outfitBlocked, boolean inventoryBlocked)
	{
		boolean guarded = outfitBlocked || inventoryBlocked;
		if (guarded && !liveInventoryBlocked)
		{
			additionalSources.invalidatePouchUi(); rules.invalidateAdditionalPouch(); rules.invalidateAdditionalItemUi();
		}
		liveOutfitBlocked = outfitBlocked; liveInventoryBlocked = guarded;
	}
	/** Callback bucket is the next committed observer tick, including packets preceding GameTick. */
	public void beginAdditionalInterval(long originalTick, long generation, long revision, long presentSinceTick, boolean witness)
	{
		openTick = originalTick; openGeneration = generation; openRevision = revision; openPresenceEpoch = presentSinceTick; openWitness = witness;
		additionalSources.beginInterval(originalTick, new SceneKey(generation, revision), presentSinceTick, witness);
		if (additionalSources.pouchSourceInvalidated()) { rules.invalidateAdditionalPouch(); }
	}
	public void onChatMessage(ChatMessage event)
	{
		OwnFollowerCopy follower = copiedOwnFollower(); refreshOwnFollowerLease(follower);
		long priorWaveSerial = additionalSources.currentTzhaarWaveSerial();
		TzhaarActivityCopy activity = AdditionalSourceCollector.requiresTzhaarBinding(event) ? copiedTzhaarActivity() : null;
		additionalSources.onChatMessage(event, follower, activity); refreshOwnFollowerLease(follower);
		if (priorWaveSerial != additionalSources.currentTzhaarWaveSerial()) { rules.invalidateAdditionalTzhaar(); }
		refreshAdditionalReportLeases();
		if (additionalSources.sourceInvalidated(SourceKind.GAME_MESSAGE) || additionalSources.sourceInvalidated(SourceKind.SPAM_MESSAGE))
		{ rules.invalidateAdditionalCannon(); }
	}
	public void onVarbitChanged(VarbitChanged event)
	{
		if (additionalSources.onVarbitChanged(event)) { refreshPouchLease(); }
		if (event.getVarpId() == 261) { refreshPuzzleLease(); }
		refreshCollectionLogLease();
	}
	/** Root also calls this before client-cycle validation so a pending edge cannot cross a current UNKNOWN. */
	public void refreshPouchLease()
	{
		if (!additionalSources.refreshPouchLease()) { rules.invalidateAdditionalPouch(); }
	}
	public void onPuzzleScriptPre(ScriptPreFired event)
	{ if (additionalSources.onPuzzleScriptPre(event)) { rules.invalidateAdditionalPuzzle(); } }
	public void onPuzzleScriptPost(ScriptPostFired event)
	{ if (additionalSources.onPuzzleScriptPost(event)) { rules.invalidateAdditionalPuzzle(); } }
	public void onPuzzleWidgetClosed(WidgetClosed event)
	{ if (additionalSources.onPuzzleWidgetClosed(event)) { rules.invalidateAdditionalPuzzle(); } }
	public void onPuzzleWidgetLoaded(WidgetLoaded event)
	{ if (additionalSources.onPuzzleWidgetLoaded(event)) { rules.invalidateAdditionalPuzzle(); } }
	public void refreshPuzzleLease()
	{ if (!additionalSources.refreshPuzzleLease()) { rules.invalidateAdditionalPuzzle(); } }
	public void setActiveSceneMotion(long candidateId, MotionHoldReceipt hold)
	{
		setActiveSceneMotion(candidateId, hold, false);
	}
	public void setActiveSceneMotion(long candidateId, MotionHoldReceipt hold, boolean committed)
	{
		activeSceneCandidateId = candidateId; activeSceneCommitted = candidateId >= 0 && committed; motionHold = hold;
		rules.setActiveSceneMotion(candidateId, hold, activeSceneCommitted);
	}
	/** Direct current relation lookup, without advancing committed observations or retaining another actor. */
	public void refreshOwnFollowerLease() { refreshOwnFollowerLease(copiedOwnFollower()); refreshAdditionalReportLeases(); }
	private void refreshOwnFollowerLease(OwnFollowerCopy follower)
	{
		if (rules.refreshOwnFollowerLease(follower)) { additionalSources.markFollowerChanged(); }
		additionalSources.refreshOwnCatLease(follower);
		if (additionalSources.sourceInvalidated(SourceKind.GAME_MESSAGE) || additionalSources.sourceInvalidated(SourceKind.FOLLOWER))
		{ rules.invalidateAdditionalKitten(0); }
		else if (additionalSources.hasPendingOwnCatFed(follower)) { rules.invalidateAdditionalKitten(follower.incarnationSerial); }
	}
	private TzhaarActivityCopy copiedTzhaarActivity()
	{ return TzhaarActivityCopy.capture(client, openTick, new SceneKey(openGeneration, openRevision), openPresenceEpoch, worldWitnessCurrent()); }
	private void refreshCollectionLogLease()
	{
		if ((additionalSources.hasPendingCollectionLogReport() || rules.hasAdditionalCollectionLogEvidence())
			&& (!additionalSources.refreshCollectionLogLease() || additionalSources.sourceInvalidated(SourceKind.GAME_MESSAGE)))
		{ rules.invalidateAdditionalCollectionLog(); }
	}
	/** Existing ClientTick follower refresh also refreshes only armed/issued report dependencies. */
	public void refreshAdditionalReportLeases()
	{
		refreshCollectionLogLease();
		if (additionalSources.hasTzhaarActivityLease() || rules.hasAdditionalTzhaarEvidence())
		{
			if (!additionalSources.refreshTzhaarActivity(copiedTzhaarActivity()) || additionalSources.sourceInvalidated(SourceKind.GAME_MESSAGE))
			{ rules.invalidateAdditionalTzhaar(); }
		}
	}
	public void setCancelledInterest(CancelledInterestObservation interest)
	{
		cancelledInterest = interest; rules.setCancelledInterest(interest);
	}
	public long getMotionRequestId(SceneCandidate candidate)
	{
		return candidateContinuationValid(candidate) ? rules.motionRequestId(candidate) : -1;
	}
	public void setMotionObservations(MotionObservation current, MotionObservation completion, long placementGeneration)
	{
		motion = current; motionCompletion = completion; this.placementGeneration = placementGeneration;
		rules.setMotionObservations(current, completion, placementGeneration);
	}

	/** Root calls on each immediate presence change, before later callbacks can become witnesses. */
	public void setPresence(boolean present, boolean userHidden, boolean autoAbsent)
	{
		groundOutcomes.setPresence(present && !userHidden && !autoAbsent);
		if (this.present && !present)
		{
			additionalSources.reset();
			clearWorldLeases(); cancelledInterest = null; openWitness = false;
			inventoryCopyDirty = equipmentCopyDirty = true;
			rules.presenceLost(); inspectionProofs.clear(); resetSceneryProbeCycle(); hits.clear(); loot.clear(); npcDeaths.clear(); localTargetChanges.clear(); witnessedSkills.clear();
			targetChangesUnknown = false;
		}
		this.present = present; this.userHidden = userHidden; this.autoAbsent = autoAbsent;
	}

	public List<SceneCandidate> closeGameTick(long tick, long generation, long revision, boolean present,
		long presentSinceTick, Integer companionWorldX, Integer companionWorldY, boolean directorIdle)
	{
		if (sessionGeneration != Long.MIN_VALUE && generation != sessionGeneration) { resetSession(); }
		if (sceneRevision != Long.MIN_VALUE && revision != sceneRevision) { clearSceneReferences(); }
		sessionGeneration = generation; sceneRevision = revision; this.tick = tick;
		Player player = client.getLocalPlayer(); WorldView view = player == null ? null : player.getWorldView();
		if (player != null) { lastPlayer = player; }
		ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		frame.tick = tick; frame.sessionGeneration = generation; frame.sceneRevision = revision;
		frame.present = present; frame.presentSinceTick = presentSinceTick; frame.userHidden = userHidden; frame.autoAbsent = autoAbsent;
		frame.valid = !incomplete && client.getGameState() == GameState.LOGGED_IN && player != null && view != null
			&& view.getScene() != null && view.getCollisionMaps() != null && player.getWorldLocation() != null;
		frame.player = player == null ? null : position(player.getWorldLocation());
		frame.dead = player != null && player.isDead(); frame.directorIdle = directorIdle;
		frame.emergencySceneActive = emergencySceneActive;
		frame.currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS); frame.realHp = client.getRealSkillLevel(Skill.HITPOINTS);
		frame.xp = client.getSkillExperiences(); frame.levels = client.getRealSkillLevels(); frame.boosted = client.getBoostedSkillLevels();
		frame.topLevel = view != null && view.isTopLevel(); frame.instanced = view != null && view.isInstance();
		frame.worldViewId = view == null ? Integer.MIN_VALUE : view.getId(); frame.placementGeneration = placementGeneration;
		frame.motion = motion; frame.motionCompletion = motionCompletion;
		frame.activeSceneCandidateId = activeSceneCandidateId; frame.activeSceneCommitted = activeSceneCommitted; frame.motionHold = motionHold;
		frame.cancelledInterest = cancelledInterest;
		int plane = frame.player == null ? -1 : frame.player.plane;
		frame.companion = companionWorldX == null || companionWorldY == null ? null : new Position(companionWorldX, companionWorldY, plane);
		Actor interacting = player == null ? null : player.getInteracting();
		if (interacting instanceof Player && OtherPlayerPolicy.ignoreOtherPlayers(client)) { interacting = null; }
		frame.interactionKnown = player != null; frame.interactionPresent = interacting != null;
		frame.observedFacing = player == null ? -1 : player.getCurrentOrientation();
		frame.animation = player == null ? -2 : player.getAnimation();
		if (interacting instanceof NPC) { frame.target = target((NPC) interacting); frame.targetUnknown = frame.target == null; }
		frame.liveNpcKeys = liveNpcKeys(view);
		frame.world = committedWorldSnapshot(frame, player, view);
		int[] copiedIds = sharedItemIds, copiedQuantities = sharedItemQuantities;
		if (inventoryCopyDirty) { copyContainer(InventoryID.INV, copiedIds, copiedQuantities, 0, 28); inventoryCopyDirty = !knownSlots(copiedIds, 0, 28); }
		if (equipmentCopyDirty) { copyContainer(InventoryID.WORN, copiedIds, copiedQuantities, 28, 14); equipmentCopyDirty = !knownSlots(copiedIds, 28, 14); }
		if (knownSlots(copiedIds, 0, 28))
		{
			frame.inventoryIds = Arrays.copyOf(copiedIds, 28); frame.inventoryQuantities = Arrays.copyOf(copiedQuantities, 28);
		}
		if (knownSlots(copiedIds, 28, 14))
		{
			frame.equipment = new int[] {copiedIds[28], copiedIds[29], copiedIds[32], copiedIds[35]}; frame.weaponId = copiedIds[31];
		}
		beginAdditionalInterval(tick, generation, revision, presentSinceTick, frame.present && frame.valid && !frame.dead && !frame.userHidden && !frame.autoAbsent);
		List<AdditionalUiSnapshot> roots = additionalSources.captureUi();
		frame.outfitBlocked = liveOutfitBlocked || rootVisible(roots, UiKind.BANK) || rootVisible(roots, UiKind.DEPOSIT);
		frame.inventoryBlocked = liveInventoryBlocked || frame.outfitBlocked || rootVisible(roots, UiKind.TRADE) || rootVisible(roots, UiKind.TRADE_CONFIRM);
		frame.inspectionSuppressed = frame.inventoryBlocked || frame.interactionPresent || inspectionSuppression.getAsBoolean();
		frame.objects = frame.valid && present ? inspectObjects(view, player, frame.companion, directorIdle && !frame.inspectionSuppressed) : Collections.emptyList();
		ScenarioBatch batch = new ScenarioBatch(localDeath, deathUserHidden, engagementInterrupted, hits, resolvedLoot(), witnessedSkills, npcDeaths, localTargetChanges, targetChangesUnknown);
		ScenarioSnapshot snapshot = frame.build();
		groundOutcomes.closeGameTick(snapshot, batch.loot);
		if (!groundOutcomes.markSourceKnown() || !groundOutcomes.refreshSelectedMark())
		{ additionalSources.invalidateGroundMark(); }
		OwnFollowerCopy follower = copiedOwnFollower(); refreshOwnFollowerLease(follower);
		refreshCollectionLogLease();
		TzhaarActivityCopy activity = additionalSources.hasTzhaarActivityLease() || rules.hasAdditionalTzhaarEvidence() ? copiedTzhaarActivity() : null;
		AdditionalSnapshot additional = additionalSources.commit(snapshot, copiedIds, copiedQuantities, follower, activity);
		rules.setAdditionalSourceAccounting(additionalSources.retainedNonSnapshotRecords(), additionalSources.retainedSnapshot());
		List<SceneCandidate> result = rules.close(snapshot, batch, additional);
		if (rules.didAdditionalOverflow()) { additionalSources.invalidateForReducerOverflow(); }
		clearBatch(); this.present = present;
		beginAdditionalInterval(tick + 1, generation, revision, presentSinceTick, snapshot.present && snapshot.valid && !snapshot.dead && !snapshot.userHidden && !snapshot.autoAbsent);
		npcs.entrySet().removeIf(entry -> entry.getValue().retiredTick >= 0 && tick - entry.getValue().retiredTick > 2);
		return result;
	}

	public void onHitsplatApplied(HitsplatApplied event)
	{
		Hitsplat splat = event.getHitsplat(); Actor actor = event.getActor();
		if (splat == null || actor == null) { return; }
		boolean local = isLocal(actor);
		if (!local && !splat.isMine() && OtherPlayerPolicy.ignoreOtherPlayers(client)) { return; }
		String npcKey = actor instanceof NPC ? identity((NPC) actor).key : null;
		if (!local && npcKey == null) { return; }
		if (hits.size() >= MAX_HITS) { incomplete = true; return; }
		hits.add(new Hit(++sequence, local, npcKey, splat.getHitsplatType(), splat.getAmount(), splat.isMine(), present));
	}
	public void onStatChanged(StatChanged event)
	{
		additionalSources.onStatChanged(event);
		if (present && event.getSkill() != null) { witnessedSkills.add(event.getSkill()); }
		else if (event.getSkill() != null) { rules.observeAbsentStat(event.getSkill(), event.getXp(), client.getRealSkillLevel(event.getSkill())); }
	}
	public void onActorDeath(ActorDeath event)
	{
		if (isLocal(event.getActor()))
		{
			localDeath = true; deathUserHidden = userHidden;
			rules.recordDeath(tick + 1, userHidden);
		}
		else if (event.getActor() instanceof NPC)
		{
			if (npcDeaths.size() >= MAX_HITS) { incomplete = true; return; }
			NpcIdentity identity = identity((NPC) event.getActor()); identity.deathObserved = true;
			rules.invalidateWorldVisitor(identity.key);
			rules.invalidateAdditionalKitten(identity.serial);
			npcDeaths.add(new NpcDeath(++sequence, identity.key, present));
		}
	}
	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getSource() instanceof NPC) { observeVisitorAttention((NPC) event.getSource(), event.getTarget()); }
		if (isLocal(event.getSource()))
		{
			if (event.getTarget() == visitorNpc) { rules.invalidateWorldVisitor(attentionKey); }
			engagementInterrupted = true;
			if (localTargetChanges.size() >= MAX_HITS) { incomplete = true; return; }
			Actor actor = event.getTarget();
			Target copied = actor instanceof NPC ? target((NPC) actor) : null;
			if (actor instanceof NPC && copied == null) { targetChangesUnknown = true; }
			localTargetChanges.add(copied == null ? null : copied.key);
		}
	}
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc(); if (npc == null) { return; }
		NpcIdentity old = npcs.get(npc);
		if (old != null && (old.retiredTick >= 0 || old.deathObserved)) { rules.invalidateAdditionalKitten(old.serial); npcs.remove(npc); }
		NpcIdentity identity = identity(npc);
		if (old == null || old.retiredTick >= 0 || old.deathObserved)
		{
			WorldPoint location = npc.getWorldLocation();
			identity.witnessedSpawnEpoch = worldWitnessCurrent() && npc.getWorldView() == worldViewLease
				&& location != null && location.getPlane() == worldPlane ? openPresenceEpoch : -1;
		}
	}
	public void onNpcDespawned(NpcDespawned event)
	{
		NpcIdentity identity = identity(event.getNpc()); identity.retiredTick = tick + 1;
		rules.invalidateWorldVisitor(identity.key);
		rules.invalidateAdditionalKitten(identity.serial);
	}
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		if (npc != null)
		{
			if (!npcs.containsKey(npc) && npcs.size() >= MAX_NPCS) { incomplete = true; return; }
			NpcIdentity old = npcs.get(npc); if (old != null) { rules.invalidateWorldVisitor(old.key); rules.invalidateAdditionalKitten(old.serial); }
			NpcIdentity replacement = new NpcIdentity(++spawnSequence); replacement.personalAttentionConsumed = true;
			npcs.put(npc, replacement);
			// A composition transformation invalidates an engagement; it is no witnessed target switch or spawn.
			targetChangesUnknown = true; engagementInterrupted = true;
		}
	}
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (event.getNpc() == null || event.getItems() == null) { return; }
		if (loot.size() >= MAX_REPORTS || event.getItems().size() > 128) { incomplete = true; return; }
		List<Stack> stacks = new ArrayList<>();
		for (ItemStack stack : event.getItems())
		{
			int id = stack.getId(), quantity = stack.getQuantity();
			long price = quantity > 0 ? itemManager.getItemPrice(id) : 0;
			stacks.add(new Stack(id, quantity, null, price));
		}
		loot.add(new Loot(++sequence, identity(event.getNpc()).key, present, stacks));
	}
	private List<Loot> resolvedLoot()
	{
		List<Loot> result = new ArrayList<>();
		for (Loot report : loot)
		{
			Stack selected = null;
			for (Stack stack : report.stacks)
			{
				if (stack.quantity > 0 && stack.total() >= 500_000 && (selected == null || stack.total() > selected.total()
					|| stack.total() == selected.total() && (stack.id < selected.id || stack.id == selected.id && stack.quantity < selected.quantity))) { selected = stack; }
			}
			String name = selected == null ? null : additionalSources.resolveReportedName(selected.id);
			List<Stack> stacks = new ArrayList<>();
			for (Stack stack : report.stacks) { stacks.add(new Stack(stack.id, stack.quantity, stack == selected ? name : null, stack.unitPrice)); }
			result.add(new Loot(report.id, report.npcKey, report.witnessed, stacks));
		}
		return result;
	}
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV) { inventoryCopyDirty = true; rules.invalidateAdditionalPouch(); }
		if (event.getContainerId() == InventoryID.WORN) { equipmentCopyDirty = true; }
		additionalSources.onItemContainerChanged(event);
	}
	public void onItemSpawned(ItemSpawned event)
	{
		MarkOfGraceSpawn copy = groundOutcomes.copyMarkSpawn(event);
		if (copy != null && !additionalSources.onMarkSpawn(copy)) { groundOutcomes.retireCopiedMark(copy.entitySerial); }
		if (!groundOutcomes.markSourceKnown()) { additionalSources.invalidateGroundMark(); }
	}
	public void onItemDespawned(ItemDespawned event)
	{ if (groundOutcomes.retireDespawnedItem(event)) { additionalSources.invalidateGroundMark(); } }
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{ if (groundOutcomes.retireQuantityChangedItem(event)) { additionalSources.invalidateGroundMark(); } }
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject object = event.getGameObject(); objectIdentity(object);
		if (object == null || !AdditionalWorldSensor.rootsDefinitionSupported(object.getId()) || rootsIngressPending) { return; }
		rootsObject = object; rootsIngressPending = true; rootsSpawnSerial = ++sequence; rootsSpawnTick = openTick;
		rootsSpawnDefinitionId = object.getId(); rootsSpawnWitnessed = worldWitnessCurrent();
		Player player = client.getLocalPlayer(); Position location = player == null ? null : position(player.getWorldLocation());
		RootsCopy copied = copiedRoots(); rootsSpawnNearby = copied.anchorKnown && copied.sameView && location != null
			&& location.plane == copied.plane && Math.max(Math.max(copied.minX - location.x, location.x - copied.maxX),
				Math.max(copied.minY - location.y, location.y - copied.maxY)) <= 6;
	}
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		GameObject object = event.getGameObject(); objects.remove(object);
		if (object == rootsObject) { rootsObject = null; rules.invalidateWorldRoots(); }
	}
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING) { resetScene(); }
		else if (event.getGameState() != GameState.LOGGED_IN) { resetSession(); }
	}
	public void resetScene()
	{
		clearSceneReferences(); rules.resetScene(); hits.clear(); loot.clear(); npcDeaths.clear(); localTargetChanges.clear(); targetChangesUnknown = false; witnessedSkills.clear(); present = false;
	}
	public void resetSession()
	{
		rules.resetSession(); groundOutcomes.resetSession(); clearSceneReferences(); clearBatch(); lastPlayer = null; present = false;
	}
	private void clearSceneReferences() { npcs.clear(); objects.clear(); inspectionProofs.clear(); resetSceneryProbeCycle(); motion = null; motionCompletion = null; motionHold = null; cancelledInterest = null; activeSceneCandidateId = -1; activeSceneCommitted = false; placementGeneration = -1; additionalSources.reset(); groundOutcomes.resetScene(); clearWorldLeases(); openWitness = false; inventoryCopyDirty = equipmentCopyDirty = true; }
	private void clearBatch()
	{
		hits.clear(); loot.clear(); npcDeaths.clear(); localTargetChanges.clear(); witnessedSkills.clear(); localDeath = false; deathUserHidden = false; engagementInterrupted = false; targetChangesUnknown = false; incomplete = false;
		visitorAmbiguous = false; rootsIngressPending = false;
	}
	public boolean isCombatConfirmed() { return rules.getCombatState() == ScenarioRules.CombatState.CONFIRMED; }
	public boolean isPlayerMoving() { return rules.isPlayerMoving(); }
	public void setFollowing(boolean following) { rules.setFollowing(following); }
	public void setCreatureObservation(com.malfoid.motion.CreatureAwareness.Sample sample,
		com.malfoid.motion.CreatureAwareness awareness) { rules.setCreatureObservation(sample, awareness); }
	public void setCreatureCurrentValidator(BiPredicate<String, String> validator) { creatureCurrent = validator; }
	public ScenarioRules.CombatState getCombatState() { return rules.getCombatState(); }
	public long getLastLocalDamageTick() { return rules.getLastLocalDamageTick(); }
	public long getLatestHeavyHitTick() { return rules.getLatestHeavyHitTick(); }
	public long getLatestDiscontinuityTick() { return rules.getLatestDiscontinuityTick(); }
	public boolean hasPendingDeath() { return rules.hasPendingDeath(); }
	public void firstPaint(String utteranceId, String scenarioId, long atTick, long generation, long revision)
	{
		rules.firstPaint(utteranceId, scenarioId, atTick, generation, revision);
	}
	public void firstPaint(String utteranceId, String scenarioId, long atTick, long generation, long revision, long expiresAt)
	{
		rules.firstPaint(utteranceId, scenarioId, atTick, generation, revision, expiresAt);
	}
	public void presentationVisible(String utteranceId, boolean visible) { rules.presentationVisible(utteranceId, visible); }
	public void softRevokeParent() { rules.softRevokeParent(); }
	public void reunionCompleted(long atTick) { rules.reunionCompleted(atTick); }
	public boolean emoteArrived(SceneCandidate candidate)
	{
		return candidateStillValid(candidate) && rules.emoteArrived(candidate);
	}
	public void inspectionCompleted(String key) { rules.inspectionCompleted(key); }
	public void inspectionCompleted(SceneCandidate candidate)
	{
		if (candidateContinuationValid(candidate)) { rules.inspectionCompleted(candidate); }
	}
	public boolean sceneryArrived(SceneCandidate candidate)
	{
		return candidateContinuationValid(candidate) && rules.sceneryArrived(candidate);
	}
	public boolean isLocalActor(Actor actor) { return isLocal(actor); }
	/** A semantic reason is reported only from a known current dependent fact, never a generic cancellation. */
	public SpeechCancellationReceipt.Reason cancellationReason(SceneCandidate candidate)
	{
		ScenarioSnapshot frame = rules.getCurrent(); Player player = client.getLocalPlayer();
		if (candidate == null || frame == null || !present || !frame.valid || frame.dead || player == null || player.isDead()
			|| incomplete
			|| client.getGameState() != GameState.LOGGED_IN || candidate.getSessionGeneration() != frame.sessionGeneration
			|| candidate.getSceneRevision() != frame.sceneRevision || player.getWorldView() == null || player.getWorldLocation() == null
			|| player.getWorldView().getId() != frame.worldViewId || player.getWorldLocation().getPlane() != frame.player.plane) { return null; }
		Map<String, String> facts = candidate.getConditionFacts(); String id = candidate.getScenarioId();
		if (id.matches("M(09|31|32|33|34|44)") && facts.containsKey("equipmentIds"))
		{
			int[] tuple = equipment();
			if (tuple != null && !facts.get("equipmentIds").equals(tuple[0] + "," + tuple[1] + "," + tuple[2] + "," + tuple[3]))
			{ return SpeechCancellationReceipt.Reason.DEPENDENT_OUTFIT_REVISION_CHANGED; }
		}
		if ("M08".equals(id) || "M69".equals(id) || ScenarioRules.isScenery(candidate))
		{
			String key = facts.get("targetKey");
			if (key == null) { return null; }
			for (Map.Entry<GameObject, String> entry : objects.entrySet())
			{
				if (!key.equals(entry.getValue())) { continue; }
				GameObject object = entry.getKey();
				if (object.getWorldView() != player.getWorldView() || object.getPlane() != frame.player.plane) { return null; }
				String name = objectName(object);
				return name == null || !name.equals(facts.get("objectName")) || (ScenarioRules.isScenery(candidate)
					? !sceneryDefinitionMatches(object, candidate) : ScenarioRules.objectKind(name) == null)
					? SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST : null;
			}
			return SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST;
		}
		return null;
	}
	public void onSpeechCancellation(SpeechCancellationReceipt receipt) { rules.onSpeechCancellation(receipt); }
	public CancelledInterestObservation.LossReason inspectionLossReason(SceneCandidate candidate)
	{
		if (cancellationReason(candidate) != SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST || !worldScopeMatches(client.getLocalPlayer())) { return null; }
		String key = candidate.getConditionFacts().get("targetKey");
		for (Map.Entry<GameObject, String> entry : objects.entrySet())
		{
			if (!entry.getValue().equals(key)) { continue; }
			String name = objectName(entry.getKey());
			if (name == null) { return CancelledInterestObservation.LossReason.NULL_RESOLUTION; }
			return (ScenarioRules.isScenery(candidate) ? livingWorldData == null || livingWorldData.scenery(name) == null
				: ScenarioRules.objectKind(name) == null) ? CancelledInterestObservation.LossReason.UNSUPPORTED : null;
		}
		return CancelledInterestObservation.LossReason.DESPAWNED;
	}
	public void executionPainted(SpeechPacket.PaintReceipt receipt)
	{
		rules.executionPainted(receipt);
		if (receipt != null) { groundOutcomeSettled(receipt.getCandidate()); }
	}
	/** C.4 only refines the original M05 execution; it never issues a later inventory opportunity. */
	public GroundOutcomeProof getGroundOutcomeProof(SceneCandidate candidate)
	{
		if (candidate == null || liveInventoryBlocked || !candidateStillValid(candidate)) { return null; }
		long report = rules.groundOutcomeReportId(candidate); if (report <= 0) { return null; }
		GroundOutcomeProof proof = groundOutcomes.revalidate(report, rules.getCurrent());
		Map<String, String> facts = candidate.getConditionFacts();
		return proof != null && proof.reportId == report && proof.reportTick == candidate.getCreatedTick()
			&& proof.originalDeadline == candidate.getExpiresTick() && proof.sessionGeneration == candidate.getSessionGeneration()
			&& proof.sceneRevision == candidate.getSceneRevision() && Integer.toString(proof.itemId).equals(facts.get("itemId"))
			&& Integer.toString(proof.reportedQuantity).equals(facts.get("quantity")) ? proof : null;
	}
	public boolean groundOutcomeLineStillValid(SceneCandidate candidate, String lineId)
	{
		if (!"M05-03".equals(lineId) && !"M05-04".equals(lineId) && !"M05-05".equals(lineId)) { return true; }
		GroundOutcomeProof proof = getGroundOutcomeProof(candidate);
		return proof != null && ("M05-05".equals(lineId) ? proof.itemPossessionConfirmed : proof.currentGroundAvailable);
	}
	public void groundOutcomeSettled(SceneCandidate candidate)
	{
		long report = rules.groundOutcomeReportId(candidate); if (report > 0) { groundOutcomes.settled(report); }
	}

	public boolean candidateStillValid(SceneCandidate candidate)
	{
		if (!present || incomplete || !rules.stillValid(candidate)) { return false; }
		return copiedFactsStillMatch(candidate);
	}
	public boolean candidateContinuationValid(SceneCandidate candidate)
	{
		if (!present || incomplete || !rules.continuationValid(candidate)) { return false; }
		return copiedFactsStillMatch(candidate);
	}
	private boolean copiedFactsStillMatch(SceneCandidate candidate)
	{
		String id = candidate.getScenarioId();
		if (com.malfoid.motion.CreatureAwareness.isE5(id)
			&& !creatureCurrent.test(candidate.getConditionFacts().get("creatureKey"), id)) { return false; }
		if (candidate.getAdditionalContext() != null && candidate.getAdditionalContext().ruleIndex == 123
			&& (liveInventoryBlocked || !additionalSources.currentPouchLeaseMatches(candidate.getAdditionalContext())))
		{ rules.invalidateAdditionalPouch(); return false; }
		if (liveOutfitBlocked && id.matches("M(09|31|32|33|34|44|47)")) { return false; }
		if (liveInventoryBlocked && (id.matches("M(08|3[5-9]|4[0-7]|49|5[1-9]|6[0-3]|69|70)") || ScenarioRules.isScenery(candidate))) { return false; }
		if (candidate.getAdditionalContext() != null && liveInventoryBlocked
			&& candidate.getAdditionalContext().ruleIndex >= 106 && candidate.getAdditionalContext().ruleIndex <= 140) { return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M91".equals(id)
			&& !groundOutcomes.currentMarkLeaseMatches(candidate.getAdditionalContext()))
		{ additionalSources.invalidateGroundMark(); return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M158".equals(id)
			&& (additionalSources.sourceInvalidated(SourceKind.GAME_MESSAGE) || additionalSources.sourceInvalidated(SourceKind.SPAM_MESSAGE)))
		{ rules.invalidateAdditionalCannon(); return false; }
		if (candidate.getAdditionalContext() != null && candidate.getAdditionalContext().ruleIndex != 123
			&& additionalSources.sourceInvalidated(candidate.getAdditionalContext().sourceKind)) { return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M142".equals(id)
			&& !additionalSources.currentCollectionLogLeaseMatches(candidate.getAdditionalContext()))
		{ rules.invalidateAdditionalCollectionLog(); return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M160".equals(id)
			&& !additionalSources.currentSepulchreLeaseMatches(candidate.getAdditionalContext())) { return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M162".equals(id)
			&& !additionalSources.currentTzhaarLeaseMatches(candidate.getAdditionalContext(), copiedTzhaarActivity()))
		{ rules.invalidateAdditionalTzhaar(); return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M118".equals(id)
			&& !additionalSources.currentDodgyLeaseMatches(candidate.getAdditionalContext())) { return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && "M128".equals(id))
		{
			OwnFollowerCopy follower = copiedOwnFollower(); refreshOwnFollowerLease(follower);
			if (!additionalSources.currentOwnCatLeaseMatches(candidate.getAdditionalContext(), follower))
			{ rules.invalidateAdditionalKitten(candidate.getAdditionalContext().payload.entitySerial); return false; }
		}
		if (candidate.getAdditionalContext() != null && candidate.getAdditionalContext().sourceKind == com.malfoid.additional.AdditionalTypes.SourceKind.UI
			&& !additionalSources.currentUiLeaseMatches(candidate.getAdditionalContext())) { return additionalFactLost(candidate); }
		if (candidate.getAdditionalContext() != null && ("M93".equals(id) || "M131".equals(id) || "M132".equals(id))
			&& !additionalSources.currentScalarLeaseMatches(candidate.getAdditionalContext())) { return additionalFactLost(candidate); }
		if ("M150".equals(id) || "M167".equals(id))
		{
			ScenarioSnapshot frame = rules.getCurrent(); Player player = client.getLocalPlayer();
			WorldSnapshot live = copiedWorldSnapshot(frame.tick, frame.sessionGeneration, frame.sceneRevision, frame.presentSinceTick, !worldScopeMatches(player));
			Position currentPlayer = player == null ? null : position(player.getWorldLocation());
			boolean range = !"M150".equals(id) || currentPlayer != null
				&& currentPlayer.distance(new Position(live.visitorX, live.visitorY, live.visitorPlane)) <= 6;
			if (!range || !rules.worldContinuationValid(candidate, live))
			{
				if ("M150".equals(id)) { rules.invalidateWorldVisitor(candidate.getConditionFacts().get("visitorKey")); }
				else { rules.invalidateWorldRoots(); }
				return false;
			}
		}
		Map<String, String> facts = candidate.getConditionFacts();
		if ("M14".equals(candidate.getScenarioId()) && (!npcKeyLive(facts.get("firstNpcKey")) || !npcKeyLive(facts.get("secondNpcKey")))) { return false; }
		if (facts.containsKey("equipmentIds"))
		{
			int[] current = equipment();
			if (current == null || !facts.get("equipmentIds").equals(current[0] + "," + current[1] + "," + current[2] + "," + current[3])) { return false; }
		}
		if (facts.containsKey("targetKey") && !"M08".equals(candidate.getScenarioId()) && !"M69".equals(candidate.getScenarioId()) && !ScenarioRules.isScenery(candidate))
		{
			Player player = client.getLocalPlayer(); Actor actor = player == null ? null : player.getInteracting();
			if (!(actor instanceof NPC)) { return false; }
			Target now = target((NPC) actor);
			if (now == null || now.name == null || !now.alive || !now.key.equals(facts.get("targetKey")) || !now.name.equals(facts.get("npcResolvedName"))) { return false; }
		}
		if ("M08".equals(candidate.getScenarioId()) || "M69".equals(candidate.getScenarioId()) || ScenarioRules.isScenery(candidate))
		{
			for (Map.Entry<GameObject, String> object : objects.entrySet())
			{
				if (object.getValue().equals(facts.get("targetKey")))
				{
					GameObject live = object.getKey(); Player player = client.getLocalPlayer();
					return player != null && live.getWorldView() == player.getWorldView() && player.getWorldLocation() != null
						&& live.getPlane() == player.getWorldLocation().getPlane() && facts.get("objectName").equals(objectName(live)) && footprintStillMatches(live, facts)
						&& (!ScenarioRules.isScenery(candidate) || sceneryDefinitionMatches(live, candidate));
				}
			}
			return false;
		}
		return true;
	}
	private boolean additionalFactLost(SceneCandidate candidate) { rules.revokeAdditionalCandidate(candidate); return false; }
	private boolean npcKeyLive(String key)
	{
		Player player = client.getLocalPlayer(); if (player == null || player.getWorldView() == null || player.getWorldLocation() == null || key == null) { return false; }
		for (Map.Entry<NPC, NpcIdentity> entry : npcs.entrySet())
		{
			NpcIdentity identity = entry.getValue(); NPC npc = entry.getKey();
			if (key.equals(identity.key))
			{
				return !identity.deathObserved && identity.retiredTick < 0 && !npc.isDead() && npc.getWorldView() == player.getWorldView()
					&& npc.getWorldLocation() != null && npc.getWorldLocation().getPlane() == player.getWorldLocation().getPlane() && npc.getTransformedComposition() != null;
			}
		}
		return false;
	}
	private void observeVisitorAttention(NPC npc, Actor target)
	{
		NpcIdentity identity = identity(npc);
		if (npc == visitorNpc && !isLocal(target)) { rules.invalidateWorldVisitor(identity.key); }
		if (!isLocal(target)) { return; }
		boolean first = !identity.personalAttentionConsumed; identity.personalAttentionConsumed = true;
		if (!first) { return; }
		if (attentionTick == openTick && attentionKey != null && !attentionKey.equals(identity.key))
		{ visitorAmbiguous = true; rules.invalidateWorldVisitor(null); }
		Player player = client.getLocalPlayer(); NPCComposition composition = npc.getTransformedComposition();
		visitorNpc = npc; attentionKey = identity.key; attentionSerial = ++sequence; attentionTick = openTick;
		attentionDefinitionId = composition == null ? -1 : composition.getId();
		firstPersonalAttention = player != null && target == player && player.getInteracting() != npc;
		Position npcPosition = position(npc.getWorldLocation()), playerPosition = player == null ? null : position(player.getWorldLocation());
		attentionWitnessed = firstPersonalAttention && identity.witnessedSpawnEpoch == openPresenceEpoch && worldWitnessCurrent()
			&& npc.getWorldView() == worldViewLease && npcPosition != null && npcPosition.distance(playerPosition) <= 6;
	}
	private boolean worldWitnessCurrent()
	{
		ScenarioSnapshot frame = rules.getCurrent(); Player player = client.getLocalPlayer();
		return openWitness && present && !userHidden && !autoAbsent && frame != null && frame.valid && frame.present && !frame.dead
			&& frame.sessionGeneration == openGeneration && frame.sceneRevision == openRevision && frame.presentSinceTick == openPresenceEpoch
			&& openTick == frame.tick + 1 && client.getGameState() == GameState.LOGGED_IN && player != null && !player.isDead()
			&& worldScopeMatches(player);
	}
	private OwnFollowerCopy copiedOwnFollower()
	{
		SceneKey scene = new SceneKey(openGeneration, openRevision); Player player = client.getLocalPlayer();
		if (!worldWitnessCurrent() || player == null || player.isDead() || client.getGameState() != GameState.LOGGED_IN)
		{ return new OwnFollowerCopy(openTick, scene, openPresenceEpoch, Truth.UNKNOWN, 0, -1); }
		NPC npc = client.getFollower();
		if (npc == null) { return new OwnFollowerCopy(openTick, scene, openPresenceEpoch, Truth.FALSE, 0, -1); }
		NpcIdentity identity = identity(npc); NPCComposition composition = npc.getTransformedComposition(); WorldPoint point = npc.getWorldLocation();
		boolean known = !incomplete && !identity.deathObserved && identity.retiredTick < 0 && !npc.isDead()
			&& npc.getWorldView() == player.getWorldView() && point != null && point.getPlane() == player.getWorldLocation().getPlane()
			&& composition != null && composition.getId() >= 0;
		return new OwnFollowerCopy(openTick, scene, openPresenceEpoch, known ? Truth.TRUE : Truth.UNKNOWN,
			known ? identity.serial : 0, known ? composition.getId() : -1);
	}
	private boolean worldScopeMatches(Player player)
	{
		WorldView view = player == null ? null : player.getWorldView(); WorldPoint point = player == null ? null : player.getWorldLocation();
		return view != null && point != null && view == worldViewLease && view.getScene() == worldSceneLease
			&& view.getBaseX() == worldBaseX && view.getBaseY() == worldBaseY && view.getSizeX() == worldSizeX && view.getSizeY() == worldSizeY
			&& view.getPlane() == worldPlane && point.getPlane() == worldPlane && view.isInstance() == worldInstanced;
	}
	private WorldSnapshot committedWorldSnapshot(ScenarioSnapshot.Builder frame, Player player, WorldView view)
	{
		ScenarioSnapshot old = rules.getCurrent(); boolean changed = !worldScopeMatches(player);
		boolean baseline = changed || old == null || frame.tick != old.tick + 1 || frame.sessionGeneration != old.sessionGeneration
			|| frame.sceneRevision != old.sceneRevision || frame.presentSinceTick != old.presentSinceTick;
		if (changed)
		{
			rules.invalidateWorldVisitor(null); rules.invalidateWorldRoots();
			worldViewLease = view; worldSceneLease = view == null ? null : view.getScene();
			if (view != null)
			{
				worldBaseX = view.getBaseX(); worldBaseY = view.getBaseY(); worldSizeX = view.getSizeX(); worldSizeY = view.getSizeY(); worldPlane = view.getPlane(); worldInstanced = view.isInstance();
			}
		}
		return copiedWorldSnapshot(frame.tick, frame.sessionGeneration, frame.sceneRevision, frame.presentSinceTick, baseline);
	}
	private WorldSnapshot copiedWorldSnapshot(long atTick, long generation, long revision, long epoch, boolean baseline)
	{
		Player player = client.getLocalPlayer();
		return new WorldSnapshot(atTick, new SceneKey(generation, revision), epoch, baseline, copiedVisitor(player), copiedRoots());
	}
	private VisitorCopy copiedVisitor(Player player)
	{
		NpcIdentity identity = visitorNpc == null ? null : npcs.get(visitorNpc);
		NPCComposition composition = visitorNpc == null ? null : visitorNpc.getTransformedComposition();
		WorldPoint point = visitorNpc == null ? null : visitorNpc.getWorldLocation();
		boolean known = !visitorAmbiguous && !incomplete && identity != null && composition != null && point != null && worldScopeMatches(player);
		return new VisitorCopy(known, identity == null ? null : identity.key, composition == null ? -1 : composition.getId(),
			composition == null ? null : composition.getName(), point == null ? 0 : point.getX(), point == null ? 0 : point.getY(), point == null ? -1 : point.getPlane(),
			identity != null && !identity.deathObserved && identity.retiredTick < 0 && !visitorNpc.isDead(),
			visitorNpc != null && visitorNpc.getWorldView() == worldViewLease && worldScopeMatches(player),
			player == null || visitorNpc == null ? Truth.UNKNOWN : visitorNpc.getInteracting() == player ? Truth.TRUE : Truth.FALSE,
			player == null || visitorNpc == null ? Truth.UNKNOWN : player.getInteracting() == visitorNpc ? Truth.TRUE : Truth.FALSE,
			attentionSerial, attentionTick, attentionKey, attentionDefinitionId, firstPersonalAttention, attentionWitnessed);
	}
	private RootsCopy copiedRoots()
	{
		GameObject object = rootsObject;
		net.runelite.api.Point min = object == null ? null : object.getSceneMinLocation(), max = object == null ? null : object.getSceneMaxLocation();
		boolean known = object != null && objects.containsKey(object) && min != null && max != null && max.getX() >= min.getX() && max.getY() >= min.getY();
		// The existing inspection index covers a neighborhood. It cannot certify a whole-scene inactive Roots phase.
		return new RootsCopy(false, Truth.UNKNOWN, known, object != null && object.getWorldView() == worldViewLease,
			object == null ? -1 : object.getId(), min == null ? 0 : worldBaseX + min.getX(), min == null ? 0 : worldBaseY + min.getY(),
			max == null ? 0 : worldBaseX + max.getX(), max == null ? 0 : worldBaseY + max.getY(), object == null ? -1 : object.getPlane(),
			rootsSpawnSerial, rootsSpawnTick, rootsSpawnDefinitionId, rootsSpawnWitnessed, rootsSpawnNearby);
	}
	private void clearWorldLeases()
	{
		worldViewLease = null; worldSceneLease = null; visitorNpc = null; rootsObject = null; attentionKey = null;
		attentionSerial = rootsSpawnSerial = 0; attentionTick = rootsSpawnTick = -1; attentionDefinitionId = rootsSpawnDefinitionId = -1;
		firstPersonalAttention = attentionWitnessed = visitorAmbiguous = rootsSpawnWitnessed = rootsSpawnNearby = rootsIngressPending = false;
	}

	private boolean isLocal(Actor actor)
	{
		Player player = client.getLocalPlayer();
		return actor != null && (actor == player || player == null && actor == lastPlayer);
	}
	private NpcIdentity identity(NPC npc)
	{
		NpcIdentity known = npcs.get(npc);
		if (known == null)
		{
			if (npcs.size() >= MAX_NPCS) { incomplete = true; return new NpcIdentity(++spawnSequence, true); }
			known = new NpcIdentity(++spawnSequence); npcs.put(npc, known);
		}
		return known;
	}
	private Target target(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null || npc.getWorldView() == null || client.getLocalPlayer() == null || npc.getWorldView() != client.getLocalPlayer().getWorldView()) { return null; }
		NpcIdentity identity = identity(npc);
		String name = composition.getName();
		return new Target(identity.key, name, !identity.deathObserved && !npc.isDead() && identity.retiredTick < 0, position(npc.getWorldLocation()));
	}
	private static Position position(WorldPoint point) { return point == null ? null : new Position(point.getX(), point.getY(), point.getPlane()); }
	private Set<String> liveNpcKeys(WorldView view)
	{
		if (view == null || view.npcs() == null) { return null; }
		Set<String> result = new HashSet<>(); int copied = 0;
		for (NPC npc : view.npcs())
		{
			if (copied++ >= MAX_NPCS) { return null; }
			if (npc != null && npc.getWorldView() == view && !npc.isDead())
			{
				NpcIdentity identity = identity(npc); if (!identity.deathObserved && identity.retiredTick < 0) { result.add(identity.key); }
			}
		}
		return result;
	}
	private int[] equipment()
	{
		ItemContainer container = client.getItemContainer(InventoryID.WORN);
		if (container == null) { return null; }
		Item[] items = container.getItems(); if (items == null) { return null; }
		int[] result = new int[4]; int[] slots = {0, 1, 4, 7};
		for (int i = 0; i < slots.length; i++) { result[i] = slots[i] < items.length && items[slots[i]] != null ? items[slots[i]].getId() : -1; }
		return result;
	}
	private void copyContainer(int containerId, int[] ids, int[] quantities, int offset, int size)
	{
		Arrays.fill(ids, offset, offset + size, SharedItemSummary.UNKNOWN_ID); Arrays.fill(quantities, offset, offset + size, -1);
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null) { return; }
		Item[] items = container.getItems();
		if (items == null || items.length > size) { return; }
		for (int i = 0; i < size; i++)
		{
			Item item = i < items.length ? items[i] : null;
			if (item == null || item.getId() == -1 && item.getQuantity() == 0) { ids[offset + i] = -1; quantities[offset + i] = 0; }
			else if (item.getId() >= 0 && item.getQuantity() > 0) { ids[offset + i] = item.getId(); quantities[offset + i] = item.getQuantity(); }
		}
	}
	private static boolean knownSlots(int[] ids, int offset, int size)
	{
		for (int i = offset; i < offset + size; i++) { if (ids[i] == SharedItemSummary.UNKNOWN_ID) { return false; } }
		return true;
	}
	private static boolean rootVisible(List<AdditionalUiSnapshot> roots, UiKind kind)
	{
		for (AdditionalUiSnapshot root : roots) { if (root.kind == kind) { return root.visibility != Truth.FALSE; } }
		return true;
	}

	private String objectIdentity(GameObject object)
	{
		if (object == null) { return null; }
		String key = objects.get(object);
		if (key == null && objects.size() < MAX_OBJECTS) { key = "object:" + (++objectSequence); objects.put(object, key); }
		return key;
	}
	private ObjectComposition objectDefinition(GameObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null) { return null; }
		if (composition.getImpostorIds() != null) { composition = composition.getImpostor(); }
		return composition;
	}
	private String objectName(GameObject object)
	{
		ObjectComposition composition = objectDefinition(object);
		return composition == null ? null : composition.getName();
	}
	private String scenerySessionKey(GameObject object)
	{
		WorldView view = object.getWorldView(); net.runelite.api.Point min = object.getSceneMinLocation(), max = object.getSceneMaxLocation();
		if (view == null || min == null || max == null) { return null; }
		return "scenery:" + view.getId() + ":" + view.isInstance() + ":" + object.getId() + ":" + object.getPlane()
			+ ":" + (min.getX() + view.getBaseX()) + "," + (min.getY() + view.getBaseY())
			+ "," + (max.getX() + view.getBaseX()) + "," + (max.getY() + view.getBaseY());
	}
	private boolean sceneryDefinitionMatches(GameObject object, SceneCandidate candidate)
	{
		ObjectComposition definition = objectDefinition(object); String name = definition == null ? null : definition.getName();
		LivingWorldData.SceneryGroup group = livingWorldData == null || ScenarioRules.objectKind(name) != null ? null : livingWorldData.scenery(name);
		return group != null && group.id.equals(candidate.getScenarioId())
			&& candidate.getConditionFacts().get("scenerySessionKey").equals(scenerySessionKey(object))
			&& Integer.toString(definition.getId()).equals(candidate.getConditionFacts().get("objectDefinitionId"));
	}
	private List<InspectionTarget> inspectObjects(WorldView view, Player player, Position companion, boolean allowProbe)
	{
		List<InspectionTarget> result = new ArrayList<>();
		if (reachability == null || player.getLocalLocation() == null) { return result; }
		ScenarioSnapshot prior = rules.getCurrent(); Position playerPoint = position(player.getWorldLocation());
		if (prior == null || prior.worldViewId != view.getId() || !playerPoint.equals(prior.player)
			|| companion == null || !companion.equals(prior.companion)) { resetSceneryProbeCycle(); }
		List<InspectionTarget> proposals = new ArrayList<>(), sceneryProposals = new ArrayList<>(), sceneryRestart = new ArrayList<>();
		Set<String> liveKeys = new HashSet<>(), liveSceneryKeys = new HashSet<>(); InspectionTarget repeatedProbe = null;
		boolean allowSceneryProbe = allowProbe && sceneryStartAllowed.getAsBoolean();
		Tile[][][] tiles = view.getScene().getTiles(); int plane = view.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null) { return result; }
		int px = player.getLocalLocation().getSceneX(), py = player.getLocalLocation().getSceneY();
		SetIdentity<GameObject> considered = new SetIdentity<>();
		for (int x = Math.max(0, px - 6); x <= Math.min(tiles[plane].length - 1, px + 6); x++)
		{
			if (tiles[plane][x] == null) { continue; }
			for (int y = Math.max(0, py - 6); y <= Math.min(tiles[plane][x].length - 1, py + 6); y++)
			{
				Tile tile = tiles[plane][x][y]; if (tile == null || tile.getGameObjects() == null) { continue; }
				for (GameObject object : tile.getGameObjects())
				{
					if (object == null || !considered.add(object) || object.getWorldView() != view || object.getPlane() != plane) { continue; }
					ObjectComposition definition = objectDefinition(object); String name = definition == null ? null : definition.getName();
					boolean m08 = ScenarioRules.objectKind(name) != null;
					if (!m08 && (livingWorldData == null || livingWorldData.scenery(name) == null)) { continue; }
					String key = objectIdentity(object); if (key == null) { continue; }
					net.runelite.api.Point min = object.getSceneMinLocation(), max = object.getSceneMaxLocation();
					if (min == null || max == null) { continue; }
					InspectionTarget footprint = new InspectionTarget(key, name, min.getX() + view.getBaseX(), min.getY() + view.getBaseY(),
						max.getX() + view.getBaseX(), max.getY() + view.getBaseY(), plane, null, -1,
						m08 ? null : scenerySessionKey(object), m08 ? -1 : definition.getId());
					liveKeys.add(key); InspectionTarget proof = inspectionProofs.get(key);
					if (proof != null && sameFootprint(proof, footprint) && !allowProbe) { result.add(proof); }
					else
					{
						if (proof != null && !sameFootprint(proof, footprint)) { inspectionProofs.remove(key); }
						int distance = footprint.distance(position(player.getWorldLocation()));
						if (distance >= 2 && distance <= 6)
						{
							if (m08 && rules.canInspectIdentity(key) && proposals.size() < 16) { proposals.add(footprint); }
							else if (!m08 && allowSceneryProbe && rules.canInspectScenery(footprint))
							{
								liveSceneryKeys.add(key); addSceneryProposal(sceneryRestart, footprint, playerPoint);
								if (!sceneryProbeAttempts.contains(key)) { addSceneryProposal(sceneryProposals, footprint, playerPoint); }
								if (sceneryPendingProbe != null && sameFootprint(sceneryPendingProbe, footprint)) { repeatedProbe = footprint; }
							}
						}
					}
				}
			}
		}
		for (Map.Entry<GameObject, String> known : objects.entrySet())
		{
			InspectionTarget proof = inspectionProofs.get(known.getValue()); GameObject object = known.getKey();
			if (proof == null || liveKeys.contains(proof.key) || object.getWorldView() != view || object.getPlane() != plane) { continue; }
			ObjectComposition definition = objectDefinition(object); String name = definition == null ? null : definition.getName();
			net.runelite.api.Point min = object.getSceneMinLocation(), max = object.getSceneMaxLocation();
			if (name == null || min == null || max == null) { continue; }
			InspectionTarget footprint = new InspectionTarget(proof.key, name, min.getX() + view.getBaseX(), min.getY() + view.getBaseY(), max.getX() + view.getBaseX(), max.getY() + view.getBaseY(), plane, null, -1,
				proof.scenerySessionKey == null ? null : scenerySessionKey(object), proof.scenerySessionKey == null ? -1 : definition.getId());
			if (sameFootprint(proof, footprint)) { liveKeys.add(proof.key); if (!allowProbe) { result.add(proof); } }
		}
		inspectionProofs.keySet().removeIf(key -> !liveKeys.contains(key));
		if (allowSceneryProbe)
		{
			sceneryProbeAttempts.retainAll(liveSceneryKeys);
			if (sceneryPendingProbe != null && repeatedProbe == null) { sceneryPendingProbe = null; }
			if (sceneryProposals.isEmpty() && !sceneryRestart.isEmpty())
			{
				resetSceneryProbeCycle(); sceneryProposals.addAll(sceneryRestart);
			}
		}
		if (allowProbe && (!proposals.isEmpty() || !sceneryProposals.isEmpty()))
		{
			Position point = position(player.getWorldLocation());
			proposals.sort(Comparator.comparingInt((InspectionTarget target) -> target.distance(point)).thenComparing(target -> target.key));
			boolean scenery = proposals.isEmpty();
			InspectionTarget next = scenery ? (repeatedProbe != null ? repeatedProbe : sceneryProposals.get(0)) : proposals.get(0);
			InspectionTarget proof = reachability.inspect(next.key, next.name, next.minX, next.minY, next.maxX, next.maxY, next.plane);
			if (proof != null && next.scenerySessionKey != null)
			{
				proof = new InspectionTarget(proof.key, proof.name, proof.minX, proof.minY, proof.maxX, proof.maxY,
					proof.plane, proof.stand, proof.pathSteps, next.scenerySessionKey, next.resolvedDefinitionId);
			}
			if (proof != null && sameFootprint(proof, next) && proof.stand != null && proof.pathSteps >= 0 && proof.pathSteps <= 8)
			{
				if (inspectionProofs.size() >= 16) { inspectionProofs.remove(inspectionProofs.keySet().iterator().next()); }
				inspectionProofs.put(proof.key, proof); result.add(proof);
				if (scenery) { sceneryPendingProbe = null; }
			}
			else if (scenery)
			{
				if (repeatedProbe != null) { sceneryProbeAttempts.add(next.key); sceneryPendingProbe = null; }
				else { sceneryPendingProbe = next; }
			}
		}
		return result;
	}
	private void addSceneryProposal(List<InspectionTarget> proposals, InspectionTarget target, Position player)
	{
		proposals.add(target); proposals.sort((a, b) -> rules.compareScenery(a, b, player));
		if (proposals.size() > 16) { proposals.remove(16); }
	}
	private void resetSceneryProbeCycle() { sceneryProbeAttempts.clear(); sceneryPendingProbe = null; }
	private static boolean sameFootprint(InspectionTarget a, InspectionTarget b)
	{
		return a.key.equals(b.key) && a.name.equals(b.name) && a.plane == b.plane && a.minX == b.minX && a.minY == b.minY && a.maxX == b.maxX && a.maxY == b.maxY
			&& a.resolvedDefinitionId == b.resolvedDefinitionId && (a.scenerySessionKey == null ? b.scenerySessionKey == null : a.scenerySessionKey.equals(b.scenerySessionKey));
	}
	private boolean footprintStillMatches(GameObject object, Map<String, String> facts)
	{
		net.runelite.api.Point min = object.getSceneMinLocation(), max = object.getSceneMaxLocation(); WorldView view = object.getWorldView();
		return min != null && max != null && view != null && Integer.toString(min.getX() + view.getBaseX()).equals(facts.get("footprintMinX"))
			&& Integer.toString(min.getY() + view.getBaseY()).equals(facts.get("footprintMinY")) && Integer.toString(max.getX() + view.getBaseX()).equals(facts.get("footprintMaxX"))
			&& Integer.toString(max.getY() + view.getBaseY()).equals(facts.get("footprintMaxY"));
	}
	public void setInspectionReachability(Reachability reachability) { this.reachability = reachability; }
	public void setInspectionSuppression(BooleanSupplier suppression) { inspectionSuppression = suppression; }
	public void setLivingWorldData(LivingWorldData data) { livingWorldData = data; rules.setLivingWorldData(data); }
	public void setSceneryStartAllowed(BooleanSupplier allowed) { sceneryStartAllowed = allowed; rules.setSceneryStartAllowed(allowed); }
	public interface Reachability
	{
		InspectionTarget inspect(String key, String resolvedName, int minWorldX, int minWorldY, int maxWorldX, int maxWorldY, int plane);
	}
	private static final class NpcIdentity
	{
		final String key; final long serial; long retiredTick = -1, witnessedSpawnEpoch = -1; boolean deathObserved, personalAttentionConsumed;
		NpcIdentity(long serial) { this(serial, false); }
		NpcIdentity(long serial, boolean overflow) { this.serial = serial; key = (overflow ? "overflow:" : "npc:") + serial; }
	}
	private static final class SetIdentity<T>
	{
		private final Map<T, Boolean> entries = new IdentityHashMap<>();
		boolean add(T value) { return entries.put(value, Boolean.TRUE) == null; }
	}
}
