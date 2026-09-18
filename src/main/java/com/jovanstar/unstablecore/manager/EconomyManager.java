package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;

public final class EconomyManager {

    private final UnstableCore plugin;
    private Economy economy;

    public EconomyManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault not found - economy features disabled.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().warning("No Vault economy provider found.");
            return false;
        }
        economy = rsp.getProvider();
        plugin.getLogger().info("Vault economy hooked: " + economy.getName());
        return true;
    }

    public boolean isReady() {
        return economy != null;
    }

    public double getBalance(OfflinePlayer player) {
        if (!isReady()) {
            return 0;
        }
        return economy.getBalance(player);
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        return deposit(player, amount, true);
    }

    /**
     * Give-back variant that does not touch the lifetime "coins earned" statistic.
     *
     * <p>Refunds are not income: counting them let a player inflate their earned-coins stat for
     * free by repeatedly triggering a take-then-refund path (e.g. offering a bounty stack that
     * exceeds the configured cap, or a refunded purchase) - the
     * balance nets out to zero every time while the statistic only ever climbs.
     */
    public boolean refund(OfflinePlayer player, double amount) {
        return deposit(player, amount, false);
    }

    public boolean deposit(OfflinePlayer player, double amount, boolean countAsEarned) {
        if (!isReady() || amount <= 0 || !Double.isFinite(amount)) {
            return false;
        }
        boolean ok = economy.depositPlayer(player, amount).transactionSuccess();
        if (ok && countAsEarned && player.getUniqueId() != null && plugin.getStatsManager() != null) {
            plugin.getStatsManager().addCoinsEarned(player.getUniqueId(), amount);
        }
        if (ok && plugin.getLeaderboardManager() != null && player.getUniqueId() != null) {
            plugin.getLeaderboardManager().syncBalance(
                    player.getUniqueId(), player.getName(), economy.getBalance(player));
        }
        if (ok && plugin.getActionBarManager() != null && player.getUniqueId() != null) {
            plugin.getActionBarManager().invalidate(player.getUniqueId());
        }
        return ok;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return isReady() && amount >= 0 && Double.isFinite(amount) && economy.getBalance(player) >= amount;
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (!isReady() || amount <= 0 || !Double.isFinite(amount)) {
            return false;
        }
        if (economy.getBalance(player) < amount) {
            return false;
        }
        boolean ok = economy.withdrawPlayer(player, amount).transactionSuccess();
        if (ok && plugin.getLeaderboardManager() != null && player.getUniqueId() != null) {
            plugin.getLeaderboardManager().syncBalance(
                    player.getUniqueId(), player.getName(), economy.getBalance(player));
        }
        return ok;
    }

    public boolean takeExact(OfflinePlayer player, double amount) {
        if (!isReady() || amount <= 0 || player == null || !Double.isFinite(amount)) {
            return false;
        }
        boolean ok = economy.withdrawPlayer(player, amount).transactionSuccess();
        if (!ok) {
            return false;
        }
        if (player.getUniqueId() != null && plugin.getStatsManager() != null) {
            plugin.getStatsManager().addCoinsSpent(player.getUniqueId(), amount);
        }
        if (plugin.getLeaderboardManager() != null && player.getUniqueId() != null) {
            plugin.getLeaderboardManager().syncBalance(
                    player.getUniqueId(), player.getName(), economy.getBalance(player));
        }
        if (plugin.getActionBarManager() != null && player.getUniqueId() != null) {
            plugin.getActionBarManager().invalidate(player.getUniqueId());
        }
        return true;
    }

    public boolean set(OfflinePlayer player, double amount) {
        if (!isReady() || !Double.isFinite(amount) || amount < 0) {
            return false;
        }
        double bal = economy.getBalance(player);
        boolean ok;
        if (bal > amount) {
            ok = economy.withdrawPlayer(player, bal - amount).transactionSuccess();
        } else if (bal < amount) {
            ok = economy.depositPlayer(player, amount - bal).transactionSuccess();
        } else {
            ok = true;
        }
        if (ok && plugin.getLeaderboardManager() != null && player.getUniqueId() != null) {
            plugin.getLeaderboardManager().syncBalance(
                    player.getUniqueId(), player.getName(), economy.getBalance(player));
        }
        return ok;
    }

    public boolean reset(OfflinePlayer player) {
        return set(player, 0);
    }

    public void rewardKill(Player killer, double base) {
        if (!isReady()) {
            return;
        }
        double multi = plugin.getEventManager().getMultiplier();
        if (plugin.getRewardsManager() != null
                && plugin.getRewardsManager().hasBooster(killer.getUniqueId())) {
            multi *= plugin.getRewardsManager().boosterMultiplier();
        }
        double amount = Math.floor(base * multi);
        if (amount <= 0) {
            return;
        }
        deposit(killer, amount);
        if (plugin.getSettingsManager() == null
                || plugin.getSettingsManager().isEnabled(killer, "coin_notices")) {
            com.jovanstar.unstablecore.util.MessageUtil.sendConfig(
                    killer,
                    "kill-reward",
                    java.util.Map.of("amount", format(amount))
            );
        }
    }

    // Both formatters pin Locale.ROOT. String.format without one follows the JVM default locale,
    // so on a host set to pt_BR (or de_DE, fr_FR, ...) "%,d" grouped with dots and "%.2f" used a
    // decimal comma: formatCommas literally stopped emitting commas, and the same coin balance
    // rendered differently depending on where the server happened to be running.
    public static String formatCommas(double amount) {
        long whole = (long) Math.floor(amount);
        return String.format(Locale.ROOT, "%,d", whole);
    }

    public static String format(double amount) {
        if (amount == (long) amount) {
            return String.valueOf((long) amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
