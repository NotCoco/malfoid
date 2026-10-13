package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One bounded committed source envelope. No references back to the client. */
public final class AdditionalSnapshot
{
	public static final int MAX_FACTS = 64, MAX_RECORDS = 512, MAX_UI_ROOTS = 16, MAX_REDUCER_BYTES = 256 * 1024;
	public final long tick, presenceEpoch;
	public final SceneKey scene;
	public final boolean known, baseline, pendingPetAnnouncement;
	public final List<PersonalOutcomeFact> outcomes;
	public final List<AdditionalUiSnapshot> ui;
	public final SharedItemSummary items;
	public final AdditionalNumericSnapshot numeric;
	public final Set<SourceKind> unknownSources, changedSources;
	public final Truth followerKnown;
	public final long followerSerial;
	public final int followerDefinitionId;
	public final Truth tzhaarKnown;
	public final ActivityKind tzhaarActivity;
	public final long tzhaarActivitySerial, tzhaarWaveSerial;
	public final int tzhaarWave;
	public AdditionalSnapshot(long tick, SceneKey scene, long presenceEpoch, boolean known, boolean baseline,
		List<PersonalOutcomeFact> outcomes, List<AdditionalUiSnapshot> ui, SharedItemSummary items,
		AdditionalNumericSnapshot numeric, Set<SourceKind> unknownSources, boolean pendingPetAnnouncement,
		Truth followerKnown, long followerSerial)
	{
		this(tick, scene, presenceEpoch, known, baseline, outcomes, ui, items, numeric, unknownSources,
			pendingPetAnnouncement, followerKnown, followerSerial, EnumSet.allOf(SourceKind.class));
	}
	public AdditionalSnapshot(long tick, SceneKey scene, long presenceEpoch, boolean known, boolean baseline,
		List<PersonalOutcomeFact> outcomes, List<AdditionalUiSnapshot> ui, SharedItemSummary items,
		AdditionalNumericSnapshot numeric, Set<SourceKind> unknownSources, boolean pendingPetAnnouncement,
		Truth followerKnown, long followerSerial, Set<SourceKind> changedSources)
	{
		this(tick, scene, presenceEpoch, known, baseline, outcomes, ui, items, numeric, unknownSources,
			pendingPetAnnouncement, followerKnown, followerSerial, -1, changedSources);
	}
	public AdditionalSnapshot(long tick, SceneKey scene, long presenceEpoch, boolean known, boolean baseline,
		List<PersonalOutcomeFact> outcomes, List<AdditionalUiSnapshot> ui, SharedItemSummary items,
		AdditionalNumericSnapshot numeric, Set<SourceKind> unknownSources, boolean pendingPetAnnouncement,
		Truth followerKnown, long followerSerial, int followerDefinitionId, Set<SourceKind> changedSources)
	{
		this(tick, scene, presenceEpoch, known, baseline, outcomes, ui, items, numeric, unknownSources, pendingPetAnnouncement,
			followerKnown, followerSerial, followerDefinitionId, changedSources, Truth.UNKNOWN, ActivityKind.UNKNOWN, 0, -1, 0);
	}
	public AdditionalSnapshot(long tick, SceneKey scene, long presenceEpoch, boolean known, boolean baseline,
		List<PersonalOutcomeFact> outcomes, List<AdditionalUiSnapshot> ui, SharedItemSummary items,
		AdditionalNumericSnapshot numeric, Set<SourceKind> unknownSources, boolean pendingPetAnnouncement,
		Truth followerKnown, long followerSerial, int followerDefinitionId, Set<SourceKind> changedSources,
		Truth tzhaarKnown, ActivityKind tzhaarActivity, long tzhaarActivitySerial, int tzhaarWave, long tzhaarWaveSerial)
	{
		this.tick = tick; this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch;
		if (outcomes.size() > MAX_FACTS || ui.size() > MAX_UI_ROOTS) { throw new IllegalArgumentException("source envelope overflow"); }
		this.known = known; this.baseline = baseline; this.pendingPetAnnouncement = pendingPetAnnouncement;
		this.outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes)); this.ui = Collections.unmodifiableList(new ArrayList<>(ui));
		this.items = Objects.requireNonNull(items); this.numeric = Objects.requireNonNull(numeric);
		EnumSet<SourceKind> unknown = EnumSet.noneOf(SourceKind.class); unknown.addAll(unknownSources);
		this.unknownSources = Collections.unmodifiableSet(unknown); this.followerKnown = Objects.requireNonNull(followerKnown); this.followerSerial = followerSerial;
		this.followerDefinitionId = followerKnown == Truth.TRUE ? followerDefinitionId : -1;
		EnumSet<SourceKind> changed = EnumSet.noneOf(SourceKind.class); changed.addAll(changedSources);
		this.changedSources = Collections.unmodifiableSet(changed);
		this.tzhaarKnown = Objects.requireNonNull(tzhaarKnown); this.tzhaarActivity = Objects.requireNonNull(tzhaarActivity);
		this.tzhaarActivitySerial = tzhaarKnown == Truth.TRUE ? tzhaarActivitySerial : 0;
		this.tzhaarWave = tzhaarKnown == Truth.TRUE ? tzhaarWave : -1; this.tzhaarWaveSerial = tzhaarKnown == Truth.TRUE ? tzhaarWaveSerial : 0;
	}
	public AdditionalUiSnapshot ui(UiKind kind)
	{
		for (AdditionalUiSnapshot snapshot : ui) { if (snapshot.kind == kind) { return snapshot; } }
		return AdditionalUiSnapshot.unknown(kind);
	}
	public boolean sourceKnown(SourceKind source) { return known && !unknownSources.contains(source); }
	public static AdditionalSnapshot unknown(long tick, SceneKey scene, long epoch)
	{
		return new AdditionalSnapshot(tick, scene, epoch, false, true, Collections.emptyList(), Collections.emptyList(),
			SharedItemSummary.unknown(), AdditionalNumericSnapshot.unknown(), EnumSet.allOf(SourceKind.class), false, Truth.UNKNOWN, 0);
	}
}
