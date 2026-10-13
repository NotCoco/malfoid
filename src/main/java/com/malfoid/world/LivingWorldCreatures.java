package com.malfoid.world;

import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/** Copied creature opportunities. NativeActor remains the sole owner of physical effects. */
public final class LivingWorldCreatures
{
    /** Same finite identity bound as the native active-view intake; full means silent until reset. */
    public static final int MAX_WIZARD_IDENTITIES = 256;
    public static final class Sighting
    {
        public final String key;
        public final LivingWorldData.CreatureGroup group;
        public final int worldX, worldY, plane, distance;

        public Sighting(String key, LivingWorldData.CreatureGroup group, int worldX, int worldY, int plane, int distance)
        {
            this.key = key; this.group = group; this.worldX = worldX; this.worldY = worldY;
            this.plane = plane; this.distance = distance;
        }
    }

    private final Random random;
    private final LongSupplier candidateIds;
    private final Map<String, Long> lastAttempt = new HashMap<>();
    private final Set<String> wizardsSeen = new HashSet<>();
    private long generation = Long.MIN_VALUE, revision = Long.MIN_VALUE, previousTick = Long.MIN_VALUE;
    private String priorGroup, lastFondArrivalKey;
    private long lastSafetyBegin = Long.MIN_VALUE, lastFondArrivalTick = Long.MIN_VALUE;
    private Sighting current, wizardLook;
    private String petKey;
    private int petTicks;
    private boolean petAttempted;
    private long lastPetPaint = Long.MIN_VALUE;

    public LivingWorldCreatures(Random random, LongSupplier candidateIds)
    {
        this.random = random; this.candidateIds = candidateIds;
    }

    public SceneCandidate observe(long tick, long sessionGeneration, long sceneRevision, boolean known,
        boolean reactionAllowed, boolean speechAllowed, List<Sighting> creatures,
        String safetyGroup, long safetyBeginTick, String fondArrivalKey, long fondArrivalTick)
    {
        if (generation != sessionGeneration) { resetSession(); generation = sessionGeneration; }
        if (revision != sceneRevision || previousTick != Long.MIN_VALUE && tick != previousTick + 1)
        {
            resetObservation();
        }
        revision = sceneRevision; previousTick = tick; wizardLook = null;
        if (!known)
        {
            // UNKNOWN revokes the current capability; it does not prove the encounter ended.
            current = null;
            petTicks = 0;
            return null;
        }
        boolean petMatured = observePet(creatures);
        current = nearestFirstGroup(creatures);
        String groupId = current == null ? null : current.group.id;
        boolean firstSight = groupId != null && !groupId.equals(priorGroup);
        priorGroup = groupId;
        if (current == null) { return null; }

        LivingWorldData.Behaviour behaviour = current.group.behaviour;
        int chance, spacing = 0;
        boolean event;
        switch (behaviour)
        {
            case AFRAID:
            case AFRAID_AT_DISTANCE:
            case WARY:
                event = groupId.equals(safetyGroup) && safetyBeginTick >= 0 && safetyBeginTick != lastSafetyBegin;
                if (event) { lastSafetyBegin = safetyBeginTick; }
                chance = behaviour == LivingWorldData.Behaviour.AFRAID_AT_DISTANCE ? 60 : 50;
                break;
            case DISGUSTED:
                event = firstSight; chance = 50; spacing = 300;
                break;
            case FOND:
                event = current.key.equals(fondArrivalKey) && fondArrivalTick == tick
                    && (!current.key.equals(lastFondArrivalKey) || fondArrivalTick != lastFondArrivalTick);
                if (event) { lastFondArrivalKey = fondArrivalKey; lastFondArrivalTick = fondArrivalTick; }
                chance = 50; spacing = 300;
                break;
            case LOOKS_THEM_OVER:
                if (current.group.localPlayersFollower)
                {
                    event = petMatured && current.key.equals(petKey);
                    chance = 50; spacing = 1500;
                    if (event && reactionAllowed) { wizardLook = current; }
                    break;
                }
                event = !wizardsSeen.contains(current.key) && wizardsSeen.size() < MAX_WIZARD_IDENTITIES;
                if (event) { wizardsSeen.add(current.key); }
                chance = 40; spacing = 500;
                if (event && reactionAllowed) { wizardLook = current; }
                break;
            case SPEAKS_ONLY:
                event = firstSight; chance = 30; spacing = 500;
                break;
            default: return null;
        }
        // Empty WORLD F01/F02 groups leave their higher-priority E.5/E.9 speech untouched.
        if (!event || current.group.lines.isEmpty()) { return null; }
        boolean pet = current.group.localPlayersFollower;
        Long prior = pet ? lastPetPaint == Long.MIN_VALUE ? null : lastPetPaint : lastAttempt.get(groupId);
        if (prior != null && tick - prior < spacing) { return null; }
        if (!pet) { lastAttempt.put(groupId, tick); }
        if (!speechAllowed || random.nextInt(100) >= chance) { return null; }
        Map<String, String> facts = new HashMap<>();
        facts.put("livingWorld", "CREATURE"); facts.put("creatureGroup", groupId);
        facts.put("creatureKey", current.key);
        facts.put("creatureX", Integer.toString(current.worldX));
        facts.put("creatureY", Integer.toString(current.worldY));
        facts.put("creaturePlane", Integer.toString(current.plane));
        return new SceneCandidate(candidateIds.getAsLong(), groupId, -100, tick, tick + 1,
            sessionGeneration, sceneRevision, "world-creature:" + groupId + ":" + current.key + ":" + tick,
            facts, false, 0);
    }

    public Sighting getWizardLook() { return wizardLook; }
    public Sighting getCurrent() { return current; }
    public int getWizardIdentityCount() { return wizardsSeen.size(); }

    private boolean observePet(List<Sighting> creatures)
    {
        Sighting pet = null;
        for (Sighting sighting : creatures)
        {
            if (sighting != null && sighting.group != null && sighting.group.localPlayersFollower
                && sighting.key != null && sighting.distance >= 0 && sighting.distance <= 6)
            {
                pet = sighting;
                break;
            }
        }
        if (pet == null) { petKey = null; petTicks = 0; petAttempted = false; return false; }
        if (!pet.key.equals(petKey)) { petKey = pet.key; petTicks = 0; petAttempted = false; }
        if (petTicks < 20) { petTicks++; }
        if (petTicks != 20 || petAttempted) { return false; }
        petAttempted = true;
        return true;
    }

    /** Only a settled real first paint starts the pet's 1,500-tick speech spacing. */
    public void onPaint(SpeechPacket.PaintReceipt receipt)
    {
        if (receipt == null || receipt.getCandidate().getSessionGeneration() != generation
            || !"CR-PET".equals(receipt.getCandidate().getScenarioId())
            || !"CREATURE".equals(receipt.getCandidate().getConditionFacts().get("livingWorld"))) { return; }
        lastPetPaint = Math.max(lastPetPaint, receipt.getPaintedAtTick());
    }

    public boolean matches(SceneCandidate candidate)
    {
        return current != null && candidate.getSessionGeneration() == generation
            && candidate.getSceneRevision() == revision && current.group.id.equals(candidate.getScenarioId())
            && current.key.equals(candidate.getConditionFacts().get("creatureKey"));
    }

    private static Sighting nearestFirstGroup(List<Sighting> creatures)
    {
        Sighting best = null;
        for (Sighting creature : creatures)
        {
            if (creature == null || creature.group == null || creature.key == null || creature.distance > 6
                || creature.distance < 0) { continue; }
            if (best == null || creature.group.ordinal < best.group.ordinal
                || creature.group.ordinal == best.group.ordinal && (creature.distance < best.distance
                    || creature.distance == best.distance && creature.key.compareTo(best.key) < 0))
            {
                best = creature;
            }
        }
        return best;
    }

    public void resetObservation()
    {
        current = wizardLook = null; priorGroup = null;
        lastFondArrivalKey = null; lastFondArrivalTick = Long.MIN_VALUE;
        previousTick = Long.MIN_VALUE;
        petTicks = 0;
    }

    public void resetSession()
    {
        resetObservation(); lastAttempt.clear(); wizardsSeen.clear();
        lastSafetyBegin = Long.MIN_VALUE;
        petKey = null; petAttempted = false; lastPetPaint = Long.MIN_VALUE;
        generation = revision = Long.MIN_VALUE;
    }
}
