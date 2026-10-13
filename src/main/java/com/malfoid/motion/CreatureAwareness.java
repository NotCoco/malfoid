package com.malfoid.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Copied creature facts and the E.5 near/ending state; contains no client objects. */
public final class CreatureAwareness
{
    public enum Behaviour { AFRAID, WARY, DISGUST, FOND, WIZARD, NONE }
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

    public static final class Classification
    {
        public final String groupId;
        public final int priority, minPlayerDistance, maxPlayerDistance;
        public final Behaviour behaviour;
        public Classification(String groupId, int priority, Behaviour behaviour, int minimum, int maximum)
        {
            this.groupId = groupId; this.priority = priority; this.behaviour = behaviour;
            minPlayerDistance = minimum; maxPlayerDistance = maximum;
        }
        public boolean keepsAway() { return behaviour == Behaviour.AFRAID || behaviour == Behaviour.WARY; }
    }

    public static final class Creature
    {
        public final String key, name;
        public final Classification classification;
        public final int combatLevel, worldX, worldY, plane, size;
        public Creature(String key, Classification classification, String name, int level, int x, int y, int plane)
        {
            this(key, classification, name, level, x, y, plane, 1);
        }
        /** (x, y) is its south-west tile; it stands on `size` tiles each way. */
        public Creature(String key, Classification classification, String name, int level, int x, int y, int plane, int size)
        {
            this.key = key; this.classification = classification; this.name = name; combatLevel = level;
            worldX = x; worldY = y; this.plane = plane; this.size = Math.max(1, size);
        }
        /** Tiles from (x, y) to the nearest tile it stands on. */
        public int distance(int x, int y)
        {
            return MotionMath.footprintDistance(x, y, worldX, worldY, worldX + size - 1, worldY + size - 1);
        }
    }

    public static final class Sample
    {
        public final long tick;
        public final int worldViewId, baseX, baseY, plane;
        public final boolean known;
        public final List<Creature> creatures;
        public Sample(long tick, int view, int baseX, int baseY, int plane, boolean known, List<Creature> creatures)
        {
            this.tick = tick; worldViewId = view; this.baseX = baseX; this.baseY = baseY; this.plane = plane;
            this.known = known; this.creatures = Collections.unmodifiableList(new ArrayList<>(creatures));
        }
    }

    private Creature target, beginCreature;
    private long beginTick = -1, lastTick = Long.MIN_VALUE;
    private int quietTicks, view, baseX, baseY, plane;
    private boolean known, endedKeepingAway;

    public void commit(Sample sample, int actorX, int actorY)
    {
        endedKeepingAway = false;
        if (sample.tick == lastTick) { return; }
        boolean contiguous = lastTick != Long.MIN_VALUE && sample.tick == lastTick + 1
            && sample.worldViewId == view && sample.baseX == baseX && sample.baseY == baseY && sample.plane == plane;
        if (lastTick != Long.MIN_VALUE && (sample.worldViewId != view || sample.baseX != baseX
            || sample.baseY != baseY || sample.plane != plane)) { reset(); }
        else if (!contiguous) { quietTicks = 0; }
        lastTick = sample.tick; view = sample.worldViewId; baseX = sample.baseX; baseY = sample.baseY; plane = sample.plane;
        known = sample.known;
        if (!known) { quietTicks = 0; return; }
        Creature near = nearest(sample, actorX, actorY, 6, null);
        Creature retained = isKeepingAway() ? nearest(sample, actorX, actorY, 8, target.classification.groupId) : null;
        if (isKeepingAway())
        {
            if (near != null && near.classification.keepsAway()
                && priority(near) < priority(target)) { quietTicks = 0; }
            else if (retained != null) { target = retained; quietTicks = 0; return; }
            else if (++quietTicks < 5) { return; }
        }
        if (near != null)
        {
            boolean wasKeepingAway = isKeepingAway();
            if (target == null || !sameFamily(target.classification.groupId, near.classification.groupId))
            {
                beginTick = sample.tick; beginCreature = near;
            }
            target = near; quietTicks = 0;
            endedKeepingAway = wasKeepingAway && !isKeepingAway();
        }
        else if (isKeepingAway())
        {
            target = beginCreature = null; beginTick = -1; endedKeepingAway = true;
        }
        else { target = beginCreature = null; beginTick = -1; quietTicks = 0; }
    }

    private static Creature nearest(Sample sample, int x, int y, int radius, String group)
    {
        Creature best = null;
        for (Creature creature : sample.creatures)
        {
            if (creature.plane != sample.plane || creature.distance(x, y) > radius
                || group != null && !sameFamily(group, creature.classification.groupId)) { continue; }
            if (best == null || priority(creature) < priority(best)
                || priority(creature) == priority(best)
                    && (creature.distance(x, y) < best.distance(x, y)
                        || creature.distance(x, y) == best.distance(x, y) && creature.key.compareTo(best.key) < 0)) { best = creature; }
        }
        return best;
    }

    public Creature getTarget() { return target; }
    public Creature getBeginCreature() { return beginCreature; }
    public String getGroupId() { return target == null ? null : target.classification.groupId; }
    public long getBeginTick() { return beginTick; }
    public boolean isKnown() { return known; }
    public boolean isKeepingAway() { return target != null && target.classification.keepsAway(); }
    public boolean didEndKeepingAway() { return endedKeepingAway; }
    public int getQuietTicks() { return quietTicks; }
    public void reset() { target = beginCreature = null; beginTick = -1; lastTick = Long.MIN_VALUE; quietTicks = 0; known = endedKeepingAway = false; }

    public static boolean isE5(String group) { return "F01".equals(group) || "F02".equals(group); }
    private static boolean sameFamily(String first, String second)
    {
        return first.equals(second) || isE5(first) && isE5(second);
    }
    private static int priority(Creature creature)
    {
        // E.5 wins over F's table ordering: distance selects the nearest rat or beast.
        return isE5(creature.classification.groupId) ? 0 : creature.classification.priority;
    }

    public static String normalizeName(String name)
    {
        return name == null ? "" : TAGS.matcher(name).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }
    /** E.5 fallback only. The full group classifier uses its pinned living-world data. */
    public static Classification classifyFear(String name, Integer combatLevel)
    {
        if (name.equals("rat") || name.endsWith(" rat") || name.endsWith("-rat"))
        {
            return new Classification("F01", 0, Behaviour.AFRAID, 1, 2);
        }
        if (name.equals("wolf") || name.equals("werewolf") || name.endsWith(" wolf")
            || name.equals("bear") || name.endsWith(" bear") || name.equals("hellhound")
            || name.equals("scorpion") || name.endsWith(" scorpion") || name.equals("snake") || name.endsWith(" snake")
            || name.equals("crocodile") || name.endsWith(" crocodile") || name.equals("bat") || name.endsWith(" bat")
            || name.equals("spider") || name.endsWith(" spider")
            || name.equals("jackal") || name.equals("guard dog") || name.equals("wild dog") || name.equals("terror dog")
            || name.equals("shadow hound") || name.equals("jaguar") || name.equals("spined larupia")
            || name.equals("horned graahk") || name.equals("sabre-toothed kyatt"))
        {
            return new Classification("F02", 2, Behaviour.AFRAID, 1, 2);
        }
        return null;
    }

    /** The player lies between this tile and the creature, subject to a legal route. */
    public static boolean farSide(int x, int y, int playerX, int playerY, int creatureX, int creatureY)
    {
        return (long) (x - playerX) * (playerX - creatureX) + (long) (y - playerY) * (playerY - creatureY) > 0;
    }
}
