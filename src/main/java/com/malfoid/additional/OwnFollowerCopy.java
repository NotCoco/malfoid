package com.malfoid.additional;

import com.malfoid.additional.AdditionalTypes.Truth;
import java.util.Objects;

/** Input-only copy of the public local follower relation; never retained by the collector. */
public final class OwnFollowerCopy
{
	public final long tick, presenceEpoch, incarnationSerial;
	public final SceneKey scene;
	public final Truth ownLive;
	public final int resolvedDefinitionId;
	public OwnFollowerCopy(long tick, SceneKey scene, long presenceEpoch, Truth ownLive,
		long incarnationSerial, int resolvedDefinitionId)
	{
		this.tick = tick; this.scene = Objects.requireNonNull(scene); this.presenceEpoch = presenceEpoch;
		this.ownLive = Objects.requireNonNull(ownLive);
		if (ownLive == Truth.TRUE && (incarnationSerial <= 0 || resolvedDefinitionId < 0))
		{ throw new IllegalArgumentException("unknown live follower identity"); }
		this.incarnationSerial = ownLive == Truth.TRUE ? incarnationSerial : 0;
		this.resolvedDefinitionId = ownLive == Truth.TRUE ? resolvedDefinitionId : -1;
	}
	public boolean scopeMatches(long tick, SceneKey scene, long presenceEpoch)
	{ return this.tick == tick && this.scene.equals(scene) && this.presenceEpoch == presenceEpoch; }
	public boolean knownKitten()
	{ return ownLive == Truth.TRUE && resolvedDefinitionId >= 5591 && resolvedDefinitionId <= 5597; }
}
