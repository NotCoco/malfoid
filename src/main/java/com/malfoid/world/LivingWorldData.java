package com.malfoid.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The exact authored Addendum F data. Counts and predicates come from the resource. */
public final class LivingWorldData
{
    public static final String RESOURCE = "/com/malfoid/living-world.json";
    public static final String SHA256 = "e2341361aa78dcf20bac43516a7829b4dddb04107c3fd8d8b1022a9ef3e11477";
    public enum Behaviour { AFRAID, AFRAID_AT_DISTANCE, WARY, DISGUSTED, FOND, LOOKS_THEM_OVER, SPEAKS_ONLY }

    public final List<PlaceGroup> places, placeKinds;
    public final List<CreatureGroup> creatures;
    public final List<SceneryGroup> scenery;
    public final List<Moment> moments;
    public final int lineCount;
    private final Map<Integer, PlaceGroup> regions;
    private final Map<String, Group> groups;
    private final Map<String, SceneryGroup> objectNames;
    private final int kindAreaCount;

    private LivingWorldData(List<PlaceGroup> places, List<PlaceGroup> kinds, List<CreatureGroup> creatures,
        List<SceneryGroup> scenery, List<Moment> moments, Map<Integer, PlaceGroup> regions, Map<String, Group> groups,
        Map<String, SceneryGroup> objectNames, int lineCount, int kindAreaCount)
    {
        this.places = immutable(places); placeKinds = immutable(kinds); this.creatures = immutable(creatures);
        this.scenery = immutable(scenery); this.moments = immutable(moments); this.regions = Collections.unmodifiableMap(regions);
        this.groups = Collections.unmodifiableMap(groups); this.objectNames = Collections.unmodifiableMap(objectNames);
        this.lineCount = lineCount;
        this.kindAreaCount = kindAreaCount;
    }

    public static LivingWorldData load(Class<?> resourceOwner) throws IOException
    {
        try (InputStream input = resourceOwner.getResourceAsStream(RESOURCE))
        {
            if (input == null) { throw new IOException("Missing bundled " + RESOURCE); }
            return parse(input.readAllBytes());
        }
    }

    public static LivingWorldData parse(byte[] bytes) throws IOException { return parse(bytes, SHA256); }

    // A future built pin changes the resource/pin, never the observer or entry-count logic.
    static LivingWorldData parse(byte[] bytes, String builtHash) throws IOException
    {
        if (bytes == null || builtHash == null || !builtHash.equals(sha256(bytes)))
        {
            throw new IOException("Living-world resource hash mismatch");
        }
        try
        {
            JsonObject root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            require(integer(root, "schemaVersion") == 1, "Unsupported living-world schema");
            JsonObject lineRules = root.getAsJsonObject("lineRules");
            int maximumCharacters = integer(lineRules, "maximumCharacters");
            require(maximumCharacters > 0, "Invalid line length rule");
            Set<String> emotions = new HashSet<>(strings(lineRules, "emotions", false));
            require(!emotions.isEmpty(), "Missing line emotions");
            Set<String> lineIds = new HashSet<>();
            Map<String, Group> groups = new LinkedHashMap<>();
            List<PlaceGroup> places = new ArrayList<>(), kinds = new ArrayList<>();
            int kindAreaCount = 0;
            Map<Integer, PlaceGroup> namedRegions = new HashMap<>(), kindRegions = new HashMap<>();
            for (JsonElement element : array(root, "places"))
            {
                JsonObject entry = element.getAsJsonObject();
                PlaceGroup group = new PlaceGroup(id(entry), string(entry, "name"), false,
                    facts(entry), lines(entry, lineIds, emotions, maximumCharacters));
                addGroup(groups, group); places.add(group);
                addRegions(namedRegions, entry, group);
            }
            for (JsonElement element : array(root, "placeKinds"))
            {
                JsonObject entry = element.getAsJsonObject();
                PlaceGroup group = new PlaceGroup(id(entry), null, true,
                    facts(entry), lines(entry, lineIds, emotions, maximumCharacters));
                addGroup(groups, group); kinds.add(group);
                for (JsonElement area : array(entry, "places"))
                {
                    JsonObject place = area.getAsJsonObject(); string(place, "name");
                    addRegions(kindRegions, place, group);
                    kindAreaCount++;
                }
            }
            Map<Integer, PlaceGroup> regions = new HashMap<>(kindRegions);
            regions.putAll(namedRegions); // Authored named places take precedence over kinds.
            Map<String, JsonObject> creatureEntries = new LinkedHashMap<>();
            for (JsonElement element : array(root, "creatures"))
            {
                JsonObject entry = element.getAsJsonObject();
                require(creatureEntries.put(id(entry), entry) == null, "Duplicate creature ID");
            }
            List<CreatureGroup> creatures = new ArrayList<>();
            Set<String> prioritySeen = new HashSet<>();
            for (String key : strings(root, "creaturePriority", false))
            {
                JsonObject entry = creatureEntries.get(key);
                require(entry != null && prioritySeen.add(key), "Invalid creature priority");
                CreatureGroup group = new CreatureGroup(key, creatures.size(),
                    Behaviour.valueOf(string(entry, "behaviour")),
                    lines(entry, lineIds, emotions, maximumCharacters),
                    strings(entry, "equals", true), strings(entry, "startsWith", true),
                    strings(entry, "endsWith", true), strings(entry, "containsWord", true),
                    strings(entry, "neverIfContainsWord", true),
                    entry.has("minimumCombatLevel") ? nonnegative(entry, "minimumCombatLevel") : 0,
                    entry.has("endsWithAtCombatLevel") ? entry.getAsJsonObject("endsWithAtCombatLevel") : null,
                    entry.has("localPlayersFollower") && bool(entry, "localPlayersFollower"));
                addGroup(groups, group); creatures.add(group);
            }
            require(prioritySeen.equals(creatureEntries.keySet()), "Missing creature priority");
            List<SceneryGroup> scenery = new ArrayList<>();
            Map<String, SceneryGroup> objectNames = new HashMap<>();
            for (JsonElement element : array(root, "scenery"))
            {
                JsonObject entry = element.getAsJsonObject();
                SceneryGroup group = new SceneryGroup(id(entry), facts(entry),
                    lines(entry, lineIds, emotions, maximumCharacters), strings(entry, "objectNames", false));
                addGroup(groups, group); scenery.add(group);
                require(!group.objectNames.isEmpty(), "Missing scenery name");
                for (String name : group.objectNames)
                {
                    require(name.equals(normalizeName(name)), "Scenery names must be normalized");
                    require(objectNames.put(name, group) == null, "Ambiguous scenery name");
                }
            }
            List<Moment> moments = new ArrayList<>();
            for (JsonElement element : array(root, "moments"))
            {
                JsonObject entry = element.getAsJsonObject();
                Moment moment = new Moment(id(entry), string(entry, "fact"), facts(entry),
                    lines(entry, lineIds, emotions, maximumCharacters),
                    optionalNonnegative(entry, "playerHpAbovePercent"), optionalNonnegative(entry, "playerHpAtMostPercent"),
                    optionalNonnegative(entry, "timesLatelyAtMost"), optionalNonnegative(entry, "timesLatelyAtLeast"));
                addGroup(groups, moment); moments.add(moment);
            }
            require(lineIds.size() == integer(lineRules, "lineCount"), "Living-world line count mismatch");
            return new LivingWorldData(places, kinds, creatures, scenery, moments, regions, groups, objectNames,
                lineIds.size(), kindAreaCount);
        }
        catch (RuntimeException failure)
        {
            throw new IOException("Invalid living-world resource", failure);
        }
    }

    public PlaceGroup place(int regionId) { return regions.get(regionId); }
    public Group group(String id) { return groups.get(id); }
    public int regionCount() { return regions.size(); }
    public int kindAreaCount() { return kindAreaCount; }
    public SceneryGroup scenery(String rawName) { return objectNames.get(normalizeName(rawName)); }

    /** Used inside the existing single bounded NPC pass; it retains no NPC or name. */
    public CreatureGroup creature(String rawName, int combatLevel)
    {
        String name = normalizeName(rawName);
        if (name.isEmpty()) { return null; }
        for (CreatureGroup group : creatures)
        {
            if (group.matches(name, combatLevel)) { return group; }
        }
        return null;
    }

    public static String normalizeName(String raw)
    {
        if (raw == null) { return ""; }
        return raw.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
    }

    public static boolean containsWord(String name, String word)
    {
        for (int start = name.indexOf(word); start >= 0; start = name.indexOf(word, start + 1))
        {
            int end = start + word.length();
            if ((start == 0 || !wordCharacter(name.charAt(start - 1)))
                && (end == name.length() || !wordCharacter(name.charAt(end)))) { return true; }
        }
        return false;
    }

    private static boolean wordCharacter(char value) { return Character.isLetterOrDigit(value) || value == '_'; }

    public static class Group
    {
        public final String id;
        public final List<String> facts;
        public final List<Line> lines;
        private Group(String id, List<String> facts, List<Line> lines)
        {
            this.id = id; this.facts = immutable(facts); this.lines = immutable(lines);
        }
    }

    public static final class PlaceGroup extends Group
    {
        public final String name;
        public final boolean kind;
        private PlaceGroup(String id, String name, boolean kind, List<String> facts, List<Line> lines)
        {
            super(id, facts, lines); this.name = name; this.kind = kind;
            require(!lines.isEmpty(), "Missing place lines");
        }
    }

    public static final class CreatureGroup extends Group
    {
        public final int ordinal;
        public final Behaviour behaviour;
        public final boolean localPlayersFollower;
        private final List<String> equals, starts, ends, words, vetoes;
        private final int minimumCombatLevel, suffixMinimum;
        private final String combatSuffix;
        private CreatureGroup(String id, int ordinal, Behaviour behaviour, List<Line> lines,
            List<String> equals, List<String> starts, List<String> ends, List<String> words,
            List<String> vetoes, int minimumCombatLevel, JsonObject suffix, boolean localPlayersFollower)
        {
            super(id, Collections.emptyList(), lines); this.ordinal = ordinal; this.behaviour = behaviour;
            this.localPlayersFollower = localPlayersFollower;
            this.equals = immutable(equals); this.starts = immutable(starts); this.ends = immutable(ends);
            this.words = immutable(words); this.vetoes = immutable(vetoes); this.minimumCombatLevel = minimumCombatLevel;
            combatSuffix = suffix == null ? null : string(suffix, "suffix");
            suffixMinimum = suffix == null ? 0 : nonnegative(suffix, "minimumCombatLevel");
            require(combatSuffix == null || combatSuffix.equals(normalizeName(combatSuffix)),
                "Creature suffix must be normalized");
            boolean hasNamePredicate = !equals.isEmpty() || !starts.isEmpty() || !ends.isEmpty()
                || !words.isEmpty() || combatSuffix != null;
            require(localPlayersFollower ? !hasNamePredicate && vetoes.isEmpty() && minimumCombatLevel == 0 : hasNamePredicate,
                "Invalid creature identity or name predicate");
            for (List<String> predicates : java.util.Arrays.asList(equals, starts, ends, words, vetoes))
            {
                for (String predicate : predicates)
                {
                    require(predicate.equals(normalizeName(predicate)), "Creature predicates must be normalized");
                }
            }
        }
        private boolean matches(String name, int combatLevel)
        {
            if (localPlayersFollower) { return false; }
            for (String word : vetoes) { if (containsWord(name, word)) { return false; } }
            if (combatLevel < minimumCombatLevel) { return false; }
            if (equals.contains(name)) { return true; }
            for (String prefix : starts) { if (name.startsWith(prefix)) { return true; } }
            for (String suffix : ends) { if (name.endsWith(suffix)) { return true; } }
            for (String word : words) { if (containsWord(name, word)) { return true; } }
            return combatSuffix != null && combatLevel >= suffixMinimum && name.endsWith(combatSuffix);
        }
    }

    public static final class SceneryGroup extends Group
    {
        public final List<String> objectNames;
        private SceneryGroup(String id, List<String> facts, List<Line> lines, List<String> names)
        {
            super(id, facts, lines); objectNames = immutable(names);
            require(!lines.isEmpty(), "Missing scenery lines");
        }
    }

    public static final class Moment extends Group
    {
        public final String fact, logicalKey;
        public final Integer playerHpAbovePercent, playerHpAtMostPercent, timesLatelyAtMost, timesLatelyAtLeast;
        private Moment(String id, String fact, List<String> facts, List<Line> lines,
            Integer hpAbove, Integer hpAtMost, Integer timesAtMost, Integer timesAtLeast)
        {
            super(id, facts, lines); this.fact = fact;
            playerHpAbovePercent = hpAbove; playerHpAtMostPercent = hpAtMost;
            timesLatelyAtMost = timesAtMost; timesLatelyAtLeast = timesAtLeast;
            require(!facts.isEmpty() && !lines.isEmpty(), "Missing moment facts or lines");
            require("WILDERNESS_ENTERED".equals(fact) || "WILDERNESS_LEFT".equals(fact)
                || "BACK_AFTER_PLAYER_COMBAT".equals(fact) || "KEPT_WAITING".equals(fact)
                || "KEPT_BACK_BY_BEAST".equals(fact), "Unknown moment fact");
            require(hpAbove == null || hpAbove < 100, "Invalid moment HP lower bound");
            require(hpAtMost == null || hpAtMost <= 100, "Invalid moment HP upper bound");
            require(hpAbove == null || hpAtMost == null || hpAbove < hpAtMost, "Empty moment HP range");
            require(timesAtLeast == null || timesAtMost == null || timesAtLeast <= timesAtMost,
                "Empty moment repetition range");
            logicalKey = "W-AFTER-PVP-HURT".equals(id) ? "W-AFTER-PVP"
                : "G-WAITING-LONG".equals(id) ? "G-WAITING" : id;
            require(!"W-AFTER-PVP".equals(logicalKey) || "BACK_AFTER_PLAYER_COMBAT".equals(fact),
                "Invalid after-combat moment alias");
            require(!"G-WAITING".equals(logicalKey) || "KEPT_WAITING".equals(fact),
                "Invalid waiting moment alias");
        }
        public boolean matches(int hpPercent, int timesLately)
        {
            return matchesHp(hpPercent, 100) && matchesTimesLately(timesLately);
        }
        public boolean matchesHp(int currentHp, int realHp)
        {
            if (playerHpAbovePercent == null && playerHpAtMostPercent == null) { return true; }
            return currentHp >= 0 && realHp > 0
                && (playerHpAbovePercent == null || 100L * currentHp > (long) playerHpAbovePercent * realHp)
                && (playerHpAtMostPercent == null || 100L * currentHp <= (long) playerHpAtMostPercent * realHp);
        }
        public boolean matchesTimesLately(int timesLately)
        {
            if (timesLatelyAtMost == null && timesLatelyAtLeast == null) { return true; }
            return timesLately >= 0 && (timesLatelyAtMost == null || timesLately <= timesLatelyAtMost)
                && (timesLatelyAtLeast == null || timesLately >= timesLatelyAtLeast);
        }
    }

    public static final class Line
    {
        public final String id, text, emotion;
        private Line(String id, String text, String emotion) { this.id = id; this.text = text; this.emotion = emotion; }
    }

    private static List<Line> lines(JsonObject entry, Set<String> ids, Set<String> emotions, int maximum)
    {
        List<Line> result = new ArrayList<>();
        for (JsonElement element : array(entry, "lines"))
        {
            JsonObject line = element.getAsJsonObject(); String id = id(line), text = string(line, "text");
            String emotion = string(line, "emotion");
            require(ids.add(id), "Duplicate living-world line ID");
            require(text.codePointCount(0, text.length()) <= maximum && !text.contains("\n") && !text.contains("\r"),
                "Invalid living-world line length");
            require(emotions.contains(emotion), "Unknown living-world emotion");
            result.add(new Line(id, text, emotion));
        }
        return result;
    }
    private static List<String> facts(JsonObject entry) { return strings(entry, "facts", false); }
    private static List<String> strings(JsonObject object, String key, boolean optional)
    {
        if (optional && !object.has(key)) { return Collections.emptyList(); }
        List<String> result = new ArrayList<>();
        for (JsonElement element : array(object, key))
        {
            require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(), "Expected string " + key);
            String value = element.getAsString(); require(!value.isEmpty(), "Empty " + key); result.add(value);
        }
        return result;
    }
    private static JsonArray array(JsonObject object, String key) { return object.getAsJsonArray(key); }
    private static String id(JsonObject object) { return string(object, "id"); }
    private static String string(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected string " + key);
        String text = value.getAsString(); require(!text.isEmpty(), "Empty " + key); return text;
    }
    private static int integer(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected integer " + key);
        return value.getAsBigDecimal().intValueExact();
    }
    private static int nonnegative(JsonObject object, String key)
    {
        int value = integer(object, key); require(value >= 0, "Negative " + key); return value;
    }
    private static Integer optionalNonnegative(JsonObject object, String key)
    {
        return object.has(key) ? nonnegative(object, key) : null;
    }
    private static boolean bool(JsonObject object, String key)
    {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Expected boolean " + key);
        return value.getAsBoolean();
    }
    private static void addGroup(Map<String, Group> groups, Group group)
    {
        require(groups.put(group.id, group) == null, "Duplicate living-world group ID");
    }
    private static void addRegions(Map<Integer, PlaceGroup> regions, JsonObject entry, PlaceGroup group)
    {
        JsonArray ids = array(entry, "regionIds"); require(ids.size() > 0, "Missing place regions");
        for (JsonElement element : ids)
        {
            require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber(), "Invalid region ID");
            int region = element.getAsBigDecimal().intValueExact(); require(region >= 0 && region <= 65535, "Invalid region ID");
            PlaceGroup prior = regions.put(region, group); require(prior == null || prior == group, "Ambiguous place region");
        }
    }
    static String sha256(byte[] bytes)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(64);
            for (byte value : digest) { result.append(String.format(Locale.ROOT, "%02x", value & 255)); }
            return result.toString();
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalArgumentException(message); } }
}
