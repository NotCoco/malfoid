package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ItemForm;
import com.malfoid.additional.AdditionalTypes.Truth;
import java.util.Arrays;

/** Canonical 28 inventory + 14 equipment slots; all family aggregates share this single copy. */
public final class SharedItemSummary
{
	public static final int SLOT_COUNT = 42, INVENTORY_SIZE = 28, EQUIPMENT_SIZE = 14, EMPTY_ID = -1, UNKNOWN_ID = -2;
	// Exact physical IDs, frozen from the pinned gameval ItemID and ItemWithCharge source tables.
	private static final int[] CONFIGURED_IDS = {139, 141, 143, 1973, 2309, 2434, 2520, 2522, 2524, 2526,
		2552, 2554, 2556, 2558, 2560, 2562, 2564, 2566, 4537, 4539, 7509, 7510, 12791, 13660, 23182,
		1823, 1825, 1827, 1829, 1831, 24416, 27281, 27509};
	private final int[] ids, quantities, slots;
	private final ItemForm[] forms;
	private final long[] counts;
	private final String[] names;
	public final InventoryMultiset inventoryMultiset;
	public final boolean prayerBottlesKnown;
	public final int prayerBottleCount, prayerDoseTotal;
	public final boolean inventoryKnown, equipmentKnown, complete;
	public final Truth bankVisible, tradeVisible, standardPouchAssociation;
	public final long visibilityEpoch, contentsCauseSerial;
	public final boolean pouchKnown, pouchAligned, pouchEmpty;
	public final long pouchAssociationRevision, pouchDomainRevision, pouchContentSerial, pouchContentTick, pouchSampleTick;
	private final boolean pouchMetadataKnown;
	private final int[] pouchSlots;
	public SharedItemSummary(int[] ids, int[] quantities, ItemForm[] forms, Truth bankVisible, Truth tradeVisible,
		long visibilityEpoch, long contentsCauseSerial, Truth standardPouchAssociation, int[] pouchQuantities)
	{
		this(ids, quantities, forms, bankVisible, tradeVisible, visibilityEpoch, contentsCauseSerial,
			standardPouchAssociation, pouchQuantities, null, null);
	}
	public SharedItemSummary(int[] ids, int[] quantities, ItemForm[] forms, Truth bankVisible, Truth tradeVisible,
		long visibilityEpoch, long contentsCauseSerial, Truth standardPouchAssociation, int[] pouchQuantities,
		String[] copiedNames, Truth[] stackable)
	{
		this.ids = new int[SLOT_COUNT]; this.quantities = new int[SLOT_COUNT]; this.forms = new ItemForm[SLOT_COUNT];
		counts = new long[CONFIGURED_IDS.length]; slots = new int[CONFIGURED_IDS.length]; names = new String[CONFIGURED_IDS.length];
		long[] inventoryTuples = new long[INVENTORY_SIZE]; int occupied = 0;
		boolean prayerKnown = true, pouchMetadata = true; int bottles = 0, doses = 0;
		Arrays.fill(this.ids, UNKNOWN_ID); Arrays.fill(this.quantities, -1); Arrays.fill(this.forms, ItemForm.UNKNOWN);
		if (ids != null && quantities != null && ids.length == SLOT_COUNT && quantities.length == SLOT_COUNT)
		{
			for (int i = 0; i < SLOT_COUNT; i++)
			{
				if (ids[i] == EMPTY_ID && quantities[i] == 0) { this.ids[i] = EMPTY_ID; this.quantities[i] = 0; this.forms[i] = ItemForm.EMPTY; }
				else if (ids[i] >= 0 && quantities[i] > 0)
				{
					this.ids[i] = ids[i]; this.quantities[i] = quantities[i];
					if (forms != null && forms.length == SLOT_COUNT && forms[i] != null && forms[i] != ItemForm.EMPTY) { this.forms[i] = forms[i]; }
				}
				if (i >= INVENTORY_SIZE || this.ids[i] < 0) { continue; }
				if (this.forms[i] != ItemForm.REAL) { pouchMetadata = false; }
				if (this.forms[i] != ItemForm.REAL) { prayerKnown = false; }
				inventoryTuples[occupied++] = ((long) this.ids[i] << 32) | (this.quantities[i] & 0xffffffffL);
				int index = configuredIndex(this.ids[i]);
				if (index >= 0)
				{
					if (this.forms[i] != ItemForm.REAL) { counts[index] = -1; slots[index] = -1; }
					else if (counts[index] >= 0) { counts[index] += this.quantities[i]; slots[index]++; }
					if (copiedNames != null && copiedNames.length == SLOT_COUNT) { names[index] = PersonalOutcomeFact.checkedText(copiedNames[i]); }
				}
				int dose = prayerDose(this.ids[i]);
				if (dose > 0)
				{
					if (this.forms[i] != ItemForm.REAL || this.quantities[i] != 1 || stackable == null
						|| stackable.length != SLOT_COUNT || stackable[i] != Truth.FALSE) { prayerKnown = false; }
					else { bottles++; doses += dose; }
				}
			}
		}
		inventoryKnown = known(0, INVENTORY_SIZE); equipmentKnown = known(INVENTORY_SIZE, SLOT_COUNT); complete = inventoryKnown && equipmentKnown;
		inventoryMultiset = new InventoryMultiset(inventoryKnown, inventoryTuples, occupied);
		prayerBottlesKnown = inventoryKnown && prayerKnown; prayerBottleCount = prayerBottlesKnown ? bottles : -1; prayerDoseTotal = prayerBottlesKnown ? doses : -1;
		this.bankVisible = bankVisible == null ? Truth.UNKNOWN : bankVisible; this.tradeVisible = tradeVisible == null ? Truth.UNKNOWN : tradeVisible;
		this.visibilityEpoch = visibilityEpoch; this.contentsCauseSerial = contentsCauseSerial;
		// Legacy fixture arrays are not a verified current-content source.
		this.standardPouchAssociation = Truth.UNKNOWN; pouchKnown = pouchAligned = pouchEmpty = false;
		pouchAssociationRevision = pouchDomainRevision = pouchContentSerial = 0; pouchContentTick = pouchSampleTick = -1;
		pouchMetadataKnown = inventoryKnown && pouchMetadata;
		pouchSlots = new int[6]; Arrays.fill(pouchSlots, -1);
		if (!inventoryKnown) { Arrays.fill(counts, -1); Arrays.fill(slots, -1); }
	}
	private SharedItemSummary(SharedItemSummary source, Truth bank, Truth trade, long visibilityEpoch, long contentsCauseSerial)
	{ this(source, bank, trade, visibilityEpoch, contentsCauseSerial, null, false); }
	private SharedItemSummary(SharedItemSummary source, Truth bank, Truth trade, long visibilityEpoch, long contentsCauseSerial,
		RunePouchSnapshot pouch, boolean clearPouch)
	{
		ids = source.ids; quantities = source.quantities; forms = source.forms; counts = source.counts; slots = source.slots; names = source.names;
		inventoryMultiset = source.inventoryMultiset; prayerBottlesKnown = source.prayerBottlesKnown;
		prayerBottleCount = source.prayerBottleCount; prayerDoseTotal = source.prayerDoseTotal;
		inventoryKnown = source.inventoryKnown; equipmentKnown = source.equipmentKnown; complete = source.complete;
		bankVisible = bank; tradeVisible = trade; this.visibilityEpoch = visibilityEpoch; this.contentsCauseSerial = contentsCauseSerial;
		pouchMetadataKnown = source.pouchMetadataKnown;
		pouchKnown = !clearPouch && (pouch == null ? source.pouchKnown : pouch.known);
		pouchAligned = pouchKnown && (pouch == null ? source.pouchAligned : pouch.aligned);
		pouchEmpty = pouchKnown && pouchAligned && (pouch == null ? source.pouchEmpty : pouch.empty);
		standardPouchAssociation = pouchKnown ? Truth.TRUE : Truth.UNKNOWN;
		pouchAssociationRevision = pouch == null ? source.pouchAssociationRevision : pouch.associationRevision;
		pouchDomainRevision = pouch == null ? source.pouchDomainRevision : pouch.domainRevision;
		pouchContentSerial = pouch == null ? source.pouchContentSerial : pouch.contentSerial;
		pouchContentTick = pouch == null ? source.pouchContentTick : pouch.contentTick;
		pouchSampleTick = clearPouch ? -1 : pouch == null ? source.pouchSampleTick : pouch.sampleTick;
		pouchSlots = pouch == null ? source.pouchSlots : pouch.copiedSlots();
	}
	/** Only visibility changes; all privately owned immutable slot and aggregate arrays remain shared. */
	public SharedItemSummary withVisibility(Truth bank, Truth trade, long visibilityEpoch, long contentsCauseSerial)
	{
		Truth checkedBank = bank == null ? Truth.UNKNOWN : bank, checkedTrade = trade == null ? Truth.UNKNOWN : trade;
		return bankVisible == checkedBank && tradeVisible == checkedTrade && this.visibilityEpoch == visibilityEpoch
			&& this.contentsCauseSerial == contentsCauseSerial ? this : new SharedItemSummary(this, checkedBank, checkedTrade, visibilityEpoch, contentsCauseSerial);
	}
	/** Attaches only verified data; the transfer object itself is never retained. No inventory walk. */
	public SharedItemSummary withPouch(RunePouchSnapshot pouch)
	{
		boolean clear = pouch == null || !pouch.known || carriedPouchAssociation() != Truth.TRUE || guardedUi();
		return new SharedItemSummary(this, bankVisible, tradeVisible, visibilityEpoch, contentsCauseSerial, pouch, clear);
	}
	/** Source-only reuse of the same privately immutable current vector after a common slot copy. */
	SharedItemSummary withPouchFrom(SharedItemSummary current, boolean known)
	{
		if (current == null && !pouchKnown || current == this && !pouchKnown && !known) { return this; }
		return current == null ? withPouch(RunePouchSnapshot.unknown())
			: new SharedItemSummary(this, current, known && carriedPouchAssociation() == Truth.TRUE && !guardedUi());
	}
	private SharedItemSummary(SharedItemSummary source, SharedItemSummary pouch, boolean known)
	{
		ids = source.ids; quantities = source.quantities; forms = source.forms; counts = source.counts; slots = source.slots; names = source.names;
		inventoryMultiset = source.inventoryMultiset; prayerBottlesKnown = source.prayerBottlesKnown;
		prayerBottleCount = source.prayerBottleCount; prayerDoseTotal = source.prayerDoseTotal;
		inventoryKnown = source.inventoryKnown; equipmentKnown = source.equipmentKnown; complete = source.complete;
		bankVisible = source.bankVisible; tradeVisible = source.tradeVisible; visibilityEpoch = source.visibilityEpoch; contentsCauseSerial = source.contentsCauseSerial;
		pouchMetadataKnown = source.pouchMetadataKnown; pouchKnown = known && pouch.pouchKnown; pouchAligned = pouchKnown && pouch.pouchAligned;
		pouchEmpty = pouchKnown && pouchAligned && pouch.pouchEmpty;
		standardPouchAssociation = pouchKnown ? Truth.TRUE : Truth.UNKNOWN;
		pouchAssociationRevision = pouch.pouchAssociationRevision; pouchDomainRevision = pouch.pouchDomainRevision;
		pouchContentSerial = pouch.pouchContentSerial; pouchContentTick = pouch.pouchContentTick; pouchSampleTick = pouchKnown ? pouch.pouchSampleTick : -1;
		pouchSlots = pouch.pouchSlots;
	}
	/** Finite aggregates from the existing common pass; supported contents still require the audited source. */
	public Truth carriedPouchAssociation()
	{
		if (!inventoryKnown || !pouchMetadataKnown || count(12791) < 0 || count(24416) < 0 || count(27281) < 0 || count(27509) < 0) { return Truth.UNKNOWN; }
		if (count(24416) != 0 || count(27281) != 0 || count(27509) != 0 || count(12791) > 1 || occupiedSlots(12791) > 1) { return Truth.UNKNOWN; }
		return count(12791) == 1 && occupiedSlots(12791) == 1 ? Truth.TRUE : Truth.FALSE;
	}
	private static int prayerDose(int id) { return id == 143 ? 1 : id == 141 ? 2 : id == 139 ? 3 : id == 2434 ? 4 : 0; }
	private boolean known(int start, int end) { for (int i = start; i < end; i++) { if (ids[i] == UNKNOWN_ID) { return false; } } return true; }
	private static int configuredIndex(int id) { for (int i = 0; i < CONFIGURED_IDS.length; i++) { if (CONFIGURED_IDS[i] == id) { return i; } } return -1; }
	public int id(int slot) { return slot >= 0 && slot < SLOT_COUNT ? ids[slot] : UNKNOWN_ID; }
	public int quantity(int slot) { return slot >= 0 && slot < SLOT_COUNT ? quantities[slot] : -1; }
	public ItemForm form(int slot) { return slot >= 0 && slot < SLOT_COUNT ? forms[slot] : ItemForm.UNKNOWN; }
	public long count(int id) { int index = configuredIndex(id); return index < 0 ? -1 : counts[index]; }
	public int occupiedSlots(int id) { int index = configuredIndex(id); return index < 0 ? -1 : slots[index]; }
	public String name(int id) { int index = configuredIndex(id); return index < 0 || names[index] == null ? "" : names[index]; }
	public int[] inventoryIds() { return Arrays.copyOf(ids, INVENTORY_SIZE); }
	public int[] inventoryQuantities() { return Arrays.copyOf(quantities, INVENTORY_SIZE); }
	public int pouchQuantity(int slot) { return pouchKnown && slot >= 0 && slot < 6 ? pouchSlots[slot] & RunePouchSnapshot.MAX_QUANTITY : -1; }
	int pouchType(int slot) { return pouchKnown && slot >= 0 && slot < 6 ? pouchSlots[slot] >>> 14 : -1; }
	int rawPouchQuantity(int slot) { return pouchSlots[slot] < 0 ? -1 : pouchSlots[slot] & RunePouchSnapshot.MAX_QUANTITY; }
	public boolean guardedUi() { return bankVisible != Truth.FALSE || tradeVisible != Truth.FALSE; }
	public static SharedItemSummary unknown() { return new SharedItemSummary(null, null, null, Truth.UNKNOWN, Truth.UNKNOWN, 0, 0, Truth.UNKNOWN, null); }
	public static final class InventoryMultiset
	{
		public final boolean known;
		private final long[] tuples;
		private InventoryMultiset(boolean known, long[] values, int size)
		{
			this.known = known; tuples = Arrays.copyOf(values, size); Arrays.sort(tuples);
		}
		@Override public boolean equals(Object other)
		{
			if (other == this) { return true; }
			if (!(other instanceof InventoryMultiset)) { return false; }
			InventoryMultiset value = (InventoryMultiset) other;
			return known && value.known && Arrays.equals(tuples, value.tuples);
		}
		@Override public int hashCode() { return Arrays.hashCode(tuples); }
	}
}
