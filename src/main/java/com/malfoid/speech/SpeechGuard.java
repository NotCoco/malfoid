package com.malfoid.speech;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/** Anonymous process-lifetime admission/settlement; it retains no game or account facts. */
public final class SpeechGuard
{
    private static final long SECOND = 1_000_000_000L;
    private static final long SITUATION_LIFETIME = 5_400L * SECOND;
    private static SpeechGuard processGuard;
    private final LongSupplier clock;
    private final Random random;
    private final Map<String, Stamp> texts = new HashMap<>();
    private final Map<String, Stamp> interactions = new HashMap<>();
    private final LinkedHashMap<Long, Long> situations = new LinkedHashMap<>();
    private int maximumTextKeys;
    private int maximumInteractionKeys;
    private long nextToken;
    private Reservation pending;
    private boolean hasLatestPaint;
    private long latestPaint;

    public SpeechGuard(LongSupplier clock, Random random, int maximumTextKeys, int maximumInteractionKeys)
    {
        this.clock = clock;
        this.random = random;
        this.maximumTextKeys = maximumTextKeys;
        this.maximumInteractionKeys = maximumInteractionKeys;
    }

    public static synchronized SpeechGuard processLifetime(LongSupplier clock, Random random,
        int maximumTextKeys, int maximumInteractionKeys)
    {
        if (processGuard == null)
        {
            processGuard = new SpeechGuard(clock, random, maximumTextKeys, maximumInteractionKeys);
        }
        else
        {
            // A validated resource refresh can enlarge the finite authored universe.
            processGuard.maximumTextKeys = Math.max(processGuard.maximumTextKeys, maximumTextKeys);
            processGuard.maximumInteractionKeys = Math.max(processGuard.maximumInteractionKeys, maximumInteractionKeys);
        }
        return processGuard;
    }

    public synchronized boolean interactionEligible(String key)
    {
        prune(clock.getAsLong());
        return keyEligible(interactions, key, maximumInteractionKeys)
            && (pending == null || key == null || !key.equals(pending.interactionKey));
    }

    public synchronized boolean textEligible(String key)
    {
        prune(clock.getAsLong());
        return keyEligible(texts, key, maximumTextKeys)
            && (pending == null || key == null || !key.equals(pending.textKey));
    }

    public synchronized boolean situationEligible(Long fingerprint)
    {
        prune(clock.getAsLong());
        return fingerprint != null && !situations.containsKey(fingerprint);
    }

    public synchronized Reservation reserve(String interactionKey, String textKey, Long fingerprint)
    {
        prune(clock.getAsLong());
        if (pending != null || !keyEligible(interactions, interactionKey, maximumInteractionKeys)
            || !keyEligible(texts, textKey, maximumTextKeys))
        {
            return null;
        }
        pending = new Reservation(++nextToken, interactionKey, textKey, fingerprint);
        return pending;
    }

    /** Actual first paint owns one shared sample, even when its narrative session ended. */
    public synchronized boolean settlePaint(Reservation reservation, long paintedAt)
    {
        if (!isPending(reservation))
        {
            return false;
        }
        long duration = (30L + random.nextInt(31)) * SECOND;
        commit(interactions, reservation.interactionKey, paintedAt, duration);
        commit(texts, reservation.textKey, paintedAt, duration);
        if (!hasLatestPaint || paintedAt - latestPaint > 0)
        {
            latestPaint = paintedAt;
            hasLatestPaint = true;
        }
        if (reservation.fingerprint != null && !situations.containsKey(reservation.fingerprint))
        {
            pruneSituations(clock.getAsLong());
            if (situations.size() == 256)
            {
                situations.remove(situations.keySet().iterator().next());
            }
            situations.put(reservation.fingerprint, paintedAt);
        }
        pending = null;
        return true;
    }

    public synchronized boolean settleSilentArrival(Reservation reservation)
    {
        if (!isPending(reservation))
        {
            return false;
        }
        // Requested muted conversation has no automatic repeat key and needs no draw.
        if (reservation.interactionKey != null)
        {
            commit(interactions, reservation.interactionKey, clock.getAsLong(), (30L + random.nextInt(31)) * SECOND);
        }
        pending = null;
        return true;
    }

    public synchronized void retireNoPaint(Reservation reservation)
    {
        if (isPending(reservation))
        {
            pending = null;
        }
    }

    public synchronized long earliestOrdinaryPaint()
    {
        return hasLatestPaint ? latestPaint + 2L * SECOND : clock.getAsLong();
    }

    public synchronized boolean handoffEligible()
    {
        return !hasLatestPaint || clock.getAsLong() - latestPaint >= 2L * SECOND;
    }

    public synchronized int textCount() { prune(clock.getAsLong()); return texts.size(); }
    public synchronized int interactionCount() { prune(clock.getAsLong()); return interactions.size(); }
    public synchronized int situationCount() { prune(clock.getAsLong()); return situations.size(); }
    public synchronized boolean hasPendingReservation() { return pending != null; }

    private boolean isPending(Reservation reservation)
    {
        return reservation != null && pending != null && reservation.token == pending.token;
    }

    private static boolean keyEligible(Map<String, Stamp> records, String key, int maximum)
    {
        return key == null || (!records.containsKey(key) && records.size() < maximum);
    }

    private static void commit(Map<String, Stamp> records, String key, long at, long duration)
    {
        if (key == null)
        {
            return;
        }
        Stamp existing = records.get(key);
        if (existing == null || (at + duration) - (existing.at + existing.duration) > 0)
        {
            records.put(key, new Stamp(at, duration));
        }
    }

    private void prune(long now)
    {
        texts.values().removeIf(stamp -> now - stamp.at >= stamp.duration);
        interactions.values().removeIf(stamp -> now - stamp.at >= stamp.duration);
        pruneSituations(now);
    }

    private void pruneSituations(long now)
    {
        Iterator<Map.Entry<Long, Long>> iterator = situations.entrySet().iterator();
        while (iterator.hasNext())
        {
            if (now - iterator.next().getValue() >= SITUATION_LIFETIME)
            {
                iterator.remove();
            }
        }
    }

    public static final class Reservation
    {
        private final long token;
        private final String interactionKey;
        private final String textKey;
        private final Long fingerprint;

        private Reservation(long token, String interactionKey, String textKey, Long fingerprint)
        {
            this.token = token;
            this.interactionKey = interactionKey;
            this.textKey = textKey;
            this.fingerprint = fingerprint;
        }

        public long getToken() { return token; }
    }

    private static final class Stamp
    {
        private final long at;
        private final long duration;

        private Stamp(long at, long duration)
        {
            this.at = at;
            this.duration = duration;
        }
    }
}
