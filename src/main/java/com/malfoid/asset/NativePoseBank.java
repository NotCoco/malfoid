package com.malfoid.asset;

import com.malfoid.motion.WholeBoundsVisibility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/** A finite, client-thread-only preparation pump using public native model APIs. */
final class NativePoseBank
{
    private static final long DONOR_TIMEOUT_NANOS = 15_000_000_000L;
    private final Client client;
    private final NativeAsset asset;
    private final int heightPercent;
    private final float heightScale;
    private final WholeBoundsVisibility.Bounds bounds;
    private final List<ModelData> primitives = new ArrayList<>();
    private final List<ModelData> groups = new ArrayList<>();
    private final List<float[]> samples = new ArrayList<>();
    private final Model[] models;
    private ModelData basic, priority, transparent, padding, template;
    private boolean compacted, initialized;
    private int primitiveIndex, groupCursor, poseIndex;
    private int lastCycle = Integer.MIN_VALUE;
    private long started, readyAt;
    private long maxChunkNanos;
    private long[] donorHashes;
    private byte[] sharedPriorities;

    NativePoseBank(Client client, NativeAsset asset)
    {
        this(client, asset, 100);
    }

    NativePoseBank(Client client, NativeAsset asset, int heightPercent)
    {
        require(heightPercent >= 90 && heightPercent <= 110, "Height setting range");
        this.client = client;
        this.asset = asset;
        this.heightPercent = heightPercent;
        heightScale = heightPercent / 100f;
        WholeBoundsVisibility.Bounds original = asset.unionBounds;
        bounds = heightPercent == 100 ? original : new WholeBoundsVisibility.Bounds(
            original.minX * heightScale, 6 + (original.minY - 6) * heightScale, original.minZ * heightScale,
            original.maxX * heightScale, 6 + (original.maxY - 6) * heightScale, original.maxZ * heightScale);
        samples.add(asset.baseVertices);
        for (NativeAsset.Clip clip : asset.clips.values())
        {
            samples.addAll(Arrays.asList(clip.samples));
        }
        models = new Model[samples.size()];
    }

    void advance(int cycle)
    {
        if (lastCycle == cycle || isReady())
        {
            return;
        }
        lastCycle = cycle;
        long begin = System.nanoTime();
        if (started == 0)
        {
            started = begin;
        }
        try
        {
            prepareChunk(begin);
        }
        finally
        {
            maxChunkNanos = Math.max(maxChunkNanos, System.nanoTime() - begin);
        }
    }

    private void prepareChunk(long now)
    {
        if (basic == null)
        {
            ModelData b = client.loadModelData(823), p = client.loadModelData(6733), t = client.loadModelData(18871);
            if (b == null || p == null || t == null)
            {
                if (now - started >= DONOR_TIMEOUT_NANOS)
                {
                    throw new IllegalStateException("Malfoid native model donors unavailable after 15 seconds");
                }
                return;
            }
            require(b.getVerticesCount() == 3 && b.getFaceCount() == 1 && p.getVerticesCount() == 6
                && p.getFaceCount() == 2 && t.getVerticesCount() == 3 && t.getFaceCount() == 1, "cache donor counts");
            basic = b;
            priority = p;
            transparent = t;
            donorHashes = new long[]{fingerprint(b), fingerprint(p), fingerprint(t)};
            return;
        }
        int primitiveCount = asset.triangleCount - 1;
        if (primitiveIndex < primitiveCount)
        {
            int limit = padding == null ? 16 : 256;
            for (int i = 0; i < limit && primitiveIndex < primitiveCount; i++, primitiveIndex++)
            {
                primitives.add(primitive(primitiveIndex));
            }
            return;
        }
        if (groupCursor < primitives.size())
        {
            int end = Math.min(primitives.size(), groupCursor + 48);
            groups.add(client.mergeModels(primitives.subList(groupCursor, end).toArray(new ModelData[0])));
            groupCursor = end;
            return;
        }
        if (template == null)
        {
            template = client.mergeModels(groups.toArray(new ModelData[0]));
            requireCounts(template);
            for (ModelData donor : new ModelData[]{basic, priority, transparent})
            {
                require(template.getFaceIndices1() != donor.getFaceIndices1()
                    && template.getFaceIndices2() != donor.getFaceIndices2()
                    && template.getFaceIndices3() != donor.getFaceIndices3(), "template topology ownership");
                require(template.getFaceColors() != donor.getFaceColors()
                    && (template.getFaceTransparencies() == null || template.getFaceTransparencies() != donor.getFaceTransparencies())
                    && (template.getFaceTextures() == null || template.getFaceTextures() != donor.getFaceTextures()),
                    "template appearance ownership");
            }
            groups.clear();
            primitives.clear();
            return;
        }
        if (!compacted)
        {
            // Compact only allocator geometry. Merging authored coincident vertices would reindex the face order.
            template = client.mergeModels(new ModelData[]{template});
            requireCounts(template);
            require(template.getVerticesX().length == asset.vertexCount, "template coordinate capacity");
            compacted = true;
            return;
        }
        if (!initialized)
        {
            for (int i = 0; i < asset.triangleCount; i++)
            {
                template.getFaceIndices1()[i] = asset.faces[i * 3];
                template.getFaceIndices2()[i] = asset.faces[i * 3 + 1];
                template.getFaceIndices3()[i] = asset.faces[i * 3 + 2];
                template.getFaceColors()[i] = (short) asset.faceHsl[i];
            }
            if (template.getFaceTextures() != null)
            {
                Arrays.fill(template.getFaceTextures(), (short) -1);
            }
            if (template.getFaceTransparencies() != null)
            {
                Arrays.fill(template.getFaceTransparencies(), (byte) 0);
            }
            initialized = true;
            return;
        }
        models[poseIndex] = importPose(samples.get(poseIndex));
        poseIndex++;
        if (poseIndex == models.length)
        {
            require(fingerprint(basic) == donorHashes[0] && fingerprint(priority) == donorHashes[1]
                && fingerprint(transparent) == donorHashes[2], "unchanged cache donors");
            readyAt = System.nanoTime();
            // Native models retain only the finite immutable arrays they use.
            basic = priority = transparent = padding = template = null;
            samples.clear();
        }
    }

    private ModelData primitive(int index)
    {
        boolean triple = (asset.vertexCount - 3) % 2 != 0;
        int pairStart = triple ? 3 : 2;
        int pairEnd = pairStart + (asset.vertexCount - 3 - (triple ? 3 : 0)) / 2;
        if (index >= pairEnd && padding != null)
        {
            return padding;
        }
        ModelData source = index == 0 ? priority : index == 1 ? transparent : basic;
        ModelData result = source.shallowCopy().cloneVertices();
        float[] x = result.getVerticesX(), y = result.getVerticesY(), z = result.getVerticesZ();
        for (int i = 0; i < x.length; i++)
        {
            int anchor = i % 3;
            x[i] = anchor == 2 ? 1 : 0;
            y[i] = 0;
            z[i] = anchor == 1 ? 1 : 0;
        }
        if (triple && index == 2)
        {
            for (int i = 0; i < 3; i++)
            {
                x[i] = 2;
                y[i] = i;
                z[i] = 2;
            }
        }
        else if (index >= pairStart && index < pairEnd)
        {
            x[1] = x[2] = index + 1;
            y[1] = 1;
            y[2] = 2;
            z[1] = z[2] = 2;
        }
        if (index >= pairEnd)
        {
            padding = result;
        }
        return result;
    }

    private Model importPose(float[] coordinates)
    {
        ModelData data = template.shallowCopy().cloneVertices();
        requireCounts(data);
        require(data.getVerticesX() != template.getVerticesX() && data.getVerticesY() != template.getVerticesY()
            && data.getVerticesZ() != template.getVerticesZ(), "independent pose vertices");
        for (int i = 0; i < asset.vertexCount; i++)
        {
            data.getVerticesX()[i] = scaledCoordinate(coordinates[i * 3], false);
            data.getVerticesY()[i] = scaledCoordinate(coordinates[i * 3 + 1], true);
            data.getVerticesZ()[i] = scaledCoordinate(coordinates[i * 3 + 2], false);
        }
        Model model = data.light(64, 850, -30, -50, -30);
        require(model != null && model.getVerticesCount() == asset.vertexCount && model.getFaceCount() == asset.triangleCount,
            "lit model counts");
        for (int i = 0; i < asset.vertexCount; i++)
        {
            require(model.getVerticesX()[i] == scaledCoordinate(coordinates[i * 3], false)
                && model.getVerticesY()[i] == scaledCoordinate(coordinates[i * 3 + 1], true)
                && model.getVerticesZ()[i] == scaledCoordinate(coordinates[i * 3 + 2], false), "native float coordinates");
        }
        for (int i = 0; i < asset.triangleCount; i++)
        {
            require(model.getFaceIndices1()[i] == asset.faces[i * 3] && model.getFaceIndices2()[i] == asset.faces[i * 3 + 1]
                && model.getFaceIndices3()[i] == asset.faces[i * 3 + 2], "native face order");
        }
        require(model.getFaceColors1() != null && model.getFaceColors2() != null && model.getFaceColors3() != null
            && model.getFaceColors1().length >= asset.triangleCount && model.getFaceColors2().length >= asset.triangleCount
            && model.getFaceColors3().length >= asset.triangleCount, "lit color arrays");
        byte[] priorities = model.getFaceRenderPriorities();
        require(priorities != null && priorities.length >= asset.triangleCount, "native priority array");
        if (sharedPriorities == null)
        {
            System.arraycopy(asset.facePriorities, 0, priorities, 0, asset.triangleCount);
            sharedPriorities = priorities;
        }
        require(priorities == sharedPriorities && Arrays.equals(priorities, asset.facePriorities), "immutable priorities");
        for (int i = 0; i < poseIndex; i++)
        {
            Model prior = models[i];
            require(prior.getVerticesX() != model.getVerticesX() && prior.getVerticesY() != model.getVerticesY()
                && prior.getVerticesZ() != model.getVerticesZ(), "pose isolation");
            require(prior.getFaceColors1() != model.getFaceColors1() && prior.getFaceColors2() != model.getFaceColors2()
                && prior.getFaceColors3() != model.getFaceColors3(), "pose color isolation");
            if (model.getVertexNormalsX() != null && prior.getVertexNormalsX() != null)
            {
                require(prior.getVertexNormalsX() != model.getVertexNormalsX()
                    && prior.getVertexNormalsY() != model.getVertexNormalsY()
                    && prior.getVertexNormalsZ() != model.getVertexNormalsZ(), "pose normal isolation");
            }
        }
        rewriteShading(model);
        int smoothFaces = 0;
        for (int face = 0; face < asset.triangleCount; face++)
        {
            require(model.getFaceColors1()[face] >= 2 && model.getFaceColors1()[face] <= 65535
                && model.getFaceColors2()[face] >= 2 && model.getFaceColors2()[face] <= 65535, "native lit color range");
            if (asset.faceShadeModes[face] == 1)
            {
                smoothFaces++;
                require(model.getFaceColors3()[face] >= 2 && model.getFaceColors3()[face] <= 65535, "smooth face sentinel");
            }
            else
            {
                require(model.getFaceColors3()[face] == -1 && model.getFaceColors1()[face] == model.getFaceColors2()[face],
                    "flat face sentinel");
            }
        }
        require(smoothFaces == 437, "approved smooth face count");
        model.calculateBoundsCylinder();
        return model;
    }

    private void rewriteShading(Model model)
    {
        float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
        int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
        double[] nx = new double[asset.vertexCount], ny = new double[asset.vertexCount], nz = new double[asset.vertexCount];
        int[] count = new int[asset.vertexCount];
        for (int face = 0; face < asset.triangleCount; face++)
        {
            if (asset.faceShadeModes[face] == 0)
            {
                continue;
            }
            int va = a[face], vb = b[face], vc = c[face];
            double ux = x[vb] - x[va], uy = y[vb] - y[va], uz = z[vb] - z[va];
            double vx = x[vc] - x[va], vy = y[vc] - y[va], vz = z[vc] - z[va];
            double fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
            double scale = 256 / Math.sqrt(fx * fx + fy * fy + fz * fz);
            nx[va] += fx * scale; nx[vb] += fx * scale; nx[vc] += fx * scale;
            ny[va] += fy * scale; ny[vb] += fy * scale; ny[vc] += fy * scale;
            nz[va] += fz * scale; nz[vb] += fz * scale; nz[vc] += fz * scale;
            count[va]++; count[vb]++; count[vc]++;
        }
        for (int face = 0; face < asset.triangleCount; face++)
        {
            int va = a[face], vb = b[face], vc = c[face];
            int hsl = asset.faceHsl[face];
            if (asset.faceShadeModes[face] == 1)
            {
                model.getFaceColors1()[face] = shade(hsl, 64 + (-30 * nx[va] - 50 * ny[va] - 30 * nz[va]) / (215 * count[va]));
                model.getFaceColors2()[face] = shade(hsl, 64 + (-30 * nx[vb] - 50 * ny[vb] - 30 * nz[vb]) / (215 * count[vb]));
                model.getFaceColors3()[face] = shade(hsl, 64 + (-30 * nx[vc] - 50 * ny[vc] - 30 * nz[vc]) / (215 * count[vc]));
            }
            else
            {
                double ux = x[vb] - x[va], uy = y[vb] - y[va], uz = z[vb] - z[va];
                double vx = x[vc] - x[va], vy = y[vc] - y[va], vz = z[vc] - z[va];
                double fx = uy * vz - uz * vy, fy = uz * vx - ux * vz, fz = ux * vy - uy * vx;
                double brightness = 64 + (-30 * fx - 50 * fy - 30 * fz) * 256 / (322 * Math.sqrt(fx * fx + fy * fy + fz * fz));
                int color = shade(hsl, brightness);
                model.getFaceColors1()[face] = model.getFaceColors2()[face] = color;
                model.getFaceColors3()[face] = -1;
            }
        }
    }

    private static int shade(int hsl, double brightness)
    {
        return (hsl & 65408) | Math.max(2, Math.min(126, (int) ((hsl & 127) * brightness / 128)));
    }

    private float scaledCoordinate(float coordinate, boolean vertical)
    {
        if (heightPercent == 100)
        {
            return coordinate;
        }
        return vertical ? 6 + (coordinate - 6) * heightScale : coordinate * heightScale;
    }

    Model pose(String name, int cycle)
    {
        NativeAsset.Clip clip = asset.clips.get(name);
        int offset = 1;
        for (String clipName : asset.clips.keySet())
        {
            if (clipName.equals(name))
            {
                break;
            }
            offset += asset.clips.get(clipName).samples.length;
        }
        int phase = clip.loop ? Math.floorMod(cycle, clip.durationClientCycles)
            : Math.max(0, Math.min(cycle, clip.durationClientCycles - 1));
        return models[offset + phase / clip.samplePeriodClientCycles];
    }

    boolean isReady() { return readyAt != 0; }
    int heightPercent() { return heightPercent; }
    WholeBoundsVisibility.Bounds bounds() { return bounds; }
    Model preparedModel(int index) { return index >= 0 && index < poseIndex ? models[index] : null; }
    int preparedCount() { return poseIndex; }
    int poseCount() { return models.length - 1; }
    double maxChunkMillis() { return maxChunkNanos / 1_000_000d; }
    double readyMillis() { return readyAt == 0 ? 0 : (readyAt - started) / 1_000_000d; }

    private void requireCounts(ModelData model)
    {
        require(model != null && model.getVerticesCount() == asset.vertexCount && model.getFaceCount() == asset.triangleCount,
            "native allocation counts");
    }

    private static long fingerprint(ModelData model)
    {
        long hash = Arrays.hashCode(model.getVerticesX());
        hash = hash * 31 + Arrays.hashCode(model.getVerticesY());
        hash = hash * 31 + Arrays.hashCode(model.getVerticesZ());
        hash = hash * 31 + Arrays.hashCode(model.getFaceIndices1());
        hash = hash * 31 + Arrays.hashCode(model.getFaceIndices2());
        hash = hash * 31 + Arrays.hashCode(model.getFaceIndices3());
        hash = hash * 31 + Arrays.hashCode(model.getFaceColors());
        hash = hash * 31 + Arrays.hashCode(model.getFaceTextures());
        return hash * 31 + Arrays.hashCode(model.getFaceTransparencies());
    }

    private static void require(boolean condition, String reason)
    {
        if (!condition)
        {
            throw new IllegalStateException("Malfoid " + reason + " verification failed");
        }
    }
}
