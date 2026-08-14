package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StatsManager {

    private final UnstableCore plugin;
    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> bestStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Double> coinsEarned = new ConcurrentHashMap<>();
    private final Map<UUID, Double> coinsSpent = new ConcurrentHashMap<>();

    public StatsManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        kills.clear();
        bestStreak.clear();
        coinsEarned.clear();
        coinsSpent.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        for (Map.Entry<UUID, DatabaseManager.StatsRow> e : db.loadAllStats().entrySet()) {
            DatabaseManager.StatsRow row = e.getValue();
            if (row.kills() > 0) {
                kills.put(e.getKey(), row.kills());
            }
            if (row.bestStreak() > 0) {
                bestStreak.put(e.getKey(), row.bestStreak());
            }
            if (row.coinsEarned() > 0) {
                coinsEarned.put(e.getKey(), row.coinsEarned());
            }
            if (row.coinsSpent() > 0) {
                coinsSpent.put(e.getKey(), row.coinsSpent());
            }
        }
    }

    public void save() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        db.saveAllStats(kills, bestStreak, coinsEarned, coinsSpent);
    }

    public int getKills(UUID uuid) {
        return kills.getOrDefault(uuid, 0);
    }

    public int addKill(UUID uuid) {
        return kills.merge(uuid, 1, Integer::sum);
    }

    public int getBestStreak(UUID uuid) {
        return bestStreak.getOrDefault(uuid, 0);
    }

    public java.util.Set<UUID> trackedUuids() {
        java.util.Set<UUID> set = new java.util.HashSet<>(kills.keySet());
        set.addAll(bestStreak.keySet());
        set.addAll(coinsEarned.keySet());
        set.addAll(coinsSpent.keySet());
        return set;
    }

    public Map<UUID, Integer> trackedKills() {
        return java.util.Collections.unmodifiableMap(kills);
    }

    public Map<UUID, Integer> trackedBestStreaks() {
        return java.util.Collections.unmodifiableMap(bestStreak);
    }

    public void updateBestStreak(UUID uuid, int streak) {
        bestStreak.merge(uuid, streak, Math::max);
    }

    public double getCoinsEarned(UUID uuid) {
        return coinsEarned.getOrDefault(uuid, 0.0);
    }

    public void addCoinsEarned(UUID uuid, double amount) {
        if (amount <= 0) {
            return;
        }
        coinsEarned.merge(uuid, amount, Double::sum);
    }

    public double getCoinsSpent(UUID uuid) {
        return coinsSpent.getOrDefault(uuid, 0.0);
    }

    public void addCoinsSpent(UUID uuid, double amount) {
        if (amount <= 0) {
            return;
        }
        coinsSpent.merge(uuid, amount, Double::sum);
    }

    public double getKdr(UUID uuid) {
        int k = getKills(uuid);
        int d = plugin.getKillstreakManager().getDeaths(uuid);
        if (d <= 0) {
            return k;
        }
        return (double) k / (double) d;
    }

    public static String formatKdr(double kdr) {
        return String.format(java.util.Locale.US, "%.2f", kdr);
    }

    public static String formatPlaytime(Player player) {
        if (player == null) {
            return "0m";
        }
        long ticks = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE);
        long totalSec = ticks / 20L;
        long d = totalSec / 86400;
        long h = (totalSec % 86400) / 3600;
        long m = (totalSec % 3600) / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m";
    }

    public OfflinePlayer resolvePlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }
}
