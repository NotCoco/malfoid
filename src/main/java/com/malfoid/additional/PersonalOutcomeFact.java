package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ActivityKind;
import com.malfoid.additional.AdditionalTypes.Branch;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.Objects;

/** Copied terminal facts. A source gate is distinct from merely having an API callback. */
public final class PersonalOutcomeFact
{
	public static final int MAX_TEXT_CODE_POINTS = 256;
	public final OutcomeKind kind;
	public final SourceKind sourceKind;
	public final long sourceSerial, causeSerial, occurrenceTick, originalDeadline, presenceEpoch;
	public final SceneKey scene;
	public final boolean witnessed, personal, localAttributionKnown, sourceVerified, fullGrammarVerified, completionKnown;
	public final Payload payload;
	public PersonalOutcomeFact(OutcomeKind kind, SourceKind sourceKind, long sourceSerial, long causeSerial,
		long occurrenceTick, long originalDeadline, SceneKey scene, long presenceEpoch,
		boolean witnessed, boolean personal, boolean localAttributionKnown, boolean sourceVerified,
		boolean fullGrammarVerified, boolean completionKnown, Payload payload)
	{
		this.kind = Objects.requireNonNull(kind); this.sourceKind = Objects.requireNonNull(sourceKind);
		this.scene = Objects.requireNonNull(scene); this.payload = Objects.requireNonNull(payload);
		if (sourceSerial <= 0 || causeSerial <= 0 || originalDeadline <= occurrenceTick) { throw new IllegalArgumentException("invalid outcome identity/window"); }
		this.sourceSerial = sourceSerial; this.causeSerial = causeSerial; this.occurrenceTick = occurrenceTick;
		this.originalDeadline = originalDeadline; this.presenceEpoch = presenceEpoch;
		this.witnessed = witnessed; this.personal = personal; this.localAttributionKnown = localAttributionKnown;
		this.sourceVerified = sourceVerified; this.fullGrammarVerified = fullGrammarVerified; this.completionKnown = completionKnown;
	}
	public boolean freshAt(long tick, SceneKey key, long epoch)
	{
		return witnessed && kind != OutcomeKind.UNKNOWN && sourceKind != SourceKind.UNKNOWN
			&& sourceVerified && fullGrammarVerified && localAttributionKnown && scene.equals(key)
			&& presenceEpoch == epoch && occurrenceTick <= tick && tick < originalDeadline;
	}
	/** Only already validated display facts, never a retained game message or player name. */
	public static final class Payload
	{
		public static final Payload EMPTY = new Payload(Branch.UNKNOWN, ActivityKind.UNKNOWN, "", -1, -1, -1, -1, -1, -1, null, false);
		public final Branch branch;
		public final ActivityKind activity;
		public final String displayText;
		public final int value, secondaryValue, definitionId;
		public final long durationMillis, entitySerial, activitySerial;
		public final Position position;
		public final boolean currentLive;
		public Payload(Branch branch, ActivityKind activity, String displayText, int value, int secondaryValue,
			int definitionId, long durationMillis, long entitySerial, long activitySerial, Position position, boolean currentLive)
		{
			this.branch = Objects.requireNonNull(branch); this.activity = Objects.requireNonNull(activity);
			this.displayText = checkedText(displayText); this.value = value; this.secondaryValue = secondaryValue;
			this.definitionId = definitionId; this.durationMillis = durationMillis; this.entitySerial = entitySerial;
			this.activitySerial = activitySerial; this.position = position; this.currentLive = currentLive;
		}
	}
	public static String checkedText(String text)
	{
		if (text == null) { return ""; }
		if (text.length() > MAX_TEXT_CODE_POINTS * 2 || text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS
			|| text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
		{ throw new IllegalArgumentException("overlong/multiline copied text"); }
		return text;
	}
}
