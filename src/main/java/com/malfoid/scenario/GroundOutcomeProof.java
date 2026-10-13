package com.malfoid.scenario;

import com.malfoid.scenario.ScenarioSnapshot.Position;

/** Current availability or possession for one reported episode; neither proves pickup or loot source. */
public final class GroundOutcomeProof
{
	public final long reportId, reportTick, originalDeadline, sessionGeneration, sceneRevision, presentSinceTick;
	public final int itemId, reportedQuantity;
	public final String itemName;
	public final long reportedValue, pileIdentity;
	public final boolean currentGroundAvailable, itemPossessionConfirmed;
	public final Position groundPosition;

	GroundOutcomeProof(long reportId, long reportTick, long originalDeadline, long sessionGeneration,
		long sceneRevision, long presentSinceTick, int itemId, int reportedQuantity, String itemName,
		long reportedValue, boolean currentGroundAvailable, boolean itemPossessionConfirmed,
		long pileIdentity, Position groundPosition)
	{
		this.reportId = reportId; this.reportTick = reportTick; this.originalDeadline = originalDeadline;
		this.sessionGeneration = sessionGeneration; this.sceneRevision = sceneRevision; this.presentSinceTick = presentSinceTick;
		this.itemId = itemId; this.reportedQuantity = reportedQuantity; this.itemName = itemName;
		this.reportedValue = reportedValue; this.currentGroundAvailable = currentGroundAvailable;
		this.itemPossessionConfirmed = itemPossessionConfirmed; this.pileIdentity = pileIdentity; this.groundPosition = groundPosition;
	}
}
