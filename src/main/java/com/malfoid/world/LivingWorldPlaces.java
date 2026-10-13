package com.malfoid.world;

import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.LongSupplier;

/** Session-only place arrivals from copied committed observations. Retains no client objects. */
public final class LivingWorldPlaces
{
    private final LivingWorldData data;
    private final Random random;
    private final LongSupplier candidateIds;
    private final Map<String, Long> arrivalTicks = new HashMap<>();
    private boolean sessionKnown, baselineEstablished, placePainted;
    private long sessionGeneration, sceneRevision, lastCommittedTick = -1, lastPlacePaintTick;
    private String pendingEntry, previousArrival;
    private int consecutiveTicks;

    public LivingWorldPlaces(LivingWorldData data, Random random, LongSupplier candidateIds)
    {
        this.data = Objects.requireNonNull(data);
        this.random = Objects.requireNonNull(random);
        this.candidateIds = Objects.requireNonNull(candidateIds);
    }

    /**
     * Eligibility includes PRESENT age, combat, UI, pending reactions and the shared living-world cap.
     * An ineligible arrival is still consumed; it is never saved for later speech.
     */
    public SceneCandidate observe(long tick, long generation, long revision, int regionId,
        boolean committed, boolean present, boolean eligible)
    {
        if (!sessionKnown || generation != sessionGeneration)
        {
            resetSession();
            sessionKnown = true;
            sessionGeneration = generation;
            sceneRevision = revision;
        }
        else if (revision != sceneRevision)
        {
            resetObservation();
            sceneRevision = revision;
        }
        if (!committed || !present)
        {
            resetObservation();
            return null;
        }
        if (lastCommittedTick != -1 && tick != lastCommittedTick + 1)
        {
            resetObservation();
        }
        lastCommittedTick = tick;
        LivingWorldData.PlaceGroup group = data.place(regionId);
        if (group == null)
        {
            pendingEntry = null;
            consecutiveTicks = 0;
            return null;
        }
        if (!group.id.equals(pendingEntry))
        {
            pendingEntry = group.id;
            consecutiveTicks = 1;
        }
        else if (consecutiveTicks < 5)
        {
            consecutiveTicks++;
        }
        if (consecutiveTicks != 5 || group.id.equals(previousArrival))
        {
            return null;
        }

        Long previousTick = arrivalTicks.put(group.id, tick);
        previousArrival = group.id;
        if (!baselineEstablished)
        {
            baselineEstablished = true;
            return null;
        }
        if (!eligible || previousTick != null && tick - previousTick < 1500
            || placePainted && tick - lastPlacePaintTick < 300 || random.nextDouble() >= 0.6)
        {
            return null;
        }
        Map<String, String> facts = new HashMap<>();
        facts.put("livingWorld", "PLACE");
        facts.put("placeEntry", group.id);
        return new SceneCandidate(candidateIds.getAsLong(), group.id, -100, tick, tick + 1,
            generation, revision, "PLACE_ARRIVAL:" + group.id + ":" + tick, facts, false, 0);
    }

    /** Scene/absence invalidation clears only unfinished evidence, never arrival or paint memory. */
    public void resetObservation()
    {
        pendingEntry = null;
        consecutiveTicks = 0;
        lastCommittedTick = -1;
    }

    public void resetSession()
    {
        resetObservation();
        arrivalTicks.clear();
        previousArrival = null;
        baselineEstablished = false;
        placePainted = false;
        sessionKnown = false;
    }

    public void onPaint(SpeechPacket.PaintReceipt receipt)
    {
        if (receipt == null || !sessionKnown) { return; }
        SceneCandidate candidate = receipt.getCandidate();
        if (candidate.getSessionGeneration() != sessionGeneration
            || !"PLACE".equals(candidate.getConditionFacts().get("livingWorld"))
            || !candidate.getScenarioId().equals(candidate.getConditionFacts().get("placeEntry"))) { return; }
        firstPaint(candidate.getScenarioId(), receipt.getLineId(), receipt.getPaintedAtTick());
    }

    /** Called only for an actual first paint; merely offering or admitting a line starts no cooldown. */
    public void firstPaint(String groupId, String lineId, long tick)
    {
        LivingWorldData.Group group = data.group(groupId);
        if (!(group instanceof LivingWorldData.PlaceGroup) || placePainted && tick < lastPlacePaintTick) { return; }
        for (LivingWorldData.Line line : group.lines)
        {
            if (line.id.equals(lineId))
            {
                placePainted = true;
                lastPlacePaintTick = tick;
                return;
            }
        }
    }
}
