package com.malfoid.world;

import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/** F.7's local, session-only facts. An ineligible fact is consumed on its own tick. */
public final class LivingWorldMoments
{
    private final LivingWorldData data;
    private final Random random;
    private final LongSupplier candidateIds;
    private final Map<String, Long> lastPaint = new HashMap<>();
    private long generation = Long.MIN_VALUE, previousTick = Long.MIN_VALUE, issuedId = -1;
    private boolean insideKnown, inside, diedInside, playerCombatAbsent, diedDuringCombat;
    private int sameInsideTicks, keptBackTicks;
    private boolean keptBackOffered;
    private long returnedSince = -1;

    public LivingWorldMoments(LivingWorldData data, Random random, LongSupplier candidateIds)
    {
        this.data = data; this.random = random; this.candidateIds = candidateIds;
    }

    public SceneCandidate observe(long tick, long sessionGeneration, long sceneRevision,
        boolean present, long presentSinceTick, boolean combatAbsence, boolean alive,
        boolean wildernessSampleAllowed, boolean wildernessInside, int currentHp, int realHp, boolean eligible)
    {
        if (generation != sessionGeneration) { resetSession(); generation = sessionGeneration; }
        if (tick == previousTick) { return null; }
        previousTick = tick; issuedId = -1;
        if (combatAbsence) { onPlayerCombatAbsence(); }
        String fact = null;
        if (present && playerCombatAbsent)
        {
            playerCombatAbsent = false;
            returnedSince = diedDuringCombat ? -1 : presentSinceTick;
        }
        if (!present && !combatAbsence) { returnedSince = -1; }
        if (returnedSince >= 0 && tick - returnedSince >= 10)
        {
            if (tick - returnedSince == 10 && alive && !diedDuringCombat)
            {
                fact = "BACK_AFTER_PLAYER_COMBAT";
            }
            returnedSince = -1;
        }
        if (wildernessSampleAllowed)
        {
            String wildernessFact = sampleWilderness(wildernessInside);
            if (fact == null) { fact = wildernessFact; }
        }
        return fact == null || !eligible ? null
            : offerFact(fact, tick, sessionGeneration, sceneRevision, currentHp, realHp, 0);
    }

    private String sampleWilderness(boolean current)
    {
        if (!insideKnown)
        {
            insideKnown = true; inside = current; sameInsideTicks = 1; diedInside = false;
            return null;
        }
        if (inside == current)
        {
            sameInsideTicks = Math.min(200, sameInsideTicks + 1);
            return null;
        }
        String fact = current && sameInsideTicks >= 50 ? "WILDERNESS_ENTERED"
            : !current && sameInsideTicks >= 200 && !diedInside ? "WILDERNESS_LEFT" : null;
        inside = current; sameInsideTicks = 1; diedInside = false;
        return fact;
    }

    public void onPlayerCombatAbsence()
    {
        if (!playerCombatAbsent) { diedDuringCombat = false; }
        playerCombatAbsent = true; returnedSince = -1;
    }

    public void onLocalDeath()
    {
        if (insideKnown && inside) { diedInside = true; }
        if (playerCombatAbsent || returnedSince >= 0) { diedDuringCombat = true; returnedSince = -1; }
    }

    /** G.4 calls this only when a real native cycle first enters the impatient loop. */
    public SceneCandidate offerWaiting(long tick, long sessionGeneration, long sceneRevision, int timesLately,
        boolean eligible)
    {
        if (generation != sessionGeneration) { resetSession(); generation = sessionGeneration; }
        return eligible ? offerFact("KEPT_WAITING", tick, sessionGeneration, sceneRevision, 1, 1, timesLately) : null;
    }

    /**
     * H.7: she has stopped short because he stands by a creature she fears. The fact exists from the fourth tick in a
     * row of that. Unlike a crossing it is still true a moment later, so it waits for its turn: it is offered once, on
     * the first of those ticks on which a line may be offered, and not again until she has stopped being kept back.
     */
    public SceneCandidate observeKeptBack(long tick, long sessionGeneration, long sceneRevision, boolean keptBack,
        boolean eligible)
    {
        if (generation != sessionGeneration) { resetSession(); generation = sessionGeneration; }
        if (!keptBack) { keptBackTicks = 0; keptBackOffered = false; return null; }
        if (keptBackTicks < 4) { keptBackTicks++; }
        if (keptBackTicks < 4 || keptBackOffered || !eligible) { return null; }
        keptBackOffered = true;
        return offerFact("KEPT_BACK_BY_BEAST", tick, sessionGeneration, sceneRevision, 1, 1, 0);
    }

    private SceneCandidate offerFact(String fact, long tick, long sessionGeneration, long sceneRevision,
        int currentHp, int realHp, int timesLately)
    {
        for (LivingWorldData.Moment moment : data.moments)
        {
            if (!fact.equals(moment.fact) || !moment.matchesHp(currentHp, realHp)
                || !moment.matchesTimesLately(timesLately)) { continue; }
            Long painted = lastPaint.get(moment.logicalKey);
            int spacing = "BACK_AFTER_PLAYER_COMBAT".equals(fact) ? 300
                : "KEPT_WAITING".equals(fact) ? 0 : "KEPT_BACK_BY_BEAST".equals(fact) ? 500 : 1500;
            if (painted != null && tick - painted < spacing) { return null; }
            int chance = "WILDERNESS_LEFT".equals(fact) ? 40
                : "BACK_AFTER_PLAYER_COMBAT".equals(fact) || "KEPT_BACK_BY_BEAST".equals(fact) ? 60 : 50;
            if (random.nextInt(100) >= chance) { return null; }
            Map<String, String> facts = new HashMap<>();
            facts.put("livingWorld", "MOMENT"); facts.put("momentEntry", moment.id);
            facts.put("momentFact", fact);
            issuedId = candidateIds.getAsLong();
            return new SceneCandidate(issuedId, moment.id, -100, tick, tick + 1, sessionGeneration, sceneRevision,
                "world-moment:" + fact + ":" + tick, facts, false, 0);
        }
        return null;
    }

    public boolean matches(SceneCandidate candidate, long tick)
    {
        LivingWorldData.Group group = data.group(candidate.getScenarioId());
        return group instanceof LivingWorldData.Moment && candidate.getId() == issuedId
            && candidate.getCreatedTick() == tick && candidate.getSessionGeneration() == generation
            && ((LivingWorldData.Moment) group).fact.equals(candidate.getConditionFacts().get("momentFact"))
            && group.id.equals(candidate.getConditionFacts().get("momentEntry"));
    }

    public void onPaint(SpeechPacket.PaintReceipt receipt)
    {
        if (receipt == null || receipt.getCandidate().getSessionGeneration() != generation
            || !"MOMENT".equals(receipt.getCandidate().getConditionFacts().get("livingWorld"))) { return; }
        LivingWorldData.Group group = data.group(receipt.getCandidate().getScenarioId());
        if (!(group instanceof LivingWorldData.Moment)) { return; }
        for (LivingWorldData.Line line : group.lines)
        {
            if (line.id.equals(receipt.getLineId()))
            {
                String key = ((LivingWorldData.Moment) group).logicalKey;
                lastPaint.put(key, Math.max(lastPaint.getOrDefault(key, Long.MIN_VALUE), receipt.getPaintedAtTick()));
                return;
            }
        }
    }

    public void resetSession()
    {
        generation = previousTick = Long.MIN_VALUE; issuedId = -1; lastPaint.clear();
        insideKnown = inside = diedInside = playerCombatAbsent = diedDuringCombat = false;
        keptBackTicks = 0; keptBackOffered = false;
        sameInsideTicks = 0; returnedSince = -1;
    }
}
