package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EvenFrameRateTest {
    @Test
    public void suggestsHalfWhenThePanelRefusesTheStreamRate() {
        // Redmi Pad 2: 60 fps requested, the panel stays at 90 Hz
        assertEquals(45, EvenFrameRate.suggest(60, 90f, false));
        assertEquals(60, EvenFrameRate.suggest(90, 120f, false));
    }

    @Test
    public void noSuggestionWhenTheStreamRateDividesThePanelRate() {
        assertEquals(0, EvenFrameRate.suggest(60, 60f, false));
        assertEquals(0, EvenFrameRate.suggest(60, 120f, false));
        assertEquals(0, EvenFrameRate.suggest(45, 90f, false));
        // A 59.94 Hz panel counts as 60
        assertEquals(0, EvenFrameRate.suggest(60, 59.94f, false));
    }

    @Test
    public void noSuggestionWithLowestLatencyPacing() {
        assertEquals(0, EvenFrameRate.suggest(60, 90f, true));
    }

    @Test
    public void noSuggestionBelowTheMinimum() {
        // Half of 50 Hz is 25
        assertEquals(0, EvenFrameRate.suggest(40, 50f, false));
    }

    @Test
    public void noSuggestionWhenHalfIsNotLowerThanTheStream() {
        // 40 fps on 90 Hz: half (45) is higher than the stream rate
        assertEquals(0, EvenFrameRate.suggest(40, 90f, false));
    }

    @Test
    public void noSuggestionForUnknownValues() {
        assertEquals(0, EvenFrameRate.suggest(0, 90f, false));
        assertEquals(0, EvenFrameRate.suggest(60, 0f, false));
    }

    @Test
    public void shownOncePerHostUntilTheSettingChanges() {
        assertTrue(EvenFrameRate.shouldShow(null, "60"));
        assertFalse(EvenFrameRate.shouldShow("60", "60"));
        assertTrue(EvenFrameRate.shouldShow("60", "90"));
    }
}
