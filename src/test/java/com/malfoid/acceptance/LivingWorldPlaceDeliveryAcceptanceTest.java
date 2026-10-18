package com.malfoid.acceptance;

import com.malfoid.motion.RouteMotion;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import net.runelite.api.ChatMessageType;
import net.runelite.api.HitsplatID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static com.malfoid.acceptance.AdditionalProducerFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** GameTick -> tick-end closure -> PLACE observer -> director -> actual overhead draw. */
public class LivingWorldPlaceDeliveryAcceptanceTest
{
    @Test public void fifthMappedTickIssuesAndPaintsAPlaceWithoutStoppingNativeFollowing() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline(); w.enableNative();
        for (int x = 3252; x <= 3270; x += 2)
        {
            w.f.point = new WorldPoint(x, 3250, 0); w.commit();
            assertNull("No place scene before the fifth Al Kharid sample", w.director.getActiveCandidate());
            w.cycles(30);
        }
        w.f.point = new WorldPoint(3272, 3250, 0); w.commit();
        SpeechPacket packet = w.issued("PL-AL_KHARID"); SceneCandidate candidate = packet.getCandidate();
        assertEquals(0, candidate.getHoldTicks()); assertEquals(0, w.director.getExecutionHoldTicks());
        assertEquals(w.f.tick() + 1, candidate.getExpiresTick());
        assertEquals("PLACE", candidate.getConditionFacts().get("livingWorld"));
        assertEquals("PL-AL_KHARID", candidate.getConditionFacts().get("placeEntry"));
        assertNull("Passive place speech owns no scene motion plan", get(w.f.session, "plan"));
        assertEquals(-1L, ((Number) get(w.f.session, "directorSceneId")).longValue());
        assertEquals("NORMAL", get(w.f.actor, "intent").toString());
        assertTrue("The actual native follow state continues", w.f.actor.isFollowing());
        assertTrue("The actual native route continues", w.f.actor.isMoving());
        RouteMotion motion = (RouteMotion) get(w.f.actor, "motion"); long before = motion.getCompletedEdges();
        SpeechPacket.PaintReceipt receipt = w.paint(packet);
        assertSame(candidate, receipt.getCandidate()); assertEquals(w.f.tick(), receipt.getPaintedAtTick());
        assertEquals("W-PLACE", w.cat.repeatKey(candidate.getScenarioId()));
        assertFalse("Real first paint starts the shared living-world cap", w.director.livingWorldSpacingReady());
        assertTrue(w.f.actor.isFollowing()); assertTrue(w.f.actor.isMoving());
        w.cycles(30);
        assertTrue("Only genuine native ClientTick edges prove movement", motion.getCompletedEdges() > before);
        assertEquals("NORMAL", get(w.f.actor, "intent").toString());
    }

    @Test public void aRealPlacePaintOwnsTheShared100TickCapAndTheSeparate300TickPlaceCooldown() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        arriveAlKharid(w); SpeechPacket first = w.issued("PL-AL_KHARID");
        SpeechPacket.PaintReceipt receipt = w.paint(first);
        assertEquals(1, w.worldRandom.draws);
        w.hold(99); assertFalse(w.director.livingWorldSpacingReady());
        w.commit(); assertTrue("The shared cap ends exactly 100 ticks after actual paint", w.director.livingWorldSpacingReady());
        assertTrue(w.director.isIdle()); assertFalse(w.f.presence.isBusy()); assertTrue(w.f.presence.isPresent());
        w.moveTo(3266, 3266); w.hold(3);
        assertEquals("PT-MINE", w.data.place(w.f.point.getRegionID()).id);
        assertTrue(w.f.tick() - receipt.getPaintedAtTick() < 300);
        assertTrue("The shared cap has cleared, so only the place cooldown blocks this fresh kind", w.director.livingWorldSpacingReady());
        assertTrue(w.director.isIdle()); assertNull(w.director.getPacket());
        assertEquals("A cooldown-blocked arrival is consumed before probability", 1, w.worldRandom.draws);
        while (w.f.tick() < receipt.getPaintedAtTick() + 300) { w.commit(); }
        w.hold(5);
        assertTrue("The consumed mine arrival does not replay when 300 ticks pass", w.director.isIdle());
        assertEquals(1, w.worldRandom.draws); assertSame(receipt, w.director.getLastPaint());
    }

    @Test public void modalBlockedArrivalDoesNotReplayWhenTheBankCloses() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        Widget bank = w.f.open(InterfaceID.Bankmain.UNIVERSE);
        arriveAlKharid(w);
        assertTrue(w.f.presence.isBusy()); assertTrue(w.director.isIdle()); assertEquals(0, w.worldRandom.draws);
        when(bank.isHidden()).thenReturn(true); w.hold(20);
        assertFalse(w.f.presence.isBusy()); assertTrue(w.director.isIdle()); assertNull(w.director.getPacket());
        assertEquals(0, w.worldRandom.draws);
        w.moveTo(3266, 3266); w.hold(3);
        assertNotNull("A different fresh area remains reachable after the blocked arrival", w.issued("PT-MINE"));
    }

    @Test public void aRealNumberedProducerAtTheArrivalTickTakesPrecedenceAndLeavesNoPlaceBacklog() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        w.moveTo(3266, 3250); w.hold(2); // Four Al Kharid samples.
        w.f.report(41, ChatMessageType.GAMEMESSAGE, "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.");
        w.commit();
        SceneCandidate numbered = w.director.getActiveCandidate(); assertNotNull(numbered);
        assertEquals("M147", numbered.getScenarioId()); assertTrue(w.cat.hasValidOriginalContext(numbered));
        assertEquals("Living-world probability is not retried behind numbered work", 0, w.worldRandom.draws);
        w.hold(25);
        assertTrue(w.director.isIdle()); assertNull(w.director.getPacket());
        assertEquals(0, w.worldRandom.draws); assertEquals(0, w.guard.textCount());
    }

    @Test public void confirmedCombatConsumesTheArrivalWithoutSpeakingAfterCombatClears() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        w.moveTo(3266, 3250); w.hold(2);
        w.f.hit(HitsplatID.DAMAGE_ME, 1, true); w.commit();
        assertEquals(ScenarioRules.CombatState.CONFIRMED, w.f.observer.getCombatState());
        assertTrue(w.director.isIdle()); assertEquals(0, w.worldRandom.draws);
        w.hold(11);
        assertEquals(ScenarioRules.CombatState.NO_CONFIRMED_EVIDENCE, w.f.observer.getCombatState());
        assertTrue(w.director.isIdle()); assertNull(w.director.getPacket()); assertEquals(0, w.worldRandom.draws);
    }

    @Test public void returningInANewPlaceBeforeTenPresentTicksConsumesItWithoutLaterReplay() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        long generation = ((Number) get(w.f.session, "sessionGeneration")).longValue();
        w.f.loading(); w.f.point = new WorldPoint(3266, 3250, 0); w.f.restorePresent();
        assertEquals("Ordinary scene loading preserves session memory", generation,
            ((Number) get(w.f.session, "sessionGeneration")).longValue());
        assertTrue(w.f.tick() - w.f.presence.getPresentSinceTick() >= 10);
        assertTrue(w.director.isIdle()); assertEquals(0, w.worldRandom.draws);
        w.hold(20); assertTrue(w.director.isIdle()); assertEquals(0, w.worldRandom.draws);
        w.moveTo(3266, 3266); w.hold(3);
        assertNotNull(w.issued("PT-MINE"));
    }

    @Test public void currentPlaceMustStillMatchWhenTheUnpaintedPacketIsRevalidated() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        arriveAlKharid(w); SpeechPacket packet = w.issued("PL-AL_KHARID"); w.publish();
        w.f.point = new WorldPoint(3263, 3250, 0); // Client evidence changes before the next commit.
        assertFalse(w.valid(packet.getCandidate()));
        w.cycles(1); w.assertNoPaint(packet);
        assertNull(w.director.getLastPaint());
    }

    @Test public void deathBeforeTheFirstDrawRevokesThePlaceCapabilityWithoutHistory() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        arriveAlKharid(w); SpeechPacket packet = w.issued("PL-AL_KHARID"); w.publish();
        when(w.f.player.isDead()).thenReturn(true); w.cycles(1);
        assertFalse(w.f.presence.isPresent()); w.assertNoPaint(packet);
        assertNull(w.director.getLastPaint());
    }

    @Test public void sceneReloadResetsAnUnfinishedWindowButPreservesAnAlreadyConsumedArrival() throws Exception
    {
        LivingWorldDeliveryFixture w = new LivingWorldDeliveryFixture(); w.baseline();
        w.moveTo(3266, 3250); assertTrue(w.director.isIdle()); // Only two mapped ticks.
        w.f.loading(); w.f.point = new WorldPoint(3250, 3250, 0); w.f.restorePresent();
        assertNotNull("The real reload also produces the legacy Lumbridge arrival", w.director.getActiveCandidate());
        assertEquals("M10", w.director.getActiveCandidate().getScenarioId());
        long deadline = w.director.getActiveCandidate().getExpiresTick();
        assertEquals(0, w.worldRandom.draws);
        while (!w.director.isIdle() && w.f.tick() < deadline) { w.commit(); }
        assertTrue("The unpainted legacy arrival ends at its real deadline", w.director.isIdle());
        assertEquals(0, w.worldRandom.draws); assertEquals(0, w.guard.textCount());
        w.moveTo(3266, 3250); w.hold(2);
        assertTrue("Old pre-reload samples cannot finish the new five-tick window", w.director.isIdle());
        w.commit(); SpeechPacket first = w.issued("PL-AL_KHARID"); assertEquals(1, w.worldRandom.draws);
        w.f.loading(); w.f.restorePresent(); w.hold(20);
        assertTrue("A scene return cannot replay an already consumed arrival", w.director.isIdle());
        assertEquals(1, w.worldRandom.draws); assertNull(first.getFirstPaint()); assertEquals(0, w.guard.textCount());
    }

    private static void arriveAlKharid(LivingWorldDeliveryFixture w) throws Exception
    {
        w.moveTo(3266, 3250); w.hold(2);
        assertTrue("Four mapped ticks are not an arrival", w.director.isIdle());
        w.commit();
    }
}
