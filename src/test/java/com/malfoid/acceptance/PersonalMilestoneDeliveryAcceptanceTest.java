package com.malfoid.acceptance;

import com.malfoid.speech.SpeechPacket;
import net.runelite.api.ChatMessageType;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.VarbitChanged;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PersonalMilestoneDeliveryAcceptanceTest
{
	private static final String COLLECTION = "New item added to your collection log: <col=ef1020>Dragon axe</col>";
	private static final String ACHIEVEMENT = "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.";

	@Test public void achievementTravelsThroughRealRootAdmissionHoldAndFirstPaint() throws Exception
	{
		AdditionalProducerFixture f = new AdditionalProducerFixture(147);
		long occurrence = f.tick() + 1;
		f.report(1, ChatMessageType.GAMEMESSAGE, ACHIEVEMENT); f.commit();
		SpeechPacket packet = f.issued();
		assertNotNull(packet.getCandidate().getMilestone());
		assertEquals(occurrence, packet.getCandidate().getMilestone().occurrenceTick);
		assertEquals(occurrence + 20, packet.getCandidate().getExpiresTick());
		f.paintAndDebit(packet);
	}

	@Test public void collectionLogUsesOriginalLinesAndDebitsOnlyAfterDrawing() throws Exception
	{
		AdditionalProducerFixture f = collectionFixture();
		f.report(2, ChatMessageType.GAMEMESSAGE, COLLECTION); f.commit();
		SpeechPacket packet = f.issued();
		assertTrue(packet.getLineId().startsWith("M142-"));
		f.paintAndDebit(packet);
	}

	@Test public void turningOffCollectionAnnouncementsRevokesTheUnpaintedBubble() throws Exception
	{
		AdditionalProducerFixture f = collectionFixture();
		f.report(3, ChatMessageType.GAMEMESSAGE, COLLECTION); f.commit();
		SpeechPacket packet = f.issued();
		f.varbits.put(11959, 0);
		VarbitChanged changed = new VarbitChanged(); changed.setVarbitId(11959);
		f.plugin.onVarbitChanged(changed); f.assertHealthy();
		assertFalse(packet.canPaint(f.clockNanos)); assertNull(packet.getFirstPaint());
		assertEquals(0, f.guard.textCount()); assertEquals(0, f.guard.interactionCount());
		f.varbits.put(11959, 1); f.inspect();
		assertFalse(packet.canPaint(f.clockNanos));
	}

	@Test public void aPublicChatImitationNeverProducesABubble() throws Exception
	{
		AdditionalProducerFixture f = new AdditionalProducerFixture(147);
		f.report(4, ChatMessageType.PUBLICCHAT, ACHIEVEMENT); f.commit(); f.inspect();
		assertNull(f.director.getActiveCandidate()); assertEquals(0, f.guard.textCount());
	}

	private static AdditionalProducerFixture collectionFixture() throws Exception
	{
		AdditionalProducerFixture f = new AdditionalProducerFixture(142);
		VarbitComposition definition = mock(VarbitComposition.class);
		when(definition.getIndex()).thenReturn(0); when(definition.getMostSignificantBit()).thenReturn(0);
		when(f.client.getVarbit(11959)).thenReturn(definition);
		f.varbits.put(11959, 1);
		return f;
	}
}
