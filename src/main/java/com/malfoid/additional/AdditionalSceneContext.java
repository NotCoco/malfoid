package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.ItemFamily;
import com.malfoid.additional.AdditionalTypes.OutcomeKind;
import com.malfoid.additional.AdditionalTypes.RecoveryKind;
import com.malfoid.additional.AdditionalTypes.ResourceKind;
import com.malfoid.additional.AdditionalTypes.SourceKind;
import com.malfoid.additional.AdditionalTypes.UiKind;
import com.malfoid.additional.AdditionalTypes.WorldObjectKind;
import java.util.Objects;
import net.runelite.api.Skill;

/** Frozen typed offer; the renderer must never acquire source evidence. */
public final class AdditionalSceneContext
{
	public static final long CLAIM_REPORT = 1, CLAIM_NUMERIC = 2, CLAIM_ITEM_FORM = 4, CLAIM_UI_EDGE = 8,
		CLAIM_SCENE_EDGE = 16, CLAIM_COUNTER = 32, CLAIM_COMPLETION = 64;
	public final int ruleIndex;
	public final String ruleId;
	public final long sourceSerial, causeSerial, occurrenceTick, originalDeadline, presenceEpoch, permittedClaimMask;
	public final SourceKind sourceKind;
	public final SceneKey scene;
	public final Subject subject;
	public final PersonalOutcomeFact.Payload payload;
	public AdditionalSceneContext(int ruleIndex, long sourceSerial, long occurrenceTick, long originalDeadline,
		SceneKey scene, long presenceEpoch, Subject subject, long permittedClaimMask, PersonalOutcomeFact.Payload payload)
	{
		this(ruleIndex, SourceKind.UNKNOWN, sourceSerial, sourceSerial, occurrenceTick, originalDeadline,
			scene, presenceEpoch, subject, permittedClaimMask, payload);
	}
	public AdditionalSceneContext(int ruleIndex, SourceKind sourceKind, long sourceSerial, long causeSerial,
		long occurrenceTick, long originalDeadline, SceneKey scene, long presenceEpoch, Subject subject,
		long permittedClaimMask, PersonalOutcomeFact.Payload payload)
	{
		if (ruleIndex < 71 || ruleIndex > 170 || sourceSerial <= 0 || causeSerial <= 0 || originalDeadline <= occurrenceTick
			|| (permittedClaimMask & ~127L) != 0) { throw new IllegalArgumentException("invalid additional scene"); }
		this.ruleIndex = ruleIndex; this.ruleId = "M" + ruleIndex; this.sourceSerial = sourceSerial;
		this.sourceKind = Objects.requireNonNull(sourceKind); this.causeSerial = causeSerial;
		this.occurrenceTick = occurrenceTick; this.originalDeadline = originalDeadline;
		this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch;
		this.subject = Objects.requireNonNull(subject); this.permittedClaimMask = permittedClaimMask; this.payload = Objects.requireNonNull(payload);
	}
	public static final class Subject
	{
		public enum Type { UNKNOWN, UNIT, SKILL, FINITE_ITEM_FAMILY, RESOURCE_KIND, RECOVERY_SUBJECT, WIDGET_KIND, ANNOUNCEMENT_KIND, WORLD_OBJECT_KIND }
		public final Type type;
		public final Skill skill;
		public final ItemFamily itemFamily;
		public final ResourceKind resourceKind;
		public final RecoveryKind recoveryKind;
		public final UiKind uiKind;
		public final OutcomeKind outcomeKind;
		public final WorldObjectKind worldObjectKind;
		private Subject(Type type, Skill skill, ItemFamily item, ResourceKind resource, RecoveryKind recovery,
			UiKind ui, OutcomeKind outcome, WorldObjectKind world)
		{
			this.type = type; this.skill = skill; itemFamily = item; resourceKind = resource; recoveryKind = recovery;
			uiKind = ui; outcomeKind = outcome; worldObjectKind = world;
		}
		public static Subject unit() { return new Subject(Type.UNIT, null, null, null, null, null, null, null); }
		public static Subject skill(Skill skill) { return new Subject(Type.SKILL, Objects.requireNonNull(skill), null, null, null, null, null, null); }
		public static Subject item(ItemFamily family) { return new Subject(Type.FINITE_ITEM_FAMILY, null, Objects.requireNonNull(family), null, null, null, null, null); }
		public static Subject resource(ResourceKind kind) { return new Subject(Type.RESOURCE_KIND, null, null, Objects.requireNonNull(kind), null, null, null, null); }
		public static Subject recovery(RecoveryKind kind, Skill skill) { return new Subject(Type.RECOVERY_SUBJECT, skill, null, null, Objects.requireNonNull(kind), null, null, null); }
		public static Subject ui(UiKind kind) { return new Subject(Type.WIDGET_KIND, null, null, null, null, Objects.requireNonNull(kind), null, null); }
		public static Subject announcement(OutcomeKind kind) { return new Subject(Type.ANNOUNCEMENT_KIND, null, null, null, null, null, Objects.requireNonNull(kind), null); }
		public static Subject world(WorldObjectKind kind) { return new Subject(Type.WORLD_OBJECT_KIND, null, null, null, null, null, null, Objects.requireNonNull(kind)); }
	}
}
