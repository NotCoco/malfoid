package com.malfoid.acceptance;

import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechPacket;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static com.malfoid.acceptance.AdditionalProducerFixture.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LivingWorldMomentDeliveryAcceptanceTest
{
    @Test public void aRealWildernessTransitionHasNoRouteOrHoldAndActualPaintOwnsBothSpeechCaps() throws Exception
    {
        LivingWorldDeliveryFixture f = new LivingWorldDeliveryFixture(); f.baseline(); f.hold(45);
        f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 1); f.commit();
        SpeechPacket packet = f.issued("W-WILD-ENTER"); assertEquals(0, f.director.getExecutionHoldTicks());
        assertNull(get(f.f.session, "plan")); assertEquals("NORMAL", get(f.f.actor, "intent").toString());
        assertTrue(f.director.livingWorldSpacingReady()); f.paint(packet); assertFalse(f.director.livingWorldSpacingReady());
        f.director.invalidate(true); f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 0); f.hold(101);
        f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 1); f.commit();
        assertTrue(f.director.isIdle()); assertEquals("The painted enter pool has 1,500-tick spacing", 1, f.worldRandom.draws);
    }

    @Test public void anActualNumberedSourceWinsTheCrossingAndTheMomentHasNoBacklog() throws Exception
    {
        LivingWorldDeliveryFixture f = new LivingWorldDeliveryFixture(); f.baseline(); f.hold(45);
        f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 1);
        f.f.report(15, ChatMessageType.GAMEMESSAGE, "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>."); f.commit();
        SceneCandidate numbered = f.director.getActiveCandidate(); assertNotNull(numbered); assertEquals("M147", numbered.getScenarioId());
        f.director.invalidate(true); f.hold(3); assertTrue(f.director.isIdle()); assertEquals(0, f.worldRandom.draws);
    }

    @Test public void leavingAfterTwoHundredTicksCanSpeakButDeathDuringThatInsideIntervalCannot() throws Exception
    {
        for (boolean death : new boolean[]{false, true})
        {
            LivingWorldDeliveryFixture f = new LivingWorldDeliveryFixture(); f.baseline();
            f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 1); f.hold(200);
            if (death)
            {
                f.f.plugin.onActorDeath(new ActorDeath(f.f.player));
                f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 0); f.f.restorePresent();
                assertTrue(f.director.getActiveCandidate() == null || !f.director.isLivingWorld(f.director.getActiveCandidate()));
                assertEquals(0, f.worldRandom.draws);
            }
            else
            {
                f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 0); f.commit(); f.paint(f.issued("W-WILD-LEAVE"));
            }
        }
    }

    @Test public void theActualPlayerCombatAbsenceReturnsAtTenPresentTicksWithOnlyLocalHpChoosingOnePool() throws Exception
    {
        for (int hp : new int[]{80, 40})
        {
            LivingWorldDeliveryFixture f = new LivingWorldDeliveryFixture(); f.baseline();
            f.f.varbits.put(VarbitID.INSIDE_WILDERNESS, 1); f.f.levels[Skill.HITPOINTS.ordinal()] = 100;
            f.f.boosted[Skill.HITPOINTS.ordinal()] = hp;
            Player other = mock(Player.class); doReturn(indexed(Collections.singletonList(other))).when(f.f.view).players();
            MenuEntry entry = mock(MenuEntry.class); when(entry.getOption()).thenReturn("Attack");
            when(entry.getType()).thenReturn(MenuAction.PLAYER_SECOND_OPTION); when(entry.getPlayer()).thenReturn(other);
            f.f.plugin.onMenuOptionClicked(new MenuOptionClicked(entry)); assertFalse(f.f.presence.isPresent());
            f.hold(101); f.f.restorePresent();
            assertEquals(10, f.f.tick() - f.f.presence.getPresentSinceTick());
            SpeechPacket packet = f.issued(hp > 50 ? "W-AFTER-PVP" : "W-AFTER-PVP-HURT");
            f.paint(packet); assertFalse(f.director.livingWorldSpacingReady());
            verify(other, never()).getWorldLocation(); verify(other, never()).getLocalLocation();
            verify(other, never()).getPlayerComposition(); verify(other, never()).getAnimation();
        }
    }

    private static <T> IndexedObjectSet<T> indexed(List<T> values)
    {
        return new IndexedObjectSet<T>()
        {
            @Override public T byIndex(int index) { return values.get(index); }
            @Override public Iterator<T> iterator() { return values.iterator(); }
        };
    }
}
