package com.malfoid.speech;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.dialogue.DialogueLine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Client-thread owner of one execution, two pending opportunities and paint settlement. */
public final class SpeechDirector
{
    public enum ActivityTone { RESTLESS, AMUSED, QUIET }
    private static final Comparator<SceneCandidate> RANK = Comparator.comparingInt(SceneCandidate::getPriority).reversed()
        .thenComparingLong(SceneCandidate::getCreatedTick).thenComparing(SceneCandidate::getScenarioId)
        .thenComparingLong(SceneCandidate::getId);
    private final DialogueCatalog catalogue;
    private final LongSupplier clock;
    private final Random random;
    private final Random sceneRandom;
    private final Consumer<String> trace;
    private final SpeechGuard guard;
    private final List<SceneCandidate> pending = new ArrayList<>(2);
    private final Deque<String> recentLines = new ArrayDeque<>(5);
    private final Map<List<String>, TalkBag> talkBags = new HashMap<>();
    private final Map<String, Long> retryUntil = new HashMap<>();
    private final Map<String, String> lastLivingWorldLines = new HashMap<>();
    private long lastLivingWorldPaintTick = Long.MIN_VALUE;
    private boolean livingWorldArrivalChosen;
    private long tick;
    private volatile long generation;
    private long revision;
    private boolean present;
    private boolean showSpeech;
    private boolean busy;
    private SceneCandidate active;
    private DialogueLine selectedLine;
    private TalkBag selectedTalkBag;
    private SpeechGuard.Reservation reservation;
    private boolean intentionalSilent;
    private boolean arrived;
    private boolean arrivalCommitted;
    private boolean continuationCancelled;
    private long holdUntilTick;
    private long originalWatchStartTick = Long.MIN_VALUE;
    private boolean holdCompleted;
    private boolean completionNotified;
    private long releaseStartedTick;
    private long releaseUntilTick;
    private long approachDeadline;
    private volatile SpeechPacket packet;
    private SpeechPacket.PaintReceipt lastPaint;
    private long settledAdmissionToken = -1;
    private Predicate<SceneCandidate> candidateValidator = candidate -> true;
    private BiPredicate<SceneCandidate, String> lineValidator = (candidate, lineId) -> true;
    private Consumer<SceneCandidate> completionListener = candidate -> {};
    private Consumer<SceneCandidate> silentArrivalListener = candidate -> {};
    private Consumer<SpeechPacket.PaintReceipt> firstPaintListener = receipt -> {};
    private Consumer<SpeechCancellationReceipt> cancellationListener = receipt -> {};
    private boolean cancellationNotified;
    private boolean executionGenuinePainted;
    private long executionIssuedPacketToken = -1;
    private long nextExecutionId;
    private long executionId;
    private ActivityTone activityTone;
    private int executionHoldTicks;
    private final Map<Long, FrozenChoice> retainedChoices = new HashMap<>();
    private boolean loadingSuspended;
    private long resumeTick = Long.MIN_VALUE;
    private long resumeRevision = Long.MIN_VALUE;
    private int resumeStable;

    public SpeechDirector(DialogueCatalog catalogue, LongSupplier clock, Random random, Consumer<String> trace)
    {
        this(catalogue, clock, random, new Random(), trace);
    }

    public SpeechDirector(DialogueCatalog catalogue, LongSupplier clock, Random random, Random sceneRandom, Consumer<String> trace)
    {
        this(catalogue, clock, random, sceneRandom, trace, SpeechGuard.processLifetime(clock, random,
            catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys()));
    }

    /** Explicit isolated history for deterministic policy tests. */
    public SpeechDirector(DialogueCatalog catalogue, LongSupplier clock, Random random,
        Consumer<String> trace, SpeechGuard guard)
    {
        this(catalogue, clock, random, new Random(0), trace, guard);
    }

    public SpeechDirector(DialogueCatalog catalogue, LongSupplier clock, Random random, Random sceneRandom,
        Consumer<String> trace, SpeechGuard guard)
    {
        this.catalogue = catalogue;
        this.clock = clock;
        this.random = random;
        this.sceneRandom = sceneRandom;
        this.trace = trace;
        this.guard = guard;
    }

    public boolean offer(SceneCandidate candidate)
    {
        if (!valid(candidate) || (!candidate.isRequested() && !catalogue.isRuntimeScenarioEnabled(candidate.getScenarioId())))
        {
            return false;
        }
        if (isLivingWorld(candidate) && (!isIdle() || busy || !livingWorldSpacingReady()
            || !candidate.getConditionFacts().getOrDefault("livingWorld", "").equals(
                catalogue.livingWorldFamily(candidate.getScenarioId()))))
        {
            // An arrival/sighting is consumed by its observer. It never joins a backlog.
            return false;
        }
        if (isEmote(candidate) && (candidate.getPriority() != 40 || !candidate.isIntentionalSilent()))
        {
            return false;
        }
        if (candidate.isRequested())
        {
            pending.removeIf(queued -> queued.getPriority() < 85);
        }
        if (candidate.isRequested() && active != null && active.isRequested())
        {
            updateRequestedExecution(candidate);
            return true;
        }
        if (candidate.isRequested())
        {
            for (int index = 0; index < pending.size(); index++)
            {
                SceneCandidate queued = pending.get(index);
                if (queued.isRequested())
                {
                    pending.set(index, queued.updateRequestedStreak(candidate));
                    return true;
                }
            }
        }
        String key = catalogue.repeatKey(candidate.getScenarioId());
        if (!isEmote(candidate) && (!guard.interactionEligible(key) || tick < retryUntil.getOrDefault(key, Long.MIN_VALUE)))
        {
            trace.accept("speech suppressed repeat " + candidate.getScenarioId());
            return false;
        }
        if (active != null && (active.getCausalToken().equals(candidate.getCausalToken())
            || active.isRequested() && candidate.isRequested()))
        {
            return false;
        }
        for (SceneCandidate queued : pending)
        {
            if (queued.getCausalToken().equals(candidate.getCausalToken())
                || queued.isRequested() && candidate.isRequested())
            {
                return false;
            }
        }
        if (active != null && mayPreempt(candidate))
        {
            cancelActive(true, false);
        }
        if (pending.size() < 2)
        {
            pending.add(candidate);
        }
        else
        {
            pending.sort(RANK);
            if (RANK.compare(candidate, pending.get(1)) >= 0)
            {
                return false;
            }
            pending.set(1, candidate);
        }
        pending.sort(RANK);
        startNext();
        return true;
    }

    public void tick(long tick, long generation, long revision, boolean present, boolean showSpeech, boolean busy)
    {
        if (this.generation != generation)
        {
            resetSession(generation);
        }
        if ((this.revision != revision || !present) && !loadingSuspended)
        {
            invalidate(true);
        }
        this.tick = tick;
        this.generation = generation;
        this.revision = revision;
        this.present = present;
        this.busy = busy;
        boolean muteNow = this.showSpeech && !showSpeech;
        this.showSpeech = showSpeech;
        if (loadingSuspended)
        {
            if (muteNow)
            {
                retainedChoices.replaceAll((id, choice) -> new FrozenChoice(null, true));
            }
            pruneRetained();
            return;
        }
        if (muteNow && active != null)
        {
            muteExecution();
        }
        if (packet != null)
        {
            settlePaint(packet);
            if (active != null && !valid(active))
            {
                packet.revoke(false);
            }
            packet.setStartAllowed(startAllowed(active));
        }
        pending.removeIf(candidate -> !valid(candidate));
        if (active != null)
        {
            if (continuationCancelled && (packet == null || packet.isFinished(clock.getAsLong())))
            {
                cancelActive(false, false);
            }
            else if ((!valid(active) || !arrived && tick >= approachDeadline) && !arrivalCommitted)
            {
                cancelActive(false, true);
            }
            else if (arrived && arrivalCommitted && tick >= holdUntilTick
                && (packet == null || packet.isFinished(clock.getAsLong())))
            {
                finishHoldOrRelease();
            }
            else if (arrived && !intentionalSilent && packet == null)
            {
                publishPacket();
            }
        }
        startNext();
    }

    /** Root calls only on a current matching, fact-validated arrival. */
    public void arrive(long candidateId, long atTick)
    {
        tick = atTick;
        if (active == null || active.getId() != candidateId || !valid(active))
        {
            return;
        }
        if (!arrived)
        {
            arrived = true;
            if (isLivingWorldScenery(active)) { chooseLivingWorldArrival(); }
            if ("M01".equals(active.getScenarioId()))
            {
                originalWatchStartTick = tick;
                holdUntilTick = tick + 20;
            }
            trace.accept("scene arrived " + active.getScenarioId());
        }
        if (intentionalSilent)
        {
            commitSilentArrival();
        }
        else
        {
            publishPacket();
        }
    }

    /** Soft dependent loss keeps a genuinely painted sentence; hard lifecycle/preemption hides it. */
    public void invalidate(boolean hard)
    {
        if (hard)
        {
            pending.clear();
            retainedChoices.clear();
            loadingSuspended = false;
            resumeStable = 0;
            cancelActive(true, false);
        }
        else if (active != null)
        {
            if (packet != null)
            {
                packet.revoke(false);
                settlePaint(packet);
            }
            if (packet == null || !packet.isPainted())
            {
                cancelActive(false, true);
            }
            else
            {
                continuationCancelled = true;
            }
        }
    }

    /** The caller supplies the exact dependent loss; generic invalidity never invents a reason. */
    public void invalidate(boolean hard, SpeechCancellationReceipt.Reason reason)
    {
        if (active != null && reason != null && !cancellationNotified)
        {
            if (packet != null)
            {
                packet.revoke(hard);
                settlePaint(packet);
            }
            cancellationNotified = true;
            cancellationListener.accept(new SpeechCancellationReceipt(active, reason, tick,
                executionId, executionIssuedPacketToken, executionGenuinePainted,
                !arrivalCommitted && !completionNotified));
        }
        invalidate(hard);
    }

    public void resetSession(long generation)
    {
        invalidate(true);
        this.generation = generation;
        recentLines.clear();
        talkBags.clear();
        retryUntil.clear();
        lastLivingWorldLines.clear();
        lastLivingWorldPaintTick = Long.MIN_VALUE;
        lastPaint = null;
        settledAdmissionToken = -1;
    }

    /** The root calls before ordinary same-session LOADING removes the old scene capability. */
    public void suspendForOrdinaryLoading(long atTick, long sessionGeneration)
    {
        tick = atTick;
        if (generation != sessionGeneration)
        {
            invalidate(true);
            return;
        }
        if (loadingSuspended)
        {
            pruneRetained();
            return;
        }
        List<SceneCandidate> retained = new ArrayList<>(2);
        for (SceneCandidate queued : pending)
        {
            if (canRetainLevel(queued)) { retained.add(queued.unboundForLoading()); }
        }
        SceneCandidate previous = active;
        DialogueLine previousLine = selectedLine;
        boolean previousSilent = intentionalSilent;
        SpeechPacket issued = packet;
        if (issued != null)
        {
            issued.revoke(true);
            settlePaint(issued);
        }
        boolean retainActive = previous != null && canRetainLevel(previous) && !arrivalCommitted
            && (issued == null || !issued.isPainted());
        cancelActive(true, false);
        pending.clear();
        if (retainActive)
        {
            retained.add(previous.unboundForLoading());
            retainedChoices.put(previous.getId(), new FrozenChoice("M48".equals(previous.getScenarioId()) ? null : previousLine, previousSilent));
        }
        retained.sort(RANK);
        for (SceneCandidate candidate : retained)
        {
            if (pending.size() < 2) { pending.add(candidate); }
        }
        retainedChoices.keySet().removeIf(id -> pending.stream().noneMatch(candidate -> candidate.getId() == id));
        loadingSuspended = !pending.isEmpty();
        resumeTick = Long.MIN_VALUE;
        resumeRevision = Long.MIN_VALUE;
        resumeStable = 0;
        present = false;
        if (!pending.isEmpty()) { trace.accept("level loading retained " + pending.size()); }
    }

    /** Call after the observer has committed its current copied snapshot; duplicate ticks do not count. */
    public void resumeAfterLoading(long atTick, long sessionGeneration, long newSceneRevision, boolean validPresentSnapshot)
    {
        if (!loadingSuspended) { return; }
        tick = atTick;
        if (sessionGeneration != generation)
        {
            invalidate(true);
            return;
        }
        pruneRetained();
        if (!loadingSuspended) { return; }
        if (!validPresentSnapshot)
        {
            resumeStable = 0;
            resumeTick = Long.MIN_VALUE;
            return;
        }
        if (atTick == resumeTick && newSceneRevision == resumeRevision) { return; }
        resumeStable = newSceneRevision == resumeRevision && atTick - resumeTick == 1 ? resumeStable + 1 : 1;
        resumeTick = atTick;
        resumeRevision = newSceneRevision;
        if (resumeStable < 2) { return; }
        pending.replaceAll(candidate -> candidate.rebound(newSceneRevision));
        loadingSuspended = false;
        revision = newSceneRevision;
        present = true;
        trace.accept("level loading rebound " + pending.size());
        startNext();
    }

    public boolean hasRetainedLevel() { return loadingSuspended && !pending.isEmpty(); }

    /** Qualifying hits refresh only physical concern; a receipt and its repeat sample never change. */
    public void refreshEmergencyWatch(long latestHitTick)
    {
        if (active == null || !"M01".equals(active.getScenarioId()) || !arrived || continuationCancelled
            || latestHitTick <= originalWatchStartTick || latestHitTick > tick)
        {
            return;
        }
        long refreshed = Math.min(originalWatchStartTick + 30, Math.max(holdUntilTick, latestHitTick + 20));
        if (refreshed > holdUntilTick)
        {
            holdUntilTick = refreshed;
            holdCompleted = false;
            trace.accept("concern watch refreshed until " + holdUntilTick);
        }
    }

    /** Called only from ClientThread.invokeLater after a successful frame. */
    public void acknowledge(SpeechPacket issued, SpeechPacket.PaintReceipt receipt)
    {
        if (receipt != null && issued.getFirstPaint() == receipt)
        {
            settlePaint(issued);
        }
    }

    public SceneCandidate getActiveCandidate() { return active; }
    public boolean isIntentionalSilent() { return intentionalSilent; }
    public ActivityTone getActivityTone() { return activityTone; }
    public int getExecutionHoldTicks() { return active == null ? 0 : executionHoldTicks; }
    public boolean isIdle() { return active == null && pending.isEmpty(); }
    public boolean livingWorldSpacingReady()
    {
        return lastLivingWorldPaintTick == Long.MIN_VALUE
            || tick >= lastLivingWorldPaintTick && tick - lastLivingWorldPaintTick >= 100;
    }
    public boolean isLivingWorld(SceneCandidate candidate)
    {
        return candidate != null && catalogue.isLivingWorldScenario(candidate.getScenarioId());
    }
    public boolean isLivingWorldScenery(SceneCandidate candidate)
    {
        return isLivingWorld(candidate) && "SCENERY".equals(catalogue.livingWorldFamily(candidate.getScenarioId()));
    }
    public boolean hasArrived() { return arrived; }
    public boolean isCommitted() { return arrivalCommitted || packet != null && packet.isPainted(); }
    public boolean isContinuationCancelled() { return continuationCancelled; }
    public SpeechPacket getPacket() { return packet; }
    public SpeechPacket.PaintReceipt getLastPaint() { return lastPaint; }
    public long getTick() { return tick; }
    public long getSessionGeneration() { return generation; }
    public long now() { return clock.getAsLong(); }
    public int pendingCount() { return pending.size(); }
    public boolean isReleasing() { return active != null && !continuationCancelled && holdCompleted && releaseUntilTick > releaseStartedTick; }
    public double getReleaseProgress()
    {
        return isReleasing() ? Math.min(1d, Math.max(0d,
            (double) (tick - releaseStartedTick) / (releaseUntilTick - releaseStartedTick))) : 0d;
    }
    public long getHoldUntilTick() { return holdUntilTick; }
    public long getReleaseUntilTick() { return releaseUntilTick; }

    /** Refresh current paint eligibility without committing another observation ordinal. */
    public void refreshPresentation(boolean busy)
    {
        this.busy = busy;
        SpeechPacket issued = packet;
        if (issued != null) { issued.setStartAllowed(startAllowed(active)); }
    }

    public void setCandidateValidator(Predicate<SceneCandidate> validator)
    {
        this.candidateValidator = validator;
    }
    public void setLineValidator(BiPredicate<SceneCandidate, String> validator)
    {
        this.lineValidator = validator;
    }
    public void setCompletionListener(Consumer<SceneCandidate> listener)
    {
        this.completionListener = listener;
    }
    public void setSilentArrivalListener(Consumer<SceneCandidate> listener)
    {
        this.silentArrivalListener = listener;
    }
    public void setCancellationListener(Consumer<SpeechCancellationReceipt> listener)
    {
        this.cancellationListener = listener;
    }
    public void setFirstPaintListener(Consumer<SpeechPacket.PaintReceipt> listener)
    {
        this.firstPaintListener = listener;
    }

    public SpeechPacket.PresentationObservation getPresentationObservation()
    {
        SpeechPacket current = packet;
        return current == null ? null : current.getObservation();
    }

    private void startNext()
    {
        if (!present || active != null)
        {
            return;
        }
        pending.removeIf(candidate -> !valid(candidate));
        for (int index = 0; index < pending.size();)
        {
            SceneCandidate next = pending.get(index);
            if (!settlingReady(next))
            {
                index++;
                continue;
            }
            boolean emote = isEmote(next);
            if (!emote && guard.hasPendingReservation())
            {
                index++;
                continue;
            }
            pending.remove(index);
            String key = catalogue.repeatKey(next.getScenarioId());
            if (!emote && !guard.interactionEligible(key))
            {
                continue;
            }
            FrozenChoice retainedChoice = retainedChoices.remove(next.getId());
            boolean deferredScenery = isLivingWorldScenery(next);
            ActivityTone tone = null;
            int holdTicks = next.getHoldTicks();
            if ("M06".equals(next.getScenarioId()))
            {
                int toneDraw = sceneRandom.nextInt(100);
                tone = toneDraw < 50 ? ActivityTone.RESTLESS : toneDraw < 80 ? ActivityTone.AMUSED : ActivityTone.QUIET;
                holdTicks = 6 + sceneRandom.nextInt(7);
            }
            int silentPercent = "M06".equals(next.getScenarioId()) ? 0 : catalogue.getSilencePercent(next.getScenarioId());
            boolean quietChoice = deferredScenery || emote || (retainedChoice == null
                ? next.isIntentionalSilent() || !showSpeech || tone == ActivityTone.QUIET || silentPercent == 100
                    || silentPercent > 0 && random.nextInt(100) < silentPercent
                : retainedChoice.silent || !showSpeech);
            DialogueLine line = quietChoice ? null : retainedChoice != null && retainedChoice.line != null
                ? retainedChoice.line : chooseLine(next);
            if (line != null && (!line.isEnabled() || !line.matches(next.getConditionFacts())
                || !guard.textEligible(line.getNormalizedText()) || !lineValidator.test(next, line.getId())))
            {
                line = null;
            }
            boolean silent = line == null;
            Long fingerprint = silent ? null : catalogue.situationFingerprint(next);
            SpeechGuard.Reservation admission = emote ? null
                : guard.reserve(key, line == null ? null : line.getNormalizedText(), fingerprint);
            if (!emote && admission == null)
            {
                // Unsettled paint accounting is a fail-closed guard, never a speculative release.
                continue;
            }
            active = next;
            activityTone = tone;
            executionHoldTicks = holdTicks;
            selectedLine = line;
            reservation = admission;
            livingWorldArrivalChosen = !deferredScenery;
            intentionalSilent = silent;
            arrived = false;
            arrivalCommitted = false;
            continuationCancelled = false;
            holdUntilTick = Long.MAX_VALUE;
            originalWatchStartTick = Long.MIN_VALUE;
            holdCompleted = false;
            completionNotified = false;
            cancellationNotified = false;
            executionGenuinePainted = false;
            executionIssuedPacketToken = -1;
            executionId = ++nextExecutionId;
            releaseStartedTick = 0;
            releaseUntilTick = 0;
            int maximumApproach = maximumApproachTicks(next);
            approachDeadline = Math.min(next.getExpiresTick(), tick + maximumApproach);
            trace.accept("scene start " + next.getScenarioId() + (silent ? " silent" : " speech"));
            return;
        }
    }

    private DialogueLine chooseLine(SceneCandidate candidate)
    {
        selectedTalkBag = null;
        Long fingerprint = catalogue.situationFingerprint(candidate);
        if (catalogue.isSituation(candidate.getScenarioId()) && !guard.situationEligible(fingerprint))
        {
            return null;
        }
        List<DialogueLine> eligible = new ArrayList<>();
        List<DialogueLine> talkPool = new ArrayList<>();
        for (DialogueLine line : catalogue.getLines(candidate.getScenarioId()))
        {
            if (line.isEnabled() && line.isOverhead() && line.matches(candidate.getConditionFacts()))
            {
                if (candidate.isRequested()) { talkPool.add(line); }
                if (guard.textEligible(line.getNormalizedText()) && lineValidator.test(candidate, line.getId()))
                {
                    eligible.add(line);
                }
            }
        }
        if (candidate.isRequested() && "1".equals(candidate.getConditionFacts().get("talkStreak")))
        {
            String selectedGroup = null;
            for (String group : new String[] {"combatConfirmed", "playerHpAtMostPercent"})
            {
                for (DialogueLine line : talkPool)
                {
                    if (line.getConditions().containsKey(group))
                    {
                        selectedGroup = group;
                        break;
                    }
                }
                if (selectedGroup != null) { break; }
            }
            if (selectedGroup == null && "true".equals(candidate.getConditionFacts().get("localNight")))
            {
                selectedGroup = random.nextInt(4) == 0 ? "localNight" : null;
            }
            String group = selectedGroup;
            List<DialogueLine> grouped = new ArrayList<>();
            for (DialogueLine line : eligible)
            {
                if (group == null ? line.getConditions().size() <= 1 : line.getConditions().containsKey(group))
                {
                    grouped.add(line);
                }
            }
            // A guard-empty preferred conditional pool falls back only to generic first answers.
            if (grouped.isEmpty() && group != null)
            {
                group = null;
                for (DialogueLine line : eligible)
                {
                    if (line.getConditions().size() <= 1) { grouped.add(line); }
                }
            }
            String poolGroup = group;
            talkPool.removeIf(line -> poolGroup == null ? line.getConditions().size() > 1
                : !line.getConditions().containsKey(poolGroup));
            eligible = grouped;
        }
        if (eligible.isEmpty())
        {
            return null;
        }
        if (candidate.isRequested()) { return chooseTalkLine(talkPool, eligible); }
        if ("M09".equals(candidate.getScenarioId()) || "F03".equals(candidate.getScenarioId()))
        {
            return chooseOwnerPool(candidate.getScenarioId(), eligible);
        }
        if (isLivingWorld(candidate))
        {
            if (eligible.size() > 1)
            {
                String last = lastLivingWorldLines.get(candidate.getScenarioId());
                eligible.removeIf(line -> line.getId().equals(last));
            }
            return eligible.get(random.nextInt(eligible.size()));
        }
        List<DialogueLine> preferred = new ArrayList<>(eligible);
        preferred.removeIf(line -> recentLines.contains(line.getId()));
        List<DialogueLine> pool = preferred.isEmpty() ? eligible : preferred;
        return pool.get(random.nextInt(pool.size()));
    }

    private DialogueLine chooseOwnerPool(String scenario, List<DialogueLine> eligible)
    {
        boolean chooseOwner = random.nextInt(2) == 0;
        List<DialogueLine> pool = new ArrayList<>();
        for (DialogueLine line : eligible)
        {
            boolean owner = "M09".equals(scenario)
                ? "M09-H01".equals(line.getId()) || "M09-H02".equals(line.getId())
                : "F03-01".equals(line.getId());
            if (owner == chooseOwner) { pool.add(line); }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private DialogueLine chooseTalkLine(List<DialogueLine> pool, List<DialogueLine> eligible)
    {
        // Conditions identify the pool; temporary text cooldowns never create a new bag.
        List<String> ids = new ArrayList<>();
        for (DialogueLine line : pool) { ids.add(line.getId()); }
        Collections.sort(ids);
        TalkBag bag = talkBags.computeIfAbsent(ids, ignored -> new TalkBag());
        if (bag.remaining.isEmpty())
        {
            bag.remaining.addAll(ids);
            Collections.shuffle(bag.remaining, random);
            if (ids.size() > 1 && bag.remaining.get(0).equals(bag.lastPainted))
            {
                Collections.swap(bag.remaining, 0, 1 + random.nextInt(ids.size() - 1));
            }
        }
        for (String id : bag.remaining)
        {
            if (ids.size() > 1 && bag.remaining.size() == ids.size() && id.equals(bag.lastPainted)) { continue; }
            for (DialogueLine line : eligible)
            {
                if (id.equals(line.getId()))
                {
                    selectedTalkBag = bag;
                    return line;
                }
            }
        }
        return null;
    }

    private boolean valid(SceneCandidate candidate)
    {
        return present && candidate.getSessionGeneration() == generation && candidate.getSceneRevision() == revision
            && tick >= candidate.getCreatedTick() && tick < candidate.getExpiresTick()
            && maximumApproachTicks(candidate) > 0
            && catalogue.hasValidOriginalContext(candidate)
            && (candidate.isRequested() || candidateValidator.test(candidate));
    }

    private int maximumApproachTicks(SceneCandidate candidate)
    {
        int contractMaximum = "M02".equals(candidate.getScenarioId()) ? 20
            : "M07".equals(candidate.getScenarioId()) ? 6 : 12;
        String copiedMaximum = candidate.getConditionFacts().get("maximumApproachTicks");
        if (copiedMaximum == null) { return contractMaximum; }
        try
        {
            int value = Integer.parseInt(copiedMaximum);
            return value >= 1 && value <= 20 ? Math.min(value, contractMaximum) : 0;
        }
        catch (NumberFormatException ignored)
        {
            return 0;
        }
    }

    private boolean mayPreempt(SceneCandidate newcomer)
    {
        if (isLivingWorld(active) && !isLivingWorld(newcomer)) { return true; }
        if (newcomer.isEmergency())
        {
            return newcomer.getPriority() > active.getPriority();
        }
        if (newcomer.isRequested())
        {
            return active.getPriority() < 85;
        }
        return (newcomer.isMilestone99() || "M05".equals(newcomer.getScenarioId()))
            && active.getPriority() < newcomer.getPriority() && (packet == null || !packet.isPainted());
    }

    private boolean startAllowed(SceneCandidate candidate)
    {
        return candidate != null && valid(candidate) && showSpeech
            && settlingReady(candidate)
            && (!busy || candidate.isRequested() || candidate.isEmergency())
            && (!isLivingWorld(candidate) || packet != null && packet.isPainted() || livingWorldSpacingReady())
            && (!candidate.isRequested() || lastPaint == null || tick - lastPaint.getPaintedAtTick() >= 2)
            && (selectedLine == null || packet != null && packet.isPainted()
                || lineValidator.test(candidate, selectedLine.getId()))
            && complaintPaintWindow(candidate);
    }

    private boolean settlingReady(SceneCandidate candidate)
    {
        String notBefore = candidate.getConditionFacts().get("settlingNotBeforeTick");
        if (candidate.getPriority() >= 50 || notBefore == null)
        {
            return true;
        }
        try
        {
            return tick >= Long.parseLong(notBefore);
        }
        catch (NumberFormatException ignored)
        {
            return false;
        }
    }

    private boolean complaintPaintWindow(SceneCandidate candidate)
    {
        if (!"M07".equals(candidate.getScenarioId()))
        {
            return true;
        }
        try
        {
            long parentTick = Long.parseLong(candidate.getConditionFacts().get("parentFirstPaintTick"));
            return tick - parentTick >= 8 && tick - parentTick < 20;
        }
        catch (NumberFormatException | NullPointerException ignored)
        {
            return false;
        }
    }

    private void publishPacket()
    {
        if (packet != null || !startAllowed(active) || selectedLine == null)
        {
            return;
        }
        long earliest = active.isEmergency() || active.isRequested() ? clock.getAsLong() : guard.earliestOrdinaryPaint();
        packet = new SpeechPacket(active, selectedLine, reservation, earliest);
        executionIssuedPacketToken = reservation.getToken();
        packet.setStartAllowed(startAllowed(active));
    }

    /** Scenery's single probability and line draw belong to the real inspection arrival. */
    private void chooseLivingWorldArrival()
    {
        if (livingWorldArrivalChosen) { return; }
        livingWorldArrivalChosen = true;
        guard.retireNoPaint(reservation);
        reservation = null;
        boolean speak = random.nextInt(100) < 50;
        DialogueLine line = speak && showSpeech && !busy && livingWorldSpacingReady()
            ? chooseLine(active) : null;
        SpeechGuard.Reservation admission = line == null ? null : guard.reserve(
            catalogue.repeatKey(active.getScenarioId()), line.getNormalizedText(), catalogue.situationFingerprint(active));
        selectedLine = admission == null ? null : line;
        reservation = admission;
        intentionalSilent = selectedLine == null;
    }

    private void commitSilentArrival()
    {
        if (!arrivalCommitted)
        {
            if (isLivingWorld(active)) { guard.retireNoPaint(reservation); }
            else if (!isEmote(active)) { guard.settleSilentArrival(reservation); }
            arrivalCommitted = true;
            if (!"M01".equals(active.getScenarioId())) { holdUntilTick = tick + executionHoldTicks; }
            silentArrivalListener.accept(active);
        }
    }

    private void settlePaint(SpeechPacket issued)
    {
        SpeechPacket.PaintReceipt receipt = issued.getFirstPaint();
        if (receipt != null && active != null && receipt.getAdmissionToken() == executionIssuedPacketToken
            && active.getId() == receipt.getCandidate().getId()
            && active.getSessionGeneration() == receipt.getCandidate().getSessionGeneration()
            && active.getSceneRevision() == receipt.getCandidate().getSceneRevision()
            && active.getCausalToken().equals(receipt.getCandidate().getCausalToken()))
        {
            executionGenuinePainted = true;
        }
        if (receipt == null || receipt.getAdmissionToken() == settledAdmissionToken)
        {
            return;
        }
        boolean settled = guard.settlePaint(issued.getReservation(), receipt.getPaintedAtNanos());
        if (!settled)
        {
            return;
        }
        settledAdmissionToken = receipt.getAdmissionToken();
        if (receipt.getCandidate().getSessionGeneration() != generation)
        {
            return;
        }
        if (receipt.getCandidate().isRequested() && selectedTalkBag != null)
        {
            selectedTalkBag.remaining.remove(receipt.getLineId());
            selectedTalkBag.lastPainted = receipt.getLineId();
        }
        recentLines.remove(receipt.getLineId());
        recentLines.addLast(receipt.getLineId());
        while (recentLines.size() > 5)
        {
            recentLines.removeFirst();
        }
        lastPaint = receipt;
        if (isLivingWorld(receipt.getCandidate()))
        {
            lastLivingWorldPaintTick = receipt.getPaintedAtTick();
            lastLivingWorldLines.put(receipt.getCandidate().getScenarioId(), receipt.getLineId());
        }
        firstPaintListener.accept(receipt);
        trace.accept("speech painted " + receipt.getLineId());
        if (active != null && active.getId() == receipt.getCandidate().getId())
        {
            arrivalCommitted = true;
            if (!"M01".equals(active.getScenarioId()))
            {
                holdUntilTick = receipt.getPaintedAtTick() + executionHoldTicks;
            }
        }
    }

    private void muteExecution()
    {
        if (isEmote(active)) { return; }
        if (packet != null)
        {
            packet.revoke(true);
            settlePaint(packet);
            if (packet.isPainted())
            {
                packet = null;
                return;
            }
        }
        guard.retireNoPaint(reservation);
        reservation = guard.reserve(catalogue.repeatKey(active.getScenarioId()), null, null);
        packet = null;
        selectedLine = null;
        selectedTalkBag = null;
        intentionalSilent = true;
        if (arrived)
        {
            commitSilentArrival();
        }
    }

    private void updateRequestedExecution(SceneCandidate newer)
    {
        SceneCandidate updated = active.updateRequestedStreak(newer);
        boolean turnAway = newer.isIntentionalSilent();
        if (packet != null && packet.isPainted() && !turnAway)
        {
            settlePaint(packet);
            // A new request updates its streak; the already readable sentence keeps its hold.
            active = updated;
            return;
        }
        if (packet != null)
        {
            packet.revoke(true);
            settlePaint(packet);
        }
        guard.retireNoPaint(reservation);
        active = updated;
        executionHoldTicks = updated.getHoldTicks();
        packet = null;
        selectedTalkBag = null;
        selectedLine = turnAway || !showSpeech ? null : chooseLine(active);
        intentionalSilent = selectedLine == null;
        reservation = guard.reserve(null, selectedLine == null ? null : selectedLine.getNormalizedText(), null);
        arrivalCommitted = false;
        continuationCancelled = false;
        if (turnAway)
        {
            // Repeated latched commands repeat only this bounded turn, never another utterance.
            arrived = true;
            commitSilentArrival();
        }
        else if (arrived)
        {
            if (intentionalSilent) { commitSilentArrival(); }
            else { publishPacket(); }
        }
    }

    private boolean canRetainLevel(SceneCandidate candidate)
    {
        if (!("M04".equals(candidate.getScenarioId()) || "M48".equals(candidate.getScenarioId()))
            || candidate.getRebindCount() != 0 || candidate.getSessionGeneration() != generation
            || tick >= candidate.getExpiresTick() || !"true".equals(candidate.getConditionFacts().get("witnessedPresent")))
        {
            return false;
        }
        try
        {
            Map<String, String> facts = candidate.getConditionFacts();
            long occurred = Long.parseLong(facts.get("occurredTick"));
            long witnessEpoch = Long.parseLong(facts.get("witnessPresenceEpoch"));
            long notBefore = Long.parseLong(facts.get("settlingNotBeforeTick"));
            return witnessEpoch <= occurred && occurred <= candidate.getCreatedTick() && notBefore >= witnessEpoch
                && candidate.getExpiresTick() <= occurred + 10;
        }
        catch (NumberFormatException | NullPointerException ignored)
        {
            return false;
        }
    }

    private void pruneRetained()
    {
        pending.removeIf(candidate -> tick >= candidate.getExpiresTick());
        retainedChoices.keySet().removeIf(id -> pending.stream().noneMatch(candidate -> candidate.getId() == id));
        if (pending.isEmpty())
        {
            loadingSuspended = false;
            resumeStable = 0;
        }
    }

    private static final class FrozenChoice
    {
        private final DialogueLine line;
        private final boolean silent;
        private FrozenChoice(DialogueLine line, boolean silent) { this.line = line; this.silent = silent; }
    }

    private static final class TalkBag
    {
        private final List<String> remaining = new ArrayList<>();
        private String lastPainted;
    }

    private static boolean isEmote(SceneCandidate candidate)
    {
        return "E01".equals(candidate.getScenarioId());
    }

    private void cancelActive(boolean hard, boolean retry)
    {
        if (active == null)
        {
            return;
        }
        if (packet != null)
        {
            packet.revoke(hard);
            settlePaint(packet);
        }
        if (!arrivalCommitted)
        {
            guard.retireNoPaint(reservation);
            if (retry && !isEmote(active) && !isLivingWorld(active))
            {
                retryUntil.put(catalogue.repeatKey(active.getScenarioId()), tick + 5);
            }
        }
        boolean expiredWithoutPaint = !hard && !arrivalCommitted && tick >= active.getExpiresTick();
        trace.accept((expiredWithoutPaint ? "scene expired " : "scene cancel ") + active.getScenarioId());
        clearActive();
    }

    private void finishHoldOrRelease()
    {
        if (!holdCompleted)
        {
            holdCompleted = true;
            if (!completionNotified)
            {
                completionNotified = true;
                completionListener.accept(active);
            }
            if (active.isEmergency())
            {
                releaseStartedTick = tick;
                releaseUntilTick = tick + 10;
                trace.accept("scene release " + active.getScenarioId());
                return;
            }
        }
        if (tick < releaseUntilTick) { return; }
        trace.accept("scene complete " + active.getScenarioId());
        clearActive();
    }

    private void clearActive()
    {
        active = null;
        selectedLine = null;
        selectedTalkBag = null;
        reservation = null;
        packet = null;
        intentionalSilent = false;
        arrived = false;
        arrivalCommitted = false;
        continuationCancelled = false;
        originalWatchStartTick = Long.MIN_VALUE;
        holdCompleted = false;
        completionNotified = false;
        cancellationNotified = false;
        executionGenuinePainted = false;
        executionIssuedPacketToken = -1;
        activityTone = null;
        executionHoldTicks = 0;
        livingWorldArrivalChosen = false;
        releaseStartedTick = 0;
        releaseUntilTick = 0;
    }
}
