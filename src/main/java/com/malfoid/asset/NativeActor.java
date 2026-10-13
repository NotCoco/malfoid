package com.malfoid.asset;

import com.malfoid.presence.OtherPlayerPolicy;
import com.google.gson.Gson;
import com.malfoid.motion.InspectionRoutes;
import com.malfoid.motion.KeepAway;
import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.CreatureAwareness.Behaviour;
import com.malfoid.motion.CreatureAwareness.Classification;
import com.malfoid.motion.CreatureAwareness.Creature;
import com.malfoid.motion.CreatureAwareness.Sample;
import com.malfoid.motion.FollowCollision;
import com.malfoid.motion.MotionMath;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionRecorder;
import com.malfoid.motion.RecoveryPolicy;
import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.RestingRules;
import com.malfoid.motion.RouteMotion;
import com.malfoid.motion.RoutePlanner;
import com.malfoid.motion.SceneCollision;
import com.malfoid.motion.WholeBoundsVisibility;
import java.awt.Shape;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.ArrayList;
import java.util.function.BiFunction;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/** Session-owned native visual. Every method except loadResources runs on the client thread. */
public final class NativeActor
{
    private enum Intent { NORMAL, APPROACH, DESTINATION, HOLD }

    /** Capture before a physical scene begins; actual translation invalidates its zero-step evidence. */
    public static final class SceneTranslationSnapshot
    {
        private final NativeActor owner;
        private final long candidateId, placementGeneration, translationRevision;
        private final WorldView worldView;
        private final Scene scene;
        private final int plane;

        private SceneTranslationSnapshot(NativeActor actor, long candidateId)
        {
            owner = actor;
            this.candidateId = candidateId;
            placementGeneration = actor.placementGeneration;
            translationRevision = actor.translationRevision;
            worldView = actor.view;
            scene = actor.scene;
            plane = actor.plane;
        }
    }

    public static final class PlacementSnapshot
    {
        private final Model model;
        private final LocalPoint location;
        private final WorldView worldView;
        private final Scene scene;
        private final int plane, orientation, drawZ, headHeight;
        private final long placementGeneration;

        private PlacementSnapshot(Model model, LocalPoint location, WorldView view, int plane, int orientation,
            int drawZ, long generation)
        {
            this.model = model;
            this.location = location;
            worldView = view;
            scene = view.getScene();
            this.plane = plane;
            this.orientation = orientation;
            this.drawZ = drawZ;
            placementGeneration = generation;
            headHeight = model.getModelHeight();
        }

        public Model getModel() { return model; }
        public LocalPoint getLocation() { return location; }
        public WorldView getWorldView() { return worldView; }
        public Scene getScene() { return scene; }
        public int getWorldViewId() { return worldView.getId(); }
        public int getPlane() { return plane; }
        public int getOrientation() { return orientation; }
        public int getDrawZ() { return drawZ; }
        public int getHeadHeight() { return headHeight; }
        public long getPlacementGeneration() { return placementGeneration; }
    }

    public static final class InspectionRouteProof
    {
        private final String key;
        private final int minWorldX, minWorldY, maxWorldX, maxWorldY, plane;
        private final int standWorldX, standWorldY;
        private final WorldView worldView;
        private final Scene scene;
        private final long placementGeneration;
        private final RoutePlanner.Node origin;
        private final List<RoutePlanner.Node> steps;

        private InspectionRouteProof(InspectionRequest request, NativeActor actor, RoutePlanner.Result result)
        {
            key = request.key;
            minWorldX = request.minWorldX;
            minWorldY = request.minWorldY;
            maxWorldX = request.maxWorldX;
            maxWorldY = request.maxWorldY;
            plane = request.plane;
            worldView = actor.view;
            scene = actor.scene;
            placementGeneration = actor.placementGeneration;
            origin = actor.motion.completedNode();
            steps = result.steps;
            RoutePlanner.Node stand = steps.isEmpty() ? origin : steps.get(steps.size() - 1);
            standWorldX = actor.baseX + stand.x;
            standWorldY = actor.baseY + stand.y;
        }

        public String getKey() { return key; }
        public int getMinWorldX() { return minWorldX; }
        public int getMinWorldY() { return minWorldY; }
        public int getMaxWorldX() { return maxWorldX; }
        public int getMaxWorldY() { return maxWorldY; }
        public int getPlane() { return plane; }
        public int getStandWorldX() { return standWorldX; }
        public int getStandWorldY() { return standWorldY; }
        public int getPathSteps() { return steps.size(); }
        public WorldView getWorldView() { return worldView; }
        public Scene getScene() { return scene; }
        public long getPlacementGeneration() { return placementGeneration; }
    }

    private static final class InspectionRequest
    {
        private final String key;
        private final int minWorldX, minWorldY, maxWorldX, maxWorldY, plane;

        private InspectionRequest(String key, int minWorldX, int minWorldY, int maxWorldX, int maxWorldY, int plane)
        {
            this.key = key;
            this.minWorldX = minWorldX;
            this.minWorldY = minWorldY;
            this.maxWorldX = maxWorldX;
            this.maxWorldY = maxWorldY;
            this.plane = plane;
        }

        private boolean matches(InspectionRouteProof proof)
        {
            return proof != null && key.equals(proof.key) && minWorldX == proof.minWorldX && minWorldY == proof.minWorldY
                && maxWorldX == proof.maxWorldX && maxWorldY == proof.maxWorldY && plane == proof.plane;
        }
    }

    private static final class Controller extends RuneLiteObjectController
    {
        private Model model;
        @Override public Model getModel() { return model; }
    }

    private final Client client;
    private final Gson gson;
    private final RoutePlanner planner = new RoutePlanner();
    private final RouteMotion motion = new RouteMotion();
    private final MotionRecorder motionRecorder = new MotionRecorder();
    private final RecoveryPolicy recovery = new RecoveryPolicy();
    private final CreatureIntake creatureIntake = new CreatureIntake();
    private final CreatureAwareness creatureAwareness = new CreatureAwareness();
    private Sample creatureSample;
    /** H.2: the ground she will not walk on this tick, and how far she has had to give way where it left no route. */
    private KeepAway keepAway = KeepAway.NONE;
    private int keepAwayRelax;
    private long keepAwayRelaxedTick = Long.MIN_VALUE;
    /** H.3: the way she last walked, kept for a moment after each step so she does not turn between steps. */
    private int lastMoveBearing, lastMovingCycle = Integer.MIN_VALUE;
    private static final int MOVE_FACING_HOLD_CYCLES = 20;
    private boolean livingWorldCreatureAllowed = true, creatureReplanRequested, creatureShortRestPending;
    private boolean creatureShortRestE5;
    private boolean sceneEmergency, creatureScenePreempted;
    private long lastCreaturePlanTick = Long.MIN_VALUE, creaturePlanBeginTick = -1;
    private Creature strollCreature, restingCreature, timedFacingCreature, creatureStrollArrival;
    private long creatureFacingUntilTick = -1, creatureStrollArrivalTick = -1;
    private RecoveryObservation recoveryObservation;
    private long recoveryObservationSequence, recoveryEpisodeSequence, recoverySceneRevision;
    private long offScreenEpisodeId, blockedEpisodeId, offScreenEpisodeTick = -1, blockedEpisodeTick = -1;
    private final Random random;
    private final Controller controller = new Controller();
    private volatile NativeAsset decodedAsset;
    private NativePoseBank bank;
    private int heightPercent = 100;
    private WorldView view;
    private Scene scene;
    private int baseX, baseY, sizeX, sizeY, plane;
    private boolean visible, everSpawned;
    private volatile boolean disposed;
    private int lastCycle = Integer.MIN_VALUE;
    private int orientation;
    private int clipCycle;
    private String clip = "IDLE";
    private final PosturePlayback posturePlayback = new PosturePlayback(true);
    /** H.6: the emotes of her own. They exist only on top of the plain standing posture. */
    private final GesturePlayback gesturePlayback = new GesturePlayback();
    private GesturePlayback.Gesture speechGesture, pendingGesture;
    private long speechGestureUntilTick = -1;
    private int pendingGestureUntilCycle = Integer.MIN_VALUE, lastDesiredFacing;
    private boolean peerAtFacingTarget;
    private long foldedUntilTick = -1;
    private int impatientDurationTicks, impatientUntilCycle = Integer.MIN_VALUE;
    private boolean impatientEpisode;
    private int impatientLoopStartCycle = Integer.MIN_VALUE, impatientEndCycle = Integer.MIN_VALUE;
    private long impatientLoopStartTick = -1, impatientEndTick = -1;
    private long impatientLoopStartReceipt = -1, impatientEndReceipt = -1;
    private long placementGeneration;
    private long translationRevision;
    private volatile PlacementSnapshot placement;
    private int playerX, playerY, previousX, previousY, earlierX, earlierY;
    private boolean havePlayer;
    private int displacement, priorDisplacement, stillTicks;
    private boolean travelling, followingRun;
    private int travelSide, followDx, followDy, movingTicks, placementHoldTicks;
    private LocalPoint playerDestination;
    private long settledSinceTick = -1, nextIdleShiftTick = -1, nextIdleFacingTick;
    private long lastVoluntaryMovementTick = -1, planningCount;
    private long lastInspectionStartTick = -1;
    private boolean idleShift, sceneMotionVoluntary, segmentVoluntary, segmentIdleShift, idleMovementSuppressed;
    private int idleStrollEdges = 1;
    private int idleStrollTieBreak;
    private int idleFacing;
    private Intent intent = Intent.NORMAL;
    private boolean intentRun, turnAway;
    private int runBurstRemaining = -1;
    private int minDistance = 2, maxDistance = 5;
    private int destinationWorldX, destinationWorldY;
    private LocalPoint facingTarget;
    private RoutePlanner.Status lastPlannerStatus;
    private boolean lastPlanConfirmedBlocked;
    private int lastPlannerVisited, lastPlannerSteps;
    private boolean permitPlayerTransit;
    private boolean recoveryAllowed = true;
    private long decisionTick, recoveryCount, observedCompletedEdges;
    private long lastDecisionTick = Long.MIN_VALUE;
    private long lastPlanningTick = Long.MIN_VALUE;
    private int previousCollisionHash;
    private boolean combatKnown, combatConfirmed, preferCombatAvoidance;
    private LocalPoint combatTarget, interactionTarget, oldCombatTarget;
    private int combatSize, combatDecisionTick = -8, combatGoneTicks;
    private boolean interactionHolding;
    private int interactionReleaseTicks;
    private ActorOccupancy.Snapshot occupancy;
    private boolean crowds;
    private InspectionRequest inspectionRequest;
    private InspectionRouteProof inspectionProof;
    private boolean inspectionDestination, inspectionDestinationFailed;
    private long inspectionStartEdges;
    private long inspectionSourceCandidate = -1, returnSourceCandidate = -1;
    private MotionObservation motionAdmission;
    private long motionAdmissionCandidate = -1, motionAdmittedTick, motionHeldTick;
    private long motionResumeTick = -1;
    private WorldView motionAdmissionView;
    private Scene motionAdmissionScene;
    private boolean motionOwnInvalidation, motionOwnedHold;

    @Inject
    public NativeActor(Client client, Gson gson)
    {
        this(client, gson, new Random());
    }

    NativeActor(Client client, Gson gson, Random random)
    {
        this.client = client;
        this.gson = gson;
        this.random = random;
    }

    /** Parse once off the client thread. A disposed older session cannot publish into a new actor. */
    public void loadResources() throws IOException
    {
        NativeAsset loaded = NativeAsset.load(gson);
        if (!disposed)
        {
            decodedAsset = loaded;
        }
    }

    public void onClientTick()
    {
        if (disposed)
        {
            return;
        }
        int cycle = client.getGameCycle();
        if (cycle == lastCycle)
        {
            return;
        }
        lastCycle = cycle;
        if (bank == null && decodedAsset != null)
        {
            bank = new NativePoseBank(client, decodedAsset, heightPercent);
        }
        if (bank != null && !bank.isReady())
        {
            bank.advance(cycle);
        }
        if (!visible)
        {
            return;
        }
        Player player = client.getLocalPlayer();
        if (client.getGameState() != GameState.LOGGED_IN || !matchesScene(player))
        {
            hide();
            return;
        }
        SceneCollision collision = collision(permitPlayerTransit);
        if (!collision.isAvailable())
        {
            hide();
            return;
        }
        RoutePlanner.Node before = motion.completedNode(), next = motion.nextUnenteredNode();
        boolean inFlight = motion.isSegmentInFlight(), wasBlocked = motion.isBlocked(), running = motion.isRunning();
        long edges = motion.getCompletedEdges();
        if (next != null && !collision.hasEdgeData(before.x, before.y, next.x - before.x, next.y - before.y))
        {
            hide();
            return;
        }
        if (next != null && intent == Intent.NORMAL && travelling && !combatConfirmed && !interactionHolding
            && !FollowCollision.allowsStep(before.x, before.y, next.x, next.y, playerX, playerY))
        {
            motion.stop();
            motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
            revokeMotionAdmission();
        }
        double beforeX = motion.getX(), beforeY = motion.getY();
        motion.advance(cycle, collision);
        recordTranslation(beforeX, beforeY);
        if (!inFlight && motion.isSegmentInFlight())
        {
            motionRecorder.segmentStarted();
            segmentVoluntary = intent != Intent.NORMAL && sceneMotionVoluntary;
            segmentIdleShift = idleShift;
        }
        if (motion.getCompletedEdges() != edges)
        {
            RoutePlanner.Node reached = motion.completedNode();
            motionRecorder.completedEdge(decisionTick + 1, baseX + before.x, baseY + before.y,
                baseX + reached.x, baseY + reached.y, running);
            if (segmentVoluntary) lastVoluntaryMovementTick = decisionTick + 1;
            segmentVoluntary = false;
            if (segmentIdleShift && !motion.isMoving())
            {
                if (intent == Intent.NORMAL)
                {
                    idleFacing = MotionMath.bearing(playerX * 128 + 64 - motion.getX(),
                        playerY * 128 + 64 - motion.getY());
                }
                idleShift = false;
                settledSinceTick = motion.isMoving() ? -1 : decisionTick + 1;
                nextIdleShiftTick = decisionTick + 1 + sampleRestTicks();
                Creature arrivedCreature = strollCreature == null ? null : creatureIntake.current(client, view, strollCreature.key);
                if (intent == Intent.NORMAL && livingWorldCreatureAllowed && !combatConfirmed && !idleMovementSuppressed
                    && !isCreatureSafetyActive() && arrivedCreature != null
                    && arrivedCreature.classification.behaviour == Behaviour.FOND
                    && arrivedCreature.distance(baseX + reached.x, baseY + reached.y) == 1)
                {
                    creatureStrollArrival = restingCreature = arrivedCreature;
                    creatureStrollArrivalTick = decisionTick + 1;
                    strollCreature = null;
                }
            }
            segmentIdleShift = false;
        }
        if (!wasBlocked && motion.isBlocked() && next != null)
        {
            revokeMotionAdmission();
            SceneCollision physical = collision(true);
            if (physical.hasEdgeData(before.x, before.y, next.x - before.x, next.y - before.y)
                && (!physical.isLegal(next.x, next.y) || !physical.canTravel(before.x, before.y, next.x - before.x, next.y - before.y)))
            {
                motionRecorder.blocked(decisionTick + 1, baseX + before.x, baseY + before.y, baseX + next.x, baseY + next.y);
            }
            if (idleShift)
            {
                idleShift = false;
                nextIdleShiftTick = decisionTick + 1 + 12 + random.nextInt(25);
                settledSinceTick = decisionTick + 1;
                motion.setRoute(Collections.emptyList(), false);
            }
        }
        // H.3: every change of facing is a turn. While she follows him the way she walks is kept for a moment
        // after each step, so a gap between two steps does not turn her towards him and back.
        boolean moving = motion.isMoving();
        if (moving) { lastMoveBearing = motion.getBearing(); lastMovingCycle = cycle; }
        boolean betweenSteps = !moving && travelling && intent == Intent.NORMAL && lastMovingCycle != Integer.MIN_VALUE
            && cycle - lastMovingCycle >= 0 && cycle - lastMovingCycle < MOVE_FACING_HOLD_CYCLES;
        int desired = moving || betweenSteps ? lastMoveBearing : idleBearing();
        lastDesiredFacing = desired;
        orientation = MotionMath.turn(orientation, desired);
        if (hasEmotes())
        {
            applyPosture(cycle);
            return;
        }
        String nextClip = motion.isMoving() ? motion.isRunning() ? "RUN" : "WALK" : "IDLE";
        if (!nextClip.equals(clip))
        {
            clip = nextClip;
            clipCycle = 0;
        }
        else
        {
            clipCycle++;
        }
        applyPose();
    }

    /** Presence owns stable-scene timing; this decision is called exactly once per committed GameTick. */
    public void onGameTick(boolean allowPresence)
    {
        onGameTick(allowPresence, decisionTick + 1);
    }

    public void onGameTick(boolean allowPresence, long committedTick)
    {
        if (committedTick <= lastDecisionTick) return;
        lastDecisionTick = decisionTick = committedTick;
        creatureScenePreempted = false;
        if (foldedUntilTick >= 0 && committedTick >= foldedUntilTick) clearSpeechPosture();
        if (intent == Intent.NORMAL && motionResumeTick >= 0 && committedTick > motionResumeTick) motionResumeTick = -1;
        motionRecorder.commit(committedTick);
        if (disposed || !allowPresence || client.getGameState() != GameState.LOGGED_IN)
        {
            hide();
            return;
        }
        Player player = client.getLocalPlayer();
        if (player == null || player.getWorldView() == null || player.getLocalLocation() == null || !isReady())
        {
            hide();
            return;
        }
        WorldView current = player.getWorldView();
        LocalPoint playerPoint = player.getLocalLocation();
        playerDestination = client.getLocalDestinationLocation();
        if (current.getScene() == null || playerPoint.getWorldView() != current.getId())
        {
            hide();
            return;
        }
        if (visible && !matchesScene(player))
        {
            hide();
        }
        if (!visible)
        {
            captureScene(current);
            playerX = playerPoint.getSceneX();
            playerY = playerPoint.getSceneY();
            SceneCollision collision = collision(true);
            if (!collision.isLegal(playerX, playerY) || !validHeightData(playerPoint))
            {
                return;
            }
            keepAway = keepAwayFrom(allowedCreatures(creatureIntake.sample(client, view, decisionTick)),
                baseX + playerX, baseY + playerY);
            spawn(player, collision);
            return;
        }
        playerX = playerPoint.getSceneX();
        playerY = playerPoint.getSceneY();
        priorDisplacement = displacement;
        displacement = havePlayer ? MotionMath.distance(playerX, playerY, previousX, previousY) : 0;
        int dx = havePlayer ? playerX - previousX : 0, dy = havePlayer ? playerY - previousY : 0;
        boolean adjacentReversal = havePlayer && playerX == earlierX && playerY == earlierY && displacement == 1;
        earlierX = previousX;
        earlierY = previousY;
        previousX = playerX;
        previousY = playerY;
        havePlayer = true;
        stillTicks = displacement == 0 ? stillTicks + 1 : 0;
        if (displacement > 0)
        {
            settledSinceTick = -1;
            if (idleShift)
            {
                idleShift = false;
                motion.stop();
            }
        }
        int separation = getDistanceToPlayer();
        occupancy = ActorOccupancy.sample(client, view, player, playerX, playerY);
        crowds = occupancy.complete && occupancy.otherPlayers >= 8;
        updateCreatures();
        if (isCreatureSafetyActive() && intent != Intent.NORMAL && !sceneEmergency)
        {
            revokeMotionAdmission(); motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
            motion.stop(); intent = Intent.NORMAL; intentRun = turnAway = false; facingTarget = null;
            idleShift = sceneMotionVoluntary = inspectionDestination = inspectionDestinationFailed = false;
            inspectionRequest = null; inspectionProof = null; runBurstRemaining = -1;
            inspectionSourceCandidate = returnSourceCandidate = -1;
            creatureScenePreempted = true;
        }
        updateInteractionHold(player);
        if (interactionHolding) revokeMotionAdmission();
        if (idleShift && (combatConfirmed || interactionHolding || idleMovementSuppressed || isCreatureSafetyActive()))
        {
            idleShift = false;
            motion.stop();
        }
        publishMotionArrival();
        if (combatGoneTicks > 0) combatGoneTicks--;
        if (runBurstRemaining >= 0)
        {
            if (runBurstRemaining == 0)
            {
                intentRun = false;
                motion.setRunning(false);
            }
            else runBurstRemaining--;
        }
        RoutePlanner.Node planningNode = motion.planningNode();
        SceneCollision currentCollision = collision(true);
        if (!currentCollision.isAvailable() || planningNode == null)
        {
            hide();
            return;
        }
        boolean progress = motion.getCompletedEdges() != observedCompletedEdges;
        observedCompletedEdges = motion.getCompletedEdges();
        int collisionHash = currentCollision.neighborhoodFingerprint(planningNode.x, planningNode.y);
        boolean meaningfulChange = displacement > 0 || collisionHash != previousCollisionHash;
        previousCollisionHash = collisionHash;
        WholeBoundsVisibility.Visibility visibility = getWholeBoundsVisibility();
        boolean confirmedBlocked = motion.isBlocked() || lastPlanConfirmedBlocked;
        int previousOffScreenTicks = recovery.getOffScreenTicks(), previousBlockedTicks = recovery.getBlockedTicks();
        int previousIneffectivePlans = recovery.getIneffectivePlans();
        long previousOffScreenEpisodeId = offScreenEpisodeId, previousOffScreenEpisodeTick = offScreenEpisodeTick;
        recovery.commit(separation, visibility, progress, confirmedBlocked, intent == Intent.NORMAL, true);
        if (recovery.getOffScreenTicks() == 0)
        {
            offScreenEpisodeId = 0;
            offScreenEpisodeTick = -1;
        }
        else if (previousOffScreenTicks == 0)
        {
            offScreenEpisodeId = ++recoveryEpisodeSequence;
            offScreenEpisodeTick = decisionTick;
        }
        if (recovery.getBlockedTicks() == 0)
        {
            blockedEpisodeId = 0;
            blockedEpisodeTick = -1;
        }
        else if (previousBlockedTicks == 0)
        {
            blockedEpisodeId = ++recoveryEpisodeSequence;
            blockedEpisodeTick = decisionTick;
        }
        if (previousOffScreenTicks > 0 && visibility != WholeBoundsVisibility.Visibility.OFF_SCREEN)
        {
            RecoveryObservation.Builder observation = recoveryFrame(RecoveryObservation.Cause.OFF_SCREEN,
                visibility == WholeBoundsVisibility.Visibility.VISIBLE ? RecoveryObservation.Disposition.ORIGIN_VISIBLE
                    : RecoveryObservation.Disposition.ORIGIN_UNKNOWN,
                separation, visibility, previousOffScreenEpisodeId, previousOffScreenEpisodeTick);
            observation.offScreenTicksBefore = previousOffScreenTicks;
            observation.blockedTicksBefore = previousBlockedTicks;
            observation.ineffectivePlansBefore = previousIneffectivePlans;
            publishRecovery(observation);
        }
        boolean recoveryRequested = intent == Intent.NORMAL && recoveryAllowed
            && (recovery.offScreenEligible(decisionTick, separation) || recovery.stuckEligible(decisionTick));
        if (recoveryRequested)
        {
            if (recovery.mayPlan(decisionTick, meaningfulChange))
            {
                recover(player, recovery.stuckEligible(decisionTick), separation, visibility);
            }
            else
            {
                motion.stop();
            }
            return;
        }
        movingTicks = displacement > 0 ? adjacentReversal ? 1 : Math.min(2, movingTicks + 1) : 0;
        boolean consecutiveProgress = movingTicks >= 2 && separation >= 2 && separation <= 5;
        boolean follow = stillTicks < 2 && (separation >= 6 || consecutiveProgress
            || travelling && separation > 2 && !adjacentReversal);
        if (follow && !travelling)
        {
            RoutePlanner.Node standing = motion.planningNode();
            travelSide = standing == null ? 0 : Integer.signum((standing.x - playerX) * dy - (standing.y - playerY) * dx);
        }
        travelling = follow;
        if (travelling && (dx != 0 || dy != 0))
        {
            followDx = dx;
            followDy = dy;
        }
        else if (!travelling) followDx = followDy = 0;
        if (displacement >= 2 || priorDisplacement >= 2)
        {
            followingRun = true;
        }
        else if (separation <= 5)
        {
            followingRun = false;
        }
        if (stillTicks >= 2 && intent == Intent.NORMAL) motion.setRunning(false);
        if (intent != Intent.NORMAL || combatConfirmed || interactionHolding) settledSinceTick = -1;
        if (placementHoldTicks > 0)
        {
            placementHoldTicks--;
            motion.stop();
            return;
        }
        if (intent == Intent.HOLD)
        {
            motion.stop();
            return;
        }
        if (inspectionDestinationFailed)
        {
            motion.stop();
            return;
        }
        if (inspectionRequest != null && inspectionEligible())
        {
            proveInspectionRoute();
            return;
        }
        if (intent == Intent.NORMAL && combatConfirmed)
        {
            motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
            if (!validCombatTarget())
            {
                motion.stop();
                return;
            }
            if (separation >= 3 && separation <= 6 && !motion.isBlocked()
                && combatDistance(motion.planningNode().x, motion.planningNode().y) > 1
                && !keepAway.inside(motion.planningNode().x, motion.planningNode().y)
                && collision(true).isLegal(motion.planningNode().x, motion.planningNode().y)
                && (!isCreatureSafetyActive() || isFarSide(motion.planningNode(), creatureAwareness.getTarget()))
                && decisionTick - combatDecisionTick < 8)
            {
                return;
            }
        }
        else if (intent == Intent.NORMAL && interactionHolding && separation >= 2 && separation <= 4
            && !isCreatureSafetyActive())
        {
            motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
            motion.stop();
            return;
        }
        if (intent == Intent.NORMAL && !travelling && !combatConfirmed
            && separation <= 6 && isCreatureSafetyActive())
        {
            planCreatureSafety();
            return;
        }
        boolean idleShiftPlanning = false;
        RoutePlanner.Node nextNode = motion.nextUnenteredNode();
        boolean nextTileIllegal = nextNode != null && (!currentCollision.isLegal(nextNode.x, nextNode.y)
            || !currentCollision.canTravel(planningNode.x, planningNode.y, nextNode.x - planningNode.x, nextNode.y - planningNode.y));
        boolean standingOnPath = playerDestination != null && playerDestination.getWorldView() == view.getId()
            && (playerDestination.getSceneX() == planningNode.x && playerDestination.getSceneY() == planningNode.y
                || nextNode != null && playerDestination.getSceneX() == nextNode.x && playerDestination.getSceneY() == nextNode.y);
        int planningDistance = MotionMath.distance(planningNode.x, planningNode.y, playerX, playerY);
        if (intent == Intent.NORMAL && !combatConfirmed && !interactionHolding
            && planningDistance >= 1 && planningDistance <= 5 && (!travelling || separation <= 2) && !motion.isBlocked()
            && currentCollision.isLegal(planningNode.x, planningNode.y) && !standingOnPath)
        {
            if (idleShift && displacement == 0 && !idleMovementSuppressed)
            {
                return;
            }
            motion.stop();
            if (motion.isMoving()) return;
            if (stillTicks >= 2 && settledSinceTick < 0)
            {
                settledSinceTick = decisionTick;
                nextIdleShiftTick = decisionTick + 8 + random.nextInt(13);
            }
            idleShiftPlanning = stillTicks >= 2 && settledSinceTick >= 0
                && decisionTick >= nextIdleShiftTick && !idleMovementSuppressed;
            if (!idleShiftPlanning)
            {
                return;
            }
        }
        else if (intent == Intent.NORMAL && !idleShift) settledSinceTick = -1;
        if (intent == Intent.NORMAL && separation > 6 && !recovery.mayPlan(decisionTick, meaningfulChange))
        {
            motion.stop();
            return;
        }
        boolean stepAside = intent == Intent.NORMAL && stillTicks >= 2
            && motion.completedNode() != null && motion.completedNode().x == playerX && motion.completedNode().y == playerY;
        int low = intent == Intent.APPROACH ? minDistance : stepAside ? 1 : combatConfirmed ? 3 : 2;
        int high = intent == Intent.APPROACH ? maxDistance : stepAside ? 1 : combatConfirmed ? 6
            : interactionHolding ? 4 : idleShiftPlanning || intent == Intent.NORMAL && stillTicks >= 2 && planningDistance > 5 ? 5
            : travelling ? 3 : 2;
        boolean run = intent == Intent.APPROACH ? intentRun : stillTicks < 2 && (travelling || separation >= 10) && followingRun;
        if (idleShiftPlanning)
        {
            restingCreature = null;
            int choice = random.nextInt(100);
            idleStrollEdges = choice < 35 ? 1 : choice < 70 ? 2 : choice < 90 ? 3 : 4;
            idleStrollTieBreak = random.nextInt();
            low = 2;
            high = 4;
            run = false;
        }
        RoutePlanner.Node start = motion.planningNode();
        if (start == null)
        {
            return;
        }
        boolean allowPlayer = permitPlayerTransit || start.x == playerX && start.y == playerY;
        SceneCollision collision = collision(allowPlayer);
        MotionObservation priorMotion = motionRecorder.current();
        boolean retainRoamGoal = motion.isBlocked() && intent == Intent.NORMAL && !travelling && !combatConfirmed
            && !interactionHolding && priorMotion != null && priorMotion.purpose == MotionObservation.Purpose.ROAM
            && priorMotion.fixedGoal && priorMotion.legalGoal
            && MotionMath.distance(priorMotion.goalWorldX - baseX, priorMotion.goalWorldY - baseY, playerX, playerY) >= low
            && MotionMath.distance(priorMotion.goalWorldX - baseX, priorMotion.goalWorldY - baseY, playerX, playerY) <= high;
        RoutePlanner.Goal goal = intent == Intent.DESTINATION ? destinationGoal()
            : retainRoamGoal ? fixedGoal(priorMotion.goalWorldX - baseX, priorMotion.goalWorldY - baseY)
            : playerBandGoal(low, high, travelling ? followDx : 0, travelling ? followDy : 0, player, false,
                idleShiftPlanning, !motion.isBlocked() && !nextTileIllegal && !standingOnPath && !isCreatureSafetyActive());
        if (intent == Intent.NORMAL && isCreatureSafetyActive() && creatureAwareness.isKnown())
        {
            goal = farSideGoal(goal, creatureAwareness.getTarget());
        }
        else if (idleShiftPlanning && strollCreature != null)
        {
            goal = strollCreatureGoal(goal, strollCreature);
        }
        if (intent == Intent.DESTINATION)
        {
            run = intentRun;
        }
        boolean calmFollowing = intent == Intent.NORMAL && travelling && !combatConfirmed && !interactionHolding && !stepAside;
        RoutePlanner.Result result = planRoute(calmFollowing ? new FollowCollision(collision, playerX, playerY) : collision, start, goal);
        rememberResult(result);
        noteKeepAwayOutcome(result, separation);
        if (calmFollowing && result.status == RoutePlanner.Status.BLOCKED)
        {
            SceneCollision physical = collision(true);
            for (int ex = -1; ex <= 1; ex++)
            {
                for (int ey = -1; ey <= 1; ey++)
                {
                    if ((ex != 0 || ey != 0) && physical.canTravel(start.x, start.y, ex, ey)) lastPlanConfirmedBlocked = false;
                }
            }
        }
        if (idleShiftPlanning && (result.status != RoutePlanner.Status.ROUTE || result.steps.size() != idleStrollEdges))
        {
            nextIdleShiftTick = decisionTick + 12 + random.nextInt(25);
            motion.stop();
            return;
        }
        if (result.status != RoutePlanner.Status.ROUTE && result.status != RoutePlanner.Status.AT_GOAL) revokeMotionAdmission();
        if (inspectionDestination && !inspectionPlanFits(result))
        {
            inspectionDestinationFailed = true;
            motion.stop();
            motionRecorder.cancel(MotionObservation.Status.TARGET_LOST);
            return;
        }
        if (intent == Intent.NORMAL && combatConfirmed) combatDecisionTick = (int) decisionTick;
        recovery.recordPlan(decisionTick, lastPlanConfirmedBlocked,
            result.status == RoutePlanner.Status.ROUTE || result.status == RoutePlanner.Status.AT_GOAL);
        permitPlayerTransit = result.status == RoutePlanner.Status.BLOCKED || allowPlayer && !result.steps.isEmpty();
        RoutePlanner.Node endpoint = result.steps.isEmpty() ? start : result.steps.get(result.steps.size() - 1);
        int receiptGoalX = retainRoamGoal ? priorMotion.goalWorldX : baseX + endpoint.x;
        int receiptGoalY = retainRoamGoal ? priorMotion.goalWorldY : baseY + endpoint.y;
        MotionObservation.Purpose purpose = intent == Intent.APPROACH && returnSourceCandidate >= 0
            ? MotionObservation.Purpose.APPROACH_PLAYER
            : intent != Intent.NORMAL || combatConfirmed || interactionHolding || stepAside
            ? MotionObservation.Purpose.OTHER
            : idleShiftPlanning ? MotionObservation.Purpose.ROAM
            : travelling || separation > high || priorMotion != null && priorMotion.purpose == MotionObservation.Purpose.CATCHUP
                && (priorMotion.status == MotionObservation.Status.MOVING || priorMotion.status == MotionObservation.Status.BLOCKED
                    || priorMotion.status == MotionObservation.Status.BUDGET_EXHAUSTED) ? MotionObservation.Purpose.CATCHUP
            : !travelling && separation >= low && separation <= high
                ? MotionObservation.Purpose.ROAM : MotionObservation.Purpose.OTHER;
        RoutePlanner.Node completed = motion.completedNode();
        long sourceCandidate = inspectionDestination ? inspectionSourceCandidate
            : intent == Intent.APPROACH ? returnSourceCandidate : -1;
        motionRecorder.plan(purpose, sourceCandidate, intent.ordinal(), decisionTick, placementGeneration, view.getId(), plane,
            baseX + completed.x, baseY + completed.y, receiptGoalX, receiptGoalY,
            MotionMath.distance(completed.x, completed.y, playerX, playerY), motion.isSegmentInFlight(), result);
        motion.setRoute(result.steps, run);
        if (idleShiftPlanning) idleShift = true;
    }

    /** While the living world is off only rats and beasts (E.5) count, and whatever she is already keeping away from. */
    private Sample allowedCreatures(Sample sample)
    {
        if (livingWorldCreatureAllowed) { return sample; }
        List<Creature> allowed = new ArrayList<>();
        for (Creature creature : sample.creatures)
        {
            if ("F01".equals(creature.classification.groupId) || "F02".equals(creature.classification.groupId)
                || creatureAwareness.isKeepingAway() && creature.classification.groupId.equals(creatureAwareness.getGroupId()))
            {
                allowed.add(creature);
            }
        }
        return new Sample(sample.tick, sample.worldViewId, sample.baseX, sample.baseY, sample.plane, sample.known, allowed);
    }

    /** H.2: the tiles round every creature she is afraid of that is near enough (to the world tile given) to shape a route. */
    private KeepAway keepAwayFrom(Sample sample, int worldX, int worldY)
    {
        if (sample == null || !sample.known || sample.creatures.isEmpty()) { return KeepAway.NONE; }
        KeepAway.Builder zones = new KeepAway.Builder();
        for (Creature creature : sample.creatures)
        {
            if (creature.classification.behaviour != Behaviour.AFRAID || creature.plane != plane
                || creature.distance(worldX, worldY) > KeepAway.CONSIDERED_WITHIN) { continue; }
            int radius = "F01".equals(creature.classification.groupId) ? KeepAway.RAT_RADIUS : KeepAway.BEAST_RADIUS;
            zones.add(creature.worldX - baseX, creature.worldY - baseY, creature.size, radius - keepAwayRelax);
        }
        return zones.build();
    }

    /** H.2: where creatures leave her no way to him she gives ground a tile at a time, rather than be left behind. */
    private void noteKeepAwayOutcome(RoutePlanner.Result result, int separation)
    {
        boolean reached = result.status == RoutePlanner.Status.ROUTE || result.status == RoutePlanner.Status.AT_GOAL;
        boolean rested = keepAwayRelaxedTick == Long.MIN_VALUE || decisionTick - keepAwayRelaxedTick >= 20;
        if (keepAway.isEmpty() || reached && separation <= 3)
        {
            if (keepAwayRelax != 0 && rested) { keepAwayRelax = 0; }
            return;
        }
        // Only while he is on the move. If he stands among them she waits where she is (P36).
        if (!reached && separation >= 7 && stillTicks < 4 && keepAwayRelax < KeepAway.MAX_RELAX
            && (keepAwayRelaxedTick == Long.MIN_VALUE || decisionTick - keepAwayRelaxedTick >= 3))
        {
            keepAwayRelax++; keepAwayRelaxedTick = decisionTick;
        }
    }

    private void updateCreatures()
    {
        boolean endingE5 = isE5KeepingAway();
        creatureSample = creatureIntake.sample(client, view, decisionTick);
        Sample selected = allowedCreatures(creatureSample);
        int ownWorldX = baseX + ((int) motion.getX() >> 7), ownWorldY = baseY + ((int) motion.getY() >> 7);
        creatureAwareness.commit(selected, ownWorldX, ownWorldY);
        keepAway = keepAwayFrom(selected, ownWorldX, ownWorldY);
        // A creature that has wandered onto the way she was going: she stops short of it and thinks again.
        if (!keepAway.isEmpty() && motion.planningNode() != null
            && keepAway.firstRefused(motion.planningNode(), motion.remainingSteps()) >= 0)
        {
            motion.stop(); creatureReplanRequested = true;
        }
        if (creatureAwareness.didEndKeepingAway() && (endingE5 || livingWorldCreatureAllowed))
        {
            creatureShortRestPending = true; creatureShortRestE5 |= endingE5;
        }
        if (creatureShortRestPending && (creatureShortRestE5 || livingWorldCreatureAllowed)
            && intent == Intent.NORMAL && !travelling && displacement == 0 && stillTicks >= 2)
        {
            motion.stop(); creatureReplanRequested = false;
            settledSinceTick = decisionTick;
            nextIdleShiftTick = decisionTick + 12 + random.nextInt(25);
            creatureShortRestPending = creatureShortRestE5 = false;
        }
        if (isCreatureSafetyActive() && displacement > 0) { creatureReplanRequested = true; }
        if (displacement > 0 || intent != Intent.NORMAL || !livingWorldCreatureAllowed || combatConfirmed
            || isCreatureSafetyActive()) { timedFacingCreature = restingCreature = null; }
        if (timedFacingCreature != null)
        {
            timedFacingCreature = decisionTick >= creatureFacingUntilTick ? null : currentCreature(timedFacingCreature.key);
        }
        if (restingCreature != null) { restingCreature = currentCreature(restingCreature.key); }
        Creature target = creatureAwareness.getTarget();
        if (livingWorldCreatureAllowed && !isCreatureSafetyActive() && target != null
            && (target.classification.behaviour == Behaviour.DISGUST || target.classification.behaviour == Behaviour.FOND))
        {
            strollCreature = target;
        }
        else if (!idleShift) { strollCreature = null; }
    }

    private Creature currentCreature(String key)
    {
        if (creatureSample == null || !creatureSample.known) { return null; }
        for (Creature creature : creatureSample.creatures) { if (creature.key.equals(key)) { return creature; } }
        return null;
    }

    private void planCreatureSafety()
    {
        settledSinceTick = -1;
        if (!creatureAwareness.isKnown()) { motion.stop(); return; }
        Creature target = creatureAwareness.getTarget();
        int distance = target.distance(baseX + ((int) motion.getX() >> 7), baseY + ((int) motion.getY() >> 7));
        boolean run = target.classification.behaviour == Behaviour.AFRAID;
        motion.setRunning(run);
        RoutePlanner.Node start = motion.planningNode();
        // Standing where a creature has come too near, she does not wait for the usual four ticks.
        boolean cornered = start != null && keepAway.inside(start.x, start.y);
        if (creaturePlanBeginTick != creatureAwareness.getBeginTick() || distance <= 3 || cornered) { creatureReplanRequested = true; }
        if (!creatureReplanRequested || !cornered && lastCreaturePlanTick != Long.MIN_VALUE
            && decisionTick - lastCreaturePlanTick < 4) { return; }
        if (start == null) { return; }
        lastCreaturePlanTick = decisionTick; creaturePlanBeginTick = creatureAwareness.getBeginTick();
        creatureReplanRequested = false;
        RoutePlanner.Goal band = safetyBandGoal(target.classification.minPlayerDistance, target.classification.maxPlayerDistance);
        RoutePlanner.Result result = planRoute(collision(true), start, run ? hidingGoal(target) : farSideGoal(band, target));
        rememberResult(result);
        if (result.status != RoutePlanner.Status.ROUTE && result.status != RoutePlanner.Status.AT_GOAL
            && !(run && result.status == RoutePlanner.Status.FRONTIER && !result.steps.isEmpty())) { motion.stop(); return; }
        RoutePlanner.Node endpoint = result.steps.isEmpty() ? start : result.steps.get(result.steps.size() - 1);
        RoutePlanner.Node completed = motion.completedNode();
        motionRecorder.plan(MotionObservation.Purpose.OTHER, -1, intent.ordinal(), decisionTick, placementGeneration,
            view.getId(), plane, baseX + completed.x, baseY + completed.y, baseX + endpoint.x, baseY + endpoint.y,
            getDistanceToPlayer(), motion.isSegmentInFlight(), result);
        permitPlayerTransit = true;
        motion.setRoute(result.steps, run);
    }

    /** H.2: behind him first and as near as this kind of creature allows; failing that, the nearest ground she may stand on. */
    private RoutePlanner.Goal hidingGoal(Creature creature)
    {
        int low = creature.classification.minPlayerDistance, high = creature.classification.maxPlayerDistance;
        int awayX = playerX - (creature.worldX - baseX), awayY = playerY - (creature.worldY - baseY);
        double awayLength = Math.max(1d, Math.hypot(awayX, awayY));
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                if (distance == 0 || distance > 8
                    || playerDestination != null && x == playerDestination.getSceneX() && y == playerDestination.getSceneY()) { return -1; }
                boolean band = distance >= low && distance <= high;
                // 1 straight behind him as the creature sees him, -1 between the two of them.
                double behind = ((x - playerX) * awayX + (y - playerY) * awayY)
                    / (awayLength * Math.hypot(x - playerX, y - playerY));
                int tier = band ? behind > 0 ? 4 : 2 : behind > 0 ? 1 : 0;
                double closeness = band ? (high - distance + 1d) / (high - low + 1d) : 1d / distance;
                // Within a tier: straight behind him counts most, then how near a way it is, then how close to him.
                // Where she already stands scores a full "near way", so a wandering creature does not keep her shuffling.
                // A tile that is not behind him earns nothing for being close to him: that would draw her towards it.
                return tier + .40 * Math.max(0d, behind) + .30 / (1 + steps) + (behind > 0 ? .25 * closeness : 0d);
            }
            @Override public int distance(int x, int y)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                return distance > high ? distance - high : distance < low ? low - distance : 0;
            }
        };
    }

    private boolean isFarSide(RoutePlanner.Node node, Creature creature)
    {
        return node != null && creature != null && CreatureAwareness.farSide(node.x, node.y, playerX, playerY,
            creature.worldX - baseX, creature.worldY - baseY);
    }

    private RoutePlanner.Goal safetyBandGoal(int low, int high)
    {
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                if (distance < low || distance > high || x == playerX && y == playerY
                    || playerDestination != null && x == playerDestination.getSceneX() && y == playerDestination.getSceneY()) { return -1; }
                return 1d / (1 + steps);
            }
            @Override public int distance(int x, int y)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                return distance > high ? distance - high : distance < low ? low - distance : 0;
            }
        };
    }

    private RoutePlanner.Goal farSideGoal(RoutePlanner.Goal band, Creature creature)
    {
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                double score = band.score(x, y, steps);
                boolean far = CreatureAwareness.farSide(x, y, playerX, playerY, creature.worldX - baseX, creature.worldY - baseY);
                return preferenceScore(score, far);
            }
            @Override public double refineScore(int x, int y, int steps, double score)
            {
                double refined = band.refineScore(x, y, steps, band.score(x, y, steps));
                return preferenceScore(refined, CreatureAwareness.farSide(x, y, playerX, playerY,
                    creature.worldX - baseX, creature.worldY - baseY));
            }
            @Override public int distance(int x, int y) { return band.distance(x, y); }
        };
    }

    private RoutePlanner.Goal strollCreatureGoal(RoutePlanner.Goal ordinary, Creature creature)
    {
        boolean fond = creature.classification.behaviour == Behaviour.FOND;
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                boolean preferred = preferred(x, y, steps);
                double base = fond && preferred ? 1d / (1 + steps) : ordinary.score(x, y, steps);
                return preferenceScore(base, preferred);
            }
            private boolean preferred(int x, int y, int steps)
            {
                return fond ? steps == idleStrollEdges && creature.distance(baseX + x, baseY + y) == 1
                    && MotionMath.distance(x, y, playerX, playerY) <= 5 && !(x == playerX && y == playerY)
                    && !(playerDestination != null && x == playerDestination.getSceneX() && y == playerDestination.getSceneY())
                    : creature.distance(baseX + x, baseY + y) >= 4;
            }
            @Override public double refineScore(int x, int y, int steps, double score)
            {
                boolean preferred = preferred(x, y, steps);
                double base = fond && preferred ? 1d / (1 + steps) : ordinary.score(x, y, steps);
                return preferenceScore(ordinary.refineScore(x, y, steps, base), preferred);
            }
            @Override public int distance(int x, int y) { return fond ? Math.max(0, creature.distance(baseX + x, baseY + y) - 1) : ordinary.distance(x, y); }
        };
    }

    /** The preferred bucket always outranks the fallback bucket in the same bounded search. */
    private static double preferenceScore(double score, boolean preferred)
    {
        return score < 0 ? -1 : (preferred ? 2 : 0) + score / (1 + score);
    }

    private int sampleRestTicks()
    {
        return random.nextInt(10) < 7 ? 12 + random.nextInt(25) : 60 + random.nextInt(81);
    }

    private void spawn(Player player, SceneCollision collision)
    {
        RoutePlanner.Result result = planRoute(collision, new RoutePlanner.Node(playerX, playerY),
            playerBandGoal(everSpawned ? 1 : 2, everSpawned ? 2 : 5, 0, 0, player, true));
        rememberResult(result);
        if (result.status != RoutePlanner.Status.ROUTE || result.steps.isEmpty())
        {
            return;
        }
        RoutePlanner.Node tile = result.steps.get(result.steps.size() - 1);
        placeMotion(tile.x, tile.y);
        previousX = earlierX = playerX;
        previousY = earlierY = playerY;
        havePlayer = true;
        orientation = MotionMath.bearing(playerX - tile.x, playerY - tile.y);
        idleFacing = orientation;
        nextIdleFacingTick = decisionTick + 8 + random.nextInt(13);
        clip = "IDLE";
        clipCycle = 0;
        posturePlayback.reset(client.getGameCycle());
        placementHoldTicks = everSpawned ? 2 : 0;
        placementGeneration++;
        if (!applyPose())
        {
            return;
        }
        client.registerRuneLiteObject(controller);
        visible = true;
        everSpawned = true;
        observedCompletedEdges = motion.getCompletedEdges();
    }

    private void recover(Player player, boolean stuckRecovery, int separation, WholeBoundsVisibility.Visibility visibility)
    {
        recoveryObservation = null;
        RecoveryObservation.Builder observation = recoveryFrame(
            stuckRecovery ? RecoveryObservation.Cause.P15_STUCK : RecoveryObservation.Cause.OFF_SCREEN,
            RecoveryObservation.Disposition.SEARCH_FAILED, separation, visibility,
            stuckRecovery ? blockedEpisodeId : offScreenEpisodeId,
            stuckRecovery ? blockedEpisodeTick : offScreenEpisodeTick);
        SceneCollision fromPlayer = collision(true);
        RoutePlanner.Result result = planRoute(fromPlayer, new RoutePlanner.Node(playerX, playerY),
            playerBandGoal(2, 4, 0, 0, player, true));
        rememberResult(result);
        observation.plannerStatus = result.status;
        observation.visitedCells = result.visited;
        observation.plannedSteps = result.steps.size();
        observation.searchLimited = result.searchLimited;
        if (result.status != RoutePlanner.Status.ROUTE || result.steps.isEmpty())
        {
            recovery.recordPlan(decisionTick, true, false);
            motion.stop();
            publishRecovery(observation);
            return;
        }
        RoutePlanner.Node tile = result.steps.get(result.steps.size() - 1);
        observation.destinationKnown = true;
        observation.destinationWorldX = baseX + tile.x;
        observation.destinationWorldY = baseY + tile.y;
        if (!matchesScene(client.getLocalPlayer()))
        {
            return;
        }
        observation.destinationRechecked = true;
        observation.destinationLegal = fromPlayer.isLegal(tile.x, tile.y);
        if (!observation.destinationLegal)
        {
            observation.disposition = RecoveryObservation.Disposition.DESTINATION_INVALID;
            publishRecovery(observation);
            return;
        }
        observation.originRechecked = true;
        observation.originRecheckVisibility = getWholeBoundsVisibility();
        if (!recovery.confirmOrigin(observation.originRecheckVisibility, stuckRecovery))
        {
            observation.disposition = observation.originRecheckVisibility == WholeBoundsVisibility.Visibility.VISIBLE
                ? RecoveryObservation.Disposition.ORIGIN_VISIBLE : RecoveryObservation.Disposition.ORIGIN_UNKNOWN;
            publishRecovery(observation);
            return;
        }
        LocalPoint point = LocalPoint.fromScene(tile.x, tile.y, view);
        observation.heightRechecked = true;
        observation.heightValid = validHeightData(point);
        if (!observation.heightValid)
        {
            observation.disposition = RecoveryObservation.Disposition.HEIGHT_UNAVAILABLE;
            publishRecovery(observation);
            return;
        }
        client.removeRuneLiteObject(controller);
        motionRecorder.clear();
        visible = false;
        placementGeneration++;
        placeMotion(tile.x, tile.y);
        segmentVoluntary = segmentIdleShift = false;
        clearSceneMotion();
        orientation = MotionMath.bearing(playerX - tile.x, playerY - tile.y);
        idleFacing = orientation;
        nextIdleFacingTick = decisionTick + 8 + random.nextInt(13);
        clip = "IDLE";
        clipCycle = 0;
        resetPostureForRemoval();
        if (!applyPose())
        {
            return;
        }
        client.registerRuneLiteObject(controller);
        visible = true;
        recoveryCount++;
        recovery.relocated(decisionTick);
        offScreenEpisodeId = blockedEpisodeId = 0;
        offScreenEpisodeTick = blockedEpisodeTick = -1;
        observedCompletedEdges = motion.getCompletedEdges();
        placementHoldTicks = 2;
        observation.disposition = RecoveryObservation.Disposition.RELOCATED;
        publishRecovery(observation);
    }

    private RecoveryObservation.Builder recoveryFrame(RecoveryObservation.Cause cause,
        RecoveryObservation.Disposition disposition, int separation, WholeBoundsVisibility.Visibility visibility,
        long episodeId, long episodeStartedTick)
    {
        RecoveryObservation.Builder observation = new RecoveryObservation.Builder();
        observation.episodeId = episodeId;
        observation.episodeStartedTick = episodeStartedTick;
        observation.committedTick = decisionTick;
        observation.sceneRevision = recoverySceneRevision;
        observation.placementGenerationBefore = placementGeneration;
        observation.worldViewId = view.getId();
        observation.plane = plane;
        observation.baseX = baseX; observation.baseY = baseY;
        observation.sizeX = sizeX; observation.sizeY = sizeY;
        LocalPoint origin = placement.getLocation();
        observation.originLocalX = origin.getX(); observation.originLocalY = origin.getY();
        observation.originWorldX = baseX + origin.getSceneX(); observation.originWorldY = baseY + origin.getSceneY();
        observation.separation = separation;
        observation.offScreenTicksBefore = recovery.getOffScreenTicks();
        observation.blockedTicksBefore = recovery.getBlockedTicks();
        observation.ineffectivePlansBefore = recovery.getIneffectivePlans();
        observation.cause = cause;
        observation.disposition = disposition;
        observation.decisionVisibility = visibility;
        return observation;
    }

    private void publishRecovery(RecoveryObservation.Builder observation)
    {
        observation.observationId = ++recoveryObservationSequence;
        observation.placementGenerationAfter = placementGeneration;
        observation.offScreenTicksAfter = recovery.getOffScreenTicks();
        observation.blockedTicksAfter = recovery.getBlockedTicks();
        observation.ineffectivePlansAfter = recovery.getIneffectivePlans();
        observation.planNotBefore = recovery.getPlanNotBefore();
        observation.retryAt = recovery.getRetryAt();
        if (observation.disposition == RecoveryObservation.Disposition.RELOCATED)
        {
            observation.placedLocalX = placement.getLocation().getX();
            observation.placedLocalY = placement.getLocation().getY();
            observation.drawZ = placement.getDrawZ();
            observation.orientation = placement.getOrientation();
        }
        recoveryObservation = observation.build();
    }

    private boolean inspectionEligible()
    {
        long lastTrip = Math.max(lastInspectionStartTick, lastVoluntaryMovementTick);
        return visible && intent == Intent.NORMAL && !motion.isMoving() && !travelling && !combatConfirmed
            && !idleShift && !idleMovementSuppressed && !isInspectionSuppressed() && stillTicks >= 2
            && (lastTrip < 0 || decisionTick - lastTrip >= 100);
    }

    private void proveInspectionRoute()
    {
        InspectionRequest request = inspectionRequest;
        inspectionRequest = null;
        inspectionProof = null;
        if (request.plane != plane) return;
        RoutePlanner.Result result = planRoute(collision(false), motion.completedNode(),
            InspectionRoutes.besideFootprint(request.minWorldX - baseX, request.minWorldY - baseY,
                request.maxWorldX - baseX, request.maxWorldY - baseY, playerX, playerY));
        rememberResult(result);
        if ((result.status == RoutePlanner.Status.AT_GOAL || result.status == RoutePlanner.Status.ROUTE)
            && result.steps.size() <= InspectionRoutes.MAX_STEPS)
        {
            InspectionRouteProof proof = new InspectionRouteProof(request, this, result);
            LocalPoint stand = LocalPoint.fromScene(proof.standWorldX - baseX, proof.standWorldY - baseY, view);
            if (validHeightData(stand)) inspectionProof = proof;
        }
    }

    private RoutePlanner.Goal playerBandGoal(int low, int high, int dx, int dy, Player player, boolean spawning)
    {
        return playerBandGoal(low, high, dx, dy, player, spawning, false, true);
    }

    private RoutePlanner.Goal playerBandGoal(int low, int high, int dx, int dy, Player player, boolean spawning,
        boolean oneTileShift, boolean keepSide)
    {
        LocalPoint destination = playerDestination;
        int forbiddenX = destination != null && destination.getWorldView() == view.getId() ? destination.getSceneX() : -1;
        int forbiddenY = destination != null && destination.getWorldView() == view.getId() ? destination.getSceneY() : -1;
        int cameraBearing = MotionMath.cameraBearing(client.getCameraYaw());
        int playerBearing = player.getOrientation();
        boolean ignoreOtherPlayers = OtherPlayerPolicy.ignoreOtherPlayers(client);
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                if (distance < low || distance > high || x == playerX && y == playerY || x == forbiddenX && y == forbiddenY
                    || oneTileShift && steps != idleStrollEdges)
                {
                    return -1;
                }
                boolean nearTarget = validCombatTarget() && combatDistance(x, y) <= 1;
                if (nearTarget && !preferCombatAvoidance)
                {
                    return -1;
                }
                if (!spawning && intent == Intent.NORMAL && travelling && keepSide && travelSide != 0
                    && (dx != 0 || dy != 0))
                {
                    int side = Integer.signum((x - playerX) * dy - (y - playerY) * dx);
                    if (side != 0 && side != travelSide) return -1;
                }
                double score = 1d / (1 + steps);
                if (oneTileShift)
                {
                    int hash = (x * 73856093 ^ y * 19349663 ^ idleStrollTieBreak) & 65535;
                    score *= 1 + hash / 655360d;
                }
                if (nearTarget) score *= 1.0e-9;
                if (occupancy != null && occupancy.occupied(x, y)
                    && (occupancy.ignoresOtherPlayers || !ignoreOtherPlayers)) score *= .2;
                int bearing = MotionMath.bearing(x - playerX, y - playerY);
                if (distance <= 2 && angleDifference(bearing, cameraBearing) <= 256)
                {
                    score *= .25;
                }
                if (spawning && everSpawned && angleDifference(bearing, (playerBearing + 1024) & 2047) <= 256)
                {
                    score *= 1.5;
                }
                if (dx != 0 || dy != 0)
                {
                    int dot = (x - playerX) * dx + (y - playerY) * dy;
                    score *= dot < 0 ? 1.5 : dot > 0 ? .5 : 1;
                }
                if (intent == Intent.NORMAL && validCombatTarget())
                {
                    int targetDx = playerX * 128 + 64 - combatTarget.getX();
                    int targetDy = playerY * 128 + 64 - combatTarget.getY();
                    if ((x - playerX) * targetDx + (y - playerY) * targetDy > 0) score *= 1.5;
                }
                return score;
            }
            @Override public double refineScore(int x, int y, int steps, double score)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                boolean playerFront = stillTicks >= 2 && distance <= 2
                    && angleDifference(MotionMath.bearing(x - playerX, y - playerY), playerBearing) <= 256;
                Tile tile = tile(x, y);
                boolean loot = tile != null && tile.getGroundItems() != null && !tile.getGroundItems().isEmpty();
                int exits = intent == Intent.NORMAL ? 0 : -1;
                if (exits == 0)
                {
                    SceneCollision edges = collision(false);
                    for (int ex = -1; ex <= 1; ex++)
                    {
                        for (int ey = -1; ey <= 1; ey++)
                        {
                            if ((ex != 0 || ey != 0) && edges.canTravel(x, y, ex, ey)) exits++;
                        }
                    }
                }
                return RestingRules.refine(score, playerFront, loot, roofMismatch(x, y), exits);
            }
            @Override public int distance(int x, int y)
            {
                int distance = MotionMath.distance(x, y, playerX, playerY);
                return distance > high ? distance - high : distance < low ? low - distance : 0;
            }
        };
    }

    private static int angleDifference(int first, int second)
    {
        return Math.abs(((first - second + 1024) & 2047) - 1024);
    }

    private boolean roofMismatch(int x, int y)
    {
        // Only the source-defined visible-roofs value gets this optional courtesy; other values are unknown/hidden.
        if (client.getVarbit(VarbitID.OPTION_HIDE_ROOFTOPS) == null
            || client.getVarbitValue(VarbitID.OPTION_HIDE_ROOFTOPS) != 0) return false;
        byte[][][] settings = view.getTileSettings();
        if (settings == null || plane < 0 || plane >= settings.length || settings[plane] == null
            || x < 0 || playerX < 0 || x >= settings[plane].length || playerX >= settings[plane].length
            || settings[plane][x] == null || settings[plane][playerX] == null || y < 0 || playerY < 0
            || y >= settings[plane][x].length || playerY >= settings[plane][playerX].length) return false;
        return (settings[plane][x][y] & Constants.TILE_FLAG_UNDER_ROOF)
            != (settings[plane][playerX][playerY] & Constants.TILE_FLAG_UNDER_ROOF);
    }

    private Tile tile(int x, int y)
    {
        Tile[][][] tiles = scene.getTiles();
        return tiles != null && plane >= 0 && plane < tiles.length && tiles[plane] != null
            && x >= 0 && x < tiles[plane].length && tiles[plane][x] != null && y >= 0 && y < tiles[plane][x].length
            ? tiles[plane][x][y] : null;
    }

    private void updateInteractionHold(Player player)
    {
        LocalPoint npcPoint = null;
        if (combatKnown && !combatConfirmed && stillTicks >= 2 && player.getInteracting() instanceof NPC)
        {
            NPC npc = (NPC) player.getInteracting();
            LocalPoint point = npc.getLocalLocation();
            if (!npc.isDead() && point != null && point.getWorldView() == view.getId()) npcPoint = point;
        }
        Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        boolean holdingNow = !combatConfirmed && (npcPoint != null || bank != null && !bank.isHidden());
        if (holdingNow)
        {
            interactionHolding = true;
            interactionReleaseTicks = 3;
            interactionTarget = npcPoint;
        }
        else
        {
            interactionHolding = interactionReleaseTicks > 0;
            if (interactionReleaseTicks > 0) interactionReleaseTicks--;
            else interactionTarget = null;
        }
    }

    private boolean validCombatTarget()
    {
        return combatConfirmed && view != null && combatTarget != null && combatSize > 0 && combatTarget.getWorldView() == view.getId();
    }

    private int combatDistance(int x, int y)
    {
        int minX = combatTarget.getSceneX() - combatSize / 2, minY = combatTarget.getSceneY() - combatSize / 2;
        return MotionMath.footprintDistance(x, y, minX, minY, minX + combatSize - 1, minY + combatSize - 1);
    }

    private RoutePlanner.Goal destinationGoal()
    {
        int goalX = destinationWorldX - baseX, goalY = destinationWorldY - baseY;
        return fixedGoal(goalX, goalY);
    }

    private RoutePlanner.Goal fixedGoal(int goalX, int goalY)
    {
        LocalPoint destination = client.getLocalDestinationLocation();
        return new RoutePlanner.Goal()
        {
            @Override public double score(int x, int y, int steps)
            {
                if (x != goalX || y != goalY || x == playerX && y == playerY
                    || destination != null && destination.getWorldView() == view.getId()
                    && x == destination.getSceneX() && y == destination.getSceneY())
                {
                    return -1;
                }
                return 1;
            }
            @Override public int distance(int x, int y)
            {
                return MotionMath.distance(x, y, goalX, goalY);
            }
        };
    }

    private RoutePlanner.Result planRoute(RoutePlanner.Collision collision, RoutePlanner.Node start, RoutePlanner.Goal goal)
    {
        if (lastPlanningTick == decisionTick) { throw new IllegalStateException("More than one native route search in a committed tick"); }
        lastPlanningTick = decisionTick; planningCount++;
        // H.2: every route she ever takes keeps clear of the creatures she is afraid of.
        return planner.plan(keepAway.over(collision), start, keepAway.over(goal));
    }

    private void rememberResult(RoutePlanner.Result result)
    {
        lastPlannerStatus = result.status;
        lastPlanConfirmedBlocked = result.status == RoutePlanner.Status.BLOCKED;
        lastPlannerVisited = result.visited;
        lastPlannerSteps = result.steps.size();
    }

    private void publishMotionArrival()
    {
        MotionObservation record = motionRecorder.current();
        RoutePlanner.Node node = motion.completedNode();
        if (record == null || node == null || motion.isMoving()) return;
        int distance = MotionMath.distance(node.x, node.y, playerX, playerY);
        boolean permitted = intent == Intent.DESTINATION ? !inspectionDestinationFailed
            : intent == Intent.APPROACH ? distance >= minDistance && distance <= maxDistance
            : intent == Intent.NORMAL && !combatConfirmed && !interactionHolding
                && distance >= 1 && distance <= 5;
        boolean legal = permitted && collision(true).isLegal(node.x, node.y)
            && validHeightData(LocalPoint.fromScene(node.x, node.y, view));
        motionRecorder.arrive(decisionTick, baseX + node.x, baseY + node.y, distance, legal);
    }

    private boolean inspectionPlanFits(RoutePlanner.Result result)
    {
        long completed = motion.getCompletedEdges() - inspectionStartEdges;
        long remaining = InspectionRoutes.MAX_STEPS - completed - (motion.isSegmentInFlight() ? 1 : 0);
        if (remaining < 0 || result.steps.size() > remaining
            || result.status != RoutePlanner.Status.ROUTE && result.status != RoutePlanner.Status.AT_GOAL) return false;
        LocalPoint stand = LocalPoint.fromScene(destinationWorldX - baseX, destinationWorldY - baseY, view);
        return collision(false).isLegal(stand.getSceneX(), stand.getSceneY()) && validHeightData(stand);
    }

    private SceneCollision collision(boolean allowPlayer)
    {
        return new SceneCollision(view, plane, allowPlayer ? -1 : playerX, allowPlayer ? -1 : playerY);
    }

    private int idleBearing()
    {
        if (intent != Intent.NORMAL && facingTarget != null && facingTarget.getWorldView() == view.getId())
        {
            return MotionMath.bearing(facingTarget.getX() - motion.getX(), facingTarget.getY() - motion.getY());
        }
        int bearing = MotionMath.bearing(playerX * 128 + 64 - motion.getX(), playerY * 128 + 64 - motion.getY());
        if (intent == Intent.NORMAL)
        {
            Creature activeCreature = creatureAwareness.getTarget();
            Creature look = isCreatureSafetyActive() && creatureAwareness.isKnown() ? activeCreature
                : livingWorldCreatureAllowed && !combatConfirmed && !travelling && !interactionHolding && !idleMovementSuppressed && stillTicks >= 2
                    ? timedFacingCreature != null ? timedFacingCreature : restingCreature != null ? restingCreature
                        : livingWorldCreatureAllowed && activeCreature != null && activeCreature.classification.behaviour == Behaviour.DISGUST ? activeCreature : null
                    : null;
            if (look != null)
            {
                int desired = MotionMath.bearing((look.worldX - baseX) * 128 + 64 - motion.getX(),
                    (look.worldY - baseY) * 128 + 64 - motion.getY());
                return look.classification.behaviour == Behaviour.DISGUST ? (desired + 1024) & 2047 : desired;
            }
            if (angleDifference(orientation, bearing) > 512)
            {
                // With her back to him she turns round at once; it is a turn all the same (H.3).
                idleFacing = bearing;
            }
            else if (decisionTick >= nextIdleFacingTick && bearing != idleFacing)
            {
                idleFacing = bearing;
                nextIdleFacingTick = decisionTick + 8 + random.nextInt(13);
            }
            return idleFacing;
        }
        return turnAway ? (bearing + 1024) & 2047 : bearing;
    }

    private boolean applyPose()
    {
        LocalPoint location = new LocalPoint((int) Math.round(motion.getX()), (int) Math.round(motion.getY()), view.getId());
        if (!validHeightData(location))
        {
            hide();
            return false;
        }
        Model model = bank.pose(clip, clipCycle);
        int appliedOrientation = MotionMath.quantize(orientation);
        int z = Perspective.getTileHeight(client, location, plane);
        controller.setLocation(location, plane);
        controller.setZ(z);
        controller.setOrientation(appliedOrientation);
        controller.model = model;
        placement = new PlacementSnapshot(model, location, view, plane, appliedOrientation, z, placementGeneration);
        return true;
    }

    private void applyPosture(int cycle)
    {
        if (motion.isMoving()) foldedUntilTick = -1;
        else if (impatientUntilCycle != Integer.MIN_VALUE && cycle >= impatientUntilCycle)
        {
            posturePlayback.request(foldedUntilTick >= 0 ? PosturePlayback.Posture.ARMS_FOLDED
                : PosturePlayback.Posture.STANDING, cycle);
            impatientUntilCycle = Integer.MIN_VALUE;
        }
        posturePlayback.advance(cycle, motion.isMoving(), motion.isRunning());
        updateGestures(cycle);
        clip = gesturePlayback.isActive() ? gesturePlayback.getClip() : posturePlayback.getClip();
        clipCycle = (gesturePlayback.isActive() ? gesturePlayback.getSample() : posturePlayback.getSample()) * 2;
        if (!applyPose()) return;
        if (posturePlayback.consumeImpatientLoopStart())
        {
            impatientLoopStartCycle = posturePlayback.getImpatientLoopStartCycle();
            impatientLoopStartTick = impatientLoopStartReceipt = decisionTick;
            impatientUntilCycle = impatientLoopStartCycle + impatientDurationTicks * 30;
            nextIdleShiftTick = Math.max(nextIdleShiftTick, decisionTick + impatientDurationTicks + 2);
        }
        if (posturePlayback.consumeImpatientEnd())
        {
            impatientEndCycle = posturePlayback.getImpatientEndCycle();
            impatientEndTick = impatientEndReceipt = decisionTick;
            impatientUntilCycle = Integer.MIN_VALUE;
            impatientEpisode = false;
        }
    }

    /** H.6: which emote of her own she is in on this cycle, if any. */
    private void updateGestures(int cycle)
    {
        boolean standing = !motion.isMoving() && posturePlayback.getPosture() == PosturePlayback.Posture.STANDING
            && !posturePlayback.isTransitioning();
        if (!hasGestures() || !visible || !standing)
        {
            // Anything else she has to do ends it at once, as G.2 says of a folded posture.
            gesturePlayback.reset(); speechGesture = pendingGesture = null; speechGestureUntilTick = -1;
            return;
        }
        boolean settled = lastMovingCycle == Integer.MIN_VALUE || cycle - lastMovingCycle >= 10;
        Creature feared = creatureAwareness.getTarget();
        // P38: afraid and standing still, she cowers for as long as it lasts.
        boolean afraid = intent == Intent.NORMAL && settled && isCreatureSafetyActive() && feared != null
            && feared.classification.behaviour == Behaviour.AFRAID;
        // P39: looking a creature over (F.3, H.4) or a thing she has walked up to (F.4, M08, M69), she peers at it
        // once she has turned to it.
        boolean looking = settled && angleDifference(orientation, lastDesiredFacing) <= 64
            && (timedFacingCreature != null && intent == Intent.NORMAL
                || peerAtFacingTarget && facingTarget != null && intent != Intent.NORMAL);
        if (speechGesture != null && decisionTick >= speechGestureUntilTick) { speechGesture = null; }
        GesturePlayback.Gesture wanted = afraid ? GesturePlayback.Gesture.COWER : looking ? GesturePlayback.Gesture.PEER
            : combatConfirmed ? null : speechGesture;
        if (gesturePlayback.isHolding() && gesturePlayback.getGesture() != wanted) { gesturePlayback.release(cycle); }
        if (pendingGesture != null && (cycle >= pendingGestureUntilCycle || afraid || combatConfirmed)) { pendingGesture = null; }
        if (!gesturePlayback.isActive())
        {
            if (wanted != null) { gesturePlayback.begin(wanted, cycle); }
            else if (pendingGesture != null) { gesturePlayback.begin(pendingGesture, cycle); pendingGesture = null; }
        }
        gesturePlayback.advance(cycle, false);
    }

    private void resetPostureForRemoval()
    {
        gesturePlayback.reset(); speechGesture = pendingGesture = null; speechGestureUntilTick = -1;
        if (impatientEpisode)
        {
            impatientEndCycle = client.getGameCycle();
            impatientEndTick = impatientEndReceipt = decisionTick;
            impatientEpisode = false;
        }
        posturePlayback.reset(client.getGameCycle());
        foldedUntilTick = -1;
        impatientUntilCycle = Integer.MIN_VALUE;
    }

    private boolean validHeightData(LocalPoint point)
    {
        if (view == null || point.getWorldView() != view.getId() || client.getWorldView(view.getId()) != view || !view.contains(point))
        {
            return false;
        }
        int x = point.getSceneX(), y = point.getSceneY();
        int[][][] heights = view.getTileHeights();
        byte[][][] settings = view.getTileSettings();
        if (plane < 0 || heights == null || plane >= heights.length || settings == null || plane >= settings.length
            || settings[plane] == null || x < 0 || y < 0 || x >= settings[plane].length
            || settings[plane][x] == null || y >= settings[plane][x].length)
        {
            return false;
        }
        // Perspective's bridge lookup reads plane 1 even while standing on plane 0.
        if (settings.length <= 1 || settings[1] == null || x >= settings[1].length
            || settings[1][x] == null || y >= settings[1][x].length)
        {
            return false;
        }
        int effectivePlane = plane < 3 && (settings[1][x][y] & 2) != 0 ? plane + 1 : plane;
        return effectivePlane < heights.length && heights[effectivePlane] != null
            && x + 1 < heights[effectivePlane].length && heights[effectivePlane][x] != null
            && heights[effectivePlane][x + 1] != null && y + 1 < heights[effectivePlane][x].length
            && y + 1 < heights[effectivePlane][x + 1].length;
    }

    private void captureScene(WorldView current)
    {
        clearFacingState();
        creatureIntake.captureScope(current);
        recoverySceneRevision++;
        view = current;
        scene = current.getScene();
        baseX = current.getBaseX();
        baseY = current.getBaseY();
        sizeX = current.getSizeX();
        sizeY = current.getSizeY();
        plane = current.getPlane();
    }

    private boolean matchesScene(Player player)
    {
        return player != null && player.getWorldView() == view && view != null && view.getScene() == scene
            && view.getBaseX() == baseX && view.getBaseY() == baseY && view.getSizeX() == sizeX && view.getSizeY() == sizeY
            && view.getPlane() == plane && player.getLocalLocation() != null && player.getLocalLocation().getWorldView() == view.getId();
    }

    public void approachPlayer(boolean run, int minimum, int maximum)
    {
        revokeMotionAdmission();
        motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
        inspectionDestination = inspectionDestinationFailed = false;
        idleShift = false;
        intent = Intent.APPROACH;
        intentRun = run;
        runBurstRemaining = -1;
        inspectionRequest = null;
        minDistance = Math.max(1, minimum);
        maxDistance = Math.max(minDistance, maximum);
        turnAway = false;
        facingTarget = null;
        preferCombatAvoidance = false;
    }

    public void hold()
    {
        revokeMotionAdmission();
        holdInternal();
    }

    private void holdInternal()
    {
        motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
        inspectionDestination = inspectionDestinationFailed = false;
        idleShift = sceneMotionVoluntary = false;
        intent = Intent.HOLD;
        motion.stop();
    }
    public void turnToPlayer() { turnAway = false; facingTarget = null; }
    public void turnAway() { turnAway = true; facingTarget = null; }
    public void faceTarget(LocalPoint target) { facingTarget = target; turnAway = false; }
    public void setRecoveryAllowed(boolean allowed) { recoveryAllowed = allowed; }
    public void setIdleMovementSuppressed(boolean suppressed) { idleMovementSuppressed = suppressed; }
    public void setSceneMotionVoluntary(boolean voluntary) { sceneMotionVoluntary = voluntary; }
    public void setSceneEmergency(boolean emergency) { sceneEmergency = emergency; }
    public boolean consumeCreatureScenePreemption()
    {
        boolean preempted = creatureScenePreempted; creatureScenePreempted = false; return preempted;
    }
    public void setCreatureClassifier(BiFunction<String, Integer, Classification> classifier)
    {
        creatureIntake.setClassifier(classifier); creatureAwareness.reset();
    }
    public void setLocalFollowerClassification(Classification classification)
    {
        creatureIntake.setLocalFollowerClassification(classification);
    }
    public Sample getCreatureSample() { return creatureSample; }
    public CreatureAwareness getCreatureAwareness() { return creatureAwareness; }
    public boolean isE5KeepingAway()
    {
        return creatureAwareness.isKeepingAway() && CreatureAwareness.isE5(creatureAwareness.getGroupId());
    }
    public boolean isCreatureSafetyActive()
    {
        return creatureAwareness.isKeepingAway() && (isE5KeepingAway() || livingWorldCreatureAllowed);
    }
    public void setLivingWorldCreatureAllowed(boolean allowed)
    {
        if (allowed && !livingWorldCreatureAllowed && creatureAwareness.isKeepingAway() && !isE5KeepingAway())
        {
            creatureReplanRequested = true;
        }
        livingWorldCreatureAllowed = allowed;
    }
    public void setCreatureStrollPreference(Creature creature)
    {
        if (creature == null || creature.classification.behaviour == Behaviour.DISGUST
            || creature.classification.behaviour == Behaviour.FOND) { strollCreature = creature; }
    }
    public void faceCreatureFor(Creature creature, int ticks)
    {
        if (!visible || intent != Intent.NORMAL || travelling || motion.isMoving() || combatConfirmed
            || isCreatureSafetyActive() || !livingWorldCreatureAllowed || creature == null) { return; }
        timedFacingCreature = creature; creatureFacingUntilTick = decisionTick + Math.max(0, ticks);
    }
    public Creature getCreatureStrollArrival() { return creatureStrollArrival; }
    public long getCreatureStrollArrivalTick() { return creatureStrollArrivalTick; }
    public void invalidateCreature(NPC npc) { creatureIntake.invalidate(npc); }
    public void invalidateCreatureScope() { creatureIntake.clear(); }
    public void retireCreature(NPC npc) { creatureIntake.retire(npc); }
    public void reviveCreature(NPC npc) { creatureIntake.revive(npc); }
    public boolean isCreatureCurrent(String key, String groupId)
    {
        if (!visible || !matchesScene(client.getLocalPlayer())) { return false; }
        Creature creature = creatureIntake.current(client, view, key);
        return creature != null && creature.classification.groupId.equals(groupId)
            && creature.distance(baseX + ((int) motion.getX() >> 7), baseY + ((int) motion.getY() >> 7)) <= 6;
    }
    public void limitRunBurst(int gameTicks) { runBurstRemaining = Math.max(0, gameTicks); }
    public void requestApproachPlayer(int minimum, int maximum, boolean run) { approachPlayer(run, minimum, maximum); }
    public void requestApproachPlayer(int minimum, int maximum, boolean run, boolean avoidTargetIfPossible)
    {
        approachPlayer(run, minimum, maximum);
        preferCombatAvoidance = avoidTargetIfPossible;
    }
    public void setCombatTarget(boolean confirmed, LocalPoint target, int footprintSize)
    {
        if (confirmed) revokeMotionAdmission();
        combatKnown = true;
        if (combatConfirmed && !confirmed)
        {
            oldCombatTarget = combatTarget;
            combatGoneTicks = 2;
        }
        if (confirmed != combatConfirmed) combatDecisionTick = -8;
        combatConfirmed = confirmed;
        combatTarget = target;
        combatSize = Math.max(0, footprintSize);
    }
    public void requestDestination(int worldX, int worldY, boolean run)
    {
        revokeMotionAdmission();
        motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
        inspectionDestination = inspectionDestinationFailed = false;
        idleShift = false;
        intent = Intent.DESTINATION;
        intentRun = run;
        runBurstRemaining = -1;
        inspectionRequest = null;
        destinationWorldX = worldX;
        destinationWorldY = worldY;
    }
    public void requestDestination(int worldX, int worldY) { requestDestination(worldX, worldY, false); }
    public void facePlayer() { turnToPlayer(); }
    public void holdPosition() { hold(); }
    public void clearIntent() { clearSceneMotion(); }
    public void clearSceneMotion()
    {
        revokeMotionAdmission();
        clearSceneMotionInternal();
    }

    private void clearSceneMotionInternal()
    {
        motionRecorder.cancel(MotionObservation.Status.INVALIDATED);
        inspectionDestination = inspectionDestinationFailed = false;
        idleShift = sceneMotionVoluntary = false;
        motion.stop();
        intent = Intent.NORMAL;
        sceneEmergency = false;
        turnAway = false;
        facingTarget = null;
        clearFacingState();
        peerAtFacingTarget = false;
        preferCombatAvoidance = false;
        runBurstRemaining = -1;
        inspectionRequest = null;
    }

    private void clearFacingState()
    {
        facingTarget = combatTarget = oldCombatTarget = interactionTarget = null;
        turnAway = false;
        combatKnown = combatConfirmed = interactionHolding = false;
        combatGoneTicks = interactionReleaseTicks = combatSize = 0;
        nextIdleFacingTick = 0;
    }


    public void hide()
    {
        resetPostureForRemoval();
        recoveryObservation = null;
        offScreenEpisodeId = blockedEpisodeId = 0;
        offScreenEpisodeTick = blockedEpisodeTick = -1;
        if (visible)
        {
            client.removeRuneLiteObject(controller);
            placementGeneration++;
        }
        visible = false;
        placement = null;
        controller.model = null;
        motion.clear();
        creatureIntake.onHide(client); creatureAwareness.reset(); creatureSample = null;
        keepAway = KeepAway.NONE; keepAwayRelax = 0; keepAwayRelaxedTick = Long.MIN_VALUE;
        lastMovingCycle = Integer.MIN_VALUE;
        strollCreature = restingCreature = timedFacingCreature = creatureStrollArrival = null;
        creatureFacingUntilTick = creatureStrollArrivalTick = -1;
        creatureReplanRequested = creatureShortRestPending = creatureScenePreempted = sceneEmergency = false;
        creatureShortRestE5 = false;
        lastCreaturePlanTick = Long.MIN_VALUE; creaturePlanBeginTick = -1;
        motionRecorder.clear();
        havePlayer = false;
        displacement = priorDisplacement = stillTicks = movingTicks = 0;
        travelling = followingRun = permitPlayerTransit = false;
        followDx = followDy = 0;
        clearSceneMotion();
        view = null;
        scene = null;
        recovery.reset();
        combatKnown = combatConfirmed = interactionHolding = crowds = false;
        combatTarget = oldCombatTarget = interactionTarget = null;
        combatSize = combatGoneTicks = interactionReleaseTicks = 0;
        settledSinceTick = nextIdleShiftTick = -1;
        segmentVoluntary = segmentIdleShift = idleMovementSuppressed = false;
        playerDestination = null;
        occupancy = null;
        inspectionProof = null;
    }

    public void dispose()
    {
        disposed = true;
        hide();
        bank = null;
        decodedAsset = null;
        creatureIntake.clear();
    }

    /** Release native caches on a login/world/account session boundary; immutable validated bake may be reused. */
    public void resetSession()
    {
        hide();
        creatureIntake.clear();
        bank = null;
        everSpawned = false;
        lastCycle = Integer.MIN_VALUE;
        lastPlannerStatus = null;
        lastPlanConfirmedBlocked = false;
        lastPlannerVisited = lastPlannerSteps = 0;
        decisionTick = recoveryCount = 0;
        lastVoluntaryMovementTick = -1;
        lastInspectionStartTick = -1;
        planningCount = 0;
        lastDecisionTick = Long.MIN_VALUE;
        lastPlanningTick = Long.MIN_VALUE;
        impatientDurationTicks = 0;
        impatientEpisode = false;
        impatientLoopStartCycle = impatientEndCycle = Integer.MIN_VALUE;
        impatientLoopStartTick = impatientEndTick = impatientLoopStartReceipt = impatientEndReceipt = -1;
    }

    public void resetForLogin() { resetSession(); }

    public void setHeightPercent(int percent)
    {
        int next = Math.max(90, Math.min(110, percent));
        if (heightPercent == next) return;
        hide();
        bank = null;
        heightPercent = next;
    }

    public int getHeightPercent() { return heightPercent; }
    public boolean isReady() { return bank != null && bank.isReady(); }
    public boolean isVisible() { return visible; }
    public boolean isMoving() { return motion.isMoving(); }
    public boolean isFollowing() { return visible && intent == Intent.NORMAL && travelling && !combatConfirmed; }
    public boolean hasEmotes()
    {
        return decodedAsset != null && decodedAsset.clips.containsKey("CROSS_ARMS")
            && decodedAsset.clips.containsKey("IMPATIENT");
    }
    public PosturePlayback.Posture getPosture()
    {
        return hasEmotes() ? posturePlayback.getPosture() : PosturePlayback.Posture.STANDING;
    }
    public boolean isStandingInRest()
    {
        return visible && !motion.isMoving() && intent == Intent.NORMAL && !travelling && !combatConfirmed
            && !isCreatureSafetyActive() && (settledSinceTick >= 0 || interactionHolding && stillTicks >= 2)
            && getPosture() == PosturePlayback.Posture.STANDING
            && !posturePlayback.isTransitioning() && !gesturePlayback.isActive();
    }
    public boolean hasGestures()
    {
        return hasEmotes() && decodedAsset.clips.containsKey("COWER") && decodedAsset.clips.containsKey("STOMP");
    }
    /** The emote of her own she is in, or null. */
    public GesturePlayback.Gesture getGesture() { return gesturePlayback.isActive() ? gesturePlayback.getGesture() : null; }
    /** H.6: a held emote of her own until the given tick, or a gesture played once (the tick is then not used). */
    public boolean playGesture(GesturePlayback.Gesture gesture, long untilTick)
    {
        if (gesture == null || !hasGestures() || !visible || motion.isMoving() || combatConfirmed || isCreatureSafetyActive()
            || getPosture() != PosturePlayback.Posture.STANDING || posturePlayback.isTransitioning()) return false;
        if (gesture.held) { speechGesture = gesture; speechGestureUntilTick = untilTick; }
        else { pendingGesture = gesture; pendingGestureUntilCycle = client.getGameCycle() + 30; }
        return true;
    }
    /** P39: while a scene has her face its subject, she peers at it. Cleared with the scene's motion. */
    public void setPeerAtFacingTarget(boolean peer) { peerAtFacingTarget = peer; }
    /** H.7: afraid, standing 3 or more tiles from him, while he stands within 2 tiles of the creature she fears. */
    public boolean isKeptBackByBeast()
    {
        Creature feared = creatureAwareness.getTarget();
        return visible && intent == Intent.NORMAL && !motion.isMoving() && !combatConfirmed && isCreatureSafetyActive()
            && feared != null && feared.classification.behaviour == Behaviour.AFRAID && getDistanceToPlayer() >= 3
            && feared.distance(baseX + playerX, baseY + playerY) <= 2;
    }
    public boolean foldArms(long untilTick)
    {
        if (!hasEmotes() || !visible || motion.isMoving() || combatConfirmed || isCreatureSafetyActive()
            || gesturePlayback.isActive()) return false;
        if (impatientEpisode) endImpatient();
        foldedUntilTick = Math.max(foldedUntilTick, untilTick);
        posturePlayback.advance(client.getGameCycle(), false, false);
        posturePlayback.request(PosturePlayback.Posture.ARMS_FOLDED, client.getGameCycle());
        applyPosture(client.getGameCycle());
        return true;
    }
    public void clearSpeechPosture()
    {
        boolean hadSpeechPosture = foldedUntilTick >= 0;
        foldedUntilTick = -1;
        if (!hasEmotes() || !visible || impatientEpisode && !hadSpeechPosture) return;
        posturePlayback.advance(client.getGameCycle(), motion.isMoving(), motion.isRunning());
        posturePlayback.request(PosturePlayback.Posture.STANDING, client.getGameCycle());
        applyPosture(client.getGameCycle());
    }
    public boolean startImpatient(int ticks)
    {
        if (!hasEmotes() || !isStandingInRest()) return false;
        if (ticks < 8 || ticks > 16) throw new IllegalArgumentException("Impatient duration must be 8 to 16 ticks");
        impatientDurationTicks = ticks;
        impatientEpisode = true;
        impatientLoopStartTick = impatientEndTick = -1;
        impatientLoopStartReceipt = impatientEndReceipt = -1;
        impatientUntilCycle = Integer.MIN_VALUE;
        nextIdleShiftTick = Math.max(nextIdleShiftTick, decisionTick + ticks + 3);
        posturePlayback.advance(client.getGameCycle(), false, false);
        posturePlayback.request(PosturePlayback.Posture.IMPATIENT, client.getGameCycle());
        applyPosture(client.getGameCycle());
        return true;
    }
    public void endImpatient()
    {
        if (!hasEmotes() || !visible) return;
        if (motion.isMoving()) applyPosture(client.getGameCycle());
        else
        {
            posturePlayback.advance(client.getGameCycle(), false, false);
            posturePlayback.endImpatient(client.getGameCycle());
            applyPosture(client.getGameCycle());
        }
    }
    public int getImpatientDurationTicks() { return impatientDurationTicks; }
    public int getImpatientLoopStartCycle() { return impatientLoopStartCycle; }
    public long getImpatientLoopStartTick() { return impatientLoopStartTick; }
    public int getImpatientEndCycle() { return impatientEndCycle; }
    public long getImpatientEndTick() { return impatientEndTick; }
    public long consumeImpatientLoopStartTick()
    {
        long tick = impatientLoopStartReceipt; impatientLoopStartReceipt = -1; return tick;
    }
    public long consumeImpatientEndTick()
    {
        long tick = impatientEndReceipt; impatientEndReceipt = -1; return tick;
    }
    public int getPoseCount() { return bank == null ? 0 : bank.poseCount(); }
    public int getPreparedModelCount() { return bank == null ? 0 : bank.preparedCount(); }
    public String getAssetHash() { return NativeAsset.SHA256; }
    public PlacementSnapshot getPlacementSnapshot() { return placement; }
    public long getPlacementGeneration() { return placementGeneration; }
    public LocalPoint getLocation() { return placement == null ? null : placement.getLocation(); }
    public WorldView getWorldView() { return placement == null ? null : placement.getWorldView(); }
    public int getPlane() { return placement == null ? -1 : placement.getPlane(); }
    public int getOrientation() { return placement == null ? 0 : placement.getOrientation(); }
    public int getHeadHeight() { return placement == null ? 0 : placement.getHeadHeight(); }
    public Model getModel() { return placement == null ? null : placement.getModel(); }
    public Shape getClickbox() { return ClickboxAdapter.get(client, placement); }
    public RoutePlanner.Status getLastPlannerStatus() { return lastPlannerStatus; }
    public int getLastPlannerVisited() { return lastPlannerVisited; }
    public int getLastPlannerSteps() { return lastPlannerSteps; }
    public long getRecoveryCount() { return recoveryCount; }
    public RecoveryObservation getRecoveryObservation() { return recoveryObservation; }
    public long getPlanningCount() { return planningCount; }
    public long getDecisionTick() { return decisionTick; }
    public long getLastVoluntaryMovementTick() { return lastVoluntaryMovementTick; }
    public long getLastInspectionStartTick() { return lastInspectionStartTick; }
    public MotionObservation getMotionObservation() { return motionRecorder.current(); }
    public MotionObservation getLatestMotionCompletion() { return motionRecorder.completion(); }
    public SceneTranslationSnapshot captureSceneTranslation(long candidateId)
    {
        return candidateId < 0 || !translationScopeKnown() ? null : new SceneTranslationSnapshot(this, candidateId);
    }
    public boolean hasNotTranslated(SceneTranslationSnapshot snapshot, long candidateId)
    {
        return snapshot != null && snapshot.owner == this && snapshot.candidateId == candidateId
            && translationScopeKnown() && snapshot.placementGeneration == placementGeneration
            && snapshot.worldView == view && snapshot.scene == scene && snapshot.plane == plane
            && snapshot.translationRevision == translationRevision;
    }

    private boolean translationScopeKnown()
    {
        return !disposed && visible && isReady() && placement != null
            && client.getGameState() == GameState.LOGGED_IN && matchesScene(client.getLocalPlayer())
            && motion.completedNode() != null && placement.getPlacementGeneration() == placementGeneration
            && placement.getWorldView() == view && placement.getScene() == scene && placement.getPlane() == plane
            && validHeightData(placement.getLocation());
    }

    private void placeMotion(int x, int y)
    {
        double beforeX = motion.getX(), beforeY = motion.getY();
        motion.place(x, y, client.getGameCycle());
        recordTranslation(beforeX, beforeY);
    }

    private void recordTranslation(double beforeX, double beforeY)
    {
        if (Double.compare(beforeX, motion.getX()) != 0 || Double.compare(beforeY, motion.getY()) != 0)
            translationRevision++;
    }

    public boolean admitMotionScene(long candidateId, long requestId)
    {
        MotionObservation receipt = motionRecorder.recentCompletion(requestId, decisionTick);
        MotionObservation current = motionRecorder.current();
        boolean ownedReturn = intent == Intent.APPROACH && returnSourceCandidate >= 0 && receipt != null
            && receipt.purpose == MotionObservation.Purpose.APPROACH_PLAYER && receipt.sourceCandidateId == returnSourceCandidate
            && current != null && current.requestId == receipt.requestId;
        if (candidateId < 0 || receipt == null || intent != Intent.NORMAL && !ownedReturn
            || !visible || !matchesScene(client.getLocalPlayer())
            || receipt.placementGeneration != placementGeneration || receipt.worldViewId != view.getId() || receipt.plane != plane
            || current == null || !current.known || !current.ordinary
            || current.status != MotionObservation.Status.MOVING && current.status != MotionObservation.Status.ARRIVED)
        {
            revokeMotionAdmission();
            return false;
        }
        motionAdmission = receipt;
        motionAdmissionCandidate = candidateId;
        motionAdmissionView = view;
        motionAdmissionScene = scene;
        motionAdmittedTick = decisionTick;
        motionOwnInvalidation = motionOwnedHold = false;
        motionResumeTick = -1;
        return true;
    }

    public boolean holdPosition(long candidateId)
    {
        if (!motionAdmissionMatches(candidateId) || !motionAdmissionCurrentHealthy())
        {
            hold();
            return false;
        }
        holdInternal();
        motionOwnInvalidation = motionOwnedHold = true;
        motionHeldTick = decisionTick;
        motionResumeTick = -1;
        return true;
    }

    public boolean clearIntent(long candidateId)
    {
        if (!motionAdmissionMatches(candidateId) || !motionAdmissionCurrentHealthy())
        {
            clearSceneMotion();
            return false;
        }
        clearSceneMotionInternal();
        motionOwnInvalidation = true;
        motionOwnedHold = false;
        motionResumeTick = decisionTick;
        return true;
    }

    public MotionHoldReceipt getMotionHoldReceipt(long activeCandidateId)
    {
        RoutePlanner.Node node = motion.completedNode();
        boolean ownedHold = intent == Intent.HOLD && motionOwnedHold;
        boolean ownedResume = intent == Intent.NORMAL && motionResumeTick >= 0 && motionResumeTick == decisionTick;
        if (!motionAdmissionMatches(activeCandidateId) || !motionOwnInvalidation || !ownedHold && !ownedResume
            || motion.isMoving() || motion.isBlocked() || node == null
            || combatConfirmed || interactionHolding || !motionAdmissionCurrentHealthy()
            || !collision(true).isLegal(node.x, node.y) || !validHeightData(LocalPoint.fromScene(node.x, node.y, view))) return null;
        return new MotionHoldReceipt(activeCandidateId, motionAdmission, motionAdmittedTick,
            ownedResume ? motionResumeTick : motionHeldTick, placementGeneration, view.getId(), plane,
            baseX + node.x, baseY + node.y, true, ownedResume ? MotionHoldReceipt.Mode.NORMAL_RESUME : MotionHoldReceipt.Mode.HOLD);
    }

    /** A real current arrival or this exact scene's own legal hold; never a generic stationary tile. */
    public boolean hasMotionSceneArrived(long candidateId)
    {
        RoutePlanner.Node node = motion.completedNode();
        if (node == null) return false;
        int distance = MotionMath.distance(node.x, node.y, playerX, playerY);
        if (distance < 2 || distance > 5) return false;
        if (getMotionHoldReceipt(candidateId) != null) return true;
        MotionObservation current = motionRecorder.current();
        if (!motionAdmissionMatches(candidateId) || !motionAdmissionCurrentHealthy() || motion.isMoving() || motion.isBlocked()
            || node == null || combatConfirmed || interactionHolding
            || intent != Intent.NORMAL && !(intent == Intent.APPROACH && returnSourceCandidate >= 0)
            || current.status != MotionObservation.Status.ARRIVED || !current.legalGoal || current.completedEdges == 0
            || current.placementGeneration != placementGeneration || current.worldViewId != view.getId() || current.plane != plane
            || current.goalWorldX != baseX + node.x || current.goalWorldY != baseY + node.y
            || current.requestId != motionAdmission.requestId && current.startedTick < motionAdmission.completedTick) return false;
        return collision(true).isLegal(node.x, node.y)
            && validHeightData(LocalPoint.fromScene(node.x, node.y, view));
    }

    private boolean motionAdmissionMatches(long candidateId)
    {
        return motionAdmission != null && candidateId == motionAdmissionCandidate && visible
            && view == motionAdmissionView && scene == motionAdmissionScene && matchesScene(client.getLocalPlayer())
            && motionAdmission.placementGeneration == placementGeneration && motionAdmission.worldViewId == view.getId()
            && motionAdmission.plane == plane;
    }

    private boolean motionAdmissionCurrentHealthy()
    {
        MotionObservation current = motionRecorder.current();
        return current != null && current.known && current.ordinary
            && (current.status == MotionObservation.Status.MOVING || current.status == MotionObservation.Status.ARRIVED
                || current.status == MotionObservation.Status.INVALIDATED && motionOwnInvalidation);
    }

    private void revokeMotionAdmission()
    {
        inspectionSourceCandidate = returnSourceCandidate = -1;
        motionAdmission = null;
        motionAdmissionCandidate = -1;
        motionAdmissionView = null;
        motionAdmissionScene = null;
        motionOwnInvalidation = motionOwnedHold = false;
        motionResumeTick = -1;
        motionRecorder.clearRecentCompletions();
    }
    public boolean isInteractionHolding() { return interactionHolding; }
    public boolean isCrowded() { return crowds && !OtherPlayerPolicy.ignoreOtherPlayers(client); }
    public boolean isInspectionSuppressed() { return interactionHolding || isCrowded() || isCreatureSafetyActive(); }
    public boolean requestInspectionProbe(String key, int minWorldX, int minWorldY, int maxWorldX, int maxWorldY, int plane)
    {
        if (key == null || key.isEmpty() || key.length() > 128 || !inspectionEligible() || plane != this.plane
            || minWorldX > maxWorldX || minWorldY > maxWorldY || minWorldX < baseX || minWorldY < baseY
            || maxWorldX >= baseX + sizeX || maxWorldY >= baseY + sizeY)
        {
            return false;
        }
        InspectionRequest request = new InspectionRequest(key, minWorldX, minWorldY, maxWorldX, maxWorldY, plane);
        if (inspectionRequest != null && (!inspectionRequest.key.equals(key)
            || inspectionRequest.minWorldX != minWorldX || inspectionRequest.minWorldY != minWorldY
            || inspectionRequest.maxWorldX != maxWorldX || inspectionRequest.maxWorldY != maxWorldY)) return false;
        if (!request.matches(inspectionProof) || !isInspectionProofCurrent(inspectionProof)) inspectionRequest = request;
        return true;
    }
    public InspectionRouteProof getInspectionRouteProof() { return inspectionProof; }
    public boolean requestInspectionDestination(int worldX, int worldY)
    {
        if (inspectionProof == null || inspectionProof.standWorldX != worldX || inspectionProof.standWorldY != worldY
            || !isInspectionProofCurrent(inspectionProof)) return false;
        requestDestination(worldX, worldY, false);
        inspectionDestination = true;
        inspectionStartEdges = motion.getCompletedEdges();
        lastInspectionStartTick = decisionTick;
        return true;
    }
    public boolean requestInspectionDestination(long sourceCandidateId, int worldX, int worldY)
    {
        if (sourceCandidateId < 0 || !requestInspectionDestination(worldX, worldY)) return false;
        inspectionSourceCandidate = sourceCandidateId;
        return true;
    }

    /** Root supplies the genuine target-loss token before any generic clear or hold revokes this inspection. */
    public boolean requestReturnFromInspection(long sourceCandidateId, long inspectionRequestId)
    {
        MotionObservation inspection = motionRecorder.current();
        if (sourceCandidateId < 0 || sourceCandidateId != inspectionSourceCandidate || intent != Intent.DESTINATION
            || !isInspectionDestinationValid() || hasArrived() || combatConfirmed || isInspectionSuppressed()
            || inspection == null || inspection.requestId != inspectionRequestId || inspection.sourceCandidateId != sourceCandidateId
            || !inspection.known || inspection.ordinary || !inspection.legalGoal || !inspection.fixedGoal
            || inspection.purpose != MotionObservation.Purpose.OTHER || inspection.status != MotionObservation.Status.MOVING
            || inspection.completedTick >= 0 || inspection.completedEdges > InspectionRoutes.MAX_STEPS
            || inspection.completedRunEdges != 0 || inspection.plannedSteps < 1 || inspection.plannedSteps > InspectionRoutes.MAX_STEPS
            || inspection.placementGeneration != placementGeneration || inspection.worldViewId != view.getId() || inspection.plane != plane)
            return false;
        approachPlayer(false, 2, 5);
        motion.stop();
        sceneMotionVoluntary = false;
        returnSourceCandidate = sourceCandidateId;
        return true;
    }

    /** No ID exists until the one GameTick search accepts actual return edges. */
    public long getInspectionReturnRequestId(long sourceCandidateId)
    {
        MotionObservation current = motionRecorder.current();
        if (sourceCandidateId < 0 || sourceCandidateId != returnSourceCandidate || !visible || !matchesScene(client.getLocalPlayer())
            || current == null || !current.known || !current.ordinary || !current.legalGoal
            || current.purpose != MotionObservation.Purpose.APPROACH_PLAYER || current.sourceCandidateId != sourceCandidateId
            || current.placementGeneration != placementGeneration || current.worldViewId != view.getId() || current.plane != plane
            || current.status != MotionObservation.Status.MOVING && current.status != MotionObservation.Status.ARRIVED
                && !(current.status == MotionObservation.Status.INVALIDATED && motionAdmission != null && motionOwnInvalidation
                    && motionAdmission.requestId == current.requestId && getMotionHoldReceipt(motionAdmissionCandidate) != null)) return -1;
        return current.requestId;
    }
    public boolean isInspectionDestinationValid()
    {
        return inspectionDestination && !inspectionDestinationFailed && visible
            && matchesScene(client.getLocalPlayer()) && motion.getCompletedEdges() - inspectionStartEdges <= InspectionRoutes.MAX_STEPS;
    }
    public boolean isInspectionProofCurrent(InspectionRouteProof proof)
    {
        if (proof == null || !inspectionEligible() || !matchesScene(client.getLocalPlayer())
            || proof.worldView != view || proof.scene != scene || proof.plane != plane
            || proof.placementGeneration != placementGeneration || !proof.origin.equals(motion.completedNode())) return false;
        SceneCollision edges = collision(false);
        RoutePlanner.Node previous = proof.origin;
        for (RoutePlanner.Node next : proof.steps)
        {
            if (!edges.isLegal(next.x, next.y) || !edges.canTravel(previous.x, previous.y, next.x - previous.x, next.y - previous.y))
            {
                return false;
            }
            previous = next;
        }
        return edges.isLegal(previous.x, previous.y) && validHeightData(LocalPoint.fromScene(previous.x, previous.y, view));
    }
    public boolean hasArrived()
    {
        if (inspectionDestinationFailed) return false;
        if (!visible || motion.isMoving() || motion.completedNode() == null || !matchesScene(client.getLocalPlayer())) return false;
        RoutePlanner.Node node = motion.completedNode();
        if (!collision(true).isLegal(node.x, node.y) || !validHeightData(LocalPoint.fromScene(node.x, node.y, view))) return false;
        if (intent == Intent.HOLD) return true;
        if (intent == Intent.DESTINATION) return baseX + node.x == destinationWorldX && baseY + node.y == destinationWorldY;
        int distance = MotionMath.distance(node.x, node.y, playerX, playerY);
        return intent == Intent.APPROACH && distance >= minDistance && distance <= maxDistance;
    }
    public WholeBoundsVisibility.Visibility getWholeBoundsVisibility()
    {
        return decodedAsset == null ? WholeBoundsVisibility.Visibility.UNKNOWN
            : WholeBoundsAdapter.classify(client, placement, bank.bounds());
    }
    public int getDistanceToPlayer()
    {
        return visible ? MotionMath.distance((int) motion.getX() >> 7, (int) motion.getY() >> 7, playerX, playerY) : Integer.MAX_VALUE;
    }
    public Model getPoseModel(String name, int sample)
    {
        return isReady() ? bank.pose(name, sample * 2) : null;
    }
    public Model getPreparedModel(int index) { return bank == null ? null : bank.preparedModel(index); }
    public String getLastClip() { return clip; }
    public String getPreparationSummary() { return getPreparationReport(); }
    public String getPreparationReport()
    {
        return bank == null ? "not started" : "models=" + bank.preparedCount() + " readyMs=" + bank.readyMillis()
            + " maxChunkMs=" + bank.maxChunkMillis() + " hash=" + NativeAsset.SHA256;
    }
}
