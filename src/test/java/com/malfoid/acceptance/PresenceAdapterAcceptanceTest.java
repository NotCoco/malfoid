package com.malfoid.acceptance;

import com.malfoid.presence.PresenceController;
import com.malfoid.presence.PresenceReason;
import com.malfoid.presence.PresenceState;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Independent source-shaped checks at the RuneLite adapter boundary, without a game/login. */
public class PresenceAdapterAcceptanceTest
{
    @Test
    public void taggedAttackOptionRevokesAnAlreadyPlacedCompanion()
    {
        Fixture f = new Fixture();
        f.becomePresent();
        f.options[0] = "<col=ff0000>aTtAcK</col>";

        PresenceController.Decision hidden = f.gate.inspect(true, true, false, true);
        assertEquals(PresenceState.ABSENT_AUTO, hidden.getState());
        assertEquals(PresenceReason.PVP_AREA, hidden.getReason());
        assertFalse(hidden.isPlacementAllowed());
        assertFalse(hidden.isPreparationAllowed());

        f.options[0] = null;
        for (int i = 0; i < 30; i++)
        {
            assertFalse(f.gate.inspect(true, true, false, false).isPlacementAllowed());
        }
        for (long tick = 3; tick < 7; tick++)
        {
            assertFalse(f.gate.commit(tick, true, true, false, false).isPlacementAllowed());
        }
        assertTrue(f.gate.commit(7, true, true, false, false).isPlacementAllowed());
    }

    @Test
    public void stockWorldFlagsAndMovingViewsUseTheirAuditedAbsenceReasons()
    {
        for (WorldType type : new WorldType[]{WorldType.PVP, WorldType.DEADMAN})
        {
            Fixture f = new Fixture();
            f.worldTypes.add(type);
            f.pvpArea = 1; f.options[0] = "Attack";
            f.becomePresent(); assertTrue(f.gate.inspect(true, true, false, true).isPresent());
        }
        Fixture arena = new Fixture(); arena.worldTypes.add(WorldType.PVP_ARENA); arena.options[0] = "Attack";
        assertEquals(PresenceReason.PVP_AREA, arena.gate.commit(1, true, true, false, false).getReason());
        Fixture aboard = new Fixture();
        aboard.topLevel = false;
        assertEquals(PresenceReason.ABOARD, aboard.gate.commit(1, true, true, false, false).getReason());

        // A normal instance is allowed; instance membership is not a combat/PvP signal.
        Fixture instance = new Fixture();
        instance.instance = true;
        assertFalse(instance.gate.commit(1, true, true, false, false).isPlacementAllowed());
        assertTrue(instance.gate.commit(2, true, true, false, false).isPlacementAllowed());
    }

    @Test public void wildernessDitchInEitherDirectionDoesNotRemoveHerOrEarnAnotherPresenceEpoch()
    {
        Fixture f = new Fixture(); f.becomePresent(); long epoch = f.gate.getPresenceEpoch();
        f.wilderness = 1; f.pvpArea = 1; f.options[0] = "Attack";
        assertTrue(f.gate.inspect(true, true, false, true).isPresent());
        assertTrue(f.gate.commit(3, true, true, false, true).isPresent());
        f.wilderness = f.pvpArea = 0; f.options[0] = null;
        assertTrue(f.gate.inspect(true, true, false, true).isPresent());
        assertEquals(epoch, f.gate.getPresenceEpoch());
    }

    @Test public void playerCombatWaitEndsAfterOneHundredQuietCommitsPlusOrdinaryStability()
    {
        Fixture f = new Fixture(); f.becomePresent(); f.wilderness = 1; f.options[0] = "Attack";
        MenuEntry attack = mock(MenuEntry.class); when(attack.getType()).thenReturn(MenuAction.PLAYER_FIRST_OPTION);
        when(attack.getOption()).thenReturn("Attack"); when(attack.getPlayer()).thenReturn(mock(Player.class));
        assertTrue(f.gate.onMenuOptionClicked(new MenuOptionClicked(attack), 3));
        assertEquals(PresenceReason.PLAYER_COMBAT, f.gate.inspect(true, true, false, true).getReason());
        assertEquals(PresenceReason.PLAYER_COMBAT, f.gate.commit(3, true, true, false, false).getReason());
        for (long tick = 4; tick <= 103; tick++)
        {
            assertEquals(PresenceReason.PLAYER_COMBAT, f.gate.commit(tick, true, true, false, false).getReason());
            assertFalse(f.gate.inspect(true, true, false, false).isPlacementAllowed());
        }
        assertEquals(PresenceReason.STABILIZING, f.gate.commit(104, true, true, false, false).getReason());
        assertTrue(f.gate.commit(105, true, true, false, false).isPlacementAllowed());
        assertTrue(f.gate.placementSucceeded(105).isPresent());
    }

    @Test
    public void loadingAndReplacementSceneCannotReuseAnOldPresenceEpoch()
    {
        Fixture f = new Fixture();
        f.becomePresent();
        long oldScene = f.gate.getSceneGeneration();
        long oldEpoch = f.gate.getPresenceEpoch();

        f.gameState = GameState.LOADING;
        f.playerAvailable = false;
        assertEquals(PresenceReason.LOADING, f.gate.inspect(true, true, false, true).getReason());
        assertTrue(f.gate.getSceneGeneration() > oldScene);
        assertFalse(f.gate.isPresent());
        assertEquals(-1, f.gate.getPresentSinceTick());

        f.gameState = GameState.LOGGED_IN;
        f.playerAvailable = true;
        f.scene = emptyScene();
        for (long tick = 3; tick < 7; tick++)
        {
            assertFalse(f.gate.commit(tick, true, true, false, false).isPlacementAllowed());
        }
        assertTrue(f.gate.commit(7, true, true, false, false).isPlacementAllowed());
        assertEquals(oldEpoch, f.gate.getPresenceEpoch());
        f.gate.placementSucceeded(7);
        assertEquals(oldEpoch + 1, f.gate.getPresenceEpoch());
        assertEquals(7, f.gate.getPresentSinceTick());
    }

    @Test
    public void mismatchedViewPlaneAndUnknownCollisionFailClosed()
    {
        Fixture wrongView = new Fixture();
        wrongView.localView = 9;
        assertEquals(PresenceReason.INVALID_SCENE, wrongView.gate.commit(1, true, true, false, false).getReason());

        Fixture wrongPlane = new Fixture();
        wrongPlane.plane = 1;
        assertEquals(PresenceReason.INVALID_SCENE, wrongPlane.gate.commit(1, true, true, false, false).getReason());

        Fixture noCollision = new Fixture();
        noCollision.flags = null;
        assertEquals(PresenceReason.INVALID_SCENE, noCollision.gate.commit(1, true, true, false, false).getReason());
    }

    @Test
    public void currentCutsceneVarbitAndVisibleWidgetsAreSampledWithoutGameplayActions()
    {
        Fixture f = new Fixture();
        f.cutscene = 1;
        assertEquals(PresenceReason.CUTSCENE, f.gate.commit(1, true, true, false, false).getReason());
        f.cutscene = 0;
        f.gate.commit(2, true, true, false, false);
        assertFalse(f.gate.isBusy());

        f.typed = " ";
        f.gate.commit(3, true, true, false, false);
        assertTrue(f.gate.isBusy());
        f.typed = null;
        f.widgets.put(InterfaceID.BankpinKeypad.UNIVERSE, widget(false));
        f.gate.commit(4, true, true, false, false);
        assertTrue(f.gate.isBusy());
        f.widgets.put(InterfaceID.BankpinKeypad.UNIVERSE, widget(true));
        f.gate.commit(5, true, true, false, false);
        assertFalse(f.gate.isBusy());
    }

    @Test
    public void documentedPvpAreaAndSpectatorFlagsWorkWithoutAnAttackOption()
    {
        Fixture area = new Fixture();
        area.becomePresent();
        area.pvpArea = 1;
        assertEquals(PresenceReason.PVP_AREA, area.gate.inspect(true, true, false, true).getReason());
        assertFalse(area.gate.isPresent());

        Fixture spectator = new Fixture();
        spectator.becomePresent();
        spectator.partyStatus = 3;
        PresenceController.Decision hidden = spectator.gate.inspect(true, true, false, true);
        assertEquals(PresenceReason.SPECTATING, hidden.getReason());
        assertFalse(hidden.isPlacementAllowed());
        assertFalse(hidden.isPreparationAllowed());
        spectator.partyStatus = 2;
        for (int i = 0; i < 20; i++)
        {
            assertFalse(spectator.gate.inspect(true, true, false, false).isPlacementAllowed());
        }
        assertFalse(spectator.gate.commit(3, true, true, false, false).isPlacementAllowed());
        assertTrue(spectator.gate.commit(4, true, true, false, false).isPlacementAllowed());
    }

    @Test
    public void overlappingCutsceneCannotShortenTheDocumentedAreaDebounce()
    {
        Fixture f = new Fixture();
        f.becomePresent();
        f.pvpArea = f.cutscene = 1;
        assertEquals(PresenceReason.PVP_AREA, f.gate.inspect(true, true, false, true).getReason());
        f.pvpArea = f.cutscene = 0;
        for (long tick = 3; tick < 7; tick++)
        {
            assertFalse(f.gate.commit(tick, true, true, false, false).isPlacementAllowed());
        }
        assertTrue(f.gate.commit(7, true, true, false, false).isPlacementAllowed());
    }

    private static Widget widget(boolean hidden)
    {
        return api(Widget.class, (method, args) -> {
            if ("isHidden".equals(method)) { return hidden; }
            throw new AssertionError("Unexpected widget API: " + method);
        });
    }

    private static Scene emptyScene()
    {
        return api(Scene.class, (method, args) -> {
            throw new AssertionError("Presence must not inspect scene geometry: " + method);
        });
    }

    private static <T> T api(Class<T> type, BiFunction<String, Object[], Object> handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class)
            {
                if ("equals".equals(method.getName())) { return proxy == args[0]; }
                if ("hashCode".equals(method.getName())) { return System.identityHashCode(proxy); }
                return type.getSimpleName() + " acceptance double";
            }
            return handler.apply(method.getName(), args);
        }));
    }

    private static final class Fixture
    {
        private GameState gameState = GameState.LOGGED_IN;
        private Scene scene = emptyScene();
        private boolean playerAvailable = true, topLevel = true, instance;
        private int plane, cutscene, pvpArea, partyStatus, wilderness, localView = WorldView.TOPLEVEL;
        private int[][] flags = new int[104][104];
        private String typed;
        private final String[] options = new String[8];
        private final EnumSet<WorldType> worldTypes = EnumSet.noneOf(WorldType.class);
        private final Map<Integer, Widget> widgets = new HashMap<>();
        private final CollisionData collision = api(CollisionData.class, (method, args) -> {
            if ("getFlags".equals(method)) { return flags; }
            throw new AssertionError("Unexpected collision API: " + method);
        });
        private final WorldView view = api(WorldView.class, (method, args) -> {
            switch (method)
            {
                case "getId": return WorldView.TOPLEVEL;
                case "isTopLevel": return topLevel;
                case "isInstance": return instance;
                case "getScene": return scene;
                case "getPlane": return plane;
                case "getBaseX": case "getBaseY": return 3200;
                case "getSizeX": case "getSizeY": return 104;
                case "getInstanceTemplateChunks": return null;
                case "getCollisionMaps": return new CollisionData[]{collision, collision, collision, collision};
                case "players": return new IndexedObjectSet<Player>()
                {
                    public Player byIndex(int index) { return null; }
                    public Iterator<Player> iterator() { return Collections.<Player>emptyList().iterator(); }
                };
                default: throw new AssertionError("Unexpected view API: " + method);
            }
        });
        private final Player player = api(Player.class, (method, args) -> {
            switch (method)
            {
                case "getWorldView": return view;
                case "getLocalLocation": return new LocalPoint(50 * 128 + 64, 50 * 128 + 64, localView);
                case "getWorldLocation": return new WorldPoint(3250, 3250, 0);
                case "isDead": return false;
                case "getInteracting": return null;
                default: throw new AssertionError("Unexpected player API: " + method);
            }
        });
        private final Client client = api(Client.class, (method, args) -> {
            switch (method)
            {
                case "getGameState": return gameState;
                case "getLocalPlayer": return playerAvailable ? player : null;
                case "getBoostedSkillLevel":
                    assertEquals(Skill.HITPOINTS, args[0]);
                    return 99;
                case "getVarbitValue":
                    int varbit = (Integer) args[0];
                    if (varbit == VarbitID.CUTSCENE_STATUS) { return cutscene; }
                    if (varbit == VarbitID.PVP_AREA_CLIENT) { return pvpArea; }
                    if (varbit == VarbitID.TOB_CLIENT_PARTYSTATUS) { return partyStatus; }
                    assertEquals(VarbitID.INSIDE_WILDERNESS, varbit);
                    return wilderness;
                case "getWorldType": return worldTypes;
                case "getPlayerOptions": return options;
                case "getVarcStrValue":
                    assertEquals(VarClientID.CHATINPUT, args[0]);
                    return typed;
                case "getWidget": return widgets.get((Integer) args[0]);
                default: throw new AssertionError("Unexpected client API/action: " + method);
            }
        });
        private final PresenceController gate = new PresenceController(client);

        private void becomePresent()
        {
            assertFalse(gate.commit(1, true, true, false, false).isPlacementAllowed());
            assertTrue(gate.commit(2, true, true, false, false).isPlacementAllowed());
            gate.placementSucceeded(2);
            assertTrue(gate.isPresent());
        }
    }
}
