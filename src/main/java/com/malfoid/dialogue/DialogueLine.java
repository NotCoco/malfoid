package com.malfoid.dialogue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class DialogueLine
{
    private final String id;
    private final String scenario;
    private final String text;
    private final String emotion;
    private final String normalizedText;
    private final Map<String, String> conditions;
    private final boolean enabled;
    private final String presentation;

    DialogueLine(String id, String scenario, String text, Map<String, String> conditions,
        boolean enabled, String presentation)
    {
        this(id, scenario, text, "QUIET", conditions, enabled, presentation);
    }

    DialogueLine(String id, String scenario, String text, String emotion, Map<String, String> conditions,
        boolean enabled, String presentation)
    {
        this.id = id;
        this.scenario = scenario;
        this.text = text;
        this.emotion = emotion;
        this.normalizedText = DialogueCatalog.normalizeText(text);
        this.conditions = Collections.unmodifiableMap(new HashMap<>(conditions));
        this.enabled = enabled;
        this.presentation = presentation;
    }

    public String getId() { return id; }
    public String getScenario() { return scenario; }
    public String getText() { return text; }
    public String getEmotion() { return emotion; }
    public String getNormalizedText() { return normalizedText; }
    public Map<String, String> getConditions() { return conditions; }
    public boolean isEnabled() { return enabled; }
    public boolean isOverhead() { return "OVERHEAD".equals(presentation); }

    public boolean matches(Map<String, String> facts)
    {
        for (Map.Entry<String, String> condition : conditions.entrySet())
        {
            String actual = facts.get(condition.getKey());
            if (actual == null && "newRealLevel".equals(condition.getKey()))
            {
                actual = facts.get("newLevel");
            }
            if ("recentReunionWithinTicks".equals(condition.getKey())
                || "playerHpAtMostPercent".equals(condition.getKey()))
            {
                if (actual == null || !isWithin(actual, condition.getValue()))
                {
                    return false;
                }
            }
            else if (!condition.getValue().equals(actual))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isWithin(String actual, String bound)
    {
        try
        {
            int value = Integer.parseInt(actual);
            return value >= 0 && value <= Integer.parseInt(bound);
        }
        catch (NumberFormatException ignored)
        {
            return false;
        }
    }
}
