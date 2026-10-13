package com.malfoid.world;

import java.util.HashSet;
import java.util.Set;

/** Physical scenery visits survive scene reloads and presence changes, but never a session reset. */
public final class SceneryVisits
{
    public static final int MAX_VISITS = 512;
    private final Set<String> objects = new HashSet<>();
    private final Set<String> kinds = new HashSet<>();

    public boolean canVisit(String objectKey) { return objectKey != null && objects.size() < MAX_VISITS && !objects.contains(objectKey); }
    public boolean unseenKind(String kind) { return !kinds.contains(kind); }
    public int objectCount() { return objects.size(); }
    public int kindCount() { return kinds.size(); }

    /** Duplicate arrival callbacks confirm the existing mark; capacity never evicts an earlier visit. */
    public boolean arrived(String objectKey, String kind)
    {
        if (objectKey == null) { return false; }
        if (objects.contains(objectKey)) { return true; }
        if (objects.size() >= MAX_VISITS) { return false; }
        objects.add(objectKey);
        kinds.add(kind);
        return true;
    }

    public void resetSession() { objects.clear(); kinds.clear(); }
}
