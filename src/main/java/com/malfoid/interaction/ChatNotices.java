package com.malfoid.interaction;

import java.util.UUID;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import com.malfoid.presence.PresenceController;
import com.malfoid.presence.PresenceReason;

/** Only local Examine and N01-N05 use the chatbox. No player chat or network action is issued. */
public final class ChatNotices
{
	// Exactly the helper's pinned 1.13.1 suppression set, not a guessed island boundary.
	private static final int[] TUTORIAL_REGIONS = {12336, 12335, 12592, 12080, 12079, 12436};
	private final Client client;
	private final ChatMessageManager messages;
	private final ConfigManager configManager;
	private final NoticeLedger ledger = new NoticeLedger("malfoid-local-" + UUID.randomUUID());
	private int examineIndex;
	private boolean present;

	@Inject
	public ChatNotices(Client client, ChatMessageManager messages, ConfigManager configManager)
	{
		this.client = client;
		this.messages = messages;
		this.configManager = configManager;
	}

	public void onPresence(PresenceController.Decision decision)
	{
		present = decision.isPresent();
		if (decision.isPresent() && !introShown())
		{
			ledger.request(NoticeLedger.Notice.N01);
		}
		if (decision.getReason() == PresenceReason.MODEL_UNAVAILABLE)
		{
			onModelUnavailable();
		}
		else if (decision.getReason() == PresenceReason.PLAYER_COMBAT)
		{
			ledger.request(NoticeLedger.Notice.N04);
		}
		else if (decision.getReason() == PresenceReason.ABOARD)
		{
			ledger.request(NoticeLedger.Notice.N05);
		}
	}

	public void onDismiss() { present = false; ledger.request(NoticeLedger.Notice.N02); }
	public void onModelUnavailable() { present = false; ledger.request(NoticeLedger.Notice.N03); }

	public void flush()
	{
		boolean tutorial = isTutorial();
		if (tutorial)
		{
			ledger.deferTutorialIntro();
		}
		boolean eligible = client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null && !tutorial;
		for (NoticeLedger.Delivery delivery; (delivery = ledger.next(eligible, introShown(), present)) != null; )
		{
			messages.queue(QueuedMessage.builder().type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(delivery.getNotice().getText())
				.sender(delivery.getSender()).build());
		}
	}

	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.GAMEMESSAGE
			&& ledger.acknowledgeIntro(event.getSender(), event.getMessage()))
		{
			configManager.setConfiguration("malfoid", "introShown", true);
		}
	}

	public void examine(String[] approvedTexts)
	{
		if (approvedTexts == null || approvedTexts.length == 0
			|| client.getGameState() != GameState.LOGGED_IN || isTutorial())
		{
			return;
		}
		String text = approvedTexts[examineIndex % approvedTexts.length];
		if (text == null || text.isEmpty())
		{
			return;
		}
		examineIndex = (examineIndex + 1) % approvedTexts.length;
		messages.queue(QueuedMessage.builder().type(ChatMessageType.NPC_EXAMINE)
			.runeLiteFormattedMessage(text).build());
	}

	public void resetSession()
	{
		ledger.resetSession();
		examineIndex = 0;
		present = false;
	}

	private boolean introShown()
	{
		return Boolean.TRUE.equals(configManager.getConfiguration("malfoid", "introShown", Boolean.class));
	}

	private boolean isTutorial()
	{
		Player player = client.getLocalPlayer();
		if (player == null || player.getWorldLocation() == null)
		{
			return false;
		}
		int region = player.getWorldLocation().getRegionID();
		for (int blockedRegion : TUTORIAL_REGIONS)
		{
			if (region == blockedRegion)
			{
				return true;
			}
		}
		return false;
	}
}
