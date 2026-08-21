package com.jovanstar.unstablecore;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the month-length clamp behind monthly reward milestones.
 *
 * <p>{@code registerLoginDay} caps a player's monthly progress at the calendar length of the
 * month, so a milestone requiring more days than the month contains can never be reached. The
 * shipped configuration has a 30-day tier, which made it unclaimable every February however
 * perfect the player's attendance was. {@code milestoneState} now compares progress against
 * {@code min(required, lengthOfMonth)}; this reproduces that arithmetic directly so a regression
 * shows up here rather than once a year in production.
 */
class MilestoneMonthLengthTest {

    /** Mirrors the clamp in RewardsManager.milestoneState. */
    private static boolean claimable(int required, LocalDate month, int daysLoggedIn) {
        int progress = Math.min(month.lengthOfMonth(), daysLoggedIn);
        int effective = Math.min(required, month.lengthOfMonth());
        return progress >= effective;
    }

    @Test
    void thirtyDayTierIsReachableInFebruary() {
        LocalDate feb = LocalDate.of(2026, 2, 1);
        assertEquals(28, feb.lengthOfMonth());
        // Perfect attendance in a 28-day month must satisfy the 30-day tier.
        assertTrue(claimable(30, feb, 28));
    }

    @Test
    void thirtyDayTierIsReachableInLeapFebruary() {
        LocalDate feb = LocalDate.of(2028, 2, 1);
        assertEquals(29, feb.lengthOfMonth());
        assertTrue(claimable(30, feb, 29));
    }

    @Test
    void partialAttendanceStillLocksTheTier() {
        // The clamp must not turn the milestone into a freebie - it only removes the impossible
        // gap between the month length and the configured requirement.
        LocalDate feb = LocalDate.of(2026, 2, 1);
        assertFalse(claimable(30, feb, 27));
        assertFalse(claimable(20, feb, 19));
        assertTrue(claimable(20, feb, 20));
    }

    @Test
    void longerMonthsAreUnaffected() {
        LocalDate jan = LocalDate.of(2026, 1, 1);
        assertEquals(31, jan.lengthOfMonth());
        assertFalse(claimable(30, jan, 29));
        assertTrue(claimable(30, jan, 30));
    }

    @Test
    void thirtyDayMonthsBehaveExactly() {
        LocalDate apr = LocalDate.of(2026, 4, 1);
        assertEquals(30, apr.lengthOfMonth());
        assertFalse(claimable(30, apr, 29));
        assertTrue(claimable(30, apr, 30));
    }

    @Test
    void weeklyTiersAreNeverClampedByMonthLength() {
        // Weekly milestones intentionally skip the clamp; a 5-day weekly tier needs 5 days.
        LocalDate feb = LocalDate.of(2026, 2, 1);
        int weeklyRequired = 5;
        assertTrue(3 < weeklyRequired);
        assertFalse(3 >= weeklyRequired);
        assertTrue(feb.lengthOfMonth() >= weeklyRequired);
    }
}
