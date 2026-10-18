package com.malfoid.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.ChatMessage;

/** Two native personal events, with bounded item/task identities and no player chat or client objects retained. */
public final class PersonalMilestones
{
	private static final Pattern COLLECTION = Pattern.compile("New item added to your collection log: <col=ef1020>([^<>@\\p{Cntrl}]{1,96})</col>");
	private static final Pattern ACHIEVEMENT = Pattern.compile("Congratulations, you've completed (an easy|a medium|a hard|an elite|a master|a grandmaster) combat task: @ach_comp@([^<>@\\p{Cntrl}]{1,120})</col>\\.");
	private static final Pattern GRANDMASTER = Pattern.compile("Congratulations, you've completed a grandmaster combat task: @ach_comp@([^<>@\\p{Cntrl}]{1,120})</col> \\(6 points\\)\\.");
	private final Client client;
	private final int[] seen = new int[128];
	private final int[] seenTimestamps = new int[128], seenKinds = new int[128];
	private final String[] seenFacts = new String[128];
	private final List<Proof> pending = new ArrayList<>();
	private final Set<Proof> issued = Collections.newSetFromMap(new IdentityHashMap<>());
	private int seenSize, seenNext;
	private long tick = -1, epoch = -1, serial;
	private long generation = -1, revision = -1;
	private boolean witnessed, overflow;

	PersonalMilestones(Client client) { this.client = client; }

	void beginInterval(long nextTick, long generation, long revision, long presenceEpoch, boolean witness)
	{
		if (this.generation != generation)
		{
			java.util.Arrays.fill(seenFacts, null); seenSize = seenNext = 0;
		}
		if (this.generation != generation || this.revision != revision || epoch != presenceEpoch || witnessed != witness
			|| nextTick != tick && nextTick != tick + 1) { reset(); }
		if (nextTick != tick) { overflow = false; }
		tick = nextTick; this.generation = generation; this.revision = revision; epoch = presenceEpoch; witnessed = witness;
		refresh();
	}

	void onChatMessage(ChatMessage event)
	{
		if (event == null || event.getType() != ChatMessageType.GAMEMESSAGE || generation < 0 || revision < 0) { return; }
		String raw = event.getMessage();
		if (raw == null || raw.length() > 512) { return; }
		Matcher collection = COLLECTION.matcher(raw), achievement = ACHIEVEMENT.matcher(raw), grandmaster = GRANDMASTER.matcher(raw);
		int ruleIndex;
		String display;
		if (collection.matches()) { ruleIndex = 142; display = collection.group(1); }
		else if (achievement.matches())
		{
			ruleIndex = 147; display = achievement.group(2);
		}
		else if (grandmaster.matches()) { ruleIndex = 147; display = grandmaster.group(1); }
		else { return; }
		if (!validDisplay(display, ruleIndex == 142 ? 96 : 120)) { return; }

		MessageNode node = event.getMessageNode();
		if (node == null || node.getType() != event.getType() || node.getTimestamp() != event.getTimestamp() || !raw.equals(node.getValue()))
		{
			reset(); overflow = true; return;
		}
		java.util.Map<Integer, ChatLineBuffer> buffers = client.getChatLineMap();
		ChatLineBuffer buffer = buffers == null ? null : buffers.get(event.getType().getType());
		MessageNode[] lines = buffer == null ? null : buffer.getLines();
		if (lines == null || buffer.getLength() <= 0 || buffer.getLength() > lines.length) { reset(); overflow = true; return; }
		if (lines[0] != node) { return; }
		String fact = achievement.matches() ? achievement.group(1) + ":" + display : display;
		for (int i = 0; i < seenSize; i++)
		{
			if (seen[i] != node.getId()) { continue; }
			if (seenTimestamps[i] != event.getTimestamp() || seenKinds[i] != ruleIndex || !fact.equals(seenFacts[i]))
			{
				reset(); overflow = true;
			}
			return;
		}
		seenTimestamps[seenNext] = event.getTimestamp(); seenKinds[seenNext] = ruleIndex; seenFacts[seenNext] = fact;
		seen[seenNext] = node.getId(); seenNext = (seenNext + 1) % seen.length; seenSize = Math.min(seen.length, seenSize + 1);
		// Even a hidden, settling or suppressed event is consumed; it cannot become a later reaction.
		if (!witnessed || overflow || tick < 0 || tick - epoch < 10 || tick > Long.MAX_VALUE - 20
			|| ruleIndex == 142 && !collectionEnabled()) { return; }
		if (issued.size() >= 16 || serial == Long.MAX_VALUE) { reset(); overflow = true; return; }
		long id = ++serial;
		Proof context = new Proof(ruleIndex, id, tick, generation, revision, epoch);
		pending.add(context); issued.add(context);
	}

	List<Proof> drain()
	{
		refresh();
		List<Proof> result = new ArrayList<>(pending);
		pending.clear();
		return result;
	}

	boolean current(Proof context)
	{
		refresh();
		return witnessed && issued.contains(context) && context.sessionGeneration == generation && context.sceneRevision == revision && context.presenceEpoch == epoch;
	}

	void refresh()
	{
		boolean collectionEnabled = collectionEnabled();
		issued.removeIf(context -> tick >= context.originalDeadline && tick - context.originalDeadline >= 40
			|| context.ruleIndex == 142 && !collectionEnabled);
		pending.removeIf(context -> !issued.contains(context));
	}

	private boolean collectionEnabled()
	{
		VarbitComposition definition = client.getVarbit(11959);
		int[] varps = client.getVarps();
		return definition != null && varps != null && definition.getIndex() >= 0 && definition.getIndex() < varps.length
			&& definition.getLeastSignificantBit() >= 0 && definition.getMostSignificantBit() >= definition.getLeastSignificantBit()
			&& definition.getMostSignificantBit() < 32 && client.getVarbitValue(11959) == 1;
	}

	private static boolean validDisplay(String text, int maximum)
	{
		if (text.trim().isEmpty() || text.codePointCount(0, text.length()) > maximum) { return false; }
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (Character.isHighSurrogate(c))
			{
				if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) { return false; }
			}
			else if (Character.isLowSurrogate(c)) { return false; }
		}
		return true;
	}

	/** Only this collector can create a proof; consumers receive immutable scalar facts. */
	public static final class Proof
	{
		public final int ruleIndex;
		public final String ruleId;
		public final long sourceSerial, occurrenceTick, originalDeadline, sessionGeneration, sceneRevision, presenceEpoch;
		private Proof(int ruleIndex, long serial, long tick, long generation, long revision, long epoch)
		{
			this.ruleIndex = ruleIndex; ruleId = "M" + ruleIndex; sourceSerial = serial;
			occurrenceTick = tick; originalDeadline = tick + 20;
			sessionGeneration = generation; sceneRevision = revision; presenceEpoch = epoch;
		}
		public boolean owns(com.malfoid.speech.SceneCandidate candidate)
		{
			return ruleId.equals(candidate.getScenarioId()) && sessionGeneration == candidate.getSessionGeneration()
				&& sceneRevision == candidate.getSceneRevision() && originalDeadline == candidate.getExpiresTick()
				&& occurrenceTick <= candidate.getCreatedTick() && presenceEpoch <= occurrenceTick;
		}
	}

	void reset() { pending.clear(); issued.clear(); }
}
