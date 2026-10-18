package com.malfoid.asset;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeAssetTest
{
    @Test public void approvedBakeLoadsWithItsExactIdentityAndTiming() throws Exception
    {
        NativeAsset asset = NativeAsset.load(new Gson());
        assertEquals(716, asset.vertexCount);
        assertEquals(1100, asset.triangleCount);
        assertEquals(437, Arrays.stream(asset.faceShadeModes).sum());
        for (byte priority : asset.facePriorities)
        {
            assertEquals(0, priority);
        }
        assertEquals(50, asset.clips.get("IDLE").samples.length);
        assertEquals(30, asset.clips.get("WALK").samples.length);
        assertEquals(20, asset.clips.get("RUN").samples.length);
        assertEquals(10, asset.clips.size());
        for (GesturePlayback.Gesture gesture : GesturePlayback.Gesture.values())
        {
            assertEquals(gesture.clip, gesture.samples, asset.clips.get(gesture.clip).samples.length);
            assertFalse(gesture.clip, asset.clips.get(gesture.clip).loop);
            // Each begins as she stands; the two that are played once end as she stands.
            assertArrayEquals(gesture.clip, asset.clips.get("IDLE").samples[0], asset.clips.get(gesture.clip).samples[0], .0002f);
            if (!gesture.held)
            {
                assertArrayEquals(gesture.clip, asset.clips.get("IDLE").samples[0],
                    asset.clips.get(gesture.clip).samples[gesture.samples - 1], .0002f);
            }
        }
        assertEquals(10, asset.clips.get("CROSS_ARMS").samples.length);
        assertFalse(asset.clips.get("CROSS_ARMS").loop);
        assertEquals(20, asset.clips.get("IMPATIENT").samples.length);
        assertTrue(asset.clips.get("IMPATIENT").loop);
        assertArrayEquals(asset.clips.get("IDLE").samples[0], asset.clips.get("CROSS_ARMS").samples[0], .00002f);
        assertArrayEquals(asset.clips.get("CROSS_ARMS").samples[9], asset.clips.get("IMPATIENT").samples[0], 0);
        for (NativeAsset.Clip clip : asset.clips.values())
        {
            assertEquals(2, clip.samplePeriodClientCycles);
            assertEquals(2 * clip.samples.length, clip.durationClientCycles);
        }
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < asset.vertexCount; vertex++)
        {
            min = Math.min(min, asset.baseVertices[vertex * 3 + 1]);
            max = Math.max(max, asset.baseVertices[vertex * 3 + 1]);
        }
        assertEquals(212, max - min, .02);
        assertEquals(206, -min, .02);
        assertEquals(-6, asset.feetOffsetModelUnits, .001);
        assertEquals(-asset.feetOffsetModelUnits, max, .001);
        assertTrue("below-origin soles are inside the union", asset.unionBounds.maxY >= max);
        for (java.util.Map.Entry<String, NativeAsset.Clip> entry : asset.clips.entrySet())
        {
            boolean reaching = "HANDS_ON_HIPS".equals(entry.getKey()) || "HAIR_FLICK".equals(entry.getKey());
            for (float[] pose : entry.getValue().samples)
            {
                float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
                for (int vertex = 0; vertex < asset.vertexCount; vertex++)
                {
                    left = Math.min(left, pose[vertex * 3]);
                    right = Math.max(right, pose[vertex * 3]);
                    assertTrue("all below-origin pose coordinates are inside the union",
                        asset.unionBounds.maxY >= pose[vertex * 3 + 1]);
                }
                assertTrue("claude10 clip width " + entry.getKey(), right - left <= (reaching ? 100 : 75));      // an elbow or a hand out (H.6)
            }
        }
    }

    @Test public void authoredFlatWalkSolesMoveBackAtGroundSpeed() throws Exception
    {
        NativeAsset.Clip walk = NativeAsset.load(new Gson()).clips.get("WALK");
        // claude07 geometry-audit Boot parts and mf_body.py's final six-vertex
        // sole rings identify L 153..158 and R 213..218. These pairs are in
        // mf_rig.py's flat planted phase, excluding heel/toe rotation.
        int[][] planted = {{2, 153}, {3, 153}, {4, 153}, {9, 213}, {10, 213},
            {11, 213}, {12, 213}, {17, 153}, {18, 153}, {19, 153}, {24, 213},
            {25, 213}, {26, 213}, {27, 213}};
        double expectedSpeed = 128.0 / .6;
        double sampleSeconds = walk.samplePeriodClientCycles * .02;
        for (int[] pair : planted)
        {
            float[] before = walk.samples[pair[0]], after = walk.samples[pair[0] + 1];
            for (int vertex = pair[1]; vertex < pair[1] + 6; vertex++)
            {
                assertEquals("flat sole below-origin placement", 6, before[vertex * 3 + 1], .3);
                assertEquals("flat sole below-origin placement", 6, after[vertex * 3 + 1], .3);
                double speed = (after[vertex * 3 + 2] - before[vertex * 3 + 2]) / sampleSeconds;
                assertEquals("D.2 flat planted WALK speed", expectedSpeed, speed, expectedSpeed * .1);
            }
        }
    }

    @Test public void authoredRunContactCornersMoveBackAtGroundSpeed() throws Exception
    {
        NativeAsset.Clip run = NativeAsset.load(new Gson()).clips.get("RUN");
        // The same authored sole rings identify heel corners 155/156 and
        // toe corners 153/158 (R +60). mf_rig.py's 0.4s cycle and contact
        // phase identify these consecutive heel-to-flat and toe-off samples.
        // RUN has only one sample in its fully flat phase, so this verifies
        // contact corners rather than claiming two fully flat sole poses.
        int[][] planted = {{0, 155}, {0, 156}, {1, 153}, {1, 158}, {2, 158},
            {5, 215}, {5, 216}, {6, 213}, {6, 218}, {7, 218},
            {10, 155}, {10, 156}, {11, 153}, {11, 158}, {12, 158},
            {15, 215}, {15, 216}, {16, 213}, {16, 218}, {17, 218}};
        double expectedSpeed = 128.0 / .3;
        double sampleSeconds = run.samplePeriodClientCycles * .02;
        for (int[] pair : planted)
        {
            int coordinate = pair[1] * 3;
            float[] before = run.samples[pair[0]], after = run.samples[pair[0] + 1];
            assertEquals("contact corner below-origin placement", 6, before[coordinate + 1], .5);
            assertEquals("contact corner below-origin placement", 6, after[coordinate + 1], .5);
            double speed = (after[coordinate + 2] - before[coordinate + 2]) / sampleSeconds;
            assertEquals("D.2 planted RUN corner speed", expectedSpeed, speed, expectedSpeed * .1);
        }
    }
}
