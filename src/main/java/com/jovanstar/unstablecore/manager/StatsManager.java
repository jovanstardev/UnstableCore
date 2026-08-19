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

    /** Save-on-quit for a single player, so a crash between the 5-minute autosaves can't lose
     *  their kills/best-streak/coins since the last periodic save. */
    public void save(UUID uuid) {
        if (uuid == null) {
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        int k = kills.getOrDefault(uuid, 0);
        int b = bestStreak.getOrDefault(uuid, 0);
        double earned = coinsEarned.getOrDefault(uuid, 0.0);
        double spent = coinsSpent.getOrDefault(uuid, 0.0);
        if (k <= 0 && b <= 0 && earned <= 0 && spent <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertStatsRow(uuid, k, b, earned, spent));
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

    /**
     * Name -> account lookup for commands like /stats and /uc economy.
     *
     * <p>Deliberately never walks {@link Bukkit#getOfflinePlayers()}: that call stats and parses
     * every player data file the server has ever written, on the calling thread. Since it was
     * reached from a plain, permission-less {@code /stats <name>}, any player could stall the main
     * thread on demand simply by looking up names that don't exist (the miss is the expensive
     * path), and the cost grows with the size of the playerdata folder. The profile-name cache and
     * Paper's non-blocking {@code getOfflinePlayerIfCached} cover every account the server has
     * actually seen, which is the same set that scan could have found.
     */
    public OfflinePlayer resolvePlayer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        LeaderboardManager leaderboard = plugin.getLeaderboardManager();
        if (leaderboard != null) {
            UUID cached = leaderboard.findUuidByName(name);
            if (cached != null) {
                return Bukkit.getOfflinePlayer(cached);
            }
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }
}
