package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class DecodeBenchmarkTest {
    // Redmi Pad 2 in-stream values (GPU decode + present): about 15 ms at 1080p, 29 ms at 2560x1600
    private static final List<DecodeBenchmark.Result> TABLET = Arrays.asList(
            new DecodeBenchmark.Result(1280, 720, 10.0f),
            new DecodeBenchmark.Result(1920, 1080, 15.0f),
            new DecodeBenchmark.Result(1920, 1200, 16.5f),
            new DecodeBenchmark.Result(2560, 1600, 29.0f));

    @Test
    public void sustainableLeavesThirtyPercentOfThePeriod() {
        // 60 fps: 0.7 x 16.67 = 11.67 ms
        assertTrue(DecodeBenchmark.isSustainable(11.6f, 60));
        assertFalse(DecodeBenchmark.isSustainable(11.7f, 60));
        // 45 fps: 0.7 x 22.2 = 15.6 ms
        assertTrue(DecodeBenchmark.isSustainable(15.0f, 45));
        assertFalse(DecodeBenchmark.isSustainable(0f, 60));
        assertFalse(DecodeBenchmark.isSustainable(5f, 0));
    }

    @Test
    public void largestSustainablePicksTheMostPixels() {
        assertEquals(1280, DecodeBenchmark.largestSustainable(TABLET, 60).width);
        DecodeBenchmark.Result at45 = DecodeBenchmark.largestSustainable(TABLET, 45);
        assertEquals(1920, at45.width);
        assertEquals(1080, at45.height);
        DecodeBenchmark.Result at30 = DecodeBenchmark.largestSustainable(TABLET, 30);
        assertEquals(1920, at30.width);
        assertEquals(1200, at30.height);
        assertNull(DecodeBenchmark.largestSustainable(TABLET, 144));
    }

    @Test
    public void exceedsUsesTheMeasuredSizeWhenThereIsOne() {
        assertTrue(DecodeBenchmark.exceeds(TABLET, 60, 2560, 1600));
        assertFalse(DecodeBenchmark.exceeds(TABLET, 45, 1920, 1080));
        assertTrue(DecodeBenchmark.exceeds(TABLET, 45, 1920, 1200));
    }

    @Test
    public void exceedsComparesPixelsForOtherSizes() {
        // 1600x900 has fewer pixels than 1920x1080 (sustainable at 45)
        assertFalse(DecodeBenchmark.exceeds(TABLET, 45, 1600, 900));
        // 2560x1440 has more
        assertTrue(DecodeBenchmark.exceeds(TABLET, 45, 2560, 1440));
        // Nothing sustainable: every size exceeds
        assertTrue(DecodeBenchmark.exceeds(TABLET, 144, 854, 480));
    }

    @Test
    public void noResultsMeansNoWarning() {
        assertFalse(DecodeBenchmark.exceeds(Collections.<DecodeBenchmark.Result>emptyList(), 60, 3840, 2160));
        assertFalse(DecodeBenchmark.exceeds(TABLET, 60, 0, 0));
    }

    @Test
    public void sizesEndWithTheNativeOne() {
        List<int[]> tablet = DecodeBenchmark.sizes(1600, 2560);
        assertEquals(4, tablet.size());
        assertEquals(2560, tablet.get(3)[0]);
        assertEquals(1600, tablet.get(3)[1]);
        // A 1920x1080 phone: only 1280x720 is smaller, then native
        List<int[]> phone = DecodeBenchmark.sizes(1080, 1920);
        assertEquals(2, phone.size());
        assertEquals(1920, phone.get(1)[0]);
        // Odd native sizes are made even
        assertEquals(2772, DecodeBenchmark.sizes(2773, 1281).get(3)[0]);
        assertEquals(1280, DecodeBenchmark.sizes(2773, 1281).get(3)[1]);
    }

    @Test
    public void frameBytesFollowTheBitrate() {
        // 60 Mbit/s at 60 fps: 125 KB per frame
        assertEquals(125000, DecodeBenchmark.frameBytes(60000, 60));
        assertEquals(0, DecodeBenchmark.frameBytes(0, 60));
        assertEquals(0, DecodeBenchmark.frameBytes(60000, 0));
    }

    @Test
    public void storedResultsRoundTrip() {
        List<DecodeBenchmark.Result> back = DecodeBenchmark.parse(DecodeBenchmark.format(TABLET));
        assertEquals(4, back.size());
        assertEquals(2560, back.get(3).width);
        assertEquals(29.0f, back.get(3).medianMs, 0.001f);
        assertTrue(DecodeBenchmark.parse("garbage").isEmpty());
        assertTrue(DecodeBenchmark.parse(null).isEmpty());
        assertTrue(DecodeBenchmark.parse("1920x1080=abc").isEmpty());
    }

    @Test
    public void storageKeyChangesWithTheVersion() {
        assertFalse(DecodeBenchmark.storageKey("25040RP0AL", 10).equals(DecodeBenchmark.storageKey("25040RP0AL", 20)));
    }
}
