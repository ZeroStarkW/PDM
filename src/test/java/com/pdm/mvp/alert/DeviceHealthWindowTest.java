package com.pdm.mvp.alert;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeviceHealthWindowTest {
    @Test
    void confirmsFourOfFiveLowScoresAndIgnoresOneTransientSpike() {
        DeviceHealthWindow window = new DeviceHealthWindow();
        long timestamp = 1_000_000L;

        assertFalse(window.observe(1L, 7L, timestamp, 40));
        assertFalse(window.observe(1L, 7L, timestamp + 1_000, 100));
        assertFalse(window.observe(1L, 7L, timestamp + 2_000, 100));
        assertFalse(window.observe(1L, 7L, timestamp + 3_000, 100));
        assertFalse(window.observe(1L, 7L, timestamp + 4_000, 100));

        window.reset(1L, 7L);
        assertFalse(window.observe(1L, 7L, timestamp + 10_000, 40));
        assertFalse(window.observe(1L, 7L, timestamp + 11_000, 35));
        assertFalse(window.observe(1L, 7L, timestamp + 12_000, 100));
        assertFalse(window.observe(1L, 7L, timestamp + 13_000, 32));
        assertTrue(window.observe(1L, 7L, timestamp + 14_000, 30));
    }
}
