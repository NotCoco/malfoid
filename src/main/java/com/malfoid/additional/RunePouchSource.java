package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ResourceKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import java.util.Objects;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;

/** Client-thread-only, event-dirtied source for the uniquely carried physical standard pouch12791. */
public final class RunePouchSource
{
	public static final int MAX_DIRTY_TICKS = 12, MAX_PROFILE_READS = 13, MAX_PACKED_READS = 8;
	public static final int RETAINED_WATCHER_RECORDS = 1, FIXED_STATE_BYTES = 384;
	private static final int[] VARPS = {1139, 1140, 3574, 4017}, MASKS = {-1, 0x0fffffff, -1, 0x0fffffff};
	// The paired type/quantity definitions are the exact decoded cache2735/build241 triples.
	private static final int[] VARBITS = {29, 1622, 1623, 14285, 15373, 15374, 1624, 1625, 1626, 14286, 15375, 15376};
	private static final int[] WORD = {0, 0, 0, 2, 2, 2, 0, 1, 1, 2, 3, 3};
	private static final int[] LEAST = {0, 6, 12, 0, 20, 26, 18, 0, 14, 6, 0, 14};
	private static final int[] MOST = {5, 11, 17, 5, 25, 31, 31, 13, 27, 19, 13, 27};
	private static final int[] RUNE_IDS = {556, 555, 557, 554, 558, 562, 560, 565, 564, 561, 563, 559,
		566, 9075, 4695, 4698, 4696, 4699, 4694, 4697, 21880, 28929, 30843};
	private SceneKey scene;
	private SharedItemSummary current;
	private long tick = -1, epoch, lastCommitTick = -1, associationRevision, domainRevision, quantitySerial;
	private long dirtyTick = -1, confirmationTick = -1, retiredEmptySerial;
	private boolean witnessed, associationPending = true, profileKnown, requested, continuous, proven, changed;

	/** The observer's original committed ordinal is authoritative; never infer it from client tick counters. */
	public void beginInterval(long occurrenceTick, SceneKey key, long presenceEpoch, boolean presentWitness)
	{
		Objects.requireNonNull(key);
		boolean discontinuity = scene == null || !scene.equals(key) || epoch != presenceEpoch || witnessed != presentWitness
			|| occurrenceTick != tick && occurrenceTick != tick + 1;
		if (discontinuity)
		{
			breakProof(); invalidateAssociation(); profileKnown = false; current = null;
			lastCommitTick = -1; dirtyTick = occurrenceTick; confirmationTick = -1; requested = true; changed = true;
		}
		else if (tick != occurrenceTick) { changed = false; }
		tick = occurrenceTick; scene = key; epoch = presenceEpoch; witnessed = presentWitness;
	}
	/** No container walk or getter; a remove/restore cannot recover the old association lease. */
	public boolean onItemContainerChanged(ItemContainerChanged event)
	{
		if (event == null || event.getContainerId() != 93) { return false; }
		breakProof(); invalidateAssociation(); request(); changed = true; return true;
	}
	/** Only this vector's twelve fields or four backing words arm the bounded server-agreement watch. */
	public boolean onVarbitChanged(VarbitChanged event)
	{
		if (event == null || !contains(VARBITS, event.getVarbitId()) && !contains(VARPS, event.getVarpId())) { return false; }
		request(); return true;
	}
	/** Immediate common UI UNKNOWN/busy loss, before a later close could reuse old zero ownership. */
	public void invalidateUi()
	{
		boolean wasProven = proven; breakProof(); invalidateAssociation(); if (wasProven) { request(); } changed = true;
	}
	private void request()
	{
		if (scene == null || tick < 0 || !witnessed) { return; }
		if (dirtyTick < 0) { dirtyTick = tick; }
		requested = true;
	}
	/** Definite negative state only; a type-only dirty callback still gets its factual equality probe. */
	public boolean invalidated() { return scene == null || !witnessed || associationPending || !profileKnown || !proven; }
	public boolean changed() { return changed; }
	public boolean pending() { return dirtyTick >= 0; }
	public long originalDirtyTick() { return dirtyTick; }
	/** Metadata-only dependency check; initial/recovered zero and an expired/retired cause require no live reads. */
	public boolean hasCurrentEmptyCause()
	{
		return current != null && current.pouchKnown && current.pouchEmpty && current.pouchContentSerial > 0
			&& current.pouchContentSerial != retiredEmptySerial && current.pouchContentTick >= 0
			&& lastCommitTick >= current.pouchContentTick && lastCommitTick - current.pouchContentTick < MAX_DIRTY_TICKS;
	}

	/** One common committed item summary, at most one eight-word sample per ordinal. No client objects retained. */
	public SharedItemSummary commit(Client client, SharedItemSummary items)
	{
		Objects.requireNonNull(client); Objects.requireNonNull(items);
		if (lastCommitTick == tick) { return current == null ? items.withPouch(RunePouchSnapshot.unknown()) : current; }
		lastCommitTick = tick;
		if (dirtyTick >= 0 && tick >= dirtyTick + MAX_DIRTY_TICKS)
		{
			breakProof(); dirtyTick = -1; requested = false; confirmationTick = -1;
			current = items.withPouchFrom(current, false); changed = true; return current;
		}
		if (scene == null || !witnessed || items.guardedUi() || items.carriedPouchAssociation() != Truth.TRUE)
		{
			boolean wasProven = proven; changed |= current == null || current.pouchKnown || !associationPending;
			breakProof(); invalidateAssociation(); if (wasProven) { request(); }
			current = items.withPouchFrom(current, false); return current;
		}
		if (associationPending)
		{
			if (associationRevision == 0) { associationRevision++; }
			associationPending = false; continuous = false; proven = false;
		}
		boolean sample = requested || dirtyTick >= 0 || confirmationTick == tick;
		if (!sample)
		{
			changed |= current != null && current.pouchKnown != proven;
			current = items == current && proven ? current : items.withPouchFrom(current, proven); return current;
		}
		changed = true;
		if (!checkProfile(client))
		{
			breakProof(); current = items.withPouchFrom(current, false); return current;
		}
		int[] clientWords = new int[4], serverWords = new int[4];
		readWords(client, clientWords, serverWords);
		int[] types = new int[6], quantities = new int[6];
		if (!decode(clientWords, serverWords, types, quantities))
		{
			breakProof(); current = items.withPouchFrom(current, false); return current;
		}
		boolean quantitiesChanged = false, allZero = true;
		for (int i = 0; i < 6; i++)
		{
			quantitiesChanged |= current != null && current.rawPouchQuantity(i) != quantities[i];
			allZero &= quantities[i] == 0;
		}
		long serial = current == null ? 0 : current.pouchContentSerial, contentTick = current == null ? -1 : current.pouchContentTick;
		boolean newContent = continuous && quantitiesChanged;
		if (newContent) { serial = ++quantitySerial; contentTick = tick; }
		current = items.withPouch(new RunePouchSnapshot(true, true, associationRevision, domainRevision,
			serial, contentTick, tick, types, quantities));
		proven = continuous = current.pouchKnown && current.pouchAligned;
		// Keep the original dirty window through the required second zero sample.
		if (!newContent || !allZero) { dirtyTick = -1; }
		requested = false; confirmationTick = newContent && allZero ? tick + 1 : -1;
		return current;
	}

	/** Negative-only current probe. It cannot mint a cause, refresh the sample tick or revive retired ownership. */
	public boolean currentLeaseMatches(Client client, SharedItemSummary items, AdditionalSceneContext context)
	{
		if (items == null || items.guardedUi() || items.carriedPouchAssociation() != Truth.TRUE)
		{ breakProof(); invalidateAssociation(); return false; }
		if (context == null || context.ruleIndex != 123 || context.sourceKind != SourceKind.VARBIT || invalidated()
			|| current == null
			|| !scene.equals(context.scene) || epoch != context.presenceEpoch || lastCommitTick < context.occurrenceTick || lastCommitTick >= context.originalDeadline
			|| context.originalDeadline != context.occurrenceTick + MAX_DIRTY_TICKS
			|| context.subject.type != AdditionalSceneContext.Subject.Type.RESOURCE_KIND || context.subject.resourceKind != ResourceKind.STANDARD_RUNE_POUCH
			|| context.permittedClaimMask != (AdditionalSceneContext.CLAIM_NUMERIC | AdditionalSceneContext.CLAIM_ITEM_FORM)
			|| !context.payload.currentLive || context.payload.definitionId != 12791
			|| context.payload.entitySerial != associationRevision || context.payload.activitySerial != domainRevision
			|| context.sourceSerial != context.causeSerial || context.sourceSerial == retiredEmptySerial
			|| context.sourceSerial != current.pouchContentSerial || context.occurrenceTick != current.pouchContentTick
			|| !current.pouchEmpty || current.pouchSampleTick < context.occurrenceTick + 1 || current.pouchSampleTick > lastCommitTick) { return false; }
		return currentEmptyLeaseMatches(client, items);
	}
	/** An unissued empty edge has no scene context yet; this probe can only retire its current proof. */
	public boolean currentEmptyLeaseMatches(Client client, SharedItemSummary items)
	{
		if (items == null || items.guardedUi() || items.carriedPouchAssociation() != Truth.TRUE)
		{ breakProof(); invalidateAssociation(); return false; }
		if (invalidated() || current == null || !current.pouchEmpty) { return false; }
		if (!checkProfile(client)) { breakProof(); return false; }
		int[] clientWords = new int[4], serverWords = new int[4]; readWords(client, clientWords, serverWords);
		int[] types = new int[6], quantities = new int[6];
		if (!decode(clientWords, serverWords, types, quantities)) { breakProof(); return false; }
		for (int quantity : quantities) { if (quantity != 0) { breakProof(); return false; } }
		return true;
	}

	private boolean checkProfile(Client client)
	{
		boolean known = exactProfile(client);
		if (known != profileKnown) { domainRevision++; profileKnown = known; breakProof(); }
		return known;
	}
	private static boolean exactProfile(Client client)
	{
		int[] clientVarps = client.getVarps(), serverVarps = client.getServerVarps();
		if (clientVarps == null || serverVarps == null || clientVarps.length <= 4017 || serverVarps.length <= 4017) { return false; }
		EnumComposition profile = client.getEnum(982);
		if (profile == null || profile.size() != RUNE_IDS.length) { return false; }
		int[] keys = profile.getKeys(), values = profile.getIntVals();
		if (keys == null || values == null || keys.length != RUNE_IDS.length || values.length != RUNE_IDS.length) { return false; }
		int seen = 0;
		for (int i = 0; i < RUNE_IDS.length; i++)
		{
			int key = keys[i];
			if (key < 1 || key > RUNE_IDS.length || (seen & 1 << key) != 0 || values[i] != RUNE_IDS[key - 1]) { return false; }
			seen |= 1 << key;
		}
		if (profile.getIntValue(0) != -1 || profile.getIntValue(24) != -1) { return false; }
		for (int i = 0; i < VARBITS.length; i++)
		{
			VarbitComposition definition = client.getVarbit(VARBITS[i]);
			if (definition == null || definition.getIndex() != VARPS[WORD[i]] || definition.getLeastSignificantBit() != LEAST[i]
				|| definition.getMostSignificantBit() != MOST[i]) { return false; }
		}
		return true;
	}
	private static void readWords(Client client, int[] clientWords, int[] serverWords)
	{
		for (int i = 0; i < VARPS.length; i++)
		{
			clientWords[i] = client.getVarpValue(VARPS[i]); serverWords[i] = client.getServerVarpValue(VARPS[i]);
		}
	}
	private static boolean decode(int[] clientWords, int[] serverWords, int[] types, int[] quantities)
	{
		for (int i = 0; i < VARPS.length; i++) { if ((clientWords[i] & MASKS[i]) != (serverWords[i] & MASKS[i])) { return false; } }
		for (int i = 0; i < 6; i++)
		{
			types[i] = serverWords[WORD[i]] >>> LEAST[i] & 63;
			quantities[i] = serverWords[WORD[i + 6]] >>> LEAST[i + 6] & RunePouchSnapshot.MAX_QUANTITY;
			if (types[i] > RunePouchSnapshot.MAX_TYPE || types[i] == 0 && quantities[i] > 0) { return false; }
		}
		return true;
	}
	private void breakProof()
	{
		if (current != null && current.pouchKnown && current.pouchEmpty && current.pouchContentSerial > 0) { retiredEmptySerial = current.pouchContentSerial; }
		changed |= continuous || proven;
		continuous = proven = false; confirmationTick = -1;
	}
	private void invalidateAssociation()
	{
		if (!associationPending && associationRevision > 0) { associationRevision++; }
		associationPending = true;
	}
	private static boolean contains(int[] values, int expected) { for (int value : values) { if (value == expected) { return true; } } return false; }
	/** Root loss/overflow consumes proof without resetting the quantity-content sequence. */
	public void reset()
	{
		breakProof(); invalidateAssociation(); scene = null; current = null; tick = lastCommitTick = -1;
		witnessed = profileKnown = requested = false; dirtyTick = confirmationTick = -1; changed = true;
	}
	/** Source scalar state plus one common vector reservation; current shared DTO identity is already counted. */
	public int retainedWatcherRecords() { return RETAINED_WATCHER_RECORDS; }
	public SharedItemSummary retainedItems() { return current; }
}
