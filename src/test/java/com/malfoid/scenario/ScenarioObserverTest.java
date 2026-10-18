package com.malfoid.scenario;

import com.malfoid.speech.SceneCandidate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.GameObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.LocalPoint;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.malfoid.scenario.ScenarioReplay.find;

public class ScenarioObserverTest
{
	@Test public void m14UnselectedNpcDeathDespawnOrTransformationRevokesBeforeAnotherCommit()
	{
		for (int loss = 0; loss < 4; loss++)
		{
			Fixture f = new Fixture(); SceneCandidate candidate = f.returnToA();
			assertNotNull(candidate); assertTrue(f.observer.candidateStillValid(candidate));
			if (loss == 0) { f.observer.onActorDeath(new ActorDeath(f.b)); }
			else if (loss == 1) { f.observer.onNpcDespawned(new NpcDespawned(f.b)); }
			else if (loss == 2) { f.observer.onNpcChanged(new NpcChanged(f.b, f.composition)); }
			else { when(f.b.getTransformedComposition()).thenReturn(null); }
			assertFalse(f.observer.candidateStillValid(candidate));
			assertFalse(f.observer.candidateContinuationValid(candidate));
		}
	}

	@Test public void transformationCannotBeReplayedAsAWitnessedTargetSwitch()
	{
		Fixture f = new Fixture(); f.hit(f.a); f.next(); f.next(); f.change(f.b); f.hit(f.b); f.next(); f.next();
		f.observer.onNpcChanged(new NpcChanged(f.b, f.composition)); f.change(f.a); f.hit(f.a);
		assertNull(find(f.next(), "M14"));
	}

	@Test public void observedDeathDoesNotNeedActorDeadFlagToRevokeCurrentTarget()
	{
		Fixture f = new Fixture(); SceneCandidate candidate = f.returnToA();
		assertFalse(f.a.isDead()); f.observer.onActorDeath(new ActorDeath(f.a));
		assertFalse(f.observer.candidateStillValid(candidate)); assertFalse(f.observer.candidateContinuationValid(candidate));
	}

	@Test public void freshInspectionOfferNeedsCurrentRouteProofButAdmittedObjectLeaseSurvivesApproach()
	{
		Fixture f = new Fixture(); f.change(null); when(f.player.getLocalLocation()).thenReturn(new LocalPoint(6 * 128 + 64, 6 * 128 + 64));
		when(f.view.getBaseX()).thenReturn(3094); when(f.view.getBaseY()).thenReturn(3194);
		GameObject object = mock(GameObject.class); ObjectComposition definition = mock(ObjectComposition.class); Tile tile = mock(Tile.class);
		when(object.getWorldView()).thenReturn(f.view); when(object.getId()).thenReturn(1000); when(object.getPlane()).thenReturn(0);
		when(object.getSceneMinLocation()).thenReturn(new Point(12, 6)); when(object.getSceneMaxLocation()).thenReturn(new Point(12, 6));
		when(f.client.getObjectDefinition(1000)).thenReturn(definition); when(definition.getName()).thenReturn("Oak tree");
		when(tile.getGameObjects()).thenReturn(new GameObject[]{object}); Tile[][][] tiles = new Tile[1][13][13]; tiles[0][12][6] = tile;
		when(f.view.getScene().getTiles()).thenReturn(tiles); boolean[] routeCurrent = {true}; int[] probes = {0};
		f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> {
			probes[0]++; return routeCurrent[0] ? new InspectionTarget(key, name, minX, minY, maxX, maxY, plane, new Position(3105, 3200, 0), 8) : null;
		});
		assertNull(find(f.next(), "M08")); assertNull(find(f.next(), "M08")); routeCurrent[0] = false;
		assertNull(find(f.next(), "M08")); assertEquals(3, probes[0]); routeCurrent[0] = true;
		SceneCandidate c = find(f.next(), "M08"); assertNotNull(c); routeCurrent[0] = false;
		f.next(false); assertEquals(4, probes[0]); assertTrue(f.observer.candidateContinuationValid(c));
	}

	private static final class Fixture
	{
		final Client client = mock(Client.class);
		final WorldView view = mock(WorldView.class);
		final Player player = mock(Player.class);
		final NPC a = mock(NPC.class), b = mock(NPC.class);
		final NPCComposition composition = mock(NPCComposition.class);
		final ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
		long tick;
		Fixture()
		{
			when(client.getLocalPlayer()).thenReturn(player);
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN); when(player.getWorldView()).thenReturn(view);
			when(player.getWorldLocation()).thenReturn(new WorldPoint(3100, 3200, 0)); when(player.getInteracting()).thenReturn(a);
			when(view.getScene()).thenReturn(mock(Scene.class)); when(view.getCollisionMaps()).thenReturn(new CollisionData[1]);
			when(view.isTopLevel()).thenReturn(true); when(composition.getName()).thenReturn("Goblin");
			for (NPC npc : Arrays.asList(a, b))
			{
				when(npc.getWorldView()).thenReturn(view); when(npc.getTransformedComposition()).thenReturn(composition);
				when(npc.getWorldLocation()).thenReturn(new WorldPoint(npc == a ? 3101 : 3102, 3200, 0));
			}
			doReturn(new IndexedObjectSet<NPC>()
			{
				public NPC byIndex(int index) { return index == 0 ? a : index == 1 ? b : null; }
				public Iterator<NPC> iterator() { return Arrays.asList(a, b).iterator(); }
			}).when(view).npcs();
			int[] levels = new int[Skill.values().length], xp = new int[Skill.values().length]; Arrays.fill(levels, 10); Arrays.fill(xp, 100);
			levels[Skill.HITPOINTS.ordinal()] = 100;
			when(client.getRealSkillLevels()).thenReturn(levels); when(client.getBoostedSkillLevels()).thenReturn(levels);
			when(client.getSkillExperiences()).thenReturn(xp); when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(100);
			when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(100);
			observer.setScenarioEnabled(id -> true); observer.setPresence(true, false, false);
			for (int i = 0; i < 12; i++) { next(); }
		}
		SceneCandidate returnToA()
		{
			hit(a); next(); next(); change(b); hit(b); next(); next(); change(a); hit(a); return find(next(), "M14");
		}
		void change(NPC target)
		{
			when(player.getInteracting()).thenReturn(target); observer.onInteractingChanged(new InteractingChanged(player, target));
		}
		void hit(NPC target)
		{
			Hitsplat splat = mock(Hitsplat.class); when(splat.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_ME);
			when(splat.getAmount()).thenReturn(2); when(splat.isMine()).thenReturn(true);
			HitsplatApplied event = new HitsplatApplied(); event.setActor(target); event.setHitsplat(splat); observer.onHitsplatApplied(event);
		}
		List<SceneCandidate> next() { return next(true); }
		List<SceneCandidate> next(boolean idle) { return observer.closeGameTick(++tick, 1, 1, true, 0, 3103, 3200, idle); }
	}
}
