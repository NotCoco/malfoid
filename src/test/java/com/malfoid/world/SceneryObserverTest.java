package com.malfoid.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.motion.MotionObservation;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.scenario.ScenarioSnapshot.InspectionTarget;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Scenery shares M08's callback registry, local refresh and real-route proof interface. */
public class SceneryObserverTest
{
	@Test public void multiTileObjectsAreDeduplicatedAndOnlyOneM08RouteProbeRunsPerTick() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3105); f.object(1001, "Statue", 3106, 3106);
		assertNotNull(find(f.next(), "SC-BOOKS")); assertEquals(1, f.probes.size());
		f.startAllowed = false; f.next(false); assertEquals(1, f.probes.size());
	}

	@Test public void unseenKindIsProbedBeforeANearerObjectOfAnAlreadyVisitedKind() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104);
		SceneCandidate books = find(f.next(), "SC-BOOKS"); f.arrive(books);
		f.object(1001, "Bookshelf", 3102, 3102); f.object(1002, "Statue", 3106, 3106);
		assertNotNull(find(f.next(), "SC-STATUE")); assertEquals(Arrays.asList("Bookcase", "Statue"), f.probes);
	}

	@Test public void twoMissingClosestProofsAdvanceToTheNextSceneryUsingOneProbePerCommittedTick() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); f.object(1001, "Statue", 3106, 3106);
		f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> {
			f.probes.add(name); return "Bookcase".equals(name) ? null
				: new InspectionTarget(key, name, minX, minY, maxX, maxY, plane, new Position(minX - 1, minY, plane), 8);
		});
		assertNull(find(f.next(), "SC-BOOKS")); assertEquals(1, f.probes.size());
		assertNull(find(f.next(), "SC-STATUE")); assertEquals(2, f.probes.size());
		assertNotNull(find(f.next(), "SC-STATUE")); assertEquals(3, f.probes.size());
		assertEquals(Arrays.asList("Bookcase", "Bookcase", "Statue"), f.probes);
	}

	@Test public void exhaustedSceneryProbesStartAFreshCycleWithoutInventingABlockedOutcome() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); f.object(1001, "Statue", 3106, 3106);
		f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> { f.probes.add(name); return null; });
		for (int i = 0; i < 6; i++)
		{
			List<SceneCandidate> output = f.next(); assertNull(find(output, "SC-BOOKS")); assertNull(find(output, "SC-STATUE"));
			assertEquals(i + 1, f.probes.size());
		}
		assertEquals(Arrays.asList("Bookcase", "Bookcase", "Statue", "Statue", "Bookcase", "Bookcase"), f.probes);
	}

	@Test public void changedProbeOriginRestartsFromTheNearestScenery() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); f.object(1001, "Statue", 3106, 3106);
		f.observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> { f.probes.add(name); return null; });
		f.next(); f.next(); f.companionX++; f.next();
		assertEquals(Arrays.asList("Bookcase", "Bookcase", "Bookcase"), f.probes);
	}

	@Test public void aSilentOrSpeechGuardSuppressedArrivalStillSurvivesSceneReloadAndObjectReplacement() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); SceneCandidate c = find(f.next(), "SC-BOOKS");
		f.arrive(c); assertTrue(f.observer.sceneryArrived(c));
		f.observer.resetScene(); f.revision++; f.object(1000, "Bookcase", 3104, 3104);
		for (int i = 0; i < 16; i++) { assertNull(find(f.next(), "SC-BOOKS")); }
		assertEquals(1, f.probes.size());
		f.observer.setPresence(false, false, true); f.next(false, false); f.observer.setPresence(true, false, false); f.epoch = f.tick + 1;
		for (int i = 0; i < 16; i++) { assertNull(find(f.next(), "SC-BOOKS")); }
		assertEquals(1, f.probes.size());
		f.observer.resetSession(); f.generation++; SceneCandidate fresh = null;
		for (int i = 0; i < 16; i++) { SceneCandidate next = find(f.next(), "SC-BOOKS"); if (next != null) { fresh = next; } }
		assertNotNull(fresh);
	}

	@Test public void aCurrentCycleImpostorChangeWithTheSameNameStillRevokesTheDefinitionProof() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); SceneCandidate c = find(f.next(), "SC-BOOKS");
		assertTrue(f.observer.candidateStillValid(c)); ObjectComposition transformed = mock(ObjectComposition.class);
		when(transformed.getId()).thenReturn(2000); when(transformed.getName()).thenReturn("Bookcase");
		ObjectComposition source = f.client.getObjectDefinition(1000); when(source.getImpostorIds()).thenReturn(new int[]{2000});
		when(source.getImpostor()).thenReturn(transformed);
		assertFalse(f.observer.candidateContinuationValid(c)); assertFalse(f.observer.sceneryArrived(c));
	}

	@Test public void aSupportedTransformationCannotMakeTheSamePhysicalObjectVisitableAgain() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); f.arrive(find(f.next(), "SC-BOOKS"));
		ObjectComposition transformed = mock(ObjectComposition.class); when(transformed.getId()).thenReturn(2000);
		when(transformed.getName()).thenReturn("Bookshelf"); ObjectComposition source = f.client.getObjectDefinition(1000);
		when(source.getImpostorIds()).thenReturn(new int[]{2000}); when(source.getImpostor()).thenReturn(transformed);
		assertNull(find(f.next(), "SC-BOOKS")); assertEquals(1, f.probes.size());
	}

	@Test public void untransformedDefinitionsNeverCallTheUnsafeImpostorGetter() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase", 3104, 3104); SceneCandidate c = find(f.next(), "SC-BOOKS");
		assertTrue(f.observer.candidateStillValid(c)); verify(f.client.getObjectDefinition(1000), never()).getImpostor();
	}

	@Test public void despawnFootprintNameAndOtherViewLossesRejectBeforeAnotherCommittedTick() throws Exception
	{
		for (int loss = 0; loss < 4; loss++)
		{
			Fixture f = new Fixture(); GameObject object = f.object(1000, "Bookcase", 3104, 3104);
			SceneCandidate c = find(f.next(), "SC-BOOKS"); assertTrue(f.observer.candidateStillValid(c));
			if (loss == 0)
			{
				GameObjectDespawned event = new GameObjectDespawned(); event.setGameObject(object); f.observer.onGameObjectDespawned(event);
			}
			if (loss == 1) { when(object.getSceneMinLocation()).thenReturn(new Point(15, 10)); }
			if (loss == 2) { when(f.client.getObjectDefinition(1000).getName()).thenReturn("Bookcase (empty)"); }
			if (loss == 3) { when(object.getWorldView()).thenReturn(mock(WorldView.class)); }
			assertFalse(f.observer.candidateContinuationValid(c)); assertFalse(f.observer.sceneryArrived(c));
		}
	}

	@Test public void wholeNameMatchingDoesNotProbeAnEmptyBookcaseButRemovesTagsAndCase() throws Exception
	{
		Fixture f = new Fixture(); f.object(1000, "Bookcase (empty)", 3104, 3104);
		assertNull(find(f.next(), "SC-BOOKS")); assertTrue(f.probes.isEmpty());
		when(f.client.getObjectDefinition(1000).getName()).thenReturn("<col=ffffff>bOoKcAsE</col>");
		assertNotNull(find(f.next(), "SC-BOOKS")); assertEquals(1, f.probes.size());
	}

	@Test public void anotherViewOrPlaneCannotSupplyASceneryRouteProof() throws Exception
	{
		for (boolean otherView : new boolean[]{false, true})
		{
			Fixture f = new Fixture(); GameObject object = f.object(1000, "Bookcase", 3104, 3104);
			if (otherView) { when(object.getWorldView()).thenReturn(mock(WorldView.class)); }
			else { when(object.getPlane()).thenReturn(1); }
			assertNull(find(f.next(), "SC-BOOKS")); assertTrue(f.probes.isEmpty());
		}
	}

	@Test public void theSceneryStartGateLeavesM08ProbesAndAlreadyAdmittedObjectLeasesAlone() throws Exception
	{
		Fixture denied = new Fixture(); denied.startAllowed = false; denied.object(1000, "Bookcase", 3104, 3104);
		assertNull(find(denied.next(), "SC-BOOKS")); assertTrue(denied.probes.isEmpty());
		denied.object(1001, "Oak tree", 3106, 3106); assertNotNull(find(denied.next(), "M08"));
		assertEquals(Collections.singletonList("Oak tree"), denied.probes);
		Fixture active = new Fixture(); active.object(1000, "Bookcase", 3104, 3104); SceneCandidate c = find(active.next(), "SC-BOOKS");
		active.startAllowed = false; active.next(false); assertTrue(active.observer.candidateContinuationValid(c));
		assertEquals(1, active.probes.size());
	}

	@Test public void aNameInBothDataAndM08TablesKeepsM08AndItsOriginalLineFacts() throws Exception
	{
		Fixture f = new Fixture(overlapData()); f.object(1000, "Fountain", 3104, 3104);
		List<SceneCandidate> output = f.next(); SceneCandidate c = find(output, "M08"); assertNotNull(c);
		assertNull(find(output, "SC-GRAVE")); assertEquals("FOUNTAIN", c.getConditionFacts().get("objectKind"));
		assertNull(c.getConditionFacts().get("livingWorld"));
	}

	private static LivingWorldData overlapData() throws Exception
	{
		byte[] bytes;
		try (InputStream input = SceneryObserverTest.class.getResourceAsStream(LivingWorldData.RESOURCE))
		{
			assertNotNull(input); bytes = input.readAllBytes();
		}
		JsonObject root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		root.getAsJsonArray("scenery").get(0).getAsJsonObject().getAsJsonArray("objectNames").add("fountain");
		bytes = root.toString().getBytes(StandardCharsets.UTF_8); StringBuilder hash = new StringBuilder();
		for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) { hash.append(String.format("%02x", value & 255)); }
		return LivingWorldData.parse(bytes, hash.toString());
	}
	private static SceneCandidate find(List<SceneCandidate> output, String id)
	{
		return output.stream().filter(candidate -> id.equals(candidate.getScenarioId())).findFirst().orElse(null);
	}
	private static final class Fixture
	{
		final Client client = mock(Client.class);
		final Player player = mock(Player.class);
		final WorldView view = mock(WorldView.class);
		final Tile[][][] tiles = new Tile[1][24][24];
		final List<String> probes = new ArrayList<>();
		final ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
		long tick, generation = 1, revision = 1, epoch;
		int companionX = 3103;
		boolean startAllowed = true;
		Fixture() throws Exception { this(LivingWorldData.load(SceneryObserverTest.class)); }
		Fixture(LivingWorldData data)
		{
			when(client.getLocalPlayer()).thenReturn(player); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
			when(player.getWorldView()).thenReturn(view); when(player.getWorldLocation()).thenReturn(new WorldPoint(3100, 3200, 0));
			when(player.getLocalLocation()).thenReturn(new LocalPoint(10 * 128 + 64, 10 * 128 + 64));
			Scene scene = mock(Scene.class); when(scene.getTiles()).thenReturn(tiles); when(view.getScene()).thenReturn(scene);
			when(view.getCollisionMaps()).thenReturn(new CollisionData[1]); when(view.getId()).thenReturn(-1);
			when(view.isTopLevel()).thenReturn(true); when(view.getBaseX()).thenReturn(3090); when(view.getBaseY()).thenReturn(3190);
			when(view.getSizeX()).thenReturn(24); when(view.getSizeY()).thenReturn(24);
			doReturn(new IndexedObjectSet<NPC>()
			{
				public NPC byIndex(int index) { return null; }
				public Iterator<NPC> iterator() { return Collections.emptyIterator(); }
			}).when(view).npcs();
			int[] levels = new int[Skill.values().length], xp = new int[Skill.values().length];
			Arrays.fill(levels, 10); Arrays.fill(xp, 100); levels[Skill.HITPOINTS.ordinal()] = 100;
			when(client.getRealSkillLevels()).thenReturn(levels); when(client.getBoostedSkillLevels()).thenReturn(levels);
			when(client.getSkillExperiences()).thenReturn(xp); when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(100);
			when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(100);
			observer.setScenarioEnabled(id -> true); observer.setPresence(true, false, false); observer.setLivingWorldData(data);
			observer.setSceneryStartAllowed(() -> startAllowed);
			observer.setInspectionReachability((key, name, minX, minY, maxX, maxY, plane) -> {
				probes.add(name); return new InspectionTarget(key, name, minX, minY, maxX, maxY, plane, new Position(minX - 1, minY, plane), 8);
			});
			for (int i = 0; i < 15; i++) { next(); }
		}
		GameObject object(int id, String name, int minX, int maxX)
		{
			GameObject object = mock(GameObject.class); ObjectComposition definition = mock(ObjectComposition.class);
			when(object.getWorldView()).thenReturn(view); when(object.getPlane()).thenReturn(0); when(object.getId()).thenReturn(id);
			when(object.getSceneMinLocation()).thenReturn(new Point(minX - 3090, 10));
			when(object.getSceneMaxLocation()).thenReturn(new Point(maxX - 3090, 10));
			when(definition.getId()).thenReturn(id); when(definition.getName()).thenReturn(name); when(client.getObjectDefinition(id)).thenReturn(definition);
			Tile tile = mock(Tile.class); when(tile.getGameObjects()).thenReturn(new GameObject[]{object});
			for (int x = minX - 3090; x <= maxX - 3090; x++) { tiles[0][x][10] = tile; }
			return object;
		}
		List<SceneCandidate> next() { return next(true); }
		List<SceneCandidate> next(boolean idle) { return next(idle, true); }
		List<SceneCandidate> next(boolean idle, boolean present)
		{
			return observer.closeGameTick(++tick, generation, revision, present, epoch, companionX, 3200, idle);
		}
		void arrive(SceneCandidate c)
		{
			assertNotNull(c); MotionObservation.Builder arrival = new MotionObservation.Builder();
			arrival.sourceCandidateId = c.getId(); arrival.requestId = c.getId() + 1; arrival.status = MotionObservation.Status.ARRIVED;
			arrival.purpose = MotionObservation.Purpose.OTHER; arrival.legalGoal = true; arrival.fixedGoal = true;
			arrival.completedEdges = 8; arrival.plannedSteps = 8; arrival.completedTick = tick + 1;
			arrival.worldViewId = -1; arrival.placementGeneration = 17; arrival.plane = 0;
			arrival.goalWorldX = companionX = Integer.parseInt(c.getConditionFacts().get("targetX")); arrival.goalWorldY = 3200;
			observer.setMotionObservations(arrival.build(), arrival.build(), 17); next(false); assertTrue(observer.sceneryArrived(c));
		}
	}
}
