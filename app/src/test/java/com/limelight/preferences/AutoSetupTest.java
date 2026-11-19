package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class AutoSetupTest {
    // Redmi Pad 2 benchmark (Selene 1.13.0) and panel modes
    private static final List<DecodeBenchmark.Result> TABLET = Arrays.asList(
            new DecodeBenchmark.Result(1280, 720, 10.4f),
            new DecodeBenchmark.Result(1920, 1080, 14.7f),
            new DecodeBenchmark.Result(1920, 1200, 16.1f),
            new DecodeBenchmark.Result(2560, 1600, 28.3f));
    private static final float[] TABLET_HZ = {90f, 60f, 50f, 48f, 30f};

    private static AutoSetup.Measurement measurement(double frameBytes, double transitMs, double incomplete,
                                                     int[] shown, float hz) {
        AutoSetup.Measurement m = new AutoSetup.Measurement();
        m.avgFrameBytes = frameBytes;
        m.avgTransitMs = transitMs;
        m.incompletePercent = incomplete;
        m.shownCounts = shown;
        m.appliedHz = hz;
        m.frames = 600;
        return m;
    }

    @Test
    public void frameRatesAreThePanelRatesAndTheirHalves() {
        assertEquals(Arrays.asList(90, 60, 50, 48, 45, 30), AutoSetup.frameRates(TABLET_HZ));
        assertEquals(Arrays.asList(120, 90, 60, 45, 30), AutoSetup.frameRates(new float[]{120f, 90f, 60f, 30f}));
    }

    @Test
    public void candidatesAreSustainableAndOrderedByPixelRate() {
        List<AutoSetup.Candidate> c = AutoSetup.candidates(TABLET, TABLET_HZ);
        assertEquals(3, c.size());
        // 1080p@45 (93 Mpx/s) > 1200p@30 (69) > 1080p@30 (62) > 720p@60 (55); 2560x1600 is sustainable at no rate
        assertEquals("1920x1080@45", c.get(0).toString());
        assertEquals("1920x1200@30", c.get(1).toString());
        assertEquals("1920x1080@30", c.get(2).toString());
    }

    @Test
    public void noCandidateWithoutABenchmark() {
        assertTrue(AutoSetup.candidates(Collections.<DecodeBenchmark.Result>emptyList(), TABLET_HZ).isEmpty());
    }

    @Test
    public void testBitrateFollowsTheLinkWithinLimits() {
        assertEquals(60620, AutoSetup.testBitrateKbps(433));   // tablet
        assertEquals(150000, AutoSetup.testBitrateKbps(1200)); // phone, capped
        assertEquals(20000, AutoSetup.testBitrateKbps(100));   // slow link, floor
        assertEquals(60000, AutoSetup.testBitrateKbps(0));     // unknown
    }

    @Test
    public void bitrateIsAShareOfTheMeasuredGoodput() {
        // 114 KB frames crossing in 2.5 ms: about 373 Mbit/s of goodput -> 130 Mbit/s
        AutoSetup.Measurement m = measurement(116736, 2.5, 0, null, 60);
        assertEquals(373.6, AutoSetup.goodputMbps(m), 0.1);
        assertEquals(130744, AutoSetup.bitrateKbps(AutoSetup.goodputMbps(m)));
        assertEquals(20000, AutoSetup.bitrateKbps(30));
        assertEquals(150000, AutoSetup.bitrateKbps(1000));
        assertEquals(150000, AutoSetup.bitrateKbps(0));
    }

    @Test
    public void passesNeedsAnEvenCadence() {
        AutoSetup.Candidate c45 = new AutoSetup.Candidate(1920, 1080, 45, 14.7f);
        // 45 fps on 90 Hz: expected 2 refreshes per frame
        assertTrue(AutoSetup.passes(c45, measurement(100000, 5, 0, new int[]{5, 90, 5}, 90f)));
        assertFalse(AutoSetup.passes(c45, measurement(100000, 5, 0, new int[]{40, 55, 5}, 90f)));
        // 60 fps on a 90 Hz panel: expected 2 (rounded), only about half are -> fails
        AutoSetup.Candidate c60 = new AutoSetup.Candidate(1280, 720, 60, 10.4f);
        assertFalse(AutoSetup.passes(c60, measurement(100000, 5, 0, new int[]{51, 48, 1}, 90f)));
        // 60 fps on 60 Hz: expected 1
        assertTrue(AutoSetup.passes(c60, measurement(100000, 5, 0, new int[]{97, 2, 1}, 60f)));
    }

    @Test
    public void passesNeedsFewIncompleteFrames() {
        AutoSetup.Candidate c = new AutoSetup.Candidate(1920, 1080, 45, 14.7f);
        assertFalse(AutoSetup.passes(c, measurement(100000, 5, 1.0, new int[]{5, 90, 5}, 90f)));
        assertTrue(AutoSetup.passes(c, measurement(100000, 5, 0.9, new int[]{5, 90, 5}, 90f)));
    }

    @Test
    public void passesNeedsTheDecodeInTime() {
        AutoSetup.Candidate slow = new AutoSetup.Candidate(1920, 1200, 45, 16.1f);
        assertFalse(AutoSetup.passes(slow, measurement(100000, 5, 0, new int[]{5, 90, 5}, 90f)));
    }

    @Test
    public void withoutDisplayTimingTheRateMustDivideThePanel() {
        AutoSetup.Candidate c45 = new AutoSetup.Candidate(1920, 1080, 45, 14.7f);
        assertTrue(AutoSetup.passes(c45, measurement(100000, 5, 0, null, 90f)));
        assertFalse(AutoSetup.passes(c45, measurement(100000, 5, 0, null, 60f)));
        assertTrue(AutoSetup.passes(c45, measurement(100000, 5, 0, new int[]{0, 0, 0}, 90f)));
    }

    @Test
    public void noMeasurementNeverPasses() {
        AutoSetup.Candidate c = new AutoSetup.Candidate(1920, 1080, 45, 14.7f);
        assertFalse(AutoSetup.passes(c, null));
        AutoSetup.Measurement empty = measurement(0, 0, 0, null, 90f);
        empty.frames = 0;
        assertFalse(AutoSetup.passes(c, empty));
    }

    @Test
    public void decideTakesTheFirstPassingCandidate() {
        List<AutoSetup.Candidate> c = AutoSetup.candidates(TABLET, TABLET_HZ);
        AutoSetup.Measurement bad = measurement(100000, 5, 3.0, new int[]{5, 90, 5}, 90f);
        AutoSetup.Measurement good = measurement(125000, 6.5, 0, new int[]{2, 3, 95}, 90f); // 30 fps on 90 Hz: 3 refreshes
        AutoSetup.Decision d = AutoSetup.decide(c, Arrays.asList(bad, good));
        assertNotNull(d);
        assertEquals("1920x1200@30", d.candidate.toString());
        // 125 KB in 6.5 ms = 153.8 Mbit/s -> 53.8 Mbit/s
        assertEquals(53846, d.pyroWaveKbps);
    }

    @Test
    public void hostBusyOnlyForAnotherSession() {
        assertFalse(AutoSetup.hostBusy(0));
        // A test session still closing on the host does not block the next one
        assertFalse(AutoSetup.hostBusy(AutoSetup.TEST_PATTERN_APP_ID));
        assertTrue(AutoSetup.hostBusy(881448767));
    }

    @Test
    public void decideKeepsTheSettingsWhenNothingPasses() {
        List<AutoSetup.Candidate> c = AutoSetup.candidates(TABLET, TABLET_HZ);
        AutoSetup.Measurement bad = measurement(100000, 5, 3.0, new int[]{5, 90, 5}, 90f);
        assertNull(AutoSetup.decide(c, Arrays.asList(bad, bad, bad)));
        assertNull(AutoSetup.decide(c, Collections.<AutoSetup.Measurement>emptyList()));
    }
}
