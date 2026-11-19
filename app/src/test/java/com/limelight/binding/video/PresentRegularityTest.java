package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PresentRegularityTest {
    @Test
    public void sharesAddUpToAHundred() {
        // 45 fps on a 90 Hz panel with pacing: almost every frame two refreshes
        float[] p = PresentRegularity.percentages(45, new int[]{2, 41, 2});
        assertArrayEquals(new float[]{4.444f, 91.111f, 4.444f}, p, 0.01f);
    }

    @Test
    public void unknownWithoutDisplayTiming() {
        assertNull(PresentRegularity.percentages(-1, new int[]{0, 0, 0}));
        assertEquals("shown unknown", PresentRegularity.describe(null));
    }

    @Test
    public void unknownWhenNothingWasCounted() {
        assertNull(PresentRegularity.percentages(0, new int[]{0, 0, 0}));
        assertNull(PresentRegularity.percentages(5, null));
        assertNull(PresentRegularity.percentages(5, new int[]{1, 2}));
    }

    @Test
    public void describeFormatsTheShares() {
        assertEquals("shown 1x 50.0%, 2x 50.0%, 3+x 0.0%", PresentRegularity.describe(new float[]{50f, 50f, 0f}));
    }
}
