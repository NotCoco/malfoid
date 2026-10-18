package com.malfoid.acceptance;

import com.google.gson.Gson;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** Real root ClientTick refresh, with an outfit admitted by actual container callbacks. */
public class AcceptanceUiFreshnessTest
{
    @Test
    public void typingPausesAnIssuedAutomaticPacketAndClearResumesItsOriginalDeadline() throws Exception
    {
        Fixture f = new Fixture();
        SpeechPacket issued = f.issueOutfit();
        long deadline = issued.getCandidate().getExpiresTick();
        assertTrue(issued.canPaint(0));

        when(f.client.getVarcStrValue(VarClientID.CHATINPUT)).thenReturn("typing");
        f.inspect();

        assertTrue(f.presence.isBusy());
        assertFalse("Typing must prevent first paint before another GameTick", issued.canPaint(0));
        assertSame(issued, f.director.getPacket());
        assertTrue(f.observer.candidateContinuationValid(issued.getCandidate()));

        when(f.client.getVarcStrValue(VarClientID.CHATINPUT)).thenReturn("");
        f.inspect();

        assertFalse(f.presence.isBusy());
        assertTrue(issued.canPaint(0));
        assertSame(issued, f.director.getPacket());
        assertEquals(deadline, issued.getCandidate().getExpiresTick());
        assertNull("Refreshing permission cannot invent a first paint", issued.getFirstPaint());
    }

    @Test
    public void bankReopeningRevokesUnchangedOutfitBeforeAnotherCommittedTick() throws Exception
    {
        for (int root : new int[]{InterfaceID.Bankmain.UNIVERSE, InterfaceID.BankDepositbox.UNIVERSE})
        {
            Fixture f = new Fixture();
            SpeechPacket issued = f.issueOutfit();
            SceneCandidate outfit = issued.getCandidate();
            Widget widget = f.visible(root);

            f.inspect();

            assertTrue(f.presence.isCurrentOutfitBlocked());
            assertTrue(f.presence.isCurrentInventoryBlocked());
            assertFalse(f.observer.candidateStillValid(outfit));
            assertFalse(f.observer.candidateContinuationValid(outfit));
            assertNull("Opening a bank is not an equipment revision", f.observer.cancellationReason(outfit));
            assertFalse("The issued capability must be revoked immediately", issued.canPaint(0));
            assertNull(f.director.getPacket());

            when(widget.isHidden()).thenReturn(true);
            f.inspect();

            assertFalse(f.presence.isCurrentOutfitBlocked());
            assertFalse(f.presence.isCurrentInventoryBlocked());
            assertFalse("Closing in the same ordinal cannot revive the revoked packet", issued.canPaint(0));
            assertNull(f.director.getPacket());
        }
    }

    @Test
    public void tradePausesPaintWithoutInventingAnOutfitDependency() throws Exception
    {
        Fixture f = new Fixture();
        SpeechPacket issued = f.issueOutfit();
        Widget widget = f.visible(InterfaceID.Trademain.UNIVERSE);

        f.inspect();

        assertFalse(f.presence.isCurrentOutfitBlocked());
        assertTrue(f.presence.isCurrentInventoryBlocked());
        assertTrue(f.observer.candidateStillValid(issued.getCandidate()));
        assertTrue(f.observer.candidateContinuationValid(issued.getCandidate()));
        assertFalse(issued.canPaint(0));
        assertSame(issued, f.director.getPacket());

        when(widget.isHidden()).thenReturn(true);
        f.inspect();

        assertFalse(f.presence.isCurrentInventoryBlocked());
        assertTrue(issued.canPaint(0));
        assertSame(issued, f.director.getPacket());
    }

    private static final class Fixture
    {
        final Client client = mock(Client.class);
        final ClientThread clientThread = mock(ClientThread.class);
        final Player player = mock(Player.class);
        final WorldView view = mock(WorldView.class);
        final NativeActor actor = new NativeActor(client, new Gson());
        final MalfoidPlugin plugin = new MalfoidPlugin();
        final ItemContainer equipment = mock(ItemContainer.class);
        final ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
        final PresenceController presence = new PresenceController(client);
        final SpeechDirector director;
        final Object session;
        long tick = 2;

        Fixture() throws Exception
        {
            when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getTopLevelWorldView()).thenReturn(view);
            when(client.getWorldView(WorldView.TOPLEVEL)).thenReturn(view);
            when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
            when(client.getPlayerOptions()).thenReturn(new String[8]);
            when(client.getVarcStrValue(VarClientID.CHATINPUT)).thenReturn("");
            when(player.getWorldView()).thenReturn(view);
            when(player.getWorldLocation()).thenReturn(new WorldPoint(3250, 3250, 0));
            when(player.getLocalLocation()).thenReturn(new LocalPoint(50 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
            when(view.getId()).thenReturn(WorldView.TOPLEVEL);
            when(view.isTopLevel()).thenReturn(true);
            when(view.getScene()).thenReturn(mock(Scene.class));
            when(view.getBaseX()).thenReturn(3200);
            when(view.getBaseY()).thenReturn(3200);
            when(view.getSizeX()).thenReturn(104);
            when(view.getSizeY()).thenReturn(104);
            when(view.getTileHeights()).thenReturn(new int[4][105][105]);
            when(view.getTileSettings()).thenReturn(new byte[4][104][104]);
            CollisionData collision = mock(CollisionData.class);
            when(collision.getFlags()).thenReturn(new int[104][104]);
            when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
            int[] levels = new int[Skill.values().length], xp = new int[Skill.values().length];
            Arrays.fill(levels, 50); Arrays.fill(xp, 1000);
            when(client.getRealSkillLevels()).thenReturn(levels);
            when(client.getBoostedSkillLevels()).thenReturn(levels);
            when(client.getSkillExperiences()).thenReturn(xp);
            when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(50);
            when(client.getRealSkillLevel(Skill.HITPOINTS)).thenReturn(50);
            ItemContainer inventory = mock(ItemContainer.class);
            when(inventory.getItems()).thenReturn(new Item[0]);
            when(equipment.getItems()).thenReturn(new Item[0]);
            when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
            when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
            observer.setScenarioEnabled("M09"::equals);
            observer.setPresence(true, false, false);
            while (tick < 12) { close(); }
            presence.commit(1, true, true, false, false);
            assertTrue(presence.commit(2, true, true, false, false).isPlacementAllowed());
            presence.placementSucceeded(2);

            Model model = mock(Model.class);
            when(model.getVerticesY()).thenReturn(new float[]{-100});
            when(model.getModelHeight()).thenReturn(100);
            Constructor<?> placement = NativeActor.PlacementSnapshot.class.getDeclaredConstructor(Model.class,
                LocalPoint.class, WorldView.class, int.class, int.class, int.class, long.class);
            placement.setAccessible(true);
            NativeActor.PlacementSnapshot visual = (NativeActor.PlacementSnapshot) placement.newInstance(model,
                new LocalPoint(52 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL), view, 0, 0, 0, 1L);
            // Supply an already prepared visual. UI refresh must not require another native bake/clock advance.
            actor.loadResources();
            Object asset = get(actor, "decodedAsset");
            Class<?> bankType = Class.forName("com.malfoid.asset.NativePoseBank");
            Constructor<?> bankConstructor = bankType.getDeclaredConstructor(Client.class, asset.getClass());
            bankConstructor.setAccessible(true);
            Object bank = bankConstructor.newInstance(client, asset);
            set(bank, "readyAt", 1L);
            set(actor, "bank", bank);
            set(actor, "visible", true);
            set(actor, "placement", visual);
            set(actor, "placementGeneration", 1L);
            set(actor, "view", view);
            set(actor, "scene", view.getScene());
            set(actor, "lastCycle", 0);

            Gson gson = new Gson();
            DialogueCatalog catalog = DialogueCatalog.load(MalfoidPlugin.class, gson);
            SpeechGuard guard = new SpeechGuard(() -> 0L, new Random(1),
                catalog.getMaximumTextKeys(), catalog.getMaximumInteractionKeys());
            director = new SpeechDirector(catalog, () -> 0L, new Random(1), ignored -> {}, guard);
            director.setCandidateValidator(observer::candidateStillValid);
            Class<?> sessionType = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
            Constructor<?> constructor = sessionType.getDeclaredConstructor(long.class, NativeActor.class,
                ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
            constructor.setAccessible(true);
            session = constructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
                new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
            set(session, "director", director);
            set(session, "overlay", new MalfoidSpeechOverlay(director, clientThread, error -> fail(error.toString())));
            set(session, "tick", tick);
            set(session, "presenceSceneGeneration", presence.getSceneGeneration());
            MalfoidConfig config = mock(MalfoidConfig.class);
            when(config.showMalfoid()).thenReturn(true);
            when(config.showDialogue()).thenReturn(true);
            set(plugin, "client", client);
            set(plugin, "clientThread", clientThread);
            set(plugin, "config", config);
            set(plugin, "current", session);
            plugin.onClientTick(new ClientTick());
            assertHealthy();
        }

        SpeechPacket issueOutfit() throws Exception
        {
            when(equipment.getItems()).thenReturn(new Item[]{new Item(1001, 1)});
            plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment));
            assertTrue(close().isEmpty());
            List<SceneCandidate> admitted = close();
            assertEquals(1, admitted.size());
            SceneCandidate candidate = admitted.get(0);
            assertEquals("M09", candidate.getScenarioId());
            assertTrue(observer.candidateStillValid(candidate));
            set(session, "tick", tick);
            director.tick(tick, 1, 1, true, true, false);
            assertTrue(director.offer(candidate));
            director.arrive(candidate.getId(), tick);
            assertNotNull(director.getPacket());
            assertHealthy();
            return director.getPacket();
        }

        List<SceneCandidate> close()
        { return observer.closeGameTick(++tick, 1, 1, true, 2, 3252, 3250, true); }

        Widget visible(int root)
        {
            Widget widget = mock(Widget.class);
            when(widget.getBounds()).thenReturn(new Rectangle(1, 1, 20, 20));
            when(client.getWidget(root)).thenReturn(widget);
            return widget;
        }

        void inspect() throws Exception
        {
            long directorTick = director.getTick(), since = presence.getPresentSinceTick();
            long epoch = presence.getDecision().getPresenceEpoch();
            int stable = presence.getDecision().getStableTicks();
            clearInvocations(client);
            plugin.onClientTick(new ClientTick());
            assertHealthy();
            assertEquals(directorTick, director.getTick());
            assertEquals(tick, ((Number) get(session, "tick")).longValue());
            assertEquals(since, presence.getPresentSinceTick());
            assertEquals(epoch, presence.getDecision().getPresenceEpoch());
            assertEquals(stable, presence.getDecision().getStableTicks());
            assertTrue(presence.isPresent());
            verify(client, times(16)).getWidget(anyInt());
        }

        void assertHealthy() throws Exception
        { assertFalse("ClientTick must not escape through the failure guard", ((AtomicBoolean) get(session, "failed")).get()); }
    }

    private static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }

    private static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
