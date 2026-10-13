package com.malfoid.additional;

import java.util.Objects;

/** Immutable bounded arm; deadline is always copied from the original occurrence. */
public final class AdditionalReducerArm
{
	public final int ruleIndex, stableCommits, quietCommits;
	public final long sourceSerial, occurrenceTick, originalDeadline, presenceEpoch;
	public final SceneKey scene;
	public final AdditionalSceneContext.Subject subject;
	public AdditionalReducerArm(int ruleIndex, long sourceSerial, long occurrenceTick, long originalDeadline,
		SceneKey scene, long presenceEpoch, int stableCommits, int quietCommits, AdditionalSceneContext.Subject subject)
	{
		if (ruleIndex < 71 || ruleIndex > 170 || sourceSerial <= 0 || originalDeadline <= occurrenceTick
			|| stableCommits < 0 || stableCommits > 200 || quietCommits < 0 || quietCommits > 200)
		{ throw new IllegalArgumentException("invalid additional arm"); }
		this.ruleIndex = ruleIndex; this.sourceSerial = sourceSerial; this.occurrenceTick = occurrenceTick;
		this.originalDeadline = originalDeadline; this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch;
		this.stableCommits = stableCommits; this.quietCommits = quietCommits; this.subject = Objects.requireNonNull(subject);
	}
	public boolean liveAt(long tick, SceneKey key, long epoch)
	{ return scene.equals(key) && presenceEpoch == epoch && occurrenceTick <= tick && tick < originalDeadline; }
}
