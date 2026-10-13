package com.malfoid.scenario;

/** Copied context from a director-validated inspection arrival and full hold completion. */
public final class InspectionCompletionRecord
{
	public final String key, category, name;
	public final long completedTick, sessionGeneration, sceneRevision, presentSinceTick;
	InspectionCompletionRecord(ScenarioSnapshot.InspectionTarget object, ScenarioSnapshot frame)
	{
		key = object.key; category = ScenarioRules.objectKind(object.name); name = object.name;
		completedTick = frame.tick; sessionGeneration = frame.sessionGeneration;
		sceneRevision = frame.sceneRevision; presentSinceTick = frame.presentSinceTick;
	}
}
