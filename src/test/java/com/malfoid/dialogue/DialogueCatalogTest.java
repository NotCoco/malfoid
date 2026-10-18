package com.malfoid.dialogue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DialogueCatalogTest
{
    @Test
    public void packagedResourcesValidateAndRetainCoreLineGates() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        assertTrue(catalogue.size() >= 351);
        assertEquals(4, catalogue.getApprovedExamineTexts().size());
        assertNotNull(catalogue.getLocalText("N01"));
        for (String id : new String[] {"M04-04", "M05-01", "M05-02", "M05-03", "M05-04", "M05-05", "M06-03", "M08-01", "M10-04", "M47-G01", "M47-G02", "M47-G03", "M47-G04", "M47-G05", "M59-G04"})
        {
            assertFalse(id, catalogue.getLine(id).isEnabled());
        }
        assertEquals(500, catalogue.size());
    }

    @Test
    public void normalizationUsesNfcRootLowercaseAndUnicodeWhitespace()
    {
        assertEquals("café hello", DialogueCatalog.normalizeText("  CAFE\u0301\u00a0\u2003HELLO\t "));
        assertEquals("i", DialogueCatalog.normalizeText("I"));
    }

    @Test
    public void aliasesAndTypedUnknownSubjectCannotBypassPolicy() throws Exception
    {
        DialogueCatalog catalogue = DialogueCatalog.load(getClass());
        assertEquals(catalogue.repeatKey("M04"), catalogue.repeatKey("M48"));
        assertEquals(catalogue.repeatKey("M09"), catalogue.repeatKey("M47"));
        assertEquals(100, catalogue.getSilencePercent("M47"));
        assertEquals(catalogue.repeatKey("M03"), catalogue.repeatKey("M50"));
        assertNull(catalogue.repeatKey("M64"));
        assertNull(catalogue.situationFingerprint("M01", Collections.emptyMap()));
        Map<String, String> facts = new HashMap<>();
        facts.put("equipmentIds", "-1,100,200,300");
        assertEquals(catalogue.situationFingerprint("M09", facts), catalogue.situationFingerprint("M31", facts));
        assertFalse(catalogue.getLine("M04-01").matches(Collections.emptyMap()));
        facts.put("newLevel", "99");
        assertTrue(catalogue.getLine("M04-01").matches(facts));
        facts.put("equipmentIds", "-1,0100,200,300");
        Map<String, String> canonical = new HashMap<>();
        canonical.put("equipmentIds", "-1,100,200,300");
        assertEquals(catalogue.situationFingerprint("M09", canonical), catalogue.situationFingerprint("M09", facts));
        facts.put("equipmentIds", "100,200");
        assertNull(catalogue.situationFingerprint("M09", facts));
    }

    @Test(expected = IOException.class)
    public void unsupportedConditionsFailValidation() throws Exception
    {
        JsonObject runtime = readJson("/com/malfoid/dialogue.json");
        runtime.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("conditions").addProperty("script", "anything");
        DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test(expected = IOException.class)
    public void enablingExcludedEntryFailsValidation() throws Exception
    {
        JsonObject runtime = readJson("/com/malfoid/dialogue.json");
        for (com.google.gson.JsonElement element : runtime.getAsJsonArray("entries"))
        {
            JsonObject entry = element.getAsJsonObject();
            if ("M04-04".equals(entry.get("id").getAsString())) { entry.addProperty("status", "enabled"); }
        }
        DialogueCatalog.read(new ByteArrayInputStream(runtime.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private JsonObject readJson(String path) throws IOException
    {
        try (InputStream input = getClass().getResourceAsStream(path))
        {
            assertNotNull(input);
            return new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
