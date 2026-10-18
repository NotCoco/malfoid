package com.malfoid.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.scenario.ScenarioRules.CombatState;
import com.malfoid.scenario.ScenarioSnapshot.Position;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;

import static org.junit.Assert.*;

public class EmoteRulesTest
{
	@Test public void startupPlayingAnimationAndRepeatedSnapshotsStaySilent()
	{
		Fixture f = new Fixture();
		assertNull(f.close(1, AnimationID.EMOTE_WAVE));
		assertNull(f.close(2, AnimationID.EMOTE_WAVE));
		assertNull(f.close(3, -1));
		SceneCandidate wave = f.close(4, AnimationID.EMOTE_WAVE);
		assertNotNull(wave);
		assertNull(f.close(4, AnimationID.EMOTE_WAVE));
		for (int tick = 5; tick <= 30; tick++) { assertNull(f.close(tick, AnimationID.EMOTE_WAVE)); }
	}

	@Test public void authoredEmotesHaveSilentPhysicalPlansAndPriorityForty()
	{
		int[] animations = {AnimationID.EMOTE_WAVE, AnimationID.EMOTE_BECKON, AnimationID.EMOTE_BOW,
			AnimationID.EMOTE_DANCE, AnimationID.EMOTE_BLOW_KISS};
		int[] holds = {3, 5, 3, 4, 6};
		for (int i = 0; i < animations.length; i++)
		{
			Fixture f = new Fixture(); f.close(1, -1);
			SceneCandidate c = f.close(2, animations[i]); assertNotNull(c);
			assertEquals("E01", c.getScenarioId()); assertEquals(40, c.getPriority());
			assertTrue(c.isIntentionalSilent()); assertEquals(holds[i], c.getHoldTicks());
			ScenePlan plan = ScenePlan.forCandidate(c, 8, true);
			assertFalse(plan.run); assertEquals(holds[i], plan.holdTicks);
			if (animations[i] == AnimationID.EMOTE_BECKON)
			{
				assertTrue(plan.approach); assertEquals(1, plan.minDistance); assertEquals(2, plan.maxDistance);
				assertEquals(ScenePlan.Facing.PLAYER, plan.facing);
			}
			else
			{
				assertFalse(plan.approach); assertEquals(0, plan.minDistance); assertEquals(8, plan.maxDistance);
				assertEquals(i >= 3 ? ScenePlan.Facing.AWAY : ScenePlan.Facing.PLAYER, plan.facing);
			}
		}
	}

	@Test public void globalCooldownIsTenTicksFromArrivalRatherThanObservation()
	{
		for (int age : new int[] {9, 10})
		{
			Fixture f = new Fixture(); f.close(1, -1);
			SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE); f.advance(5);
			assertTrue(f.arrive(wave)); f.advance(5 + age - 1);
			assertEquals(age == 10, f.close(5 + age, AnimationID.EMOTE_BOW) != null);
		}
	}

	@Test public void perEmoteCooldownIsTwentyTicksAndDuplicateArrivalCannotExtendIt()
	{
		for (int age : new int[] {19, 20})
		{
			Fixture f = new Fixture(); f.close(1, -1);
			SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE); f.advance(5);
			assertTrue(f.arrive(wave)); f.advance(6); assertFalse(f.arrive(wave));
			f.advance(5 + age - 1);
			assertEquals(age == 20, f.close(5 + age, AnimationID.EMOTE_WAVE) != null);
		}
	}

	@Test public void unarrivedOrCancelledReactionDoesNotConsumeAnotherEmoteCooldown()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE);
		f.frame.emergencySceneActive = true; f.close(3, -1);
		assertFalse(f.rules.continuationValid(wave, f.latest, f.combat, -100));
		assertFalse(f.arrive(wave));
		f.frame.emergencySceneActive = false;
		assertNotNull(f.close(4, AnimationID.EMOTE_BOW));
	}

	@Test public void occurrenceDeniedByCooldownCannotBeReplayedWhenTheCooldownClears()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE); assertTrue(f.arrive(wave));
		assertNull(f.close(3, AnimationID.EMOTE_BOW));
		for (int tick = 4; tick <= 20; tick++) { assertNull(f.close(tick, AnimationID.EMOTE_BOW)); }
		f.close(21, -1); assertNotNull(f.close(22, AnimationID.EMOTE_BOW));
	}

	@Test public void arrivalRejectsAnotherPendingEmoteWithoutCancellingItsOwnCommittedHold()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE);
		SceneCandidate bow = f.close(3, AnimationID.EMOTE_BOW);
		assertNotNull(bow); assertTrue(f.arrive(wave));
		assertTrue(f.rules.continuationValid(wave, f.latest, f.combat, -100));
		assertFalse(f.rules.continuationValid(bow, f.latest, f.combat, -100));
		assertFalse(f.arrive(bow));
	}

	@Test public void animationStartedWhileAbsentNeverBecomesANewWitnessOnReturn()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		f.frame.present = false; assertNull(f.close(2, AnimationID.EMOTE_WAVE));
		f.frame.present = true; f.frame.presentSinceTick = Fixture.BASE + 3;
		assertNull(f.close(3, AnimationID.EMOTE_WAVE));
		assertNull(f.close(4, AnimationID.EMOTE_WAVE));
		f.close(5, -1); assertNotNull(f.close(6, AnimationID.EMOTE_WAVE));
	}

	@Test public void unknownAnimationGapAndSceneReplacementEstablishFreshBaselines()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		assertNull(f.close(2, -2)); assertNull(f.close(3, AnimationID.EMOTE_WAVE));
		assertNull(f.close(10, AnimationID.EMOTE_BOW));
		assertNull(f.close(11, AnimationID.EMOTE_BOW));
		f.close(12, -1); SceneCandidate bow = f.close(13, AnimationID.EMOTE_BOW); assertNotNull(bow);
		f.frame.sceneRevision++; assertNull(f.close(14, AnimationID.EMOTE_DANCE));
		assertFalse(f.rules.continuationValid(bow, f.latest, f.combat, -100));
		assertNull(f.close(15, AnimationID.EMOTE_DANCE));
		f.close(16, -1); assertNotNull(f.close(17, AnimationID.EMOTE_DANCE));
	}

	@Test public void replacementOutfitAnimationsAreNeverGuessedToBeSupportedEmotes()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		assertNull(f.close(2, 5695)); assertNull(f.close(3, 1483));
	}

	@Test public void separationEightIsAllowedButMissingLocationsOrDifferentPlanesAreNot()
	{
		for (int distance : new int[] {8, 9})
		{
			Fixture f = new Fixture(); f.frame.companion = new Position(3100 + distance, 3200, 0); f.close(1, -1);
			assertEquals(distance == 8, f.close(2, AnimationID.EMOTE_WAVE) != null);
		}
		Fixture plane = new Fixture(); plane.frame.companion = new Position(3100, 3200, 1); plane.close(1, -1);
		assertNull(plane.close(2, AnimationID.EMOTE_WAVE));
		Fixture missing = new Fixture(); missing.close(1, -1); missing.frame.companion = null;
		assertNull(missing.close(2, AnimationID.EMOTE_WAVE));
		Fixture player = new Fixture(); player.close(1, -1); player.frame.player = null;
		assertNull(player.close(2, AnimationID.EMOTE_WAVE));
	}

	@Test public void confirmedCombatBlocksButUnknownCombatIsAllowedByTheAuthoredRule()
	{
		for (CombatState combat : CombatState.values())
		{
			Fixture f = new Fixture(); f.combat = combat; f.close(1, -1);
			assertEquals(combat != CombatState.CONFIRMED, f.close(2, AnimationID.EMOTE_WAVE) != null);
		}
		Fixture f = new Fixture(); f.close(1, -1); f.combat = CombatState.CONFIRMED;
		assertNull(f.close(2, AnimationID.EMOTE_WAVE)); f.combat = CombatState.NO_CONFIRMED_EVIDENCE;
		assertNull(f.close(3, AnimationID.EMOTE_WAVE));
	}

	@Test public void invalidDeadAndTerminalDeathSnapshotsCannotReact()
	{
		Fixture invalid = new Fixture(); invalid.close(1, -1); invalid.frame.valid = false;
		assertNull(invalid.close(2, AnimationID.EMOTE_WAVE));
		Fixture dead = new Fixture(); dead.close(1, -1); dead.frame.dead = true;
		assertNull(dead.close(2, AnimationID.EMOTE_WAVE));
		Fixture hp = new Fixture(); hp.close(1, -1); hp.frame.currentHp = 0;
		assertNull(hp.close(2, AnimationID.EMOTE_WAVE));
		Fixture terminal = new Fixture(); terminal.close(1, -1);
		ScenarioBatch death = new ScenarioBatch(true, false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptySet());
		assertNull(terminal.close(2, AnimationID.EMOTE_WAVE, death));
	}

	@Test public void sceneResetPreservesNumericCooldownButSessionResetClearsIt()
	{
		for (boolean sessionReset : new boolean[] {false, true})
		{
			Fixture f = new Fixture(); f.close(1, -1);
			SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE); assertTrue(f.arrive(wave)); f.advance(4);
			if (sessionReset) { f.rules.resetSession(); } else { f.rules.reset(); f.frame.sceneRevision++; }
			assertNull(f.close(5, -1));
			assertEquals(sessionReset, f.close(6, AnimationID.EMOTE_WAVE) != null);
		}
	}

	@Test public void committedHoldSurvivesItsOwnCooldownButNotAbsenceOrEmergency()
	{
		Fixture f = new Fixture(); f.close(1, -1);
		SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE); assertTrue(f.arrive(wave)); f.close(3, -1);
		assertTrue(f.rules.continuationValid(wave, f.latest, f.combat, -100));
		f.frame.emergencySceneActive = true; f.close(4, -1);
		assertFalse(f.rules.continuationValid(wave, f.latest, f.combat, -100));
		f.frame.emergencySceneActive = false; f.frame.present = false; f.close(5, -1);
		assertFalse(f.rules.continuationValid(wave, f.latest, f.combat, -100));
		f.frame.present = true; f.frame.presentSinceTick = Fixture.BASE + 6; f.close(6, -1);
		assertFalse(f.rules.continuationValid(wave, f.latest, f.combat, -100));
	}

	@Test public void silentEmoteUsesNoSpeechReservationAndEmergencyPreemptsItsHold() throws Exception
	{
		Fixture f = new Fixture(); f.close(1, -1); SceneCandidate wave = f.close(2, AnimationID.EMOTE_WAVE);
		AtomicLong clock = new AtomicLong();
		Random noSpeechDraw = new Random(0)
		{
			@Override public int nextInt(int bound) { fail("Silent emote drew speech randomness"); return 0; }
		};
		SpeechGuard guard = new SpeechGuard(clock::get, noSpeechDraw, 1000, 200);
		SpeechDirector director = new SpeechDirector(testCatalogue(), clock::get, noSpeechDraw, ignored -> {}, guard);
		director.setSilentArrivalListener(c -> assertTrue(f.arrive(c)));
		director.tick(f.latest.tick, 1, 1, true, true, false);
		assertTrue(director.offer(wave)); assertFalse(guard.hasPendingReservation());
		director.arrive(wave.getId(), f.latest.tick);
		director.arrive(wave.getId(), f.latest.tick);
		assertNull(director.getPacket()); assertNull(director.getLastPaint());
		assertEquals(0, guard.textCount()); assertEquals(0, guard.interactionCount());
		assertFalse(guard.hasPendingReservation());
		f.close(3, -1); director.tick(f.latest.tick, 1, 1, true, true, false);
		SceneCandidate emergency = new SceneCandidate(1000, "M01", 100, f.latest.tick, f.latest.tick + 12,
			1, 1, "hit:1000", Collections.emptyMap(), true, 20);
		assertTrue(director.offer(emergency));
		assertSame(emergency, director.getActiveCandidate());
		assertNull(director.getPacket()); assertEquals(0, guard.textCount());
	}

	@Test public void centralReducerAdmitsFirstActiveTransitionAndNeverReplaysStartupAnimation()
	{
		ScenarioReplay r = new ScenarioReplay(2);
		r.frame.animation = AnimationID.EMOTE_WAVE;
		SceneCandidate wave = ScenarioReplay.find(r.next(), "E01"); assertNotNull(wave);
		assertEquals("10", wave.getConditionFacts().get("settlingNotBeforeTick"));
		assertNull(ScenarioReplay.find(r.next(), "E01"));
		r.frame.animation = -1; r.next(); assertTrue(r.rules.stillValid(wave));
		r.frame.emergencySceneActive = true; r.next(); assertFalse(r.rules.continuationValid(wave));
		ScenarioReplay startup = new ScenarioReplay(0); startup.frame.animation = AnimationID.EMOTE_WAVE;
		for (int tick = 0; tick < 5; tick++) { assertNull(ScenarioReplay.find(startup.next(), "E01")); }
	}

	@Test public void centralReducerBaselinesAbsenceAndCommitsOnlyTheValidatedEmoteArrival()
	{
		ScenarioReplay r = new ScenarioReplay(); r.frame.present = false; r.frame.animation = AnimationID.EMOTE_WAVE;
		assertNull(ScenarioReplay.find(r.next(), "E01"));
		r.frame.present = true; r.frame.presentSinceTick = r.frame.tick + 1;
		for (int tick = 0; tick < 11; tick++) { assertNull(ScenarioReplay.find(r.next(), "E01")); }
		r.frame.animation = -1; r.next(); r.frame.animation = AnimationID.EMOTE_WAVE;
		SceneCandidate wave = ScenarioReplay.find(r.next(), "E01"); assertNotNull(wave);
		assertTrue(r.rules.emoteArrived(wave)); assertFalse(r.rules.emoteArrived(wave));
		r.frame.animation = -1; r.next(); r.frame.animation = AnimationID.EMOTE_BOW;
		for (int tick = 0; tick < 11; tick++) { assertNull(ScenarioReplay.find(r.next(), "E01")); }
		r.frame.animation = -1; r.next(); r.frame.animation = AnimationID.EMOTE_BOW;
		assertNotNull(ScenarioReplay.find(r.next(), "E01"));
	}

	private static DialogueCatalog testCatalogue() throws Exception
	{
		JsonObject catalogue;
		try (InputStream input = EmoteRulesTest.class.getResourceAsStream("/com/malfoid/dialogue.json");
			InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8))
		{
			catalogue = new JsonParser().parse(reader).getAsJsonObject();
		}
		// Exercise the implementation without enabling E01 in the production catalogue.
		catalogue.getAsJsonArray("runtimeEnabledScenarios").add("E01");
		try (InputStream input = new ByteArrayInputStream(catalogue.toString().getBytes(StandardCharsets.UTF_8)))
		{
			return DialogueCatalog.read(input);
		}
	}

	private static final class Fixture
	{
		static final long BASE = 100;
		final EmoteRules rules = new EmoteRules();
		final ScenarioSnapshot.Builder frame = new ScenarioSnapshot.Builder();
		CombatState combat = CombatState.NO_CONFIRMED_EVIDENCE;
		ScenarioSnapshot latest;
		long id, offset;
		Fixture()
		{
			frame.player = new Position(3100, 3200, 0); frame.companion = new Position(3104, 3200, 0);
			frame.presentSinceTick = 0;
		}
		SceneCandidate close(long tick, int animation) { return close(tick, animation, ScenarioBatch.empty()); }
		SceneCandidate close(long tick, int animation, ScenarioBatch batch)
		{
			frame.tick = BASE + tick; frame.animation = animation; offset = tick;
			ScenarioSnapshot current = frame.build();
			List<SceneCandidate> result = rules.close(current, latest, batch, Collections.emptyList(), combat, -100,
				(f, scenario, priority, life, cause, facts, silent, hold) -> {
					Map<String, String> copied = new HashMap<>(facts);
					copied.put("settlingNotBeforeTick", Long.toString(f.presentSinceTick + 10));
					return new SceneCandidate(++id, scenario, priority, f.tick, f.tick + life, f.sessionGeneration,
						f.sceneRevision, cause, copied, silent, hold);
				});
			latest = current; assertTrue(result.size() <= 1);
			return result.isEmpty() ? null : result.get(0);
		}
		void advance(long tick) { while (offset < tick) { close(offset + 1, -1); } }
		boolean arrive(SceneCandidate candidate) { return rules.arrived(candidate, latest, combat, -100); }
	}
}
