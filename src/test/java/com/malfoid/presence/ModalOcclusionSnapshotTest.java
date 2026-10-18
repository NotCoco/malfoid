package com.malfoid.presence;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModalOcclusionSnapshotTest
{
	@Test public void snapshotCopiesEveryInputRectangleAndItsContainingList()
	{
		Rectangle rectangle = new Rectangle(10, 20, 30, 40); List<Rectangle> source = new ArrayList<>(Collections.singletonList(rectangle));
		ModalOcclusionSnapshot snapshot = new ModalOcclusionSnapshot(true, source);
		rectangle.setBounds(500, 500, 1, 1); source.clear();
		assertTrue(snapshot.isKnown()); assertTrue(snapshot.intersects(new Rectangle(15, 25, 1, 1)));
		assertFalse(snapshot.intersects(new Rectangle(500, 500, 1, 1)));
	}
	@Test public void returnedRectanglesCannotMutateThePublishedSnapshot()
	{
		ModalOcclusionSnapshot snapshot = new ModalOcclusionSnapshot(true, Collections.singletonList(new Rectangle(10, 20, 30, 40)));
		snapshot.getBounds().get(0).setBounds(500, 500, 1, 1);
		assertTrue(snapshot.intersects(new Rectangle(15, 25, 1, 1))); assertEquals(new Rectangle(10, 20, 30, 40), snapshot.getBounds().get(0));
	}
	@Test(expected = UnsupportedOperationException.class) public void returnedBoundListIsImmutable()
	{
		ModalOcclusionSnapshot.CLEAR.getBounds().add(new Rectangle(0, 0, 1, 1));
	}
	@Test public void disjointRootUnionPreservesTheUncoveredGapAndExactPixelEdges()
	{
		ModalOcclusionSnapshot snapshot = new ModalOcclusionSnapshot(true, Arrays.asList(new Rectangle(0, 0, 10, 10), new Rectangle(20, 0, 10, 10)));
		assertTrue(snapshot.intersects(new Rectangle(9, 0, 1, 1)));
		assertFalse(snapshot.intersects(new Rectangle(10, 0, 1, 1)));
		assertFalse(snapshot.intersects(new Rectangle(12, 2, 6, 6)));
		assertTrue(snapshot.intersects(new Rectangle(19, 0, 2, 1)));
	}
	@Test public void unknownEmptyOverflowAndOversizedGeometryCannotAuthorizePaint()
	{
		assertFalse(ModalOcclusionSnapshot.UNKNOWN.isKnown()); assertTrue(ModalOcclusionSnapshot.UNKNOWN.intersects(new Rectangle(0, 0, 1, 1)));
		for (Rectangle rectangle : Arrays.asList(null, new Rectangle(0, 0, 0, 10), new Rectangle(0, 0, 10, -1), new Rectangle(Integer.MAX_VALUE, 0, 1, 1)))
		{
			assertFalse(new ModalOcclusionSnapshot(true, Collections.singletonList(rectangle)).isKnown());
		}
		List<Rectangle> tooMany = new ArrayList<>();
		for (int i = 0; i <= ModalOcclusionSnapshot.MAX_ROOTS; i++) { tooMany.add(new Rectangle(i, 0, 1, 1)); }
		assertFalse(new ModalOcclusionSnapshot(true, tooMany).isKnown());
		assertFalse(new ModalOcclusionSnapshot(true, null).isKnown());
	}
	@Test public void knownClearAllowsValidPixelsButCannotAuthorizeMissingPixelGeometry()
	{
		assertTrue(ModalOcclusionSnapshot.CLEAR.isKnown()); assertFalse(ModalOcclusionSnapshot.CLEAR.intersects(new Rectangle(0, 0, 1, 1)));
		assertTrue(ModalOcclusionSnapshot.CLEAR.intersects(null)); assertTrue(ModalOcclusionSnapshot.CLEAR.intersects(new Rectangle(0, 0, 0, 1)));
		assertTrue(new ModalOcclusionSnapshot(true, Collections.singletonList(new Rectangle(-10, -10, 20, 20))).intersects(new Rectangle(0, 0, 1, 1)));
	}
	@Test public void absentRootsAreClearAndExactlySixteenAuditedPackedRootsAreReadOnce()
	{
		Client client = client(); Set<Integer> roots = new HashSet<>();
		when(client.getWidget(anyInt())).thenAnswer(call -> { assertTrue("Duplicate root read", roots.add(call.getArgument(0))); return null; });
		assertTrue(new PresenceController(client).sampleModalOcclusion().isKnown());
		assertEquals(ModalOcclusionSnapshot.MAX_ROOTS, roots.size());
		assertEquals(new HashSet<>(Arrays.asList(InterfaceID.ChatLeft.UNIVERSE, InterfaceID.ChatRight.UNIVERSE,
			InterfaceID.Chatmenu.UNIVERSE, InterfaceID.Messagebox.UNIVERSE, InterfaceID.Objectbox.UNIVERSE,
			InterfaceID.Bankmain.UNIVERSE, InterfaceID.BankDepositbox.UNIVERSE, InterfaceID.BankpinKeypad.UNIVERSE,
			InterfaceID.Trademain.UNIVERSE, InterfaceID.Tradeconfirm.UNIVERSE, InterfaceID.GeOffers.UNIVERSE,
			InterfaceID.Shopmain.UNIVERSE, InterfaceID.Skillmulti.UNIVERSE, InterfaceID.Worldmap.UNIVERSE,
			InterfaceID.Fairyrings.ROOT_RECT0, InterfaceID.TrailSlidepuzzle.UNIVERSE)), roots);
	}
	@Test public void hiddenRootsDoNotReadUnknownBoundsOrCoverTheHead()
	{
		Client client = client(); Widget hidden = mock(Widget.class); when(hidden.isHidden()).thenReturn(true);
		when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(hidden);
		ModalOcclusionSnapshot snapshot = new PresenceController(client).sampleModalOcclusion();
		assertTrue(snapshot.isKnown()); assertTrue(snapshot.getBounds().isEmpty()); verify(hidden, never()).getBounds();
	}
	@Test public void visibleRootsWithNullEmptyOrOverflowBoundsMakeTheWholeSnapshotUnknown()
	{
		for (Rectangle rectangle : Arrays.asList(null, new Rectangle(0, 0, 0, 10), new Rectangle(0, 0, 10, -1), new Rectangle(Integer.MAX_VALUE, 0, 1, 1)))
		{
			Client client = client(); Widget visible = mock(Widget.class); when(visible.getBounds()).thenReturn(rectangle);
			when(client.getWidget(InterfaceID.Fairyrings.ROOT_RECT0)).thenReturn(visible);
			assertFalse(new PresenceController(client).sampleModalOcclusion().isKnown());
		}
	}
	@Test public void visibleWorldmapFairyringAndCluePuzzleUseTheirActualCopiedCanvasBounds()
	{
		for (int root : new int[]{InterfaceID.Worldmap.UNIVERSE, InterfaceID.Fairyrings.ROOT_RECT0, InterfaceID.TrailSlidepuzzle.UNIVERSE})
		{
			Client client = client(); Widget visible = mock(Widget.class); Rectangle bounds = new Rectangle(100, 50, 40, 60);
			when(visible.getBounds()).thenReturn(bounds); when(client.getWidget(root)).thenReturn(visible);
			ModalOcclusionSnapshot snapshot = new PresenceController(client).sampleModalOcclusion(); bounds.setBounds(0, 0, 1, 1);
			assertTrue(snapshot.isKnown()); assertTrue(snapshot.intersects(new Rectangle(110, 60, 1, 1)));
			assertFalse(snapshot.intersects(new Rectangle(0, 0, 1, 1)));
		}
	}
	@Test public void sharedBusyRootsAndTypingAreRefreshedDuringCaptureWithoutCommittingPresence()
	{
		Client client = client(); PresenceController presence = new PresenceController(client); Widget bank = mock(Widget.class);
		when(bank.getBounds()).thenReturn(new Rectangle(100, 50, 40, 60)); when(client.getWidget(InterfaceID.Bankmain.UNIVERSE)).thenReturn(bank);
		assertTrue(presence.sampleModalOcclusion().isKnown()); assertTrue(presence.isBusy()); assertFalse(presence.isPresent());
		when(bank.isHidden()).thenReturn(true); assertTrue(presence.sampleModalOcclusion().isKnown()); assertFalse(presence.isBusy());
		when(client.getVarcStrValue(VarClientID.CHATINPUT)).thenReturn("typing"); presence.sampleModalOcclusion(); assertTrue(presence.isBusy());
	}
	@Test public void loadingUiIsUnknownAndNeverReadsLiveWidgetGeometry()
	{
		Client client = client(); when(client.getGameState()).thenReturn(GameState.LOADING);
		PresenceController presence = new PresenceController(client); assertFalse(presence.sampleModalOcclusion().isKnown());
		assertTrue(presence.isBusy()); verify(client, never()).getWidget(anyInt());
	}
	private static Client client()
	{
		Client client = mock(Client.class); when(client.getGameState()).thenReturn(GameState.LOGGED_IN); return client;
	}
}
