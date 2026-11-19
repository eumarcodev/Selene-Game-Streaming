package com.limelight.nvstream.http;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostBusyTest {
    @Test
    public void busyFlagOnlyWhenOne() {
        assertTrue(HostBusy.busyWithOtherClient("1"));
        assertTrue(HostBusy.busyWithOtherClient(" 1 "));
        assertFalse(HostBusy.busyWithOtherClient("0"));
        assertFalse(HostBusy.busyWithOtherClient(""));
        // Older hosts do not send the tag
        assertFalse(HostBusy.busyWithOtherClient(null));
    }

    @Test
    public void claimGrantedUnlessTheHostSaysNo() {
        assertTrue(HostBusy.claimGranted("1"));
        assertFalse(HostBusy.claimGranted("0"));
        assertFalse(HostBusy.claimGranted(" 0 "));
        // Hosts without the extension keep the old behaviour
        assertTrue(HostBusy.claimGranted(null));
    }

    @Test
    public void recognisesTheHeliosRefusal() {
        assertTrue(HostBusy.isOtherDeviceRefusal(503,
                "The host is in use by another device. Quit or leave the host on that device first."));
    }

    @Test
    public void ignoresOtherErrors() {
        // Same status, other reason (encoder failure)
        assertFalse(HostBusy.isOtherDeviceRefusal(503, "Failed to initialize video capture/encoding. Is a display connected and turned on?"));
        // Same text, other status
        assertFalse(HostBusy.isOtherDeviceRefusal(400, "in use by another device"));
        assertFalse(HostBusy.isOtherDeviceRefusal(503, null));
    }
}
