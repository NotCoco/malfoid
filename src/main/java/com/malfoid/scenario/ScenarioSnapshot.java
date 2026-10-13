package com.malfoid.scenario;

import com.malfoid.motion.MotionObservation;
import com.malfoid.motion.MotionHoldReceipt;
import com.malfoid.scenario.AdditionalWorldSensor.WorldSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Skill;

/** Immutable client-thread copy. Missing arrays/points remain unknown. */
public final class ScenarioSnapshot
{
	public final long tick, sessionGeneration, sceneRevision, presentSinceTick;
	public final boolean valid, present, userHidden, autoAbsent, dead, topLevel, instanced, directorIdle, inventoryBlocked, targetUnknown;
	public final boolean emergencySceneActive, interactionKnown, interactionPresent, inspectionSuppressed, outfitBlocked;
	public final int observedFacing, animation;
	public final int worldViewId;
	public final long placementGeneration;
	public final MotionObservation motion, motionCompletion;
	public final long activeSceneCandidateId;
	public final boolean activeSceneCommitted;
	public final MotionHoldReceipt motionHold;
	public final CancelledInterestObservation cancelledInterest;
	public final WorldSnapshot world;
	public final int currentHp, realHp, weaponId;
	public final Position player, companion;
	public final Target target;
	public final Set<String> liveNpcKeys;
	private final int[] xp, levels, boosted, equipment, inventoryIds, inventoryQuantities;
	private final boolean skillsKnown;
	public final List<InspectionTarget> objects;

	private ScenarioSnapshot(Builder b)
	{
		tick = b.tick; sessionGeneration = b.sessionGeneration; sceneRevision = b.sceneRevision;
		presentSinceTick = b.presentSinceTick; valid = b.valid; present = b.present;
		userHidden = b.userHidden; autoAbsent = b.autoAbsent; dead = b.dead;
		topLevel = b.topLevel; instanced = b.instanced; directorIdle = b.directorIdle;
		inventoryBlocked = b.inventoryBlocked;
		targetUnknown = b.targetUnknown;
		emergencySceneActive = b.emergencySceneActive;
		outfitBlocked = b.outfitBlocked;
		interactionKnown = b.interactionKnown; interactionPresent = b.interactionPresent; inspectionSuppressed = b.inspectionSuppressed; observedFacing = b.observedFacing; animation = b.animation;
		worldViewId = b.worldViewId; placementGeneration = b.placementGeneration; motion = b.motion; motionCompletion = b.motionCompletion;
		activeSceneCandidateId = b.activeSceneCandidateId; activeSceneCommitted = activeSceneCandidateId >= 0 && b.activeSceneCommitted; motionHold = b.motionHold;
		cancelledInterest = b.cancelledInterest;
		world = b.world;
		currentHp = b.currentHp; realHp = b.realHp; weaponId = b.weaponId;
		player = b.player; companion = b.companion; target = b.target;
		liveNpcKeys = b.liveNpcKeys == null ? null : Collections.unmodifiableSet(new HashSet<>(b.liveNpcKeys));
		xp = copy(b.xp); levels = copy(b.levels); boosted = copy(b.boosted);
		skillsKnown = knownSkills(xp, levels, boosted);
		equipment = copy(b.equipment); inventoryIds = copy(b.inventoryIds);
		inventoryQuantities = copy(b.inventoryQuantities);
		objects = Collections.unmodifiableList(new ArrayList<>(b.objects));
	}

	private ScenarioSnapshot(ScenarioSnapshot f, long candidateId, boolean committed, MotionHoldReceipt hold, MotionObservation currentMotion,
		MotionObservation completion, long placement, CancelledInterestObservation interest)
	{
		tick = f.tick; sessionGeneration = f.sessionGeneration; sceneRevision = f.sceneRevision; presentSinceTick = f.presentSinceTick;
		valid = f.valid; present = f.present; userHidden = f.userHidden; autoAbsent = f.autoAbsent; dead = f.dead;
		topLevel = f.topLevel; instanced = f.instanced; directorIdle = f.directorIdle; inventoryBlocked = f.inventoryBlocked;
		targetUnknown = f.targetUnknown; emergencySceneActive = f.emergencySceneActive; interactionKnown = f.interactionKnown;
		interactionPresent = f.interactionPresent; inspectionSuppressed = f.inspectionSuppressed; outfitBlocked = f.outfitBlocked;
		observedFacing = f.observedFacing; animation = f.animation; worldViewId = f.worldViewId; placementGeneration = placement;
		motion = currentMotion; motionCompletion = completion; activeSceneCandidateId = candidateId; activeSceneCommitted = candidateId >= 0 && committed; motionHold = hold;
		cancelledInterest = interest;
		world = f.world;
		currentHp = f.currentHp; realHp = f.realHp; weaponId = f.weaponId; player = f.player; target = f.target;
		companion = hold != null && hold.legalStanding && hold.candidateId == candidateId && hold.worldViewId == f.worldViewId
			&& hold.placementGeneration == placement && f.player != null && hold.plane == f.player.plane
			? new Position(hold.worldX, hold.worldY, hold.plane) : f.companion;
		liveNpcKeys = f.liveNpcKeys; xp = f.xp; levels = f.levels; boosted = f.boosted; equipment = f.equipment;
		skillsKnown = f.skillsKnown;
		inventoryIds = f.inventoryIds; inventoryQuantities = f.inventoryQuantities; objects = f.objects;
	}

	ScenarioSnapshot withActiveSceneMotion(long candidateId, MotionHoldReceipt hold)
	{
		return withActiveSceneMotion(candidateId, hold, false);
	}
	ScenarioSnapshot withActiveSceneMotion(long candidateId, MotionHoldReceipt hold, boolean committed)
	{
		return activeSceneCandidateId == candidateId && motionHold == hold && activeSceneCommitted == (candidateId >= 0 && committed) ? this
			: new ScenarioSnapshot(this, candidateId, committed, hold, motion, motionCompletion, placementGeneration, cancelledInterest);
	}
	ScenarioSnapshot withMotionObservations(MotionObservation currentMotion, MotionObservation completion, long placement)
	{
		return motion == currentMotion && motionCompletion == completion && placementGeneration == placement ? this
			: new ScenarioSnapshot(this, activeSceneCandidateId, activeSceneCommitted, motionHold, currentMotion, completion, placement, cancelledInterest);
	}
	ScenarioSnapshot withCancelledInterest(CancelledInterestObservation interest)
	{
		return cancelledInterest == interest ? this : new ScenarioSnapshot(this, activeSceneCandidateId, activeSceneCommitted, motionHold, motion, motionCompletion, placementGeneration, interest);
	}

	private static int[] copy(int[] source) { return source == null ? null : source.clone(); }
	private static boolean knownSkills(int[] xp, int[] levels, int[] boosted)
	{
		int count = Skill.values().length;
		if (xp == null || levels == null || boosted == null || xp.length != count || levels.length != count || boosted.length != count) { return false; }
		for (int i = 0; i < count; i++) { if (xp[i] < 0 || levels[i] < 0 || boosted[i] < 0) { return false; } }
		return true;
	}
	public boolean skillsKnown() { return skillsKnown; }
	public int xp(Skill skill) { return value(xp, skill.ordinal()); }
	public int level(Skill skill) { return value(levels, skill.ordinal()); }
	public int boosted(Skill skill) { return value(boosted, skill.ordinal()); }
	private static int value(int[] data, int slot) { return data == null || slot >= data.length ? -1 : data[slot]; }
	public int[] equipment() { return copy(equipment); }
	public int[] inventoryIds() { return copy(inventoryIds); }
	public int[] inventoryQuantities() { return copy(inventoryQuantities); }
	public boolean inventoryKnown() { return inventoryIds != null && inventoryQuantities != null && inventoryIds.length == 28 && inventoryQuantities.length == 28; }
	public int occupiedSlots(int id)
	{
		if (!inventoryKnown() || inventoryIds.length != 28) { return -1; }
		int count = 0;
		for (int i = 0; i < 28; i++) { if (inventoryIds[i] == id && inventoryQuantities[i] > 0) { count++; } }
		return count;
	}
	public long itemCount(int id)
	{
		if (!inventoryKnown()) { return -1; }
		long total = 0;
		for (int i = 0; i < inventoryIds.length; i++)
		{
			if (inventoryIds[i] == id && inventoryQuantities[i] > 0) { total += inventoryQuantities[i]; }
		}
		return total;
	}

	public static final class Position
	{
		public final int x, y, plane;
		public Position(int x, int y, int plane) { this.x = x; this.y = y; this.plane = plane; }
		public int distance(Position other)
		{
			return other == null || other.plane != plane ? Integer.MAX_VALUE : Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
		}
		public int region() { return ((x >> 6) << 8) | (y >> 6); }
		@Override public boolean equals(Object other)
		{
			if (!(other instanceof Position)) { return false; }
			Position p = (Position) other;
			return x == p.x && y == p.y && plane == p.plane;
		}
		@Override public int hashCode() { return (x * 31 + y) * 31 + plane; }
	}

	public static final class Target
	{
		public final String key, name;
		public final boolean alive;
		public final Position position;
		public Target(String key, String name, boolean alive, Position position)
		{
			this.key = key; this.name = name; this.alive = alive; this.position = position;
		}
	}

	public static final class InspectionTarget
	{
		public final String key, name, scenerySessionKey;
		public final int minX, minY, maxX, maxY, plane, pathSteps, resolvedDefinitionId;
		public final Position stand;
		public InspectionTarget(String key, String name, int minX, int minY, int maxX, int maxY, int plane, Position stand, int pathSteps)
		{
			this(key, name, minX, minY, maxX, maxY, plane, stand, pathSteps, null, -1);
		}
		public InspectionTarget(String key, String name, int minX, int minY, int maxX, int maxY, int plane, Position stand, int pathSteps, String scenerySessionKey)
		{
			this(key, name, minX, minY, maxX, maxY, plane, stand, pathSteps, scenerySessionKey, -1);
		}
		public InspectionTarget(String key, String name, int minX, int minY, int maxX, int maxY, int plane, Position stand, int pathSteps, String scenerySessionKey, int resolvedDefinitionId)
		{
			this.key = key; this.name = name; this.minX = minX; this.minY = minY;
			this.maxX = maxX; this.maxY = maxY; this.plane = plane; this.stand = stand; this.pathSteps = pathSteps;
			this.scenerySessionKey = scenerySessionKey;
			this.resolvedDefinitionId = resolvedDefinitionId;
		}
		public int distance(Position p)
		{
			if (p == null || p.plane != plane) { return Integer.MAX_VALUE; }
			return Math.max(Math.max(minX - p.x, p.x - maxX), Math.max(minY - p.y, p.y - maxY));
		}
	}

	public static final class Builder
	{
		public long tick, sessionGeneration = 1, sceneRevision = 1, presentSinceTick;
		public boolean valid = true, present = true, userHidden, autoAbsent, dead, topLevel = true, instanced, directorIdle = true, inventoryBlocked, targetUnknown;
		public boolean emergencySceneActive, interactionKnown = true, interactionPresent, inspectionSuppressed, outfitBlocked;
		public int observedFacing = -1, animation = -1;
		public int worldViewId = Integer.MIN_VALUE;
		public long placementGeneration = -1;
		public MotionObservation motion, motionCompletion;
		public long activeSceneCandidateId = -1;
		public boolean activeSceneCommitted;
		public MotionHoldReceipt motionHold;
		public CancelledInterestObservation cancelledInterest;
		public WorldSnapshot world;
		public int currentHp = 99, realHp = 99, weaponId = -1;
		public Position player, companion;
		public Target target;
		public Set<String> liveNpcKeys;
		public int[] xp, levels, boosted, equipment, inventoryIds, inventoryQuantities;
		public List<InspectionTarget> objects = Collections.emptyList();
		public ScenarioSnapshot build() { return new ScenarioSnapshot(this); }
	}
}
