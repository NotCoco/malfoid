package com.malfoid.acceptance;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.malfoid.MalfoidConfig;
import com.malfoid.MalfoidPlugin;
import com.malfoid.asset.NativeActor;
import com.malfoid.dialogue.DialogueCatalog;
import com.malfoid.interaction.ChatNotices;
import com.malfoid.interaction.MalfoidMenu;
import com.malfoid.motion.RouteMotion;
import com.malfoid.presence.ModalOcclusionSnapshot;
import com.malfoid.presence.PresenceController;
import com.malfoid.scenario.ScenarioObserver;
import com.malfoid.speech.MalfoidSpeechOverlay;
import com.malfoid.speech.SceneCandidate;
import com.malfoid.speech.SpeechDirector;
import com.malfoid.speech.SpeechGuard;
import com.malfoid.speech.SpeechPacket;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Actual root callbacks/closure/offer/arrival, using a prepared fixed visual and synthetic projection. */
final class AdditionalProducerFixture
{
    final String scenario;
    final Client client = mock(Client.class);
    final ClientThread clientThread = mock(ClientThread.class);
    final Player player = mock(Player.class);
    final WorldView view = mock(WorldView.class);
    final MalfoidConfig config = mock(MalfoidConfig.class);
    final MalfoidPlugin plugin = new MalfoidPlugin();
    final ScenarioObserver observer = new ScenarioObserver(client, mock(ItemManager.class));
    final PresenceController presence = new PresenceController(client);
    final NativeActor actor = new NativeActor(client, new Gson());
    final ItemContainer inventory = mock(ItemContainer.class), equipment = mock(ItemContainer.class);
    final int[] levels = new int[Skill.values().length], boosted = new int[Skill.values().length], xp = new int[Skill.values().length];
    final int[] varps = new int[2000];
    final Map<Integer, Integer> varbits = new HashMap<>();
    final Queue<Runnable> closures = new ArrayDeque<>(), callbacks = new ArrayDeque<>();
    final List<Throwable> failures = new ArrayList<>();
    final NativeChatHeads chat = new NativeChatHeads(client);
    final DialogueCatalog catalogue;
    final SpeechGuard guard;
    final SpeechDirector director;
    final MalfoidSpeechOverlay overlay;
    final Object session;
    WorldPoint point = new WorldPoint(3250, 3250, 0);
    long clockNanos;
    int energy = 5000, weight;
    long frame;
    GameState gameState = GameState.LOGGED_IN;

    AdditionalProducerFixture(int rule) throws Exception
    {
        this(rule, 12);
    }

    AdditionalProducerFixture(int rule, long baselineThrough) throws Exception
    {
        this(rule, baselineThrough, null);
    }

    AdditionalProducerFixture(int rule, long baselineThrough, int[][][] instanceChunks) throws Exception
    {
        scenario = "M" + rule;
        when(client.getGameState()).thenAnswer(call -> gameState);
        when(client.isClientThread()).thenReturn(true);
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(view);
        when(client.getWorldView(WorldView.TOPLEVEL)).thenReturn(view);
        when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
        when(client.getPlayerOptions()).thenReturn(new String[8]);
        when(client.getEnergy()).thenAnswer(call -> energy);
        when(client.getWeight()).thenAnswer(call -> weight);
        when(client.getVarps()).thenReturn(varps);
        when(client.getVarpValue(anyInt())).thenAnswer(call -> varps[call.getArgument(0)]);
        when(client.getVarbitValue(anyInt())).thenAnswer(call -> varbits.getOrDefault(call.getArgument(0), 0));

        when(player.getWorldView()).thenReturn(view);
        when(player.getWorldLocation()).thenAnswer(call -> point);
        when(player.getLocalLocation()).thenAnswer(call -> new LocalPoint(
            (point.getX() - 3200) * 128 + 64, (point.getY() - 3200) * 128 + 64, WorldView.TOPLEVEL));
        when(player.getAnimation()).thenReturn(-1);
        when(view.getId()).thenReturn(WorldView.TOPLEVEL);
        when(view.isTopLevel()).thenReturn(true);
        when(view.getBaseX()).thenReturn(3200); when(view.getBaseY()).thenReturn(3200);
        when(view.getSizeX()).thenReturn(104); when(view.getSizeY()).thenReturn(104);
        when(view.getPlane()).thenReturn(0);
        if (instanceChunks != null)
        {
            when(view.isInstance()).thenReturn(true);
            when(view.getInstanceTemplateChunks()).thenReturn(instanceChunks);
        }
        when(view.contains(any(LocalPoint.class))).thenAnswer(call ->
        {
            LocalPoint location = call.getArgument(0);
            return location != null && location.getWorldView() == view.getId()
                && location.getSceneX() >= 0 && location.getSceneY() >= 0
                && location.getSceneX() < view.getSizeX() && location.getSceneY() < view.getSizeY();
        });
        when(view.getScene()).thenReturn(mock(Scene.class));
        when(view.getTileHeights()).thenReturn(new int[4][105][105]);
        when(view.getTileSettings()).thenReturn(new byte[4][104][104]);
        CollisionData collision = mock(CollisionData.class);
        when(collision.getFlags()).thenReturn(new int[104][104]);
        when(view.getCollisionMaps()).thenReturn(new CollisionData[]{collision, collision, collision, collision});
        Arrays.fill(levels, 50); Arrays.fill(boosted, 50); Arrays.fill(xp, 1000);
        when(client.getRealSkillLevels()).thenAnswer(call -> levels.clone());
        when(client.getBoostedSkillLevels()).thenAnswer(call -> boosted.clone());
        when(client.getSkillExperiences()).thenAnswer(call -> xp.clone());
        when(client.getRealSkillLevel(any(Skill.class))).thenAnswer(call -> levels[((Skill) call.getArgument(0)).ordinal()]);
        when(client.getBoostedSkillLevel(any(Skill.class))).thenAnswer(call -> boosted[((Skill) call.getArgument(0)).ordinal()]);
        when(inventory.getItems()).thenReturn(new Item[0]);
        when(equipment.getItems()).thenReturn(new Item[0]);
        when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
        when(client.getItemContainer(InventoryID.WORN)).thenReturn(equipment);
        when(config.showMalfoid()).thenReturn(true); when(config.showDialogue()).thenReturn(true);
        observer.setScenarioEnabled(scenario::equals);
        presence.commit(1, true, true, false, false);
        assertTrue(presence.commit(2, true, true, false, false).isPlacementAllowed());
        presence.placementSucceeded(2);
        preparedVisual();
        Class<?> type = Class.forName("com.malfoid.MalfoidPlugin$RuntimeSession");
        Constructor<?> constructor = type.getDeclaredConstructor(long.class, NativeActor.class,
            ScenarioObserver.class, PresenceController.class, MalfoidMenu.class, ChatNotices.class);
        constructor.setAccessible(true);
        session = constructor.newInstance(1L, actor, observer, presence, new MalfoidMenu(client),
            new ChatNotices(client, mock(ChatMessageManager.class), mock(ConfigManager.class)));
        set(session, "tick", 2L); set(session, "presenceSceneGeneration", presence.getSceneGeneration());
        set(plugin, "client", client); set(plugin, "clientThread", clientThread); set(plugin, "config", config);
        set(plugin, "current", session);
        ((AtomicLong) get(plugin, "generations")).set(1L);
        doAnswer(call -> { closures.add(call.getArgument(0)); return null; })
            .when(clientThread).invokeAtTickEnd(any(Runnable.class));
        doAnswer(call -> { callbacks.add(call.getArgument(0)); return null; })
            .when(clientThread).invokeLater(any(Runnable.class));
        catalogue = copiedCatalogue();
        guard = new SpeechGuard(() -> clockNanos, new Random(1), catalogue.getMaximumTextKeys(), catalogue.getMaximumInteractionKeys());
        director = new SpeechDirector(catalogue, () -> clockNanos, new Random(1), ignored -> {}, guard);
        director.setCandidateValidator(observer::candidateStillValid);
        overlay = new MalfoidSpeechOverlay(director, clientThread, failures::add);
        set(session, "catalog", catalogue); set(session, "director", director); set(session, "overlay", overlay);
        publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ModalOcclusionSnapshot.CLEAR);
        while (tick() < baselineThrough) { commit(); }
        assertNull(director.getActiveCandidate());
    }

    private void preparedVisual() throws Exception
    {
        // Native bake, movement and world projection are independently tested; this is a fixed registered visual.
        actor.loadResources();
        Object asset = get(actor, "decodedAsset");
        Class<?> type = Class.forName("com.malfoid.asset.NativePoseBank");
        Constructor<?> constructor = type.getDeclaredConstructor(Client.class, asset.getClass());
        constructor.setAccessible(true);
        Object bank = constructor.newInstance(client, asset);
        set(bank, "readyAt", 1L); set(actor, "bank", bank);
        Model model = mock(Model.class); when(model.getModelHeight()).thenReturn(100);
        when(model.getVerticesY()).thenReturn(new float[]{-100, 6});
        Arrays.fill((Model[]) get(bank, "models"), model);
        Constructor<?> placement = NativeActor.PlacementSnapshot.class.getDeclaredConstructor(Model.class,
            LocalPoint.class, WorldView.class, int.class, int.class, int.class, long.class);
        placement.setAccessible(true);
        set(actor, "placement", placement.newInstance(model, new LocalPoint(54 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL),
            view, 0, 0, 0, 1L));
        set(actor, "visible", true); set(actor, "placementGeneration", 1L); set(actor, "view", view);
        set(actor, "scene", view.getScene()); set(actor, "baseX", 3200); set(actor, "baseY", 3200);
        set(actor, "sizeX", view.getSizeX()); set(actor, "sizeY", view.getSizeY()); set(actor, "plane", view.getPlane());
        set(actor, "playerX", 50); set(actor, "playerY", 50); set(actor, "lastCycle", 0);
        ((RouteMotion) get(actor, "motion")).place(54, 50, 0);
    }

    long tick() throws Exception { return ((Number) get(session, "tick")).longValue(); }

    void commit() throws Exception
    {
        long before = tick();
        plugin.onGameTick(new GameTick());
        assertEquals(1, closures.size());
        closures.remove().run();
        assertEquals(before + 1, tick());
        assertHealthy();
    }

    void inspect() throws Exception
    {
        long before = tick(), original = director.getTick();
        plugin.onClientTick(new ClientTick());
        assertEquals(before, tick()); assertEquals(original, director.getTick());
        assertHealthy();
    }

    void itemDefinition(int id, String name, boolean stackable)
    {
        ItemComposition item = mock(ItemComposition.class);
        when(item.getId()).thenReturn(id); when(item.getName()).thenReturn(name);
        when(item.getNote()).thenReturn(-1); when(item.getPlaceholderTemplateId()).thenReturn(-1);
        when(item.isStackable()).thenReturn(stackable); when(client.getItemDefinition(id)).thenReturn(item);
    }

    void inventory(Item... items) throws Exception
    {
        when(inventory.getItems()).thenReturn(items);
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inventory)); assertHealthy();
    }

    void worn(int slot, int id) throws Exception
    {
        Item[] items = new Item[14]; if (id >= 0) { items[slot] = new Item(id, 1); }
        when(equipment.getItems()).thenReturn(items);
        plugin.onItemContainerChanged(new ItemContainerChanged(InventoryID.WORN, equipment)); assertHealthy();
    }

    void stat(Skill skill, int current) throws Exception
    {
        boosted[skill.ordinal()] = current;
        StatChanged event = new StatChanged(skill, xp[skill.ordinal()], levels[skill.ordinal()], current);
        plugin.onStatChanged(event); assertHealthy();
    }

    void varbit(int id, int value) throws Exception
    { varbits.put(id, value); plugin.onVarbitChanged(new VarbitChanged()); assertHealthy(); }

    void poison(int value) throws Exception
    { varps[102] = value; plugin.onVarbitChanged(new VarbitChanged()); assertHealthy(); }

    void hit(int type, int amount, boolean mine) throws Exception
    {
        Hitsplat splat = mock(Hitsplat.class); when(splat.getHitsplatType()).thenReturn(type);
        when(splat.getAmount()).thenReturn(amount); when(splat.isMine()).thenReturn(mine);
        HitsplatApplied event = new HitsplatApplied(); event.setActor(player); event.setHitsplat(splat);
        plugin.onHitsplatApplied(event); assertHealthy();
    }

    void combatTarget() throws Exception
    {
        NPC npc = mock(NPC.class); NPCComposition definition = mock(NPCComposition.class);
        when(definition.getName()).thenReturn("Goblin"); when(definition.getSize()).thenReturn(1);
        when(npc.getTransformedComposition()).thenReturn(definition); when(npc.getComposition()).thenReturn(definition);
        when(npc.getWorldView()).thenReturn(view); when(npc.getWorldLocation()).thenReturn(new WorldPoint(3251, 3250, 0));
        when(npc.getLocalLocation()).thenReturn(new LocalPoint(51 * 128 + 64, 50 * 128 + 64, WorldView.TOPLEVEL));
        plugin.onNpcSpawned(new NpcSpawned(npc)); when(player.getInteracting()).thenReturn(npc);
        assertHealthy();
    }

    Widget open(int root)
    {
        Widget widget = mock(Widget.class); when(widget.getBounds()).thenReturn(new Rectangle(1, 1, 20, 20));
        when(client.getWidget(root)).thenReturn(widget); return widget;
    }

    void report(int id, ChatMessageType type, String text) throws Exception
    {
        plugin.onChatMessage(chat.message(id, type, text)); assertHealthy();
    }

    SpeechPacket issued() throws Exception
    {
        SceneCandidate candidate = director.getActiveCandidate();
        assertNotNull("Root must admit the actual producer", candidate); assertEquals(scenario, candidate.getScenarioId());
        assertTrue(catalogue.hasValidOriginalContext(candidate));
        assertTrue("The prepared visual must satisfy real scene/collision/height arrival checks", actor.hasArrived());
        assertNotNull("Root must acknowledge the existing legal native hold", director.getPacket());
        assertHealthy(); return director.getPacket();
    }

    void publish(Point anchor, Rectangle viewport, ModalOcclusionSnapshot modal)
    { overlay.publish(anchor, viewport, ++frame, new ArrayList<>(), modal); }

    BufferedImage render()
    {
        BufferedImage image = new BufferedImage(512, 334, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { overlay.render(graphics); } finally { graphics.dispose(); }
        assertTrue("Rendering must not escape through the failure guard", failures.isEmpty()); return image;
    }

    void paintAndDebit(SpeechPacket packet) throws Exception
    {
        assertEquals(0, guard.textCount()); assertEquals(0, guard.interactionCount()); assertEquals(0, guard.situationCount());
        assertNull(director.getLastPaint());
        publish(new Point(256, 250), new Rectangle(0, 0, 512, 334), ModalOcclusionSnapshot.CLEAR);
        assertTrue(hasPixels(render())); assertNotNull(packet.getFirstPaint());
        assertEquals(tick(), packet.getFirstPaint().getPaintedAtTick());
        assertEquals(0, guard.textCount()); assertEquals(0, guard.interactionCount()); assertEquals(1, callbacks.size());
        drain(); assertSame(packet.getFirstPaint(), director.getLastPaint()); assertEquals(1, guard.textCount());
        assertEquals(1, guard.interactionCount());
        assertEquals(catalogue.situationFingerprint(packet.getCandidate()) == null ? 0 : 1, guard.situationCount());
        assertTrue(hasPixels(render())); assertEquals(0, callbacks.size()); assertEquals(1, guard.textCount());
    }

    void loading() throws Exception
    {
        gameState = GameState.LOADING;
        GameStateChanged event = new GameStateChanged(); event.setGameState(gameState);
        plugin.onGameStateChanged(event); assertHealthy();
    }

    void restorePresent() throws Exception
    {
        gameState = GameState.LOGGED_IN; when(config.showMalfoid()).thenReturn(true);
        assertFalse(actor.isVisible());
        // Only committed clear ticks permit placement; rapid scene loss requires five rather than two.
        for (int clearTick = 0; clearTick < 5 && !presence.getDecision().isPlacementAllowed(); clearTick++) { commit(); }
        assertTrue("Committed stability must permit a new placement", presence.getDecision().isPlacementAllowed());
        assertFalse("Placement permission alone must not restore PRESENT", presence.isPresent());
        preparedVisual(); commit();
        assertTrue(presence.isPresent());
        assertTrue(actor.isVisible());
        long since = presence.getPresentSinceTick();
        while (tick() < since + 10) { commit(); }
        assertHealthy();
    }

    void drain() { while (!callbacks.isEmpty()) { callbacks.remove().run(); } }
    void assertHealthy() throws Exception
    { assertFalse("Root callback failure guard must remain clear", ((AtomicBoolean) get(session, "failed")).get()); }

    private DialogueCatalog copiedCatalogue() throws Exception
    {
        Gson gson = new Gson(); JsonObject copy;
        try (InputStream input = MalfoidPlugin.class.getResourceAsStream("/com/malfoid/dialogue.json"))
        { copy = gson.fromJson(new InputStreamReader(input, StandardCharsets.UTF_8), JsonObject.class); }
        copy.getAsJsonArray("runtimeEnabledScenarios").add(scenario);
        copy.getAsJsonObject("silencePercentByScenario").addProperty(scenario, 0);
        for (JsonElement element : copy.getAsJsonArray("entries"))
        {
            JsonObject entry = element.getAsJsonObject();
            if (scenario.equals(entry.get("scenario").getAsString())) { entry.addProperty("status", "enabled"); }
        }
        return DialogueCatalog.read(new ByteArrayInputStream(gson.toJson(copy).getBytes(StandardCharsets.UTF_8)), gson);
    }

    static boolean hasPixels(BufferedImage image)
    {
        for (int y = 0; y < image.getHeight(); y++)
        { for (int x = 0; x < image.getWidth(); x++) { if (image.getRGB(x, y) != 0) { return true; } } }
        return false;
    }

    static Object get(Object target, String name) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    static void set(Object target, String name, Object value) throws Exception
    { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
