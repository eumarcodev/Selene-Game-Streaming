package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PyroWaveBitrateTest {
    @Test
    public void unknownLinkKeepsTheVideoBasedValue() {
        assertEquals(50000, PyroWaveBitrate.autoKbps(20000, 0));
        assertEquals(150000, PyroWaveBitrate.autoKbps(80000, -1));
    }

    @Test
    public void neverBelowTheVideoBitrateWithoutALinkLimit() {
        // 2.5 x 200 Mbit/s is capped at 150, but the video bitrate itself is kept
        assertEquals(200000, PyroWaveBitrate.autoKbps(200000, 0));
    }

    @Test
    public void wifiLinkLimitsTheBitrate() {
        // Redmi Pad 2 measurements: 433 Mbit/s link, about 60 Mbit/s keeps a frame well under the period
        assertEquals(60620, PyroWaveBitrate.autoKbps(60000, 433));
        assertEquals(54600, PyroWaveBitrate.autoKbps(60000, 390));
    }

    @Test
    public void fastLinkKeepsTheVideoBasedValue() {
        // A 2400 Mbit/s Wi-Fi 6 link allows 336 Mbit/s, more than the video based 150
        assertEquals(150000, PyroWaveBitrate.autoKbps(60000, 2400));
        assertEquals(50000, PyroWaveBitrate.autoKbps(20000, 2400));
    }

    @Test
    public void slowLinkStopsAtTheFloor() {
        // 2.4 GHz at 72 Mbit/s would give 10 Mbit/s; PyroWave is not usable that low
        assertEquals(PyroWaveBitrate.MIN_AUTO_KBPS, PyroWaveBitrate.autoKbps(60000, 72));
        // but the floor never raises a value that was already lower
        assertEquals(10000, PyroWaveBitrate.autoKbps(4000, 72));
    }
}
