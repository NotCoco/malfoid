package com.malfoid;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.interaction.TalkState;
import com.malfoid.presence.PresenceController;
import com.malfoid.presence.OtherPlayerPolicy;
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.CreatureAwareness;
import com.malfoid.scenario.CancelledInterestObservation;
import com.malfoid.scenario.ScenePlan;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.NativeOverheadText;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechPacket;
import com.malfoid.world.LivingWorldData;
import com.malfoid.world.LivingWorldPlaces;
import com.malfoid.world.LivingWorldCreatures;
import com.malfoid.world.LivingWorldMoments;
import com.malfoid.world.LivingWorldWaiting;
import com.malfoid.world.SpeechPostureTriggers;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(name = "Malfoid", configName = "malfoid", internalName = "malfoid",
    description = "A local visual companion with reactive personality and dialogue.",
    tags = {"companion", "pet", "dialogue"})
public final class MalfoidPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(MalfoidPlugin.class);
    private final AtomicLong generations = new AtomicLong();
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private MalfoidConfig config;
    @Inject private Gson gson;
    @Inject private OverlayManager overlayManager;
    @Inject private ScheduledExecutorService executor;
    @Inject private Provider<NativeActor> actors;
    @Inject private Provider<ScenarioObserver> observers;
    @Inject private Provider<PresenceController> presences;
    @Inject private Provider<MalfoidMenu> menus;
    @Inject private Provider<ChatNotices> notices;
    private volatile RuntimeSession current;

    @Provides
    MalfoidConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(MalfoidConfig.class);
    }

    @Override
    protected void startUp()
    {
        try
        {
            RuntimeSession session = new RuntimeSession(generations.incrementAndGet(),
                actors.get(), observers.get(), presences.get(), menus.get(), notices.get());
            current = session;
            session.menu.setFailureHandler(failure -> fail(session, "local-menu-click", failure));
            session.menu.setAbsenceHandler(decision -> guarded(session, "local-menu-absence",
                () -> hide(session, decision.getReason().name())));
            session.log = new SessionLog(getPluginDirectory(), executor,
                failure -> fail(session, "log", failure));
            record(session, "ENABLE generation=" + session.sessionGeneration);
            executor.execute(() -> guarded(session, "resource-load", () ->
            {
                DialogueCatalog catalog;
                try
                {
                    session.actor.loadResources();
                    session.worldData = LivingWorldData.load(MalfoidPlugin.class);
                    catalog = DialogueCatalog.load(MalfoidPlugin.class, gson).withLivingWorld(session.worldData);
                }
                catch (Exception failure)
                {
                    throw new IllegalStateException("Bundled dialogue unavailable", failure);
                }
                session.catalog = catalog;
                clientThread.invokeLater(() -> guarded(session, "resource-bind", () ->
                {
                    session.actor.setHeightPercent(configuredHeightPercent());
                    session.worldPlaces = new LivingWorldPlaces(session.worldData, new Random(), () -> ++session.worldSequence);
                    session.worldCreatures = new LivingWorldCreatures(new Random(), () -> ++session.worldSequence);
                    session.worldMoments = new LivingWorldMoments(session.worldData, new Random(), () -> ++session.worldSequence);
                    session.worldWaiting = new LivingWorldWaiting(new Random());
                    session.speechPostures = new SpeechPostureTriggers(new Random());
                    session.actor.setCreatureClassifier((name, level) -> livingWorldClassification(
                        session.worldData.creature(name, level == null ? 0 : level)));
                    LivingWorldData.Group pet = session.worldData.group("CR-PET");
                    session.actor.setLocalFollowerClassification(pet instanceof LivingWorldData.CreatureGroup
                        ? livingWorldClassification((LivingWorldData.CreatureGroup) pet) : null);
                    session.director = new SpeechDirector(catalog, System::nanoTime, new Random(),
                        message -> record(session, message));
                    session.observer.setScenarioEnabled(catalog::isRuntimeScenarioEnabled);
                    session.observer.setLivingWorldData(session.worldData);
                    session.observer.setSceneryStartAllowed(() -> livingWorldBaseEligible(session)
                        && session.director.livingWorldSpacingReady() && !session.observer.isPlayerMoving()
                        && !session.actor.isInspectionSuppressed());
                    session.observer.setInspectionSuppression(session.actor::isInspectionSuppressed);
                    session.observer.setCreatureCurrentValidator(session.actor::isCreatureCurrent);
                    session.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) ->
                        inspectionTarget(session, key, name, minX, minY, maxX, maxY, plane));
                    session.director.resetSession(session.sessionGeneration);
                    session.director.setCandidateValidator(candidate -> candidate.isRequested()
                        || (session.director.isLivingWorld(candidate) ? livingWorldCandidateValid(session, candidate)
                            : session.observer.candidateStillValid(candidate)));
                    session.director.setLineValidator(session.observer::groundOutcomeLineStillValid);
                    session.director.setCompletionListener(candidate ->
                    {
                        if ("M02".equals(candidate.getScenarioId()))
                        {
                            session.observer.reunionCompleted(session.tick);
                        }
                        if ("M08".equals(candidate.getScenarioId()) || "M69".equals(candidate.getScenarioId()))
                        {
                            session.observer.inspectionCompleted(candidate);
                        }
                        record(session, "SCENE_COMPLETED scenario=" + candidate.getScenarioId());
                    });
                    session.director.setCancellationListener(receipt -> inspectionCancelled(session, receipt));
                    session.director.setFirstPaintListener(receipt -> speechPainted(session, receipt));
                    session.director.setSilentArrivalListener(candidate -> silentSceneArrived(session, candidate));
                    session.overlay = new MalfoidSpeechOverlay(session.director, clientThread,
                        failure -> fail(session, "speech-render", failure));
                    SwingUtilities.invokeLater(() -> guarded(session, "overlay-add",
                        () -> overlayManager.add(session.overlay)));
                    session.resourcesLoaded = true;
                    record(session, "RESOURCES_READY dialogueEntries=" + catalog.size());
                }));
            }));
        }
        catch (Throwable failure)
        {
            RuntimeSession session = current;
            if (session != null)
            {
                fail(session, "enable", failure);
            }
            else
            {
                LOG.warn("Malfoid SESSION_OFF enable {}", safeFailure(failure));
            }
        }
    }

    @Override
    protected void shutDown()
    {
        RuntimeSession session = current;
        current = null;
        if (session == null)
        {
            return;
        }
        session.active.set(false);
        record(session, "DISABLE generation=" + session.sessionGeneration);
        removeOverlay(session);
        try
        {
            clientThread.invokeLater(() -> dispose(session));
        }
        catch (Throwable failure)
        {
            fail(session, "disable-queue", failure);
        }
        if (session.log != null)
        {
            try
            {
                session.log.close(executor);
            }
            catch (Throwable failure)
            {
                fail(session, "log-close", failure);
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        RuntimeSession session = current;
        guarded(session, "game-tick", () ->
        {
            long tick = ++session.tick;
            long generation = session.sessionGeneration;
            session.closingBatch = true;
            beginAdditionalInterval(session);
            clientThread.invokeAtTickEnd(() -> guarded(session, "tick-commit", () ->
            {
                if (generation != session.sessionGeneration || tick != session.tick)
                {
                    return;
                }
                session.closingBatch = false;
                commit(session);
            }));
        });
    }

    private void commit(RuntimeSession session)
    {
        checkSessionIdentity(session);
        PresenceController.Decision decision = session.presence.commit(session.tick,
            config.showMalfoid(), session.actor.isReady(), session.failed.get(), session.actor.isVisible());
        syncScene(session);
        refreshLivePresentation(session);
        if (!decision.isPlacementAllowed())
        {
            hide(session, decision.getReason().name());
        }
        if (session.resourcesLoaded)
        {
            applySceneMotion(session);
            updateCombatTarget(session);
            session.actor.setRecoveryAllowed(session.director == null || session.director.isIdle());
            session.actor.setLivingWorldCreatureAllowed(worldCreatureDecisionsAllowed(session));
            long previousPlacement = session.actor.isVisible() ? session.actor.getPlacementGeneration() : -1;
            session.actor.onGameTick(decision.isPlacementAllowed(), session.tick);
            if (session.actor.consumeCreatureScenePreemption() && session.director != null)
            {
                SceneCandidate interrupted = session.director.getActiveCandidate();
                if (interrupted != null && !interrupted.isEmergency())
                {
                    session.director.invalidate(false);
                    session.observer.softRevokeParent();
                    session.cancelledSceneId = session.creaturePreemptedSceneId = interrupted.getId();
                    record(session, "CREATURE_PREEMPTED_SCENE scenario=" + interrupted.getScenarioId());
                }
            }
            refreshInspectionReturn(session, true);
            if (previousPlacement >= 0 && session.actor.isVisible()
                && previousPlacement != session.actor.getPlacementGeneration())
            {
                invalidateSpeech(session, true);
                session.observer.softRevokeParent();
                session.directorSceneId = -1;
                session.placementGeneration = session.actor.getPlacementGeneration();
                record(session, "RECOVERY_RELOCATED count=" + session.actor.getRecoveryCount());
            }
            if (session.actor.isVisible() && decision.isPlacementAllowed())
            {
                decision = session.presence.placementSucceeded(session.tick);
                logPlacement(session);
            }
        }
        logPresence(session, decision);
        logRecoveryObservation(session, decision);
        session.notices.onPresence(decision);
        session.notices.flush();
        session.observer.setPresence(decision.isPresent(), !config.showMalfoid(),
            decision.getState() == com.malfoid.presence.PresenceState.ABSENT_AUTO
                && decision.getReason() != com.malfoid.presence.PresenceReason.PREPARING);
        WorldPoint companion = companionWorldPoint(session);
        if (session.director != null)
        {
            validateActive(session);
            session.director.tick(session.tick, session.sessionGeneration, session.sceneRevision,
                decision.isPresent(), config.showDialogue(), session.presence.isBusy());
            observePresentation(session);
        }
        session.observer.setMotionObservations(session.actor.getMotionObservation(),
            session.actor.getLatestMotionCompletion(), session.actor.getPlacementGeneration());
        session.observer.setFollowing(session.actor.isFollowing());
        session.observer.setCreatureObservation(session.actor.getCreatureSample(), session.actor.getCreatureAwareness());
        SceneCandidate executing = session.director == null ? null : session.director.getActiveCandidate();
        session.observer.setEmergencySceneActive(executing != null && executing.isEmergency());
        List<SceneCandidate> scenery = new ArrayList<>();
        for (SceneCandidate candidate : session.observer.closeGameTick(session.tick,
            session.sessionGeneration, session.sceneRevision, decision.isPresent(),
            decision.getPresentSinceTick(), companion == null ? null : companion.getX(),
            companion == null ? null : companion.getY(), session.director == null
                || session.director.getActiveCandidate() == null))
        {
            if (session.director != null)
            {
                if (session.director.isLivingWorldScenery(candidate)) { scenery.add(candidate); }
                else { offerScene(session, candidate); }
            }
        }
        observeLivingWorld(session, decision);
        for (SceneCandidate candidate : scenery)
        {
            if (session.director != null && livingWorldBaseEligible(session)) { offerScene(session, candidate); }
        }
        if (session.director != null)
        {
            session.director.resumeAfterLoading(session.tick, session.sessionGeneration,
                session.sceneRevision, decision.isPresent());
            session.director.refreshEmergencyWatch(session.observer.getLatestHeavyHitTick());
            updateCombatTarget(session);
            validateActive(session);
            applySceneMotion(session);
            acknowledgeArrival(session);
            updatePostures(session, true);
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        RuntimeSession session = current;
        guarded(session, "client-cycle", () ->
        {
            PresenceController.Decision decision = session.presence.inspect(config.showMalfoid(),
                session.actor.isReady(), false, session.actor.isVisible());
            syncScene(session);
            ModalOcclusionSnapshot modal = refreshLivePresentation(session);
            if (!decision.isPlacementAllowed())
            {
                hide(session, decision.getReason().name());
            }
            if (session.resourcesLoaded && decision.isPreparationAllowed())
            {
                boolean wasReady = session.actor.isReady();
                session.actor.onClientTick();
                if (!wasReady && session.actor.isReady())
                {
                    record(session, "MODEL_BANK_READY " + session.actor.getPreparationReport());
                }
            }
            if (session.actor.isVisible())
            {
                long placement = session.actor.getPlacementSnapshot().getPlacementGeneration();
                if (session.placementGeneration >= 0 && placement != session.placementGeneration)
                {
                    invalidateSpeech(session, true);
                    record(session, "PLACEMENT_REPLACED generation=" + placement);
                }
                session.placementGeneration = placement;
                logPlacement(session);
                if (session.seenClips.add(session.actor.getLastClip()))
                {
                    record(session, "POSE_FIRST_USE clip=" + session.actor.getLastClip());
                }
            }
            session.observer.setMotionObservations(session.actor.getMotionObservation(),
                session.actor.getLatestMotionCompletion(), session.actor.getPlacementGeneration());
            session.observer.refreshOwnFollowerLease();
            session.observer.refreshPouchLease();
            session.observer.refreshPuzzleLease();
            refreshInspectionReturn(session, false);
            validateActive(session);
            updatePostures(session, false);
            publishProjection(session, modal);
            observePresentation(session);
        });
    }

    private void syncScene(RuntimeSession session)
    {
        long scene = session.presence.getSceneGeneration();
        if (scene != session.presenceSceneGeneration)
        {
            session.presenceSceneGeneration = scene;
            session.sceneRevision++;
            session.actor.invalidateCreatureScope();
            hide(session, "SCENE_CHANGED");
            record(session, "SCENE_INVALIDATED revision=" + session.sceneRevision);
        }
    }

    private void checkSessionIdentity(RuntimeSession session)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        int world = client.getWorld();
        long account = client.getAccountHash();
        if (session.world != 0 && session.world != world
            || session.accountHash != 0 && account != 0 && session.accountHash != account)
        {
            resetSession(session, "IDENTITY_CHANGED");
        }
        session.world = world;
        if (account != 0)
        {
            session.accountHash = account;
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        RuntimeSession session = current;
        if (session != null && session.active.get() && session.failed.get())
        {
            if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING
                || event.getGameState() == GameState.CONNECTION_LOST)
            {
                session.retryAtLogin = true;
            }
            if (event.getGameState() == GameState.LOGGED_IN && session.retryAtLogin)
            {
                SwingUtilities.invokeLater(() ->
                {
                    if (current == session && session.active.get() && session.failed.get())
                    {
                        shutDown();
                        startUp();
                    }
                });
            }
            return;
        }
        guarded(session, "game-state", () ->
        {
            if (event.getGameState() != GameState.LOGGED_IN)
            {
                session.actor.invalidateCreatureScope();
                if (event.getGameState() == GameState.LOADING && session.director != null)
                {
                    session.director.suspendForOrdinaryLoading(session.tick, session.sessionGeneration);
                }
                hide(session, event.getGameState().name());
                session.presence.invalidateScene();
                session.sceneRevision++;
                if (event.getGameState() != GameState.LOADING)
                {
                    resetSession(session, event.getGameState().name());
                }
            }
            session.observer.onGameStateChanged(event);
            record(session, "LIFECYCLE " + event.getGameState());
        });
    }

    private void resetSession(RuntimeSession session, String reason)
    {
        session.sessionGeneration = generations.incrementAndGet();
        session.sceneRevision++;
        session.world = 0;
        session.accountHash = 0;
        session.actor.resetForLogin();
        clearInspectionReturn(session);
        session.observer.resetSession();
        if (session.worldPlaces != null) { session.worldPlaces.resetSession(); }
        if (session.worldCreatures != null) { session.worldCreatures.resetSession(); }
        if (session.worldMoments != null) { session.worldMoments.resetSession(); }
        if (session.worldWaiting != null) { session.worldWaiting.resetSession(); }
        if (session.speechPostures != null) { session.speechPostures.resetSession(); }
        session.speechPostureActive = false;
        session.sceneTranslation = null;
        session.presence.reset();
        session.notices.resetSession();
        session.talkState.reset();
        session.seenClips.clear();
        session.lastPaintToken = -1;
        session.directorSceneId = -1;
        session.closingBatch = false;
        if (session.director != null)
        {
            session.director.resetSession(session.sessionGeneration);
        }
        record(session, "SESSION_RESET reason=" + reason + " generation=" + session.sessionGeneration);
    }

    @Subscribe
    public void onActorDeath(ActorDeath event)
    {
        RuntimeSession session = current;
        guarded(session, "actor-death", () ->
        {
            boolean localDeath = session.observer.isLocalActor(event.getActor());
            if (event.getActor() instanceof NPC) { session.actor.retireCreature((NPC) event.getActor()); }
            session.observer.onActorDeath(event);
            if (localDeath)
            {
                if (session.worldMoments != null) { session.worldMoments.onLocalDeath(); }
                session.presence.onLocalDeath(session.closingBatch ? session.tick : session.tick + 1);
                hide(session, "LOCAL_DEATH");
            }
            else
            {
                validateActive(session);
            }
        });
    }

    @Subscribe public void onHitsplatApplied(HitsplatApplied event)
    {
        RuntimeSession s = current;
        guarded(s, "hitsplat", () ->
        {
            if (s.presence.onHitsplatApplied(event, s.closingBatch ? s.tick : s.tick + 1))
            {
                playerCombatStarted(s);
            }
            s.observer.onHitsplatApplied(event);
            validateActive(s);
        });
    }
    @Subscribe public void onMenuOptionClicked(MenuOptionClicked event)
    {
        RuntimeSession s = current;
        guarded(s, "player-attack", () ->
        {
            if (s.presence.onMenuOptionClicked(event, s.closingBatch ? s.tick : s.tick + 1))
            {
                playerCombatStarted(s);
            }
        });
    }

    /** Native callback revocation precedes all observation and any later paint acknowledgement. */
    private void playerCombatStarted(RuntimeSession session)
    {
        if (session.worldMoments != null) { session.worldMoments.onPlayerCombatAbsence(); }
        PresenceController.Decision decision = session.presence.inspect(config.showMalfoid(),
            session.actor.isReady(), session.failed.get(), session.actor.isVisible());
        hide(session, "PLAYER_COMBAT");
        logPresence(session, decision);
        session.notices.onPresence(decision);
        session.notices.flush();
    }
    @Subscribe public void onStatChanged(StatChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "stat", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onStatChanged(event);
            validateActive(s);
        });
    }
    @Subscribe public void onNpcLootReceived(NpcLootReceived event)
    {
        RuntimeSession s = current;
        guarded(s, "loot", () -> { s.observer.onNpcLootReceived(event); validateActive(s); });
    }
    @Subscribe public void onItemContainerChanged(ItemContainerChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "container", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onItemContainerChanged(event);
            refreshLivePresentation(s);
            validateActive(s);
        });
    }
    @Subscribe public void onNpcSpawned(NpcSpawned event)
    {
        RuntimeSession s = current;
        guarded(s, "npc-spawn", () -> { s.actor.reviveCreature(event.getNpc()); s.observer.onNpcSpawned(event); validateActive(s); });
    }
    @Subscribe public void onNpcChanged(NpcChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "npc-transform", () -> { s.actor.invalidateCreature(event.getNpc()); s.observer.onNpcChanged(event); validateActive(s); });
    }
    @Subscribe public void onInteractingChanged(InteractingChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "interaction", () ->
        {
            s.presence.onInteractingChanged(event, s.closingBatch ? s.tick : s.tick + 1);
            s.observer.onInteractingChanged(event);
            validateActive(s);
        });
    }
    @Subscribe public void onNpcDespawned(NpcDespawned event)
    {
        RuntimeSession s = current;
        guarded(s, "npc-despawn", () -> { s.actor.retireCreature(event.getNpc()); s.observer.onNpcDespawned(event); validateActive(s); });
    }
    @Subscribe public void onGameObjectSpawned(GameObjectSpawned event)
    {
        RuntimeSession s = current;
        guarded(s, "object-spawn", () -> { s.observer.onGameObjectSpawned(event); validateActive(s); });
    }
    @Subscribe public void onGameObjectDespawned(GameObjectDespawned event)
    {
        RuntimeSession s = current;
        guarded(s, "object-despawn", () -> { s.observer.onGameObjectDespawned(event); validateActive(s); });
    }
    @Subscribe public void onItemSpawned(ItemSpawned event)
    {
        RuntimeSession s = current;
        guarded(s, "item-spawn", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onItemSpawned(event);
            refreshLivePresentation(s);
            validateActive(s);
        });
    }
    @Subscribe public void onItemDespawned(ItemDespawned event)
    {
        RuntimeSession s = current;
        guarded(s, "item-despawn", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onItemDespawned(event);
            refreshLivePresentation(s);
            validateActive(s);
        });
    }
    @Subscribe public void onItemQuantityChanged(ItemQuantityChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "item-quantity", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onItemQuantityChanged(event);
            refreshLivePresentation(s);
            validateActive(s);
        });
    }
    @Subscribe public void onChatMessage(ChatMessage event)
    {
        RuntimeSession s = current;
        guarded(s, "chat-fact", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onChatMessage(event);
            validateActive(s);
            s.notices.onChatMessage(event);
        });
    }
    @Subscribe public void onScriptPreFired(ScriptPreFired event)
    {
        if (event == null || event.getScriptId() != 688 && event.getScriptId() != 690) { return; }
        RuntimeSession s = current;
        guarded(s, "puzzle-script-pre", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onPuzzleScriptPre(event);
            validateActive(s);
        });
    }
    @Subscribe public void onScriptPostFired(ScriptPostFired event)
    {
        if (event == null || event.getScriptId() != 688 && event.getScriptId() != 690) { return; }
        RuntimeSession s = current;
        guarded(s, "puzzle-script-post", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onPuzzleScriptPost(event);
            validateActive(s);
        });
    }
    @Subscribe public void onWidgetClosed(WidgetClosed event)
    {
        if (event == null || event.getGroupId() != 306) { return; }
        RuntimeSession s = current;
        guarded(s, "puzzle-widget-close", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onPuzzleWidgetClosed(event);
            validateActive(s);
        });
    }
    @Subscribe public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event == null || event.getGroupId() != 306) { return; }
        RuntimeSession s = current;
        guarded(s, "puzzle-widget-load", () ->
        {
            beginAdditionalInterval(s);
            s.observer.onPuzzleWidgetLoaded(event);
            validateActive(s);
        });
    }
    @Subscribe public void onVarbitChanged(VarbitChanged event)
    {
        RuntimeSession s = current;
        guarded(s, "presence-varbit", () ->
        {
            PresenceController.Decision decision = s.presence.inspect(config.showMalfoid(),
                s.actor.isReady(), false, s.actor.isVisible());
            if (!decision.isPlacementAllowed())
            {
                hide(s, decision.getReason().name());
            }
            beginAdditionalInterval(s);
            s.observer.onVarbitChanged(event);
            validateActive(s);
        });
    }

    private void beginAdditionalInterval(RuntimeSession session)
    {
        session.observer.beginAdditionalInterval(session.closingBatch ? session.tick : session.tick + 1,
            session.sessionGeneration, session.sceneRevision, session.presence.getPresentSinceTick(),
            session.presence.isPresent() && config.showMalfoid() && session.actor.isVisible());
    }
    @Subscribe public void onConfigChanged(ConfigChanged event)
    {
        if (!"malfoid".equals(event.getGroup()))
        {
            return;
        }
        RuntimeSession s = current;
        if (s == null)
        {
            return;
        }
        long generation = s.sessionGeneration;
        boolean eventOff = "false".equals(event.getNewValue());
        String key = event.getKey();
        guarded(s, "config-queue", () -> clientThread.invokeLater(() -> guarded(s, "config", () ->
        {
            if (generation != s.sessionGeneration)
            {
                return;
            }
            if ("heightPercent".equals(key))
            {
                int percent = configuredHeightPercent();
                if (percent != s.actor.getHeightPercent())
                {
                    hide(s, "HEIGHT_REBUILD");
                    s.actor.setHeightPercent(percent);
                    s.presence.inspect(config.showMalfoid(), false, false, false);
                    record(s, "HEIGHT_REBUILD heightPercent=" + percent);
                }
            }
            if ("showMalfoid".equals(key) && eventOff)
            {
                s.presence.inspect(false, s.actor.isReady(), false, s.actor.isVisible());
                hide(s, "USER");
            }
            if ("showDialogue".equals(key) && eventOff)
            {
                if (s.director != null)
                {
                    s.director.tick(s.tick, s.sessionGeneration, s.sceneRevision,
                        s.presence.isPresent(), false, s.presence.isBusy());
                }
                if (s.overlay != null)
                {
                    s.overlay.clear();
                }
            }
        })));
    }

    @Subscribe public void onMenuOpened(MenuOpened event)
    {
        RuntimeSession s = current;
        guarded(s, "local-menu", () ->
        {
            if (!config.showMalfoid())
            {
                hide(s, "USER");
                return;
            }
            s.menu.onMenuOpened(event, s.actor, s.presence,
            s.sessionGeneration, () -> current == s && s.active.get() && !s.failed.get()
                ? s.sessionGeneration : Long.MIN_VALUE,
            () -> guarded(s, "talk", () -> talk(s)),
            () -> guarded(s, "dismiss", () ->
            {
                configManager.setConfiguration("malfoid", "showMalfoid", false);
                hide(s, "DISMISS");
                s.notices.onDismiss();
            }),
            () -> guarded(s, "examine", () ->
            {
                if (config.showMalfoid() && s.catalog != null)
                {
                    s.notices.examine(s.catalog.getApprovedExamineTexts().toArray(new String[0]));
                }
            }));
        });
    }

    private void talk(RuntimeSession session)
    {
        if (session.director == null || !config.showMalfoid() || !session.presence.permitsLocalControls())
        {
            return;
        }
        long tick = session.tick;
        int talkStreak = session.talkState.accept(tick);
        if (talkStreak == 0)
        {
            return;
        }
        if (session.speechPostures != null) { session.speechPostures.onTalkAccepted(tick); }
        Map<String, String> facts = new HashMap<>();
        facts.put("talkStreak", Integer.toString(talkStreak));
        facts.put("combatConfirmed", Boolean.toString(session.observer.isCombatConfirmed()));
        int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int real = client.getRealSkillLevel(Skill.HITPOINTS);
        facts.put("playerHpAtMostPercent", hp > 0 && real > 0 && 100L * hp <= 25L * real ? "25" : "100");
        facts.put("localNight", Boolean.toString(java.time.LocalTime.now().getHour() < 5));
        offerScene(session, new SceneCandidate(++session.commandSequence, "U01", 85, tick, tick + 8,
            session.sessionGeneration, session.sceneRevision, "talk:" + session.commandSequence,
            facts, talkStreak >= 4, talkStreak >= 4 ? 5 : 4));
    }

    private void validateActive(RuntimeSession session)
    {
        if (session.director == null)
        {
            return;
        }
        SceneCandidate candidate = session.director.getActiveCandidate();
        boolean passiveWorld = isPassiveLivingWorld(session, candidate);
        session.observer.setActiveSceneMotion(candidate == null || passiveWorld ? -1 : candidate.getId(),
            candidate == null || passiveWorld ? null : session.actor.getMotionHoldReceipt(candidate.getId()),
            candidate != null && !passiveWorld && session.director.isCommitted());
        com.malfoid.speech.SpeechPacket.PaintReceipt lastPaint = session.director.getLastPaint();
        if (lastPaint != null && session.tick >= lastPaint.getCandidate().getExpiresTick())
        {
            session.observer.softRevokeParent();
        }
        if (candidate != null && session.tick >= candidate.getExpiresTick())
        {
            session.observer.softRevokeParent();
        }
        boolean inspectionInvalid = candidate != null && candidate.getId() == session.directorSceneId
            && ("M08".equals(candidate.getScenarioId()) || "M69".equals(candidate.getScenarioId())
                || session.director.isLivingWorldScenery(candidate))
            && !session.actor.isInspectionDestinationValid();
        if (candidate != null && !candidate.isRequested()
            && (inspectionInvalid || (session.director.isLivingWorld(candidate)
                ? !livingWorldCandidateValid(session, candidate) : !session.observer.candidateContinuationValid(candidate))))
        {
            SpeechCancellationReceipt.Reason reason = session.observer.cancellationReason(candidate);
            captureInspectionLoss(session, candidate, reason);
            session.observer.softRevokeParent();
            session.director.invalidate(false, reason);
            if (session.inspectionReturn == null || session.inspectionReturn.cancellation == null)
            {
                clearInspectionReturn(session);
                if (!preservesNativeMotion(session, candidate) && session.creaturePreemptedSceneId != candidate.getId()) { session.actor.holdPosition(); }
            }
        }
    }

    private void captureInspectionLoss(RuntimeSession session, SceneCandidate candidate,
        SpeechCancellationReceipt.Reason reason)
    {
        clearInspectionReturn(session);
        if (reason != SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST
            || !"M08".equals(candidate.getScenarioId()) || session.director.isCommitted()
            || session.actor.hasArrived() || session.director.hasArrived()
            || candidate.getId() != session.directorSceneId || !session.presence.isPresent()
            || session.tick >= candidate.getExpiresTick()
            || session.catalog == null || !session.catalog.isRuntimeScenarioEnabled("M62"))
        {
            return;
        }
        MotionObservation path = session.actor.getMotionObservation();
        CancelledInterestObservation.LossReason loss = session.observer.inspectionLossReason(candidate);
        if (loss == null || path == null || !path.known || path.ordinary
            || path.purpose != MotionObservation.Purpose.OTHER || path.status != MotionObservation.Status.MOVING
            || path.sourceCandidateId != candidate.getId() || path.completedTick >= 0)
        {
            return;
        }
        CancelledInterestObservation.Builder copy = new CancelledInterestObservation.Builder();
        copy.inspectionCandidate = candidate;
        copy.inspectionPath = path;
        copy.lossReason = loss;
        copy.targetKey = candidate.getConditionFacts().get("targetKey");
        copy.historicalName = candidate.getConditionFacts().get("objectName");
        copy.presentSinceTick = session.presence.getPresentSinceTick();
        copy.placementGeneration = path.placementGeneration;
        copy.worldViewId = path.worldViewId;
        copy.plane = path.plane;
        session.inspectionReturn = copy.build();
    }

    private void inspectionCancelled(RuntimeSession session, SpeechCancellationReceipt receipt)
    {
        session.observer.onSpeechCancellation(receipt);
        CancelledInterestObservation interest = session.inspectionReturn;
        if (interest == null || interest.cancellation != null
            || receipt.getCandidateId() != interest.inspectionCandidate.getId()
            || receipt.getReason() != SpeechCancellationReceipt.Reason.INSPECTION_TARGET_LOST
            || !receipt.isPrePaintAttempt() || receipt.isGenuinePainted()
            || !session.actor.requestReturnFromInspection(receipt.getCandidateId(), interest.inspectionPath.requestId))
        {
            return;
        }
        CancelledInterestObservation.Builder copy = copyInspectionReturn(interest);
        copy.cancellation = receipt;
        session.inspectionReturn = copy.build();
        session.observer.setCancelledInterest(session.inspectionReturn);
        session.directorSceneId = -1;
        session.plan = null;
        record(session, "INSPECTION_RETURN_PENDING source=" + receipt.getCandidateId()
            + " reason=" + interest.lossReason);
    }

    private void refreshInspectionReturn(RuntimeSession session, boolean afterDecision)
    {
        CancelledInterestObservation interest = session.inspectionReturn;
        if (interest == null || interest.cancellation == null) { return; }
        SceneCandidate active = session.director == null ? null : session.director.getActiveCandidate();
        session.observer.setActiveSceneMotion(active == null ? -1 : active.getId(),
            active == null ? null : session.actor.getMotionHoldReceipt(active.getId()),
            active != null && session.director.isCommitted());
        boolean ownReturnScene = active != null && "M62".equals(active.getScenarioId())
            && interest.returnRequestId >= 0
            && session.observer.getMotionRequestId(active) == interest.returnRequestId;
        if (!session.presence.isPresent() || !session.actor.isVisible()
            || interest.inspectionCandidate.getSessionGeneration() != session.sessionGeneration
            || interest.inspectionCandidate.getSceneRevision() != session.sceneRevision
            || interest.presentSinceTick != session.presence.getPresentSinceTick()
            || interest.placementGeneration != session.actor.getPlacementGeneration()
            || session.tick - interest.lossTick > 16 && !ownReturnScene
            || active != null && !"M62".equals(active.getScenarioId()))
        {
            clearInspectionReturn(session);
            return;
        }
        long request = session.actor.getInspectionReturnRequestId(interest.inspectionCandidate.getId());
        if (request < 0)
        {
            if (afterDecision || interest.returnRequestId >= 0) { clearInspectionReturn(session); }
            return;
        }
        if (interest.returnRequestId >= 0 && request != interest.returnRequestId)
        {
            clearInspectionReturn(session);
            return;
        }
        if (interest.returnRequestId < 0)
        {
            CancelledInterestObservation.Builder copy = copyInspectionReturn(interest);
            copy.returnRequestId = request;
            session.inspectionReturn = copy.build();
            session.observer.setCancelledInterest(session.inspectionReturn);
            record(session, "INSPECTION_RETURN_ACCEPTED request=" + request);
        }
    }

    private static CancelledInterestObservation.Builder copyInspectionReturn(CancelledInterestObservation interest)
    {
        CancelledInterestObservation.Builder copy = new CancelledInterestObservation.Builder();
        copy.inspectionCandidate = interest.inspectionCandidate;
        copy.inspectionPath = interest.inspectionPath;
        copy.cancellation = interest.cancellation;
        copy.lossReason = interest.lossReason;
        copy.targetKey = interest.targetKey;
        copy.historicalName = interest.historicalName;
        copy.returnRequestId = interest.returnRequestId;
        copy.presentSinceTick = interest.presentSinceTick;
        copy.placementGeneration = interest.placementGeneration;
        copy.worldViewId = interest.worldViewId;
        copy.plane = interest.plane;
        copy.inspectionArrived = interest.inspectionArrived;
        copy.known = interest.known;
        return copy;
    }

    private static void clearInspectionReturn(RuntimeSession session)
    {
        session.inspectionReturn = null;
        session.observer.setCancelledInterest(null);
    }

    private static CreatureAwareness.Classification livingWorldClassification(LivingWorldData.CreatureGroup group)
    {
        if (group == null) { return null; }
        CreatureAwareness.Behaviour behaviour;
        int minimum = 1, maximum = 2;
        switch (group.behaviour)
        {
            case AFRAID: behaviour = CreatureAwareness.Behaviour.AFRAID; break;
            case AFRAID_AT_DISTANCE:
                behaviour = CreatureAwareness.Behaviour.AFRAID; minimum = 2; maximum = 3; break;
            case WARY: behaviour = CreatureAwareness.Behaviour.WARY; break;
            case DISGUSTED: behaviour = CreatureAwareness.Behaviour.DISGUST; break;
            case FOND: behaviour = CreatureAwareness.Behaviour.FOND; break;
            case LOOKS_THEM_OVER: behaviour = CreatureAwareness.Behaviour.WIZARD; break;
            case SPEAKS_ONLY: behaviour = CreatureAwareness.Behaviour.NONE; break;
            default: throw new IllegalArgumentException("Unknown living-world creature behaviour");
        }
        return new CreatureAwareness.Classification(group.id, group.ordinal, behaviour, minimum, maximum);
    }

    private boolean livingWorldConditionsHold(RuntimeSession session)
    {
        Player player = client.getLocalPlayer();
        return session.worldData != null && session.presence.isPresent()
            && session.tick - session.presence.getPresentSinceTick() >= 10
            && player != null && !player.isDead() && client.getGameState() == GameState.LOGGED_IN
            && !session.presence.isBusy() && !session.observer.isCombatConfirmed();
    }

    private boolean livingWorldBaseEligible(RuntimeSession session)
    {
        return livingWorldConditionsHold(session) && session.director != null && session.director.isIdle();
    }

    private boolean worldCreatureDecisionsAllowed(RuntimeSession session)
    {
        SceneCandidate active = session.director == null ? null : session.director.getActiveCandidate();
        return livingWorldConditionsHold(session) && session.director != null && (session.director.isIdle()
            || session.director.pendingCount() == 0 && active != null && preservesNativeMotion(session, active));
    }

    private void offerScene(RuntimeSession session, SceneCandidate candidate)
    {
        if (!preservesNativeMotion(session, candidate))
        {
            session.actor.setLivingWorldCreatureAllowed(false);
        }
        session.director.offer(candidate);
        session.actor.setLivingWorldCreatureAllowed(worldCreatureDecisionsAllowed(session));
    }

    private static boolean isPassiveLivingWorld(RuntimeSession session, SceneCandidate candidate)
    {
        return session.director != null && session.director.isLivingWorld(candidate)
            && !session.director.isLivingWorldScenery(candidate);
    }

    private boolean livingWorldCandidateValid(RuntimeSession session, SceneCandidate candidate)
    {
        if (!livingWorldConditionsHold(session) || candidate.getSessionGeneration() != session.sessionGeneration
            || candidate.getSceneRevision() != session.sceneRevision || session.catalog == null
            || !session.catalog.livingWorldFamily(candidate.getScenarioId()).equals(
                candidate.getConditionFacts().get("livingWorld"))) { return false; }
        if (session.director.isLivingWorldScenery(candidate)) { return session.observer.candidateContinuationValid(candidate); }
        if (session.director.getPacket() != null && session.director.getPacket().isPainted()) { return true; }
        if ("PLACE".equals(candidate.getConditionFacts().get("livingWorld")))
        {
            Player player = client.getLocalPlayer(); WorldPoint point = player == null ? null : player.getWorldLocation();
            LivingWorldData.PlaceGroup place = point == null ? null : session.worldData.place(point.getRegionID());
            return place != null && place.id.equals(candidate.getScenarioId())
                && place.id.equals(candidate.getConditionFacts().get("placeEntry"));
        }
        if ("MOMENT".equals(candidate.getConditionFacts().get("livingWorld")))
        {
            return session.worldMoments != null && session.worldMoments.matches(candidate, session.tick);
        }
        return session.worldCreatures != null && session.worldCreatures.matches(candidate)
            && session.actor.isCreatureCurrent(candidate.getConditionFacts().get("creatureKey"), candidate.getScenarioId());
    }

    private void observeLivingWorld(RuntimeSession session, PresenceController.Decision decision)
    {
        if (session.worldPlaces == null || session.worldCreatures == null || session.director == null) { return; }
        Player player = client.getLocalPlayer(); WorldPoint point = player == null ? null : player.getWorldLocation();
        boolean known = point != null && player != null && !player.isDead()
            && client.getGameState() == GameState.LOGGED_IN;
        boolean reactionAllowed = livingWorldBaseEligible(session);
        boolean speechAllowed = reactionAllowed && session.director.livingWorldSpacingReady();
        SceneCandidate place = session.worldPlaces.observe(session.tick, session.sessionGeneration, session.sceneRevision,
            point == null ? -1 : point.getRegionID(), known, decision.isPresent(), speechAllowed);
        CreatureAwareness.Sample sample = session.actor.getCreatureSample();
        CreatureAwareness awareness = session.actor.getCreatureAwareness();
        WorldPoint companion = companionWorldPoint(session);
        List<LivingWorldCreatures.Sighting> sightings = new ArrayList<>();
        if (sample != null && sample.known && sample.tick == session.tick && companion != null)
        {
            for (CreatureAwareness.Creature creature : sample.creatures)
            {
                LivingWorldData.Group group = session.worldData.group(creature.classification.groupId);
                if (group instanceof LivingWorldData.CreatureGroup)
                {
                    sightings.add(new LivingWorldCreatures.Sighting(creature.key, (LivingWorldData.CreatureGroup) group,
                        creature.worldX, creature.worldY, creature.plane, creature.distance(companion.getX(), companion.getY())));
                }
            }
        }
        CreatureAwareness.Creature fondArrival = session.actor.getCreatureStrollArrival();
        SceneCandidate creatureLine = session.worldCreatures.observe(session.tick, session.sessionGeneration,
            session.sceneRevision, known && decision.isPresent() && sample != null && sample.known && sample.tick == session.tick,
            reactionAllowed && !session.actor.isMoving() && !awareness.isKeepingAway(), speechAllowed && place == null,
            sightings, awareness.getGroupId(), awareness.getBeginTick(), fondArrival == null ? null : fondArrival.key,
            session.actor.getCreatureStrollArrivalTick());
        LivingWorldCreatures.Sighting wizard = session.worldCreatures.getWizardLook();
        if (wizard != null && sample != null)
        {
            for (CreatureAwareness.Creature creature : sample.creatures)
            {
                if (wizard.key.equals(creature.key)) { session.actor.faceCreatureFor(creature, 4); break; }
            }
        }
        if (place != null) { offerScene(session, place); }
        else if (creatureLine != null) { offerScene(session, creatureLine); }
        if (session.worldMoments != null)
        {
            boolean sampled = known && decision.isPresent() && session.tick - decision.getPresentSinceTick() >= 10;
            int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
            boolean alive = player != null && !player.isDead() && hp > 0;
            if (player != null && (player.isDead() || hp <= 0)) { session.worldMoments.onLocalDeath(); }
            SceneCandidate moment = session.worldMoments.observe(session.tick, session.sessionGeneration, session.sceneRevision,
                decision.isPresent(), decision.getPresentSinceTick(),
                decision.getState() == com.malfoid.presence.PresenceState.ABSENT_AUTO
                    && decision.getReason() == com.malfoid.presence.PresenceReason.PLAYER_COMBAT,
                alive, sampled, sampled && client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1,
                hp, client.getRealSkillLevel(Skill.HITPOINTS),
                livingWorldBaseEligible(session) && session.director.livingWorldSpacingReady());
            if (moment != null) { offerScene(session, moment); }
            // H.7: stopped short of a beast he is standing by, she may say so from where she is.
            SceneCandidate keptBack = session.worldMoments.observeKeptBack(session.tick, session.sessionGeneration,
                session.sceneRevision, session.actor.isKeptBackByBeast(),
                moment == null && livingWorldBaseEligible(session) && session.director.livingWorldSpacingReady());
            if (keptBack != null) { offerScene(session, keptBack); }
        }
    }

    private void speechPainted(RuntimeSession session, SpeechPacket.PaintReceipt receipt)
    {
        session.observer.executionPainted(receipt);
        if (session.worldPlaces != null) { session.worldPlaces.onPaint(receipt); }
        if (session.worldCreatures != null) { session.worldCreatures.onPaint(receipt); }
        if (session.worldMoments != null) { session.worldMoments.onPaint(receipt); }
        if (session.speechPostures != null && session.director != null
            && !"KEPT_WAITING".equals(receipt.getCandidate().getConditionFacts().get("momentFact")))
        {
            SceneCandidate candidate = receipt.getCandidate();
            boolean walks = !isPassiveLivingWorld(session, candidate)
                && (session.plan == null || session.directorSceneId != candidate.getId()
                    || (session.plan.approach || session.plan.standWorldX != null)
                        && !session.actor.hasNotTranslated(session.sceneTranslation, candidate.getId()));
            session.speechPostures.onPaint(receipt, session.director.getPacket(), session.director.getExecutionHoldTicks(),
                speechPostureAllowed(session), walks, session.actor.hasGestures());
            updatePostures(session, false);
        }
    }

    private void silentSceneArrived(RuntimeSession session, SceneCandidate candidate)
    {
        if ("E01".equals(candidate.getScenarioId())) { session.observer.emoteArrived(candidate); }
        if ("M05".equals(candidate.getScenarioId())) { session.observer.groundOutcomeSettled(candidate); }
        if (session.speechPostures != null)
        {
            session.speechPostures.onFourthAnswerArrival(candidate, session.tick, speechPostureAllowed(session));
            updatePostures(session, false);
        }
    }

    private boolean speechPostureAllowed(RuntimeSession session)
    {
        return session.actor.hasEmotes() && session.presence.isPresent() && session.actor.isVisible()
            && !session.actor.isMoving() && !session.observer.isCombatConfirmed()
            && !session.actor.getCreatureAwareness().isKeepingAway();
    }

    private void updatePostures(RuntimeSession session, boolean committed)
    {
        if (session.director == null || session.worldWaiting == null || session.speechPostures == null) { return; }
        Player player = client.getLocalPlayer(); WorldPoint point = player == null ? null : player.getWorldLocation();
        boolean known = point != null && !player.isDead() && client.getBoostedSkillLevel(Skill.HITPOINTS) > 0
            && client.getGameState() == GameState.LOGGED_IN;
        SceneCandidate active = session.director.getActiveCandidate();
        boolean waitingLine = active != null && "KEPT_WAITING".equals(active.getConditionFacts().get("momentFact"));
        boolean waitingAllowed = known && session.presence.isPresent() && session.actor.isVisible()
            && !session.observer.isCombatConfirmed() && !session.actor.getCreatureAwareness().isKeepingAway()
            && session.director.pendingCount() == 0 && (active == null || waitingLine) && !session.speechPostureActive;
        if (session.worldWaiting.isActive() && (!waitingAllowed || !known
            || !session.worldWaiting.playerStillOnTile(point.getX(), point.getY(), point.getPlane())))
        {
            session.actor.endImpatient();
        }
        long ended = session.actor.consumeImpatientEndTick();
        if (ended >= 0) { session.worldWaiting.onEmoteEnded(ended); }
        long started = session.actor.consumeImpatientLoopStartTick();
        if (started >= 0)
        {
            int times = session.worldWaiting.onLoopStarted(started);
            if (times > 0 && session.worldMoments != null)
            {
                SceneCandidate line = session.worldMoments.offerWaiting(session.tick, session.sessionGeneration,
                    session.sceneRevision, times, livingWorldBaseEligible(session) && session.director.livingWorldSpacingReady());
                if (line != null) { offerScene(session, line); applySceneMotion(session); }
            }
        }
        if (committed)
        {
            int duration = session.worldWaiting.observe(session.tick, session.sessionGeneration, session.sceneRevision, known,
                point == null ? 0 : point.getX(), point == null ? 0 : point.getY(), point == null ? -1 : point.getPlane(),
                waitingAllowed && active == null && session.actor.hasEmotes() && session.actor.isStandingInRest());
            if (duration > 0 && !session.actor.startImpatient(duration)) { session.worldWaiting.requestRejected(); }
        }
        // H.6: a painted line may ask for an emote of her own. It is taken only if she is free to stand and do it.
        SpeechPostureTriggers.GestureRequest gesture = session.speechPostures.consumeGesture();
        if (gesture != null && speechPostureAllowed(session) && !session.speechPostureActive && !session.worldWaiting.isActive())
        {
            session.actor.playGesture(com.malfoid.asset.GesturePlayback.Gesture.valueOf(gesture.name), gesture.untilTick);
        }
        boolean folded = session.speechPostures.shouldFold(session.tick, session.director.now(),
            session.director.getActiveCandidate(), speechPostureAllowed(session));
        if (folded && !session.speechPostureActive && !session.worldWaiting.isActive())
        {
            session.speechPostureActive = session.actor.foldArms(Long.MAX_VALUE);
        }
        else if (!folded && session.speechPostureActive)
        {
            session.actor.clearSpeechPosture(); session.speechPostureActive = false;
        }
    }

    private void applySceneMotion(RuntimeSession session)
    {
        if (session.director == null)
        {
            return;
        }
        SceneCandidate candidate = session.director.getActiveCandidate();
        if (isPassiveLivingWorld(session, candidate))
        {
            if (session.directorSceneId != -1)
            {
                if ((session.plan == null || !session.plan.preservesNativeMotion)
                    && session.creaturePreemptedSceneId != session.directorSceneId)
                {
                    session.actor.clearIntent(); session.actor.setSceneMotionVoluntary(false);
                }
                session.directorSceneId = -1; session.plan = null;
            }
            session.sceneTranslation = null;
            // A place or creature sentence accompanies native movement; it owns no hold or route.
            if (("PLACE".equals(session.catalog.livingWorldFamily(candidate.getScenarioId()))
                || "MOMENT".equals(session.catalog.livingWorldFamily(candidate.getScenarioId()))) && !session.actor.isMoving()
                && !session.actor.getCreatureAwareness().isKeepingAway())
            {
                session.actor.facePlayer();
            }
            session.director.arrive(candidate.getId(), session.tick);
            return;
        }
        if (candidate == null)
        {
            if (session.directorSceneId != -1)
            {
                if ((session.plan == null || !session.plan.preservesNativeMotion)
                    && session.creaturePreemptedSceneId != session.directorSceneId)
                {
                    session.actor.clearIntent();
                    session.actor.setSceneMotionVoluntary(false);
                }
                session.directorSceneId = -1;
                session.creaturePreemptedSceneId = -1;
            }
            session.sceneTranslation = null;
            return;
        }
        if (session.director.isContinuationCancelled())
        {
            if (session.creaturePreemptedSceneId != candidate.getId() && !session.observer.isCombatConfirmed()
                && hasHmphPaint(session, candidate))
            {
                updateOutfitFacing(session, candidate);
                return;
            }
            if (session.cancelledSceneId != candidate.getId())
            {
                if (!preservesNativeMotion(session, candidate)) { session.actor.clearIntent(); }
                session.cancelledSceneId = candidate.getId();
                record(session, "SCENE_CANCELLED_KEEPING_TEXT scenario=" + candidate.getScenarioId());
            }
            return;
        }
        if (candidate.getId() == session.directorSceneId)
        {
            updateOutfitFacing(session, candidate);
            if (session.plan != null && session.plan.preservesNativeMotion) { return; }
            if (preservesTravelFollowing(candidate, session.plan))
            {
                updateTravelFollowing(session);
            }
            if (session.director.isReleasing())
            {
                double progress = session.director.getReleaseProgress();
                int start = "M01".equals(candidate.getScenarioId()) ? 1 : 2;
                int minimum = start + (int) Math.round(progress * (4 - start));
                int maximum = 2 + (int) Math.round(progress * 5);
                int band = minimum * 16 + maximum;
                if (band != session.releaseBand)
                {
                    session.actor.requestApproachPlayer(minimum, maximum, false);
                    session.actor.facePlayer();
                    session.releaseBand = band;
                }
                return;
            }
            if (session.releaseBand != -1 && session.plan != null)
            {
                session.actor.requestApproachPlayer(session.plan.minDistance, session.plan.maxDistance,
                    session.plan.run, "M01".equals(candidate.getScenarioId()));
                session.releaseBand = -1;
            }
            if (session.plan != null && session.plan.run && session.plan.runBurstMaxTicks > 0
                && !session.burstDemoted && session.tick - session.sceneMotionStarted >= session.plan.runBurstMaxTicks)
            {
                session.actor.requestApproachPlayer(session.plan.minDistance, session.plan.maxDistance, false);
                session.burstDemoted = true;
                record(session, "SCENE_RUN_BURST_ENDED scenario=" + candidate.getScenarioId());
            }
            return;
        }
        session.directorSceneId = candidate.getId();
        session.sceneMotionStarted = session.tick;
        session.burstDemoted = false;
        session.followingTravelAcknowledgement = false;
        session.releaseBand = -1;
        ScenePlan plan = ScenePlan.forCandidate(candidate, companionDistance(session),
            session.overlay != null && session.overlay.isHeadVisible(), session.director.isIntentionalSilent(),
            session.director.getExecutionHoldTicks());
        if (plan.voluntaryTrip && !plan.voluntaryTripAllowed(session.tick,
            Math.max(session.actor.getLastInspectionStartTick(), session.actor.getLastVoluntaryMovementTick())))
        {
            session.actor.clearIntent();
            session.actor.setSceneMotionVoluntary(false);
            session.director.invalidate(false);
            session.directorSceneId = -1;
            session.plan = null;
            record(session, "VOLUNTARY_TRIP_DEFERRED scenario=" + candidate.getScenarioId());
            return;
        }
        session.plan = plan;
        session.sceneTranslation = session.actor.captureSceneTranslation(candidate.getId());
        if (session.actor.isE5KeepingAway() && !candidate.isEmergency() && !plan.preservesNativeMotion)
        {
            session.director.invalidate(false);
            session.observer.softRevokeParent();
            session.cancelledSceneId = session.creaturePreemptedSceneId = candidate.getId();
            record(session, "CREATURE_PREEMPTED_SCENE scenario=" + candidate.getScenarioId());
            return;
        }
        if (plan.preservesNativeMotion)
        {
            record(session, "SCENE_NATIVE_MOTION id=" + candidate.getId() + " scenario=" + candidate.getScenarioId());
            return;
        }
        long motionRequest = session.observer.getMotionRequestId(candidate);
        if (motionRequest >= 0 && !session.actor.admitMotionScene(candidate.getId(), motionRequest))
        {
            session.director.invalidate(false);
            return;
        }
        boolean keepNativeFollowing = motionRequest >= 0 && preservesTravelFollowing(candidate, plan)
            && session.observer.isPlayerMoving();
        if (!keepNativeFollowing)
        {
            session.actor.clearIntent(candidate.getId());
        }
        if (plan.standWorldX != null && plan.standWorldY != null)
        {
            if ("M08".equals(candidate.getScenarioId()) || "M69".equals(candidate.getScenarioId())
                || session.director.isLivingWorldScenery(candidate))
            {
                if (!session.actor.requestInspectionDestination(candidate.getId(), plan.standWorldX, plan.standWorldY))
                {
                    session.observer.softRevokeParent();
                    session.actor.holdPosition();
                    session.director.invalidate(false);
                    return;
                }
            }
            else
            {
                session.actor.requestDestination(plan.standWorldX, plan.standWorldY, plan.run);
            }
        }
        else if (plan.approach)
        {
            session.actor.requestApproachPlayer(plan.minDistance, plan.maxDistance, plan.run,
                "M01".equals(candidate.getScenarioId()));
            if (plan.runBurstMaxTicks > 0)
            {
                session.actor.limitRunBurst(plan.runBurstMaxTicks);
            }
        }
        else
        {
            if (preservesTravelFollowing(candidate, plan) && session.observer.isPlayerMoving())
            {
                session.followingTravelAcknowledgement = true;
            }
            else
            {
                session.actor.holdPosition(candidate.getId());
            }
        }
        session.actor.setSceneMotionVoluntary(plan.voluntaryTrip);
        session.actor.setSceneEmergency(candidate.isEmergency());
        // P39: at a thing she has walked up to (F.4 scenery, M08, M69) she peers at it.
        session.actor.setPeerAtFacingTarget(plan.facing == ScenePlan.Facing.OBJECT);
        if (plan.facing == ScenePlan.Facing.AWAY)
        {
            session.actor.turnAway();
        }
        else if (plan.subjectWorldX != null && plan.subjectWorldY != null
            && session.actor.getWorldView() != null)
        {
            session.actor.faceTarget(net.runelite.api.coords.LocalPoint.fromWorld(session.actor.getWorldView(),
                new WorldPoint(plan.subjectWorldX, plan.subjectWorldY, session.actor.getPlane())));
        }
        else
        {
            session.actor.facePlayer();
        }
        record(session, "SCENE_MOTION id=" + candidate.getId() + " scenario=" + candidate.getScenarioId());
    }

    private void updateOutfitFacing(RuntimeSession session, SceneCandidate candidate)
    {
        com.malfoid.speech.SpeechPacket packet = session.director.getPacket();
        com.malfoid.speech.SpeechPacket.PaintReceipt paint = packet == null ? null : packet.getFirstPaint();
        if (!hasHmphPaint(session, candidate)) { return; }
        if (session.tick - paint.getPaintedAtTick() >= 2) { session.actor.turnAway(); }
        else { session.actor.facePlayer(); }
    }

    private static boolean hasHmphPaint(RuntimeSession session, SceneCandidate candidate)
    {
        com.malfoid.speech.SpeechPacket packet = session.director.getPacket();
        com.malfoid.speech.SpeechPacket.PaintReceipt paint = packet == null ? null : packet.getFirstPaint();
        return paint != null && paint.getCandidate().getId() == candidate.getId()
            && ("M09-H01".equals(paint.getLineId()) || "M09-H02".equals(paint.getLineId()));
    }

    private static boolean preservesTravelFollowing(SceneCandidate candidate, ScenePlan plan)
    {
        if (plan == null || plan.approach || plan.standWorldX != null || candidate.isRequested())
        {
            return false;
        }
        String scenario = candidate.getScenarioId();
        return "M10".equals(scenario) || "M70".equals(scenario)
            || scenario.matches("M5[1-9]|M6[0-3]");
    }

    private void updateTravelFollowing(RuntimeSession session)
    {
        boolean moving = session.observer.isPlayerMoving();
        if (moving != session.followingTravelAcknowledgement)
        {
            SceneCandidate candidate = session.director.getActiveCandidate();
            if (candidate == null) { return; }
            if (moving) { session.actor.clearIntent(candidate.getId()); }
            else { session.actor.holdPosition(candidate.getId()); }
            session.followingTravelAcknowledgement = moving;
        }
    }

    private void acknowledgeArrival(RuntimeSession session)
    {
        SceneCandidate candidate = session.director.getActiveCandidate();
        if (candidate == null || !session.actor.isVisible() || session.plan == null
            || !session.plan.preservesNativeMotion && !session.actor.hasArrived()
                && !session.actor.hasMotionSceneArrived(candidate.getId()))
        {
            return;
        }
        ScenePlan plan = session.plan;
        int distance = companionDistance(session);
        WorldPoint companion = companionWorldPoint(session);
        boolean atObject = plan.standWorldX != null && plan.standWorldY != null && companion != null
            && companion.getX() == plan.standWorldX && companion.getY() == plan.standWorldY;
        if (!plan.approach || (plan.standWorldX != null ? atObject
            : distance >= plan.minDistance && distance <= plan.maxDistance))
        {
            if (session.director.isLivingWorldScenery(candidate) && !session.director.hasArrived()
                && !session.observer.sceneryArrived(candidate))
            {
                session.director.invalidate(false);
                return;
            }
            session.director.arrive(candidate.getId(), session.tick);
        }
    }

    private void updateCombatTarget(RuntimeSession session)
    {
        Player player = client.getLocalPlayer();
        Actor interacting = player == null ? null : player.getInteracting();
        NPC target = interacting instanceof NPC ? (NPC) interacting : null;
        NPCComposition composition = target == null ? null : target.getTransformedComposition();
        boolean copiedTarget = target != null && composition != null && !target.isDead()
            && target.getWorldView() == player.getWorldView() && target.getLocalLocation() != null;
        session.actor.setCombatTarget(session.observer.isCombatConfirmed(),
            copiedTarget ? target.getLocalLocation() : null, copiedTarget ? composition.getSize() : 0);
    }

    private com.malfoid.scenario.ScenarioSnapshot.InspectionTarget inspectionTarget(RuntimeSession session,
        String key, String name, int minX, int minY, int maxX, int maxY, int plane)
    {
        if (session.actor.isInspectionSuppressed()
            || !session.actor.requestInspectionProbe(key, minX, minY, maxX, maxY, plane))
        {
            return null;
        }
        NativeActor.InspectionRouteProof proof = session.actor.getInspectionRouteProof();
        if (proof == null || !key.equals(proof.getKey()) || proof.getMinWorldX() != minX
            || proof.getMinWorldY() != minY || proof.getMaxWorldX() != maxX || proof.getMaxWorldY() != maxY
            || proof.getPlane() != plane || !session.actor.isInspectionProofCurrent(proof))
        {
            return null;
        }
        return new com.malfoid.scenario.ScenarioSnapshot.InspectionTarget(key, name, minX, minY,
            maxX, maxY, plane, new com.malfoid.scenario.ScenarioSnapshot.Position(
                proof.getStandWorldX(), proof.getStandWorldY(), plane), proof.getPathSteps());
    }

    private int companionDistance(RuntimeSession session)
    {
        Player player = client.getLocalPlayer();
        WorldPoint companion = companionWorldPoint(session);
        return player == null || companion == null || player.getWorldLocation() == null
            ? Integer.MAX_VALUE : companion.distanceTo(player.getWorldLocation());
    }

    private WorldPoint companionWorldPoint(RuntimeSession session)
    {
        NativeActor.PlacementSnapshot placement = session.actor.getPlacementSnapshot();
        return placement == null ? null : WorldPoint.fromLocal(placement.getWorldView(),
            placement.getLocation().getX(), placement.getLocation().getY(), placement.getPlane());
    }

    private ModalOcclusionSnapshot refreshLivePresentation(RuntimeSession session)
    {
        ModalOcclusionSnapshot modal = session.presence.sampleModalOcclusion();
        session.actor.setIdleMovementSuppressed(session.presence.isBusy());
        session.observer.setLiveUiGuards(session.presence.isCurrentOutfitBlocked(),
            session.presence.isCurrentInventoryBlocked());
        if (session.director != null)
        {
            session.director.refreshPresentation(session.presence.isBusy());
        }
        return modal;
    }

    private void publishProjection(RuntimeSession session, ModalOcclusionSnapshot modal)
    {
        if (session.overlay == null)
        {
            return;
        }
        NativeActor.PlacementSnapshot placement = session.actor.getPlacementSnapshot();
        if (placement == null || !session.presence.isPresent() || !config.showDialogue())
        {
            session.overlay.clear();
            return;
        }
        net.runelite.api.Point head = Perspective.localToCanvas(client, placement.getLocation(),
            placement.getPlane(), placement.getHeadHeight());
        Rectangle viewport = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
            client.getViewportWidth(), client.getViewportHeight());
        List<NativeOverheadText> nativeTexts = new ArrayList<>();
        WorldView view = placement.getWorldView();
        Player player = client.getLocalPlayer();
        if ((client.getDraw2DMask() & Client.DRAW_2D_OVERHEAD_TEXT) != 0)
        {
            appendNativeOverhead(nativeTexts, player, view, placement.getPlane(), viewport.x);
            Iterable<? extends Player> players = OtherPlayerPolicy.ignoreOtherPlayers(client) ? null : view.players();
            if (players != null)
            {
                for (Player other : players)
                {
                    if (nativeTexts.size() >= NativeOverheadText.MAX_NATIVE_TEXTS) { break; }
                    if (other != player)
                    {
                        appendNativeOverhead(nativeTexts, other, view, placement.getPlane(), viewport.x);
                    }
                }
            }
            Iterable<? extends NPC> npcs = view.npcs();
            if (npcs != null)
            {
                for (NPC npc : npcs)
                {
                    if (nativeTexts.size() >= NativeOverheadText.MAX_NATIVE_TEXTS) { break; }
                    appendNativeOverhead(nativeTexts, npc, view, placement.getPlane(), viewport.x);
                }
            }
        }
        session.overlay.publish(head == null ? null : new java.awt.Point(head.getX(), head.getY()),
            viewport, client.getGameCycle(), nativeTexts, modal);
    }

    private void appendNativeOverhead(List<NativeOverheadText> texts, Actor actor, WorldView view, int plane,
        int viewportX)
    {
        if (texts.size() >= NativeOverheadText.MAX_NATIVE_TEXTS || actor == null
            || actor.getWorldView() != view || actor.getOverheadCycle() <= 0)
        {
            return;
        }
        String text = actor.getOverheadText();
        LocalPoint location = actor.getLocalLocation();
        if (text == null || text.isEmpty() || location == null)
        {
            return;
        }
        net.runelite.api.Point baseline = Perspective.localToCanvas(client, location, plane,
            actor.getLogicalHeight() + actor.getAnimationHeightOffset());
        if (baseline != null && baseline.getX() >= viewportX)
        {
            texts.add(new NativeOverheadText(baseline.getX(), baseline.getY(), text));
        }
    }

    private void observePresentation(RuntimeSession session)
    {
        if (session.director == null)
        {
            return;
        }
        com.malfoid.speech.SpeechPacket.PaintReceipt paint = session.director.getLastPaint();
        if (paint == null)
        {
            return;
        }
        String utterance = Long.toString(paint.getAdmissionToken());
        if (paint.getAdmissionToken() != session.lastPaintToken)
        {
            session.lastPaintToken = paint.getAdmissionToken();
            SceneCandidate candidate = paint.getCandidate();
            SceneCandidate active = session.director.getActiveCandidate();
            if (active != null && active.getId() == candidate.getId() && session.presence.isPresent()
                && candidate.getSessionGeneration() == session.sessionGeneration
                && candidate.getSceneRevision() == session.sceneRevision
                && (candidate.isRequested() || session.observer.candidateStillValid(candidate)))
            {
                session.observer.firstPaint(utterance, candidate.getScenarioId(), paint.getPaintedAtTick(),
                    candidate.getSessionGeneration(), candidate.getSceneRevision(), candidate.getExpiresTick());
            }
        }
        session.observer.presentationVisible(utterance, session.overlay != null && session.overlay.isDrawable());
    }

    private void hide(RuntimeSession session, String reason)
    {
        boolean visible = session.actor.isVisible();
        session.observer.setPresence(false, !config.showMalfoid(), isAutomaticAbsence(reason));
        clearInspectionReturn(session);
        session.actor.hide();
        session.sceneTranslation = null;
        if (session.worldWaiting != null)
        {
            long ended = session.actor.consumeImpatientEndTick();
            if (ended >= 0) { session.worldWaiting.onEmoteEnded(ended); }
        }
        if (session.speechPostures != null) { session.speechPostures.shouldFold(session.tick, 0, null, false); }
        session.speechPostureActive = false;
        session.directorSceneId = -1;
        session.placementGeneration = -1;
        session.loggedPlacementGeneration = -1;
        boolean retainedLoading = session.director != null && session.director.hasRetainedLevel()
            && ("LOADING".equals(reason) || "STABILIZING".equals(reason)
                || "SCENE_CHANGED".equals(reason) || "PREPARING".equals(reason));
        if (retainedLoading)
        {
            if (session.overlay != null) { session.overlay.clear(); }
        }
        else
        {
            invalidateSpeech(session, true);
        }
        if (visible)
        {
            record(session, "OBJECT_REMOVED reason=" + reason);
        }
    }

    private void invalidateSpeech(RuntimeSession session, boolean hard)
    {
        if (hard) { clearInspectionReturn(session); }
        if (session.director != null)
        {
            session.director.invalidate(hard);
        }
        if (hard && session.overlay != null)
        {
            session.overlay.clear();
        }
    }

    private void logRecoveryObservation(RuntimeSession session, PresenceController.Decision decision)
    {
        RecoveryObservation observation = session.actor.getRecoveryObservation();
        if (observation == null || observation.committedTick != session.tick
            || observation.observationId == session.lastRecoveryObservationId) return;
        record(session, "RECOVERY_OBSERVATION id=" + observation.observationId
            + " tick=" + observation.committedTick + " session=" + session.sessionGeneration
            + " scene=" + session.sceneRevision + " nativeScene=" + observation.sceneRevision
            + " present=" + decision.isPresent() + " epoch=" + decision.getPresenceEpoch()
            + " since=" + decision.getPresentSinceTick() + " view=" + observation.worldViewId
            + " plane=" + observation.plane + " generation=" + observation.placementGenerationBefore
            + "->" + observation.placementGenerationAfter + " episode=" + observation.episodeId
            + " start=" + observation.episodeStartedTick + " cause=" + observation.cause
            + " result=" + observation.disposition + " raw=" + observation.plannerStatus
            + " limited=" + observation.searchLimited + " visited=" + observation.visitedCells
            + " steps=" + observation.plannedSteps + " separation=" + observation.separation
            + " off=" + observation.offScreenTicksBefore + "->" + observation.offScreenTicksAfter
            + " blocked=" + observation.blockedTicksBefore + "->" + observation.blockedTicksAfter
            + " ineffective=" + observation.ineffectivePlansBefore + "->" + observation.ineffectivePlansAfter
            + " visibility=" + observation.decisionVisibility + " recheck="
            + (observation.originRechecked ? observation.originRecheckVisibility : "NOT_RUN")
            + " pauseUntil=" + observation.planNotBefore + " retryAt=" + observation.retryAt);
        session.lastRecoveryObservationId = observation.observationId;
    }

    private void logPresence(RuntimeSession session, PresenceController.Decision decision)
    {
        String next = decision.getState() + ":" + decision.getReason();
        if (!next.equals(session.lastPresence))
        {
            session.lastPresence = next;
            record(session, "PRESENCE " + next + " tick=" + session.tick);
        }
    }

    private static boolean preservesNativeMotion(RuntimeSession session, SceneCandidate candidate)
    {
        return isPassiveLivingWorld(session, candidate) || "F03".equals(candidate.getScenarioId())
            || com.malfoid.motion.CreatureAwareness.isE5(candidate.getScenarioId());
    }

    private int configuredHeightPercent()
    {
        int percent = config.heightPercent();
        return percent >= 90 && percent <= 110 ? percent : 100;
    }

    private void logPlacement(RuntimeSession session)
    {
        NativeActor.PlacementSnapshot placement = session.actor.getPlacementSnapshot();
        if (placement == null || placement.getPlacementGeneration() == session.loggedPlacementGeneration)
        {
            return;
        }
        Player player = client.getLocalPlayer();
        record(session, "OBJECT_PLACED plane=" + placement.getPlane()
            + " view=" + placement.getWorldViewId()
            + " localX=" + placement.getLocation().getX()
            + " localY=" + placement.getLocation().getY()
            + " drawZ=" + placement.getDrawZ()
            + " orientation=" + placement.getOrientation()
            + " headHeight=" + placement.getHeadHeight()
            + " heightPercent=" + session.actor.getHeightPercent()
            + " playerLogicalHeight=" + (player == null ? -1 : player.getLogicalHeight()));
        session.loggedPlacementGeneration = placement.getPlacementGeneration();
    }

    private void guarded(RuntimeSession session, String stage, Runnable action)
    {
        if (session == null || current != session || !session.active.get() || session.failed.get())
        {
            return;
        }
        try
        {
            action.run();
        }
        catch (Throwable failure)
        {
            fail(session, stage, failure);
        }
    }

    private void fail(RuntimeSession session, String stage, Throwable failure)
    {
        if (session.failed.compareAndSet(false, true))
        {
            try
            {
                String line = "SESSION_OFF stage=" + stage + " " + safeFailure(failure);
                record(session, line);
                LOG.warn("Malfoid {}", line);
            }
            catch (Throwable ignored)
            {
                // The failure reporter must not escape into a RuneLite callback.
            }
            if (current != session || !session.active.get()) { return; }
            try
            {
                if (session.overlay != null) { session.overlay.clear(); }
                clientThread.invokeLater(() ->
                {
                    dispose(session);
                    if (current != session || !session.active.get()) { return; }
                    try
                    {
                        session.notices.onModelUnavailable();
                        session.notices.flush();
                    }
                    catch (Throwable ignored)
                    {
                        // The original failure is the sole session error record.
                    }
                });
            }
            catch (Throwable ignored)
            {
                // A failed cleanup queue cannot turn containment into another client failure.
            }
        }
    }

    private static String safeFailure(Throwable failure)
    {
        return failure.getClass().getSimpleName() + ": "
            + String.valueOf(failure.getMessage()).replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean isAutomaticAbsence(String reason)
    {
        return "CUTSCENE".equals(reason) || "PLAYER_COMBAT".equals(reason)
            || "PVP_AREA".equals(reason) || "SPECTATING".equals(reason) || "ABOARD".equals(reason) || "MODEL_UNAVAILABLE".equals(reason)
            || "NO_LEGAL_TILE".equals(reason);
    }

    private void removeOverlay(RuntimeSession session)
    {
        try
        {
            if (session.overlay != null)
            {
                session.overlay.clear();
                overlayManager.remove(session.overlay);
            }
        }
        catch (Throwable failure)
        {
            fail(session, "overlay-remove", failure);
        }
    }

    private void dispose(RuntimeSession session)
    {
        try
        {
            session.actor.dispose();
            invalidateSpeech(session, true);
        }
        catch (Throwable failure)
        {
            fail(session, "dispose", failure);
        }
    }

    private static void record(RuntimeSession session, String message)
    {
        if (session.log != null)
        {
            session.log.record(message);
        }
    }

    private static final class RuntimeSession
    {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicBoolean failed = new AtomicBoolean();
        final NativeActor actor;
        final ScenarioObserver observer;
        final PresenceController presence;
        final MalfoidMenu menu;
        final ChatNotices notices;
        volatile SessionLog log;
        volatile DialogueCatalog catalog;
        volatile LivingWorldData worldData;
        LivingWorldPlaces worldPlaces;
        LivingWorldCreatures worldCreatures;
        LivingWorldMoments worldMoments;
        LivingWorldWaiting worldWaiting;
        SpeechPostureTriggers speechPostures;
        boolean speechPostureActive;
        NativeActor.SceneTranslationSnapshot sceneTranslation;
        volatile SpeechDirector director;
        volatile MalfoidSpeechOverlay overlay;
        volatile boolean resourcesLoaded;
        long sessionGeneration, sceneRevision = 1, tick, accountHash;
        long presenceSceneGeneration = Long.MIN_VALUE, placementGeneration = -1, directorSceneId = -1;
        long loggedPlacementGeneration = -1;
        long commandSequence = 1_000_000_000L;
        long worldSequence = 1_000_000_000_000L;
        final TalkState talkState = new TalkState();
        final java.util.Set<String> seenClips = new java.util.HashSet<>();
        long lastPaintToken = -1;
        long lastRecoveryObservationId = -1;
        long cancelledSceneId = -1;
        long creaturePreemptedSceneId = -1;
        long sceneMotionStarted;
        int world;
        int releaseBand = -1;
        boolean closingBatch, retryAtLogin, burstDemoted, followingTravelAcknowledgement;
        String lastPresence;
        ScenePlan plan;
        CancelledInterestObservation inspectionReturn;

        RuntimeSession(long generation, NativeActor actor, ScenarioObserver observer,
            PresenceController presence, MalfoidMenu menu, ChatNotices notices)
        {
            this.sessionGeneration = generation;
            this.actor = actor;
            this.observer = observer;
            this.presence = presence;
            this.menu = menu;
            this.notices = notices;
        }
    }
}
