package com.malfoid.additional;

/** Only the session and scene revisions; no world or account identity. */
public final class SceneKey
{
	public final long sessionGeneration, sceneRevision;
	public SceneKey(long sessionGeneration, long sceneRevision)
	{
		this.sessionGeneration = sessionGeneration; this.sceneRevision = sceneRevision;
	}
	@Override public boolean equals(Object other)
	{
		if (!(other instanceof SceneKey)) { return false; }
		SceneKey key = (SceneKey) other;
		return sessionGeneration == key.sessionGeneration && sceneRevision == key.sceneRevision;
	}
	@Override public int hashCode() { return Long.hashCode(sessionGeneration) * 31 + Long.hashCode(sceneRevision); }
}
