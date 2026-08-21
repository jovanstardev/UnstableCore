package com.jovanstar.unstablecore;

import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.EventManager;
import com.jovanstar.unstablecore.util.SmallCaps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the formatters players see in cooldown, reward and economy messages.
 * These run without a Bukkit server, so they are safe to execute in a normal build.
 */
class FormattingTest {

    @Test
    void durationFormatsAcrossUnitBoundaries() {
        assertEquals("0s", EventManager.formatDurationMillis(0));
        assertEquals("1s", EventManager.formatDurationMillis(1_000));
        assertEquals("59s", EventManager.formatDurationMillis(59_000));
        assertEquals("1m 0s", EventManager.formatDurationMillis(60_000));
        assertEquals("9m 59s", EventManager.formatDurationMillis(599_000));
        assertEquals("1h 0m", EventManager.formatDurationMillis(3_600_000));
        assertEquals("2h 30m", EventManager.formatDurationMillis(9_000_000));
    }

    @Test
    void durationNeverRendersNegativeTime() {
        // remainingMillis() clamps at zero, but a caller passing a stale timestamp must not be
        // shown something like "-3s" in a cooldown message.
        assertEquals("0s", EventManager.formatDurationMillis(-1));
        assertEquals("0s", EventManager.formatDurationMillis(-60_000));
        assertEquals("0s", EventManager.formatDurationMillis(Long.MIN_VALUE + 1));
    }

    @Test
    void durationHandlesSubSecondValues() {
        // Sub-second remainders round down rather than displaying a fraction.
        assertEquals("0s", EventManager.formatDurationMillis(1));
        assertEquals("0s", EventManager.formatDurationMillis(999));
    }

    @Test
    void perKitCooldownValuesFormatAsExpected() {
        // The cooldowns actually shipped in the kit files, as players see them in the kits menu.
        assertEquals("10m 0s", EventManager.formatDurationMillis(600_000));
        assertEquals("3m 0s", EventManager.formatDurationMillis(180_000));
        assertEquals("1m 0s", EventManager.formatDurationMillis(60_000));
    }

    @Test
    void economyFormatDropsTrailingZeroesOnWholeNumbers() {
        assertEquals("0", EconomyManager.format(0));
        assertEquals("10", EconomyManager.format(10));
        assertEquals("10.50", EconomyManager.format(10.5));
        assertEquals("-5", EconomyManager.format(-5));
    }

    @Test
    void economyFormatIsFiniteForExtremeValues() {
        // Reward and bounty maths can produce large doubles; the formatter must not throw.
        assertNotNull(EconomyManager.format(Double.MAX_VALUE));
        assertNotNull(EconomyManager.format(-Double.MAX_VALUE));
        assertNotNull(EconomyManager.formatCommas(Double.MAX_VALUE));
        assertNotNull(EconomyManager.formatCommas(0));
    }

    @Test
    void smallCapsHandlesNullAndEmpty() {
        assertEquals("", SmallCaps.of(null));
        assertEquals("", SmallCaps.of(""));
        assertNotNull(SmallCaps.of("Unstable"));
        assertTrue(SmallCaps.of("abc").length() >= 3);
    }
}
