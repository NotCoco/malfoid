package com.malfoid.asset;

import com.google.gson.Gson;
import com.malfoid.motion.WholeBoundsVisibility;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** The approved, immutable offline bake; no authoring files are read at runtime. */
final class NativeAsset
{
    static final String RESOURCE = "/com/malfoid/model/Malfoid.native.json.gz";
    static final String SHA256 = "fba96d4078af689be666edfe7e7c87f4cbc4c6472048b80c88b264e052dfc12f";
    static final int MAX_SAMPLES = 204;
    final int vertexCount;
    final int triangleCount;
    final float feetOffsetModelUnits;
    final float[] baseVertices;
    final int[] faces;
    final int[] faceHsl;
    final int[] faceShadeModes;
    final byte[] facePriorities;
    final Map<String, Clip> clips;
    final WholeBoundsVisibility.Bounds unionBounds;

    static final class Clip
    {
        final int samplePeriodClientCycles;
        final int durationClientCycles;
        final boolean loop;
        final float[][] samples;

        private Clip(ClipData source)
        {
            samplePeriodClientCycles = source.samplePeriodClientCycles;
            durationClientCycles = source.durationClientCycles;
            loop = source.loop;
            samples = source.samples;
        }
    }

    private static final class ClipData
    {
        int samplePeriodClientCycles;
        int durationClientCycles;
        boolean loop;
        float[][] samples;
    }

    private static final class Data
    {
        int schemaVersion;
        String assetRevision;
        int vertexCount;
        int triangleCount;
        float feetOffsetModelUnits;
        float[] baseVertices;
        int[] faces;
        int[] faceHsl;
        int[] faceShadeModes;
        byte[] facePriorities;
        Map<String, ClipData> clips;
    }

    private NativeAsset(Data data)
    {
        require(data != null && data.schemaVersion == 1
            && ("claude07".equals(data.assetRevision) || "claude08".equals(data.assetRevision) || "claude09".equals(data.assetRevision)
                || "claude10".equals(data.assetRevision)), "asset identity");
        require(data.vertexCount == 716 && data.triangleCount == 1100, "asset counts");
        vertexCount = data.vertexCount;
        triangleCount = data.triangleCount;
        require(Float.isFinite(data.feetOffsetModelUnits) && Math.abs(data.feetOffsetModelUnits) <= 16, "feet offset");
        feetOffsetModelUnits = data.feetOffsetModelUnits;
        require(data.faces != null && data.faces.length == triangleCount * 3, "face indices length");
        require(data.faceHsl != null && data.faceHsl.length == triangleCount, "face HSL length");
        require(data.faceShadeModes != null && data.faceShadeModes.length == triangleCount, "shade mask length");
        require(data.facePriorities != null && data.facePriorities.length == triangleCount, "priorities length");
        faces = data.faces;
        faceHsl = data.faceHsl;
        faceShadeModes = data.faceShadeModes;
        facePriorities = data.facePriorities;
        boolean[] used = new boolean[vertexCount];
        int smooth = 0;
        for (int i = 0; i < triangleCount; i++)
        {
            for (int j = 0; j < 3; j++)
            {
                int vertex = faces[i * 3 + j];
                require(vertex >= 0 && vertex < vertexCount, "face index range");
                used[vertex] = true;
            }
            require(faceHsl[i] >= 0 && faceHsl[i] <= 65535, "HSL range");
            require(faceShadeModes[i] == 0 || faceShadeModes[i] == 1, "shade mode");
            require(facePriorities[i] == 0, "claude07 face priority");
            smooth += faceShadeModes[i];
        }
        require(smooth == 437, "claude07 shading identity");
        for (boolean present : used)
        {
            require(present, "unused vertex");
        }
        baseVertices = data.baseVertices;
        validatePose(baseVertices);
        // Three clips; or those and the two of Addendum G; or those five and the five emotes of her own (H.6).
        require(data.clips != null && (data.clips.size() == 3 || data.clips.size() == 5 || data.clips.size() == 10), "clip set");
        Map<String, Clip> loaded = new LinkedHashMap<>();
        int samples = 0;
        String[] names = data.clips.size() == 3 ? new String[]{"IDLE", "WALK", "RUN"}
            : data.clips.size() == 5 ? new String[]{"IDLE", "WALK", "RUN", "CROSS_ARMS", "IMPATIENT"}
            : new String[]{"IDLE", "WALK", "RUN", "CROSS_ARMS", "IMPATIENT", "COWER", "PEER", "HANDS_ON_HIPS", "HAIR_FLICK", "STOMP"};
        for (String name : names)
        {
            ClipData clip = data.clips.get(name);
            require(clip != null && clip.samples != null && clip.samples.length > 0, "missing " + name);
            GesturePlayback.Gesture gesture = null;
            for (GesturePlayback.Gesture known : GesturePlayback.Gesture.values()) { if (known.clip.equals(name)) { gesture = known; } }
            require(clip.samplePeriodClientCycles == 2 && clip.durationClientCycles == 2 * clip.samples.length
                && clip.loop == !("CROSS_ARMS".equals(name) || gesture != null),
                "clip timing " + name);
            int expected = gesture != null ? gesture.samples
                : "IDLE".equals(name) ? 50 : "WALK".equals(name) ? 30 : "CROSS_ARMS".equals(name) ? 10 : 20;
            require(clip.samples.length == expected, "clip samples " + name);
            for (float[] pose : clip.samples)
            {
                validatePose(pose);
            }
            samples += clip.samples.length;
            loaded.put(name, new Clip(clip));
        }
        // 1.25 MiB while there were at most 130 samples; 2 MiB since the emotes of her own (H.6).
        require(samples <= MAX_SAMPLES && 12L * vertexCount * (1L + samples) <= 2097152L, "pose payload budget");
        clips = Collections.unmodifiableMap(loaded);
        double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        accumulateBounds(baseVertices, minimum, maximum);
        for (Clip clip : clips.values())
        {
            for (float[] pose : clip.samples) accumulateBounds(pose, minimum, maximum);
        }
        unionBounds = new WholeBoundsVisibility.Bounds(minimum[0] - 2, minimum[1] - 2, minimum[2] - 2,
            maximum[0] + 2, maximum[1] + 2, maximum[2] + 2);
    }

    static NativeAsset load(Gson gson) throws IOException
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Data data;
            InputStream resource = NativeAsset.class.getResourceAsStream(RESOURCE);
            if (resource == null)
            {
                throw new IOException("Missing bundled Malfoid model");
            }
            try (DigestInputStream compressed = new DigestInputStream(resource, digest);
                 InputStreamReader reader = new InputStreamReader(new GZIPInputStream(compressed), StandardCharsets.UTF_8))
            {
                data = gson.fromJson(reader, Data.class);
                while (reader.read() != -1)
                {
                    // Consume the gzip trailer before checking the compressed resource hash.
                }
            }
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest())
            {
                hash.append(String.format("%02x", value & 255));
            }
            require(SHA256.contentEquals(hash), "bundled model SHA-256");
            return new NativeAsset(data);
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IOException(ex);
        }
    }

    /** Validate a supplied bake for offline acceptance; runtime loading remains hash-pinned above. */
    static NativeAsset decode(Gson gson, Reader reader)
    {
        return new NativeAsset(gson.fromJson(reader, Data.class));
    }

    private void validatePose(float[] pose)
    {
        require(pose != null && pose.length == vertexCount * 3, "pose coordinates length");
        for (float coordinate : pose)
        {
            require(Float.isFinite(coordinate) && Math.abs(coordinate) <= 512, "pose coordinate range");
        }
        for (int i = 0; i < triangleCount; i++)
        {
            int a = faces[i * 3] * 3, b = faces[i * 3 + 1] * 3, c = faces[i * 3 + 2] * 3;
            double ux = pose[b] - pose[a], uy = pose[b + 1] - pose[a + 1], uz = pose[b + 2] - pose[a + 2];
            double vx = pose[c] - pose[a], vy = pose[c + 1] - pose[a + 1], vz = pose[c + 2] - pose[a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            require(nx * nx + ny * ny + nz * nz > 1.0e-12, "collapsed pose triangle");
        }
    }

    private static void accumulateBounds(float[] pose, double[] minimum, double[] maximum)
    {
        for (int i = 0; i < pose.length; i++)
        {
            int axis = i % 3;
            minimum[axis] = Math.min(minimum[axis], pose[i]);
            maximum[axis] = Math.max(maximum[axis], pose[i]);
        }
    }

    private static void require(boolean condition, String field)
    {
        if (!condition)
        {
            throw new IllegalArgumentException("Invalid Malfoid " + field);
        }
    }
}
