package com.malfoid.asset;

import com.google.gson.Gson;
import com.malfoid.MalfoidConfig;
import com.malfoid.motion.WholeBoundsVisibility;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NativePoseBankHeightTest
{
    @Test public void heightSettingIsWholePercentWithTheDeclaredRangeAndDefault() throws Exception
    {
        Method setting = MalfoidConfig.class.getMethod("heightPercent");
        assertEquals(int.class, setting.getReturnType());
        assertEquals(100, new MalfoidConfig() {}.heightPercent());
        assertEquals(90, setting.getAnnotation(Range.class).min());
        assertEquals(110, setting.getAnnotation(Range.class).max());
        assertEquals("Height", setting.getAnnotation(ConfigItem.class).name());
        assertEquals("heightPercent", setting.getAnnotation(ConfigItem.class).keyName());
        assertEquals(Units.PERCENT, setting.getAnnotation(Units.class).value());
    }

    @Test public void all205ModelsScaleAboutTheSolesAnd100KeepsExactAuthoredBits() throws Exception
    {
        NativeAsset asset = NativeAsset.load(new Gson());
        for (int percent : new int[]{90, 100, 110})
        {
            ImportFixture fixture = new ImportFixture(asset, percent);
            fixture.prepare();
            NativePoseBank bank = fixture.bank;
            assertEquals(percent, bank.heightPercent());
            if (percent == 100) assertSame(asset.unionBounds, bank.bounds());
            for (int index = 0; index < fixture.authored.size(); index++)
            {
                float[] authored = fixture.authored.get(index);
                Model model = bank.preparedModel(index);
                assertNotNull(model);
                for (int vertex = 0; vertex < asset.vertexCount; vertex++)
                {
                    float[] actual = {model.getVerticesX()[vertex], model.getVerticesY()[vertex], model.getVerticesZ()[vertex]};
                    for (int axis = 0; axis < 3; axis++)
                    {
                        float original = authored[vertex * 3 + axis];
                        if (percent == 100)
                        {
                            assertEquals(Float.floatToRawIntBits(original), Float.floatToRawIntBits(actual[axis]));
                        }
                        else
                        {
                            double pivot = axis == 1 ? 6 : 0;
                            assertEquals(pivot + (original - pivot) * percent / 100.0, actual[axis], .0001);
                        }
                    }
                    WholeBoundsVisibility.Bounds bounds = bank.bounds();
                    assertTrue(actual[0] >= bounds.minX && actual[0] <= bounds.maxX);
                    assertTrue(actual[1] >= bounds.minY && actual[1] <= bounds.maxY);
                    assertTrue(actual[2] >= bounds.minZ && actual[2] <= bounds.maxZ);
                }
                for (int face = 0; face < asset.triangleCount; face++)
                {
                    assertEquals(asset.faces[face * 3], model.getFaceIndices1()[face]);
                    assertEquals(asset.faces[face * 3 + 1], model.getFaceIndices2()[face]);
                    assertEquals(asset.faces[face * 3 + 2], model.getFaceIndices3()[face]);
                    assertEquals(0, model.getFaceRenderPriorities()[face]);
                    if (asset.faceShadeModes[face] == 1) assertTrue(model.getFaceColors3()[face] >= 2);
                    else assertEquals(-1, model.getFaceColors3()[face]);
                }
                assertEquals(1, fixture.boundsCalls[index]);
            }
            assertEquals(205, fixture.lightCalls);
            for (int sole : new int[]{153, 154, 155, 156, 157, 158, 213, 214, 215, 216, 217, 218})
            {
                assertEquals(6, bank.preparedModel(0).getVerticesY()[sole], 0);
            }
        }
    }

    @Test public void scalingDoesNotChangeClipSamplesOrPlaybackTiming() throws Exception
    {
        NativeAsset asset = NativeAsset.load(new Gson());
        ImportFixture fixture = new ImportFixture(asset, 110);
        fixture.prepare();
        NativePoseBank bank = fixture.bank;
        int offset = 1;
        for (String name : asset.clips.keySet())
        {
            NativeAsset.Clip clip = asset.clips.get(name);
            for (int cycle = 0; cycle < clip.durationClientCycles * 2; cycle++)
            {
                int phase = clip.loop ? cycle % clip.durationClientCycles : Math.min(cycle, clip.durationClientCycles - 1);
                int index = offset + phase / 2;
                assertSame(bank.preparedModel(index), bank.pose(name, cycle));
            }
            offset += clip.samples.length;
        }
        assertEquals(205, offset);
    }

    @Test public void defaultConstructorIs100AndOutOfRangeValuesAreRejected() throws Exception
    {
        NativeAsset asset = NativeAsset.load(new Gson());
        assertEquals(100, new NativePoseBank(mock(Client.class), asset).heightPercent());
        for (int percent : new int[]{89, 111})
        {
            try
            {
                new NativePoseBank(mock(Client.class), asset, percent);
                fail("accepted invalid Height setting");
            }
            catch (IllegalStateException expected)
            {
                assertTrue(expected.getMessage().contains("Height setting range"));
            }
        }
    }

    /** Begin after cache allocation so this fixture exercises the actual pose import pump. */
    private static final class ImportFixture
    {
        final NativeAsset asset;
        final NativePoseBank bank;
        final List<float[]> authored = new ArrayList<>();
        final int[] boundsCalls = new int[205];
        int lightCalls, imported;
        final int[] faceA, faceB, faceC;
        final byte[] priorities;

        ImportFixture(NativeAsset asset, int percent) throws Exception
        {
            this.asset = asset;
            bank = new NativePoseBank(mock(Client.class), asset, percent);
            authored.add(asset.baseVertices);
            for (NativeAsset.Clip clip : asset.clips.values())
            {
                for (float[] pose : clip.samples) authored.add(pose);
            }
            faceA = new int[asset.triangleCount];
            faceB = new int[asset.triangleCount];
            faceC = new int[asset.triangleCount];
            for (int face = 0; face < asset.triangleCount; face++)
            {
                faceA[face] = asset.faces[face * 3];
                faceB[face] = asset.faces[face * 3 + 1];
                faceC[face] = asset.faces[face * 3 + 2];
            }
            priorities = new byte[asset.triangleCount];
            ModelData template = mock(ModelData.class, withSettings().stubOnly());
            when(template.getVerticesX()).thenReturn(new float[asset.vertexCount]);
            when(template.getVerticesY()).thenReturn(new float[asset.vertexCount]);
            when(template.getVerticesZ()).thenReturn(new float[asset.vertexCount]);
            when(template.shallowCopy()).thenAnswer(invocation -> nextImport());
            ModelData donor = mock(ModelData.class, withSettings().stubOnly());
            set(bank, "basic", donor);
            set(bank, "priority", donor);
            set(bank, "transparent", donor);
            set(bank, "donorHashes", new long[]{0, 0, 0});
            set(bank, "template", template);
            set(bank, "primitiveIndex", asset.triangleCount - 1);
            set(bank, "compacted", true);
            set(bank, "initialized", true);
        }

        void prepare()
        {
            for (int cycle = 0; cycle < authored.size(); cycle++)
            {
                bank.advance(cycle);
                assertEquals(cycle + 1, bank.preparedCount());
                bank.advance(cycle);
                assertEquals(cycle + 1, bank.preparedCount());
            }
            assertTrue(bank.isReady());
            assertEquals(205, bank.preparedCount());
            assertEquals(204, bank.poseCount());
        }

        private ModelData nextImport()
        {
            float[] x = new float[asset.vertexCount], y = new float[asset.vertexCount], z = new float[asset.vertexCount];
            ModelData data = mock(ModelData.class, withSettings().stubOnly());
            when(data.getVerticesCount()).thenReturn(asset.vertexCount);
            when(data.getFaceCount()).thenReturn(asset.triangleCount);
            when(data.getVerticesX()).thenReturn(x);
            when(data.getVerticesY()).thenReturn(y);
            when(data.getVerticesZ()).thenReturn(z);
            when(data.cloneVertices()).thenReturn(data);
            Model model = mock(Model.class, withSettings().stubOnly());
            int index = imported++;
            doAnswer(call -> { boundsCalls[index]++; return null; }).when(model).calculateBoundsCylinder();
            when(model.getVerticesCount()).thenReturn(asset.vertexCount);
            when(model.getFaceCount()).thenReturn(asset.triangleCount);
            when(model.getVerticesX()).thenReturn(x);
            when(model.getVerticesY()).thenReturn(y);
            when(model.getVerticesZ()).thenReturn(z);
            when(model.getFaceIndices1()).thenReturn(faceA);
            when(model.getFaceIndices2()).thenReturn(faceB);
            when(model.getFaceIndices3()).thenReturn(faceC);
            when(model.getFaceColors1()).thenReturn(new int[asset.triangleCount]);
            when(model.getFaceColors2()).thenReturn(new int[asset.triangleCount]);
            when(model.getFaceColors3()).thenReturn(new int[asset.triangleCount]);
            when(model.getFaceRenderPriorities()).thenReturn(priorities);
            when(data.light(64, 850, -30, -50, -30)).thenAnswer(call -> { lightCalls++; return model; });
            return data;
        }
    }

    private static void set(Object object, String name, Object value) throws Exception
    {
        Field field = NativePoseBank.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
