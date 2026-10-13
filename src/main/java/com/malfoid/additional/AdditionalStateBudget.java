package com.malfoid.additional;

/** Shared chosen record reservations. Heap and ordinary-PC timing still need measured acceptance. */
public final class AdditionalStateBudget
{
	public static final int MAX_SOURCE_ACTIVITY_RECORDS = 1, MAX_REDUCER_ARMS = 115, MAX_CAUSAL_JOINS = 16, MAX_PRESENTATIONS = 3;
	public static final int MAX_ENVELOPE_FACTS = AdditionalSnapshot.MAX_FACTS * 2;
	// Current/prior immutable UI values and the single root-watcher state, all at the 16-root cap.
	// M170 adds 28 primitive watcher bytes and 5 per immutable UI value inside the existing 384-byte/root reservation.
	public static final int MAX_UI_RECORDS = AdditionalSnapshot.MAX_UI_ROOTS * 3;
	// Existing current/prior numeric/items plus one fixed pouch synchronization watcher; its vector is shared.
	public static final int NUMERIC_ITEM_RECORDS = 4 + RunePouchSource.RETAINED_WATCHER_RECORDS;
	public static final int MAX_ENVELOPES = 2, MAX_WORLD_CONTEXTS = 2;
	public static final int CHOSEN_MAX_RECORDS = AdditionalSourceCollector.MAX_METADATA
		+ AdditionalSourceCollector.MAX_SEEN_MESSAGES + MAX_ENVELOPE_FACTS + MAX_UI_RECORDS
		+ NUMERIC_ITEM_RECORDS + MAX_ENVELOPES + MAX_WORLD_CONTEXTS + MAX_SOURCE_ACTIVITY_RECORDS + MAX_REDUCER_ARMS + MAX_CAUSAL_JOINS + MAX_PRESENTATIONS;
	static
	{
		if (CHOSEN_MAX_RECORDS > AdditionalSnapshot.MAX_RECORDS) { throw new IllegalStateException("additional record reservations exceed cap"); }
	}
	private AdditionalStateBudget() { }
	/** A false result must clear/rebaseline the affected rules before any arbitration or first paint. */
	public static boolean permits(int reducerArms, int causalJoins, int presentations, int reducerFixedStateBytes)
	{
		return reducerArms >= 0 && reducerArms <= MAX_REDUCER_ARMS && causalJoins >= 0 && causalJoins <= MAX_CAUSAL_JOINS
			&& presentations >= 0 && presentations <= MAX_PRESENTATIONS && reducerFixedStateBytes >= 0
			&& reducerFixedStateBytes <= AdditionalSnapshot.MAX_REDUCER_BYTES;
	}
}
