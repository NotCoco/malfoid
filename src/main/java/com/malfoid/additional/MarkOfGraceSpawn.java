package com.malfoid.additional;

import com.malfoid.scenario.ScenarioSnapshot.Position;
import java.util.Objects;

/** One immutable original physical insertion; no owner, pickup, course, name or actor reference. */
public final class MarkOfGraceSpawn
{
	public final long entitySerial, occurrenceTick, presenceEpoch;
	public final SceneKey scene;
	public final int quantity;
	public final Position position;
	public MarkOfGraceSpawn(long entitySerial, long occurrenceTick, long presenceEpoch,
		SceneKey scene, int quantity, Position position)
	{
		if (entitySerial <= 0 || occurrenceTick < 0 || occurrenceTick > Long.MAX_VALUE - 12
			|| presenceEpoch < 0 || occurrenceTick - presenceEpoch < 10 || quantity <= 0)
		{ throw new IllegalArgumentException("invalid original mark insertion"); }
		this.entitySerial = entitySerial; this.occurrenceTick = occurrenceTick; this.presenceEpoch = presenceEpoch;
		this.scene = Objects.requireNonNull(scene); this.quantity = quantity; this.position = Objects.requireNonNull(position);
	}
}
