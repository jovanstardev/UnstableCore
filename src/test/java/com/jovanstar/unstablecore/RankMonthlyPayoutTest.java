package com.jovanstar.unstablecore;

import com.jovanstar.unstablecore.manager.RewardsManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankMonthlyPayoutTest {

    @Test
    void rankMonthlyPayoutUsesHighestEligibleTier() {
        assertEquals(1100.0, RewardsManager.resolveRankMonthlyPayout(new String[] {"monthly.crow"}));
        assertEquals(2750.0, RewardsManager.resolveRankMonthlyPayout(new String[] {"monthly.crow", "monthly.talon"}));
        assertEquals(6500.0, RewardsManager.resolveRankMonthlyPayout(new String[] {"monthly.crow", "monthly.talon", "monthly.raven"}));
    }

    @Test
    void testThirtyDayCooldownAndUpgradeCalculations() {
        long cooldown = RewardsManager.RANK_MONTHLY_COOLDOWN_MS;
        long start = 1_000_000_000L;

        // 1. Initial claim: Player gets Crow (1,100 coins)
        double due = RewardsManager.calculatePayoutDue(1100.0, 0L, 0.0, start, cooldown);
        assertEquals(1100.0, due);

        // 2. Immediate check after getting Crow: should be 0 (cooldown active)
        due = RewardsManager.calculatePayoutDue(1100.0, start, 1100.0, start + 1000L, cooldown);
        assertEquals(0.0, due);

        // 3. 15 days later, still Crow: should be 0
        long fifteenDays = start + (15L * 24 * 60 * 60 * 1000L);
        due = RewardsManager.calculatePayoutDue(1100.0, start, 1100.0, fifteenDays, cooldown);
        assertEquals(0.0, due);

        // 4. 15 days later, player upgrades to Raven (6,500 coins): receives difference (5,400 coins)
        due = RewardsManager.calculatePayoutDue(6500.0, start, 1100.0, fifteenDays, cooldown);
        assertEquals(5400.0, due);

        // 5. Subsequent check after upgrade in the same cycle: should be 0
        due = RewardsManager.calculatePayoutDue(6500.0, start, 6500.0, fifteenDays + 5000L, cooldown);
        assertEquals(0.0, due);

        // 6. 31 days later (cooldown expired): should receive full 6,500 coins for new monthly cycle
        long thirtyOneDays = start + (31L * 24 * 60 * 60 * 1000L);
        due = RewardsManager.calculatePayoutDue(6500.0, start, 6500.0, thirtyOneDays, cooldown);
        assertEquals(6500.0, due);

        // 7. Downgrade from Raven to Talon within cycle: should be 0
        due = RewardsManager.calculatePayoutDue(2750.0, start, 6500.0, fifteenDays, cooldown);
        assertEquals(0.0, due);
    }
}
