package com.malfoid.asset;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeEmoteAssetTest
{
    @Test public void exactlyThreeFiveOrTenKnownClipsLoad()
    {
        JsonObject ten = bake();
        assertEquals(10, decode(ten).clips.size());
        assertEquals(204, decode(ten).clips.values().stream().mapToInt(clip -> clip.samples.length).sum());
        JsonObject five = withoutGestures(ten);
        assertEquals(5, decode(five).clips.size());
        JsonObject three = withoutEmotes(five);
        three.addProperty("assetRevision", "claude07");
        NativeAsset old = decode(three);
        assertEquals(3, old.clips.size());
        assertFalse(old.clips.containsKey("CROSS_ARMS")); assertFalse(old.clips.containsKey("IMPATIENT"));
        assertEquals(100, old.clips.values().stream().mapToInt(clip -> clip.samples.length).sum());
        assertEquals(130, decode(five).clips.values().stream().mapToInt(clip -> clip.samples.length).sum());
    }

    @Test public void missingPartialAndAdditionalEmoteSetsAreRejected()
    {
        JsonObject four = bake(); four.getAsJsonObject("clips").remove("IMPATIENT"); rejected(four, "clip set");
        JsonObject six = bake(); six.getAsJsonObject("clips").add("EXTRA", six.getAsJsonObject("clips").get("IDLE"));
        rejected(six, "clip set");
        JsonObject wrong = bake();
        wrong.getAsJsonObject("clips").add("EXTRA", wrong.getAsJsonObject("clips").remove("CROSS_ARMS"));
        rejected(wrong, "missing CROSS_ARMS");
        JsonObject missing = withoutEmotes(bake()); missing.getAsJsonObject("clips").remove("RUN");
        rejected(missing, "clip set");
    }

    @Test public void theEmotesOfHerOwnComeAllFiveOrNotAtAll()
    {
        JsonObject nine = bake(); nine.getAsJsonObject("clips").remove("STOMP"); rejected(nine, "clip set");
        JsonObject renamed = bake();
        renamed.getAsJsonObject("clips").add("EXTRA", renamed.getAsJsonObject("clips").remove("PEER"));
        rejected(renamed, "missing PEER");
        for (String name : new String[]{"COWER", "PEER", "HANDS_ON_HIPS", "HAIR_FLICK", "STOMP"})
        {
            JsonObject loop = bake(); loop.getAsJsonObject("clips").getAsJsonObject(name).addProperty("loop", true);
            rejected(loop, "clip timing " + name);
            JsonObject count = bake();
            JsonObject clip = count.getAsJsonObject("clips").getAsJsonObject(name);
            JsonArray samples = clip.getAsJsonArray("samples"); samples.remove(samples.size() - 1);
            clip.addProperty("durationClientCycles", samples.size() * 2);
            rejected(count, "clip samples " + name);
        }
    }

    @Test public void emoteLoopFlagsSampleCountsAndTimingAreEnforced()
    {
        for (String name : new String[]{"CROSS_ARMS", "IMPATIENT"})
        {
            JsonObject loop = bake();
            loop.getAsJsonObject("clips").getAsJsonObject(name).addProperty("loop", "CROSS_ARMS".equals(name));
            rejected(loop, "clip timing " + name);
            JsonObject period = bake();
            period.getAsJsonObject("clips").getAsJsonObject(name).addProperty("samplePeriodClientCycles", 1);
            rejected(period, "clip timing " + name);
            JsonObject count = bake();
            JsonObject clip = count.getAsJsonObject("clips").getAsJsonObject(name);
            JsonArray samples = clip.getAsJsonArray("samples"); samples.remove(samples.size() - 1);
            clip.addProperty("durationClientCycles", samples.size() * 2);
            rejected(count, "clip samples " + name);
        }
    }

    static JsonObject bake()
    {
        try (InputStreamReader reader = new InputStreamReader(new GZIPInputStream(
            NativeAsset.class.getResourceAsStream(NativeAsset.RESOURCE)), StandardCharsets.UTF_8))
        {
            return new JsonParser().parse(reader).getAsJsonObject();
        }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    static JsonObject withoutGestures(JsonObject source)
    {
        JsonObject result = source.deepCopy();
        for (String name : new String[]{"COWER", "PEER", "HANDS_ON_HIPS", "HAIR_FLICK", "STOMP"}) { result.getAsJsonObject("clips").remove(name); }
        return result;
    }

    static JsonObject withoutEmotes(JsonObject source)
    {
        JsonObject result = withoutGestures(source);
        result.getAsJsonObject("clips").remove("CROSS_ARMS");
        result.getAsJsonObject("clips").remove("IMPATIENT");
        return result;
    }

    static NativeAsset decode(JsonObject source)
    {
        return NativeAsset.decode(new Gson(), new StringReader(source.toString()));
    }

    private static void rejected(JsonObject source, String reason)
    {
        try { decode(source); fail("Accepted invalid " + reason); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(reason)); }
    }
}
