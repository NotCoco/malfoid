package com.malfoid.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.malfoid.speech.SceneCandidate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnerDialogueCatalogTest
{
    @Test
    public void enabledOutfitPoolContainsOnlyReviewedOwnerAndRetainedLines() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        Set<String> enabled = new HashSet<>();
        for (DialogueLine line : catalogue.getLines("M09"))
        {
            if (line.isEnabled()) { enabled.add(line.getId()); }
        }
        assertEquals(new HashSet<>(Arrays.asList("M09-H01", "M09-H02", "M09-05", "M09-T01", "M09-T02", "M09-T03")), enabled);
        assertEquals("Hmph.", catalogue.getLine("M09-H01").getText());
        assertEquals("...Hmph.", catalogue.getLine("M09-H02").getText());
        assertTrue(catalogue.getLine("M33-G04").isEnabled());
        assertTrue(catalogue.getLine("M33-G05").isEnabled());
        for (String scenario : new String[] {"M09", "M32", "M33", "M34"})
        {
            for (DialogueLine line : catalogue.getLines(scenario))
            {
                if (!line.isEnabled()) { continue; }
                for (String word : forbiddenWords())
                {
                    assertFalse(line.getId(), line.getNormalizedText().contains(word));
                }
            }
        }
    }

    @Test
    public void reenabledWithdrawnOutfitEntriesFailClosed() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        for (String scenario : new String[] {"M09", "M32", "M33", "M34"})
        {
            for (DialogueLine line : catalogue.getLines(scenario))
            {
                if (line.isEnabled()) { continue; }
                JsonObject runtime = runtime();
                entry(runtime, line.getId()).addProperty("status", "enabled");
                assertRejected(runtime);
            }
        }
    }

    @Test
    public void withdrawnWordsRejectCaseAndPluralSubstringsInEveryOutfitScenario() throws Exception
    {
        for (String scenario : new String[] {"M09", "M32", "M33", "M34"})
        {
            for (String word : forbiddenWords())
            {
                JsonObject runtime = runtime();
                JsonObject line = entry(runtime, "M09-05");
                line.addProperty("scenario", scenario);
                line.addProperty("text", "Those " + word.toUpperCase(java.util.Locale.ROOT) + "s.");
                assertRejected(runtime);
            }
        }
    }

    @Test
    public void unreviewedM09LineCannotJoinTheWeightedPool() throws Exception
    {
        JsonObject runtime = runtime();
        entry(runtime, "M09-05").addProperty("id", "M09-NEW");
        assertRejected(runtime);
    }

    @Test
    public void ownerLineIdsRequireTheirExactTextAndScenarioEvenWhenDisabled() throws Exception
    {
        for (String id : new String[] {"M09-H01", "M09-H02", "F03-01"})
        {
            for (String status : new String[] {"enabled", "disabled"})
            {
                JsonObject changedText = runtime();
                JsonObject line = entry(changedText, id);
                line.addProperty("status", status);
                line.addProperty("text", line.get("text").getAsString() + " ");
                assertRejected(changedText);
                JsonObject changedScenario = runtime();
                line = entry(changedScenario, id);
                line.addProperty("status", status);
                line.addProperty("scenario", "M01");
                assertRejected(changedScenario);
            }
        }
    }

    @Test
    public void longRunLineUsesProducerFactsAndMomentGuardWithoutAnotherProbabilityDraw() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        assertTrue(catalogue.isRuntimeScenarioEnabled("F03"));
        assertEquals("F03", catalogue.repeatKey("F03"));
        assertFalse(catalogue.isSituation("F03"));
        assertEquals(0, catalogue.getSilencePercent("F03"));
        DialogueLine line = catalogue.getLine("F03-01");
        assertTrue(line.isEnabled());
        assertEquals("Where are you taking me, Potter?!", line.getText());
        assertTrue(line.getConditions().isEmpty());
        Map<String, String> facts = new HashMap<>();
        facts.put("runWindowStartTick", "1");
        facts.put("runWindowTiles", "60");
        facts.put("runWorldViewId", "-1");
        facts.put("witnessPresenceEpoch", "1");
        SceneCandidate candidate = new SceneCandidate(1, "F03", 30, 50, 62, 1, 1,
            "run:1", facts, false, 4);
        assertTrue(line.matches(facts));
        assertTrue(catalogue.hasValidOriginalContext(candidate));
        assertNull(catalogue.situationFingerprint(candidate));
        JsonObject binding = runtime().getAsJsonObject("situationSpeechPolicy")
            .getAsJsonObject("byLogicalKey").getAsJsonObject("F03");
        assertEquals("MOMENT", binding.get("repeatClass").getAsString());
        assertEquals("NOT_APPLICABLE", binding.getAsJsonObject("subjectBinding").get("type").getAsString());
    }

    @Test
    public void producerRunFactsCannotBecomeCatalogueTextConditions() throws Exception
    {
        for (String fact : new String[] {"runWindowStartTick", "runWindowTiles", "runWorldViewId", "witnessPresenceEpoch"})
        {
            JsonObject runtime = runtime();
            entry(runtime, "F03-01").getAsJsonObject("conditions").addProperty(fact, "1");
            assertRejected(runtime);
        }
    }

    private byte[] resourceBytes(String path) throws IOException
    {
        try (InputStream input = getClass().getResourceAsStream(path))
        {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception
    {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
        {
            hex.append(String.format("%02x", value & 255));
        }
        return hex.toString();
    }

    private static String[] forbiddenWords()
    {
        return new String[] {"ensemble", "attire", "headpiece", "garment", "wardrobe", "overhaul", "triumphant"};
    }

    private JsonObject runtime() throws IOException { return readJson("/com/malfoid/dialogue.json"); }

    private JsonObject readJson(String path) throws IOException
    {
        try (InputStream input = getClass().getResourceAsStream(path))
        {
            assertNotNull(input);
            return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static JsonObject entry(JsonObject runtime, String id)
    {
        for (JsonElement element : runtime.getAsJsonArray("entries"))
        {
            JsonObject line = element.getAsJsonObject();
            if (id.equals(line.get("id").getAsString())) { return line; }
        }
        throw new AssertionError("Missing line " + id);
    }

    private static void assertRejected(JsonObject runtime) throws Exception
    {
        try
        {
            DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
            fail("Invalid dialogue loaded");
        }
        catch (IOException expected) { }
    }
}
