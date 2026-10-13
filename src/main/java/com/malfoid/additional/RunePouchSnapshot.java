package com.malfoid.additional;

import java.util.Arrays;

/** Immutable transfer value; the common item summary owns its one six-slot vector after attachment. */
public final class RunePouchSnapshot
{
	public static final int SLOT_COUNT = 6, MAX_QUANTITY = 16383, MAX_TYPE = 23;
	public final boolean known, aligned, empty;
	public final long associationRevision, domainRevision, contentSerial, contentTick, sampleTick;
	private final int[] slots;
	public RunePouchSnapshot(boolean known, boolean aligned, long associationRevision, long domainRevision,
		long contentSerial, long contentTick, long sampleTick, int[] types, int[] quantities)
	{
		boolean valid = types != null && quantities != null && types.length == SLOT_COUNT && quantities.length == SLOT_COUNT;
		slots = new int[SLOT_COUNT]; Arrays.fill(slots, -1);
		boolean allZero = true;
		if (valid)
		{
			for (int i = 0; i < SLOT_COUNT; i++)
			{
				if (types[i] < 0 || types[i] > MAX_TYPE || quantities[i] < 0 || quantities[i] > MAX_QUANTITY
					|| types[i] == 0 && quantities[i] > 0) { valid = false; break; }
				slots[i] = types[i] << 14 | quantities[i];
				allZero &= quantities[i] == 0;
			}
		}
		this.known = known && valid && associationRevision > 0 && domainRevision > 0 && sampleTick >= 0
			&& contentSerial >= 0 && (contentSerial == 0 && contentTick == -1 || contentSerial > 0 && contentTick >= 0 && contentTick <= sampleTick);
		this.aligned = this.known && aligned;
		this.empty = this.known && this.aligned && allZero;
		this.associationRevision = associationRevision; this.domainRevision = domainRevision;
		this.contentSerial = contentSerial; this.contentTick = contentTick; this.sampleTick = sampleTick;
		if (!this.known) { Arrays.fill(slots, -1); }
	}
	public int quantity(int slot) { return known && slot >= 0 && slot < SLOT_COUNT ? slots[slot] & MAX_QUANTITY : -1; }
	public int type(int slot) { return known && slot >= 0 && slot < SLOT_COUNT ? slots[slot] >>> 14 : -1; }
	// The array is privately immutable; no API returns it outside the common source package.
	int[] copiedSlots() { return Arrays.copyOf(slots, slots.length); }
	public static RunePouchSnapshot unknown() { return new RunePouchSnapshot(false, false, 0, 0, 0, -1, -1, null, null); }
}
