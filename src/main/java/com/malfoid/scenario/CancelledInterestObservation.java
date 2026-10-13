package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;

/** One actual abandoned inspection and its separate return request; no live object is retained. */
public final class CancelledInterestObservation
{
	public enum LossReason { NULL_RESOLUTION, INACTIVE, UNSUPPORTED, DESPAWNED }
	public final SceneCandidate inspectionCandidate;
	public final MotionObservation inspectionPath;
	public final SpeechCancellationReceipt cancellation;
	public final LossReason lossReason;
	public final String targetKey, historicalName;
	public final long lossTick, returnRequestId, presentSinceTick, placementGeneration;
	public final int worldViewId, plane;
	public final boolean inspectionArrived, known;

	private CancelledInterestObservation(Builder b)
	{
		inspectionCandidate = b.inspectionCandidate; inspectionPath = b.inspectionPath; cancellation = b.cancellation;
		lossReason = b.lossReason; targetKey = b.targetKey; historicalName = b.historicalName;
		lossTick = cancellation == null ? -1 : cancellation.getCancelledAtTick();
		returnRequestId = b.returnRequestId; presentSinceTick = b.presentSinceTick; placementGeneration = b.placementGeneration;
		worldViewId = b.worldViewId; plane = b.plane; inspectionArrived = b.inspectionArrived; known = b.known;
	}

	public static final class Builder
	{
		public SceneCandidate inspectionCandidate;
		public MotionObservation inspectionPath;
		public SpeechCancellationReceipt cancellation;
		public LossReason lossReason;
		public String targetKey, historicalName;
		public long returnRequestId = -1, presentSinceTick = -1, placementGeneration = -1;
		public int worldViewId = Integer.MIN_VALUE, plane = -1;
		public boolean inspectionArrived, known = true;
		public CancelledInterestObservation build() { return new CancelledInterestObservation(this); }
	}
}
