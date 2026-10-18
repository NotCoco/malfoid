package com.malfoid.speech;

import com.malfoid.scenario.PersonalMilestones.Proof;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A copied, generation-bound opportunity. No RuneLite objects cross this boundary. */
public final class SceneCandidate
{
    private final long id;
    private final String scenarioId;
    private final int priority;
    private final long createdTick;
    private final long expiresTick;
    private final long sessionGeneration;
    private final long sceneRevision;
    private final String causalToken;
    private final Map<String, String> conditionFacts;
    private final boolean intentionalSilent;
    private final int holdTicks;
    private final int rebindCount;
    private final Proof milestone;

    public SceneCandidate(long id, String scenarioId, int priority, long createdTick,
        long expiresTick, long sessionGeneration, long sceneRevision, String causalToken,
        Map<String, String> conditionFacts, boolean intentionalSilent, int holdTicks)
    {
        this(id, scenarioId, priority, createdTick, expiresTick, sessionGeneration, sceneRevision,
            causalToken, conditionFacts, intentionalSilent, holdTicks, 0, null);
    }

    public SceneCandidate(long id, String scenarioId, int priority, long createdTick,
        long expiresTick, long sessionGeneration, long sceneRevision, String causalToken,
        Map<String, String> conditionFacts, boolean intentionalSilent, int holdTicks,
        Proof milestone)
    {
        this(id, scenarioId, priority, createdTick, expiresTick, sessionGeneration, sceneRevision,
            causalToken, conditionFacts, intentionalSilent, holdTicks, 0, milestone);
    }

    private SceneCandidate(long id, String scenarioId, int priority, long createdTick,
        long expiresTick, long sessionGeneration, long sceneRevision, String causalToken,
        Map<String, String> conditionFacts, boolean intentionalSilent, int holdTicks, int rebindCount,
        Proof milestone)
    {
        if (expiresTick <= createdTick || holdTicks < 0 || holdTicks > 40)
        {
            throw new IllegalArgumentException("Invalid candidate duration");
        }
        if (milestone != null && (!milestone.ruleId.equals(scenarioId)
            || milestone.sessionGeneration != sessionGeneration
            || milestone.sceneRevision != sceneRevision
            || milestone.originalDeadline != expiresTick
            || milestone.occurrenceTick > createdTick
            || milestone.presenceEpoch > milestone.occurrenceTick))
        {
            throw new IllegalArgumentException("Additional context does not own candidate");
        }
        this.id = id;
        this.scenarioId = Objects.requireNonNull(scenarioId);
        this.priority = priority;
        this.createdTick = createdTick;
        this.expiresTick = expiresTick;
        this.sessionGeneration = sessionGeneration;
        this.sceneRevision = sceneRevision;
        this.causalToken = Objects.requireNonNull(causalToken);
        this.conditionFacts = Collections.unmodifiableMap(new HashMap<>(conditionFacts));
        this.intentionalSilent = intentionalSilent;
        this.holdTicks = holdTicks;
        this.rebindCount = rebindCount;
        this.milestone = milestone;
    }

    public long getId() { return id; }
    public String getScenarioId() { return scenarioId; }
    public int getPriority() { return priority; }
    public long getCreatedTick() { return createdTick; }
    public long getExpiresTick() { return expiresTick; }
    public long getSessionGeneration() { return sessionGeneration; }
    public long getSceneRevision() { return sceneRevision; }
    public String getCausalToken() { return causalToken; }
    public Map<String, String> getConditionFacts() { return conditionFacts; }
    public boolean isIntentionalSilent() { return intentionalSilent; }
    public int getHoldTicks() { return holdTicks; }
    public int getRebindCount() { return rebindCount; }
    public Proof getMilestone() { return milestone; }

    SceneCandidate unboundForLoading()
    {
        Map<String, String> facts = new HashMap<>(conditionFacts);
        facts.remove("burntShrimpPresent");
        return new SceneCandidate(id, "M04", priority, createdTick, expiresTick, sessionGeneration,
            -1, causalToken, facts, intentionalSilent, holdTicks, rebindCount, milestone);
    }

    SceneCandidate rebound(long newSceneRevision)
    {
        return new SceneCandidate(id, scenarioId, priority, createdTick, expiresTick, sessionGeneration,
            newSceneRevision, causalToken, conditionFacts, intentionalSilent, holdTicks, rebindCount + 1, milestone);
    }

    SceneCandidate updateRequestedStreak(SceneCandidate newer)
    {
        return new SceneCandidate(newer.id, scenarioId, priority, createdTick, expiresTick, sessionGeneration,
            sceneRevision, causalToken, newer.conditionFacts, newer.intentionalSilent, newer.holdTicks, rebindCount, milestone);
    }
    public boolean isRequested() { return "U01".equals(scenarioId); }
    public boolean isEmergency() { return "M01".equals(scenarioId) || "M02".equals(scenarioId); }
    public boolean isMilestone99()
    {
        return ("M04".equals(scenarioId) || "M48".equals(scenarioId))
            && "99".equals(conditionFacts.getOrDefault("newRealLevel", conditionFacts.get("newLevel")));
    }
}
