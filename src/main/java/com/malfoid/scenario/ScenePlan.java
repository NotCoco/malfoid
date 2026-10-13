package com.malfoid.scenario;

import com.malfoid.speech.SceneCandidate;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;

/** Authored physical intent for a director-admitted scene, never a precomputed route. */
public final class ScenePlan
{
	public enum Facing { PLAYER, TARGET, OBJECT, AWAY, NATIVE }
	public final boolean approach, run, voluntaryTrip;
	public final int minDistance, maxDistance, holdTicks, releaseTicks, runBurstMaxTicks;
	public final Facing facing;
	public final Integer standWorldX, standWorldY, subjectWorldX, subjectWorldY;
	/** Presentation may own the bubble without acquiring any part of the movement lane. */
	public final boolean preservesNativeMotion;

	private ScenePlan(boolean approach, boolean run, int min, int max, int hold, int release, int burst,
		Facing facing, Integer standX, Integer standY, Integer subjectX, Integer subjectY, boolean voluntaryTrip)
	{
		this.approach = approach; this.run = run; minDistance = min; maxDistance = max; holdTicks = hold;
		releaseTicks = release; runBurstMaxTicks = burst; this.facing = facing;
		standWorldX = standX; standWorldY = standY; subjectWorldX = subjectX; subjectWorldY = subjectY;
		this.voluntaryTrip = voluntaryTrip;
		preservesNativeMotion = facing == Facing.NATIVE;
	}
	/** A denied trip keeps the witnessed scene's original deadline; it does not create a later trip. */
	public boolean voluntaryTripAllowed(long tick, long lastActualVoluntaryMovementTick)
	{
		return !voluntaryTrip || lastActualVoluntaryMovementTick < 0
			|| tick >= lastActualVoluntaryMovementTick && tick - lastActualVoluntaryMovementTick >= 100;
	}

	public static ScenePlan forCandidate(SceneCandidate c, int companionDistance, boolean headVisible)
	{
		return forCandidate(c, companionDistance, headVisible, c.isIntentionalSilent());
	}
	public static ScenePlan forCandidate(SceneCandidate c, int companionDistance, boolean headVisible, boolean intentionalSilent)
	{
		return forCandidate(c, companionDistance, headVisible, intentionalSilent, c.getHoldTicks());
	}
	public static ScenePlan forCandidate(SceneCandidate c, int companionDistance, boolean headVisible, boolean intentionalSilent, int executionHoldTicks)
	{
		String id = c.getScenarioId(); Map<String, String> f = c.getConditionFacts();
		if ("F03".equals(id) || com.malfoid.motion.CreatureAwareness.isE5(id))
		{
			return new ScenePlan(false, false, 0, 0, 0, 0, 0, Facing.NATIVE, null, null, null, null, false);
		}
		if ("PLACE".equals(f.get("livingWorld")) || "CREATURE".equals(f.get("livingWorld")))
		{
			return new ScenePlan(false, false, 0, 0, 0, 0, 0, Facing.PLAYER, null, null, null, null, false);
		}
		if ("M01".equals(id)) { return player(true, true, 1, 2, 20, 10, 0); }
		if ("M02".equals(id)) { return player(true, false, 2, 2, 15, 10, 0); }
		if (c.isMilestone99()) { return player(true, true, 1, 2, 8, 0, 0); }
		if ("M05".equals(id)) { return player(true, true, 2, 2, 10, 0, 0); }
		if ("M07".equals(id)) { return player(true, companionDistance >= 8, 2, 3, c.getHoldTicks(), 0, 6); }
		if ("M08".equals(id) || "M69".equals(id) || "SCENERY".equals(f.get("livingWorld")))
		{
			return new ScenePlan(true, false, 0, 0, c.getHoldTicks(), 0, 0, Facing.OBJECT,
				integer(f, "targetX"), integer(f, "targetY"), integer(f, "objectX"), integer(f, "objectY"), true);
		}
		if ("U01".equals(id) && "4".equals(f.get("talkStreak")))
		{
			return new ScenePlan(false, false, 4, 5, 5, 0, 0, Facing.AWAY, null, null, null, null, false);
		}
		if ("E01".equals(id))
		{
			Integer animation = integer(f, "emoteAnimation");
			if (animation != null && animation == AnimationID.EMOTE_BECKON) { return player(true, false, 1, 2, 5, 0, 0); }
			Facing facing = animation != null && (animation == AnimationID.EMOTE_DANCE || animation == AnimationID.EMOTE_BLOW_KISS) ? Facing.AWAY : Facing.PLAYER;
			return new ScenePlan(false, false, 0, 8, c.getHoldTicks(), 0, 0, facing, null, null, null, null, false);
		}
		if ("U01".equals(id)) { return player(true, false, 2, 3, c.getHoldTicks(), 0, 0); }
		boolean approach = !intentionalSilent && (companionDistance > 5 || !headVisible);
		Facing facing = f.containsKey("targetKey") ? Facing.TARGET : Facing.PLAYER;
		return new ScenePlan(approach, false, 3, 5, "M06".equals(id) ? executionHoldTicks : c.getHoldTicks(), 0, 0, facing, null, null,
			integer(f, "targetX"), integer(f, "targetY"), false);
	}
	private static ScenePlan player(boolean approach, boolean run, int min, int max, int hold, int release, int burst)
	{
		return new ScenePlan(approach, run, min, max, hold, release, burst, Facing.PLAYER, null, null, null, null, false);
	}
	private static Integer integer(Map<String, String> facts, String key)
	{
		String value = facts.get(key);
		return value == null ? null : Integer.valueOf(value);
	}
}
