package com.malfoid.asset;

import com.malfoid.motion.CreatureAwareness;
import com.malfoid.motion.CreatureAwareness.Classification;
import com.malfoid.motion.CreatureAwareness.Creature;
import com.malfoid.motion.CreatureAwareness.Sample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** One bounded active-view NPC copy, shared by E.5 and the data-driven living world. */
final class CreatureIntake
{
    private final Map<NPC, Long> identities = new IdentityHashMap<>();
    private final Set<NPC> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private long sequence;
    private WorldView scopedView;
    private Scene scopedScene;
    private int scopedBaseX, scopedBaseY, scopedPlane, scopedSizeX, scopedSizeY;
    private BiFunction<String, Integer, Classification> classifier = CreatureAwareness::classifyFear;
    private Classification localFollowerClassification;

    void setClassifier(BiFunction<String, Integer, Classification> classifier) { this.classifier = classifier; clear(); }
    void setLocalFollowerClassification(Classification classification) { localFollowerClassification = classification; }
    Sample sample(Client client, WorldView view, long tick)
    {
        captureScope(view);
        List<Creature> copied = new ArrayList<>();
        List<NPC> sources = new ArrayList<>();
        boolean complete = view != null && view.npcs() != null;
        boolean known = complete;
        Set<NPC> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int count = 0;
        NPC localFollower = client.getFollower();
        if (known)
        {
            for (NPC npc : view.npcs())
            {
                if (++count > 256) { known = complete = false; break; }
                if (npc == null || npc.getWorldView() != view) { continue; }
                seen.add(npc);
                if (npc.isDead() || retired.contains(npc)) { continue; }
                NPCComposition definition = npc.getTransformedComposition();
                LocalPoint point = npc.getLocalLocation(); WorldPoint world = npc.getWorldLocation();
                if (definition == null || point == null || world == null) { known = false; continue; }
                if (world.getPlane() != view.getPlane()
                    || point.getWorldView() != view.getId()) { continue; }
                String name = CreatureAwareness.normalizeName(definition.getName());
                Classification classification = npc == localFollower ? localFollowerClassification
                    : definition.isFollower() ? null : classifier.apply(name, definition.getCombatLevel());
                if (classification == null) { continue; }
                sources.add(npc);
                // A large creature's local point is its middle; its south-west tile is half its size back from there.
                int size = Math.max(1, definition.getSize()), half = (size - 1) * 64;
                copied.add(new Creature(null, classification, name, definition.getCombatLevel(),
                    view.getBaseX() + ((point.getX() - half) >> 7), view.getBaseY() + ((point.getY() - half) >> 7),
                    world.getPlane(), size));
            }
        }
        // An incomplete pass cannot prove despawn or retire an unchanged incarnation.
        if (complete) { identities.keySet().retainAll(seen); retired.retainAll(seen); }
        List<Creature> creatures = new ArrayList<>(copied.size());
        for (int index = 0; index < copied.size(); index++)
        {
            NPC npc = sources.get(index); Long serial = identities.get(npc);
            if (serial == null)
            {
                if (identities.size() + retired.size() >= 256) { known = false; continue; }
                serial = ++sequence; identities.put(npc, serial);
            }
            Creature creature = copied.get(index);
            creatures.add(new Creature("creature:" + serial, creature.classification, creature.name, creature.combatLevel,
                creature.worldX, creature.worldY, creature.plane, creature.size));
        }
        return new Sample(tick, view == null ? Integer.MIN_VALUE : view.getId(), view == null ? 0 : view.getBaseX(),
            view == null ? 0 : view.getBaseY(), view == null ? -1 : view.getPlane(), known, creatures);
    }
    void invalidate(NPC npc) { identities.remove(npc); }
    void retire(NPC npc) { if (identities.remove(npc) != null) { retired.add(npc); } }
    void revive(NPC npc) { identities.remove(npc); retired.remove(npc); }
    Creature current(Client client, WorldView view, String key)
    {
        if (!matchesScope(view) || key == null) { return null; }
        for (Map.Entry<NPC, Long> entry : identities.entrySet())
        {
            if (!key.equals("creature:" + entry.getValue())) { continue; }
            NPC npc = entry.getKey(); NPCComposition definition = npc.getTransformedComposition();
            LocalPoint point = npc.getLocalLocation(); WorldPoint world = npc.getWorldLocation();
            if (npc.isDead() || npc.getWorldView() != view || definition == null || point == null || world == null
                || point.getWorldView() != view.getId() || world.getPlane() != view.getPlane()) { return null; }
            String name = CreatureAwareness.normalizeName(definition.getName());
            Classification classification = npc == client.getFollower() ? localFollowerClassification
                : definition.isFollower() ? null : classifier.apply(name, definition.getCombatLevel());
            int size = Math.max(1, definition.getSize()), half = (size - 1) * 64;
            return classification == null ? null : new Creature(key, classification, name, definition.getCombatLevel(),
                view.getBaseX() + ((point.getX() - half) >> 7), view.getBaseY() + ((point.getY() - half) >> 7),
                world.getPlane(), size);
        }
        return null;
    }
    void onHide(Client client)
    {
        Player player = client.getGameState() == GameState.LOGGED_IN ? client.getLocalPlayer() : null;
        if (player == null || !matchesScope(player.getWorldView())) { clear(); }
    }
    void captureScope(WorldView view)
    {
        if (matchesScope(view)) { return; }
        clear();
        if (view != null)
        {
            scopedView = view; scopedScene = view.getScene(); scopedBaseX = view.getBaseX(); scopedBaseY = view.getBaseY();
            scopedPlane = view.getPlane(); scopedSizeX = view.getSizeX(); scopedSizeY = view.getSizeY();
        }
    }
    private boolean matchesScope(WorldView view)
    {
        return view != null && scopedView == view && scopedScene == view.getScene() && scopedScene != null
            && scopedBaseX == view.getBaseX() && scopedBaseY == view.getBaseY() && scopedPlane == view.getPlane()
            && scopedSizeX == view.getSizeX() && scopedSizeY == view.getSizeY();
    }
    void clear() { identities.clear(); retired.clear(); scopedView = null; scopedScene = null; }
}
