package com.malfoid.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.speech.SceneCandidate;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class E9CatalogueTest
{
    @Test
    public void pinnedRawSourceAndAllApprovedFieldsAreExactAndEnabled() throws Exception
    {
        System.out.println("E9_TEST_VM java.home=" + System.getProperty("java.home")
            + " java.version=" + System.getProperty("java.version") + " java.vendor=" + System.getProperty("java.vendor"));
        String sourceHash = "52fba7c0eb424ccaf87cf4b4cd5c9a01645eeaf25c1e9f226698d5ec7ae69b2a";
        assertEquals(sourceHash, hash("/com/malfoid/approved-dialogue-e9.json"));
        Map<String, JsonObject> runtime = new HashMap<>();
        for (JsonElement element : json("/com/malfoid/dialogue.json").getAsJsonArray("entries"))
        {
            JsonObject line = element.getAsJsonObject();
            assertNull(runtime.put(line.get("id").getAsString(), line));
        }
        int approved = 0;
        for (JsonElement element : json("/com/malfoid/approved-dialogue-e9.json").getAsJsonArray("entries"))
        {
            JsonObject source = element.getAsJsonObject();
            JsonObject adapted = runtime.get(source.get("id").getAsString());
            assertNotNull(adapted);
            for (String field : new String[] {"id", "scenario", "text", "emotion", "conditions", "authorship"})
            {
                assertEquals(source.get("id").getAsString() + " " + field, source.get(field), adapted.get(field));
            }
            assertEquals("enabled", adapted.get("status").getAsString());
            approved++;
        }
        assertEquals(140, approved);
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        for (JsonElement element : json("/com/malfoid/approved-dialogue-e9.json").getAsJsonArray("entries"))
        {
            JsonObject source = element.getAsJsonObject();
            assertEquals(source.get("emotion").getAsString(), catalogue.getLine(source.get("id").getAsString()).getEmotion());
        }
        assertEquals(500, runtime.size());
    }

    @Test
    public void allSixTalkPoolSizesIncludeTheOldSeventeen() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        Map<String, Integer> counts = new HashMap<>();
        for (DialogueLine line : catalogue.getLines("U01"))
        {
            assertTrue(line.getId(), line.isEnabled());
            Map<String, String> conditions = line.getConditions();
            String group = conditions.get("talkStreak");
            for (String conditional : new String[] {"combatConfirmed", "playerHpAtMostPercent", "localNight"})
            {
                if (conditions.containsKey(conditional)) { group = conditional; break; }
            }
            counts.merge(group, 1, Integer::sum);
        }
        assertEquals(Integer.valueOf(59), counts.get("1"));
        assertEquals(Integer.valueOf(17), counts.get("2"));
        assertEquals(Integer.valueOf(14), counts.get("3"));
        assertEquals(Integer.valueOf(13), counts.get("playerHpAtMostPercent"));
        assertEquals(Integer.valueOf(10), counts.get("combatConfirmed"));
        assertEquals(Integer.valueOf(14), counts.get("localNight"));
        for (int index = 1; index <= 17; index++) { assertTrue(catalogue.getLine(String.format("U01-%02d", index)).isEnabled()); }
    }

    @Test
    public void bothConditionsAreRequiredForConditionalTalkLines() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        for (String id : new String[] {"U01-H01", "U01-F01", "U01-N01"})
        {
            DialogueLine line = catalogue.getLine(id);
            Map<String, String> facts = new HashMap<>();
            facts.put("talkStreak", "1");
            assertFalse(line.matches(facts));
            facts.put("playerHpAtMostPercent", "25");
            facts.put("combatConfirmed", "true");
            facts.put("localNight", "true");
            assertTrue(line.matches(facts));
            facts.put("talkStreak", "2");
            assertFalse(line.matches(facts));
        }
    }

    @Test
    public void fearPoolsUseIndependentMomentKeysAndProducerFactsWithoutTextConditions() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        for (String scenario : new String[] {"F01", "F02"})
        {
            assertTrue(catalogue.isRuntimeScenarioEnabled(scenario));
            assertEquals(scenario, catalogue.repeatKey(scenario));
            assertFalse(catalogue.isSituation(scenario));
            assertEquals(0, catalogue.getSilencePercent(scenario));
            assertEquals(6, catalogue.getLines(scenario).size());
            Map<String, String> facts = new HashMap<>();
            facts.put("fearBeginTick", "50");
            facts.put("creatureKey", "7");
            facts.put("creatureGroup", scenario);
            facts.put("creatureX", "3200");
            facts.put("creatureY", "3200");
            facts.put("creaturePlane", "0");
            facts.put("creatureWorldViewId", "-1");
            facts.put("witnessPresenceEpoch", "1");
            SceneCandidate candidate = new SceneCandidate(1, scenario, 30, 50, 62, 1, 1, "fear:1", facts, false, 4);
            assertTrue(catalogue.hasValidOriginalContext(candidate));
            assertNull(catalogue.situationFingerprint(candidate));
            for (DialogueLine line : catalogue.getLines(scenario))
            {
                assertTrue(line.isEnabled());
                assertTrue(line.getConditions().isEmpty());
                assertTrue(line.matches(facts));
            }
        }
    }

    @Test
    public void intentionalHmphDuplicateSharesItsTextGuardKey() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        assertEquals("Hmph.", catalogue.getLine("U01-G28").getText());
        assertEquals(catalogue.getLine("M09-H01").getNormalizedText(), catalogue.getLine("U01-G28").getNormalizedText());
        assertEquals(772, catalogue.getMaximumTextKeys());
        assertEquals(144, catalogue.getMaximumInteractionKeys());
    }

    private JsonObject json(String path) throws Exception
    {
        try (InputStream input = getClass().getResourceAsStream(path))
        {
            assertNotNull(input);
            return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private String hash(String path) throws Exception
    {
        try (InputStream input = getClass().getResourceAsStream(path))
        {
            assertNotNull(input);
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))
            {
                hex.append(String.format("%02x", value & 255));
            }
            return hex.toString();
        }
    }
}
