package com.malfoid.interaction;

import org.junit.Test;

import static org.junit.Assert.*;

public class NoticeLedgerTest
{
	@Test
	public void repeatedRequestsQueueEachSessionNoticeOnce()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		for (int duplicate = 0; duplicate < 100; duplicate++)
		{
			for (NoticeLedger.Notice notice : NoticeLedger.Notice.values())
			{
				ledger.request(notice);
			}
		}
		int deliveries = 0;
		while (ledger.next(true, false, true) != null) { deliveries++; }
		assertEquals(5, deliveries);
		ledger.resetSession();
		ledger.request(NoticeLedger.Notice.N02);
		assertNotNull(ledger.next(true, false, true));
	}

	@Test
	public void tutorialSuppressionRetainsOnePendingIntro()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		ledger.request(NoticeLedger.Notice.N01);
		for (int cycle = 0; cycle < 100; cycle++)
		{
			assertNull(ledger.next(false, false, true));
		}
		NoticeLedger.Delivery intro = ledger.next(true, false, true);
		assertNotNull(intro);
		assertNull(ledger.next(true, false, true));
		assertFalse(ledger.acknowledgeIntro("unrelated", intro.getNotice().getText()));
		assertTrue(ledger.acknowledgeIntro(intro.getSender(), intro.getNotice().getText()));
		assertFalse(ledger.acknowledgeIntro(intro.getSender(), intro.getNotice().getText()));
	}

	@Test
	public void suppressionAfterQueueDefersAndRevokesTheOldReceipt()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		ledger.request(NoticeLedger.Notice.N01);
		NoticeLedger.Delivery old = ledger.next(true, false, true);
		ledger.deferTutorialIntro();
		assertFalse(ledger.acknowledgeIntro(old.getSender(), old.getNotice().getText()));
		NoticeLedger.Delivery retry = ledger.next(true, false, true);
		assertNotEquals(old.getSender(), retry.getSender());
		assertTrue(ledger.acknowledgeIntro(retry.getSender(), retry.getNotice().getText()));
	}

	@Test
	public void receiptsFromAnEndedSessionCannotConsumeTheNewIntro()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		ledger.request(NoticeLedger.Notice.N01);
		NoticeLedger.Delivery old = ledger.next(true, false, true);
		ledger.resetSession();
		ledger.request(NoticeLedger.Notice.N01);
		NoticeLedger.Delivery current = ledger.next(true, false, true);
		assertFalse(ledger.acknowledgeIntro(old.getSender(), old.getNotice().getText()));
		assertTrue(ledger.acknowledgeIntro(current.getSender(), current.getNotice().getText()));
	}

	@Test
	public void persistedIntroFlagDropsPendingIntro()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		ledger.request(NoticeLedger.Notice.N01);
		assertNull(ledger.next(true, true, true));
	}

	@Test
	public void deferredIntroCannotClaimPresenceOrBlockAnAbsenceNotice()
	{
		NoticeLedger ledger = new NoticeLedger("fixture");
		ledger.request(NoticeLedger.Notice.N01);
		ledger.request(NoticeLedger.Notice.N04);
		NoticeLedger.Delivery absence = ledger.next(true, false, false);
		assertEquals(NoticeLedger.Notice.N04, absence.getNotice());
		for (int cycle = 0; cycle < 100; cycle++)
		{
			assertNull(ledger.next(true, false, false));
		}
		NoticeLedger.Delivery intro = ledger.next(true, false, true);
		assertEquals(NoticeLedger.Notice.N01, intro.getNotice());
		assertNull(ledger.next(true, false, true));
		assertTrue(ledger.acknowledgeIntro(intro.getSender(), intro.getNotice().getText()));
	}
}
