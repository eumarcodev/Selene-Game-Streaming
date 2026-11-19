package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PyroWaveBudgetControllerTest {
    // 150 Mbit/s at 60 fps: 312500 bytes per frame; frame period 16.7 ms, high 7.5 ms, low 4.2 ms
    private static final long START = 312500;
    private static final long NO = PyroWaveBudgetController.NO_CHANGE;

    private static PyroWaveBudgetController at60() {
        return new PyroWaveBudgetController(START, 60);
    }

    @Test
    public void budgetBytesFromBitrateAndFps() {
        assertEquals(312500, PyroWaveBudgetController.budgetBytes(150000, 60, 0));
        assertEquals(41666, PyroWaveBudgetController.budgetBytes(20000, 60, 0));
        // The client frame size cap wins when smaller
        assertEquals(200 * 1024, PyroWaveBudgetController.budgetBytes(150000, 60, 200));
        assertEquals(312500, PyroWaveBudgetController.budgetBytes(150000, 60, 4096));
        // No division by zero
        assertEquals(18750000, PyroWaveBudgetController.budgetBytes(150000, 0, 0));
    }

    @Test
    public void floorIs20MbitEquivalentButNotAboveStart() {
        assertEquals(41666, at60().getMinBudget());
        assertEquals(20833, new PyroWaveBudgetController(START, 120).getMinBudget());
        assertEquals(30000, new PyroWaveBudgetController(30000, 60).getMinBudget());
    }

    @Test
    public void lowersAfterTwoSlowSeconds() {
        PyroWaveBudgetController c = at60();
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(250000, c.onSecond(60, 15.4));
        // The count starts again after a change
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(200000, c.onSecond(60, 15.4));
    }

    @Test
    public void oneSlowSecondIsNotEnough() {
        PyroWaveBudgetController c = at60();
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(NO, c.onSecond(60, 6.0)); // between the thresholds: resets the count
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(250000, c.onSecond(60, 15.4));
    }

    @Test
    public void justUnderTheHighThresholdDoesNotLower() {
        PyroWaveBudgetController c = at60();
        assertEquals(NO, c.onSecond(60, 7.49));
        assertEquals(NO, c.onSecond(60, 7.49));
        assertEquals(START, c.getBudget());
    }

    @Test
    public void neverBelowTheFloor() {
        PyroWaveBudgetController c = at60();
        for (int i = 0; i < 40; i++) {
            c.onSecond(60, 30);
        }
        assertEquals(41666, c.getBudget());
        // Already at the floor: nothing to send
        assertEquals(NO, c.onSecond(60, 30));
        assertEquals(NO, c.onSecond(60, 30));
    }

    @Test
    public void raisesAfterTenFastSecondsOnlyBelowStart() {
        PyroWaveBudgetController c = at60();
        // At the start budget a fast link never raises it
        for (int i = 0; i < 20; i++) {
            assertEquals(NO, c.onSecond(60, 2.0));
        }
        c.onSecond(60, 15.4);
        assertEquals(250000, c.onSecond(60, 15.4));
        for (int i = 0; i < 9; i++) {
            assertEquals(NO, c.onSecond(60, 2.0));
        }
        assertEquals(275000, c.onSecond(60, 2.0));
    }

    @Test
    public void raiseStopsAtTheStartBudget() {
        PyroWaveBudgetController c = at60();
        c.onSecond(60, 15.4);
        c.onSecond(60, 15.4); // 250000
        c.onSecond(60, 15.4);
        c.onSecond(60, 15.4); // 200000
        long last = c.getBudget();
        for (int i = 0; i < 200; i++) {
            long b = c.onSecond(60, 2.0);
            if (b != NO) {
                last = b;
            }
        }
        assertEquals(START, last);
        assertEquals(START, c.getBudget());
    }

    @Test
    public void slowSecondResetsTheFastCount() {
        PyroWaveBudgetController c = at60();
        c.onSecond(60, 15.4);
        c.onSecond(60, 15.4); // 250000
        for (int i = 0; i < 9; i++) {
            c.onSecond(60, 2.0);
        }
        assertEquals(NO, c.onSecond(60, 15.4));
        for (int i = 0; i < 9; i++) {
            assertEquals(NO, c.onSecond(60, 2.0));
        }
        assertEquals(275000, c.onSecond(60, 2.0));
    }

    @Test
    public void secondsWithoutFramesDoNotCount() {
        PyroWaveBudgetController c = at60();
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(NO, c.onSecond(0, 0));
        assertEquals(NO, c.onSecond(60, 15.4));
        assertEquals(250000, c.onSecond(60, 15.4));
    }

    @Test
    public void stillScreenSecondsDoNotCount() {
        // Still desktop on the tablet (2026-11-19): about 12 repeated frames a second with the 4x still screen
        // budget, 20-80 ms each to arrive. That must never lower the budget.
        PyroWaveBudgetController c = at60();
        for (int i = 0; i < 30; i++) {
            assertEquals(NO, c.onSecond(12, 81.8));
        }
        assertEquals(START, c.getBudget());
        // Under half the frame rate does not count, half does
        assertEquals(NO, c.onSecond(29, 15.4));
        assertEquals(NO, c.onSecond(29, 15.4));
        assertEquals(NO, c.onSecond(30, 15.4));
        assertEquals(250000, c.onSecond(30, 15.4));
    }

    @Test
    public void stillScreenInterruptsTheFastCount() {
        PyroWaveBudgetController c = at60();
        c.onSecond(60, 15.4);
        c.onSecond(60, 15.4); // 250000
        for (int i = 0; i < 9; i++) {
            c.onSecond(60, 2.0);
        }
        assertEquals(NO, c.onSecond(5, 2.0)); // a still second resets the count
        for (int i = 0; i < 9; i++) {
            assertEquals(NO, c.onSecond(60, 2.0));
        }
        assertEquals(275000, c.onSecond(60, 2.0));
    }

    @Test
    public void tabletAt150MbitConvergesWithinTenSeconds() {
        // Measured transit on the tablet at 1080p60 (2026-11-18): 150 -> 15.4 ms, 100 -> 12.8, 80 -> 11.5,
        // 60 -> 7.9, 40 -> 3.9. Interpolated in between; the controller must get under 7.5 ms within 10 s.
        PyroWaveBudgetController c = at60();
        int seconds = 0;
        while (seconds < 10 && transitMs(c.getBudget()) > 7.5) {
            c.onSecond(60, transitMs(c.getBudget()));
            seconds++;
        }
        assertEquals(true, transitMs(c.getBudget()) <= 7.5);
        assertEquals(true, seconds <= 10);
        // And it does not oscillate afterwards: two minutes without a change
        long settled = c.getBudget();
        for (int i = 0; i < 120; i++) {
            assertEquals(NO, c.onSecond(60, transitMs(c.getBudget())));
        }
        assertEquals(settled, c.getBudget());
    }

    private static double transitMs(long budgetBytes) {
        double mbps = budgetBytes * 8.0 * 60 / 1e6;
        double[][] points = {{20, 2.1}, {40, 3.9}, {60, 7.9}, {80, 11.5}, {100, 12.8}, {150, 15.4}};
        if (mbps <= points[0][0]) {
            return points[0][1];
        }
        for (int i = 1; i < points.length; i++) {
            if (mbps <= points[i][0]) {
                double f = (mbps - points[i - 1][0]) / (points[i][0] - points[i - 1][0]);
                return points[i - 1][1] + f * (points[i][1] - points[i - 1][1]);
            }
        }
        return points[points.length - 1][1];
    }
}
