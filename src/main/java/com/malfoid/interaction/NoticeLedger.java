package com.malfoid.interaction;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Queue;

/** Bounded local-notice state; only the issued intro's receipt can consume the persisted flag. */
final class NoticeLedger
{
	enum Notice
	{
		N01("Malfoid is with you. Right-click her to talk, examine or dismiss her. You can hide her any time in the Malfoid plugin settings."),
		N02("Malfoid will wait for you. Tick Show Malfoid in the Malfoid plugin settings to bring her back."),
		N03("Malfoid can't appear on this client right now. She'll try again next time you log in."),
		N04("Malfoid steps away while you fight other players. She'll be back when it's over."),
		N05("Malfoid waits on land while you're aboard. She'll rejoin you when you step off.");

		private final String text;
		Notice(String text) { this.text = text; }
		String getText() { return text; }
	}

	static final class Delivery
	{
		private final Notice notice;
		private final String sender;
		Delivery(Notice notice, String sender) { this.notice = notice; this.sender = sender; }
		Notice getNotice() { return notice; }
		String getSender() { return sender; }
	}

	private final String prefix;
	private final EnumSet<Notice> requested = EnumSet.noneOf(Notice.class);
	private final Queue<Notice> pending = new ArrayDeque<>();
	private long session;
	private long serial;
	private Delivery issuedIntro;

	NoticeLedger(String prefix)
	{
		this.prefix = prefix;
	}

	void request(Notice notice)
	{
		if (requested.add(notice))
		{
			pending.add(notice);
		}
	}

	Delivery next(boolean eligible, boolean introShown, boolean present)
	{
		if (!eligible)
		{
			return null;
		}
		for (int remaining = pending.size(); remaining > 0; remaining--)
		{
			Notice notice = pending.remove();
			if (notice == Notice.N01 && introShown)
			{
				continue;
			}
			if (notice == Notice.N01 && !present)
			{
				pending.add(notice);
				continue;
			}
			Delivery delivery = new Delivery(notice, prefix + ":" + session + ":" + ++serial);
			if (notice == Notice.N01)
			{
				issuedIntro = delivery;
			}
			return delivery;
		}
		return null;
	}

	boolean acknowledgeIntro(String sender, String text)
	{
		if (issuedIntro != null && issuedIntro.sender.equals(sender)
			&& issuedIntro.notice.text.equals(text))
		{
			issuedIntro = null;
			return true;
		}
		return false;
	}

	void deferTutorialIntro()
	{
		if (issuedIntro != null)
		{
			pending.add(Notice.N01);
			issuedIntro = null;
		}
	}

	void resetSession()
	{
		session++;
		requested.clear();
		pending.clear();
		issuedIntro = null;
	}
}
