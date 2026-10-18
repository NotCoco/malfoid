package com.malfoid.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class LivingWorldDataTest
{
    @Test public void bundledAuthoredBytesAndAllCoverageCountsAreExact() throws Exception
    {
        byte[] bytes = resourceBytes();
        assertEquals("e2341361aa78dcf20bac43516a7829b4dddb04107c3fd8d8b1022a9ef3e11477",
            LivingWorldData.sha256(bytes));
        LivingWorldData data = LivingWorldData.load(LivingWorldDataTest.class);
        assertEquals(40, data.places.size());
        assertEquals(15, data.placeKinds.size());
        assertEquals(127, data.kindAreaCount());
        assertEquals(445, data.regionCount());
        assertEquals(27, data.creatures.size());
        assertEquals(21, data.scenery.size());
        assertEquals(446, data.lineCount);
        assertEquals(Collections.emptyList(), data.group("F01").lines);
        assertEquals(Collections.emptyList(), data.group("F02").lines);
        assertEquals(LivingWorldData.Behaviour.AFRAID, data.creature("Rat", 1).behaviour);
        assertEquals(LivingWorldData.Behaviour.AFRAID, data.creature("Black bear", 1).behaviour);
        assertEquals(LivingWorldData.Behaviour.WARY, data.creature("Ghost", 1).behaviour);
    }

    @Test public void changedAuthoredBytesAreRefusedEvenIfStillValidJson() throws Exception
    {
        byte[] original = resourceBytes();
        byte[] changed = Arrays.copyOf(original, original.length + 1);
        changed[changed.length - 1] = '\n';
        assertRefused(changed, LivingWorldData.SHA256);
        assertRefused(original, "0000000000000000000000000000000000000000000000000000000000000000");
    }

    @Test public void namedPlacesAndTypeFallbackUseAuthoredRegions() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(LivingWorldDataTest.class);
        assertEquals("PL-LUMBRIDGE", data.place(12850).id);
        assertEquals("PL-VARROCK", data.place(13110).id);
        assertFalse(data.place(12850).kind);
        assertEquals("PT-DESERT", data.place(13358).id);
        assertEquals("PT-DESERT", data.place(13613).id);
        assertTrue(data.place(13358).kind);
        assertNull(data.place(65535));
        assertNull(data.place(-1));
    }

    @Test public void aNamedPlaceWinsWhenARepinnedTypeAlsoContainsItsRegion() throws Exception
    {
        JsonObject root = resourceJson();
        root.getAsJsonArray("placeKinds").get(0).getAsJsonObject().getAsJsonArray("places")
            .get(0).getAsJsonObject().getAsJsonArray("regionIds").add(12850);
        LivingWorldData data = repinned(root);
        assertEquals("PL-LUMBRIDGE", data.place(12850).id);
        assertEquals("PT-DESERT", data.place(13358).id);
    }

    @Test public void everyDataCategoryCanGrowWithOnlyAResourceAndBuiltPinUpdate() throws Exception
    {
        JsonObject root = resourceJson();
        JsonObject named = root.getAsJsonArray("places").get(0).getAsJsonObject().deepCopy();
        named.addProperty("id", "PL-FUTURE");
        named.addProperty("name", "Future place");
        named.add("regionIds", regions(65530));
        renameLines(named, "PL-FUTURE");
        root.getAsJsonArray("places").add(named);

        JsonObject kind = root.getAsJsonArray("placeKinds").get(0).getAsJsonObject().deepCopy();
        kind.addProperty("id", "PT-FUTURE");
        renameLines(kind, "PT-FUTURE");
        JsonObject area = new JsonObject();
        area.addProperty("name", "Future area");
        area.add("regionIds", regions(65531));
        JsonArray areas = new JsonArray(); areas.add(area); kind.add("places", areas);
        root.getAsJsonArray("placeKinds").add(kind);

        JsonObject creature = new JsonObject();
        creature.addProperty("id", "CR-FUTURE");
        creature.addProperty("behaviour", "SPEAKS_ONLY");
        JsonArray equals = new JsonArray(); equals.add("future creature"); creature.add("equals", equals);
        creature.add("lines", new JsonArray());
        root.getAsJsonArray("creatures").add(creature);
        root.getAsJsonArray("creaturePriority").add("CR-FUTURE");

        JsonObject scenery = root.getAsJsonArray("scenery").get(0).getAsJsonObject().deepCopy();
        scenery.addProperty("id", "SC-FUTURE");
        renameLines(scenery, "SC-FUTURE");
        JsonArray names = new JsonArray(); names.add("future object"); scenery.add("objectNames", names);
        root.getAsJsonArray("scenery").add(scenery);
        int addedLines = named.getAsJsonArray("lines").size() + kind.getAsJsonArray("lines").size()
            + scenery.getAsJsonArray("lines").size();
        root.getAsJsonObject("lineRules").addProperty("lineCount", 446 + addedLines);

        LivingWorldData data = repinned(root);
        assertEquals(41, data.places.size()); assertEquals(16, data.placeKinds.size());
        assertEquals(128, data.kindAreaCount()); assertEquals(447, data.regionCount());
        assertEquals(28, data.creatures.size()); assertEquals(22, data.scenery.size());
        assertEquals(446 + addedLines, data.lineCount);
        assertEquals("PL-FUTURE", data.place(65530).id);
        assertEquals("PT-FUTURE", data.place(65531).id);
        assertEquals("CR-FUTURE", data.creature("Future creature", 0).id);
        assertEquals("SC-FUTURE", data.scenery("Future object").id);
    }

    @Test public void creaturePriorityAndEveryPredicateComeFromTheData() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(LivingWorldDataTest.class);
        String[] priority = {"F01", "CR-DRAGON", "CR-DEMON", "F02", "CR-UNDEAD", "CR-GIANT",
            "CR-GAZE", "CR-GARGOYLE", "CR-ELEMENTAL", "CR-GOLEM", "CR-ENT",
            "CR-DISGUST", "CR-UNICORN", "CR-FAIRY", "CR-FOND", "CR-PET", "CR-WIZARD",
            "CR-ELF", "CR-TZHAAR", "CR-LEPRECHAUN", "CR-CHINCHOMPA", "CR-MONKEY", "CR-IMP", "CR-GOBLIN",
            "CR-GUARD", "CR-FARM", "CR-SEAGULL"};
        for (int i = 0; i < priority.length; i++)
        {
            assertEquals(priority[i], data.creatures.get(i).id);
            assertEquals(i, data.creatures.get(i).ordinal);
        }
        assertCreature(data, "<col=ff0000>Giant RAT</col>", 6, "F01");
        assertCreature(data, "Crypt-rat", 1, "F01");
        assertCreature(data, "Dragon rat", 90, "F01");
        assertCreature(data, "Green dragon", 40, "CR-DRAGON");
        assertNull(data.creature("Green dragon", 39));
        assertNull(data.creature("Dragonfly", 90));
        assertCreature(data, "Wolf", 1, "F02");
        assertCreature(data, "Werewolf", 1, "F02");
        assertCreature(data, "Grizzly bear", 1, "F02");
        assertCreature(data, "Hellhound", 1, "F02");
        assertCreature(data, "Giant spider", 2, "F02");
        assertCreature(data, "Spider", 1, "F02");
        assertCreature(data, "Guard dog", 44, "F02");
        assertCreature(data, "Sabre-toothed kyatt", 1, "F02");
        assertNull(data.creature("Jaguar warrior", 100));
        assertNull(data.creature("Bear cub", 1));
        assertCreature(data, "Zombie goblin", 1, "CR-UNDEAD");
        assertCreature(data, "Rockslug", 1, "CR-DISGUST");
        assertCreature(data, "Kitten", 0, "CR-FOND");
        assertNull(data.creature("Black cat", 0));
        assertNull(data.creature("Evil cat", 0));
        assertCreature(data, "Blackberry cat", 0, "CR-FOND");
        assertCreature(data, "Wizard Cromperty", 0, "CR-WIZARD");
        assertCreature(data, "Goblin guard", 0, "CR-GOBLIN");
        assertNull(data.creature("Cave goblin", 0));
        assertNull(data.creature(null, 0));
    }

    @Test public void repinningChangedPredicatesChangesRecognitionWithoutObserverCode() throws Exception
    {
        JsonObject root = resourceJson();
        JsonObject rats = root.getAsJsonArray("creatures").get(0).getAsJsonObject();
        JsonArray names = new JsonArray(); names.add("new rat species"); rats.add("equals", names);
        rats.add("endsWith", new JsonArray());
        LivingWorldData data = repinned(root);
        assertNull(data.creature("rat", 1));
        assertEquals("F01", data.creature("New rat species", 1).id);
    }

    @Test public void sceneryRequiresTheWholeNormalizedAuthoredName() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(LivingWorldDataTest.class);
        LivingWorldData.SceneryGroup bookcase = data.scenery("Bookcase");
        assertNotNull(bookcase);
        assertSame(bookcase, data.scenery("<col=fff>BOOKCASE</col>"));
        assertNull(data.scenery("Bookcase (empty)"));
        assertNull(data.scenery("Bookcases"));
        assertNull(data.scenery(null));
    }

    @Test public void exposedDataCollectionsCannotBeMutated() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(LivingWorldDataTest.class);
        try { data.places.clear(); fail("Mutable places"); }
        catch (UnsupportedOperationException expected) { }
        try { data.place(12850).lines.clear(); fail("Mutable lines"); }
        catch (UnsupportedOperationException expected) { }
        try { data.place(12850).facts.clear(); fail("Mutable facts"); }
        catch (UnsupportedOperationException expected) { }
        try { data.scenery("Bookcase").objectNames.clear(); fail("Mutable scenery names"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void repinningDoesNotBypassSchemaOrAuthoredConsistencyChecks() throws Exception
    {
        JsonObject root = resourceJson(); root.addProperty("schemaVersion", 2);
        assertRepinnedRefused(root);
        root = resourceJson(); root.getAsJsonObject("lineRules").addProperty("lineCount", 445);
        assertRepinnedRefused(root);
        root = resourceJson(); root.getAsJsonArray("creaturePriority").remove(0);
        assertRepinnedRefused(root);
        root = resourceJson(); root.getAsJsonArray("places").get(0).getAsJsonObject()
            .getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("emotion", "UNSUPPORTED");
        assertRepinnedRefused(root);
        root = resourceJson(); root.getAsJsonArray("places").get(0).getAsJsonObject()
            .getAsJsonArray("regionIds").add(1.5);
        assertRepinnedRefused(root);
        byte[] malformed = "{bad json".getBytes(StandardCharsets.UTF_8);
        assertRefused(malformed, LivingWorldData.sha256(malformed));
    }

    @Test public void exactR4ResourceLoadsEveryGroupMomentAndLine() throws Exception
    {
        byte[] bytes = resourceBytes();
        assertEquals("e2341361aa78dcf20bac43516a7829b4dddb04107c3fd8d8b1022a9ef3e11477", LivingWorldData.sha256(bytes));
        LivingWorldData data = LivingWorldData.parse(bytes);
        assertEquals(40, data.places.size()); assertEquals(15, data.placeKinds.size());
        assertEquals(127, data.kindAreaCount()); assertEquals(27, data.creatures.size());
        assertEquals(21, data.scenery.size()); assertEquals(7, data.moments.size()); assertEquals(446, data.lineCount);
        JsonObject root = resourceJson();
        int checked = 0;
        for (String family : new String[] {"places", "placeKinds", "creatures", "scenery", "moments"})
        {
            for (JsonElement element : root.getAsJsonArray(family))
            {
                JsonObject authored = element.getAsJsonObject();
                LivingWorldData.Group group = data.group(authored.get("id").getAsString());
                assertNotNull(group); assertEquals(authored.getAsJsonArray("lines").size(), group.lines.size());
                int index = 0;
                for (JsonElement lineElement : authored.getAsJsonArray("lines"))
                {
                    JsonObject line = lineElement.getAsJsonObject(); LivingWorldData.Line loaded = group.lines.get(index++);
                    assertEquals(line.get("id").getAsString(), loaded.id);
                    assertEquals(line.get("text").getAsString(), loaded.text);
                    assertEquals(line.get("emotion").getAsString(), loaded.emotion); checked++;
                }
            }
        }
        assertEquals(446, checked);
        try { data.moments.clear(); fail("Mutable moment list"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void newNameGroupsRetainWholeWordSuffixVetoAndCombatRules() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(getClass());
        assertNull(data.creature("green dragon", 39)); assertEquals("CR-DRAGON", data.creature("green dragon", 40).id);
        assertNull(data.creature("lesser demon", 49)); assertEquals("CR-DEMON", data.creature("<col=ff0000>Lesser demon</col>", 50).id);
        assertNull(data.creature("demonic gorilla", 100)); assertNull(data.creature("hill giant", 19));
        assertEquals("CR-GIANT", data.creature("hill giant", 20).id);
        for (String name : new String[] {"ghoul", "mummy", "banshee", "revenant", "wraith", "spectre", "vampyre"})
            assertEquals(name, "CR-UNDEAD", data.creature(name, 0).id);
        for (String name : new String[] {"scorpion", "king scorpion", "snake", "swamp snake", "crocodile", "bat", "giant bat"})
            assertEquals(name, "F02", data.creature(name, 0).id);
        assertEquals("F02", data.creature("giant spider", 2).id); assertEquals("F02", data.creature("spider", 1).id);
        assertEquals("CR-MONKEY", data.creature("monkey", 0).id); assertNull(data.creature("ninja monkey", 0));
        assertEquals("CR-GUARD", data.creature("monkey guard", 0).id);
        assertEquals("CR-UNDEAD", data.creature("zombie monkey", 0).id);
        assertEquals("CR-IMP", data.creature("imp", 0).id); assertNull(data.creature("impostor", 0));
        assertEquals("CR-FARM", data.creature("dairy cow", 0).id); assertEquals("CR-SEAGULL", data.creature("gull", 0).id);
        assertNull(data.creature("black cat", 0)); assertNull(data.creature("cave goblin", 0));
        assertNull(data.creature("goblinish", 0)); assertNull(data.creature("batman", 0));
    }

    @Test public void localFollowerIsAnIdentityExceptionAndNeverANameMatch() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(getClass());
        LivingWorldData.CreatureGroup pet = (LivingWorldData.CreatureGroup) data.group("CR-PET");
        assertTrue(pet.localPlayersFollower); assertEquals(LivingWorldData.Behaviour.LOOKS_THEM_OVER, pet.behaviour);
        for (LivingWorldData.CreatureGroup creature : data.creatures)
            assertEquals(creature.id, "CR-PET".equals(creature.id), creature.localPlayersFollower);
        for (String name : new String[] {"pet", "follower", "CR-PET", "Pet dark core", "Pet snakeling", "Cat"})
        {
            LivingWorldData.CreatureGroup matched = data.creature(name, 100);
            assertTrue(name, matched == null || !"CR-PET".equals(matched.id));
        }
        assertEquals("CR-FOND", data.creature("cat", 0).id);
        assertInvalidMutation(root -> entry(root, "creatures", "CR-PET").addProperty("localPlayersFollower", "true"));
        assertInvalidMutation(root -> entry(root, "creatures", "CR-PET").addProperty("localPlayersFollower", false));
        assertInvalidMutation(root -> entry(root, "creatures", "CR-PET").add("equals", new JsonParser().parse("[\"pet\"]")));
    }

    @Test public void momentsUseExactHpAndRepetitionBoundariesAndSharedLogicalKeys() throws Exception
    {
        LivingWorldData data = LivingWorldData.load(getClass());
        LivingWorldData.Moment normal = (LivingWorldData.Moment) data.group("W-AFTER-PVP");
        LivingWorldData.Moment hurt = (LivingWorldData.Moment) data.group("W-AFTER-PVP-HURT");
        assertEquals("BACK_AFTER_PLAYER_COMBAT", normal.fact); assertEquals(normal.logicalKey, hurt.logicalKey);
        assertFalse(normal.matches(50, 0)); assertTrue(hurt.matches(50, 0));
        assertTrue(normal.matches(51, 0)); assertFalse(hurt.matches(51, 0));
        assertTrue(normal.matchesHp(51, 101)); assertFalse(hurt.matchesHp(51, 101));
        assertFalse(normal.matchesHp(50, 101)); assertTrue(hurt.matchesHp(50, 101));
        assertFalse(normal.matchesHp(1, 0)); assertFalse(hurt.matchesHp(-1, 100));
        assertTrue(normal.matchesHp(Integer.MAX_VALUE, Integer.MAX_VALUE));
        LivingWorldData.Moment waiting = (LivingWorldData.Moment) data.group("G-WAITING");
        LivingWorldData.Moment longer = (LivingWorldData.Moment) data.group("G-WAITING-LONG");
        assertEquals("KEPT_WAITING", waiting.fact); assertEquals(waiting.logicalKey, longer.logicalKey);
        assertTrue(waiting.matches(0, 2)); assertFalse(longer.matches(0, 2));
        assertFalse(waiting.matches(0, 3)); assertTrue(longer.matches(0, 3));
        assertFalse(waiting.matchesTimesLately(-1));
        assertTrue(((LivingWorldData.Moment) data.group("W-WILD-ENTER")).matches(-1, -1));
    }

    @Test public void malformedMomentFactsBoundsAndAliasesAreRejected() throws Exception
    {
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").remove("fact"));
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").addProperty("fact", "UNSUPPORTED"));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP").addProperty("playerHpAbovePercent", -1));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP").addProperty("playerHpAbovePercent", 100));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP-HURT").addProperty("playerHpAtMostPercent", 101));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP-HURT").addProperty("playerHpAtMostPercent", 50.5));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP").addProperty("playerHpAtMostPercent", 50));
        assertInvalidMutation(root -> entry(root, "moments", "G-WAITING-LONG").addProperty("timesLatelyAtMost", 2));
        assertInvalidMutation(root -> entry(root, "moments", "G-WAITING").addProperty("timesLatelyAtMost", -1));
        assertInvalidMutation(root -> entry(root, "moments", "W-AFTER-PVP-HURT").addProperty("fact", "KEPT_WAITING"));
        assertInvalidMutation(root -> entry(root, "moments", "G-WAITING-LONG").addProperty("fact", "WILDERNESS_LEFT"));
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").getAsJsonArray("lines").remove(0));
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").add("facts", new JsonParser().parse("[]")));
    }

    @Test public void parserRejectsUnpinnedBytesAndCrossFamilyDuplicateIds() throws Exception
    {
        byte[] bytes = resourceBytes(); bytes[0] = ' ';
        try { LivingWorldData.parse(bytes); fail("Unpinned bytes"); } catch (IOException expected) { }
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").addProperty("id", "PL-LUMBRIDGE"));
        assertInvalidMutation(root -> entry(root, "moments", "W-WILD-ENTER").getAsJsonArray("lines").get(0).getAsJsonObject()
            .addProperty("id", "PL-LUMBRIDGE-01"));
    }

    private static JsonObject entry(JsonObject root, String family, String id)
    {
        for (JsonElement element : root.getAsJsonArray(family))
            if (id.equals(element.getAsJsonObject().get("id").getAsString())) { return element.getAsJsonObject(); }
        throw new AssertionError(id);
    }
    private static void assertInvalidMutation(Consumer<JsonObject> edit) throws IOException
    {
        JsonObject root = resourceJson(); edit.accept(root); assertRepinnedRefused(root);
    }

    static byte[] resourceBytes() throws IOException
    {
        try (InputStream input = LivingWorldDataTest.class.getResourceAsStream(LivingWorldData.RESOURCE))
        {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    static JsonObject resourceJson() throws IOException
    {
        return new JsonParser().parse(new String(resourceBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static LivingWorldData repinned(JsonObject root) throws IOException
    {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        return LivingWorldData.parse(bytes, LivingWorldData.sha256(bytes));
    }

    private static JsonArray regions(int region)
    {
        JsonArray result = new JsonArray(); result.add(region); return result;
    }

    private static void renameLines(JsonObject entry, String prefix)
    {
        JsonArray lines = entry.getAsJsonArray("lines");
        for (int i = 0; i < lines.size(); i++) { lines.get(i).getAsJsonObject().addProperty("id", prefix + "-" + i); }
    }

    private static void assertCreature(LivingWorldData data, String name, int level, String expected)
    {
        LivingWorldData.CreatureGroup actual = data.creature(name, level);
        assertNotNull(name, actual); assertEquals(name, expected, actual.id);
    }

    private static void assertRepinnedRefused(JsonObject root) throws IOException
    {
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        assertRefused(bytes, LivingWorldData.sha256(bytes));
    }

    private static void assertRefused(byte[] bytes, String builtHash) throws IOException
    {
        try { LivingWorldData.parse(bytes, builtHash); fail("Resource accepted"); }
        catch (IOException expected) { }
    }
}
