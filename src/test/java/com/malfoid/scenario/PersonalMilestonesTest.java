package com.malfoid.scenario;

import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.scenario.PersonalMilestones.Proof;
import com.malfoid.speech.SceneCandidate;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PersonalMilestonesTest
{
	private static final String COLLECTION = "New item added to your collection log: <col=ef1020>Dragon axe</col>";
	private static final String ACHIEVEMENT = "Congratulations, you've completed an easy combat task: @ach_comp@Into the Den of Giants</col>.";

	@Test public void freshNativeHeadProducesOneBoundedPersonalProof() throws Exception
	{
		Fixture f = new Fixture();
		f.source.onChatMessage(f.message(1, COLLECTION));
		Proof proof = f.only();
		assertEquals(142, proof.ruleIndex); assertEquals(20, proof.occurrenceTick);
		assertEquals(40, proof.originalDeadline); assertTrue(f.source.current(proof));
		assertTrue(f.source.drain().isEmpty());
		SceneCandidate candidate = candidate(proof);
		assertTrue(proof.owns(candidate));
		assertTrue(DialogueCatalog.load(getClass()).hasValidOriginalContext(candidate));
	}

	@Test public void allSixAchievementTiersAndThePointsVariantKeepTheirAuthoredPool() throws Exception
	{
		Fixture f = new Fixture(); int id = 0;
		for (String tier : new String[]{"an easy", "a medium", "a hard", "an elite", "a master", "a grandmaster"})
		{
			f.source.onChatMessage(f.message(++id, "Congratulations, you've completed " + tier + " combat task: @ach_comp@A task</col>."));
			Proof proof = f.only(); assertEquals(147, proof.ruleIndex);
			assertTrue(DialogueCatalog.load(getClass()).hasValidOriginalContext(candidate(proof)));
		}
		f.source.onChatMessage(f.message(++id, "Congratulations, you've completed a grandmaster combat task: @ach_comp@A task</col> (6 points)."));
		assertEquals(147, f.only().ruleIndex);
	}

	@Test public void playerChatSpamAndQuotedOrAlteredSystemMessagesDoNotQualify()
	{
		Fixture f = new Fixture(); int id = 0;
		for (ChatMessageType type : new ChatMessageType[]{ChatMessageType.PUBLICCHAT, ChatMessageType.PRIVATECHAT, ChatMessageType.SPAM})
		{
			ChatMessage event = f.message(++id, COLLECTION); when(event.getType()).thenReturn(type);
			f.source.onChatMessage(event); assertTrue(f.source.drain().isEmpty());
		}
		for (String text : new String[]{"Quote: " + COLLECTION, COLLECTION + ".", COLLECTION.replace("ef1020", "ff0000"),
			COLLECTION.replace("Dragon axe", ""), COLLECTION.replace("Dragon axe", "<b>Dragon axe</b>"),
			COLLECTION.replace("Dragon axe", "\uD800"), ACHIEVEMENT.replace("@ach_comp@", "")})
		{
			f.source.onChatMessage(f.message(++id, text)); assertTrue(text, f.source.drain().isEmpty());
		}
	}

	@Test public void sourceMustBeTheExactCurrentNativeHeadWithMatchingMetadata()
	{
		for (int fault = 0; fault < 5; fault++)
		{
			Fixture f = new Fixture(); ChatMessage event = f.message(1, COLLECTION);
			MessageNode node = event.getMessageNode();
			if (fault == 0) { f.lines[0] = mock(MessageNode.class); }
			if (fault == 1) { when(node.getTimestamp()).thenReturn(101); }
			if (fault == 2) { when(node.getValue()).thenReturn("changed"); }
			if (fault == 3) { when(node.getType()).thenReturn(ChatMessageType.PUBLICCHAT); }
			if (fault == 4) { when(event.getMessageNode()).thenReturn(null); }
			f.source.onChatMessage(event); assertTrue("fault " + fault, f.source.drain().isEmpty());
		}
	}

	@Test public void repeatedCallbacksAndChangedTimestampsCannotReplayAnEmission()
	{
		Fixture f = new Fixture(); ChatMessage first = f.message(1, COLLECTION);
		f.source.onChatMessage(first); f.only();
		f.source.onChatMessage(first); assertTrue(f.source.drain().isEmpty());
		f.source.onChatMessage(f.message(1, COLLECTION)); assertTrue(f.source.drain().isEmpty());
		f.source.beginInterval(21, 1, 1, 0, true);
		f.source.onChatMessage(f.message(1, COLLECTION)); assertTrue(f.source.drain().isEmpty());
		f.source.onChatMessage(f.message(2, COLLECTION)); assertEquals(142, f.only().ruleIndex);
	}

	@Test public void hiddenAndSettlingMessagesAreConsumedWithoutCatchup()
	{
		Fixture f = new Fixture();
		f.source.beginInterval(20, 1, 1, 0, false);
		ChatMessage hidden = f.message(1, COLLECTION); f.source.onChatMessage(hidden);
		f.source.beginInterval(21, 1, 1, 0, true); f.source.onChatMessage(hidden);
		assertTrue(f.source.drain().isEmpty());
		f.source.beginInterval(22, 1, 1, 20, true);
		ChatMessage settling = f.message(2, COLLECTION); f.source.onChatMessage(settling);
		f.source.beginInterval(30, 1, 1, 20, true); f.source.onChatMessage(settling);
		assertTrue(f.source.drain().isEmpty());
	}

	@Test public void conflictingMetadataForAConsumedNodeRevokesItsOldOpportunity()
	{
		for (boolean changedTime : new boolean[]{false, true})
		{
			Fixture f = new Fixture(); f.source.onChatMessage(f.message(1, COLLECTION)); Proof proof = f.only();
			ChatMessage conflict = f.message(1, changedTime ? COLLECTION : ACHIEVEMENT);
			if (changedTime)
			{
				when(conflict.getTimestamp()).thenReturn(101);
				when(conflict.getMessageNode().getTimestamp()).thenReturn(101);
			}
			f.source.onChatMessage(conflict);
			assertFalse(f.source.current(proof)); assertTrue(f.source.drain().isEmpty());
		}
	}

	@Test public void scenePresenceAndSessionChangesRevokeIssuedProofs()
	{
		for (int boundary = 0; boundary < 4; boundary++)
		{
			Fixture f = new Fixture(); f.source.onChatMessage(f.message(1, COLLECTION)); Proof proof = f.only();
			f.source.beginInterval(21, boundary == 0 ? 2 : 1, boundary == 1 ? 2 : 1, boundary == 2 ? 1 : 0, boundary != 3);
			assertFalse(f.source.current(proof)); assertTrue(f.source.drain().isEmpty());
		}
	}

	@Test public void unrelatedSameIntervalRefreshDoesNotRenewOrDiscardTheProof()
	{
		Fixture f = new Fixture(); f.source.onChatMessage(f.message(1, COLLECTION)); Proof proof = f.only();
		f.source.beginInterval(20, 1, 1, 0, true);
		assertTrue(f.source.current(proof)); assertEquals(40, proof.originalDeadline);
	}

	@Test public void collectionPreferenceLossIsPermanentForThatOpportunity()
	{
		Fixture f = new Fixture(); f.source.onChatMessage(f.message(1, COLLECTION)); Proof proof = f.only();
		when(f.client.getVarbitValue(11959)).thenReturn(0); f.source.refresh();
		when(f.client.getVarbitValue(11959)).thenReturn(1);
		assertFalse(f.source.current(proof));
		f.source.onChatMessage(f.message(1, COLLECTION)); assertTrue(f.source.drain().isEmpty());
	}

	@Test public void unknownCollectionPreferenceDoesNotSuppressIndependentAchievements()
	{
		Fixture f = new Fixture(); when(f.client.getVarbit(11959)).thenReturn(null);
		f.source.onChatMessage(f.message(1, COLLECTION)); assertTrue(f.source.drain().isEmpty());
		f.source.onChatMessage(f.message(2, ACHIEVEMENT)); assertEquals(147, f.only().ruleIndex);
	}

	@Test public void aFullPendingSetFailsClosedWithoutReplayingItsConsumedHeads()
	{
		Fixture f = new Fixture();
		for (int id = 1; id <= 17; id++) { f.source.onChatMessage(f.message(id, ACHIEVEMENT)); }
		assertTrue(f.source.drain().isEmpty());
		f.source.beginInterval(21, 1, 1, 0, true);
		f.source.onChatMessage(f.message(17, ACHIEVEMENT)); assertTrue(f.source.drain().isEmpty());
		f.source.onChatMessage(f.message(18, ACHIEVEMENT)); assertEquals(147, f.only().ruleIndex);
	}

	@Test public void theReducerKeepsTheOriginalDeadlineAndRejectsExpiredOffers() throws Exception
	{
		Fixture f = new Fixture(); f.source.onChatMessage(f.message(1, COLLECTION)); Proof proof = f.only();
		ScenarioRules rules = new ScenarioRules(); rules.setScenarioEnabled("M142"::equals);
		rules.close(frame(18), ScenarioBatch.empty()); rules.close(frame(19), ScenarioBatch.empty());
		List<SceneCandidate> offers = rules.close(frame(20), ScenarioBatch.empty(), Collections.singletonList(proof));
		assertEquals(1, offers.size()); assertEquals(40, offers.get(0).getExpiresTick());
		assertSame(proof, offers.get(0).getMilestone());
		assertTrue(rules.close(frame(40), ScenarioBatch.empty(), Collections.singletonList(proof)).isEmpty());
	}

	@Test public void catalogueContainsOnlyTheTwoRetainedSecondaryMilestones() throws Exception
	{
		DialogueCatalog catalogue = DialogueCatalog.load(getClass());
		for (int id = 71; id <= 170; id++)
		{
			boolean retained = id == 142 || id == 147;
			assertEquals("M" + id, retained, catalogue.isRuntimeScenarioEnabled("M" + id));
			assertEquals("M" + id, retained ? 3 : 0, catalogue.getLines("M" + id).size());
		}
		SceneCandidate forged = new SceneCandidate(1, "M142", 70, 20, 40, 1, 1, "text-only", Collections.emptyMap(), false, 4);
		assertFalse(catalogue.hasValidOriginalContext(forged));
	}

	private static SceneCandidate candidate(Proof proof)
	{
		return new SceneCandidate(proof.sourceSerial, proof.ruleId, 70, proof.occurrenceTick, proof.originalDeadline,
			proof.sessionGeneration, proof.sceneRevision, "personal-report:" + proof.sourceSerial, Collections.emptyMap(), false, 4, proof);
	}

	private static ScenarioSnapshot frame(long tick)
	{
		ScenarioSnapshot.Builder b = new ScenarioSnapshot.Builder();
		b.tick = tick; b.sessionGeneration = 1; b.sceneRevision = 1; b.presentSinceTick = 0;
		b.valid = b.present = b.topLevel = b.directorIdle = b.interactionKnown = true;
		b.currentHp = b.realHp = 50; b.player = new ScenarioSnapshot.Position(3250, 3250, 0);
		b.companion = new ScenarioSnapshot.Position(3254, 3250, 0);
		return b.build();
	}

	private static final class Fixture
	{
		final Client client = mock(Client.class);
		final PersonalMilestones source = new PersonalMilestones(client);
		final MessageNode[] lines = new MessageNode[1];
		Fixture()
		{
			ChatLineBuffer buffer = mock(ChatLineBuffer.class);
			when(buffer.getLines()).thenReturn(lines); when(buffer.getLength()).thenReturn(1);
			when(client.getChatLineMap()).thenReturn(Collections.singletonMap(ChatMessageType.GAMEMESSAGE.getType(), buffer));
			VarbitComposition definition = mock(VarbitComposition.class);
			when(definition.getIndex()).thenReturn(0); when(definition.getMostSignificantBit()).thenReturn(0);
			when(client.getVarbit(11959)).thenReturn(definition); when(client.getVarps()).thenReturn(new int[1]);
			when(client.getVarbitValue(11959)).thenReturn(1);
			source.beginInterval(20, 1, 1, 0, true);
		}
		ChatMessage message(int id, String text)
		{
			MessageNode node = mock(MessageNode.class);
			when(node.getId()).thenReturn(id); when(node.getTimestamp()).thenReturn(100);
			when(node.getType()).thenReturn(ChatMessageType.GAMEMESSAGE); when(node.getValue()).thenReturn(text);
			lines[0] = node;
			ChatMessage event = mock(ChatMessage.class);
			when(event.getType()).thenReturn(ChatMessageType.GAMEMESSAGE); when(event.getMessage()).thenReturn(text);
			when(event.getTimestamp()).thenReturn(100); when(event.getMessageNode()).thenReturn(node);
			return event;
		}
		Proof only()
		{
			List<Proof> result = source.drain(); assertEquals(1, result.size()); return result.get(0);
		}
	}
}
