package com.malfoid.asset;

import com.google.gson.Gson;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.motion.RecoveryObservation;
import com.malfoid.motion.RecoveryPolicy;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.scenario.ScenarioRules;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechCancellationReceipt;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Filepath;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Only prepared model-bank references and normal root dependencies are injected.
 * Presence, registration, movement, recovery, scenario facts and speech receipts
 * are produced by the real root callbacks. Projection uses the real Perspective.
 */
final class NativeRecoveryRootFixture
{
    final Client client = mock(Client.class);
    final ClientThread clientThread = mock(ClientThread.class);
    final Player player = mock(Player.class);
    final WorldView view = mock(WorldView.class);
    final Scene scene = mock(Scene.class);
    final int[][] flags = new int[104][104];
    final NativeActor actor = new NativeActor(client, new Gson(), new Random(7));
    final ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
    final PresenceController presence = new PresenceController(client);
    final MalfoidPlugin plugin = new MalfoidPlugin();
    final DialogueCatalog catalogue;
    final SpeechGuard guard;
    final SpeechDirector director;
    final MalfoidSpeechOverlay overlay;
    final Object session, log;
    final List<Throwable> failures = new ArrayList<>();
    final List<String> trace = new ArrayList<>();
    final Queue<Runnable> closures = new ArrayDeque<>(), callbacks = new ArrayDeque<>();
    final int[] levels = new int[Skill.values().length];
    final int[] boosted = new int[Skill.values().length];
    final int[] xp = new int[Skill.values().length];
    private final Method logRecord;
    int playerX = 50, playerY = 50, cycle;
    long clockNanos;
    boolean dead;

    NativeRecoveryRootFixture() throws Exception
    {
        when(client.isClientThread()).thenReturn(true);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(view);
        when(client.getWorldView(WorldView.TOPLEVEL)).thenReturn(view);
        when(client.getGameCycle()).thenAnswer(call -> cycle);
        when(client.getWorld()).thenReturn(301);
        when(client.getAccountHash()).thenReturn(47L); // Synthetic identity, never a profile read.
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getPlayerOptions()).thenReturn(new String[8]);
        when(client.getEnergy()).thenReturn(5000);
        when(client.getVarps()).thenReturn(new int[2000]);

        when(player.getWorldView()).thenReturn(view);
        when(player.getLocalLocation()).thenAnswer(call -> point(playerX, playerY));
        when(player.getWorldLocation()).thenAnswer(call -> new WorldPoint(3200 + playerX, 3200 + playerY, 0));
        when(player.getAnimation()).thenReturn(-1);
        when(player.isDead()).thenAnswer(call -> dead);
        when(view.getId()).thenReturn(WorldView.TOPLEVEL);
        when(view.isTopLevel()).thenReturn(true);
        when(view.getScene()).thenReturn(scene);
        when(view.getPlane()).thenReturn(0);
        when(view.getBaseX()).thenReturn(3200);
        when(view.getBaseY()).thenReturn(3200);
        when(view.getSizeX()).thenReturn(104);
        when(view.getSizeY()).thenReturn(104);
        when(view.contains(any(LocalPoint.class))).thenAnswer(call ->
        {
            LocalPoint p = call.getArgument(0);
            return p != null && p.getWorldView() == WorldView.TOPLEVEL && p.getSceneX() >= 0
                && p.getSceneY() >= 0 && p.getSceneX() < 104 && p.getSceneY() < 104;
        });
        when(view.getTileSettings()).thenReturn(new byte[4][104][104]);
        int[][][] heights = new int[4][105][105];
        for (int[][] plane : heights) for (int[] row : plane) Arrays.fill(row, 120);
        when(view.getTileHeights()).thenReturn(heights);
        when(scene.getTiles()).thenReturn(new Tile[4][104][104]);
        CollisionData collision = mock(CollisionData.class);
        when(collision.getFlags()).thenReturn(flags);
        when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
        Arrays.fill(levels, 50); Arrays.fill(boosted, 50); Arrays.fill(xp, 1000);
        when(client.getRealSkillLevels()).thenReturn(levels);
        when(client.getBoostedSkillLevels()).thenReturn(boosted);
        when(client.getSkillExperiences()).thenReturn(xp);
        when(client.getRealSkillLevel(any(Skill.class))).thenAnswer(call -> levels[((Skill) call.getArgument(0)).ordinal()]);
        when(client.getBoostedSkillLevel(any(Skill.class))).thenAnswer(call -> boosted[((Skill) call.getArgument(0)).ordinal()]);
        ItemContainer inventory = mock(ItemContainer.class), equipment = mock(ItemContainer.class);
        when(inventory.getItems()).thenReturn(new Item[0]);
        when(equipment.getItems()).thenReturn(new Item[0]);
        when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
        when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
        when(client.getViewportWidth()).thenReturn(800);
        when(client.getViewportHeight()).thenReturn(600);
        when(client.getScale()).thenReturn(512);
        when(client.getCameraX()).thenReturn(52 * 128 + 64);
        when(client.getCameraZ()).thenReturn(120);
        hideOrigin();
        prepareBank();

        MalfoidConfig config = mock(MalfoidConfig.class);
        when(config.showMalfoid()).thenReturn(true);
        when(config.showDialogue()).thenReturn(true);
        Class<?> runtime = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
        Constructor<?> runtimeConstructor = runtime.getDeclaredConstructor(long.class, NativeActor.class,
            ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
        runtimeConstructor.setAccessible(true);
        session = runtimeConstructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
            new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
        set(plugin, "client", client); set(plugin, "clientThread", clientThread);
        set(plugin, "config", config); set(plugin, "gson", new Gson()); set(plugin, "current", session);
        doAnswer(call -> { closures.add(call.getArgument(0)); return null; })
            .when(clientThread).invokeAtTickEnd(any(Runnable.class));
        doAnswer(call -> { callbacks.add(call.getArgument(0)); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));

        // Real bounded rolling-log queue; its filesystem and scheduler dependencies
        // are mocked so this fixture cannot touch a RuneLite profile or run a pump.
        Filepath directory = mock(Filepath.class), file = mock(Filepath.class), previous = mock(Filepath.class);
        when(directory.joinSegment("malfoid.log")).thenReturn(file);
        when(directory.joinSegment("malfoid.previous.log")).thenReturn(previous);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), eq(1L), eq(2L), eq(TimeUnit.SECONDS));
        Class<?> logType = Class.forName("com.malfoid.SessionLog");
        Constructor<?> logConstructor = logType.getDeclaredConstructor(Filepath.class, ScheduledExecutorService.class, Consumer.class);
        logConstructor.setAccessible(true);
        log = logConstructor.newInstance(directory, executor, (Consumer<Throwable>) failures::add);
        logRecord = logType.getDeclaredMethod("record", String.class); logRecord.setAccessible(true);
        set(session, "log", log); set(plugin, "executor", executor);

        catalogue = DialogueCatalog.load(MalfoidPlugin.class, new Gson());
        Random spoken = new LastChoice();
        guard = new SpeechGuard(() -> clockNanos, new LastChoice(),
            catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
        director = new SpeechDirector(catalogue, () -> clockNanos, spoken, new LastChoice(), this::record,
            guard);
        director.resetSession(1L);
        observer.setScenarioEnabled(catalogue::isRuntimeScenarioEnabled);
        observer.setInspectionSuppression(actor::isInspectionSuppressed);
        director.setCandidateValidator(c -> c.isRequested() || observer.candidateStillValid(c));
        director.setLineValidator(observer::groundOutcomeLineStillValid);
        director.setFirstPaintListener(observer::executionPainted);
        director.setCompletionListener(c ->
        {
            if ("M02".equals(c.getScenarioId())) observer.reunionCompleted(currentTick());
            if ("M08".equals(c.getScenarioId()) || "M69".equals(c.getScenarioId())) observer.inspectionCompleted(c);
            record("SCENE_COMPLETED scenario=" + c.getScenarioId());
        });
        director.setCancellationListener(receipt -> invokeRoot("inspectionCancelled", SpeechCancellationReceipt.class, receipt));
        director.setSilentArrivalListener(c ->
        {
            if ("E01".equals(c.getScenarioId())) observer.emoteArrived(c);
            if ("M05".equals(c.getScenarioId())) observer.groundOutcomeSettled(c);
        });
        overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
        set(session, "catalog", catalogue); set(session, "director", director);
        set(session, "overlay", overlay); set(session, "resourcesLoaded", true);

        fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        for (int x = 49; x <= 51; x++) for (int y = 49; y <= 51; y++) flags[x][y] = 0;
        flags[52][50] = 0;
        commit();
        assertFalse(actor.isVisible()); // Presence earns its own first stability commit.
        commit();
        assertTrue(actor.isVisible()); assertTrue(presence.isPresent());
        assertEquals(point(52, 50), actor.getLocation());
        assertEquals(2, presence.getPresentSinceTick());
        assertEquals(1, actor.getPlanningCount());
        fill(0);
        assertHealthy();
    }

    private void prepareBank() throws Exception
    {
        actor.loadResources();
        NativeAsset asset = (NativeAsset) get(actor, "decodedAsset");
        NativePoseBank bank = new NativePoseBank(client, asset);
        Model model = mock(Model.class);
        float[] coordinates = asset.clips.get("IDLE").samples[0];
        float[] y = new float[coordinates.length / 3];
        float minY = 0;
        for (int i = 0; i < y.length; i++) { y[i] = coordinates[i * 3 + 1]; minY = Math.min(minY, y[i]); }
        when(model.getVerticesY()).thenReturn(y);
        when(model.getModelHeight()).thenReturn((int) Math.ceil(-minY));
        Arrays.fill((Model[]) get(bank, "models"), model);
        set(bank, "readyAt", 1L); set(actor, "bank", bank);
    }

    void commit() throws Exception
    {
        long before = tick(), searches = actor.getPlanningCount();
        plugin.onGameTick(new GameTick());
        assertEquals("Root closes exactly one committed interval", 1, closures.size());
        closures.remove().run();
        drainCallbacks();
        assertEquals(before + 1, tick());
        assertTrue("Sole native BFS is bounded per root commit", actor.getPlanningCount() - searches <= 1);
        assertHealthy();
        clientTicks(30);
    }

    void commitsThrough(long tick) throws Exception { while (tick() < tick) commit(); }

    void clientTicks(int count) throws Exception
    {
        long searches = actor.getPlanningCount(), tick = tick();
        for (int i = 0; i < count; i++)
        {
            cycle++; clockNanos += 20_000_000L;
            plugin.onClientTick(new ClientTick()); drainCallbacks(); assertHealthy();
        }
        assertEquals("Render callbacks never search", searches, actor.getPlanningCount());
        assertEquals("Render callbacks never mint root ticks", tick, tick());
    }

    void baseline() throws Exception
    {
        // Preserve the real unique spawn through this fixture's baseline. E.2's
        // earliest rest can otherwise introduce a random stroll before its cause.
        fill(CollisionDataFlag.BLOCK_MOVEMENT_FULL);
        flags[50][50] = flags[51][50] = flags[52][50] = 0;
        commitsThrough(12);
        assertTrue(director.isIdle());
        assertFalse(actor.isMoving());
        fill(0);
    }

    void boxSource()
    {
        LocalPoint source = actor.getLocation();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            if (dx != 0 || dy != 0) flags[source.getSceneX() + dx][source.getSceneY() + dy] = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
        flags[source.getSceneX()][source.getSceneY()] = flags[playerX][playerY] = 0;
    }

    void openFreshSourceNeighbor()
    {
        LocalPoint source = actor.getLocation();
        flags[source.getSceneX() + 1][source.getSceneY()] = 0;
    }

    void armOffScreenFour() throws Exception
    {
        hideOrigin(); boxSource();
        for (int i = 0; policy().getOffScreenTicks() < 4 && i < 16; i++)
        {
            walkAwayFromSource(); commit();
            assertEquals("No early relocation", 0, actor.getRecoveryCount());
        }
        assertEquals(4, policy().getOffScreenTicks());
        assertTrue(actor.getDistanceToPlayer() >= 16);
        assertTrue(director.isIdle());
    }

    RecoveryObservation relocate() throws Exception
    {
        armOffScreenFour();
        return finishArmedRelocation();
    }

    RecoveryObservation finishArmedRelocation() throws Exception
    {
        while (tick() + 1 < policy().getPlanNotBefore()) commit();
        openFreshSourceNeighbor(); commit();
        RecoveryObservation result = actor.getRecoveryObservation();
        assertNotNull(result); assertEquals(RecoveryObservation.Disposition.RELOCATED, result.disposition);
        assertEquals(tick(), result.committedTick); assertEquals(1, actor.getRecoveryCount());
        return result;
    }

    void walkAwayFromSource()
    {
        LocalPoint source = actor.getLocation();
        int dx = Integer.compare(playerX, source.getSceneX());
        int dy = dx == 0 ? Integer.compare(playerY, source.getSceneY()) : 0;
        assertTrue("The current completed scene tile is separate from the player", dx != 0 || dy != 0);
        assertEquals("Observed movement's intermediate tile stays raw-legal", 0, flags[playerX + dx][playerY + dy]);
        playerX += 2 * dx; playerY += 2 * dy;
        assertEquals("Observed movement's destination stays raw-legal", 0, flags[playerX][playerY]);
    }

    void requestTalk()
    {
        invokeRoot("talk", null, null); // Real local command; it constructs its own current candidate/facts.
    }

    SceneCandidate deathReunion() throws Exception
    {
        showOrigin(); dead = true; boosted[Skill.HITPOINTS.ordinal()] = 0;
        plugin.onActorDeath(new ActorDeath(player)); assertHealthy(); commit();
        dead = false; boosted[Skill.HITPOINTS.ordinal()] = 50; fill(0);
        for (int i = 0; director.getActiveCandidate() == null && i < 12; i++) commit();
        SceneCandidate candidate = director.getActiveCandidate();
        assertNotNull("Actual death and stable return must produce M02", candidate);
        assertEquals("M02", candidate.getScenarioId());
        for (int i = 0; director.getPacket() == null && i < 8; i++) commit();
        assertNotNull("Root acknowledges its real legal reunion arrival", director.getPacket());
        return candidate;
    }

    SpeechPacket.PaintReceipt paintCurrent() throws Exception
    {
        SpeechPacket packet = director.getPacket(); assertNotNull(packet);
        LocalPoint actual = actor.getLocation();
        when(client.getCameraX()).thenReturn(actual.getX());
        when(client.getCameraY()).thenReturn(actual.getY() - 512);
        clientTicks(1);
        assertTrue("Root's actual Perspective head anchor must be visible", overlay.isHeadVisible());
        assertTrue("Actual overlay rasterization supplies paint", pixels(render()) > 0);
        drainCallbacks();
        assertNotNull(packet.getFirstPaint()); assertSame(packet.getFirstPaint(), director.getLastPaint());
        clientTicks(1); // Root observes the actual acknowledged presentation/parent.
        return packet.getFirstPaint();
    }

    void waitUntilIdle() throws Exception
    {
        for (int i = 0; !director.isIdle() && i < 70; i++) commit();
        assertTrue("Execution must finish through real root time", director.isIdle());
        for (int i = 0; actor.isMoving() && i < 4; i++) commit();
        assertFalse(actor.isMoving());
    }

    void confirmNpcCombat() throws Exception
    {
        NPC npc = mock(NPC.class); NPCComposition composition = mock(NPCComposition.class);
        when(composition.getId()).thenReturn(123); when(composition.getName()).thenReturn("Goblin");
        when(composition.getSize()).thenReturn(1);
        when(npc.getId()).thenReturn(123); when(npc.getIndex()).thenReturn(7);
        when(npc.getComposition()).thenReturn(composition); when(npc.getTransformedComposition()).thenReturn(composition);
        when(npc.getWorldView()).thenReturn(view);
        when(npc.getLocalLocation()).thenAnswer(call -> point(playerX + 1, playerY));
        when(npc.getWorldLocation()).thenAnswer(call -> new WorldPoint(3201 + playerX, 3200 + playerY, 0));
        plugin.onNpcSpawned(new NpcSpawned(npc)); when(player.getInteracting()).thenReturn(npc);
        Hitsplat hit = mock(Hitsplat.class); when(hit.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_ME);
        when(hit.getAmount()).thenReturn(1); when(hit.isMine()).thenReturn(true);
        HitsplatApplied event = new HitsplatApplied(); event.setActor(npc); event.setHitsplat(hit);
        plugin.onHitsplatApplied(event); commit();
        assertEquals(ScenarioRules.CombatState.CONFIRMED, observer.getCombatState());
        assertTrue(observer.isCombatConfirmed());
    }

    BufferedImage render()
    {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { overlay.render(graphics); } finally { graphics.dispose(); }
        return image;
    }

    static int pixels(BufferedImage image)
    {
        int result = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            if (image.getRGB(x, y) >>> 24 != 0) result++;
        return result;
    }

    @SuppressWarnings("unchecked") List<String> logs() throws Exception
    { return new ArrayList<>((ArrayBlockingQueue<String>) get(log, "pending")); }

    List<String> recoveryLogs() throws Exception
    {
        List<String> result = new ArrayList<>();
        for (String line : logs()) if (line.contains(" RECOVERY_OBSERVATION ")) result.add(line);
        return result;
    }

    ScenarioRules rules() throws Exception { return (ScenarioRules) get(observer, "rules"); }
    RecoveryPolicy policy() throws Exception { return (RecoveryPolicy) get(actor, "recovery"); }
    long tick() throws Exception { return (long) get(session, "tick"); }
    private long currentTick() { try { return tick(); } catch (Exception error) { throw new AssertionError(error); } }
    void fill(int value) { for (int[] row : flags) Arrays.fill(row, value); }
    void showOrigin() { when(client.getCameraY()).thenReturn(5952); }
    void hideOrigin() { when(client.getCameraY()).thenReturn(10000); }

    void assertHealthy() throws Exception
    {
        assertTrue("No render/log callback failure: " + failures, failures.isEmpty());
        assertFalse("Root must contain no fixture callback failure", ((java.util.concurrent.atomic.AtomicBoolean) get(session, "failed")).get());
        assertTrue(closures.isEmpty());
    }

    private void drainCallbacks()
    {
        int count = 0;
        while (!callbacks.isEmpty()) { assertTrue(++count <= 64); callbacks.remove().run(); }
    }

    private void record(String text)
    {
        trace.add(text);
        try { logRecord.invoke(log, text); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void invokeRoot(String name, Class<?> argumentType, Object argument)
    {
        try
        {
            Method method = argumentType == null
                ? MalfoidPlugin.class.getDeclaredMethod(name, session.getClass())
                : MalfoidPlugin.class.getDeclaredMethod(name, session.getClass(), argumentType);
            method.setAccessible(true);
            if (argumentType == null) method.invoke(plugin, session); else method.invoke(plugin, session, argument);
        }
        catch (InvocationTargetException error) { throw new AssertionError(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    static LocalPoint point(int x, int y) { return new LocalPoint(x * 128 + 64, y * 128 + 64, WorldView.TOPLEVEL); }

    /** Existing random decisions always take a spoken branch; no extra product draw is added. */
    private static final class LastChoice extends Random
    {
        @Override public int nextInt(int bound) { return bound - 1; }
        @Override public double nextDouble() { return 0.99; }
    }
}
