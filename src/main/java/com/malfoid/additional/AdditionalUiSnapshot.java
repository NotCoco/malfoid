package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.Truth;
import com.malfoid.additional.AdditionalTypes.UiKind;
import java.util.Objects;

/** A visible-root observation is a presentation edge, never an action completion. */
public final class AdditionalUiSnapshot
{
	public final UiKind kind;
	public final int rootId, puzzleSelector;
	public final boolean puzzleCloseVerified;
	public final Truth visibility;
	public final long episodeSerial, openTick, closeTick, completionSerial;
	public final String questTitle;
	public final boolean initializedTitle, sourceVerified, baseline;
	public AdditionalUiSnapshot(UiKind kind, int rootId, Truth visibility, long episodeSerial, long openTick,
		long closeTick, String questTitle, long completionSerial, boolean initializedTitle, boolean sourceVerified, boolean baseline)
	{
		this(kind, rootId, visibility, episodeSerial, openTick, closeTick, questTitle, completionSerial,
			initializedTitle, sourceVerified, baseline, -1, false);
	}
	/** Explicit copied initialized kind and native unload qualification; root-only callers remain unqualified. */
	public AdditionalUiSnapshot(UiKind kind, int rootId, Truth visibility, long episodeSerial, long openTick,
		long closeTick, String questTitle, long completionSerial, boolean initializedTitle, boolean sourceVerified, boolean baseline,
		int puzzleSelector, boolean puzzleCloseVerified)
	{
		this.kind = Objects.requireNonNull(kind); this.visibility = Objects.requireNonNull(visibility);
		this.puzzleSelector = puzzleSelector; this.puzzleCloseVerified = puzzleCloseVerified;
		this.rootId = rootId; this.episodeSerial = episodeSerial; this.openTick = openTick; this.closeTick = closeTick;
		this.questTitle = PersonalOutcomeFact.checkedText(questTitle); this.completionSerial = completionSerial;
		this.initializedTitle = initializedTitle; this.sourceVerified = sourceVerified; this.baseline = baseline;
	}
	public boolean puzzleKindKnown() { return puzzleSelector >= 1 && puzzleSelector <= 6 || puzzleSelector == 9; }
	public static AdditionalUiSnapshot unknown(UiKind kind)
	{
		return new AdditionalUiSnapshot(kind, -1, Truth.UNKNOWN, 0, -1, -1, "", 0, false, false, true);
	}
}
