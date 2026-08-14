package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Duel-specific combat/economic statistics and history - deliberately separate from
 * {@link StatsManager} (FFA kills/coins) and from {@link DatabaseManager}'s FFA-oriented tables,
 * per DUELS.md's "separate stats table" requirement.
 */
public final class DuelStatsManager {

    private static final class Mutable {
        int wins;
        int losses;
        int currentStreak;
        int bestStreak;
        double coinsWagered;
        double coinsWon;
        double coinsLost;
        int duelsPlayed;
    }

    private final UnstableCore plugin;
    private final Map<UUID, Mutable> stats = new ConcurrentHashMap<>();

    public DuelStatsManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        stats.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        for (Map.Entry<UUID, DatabaseManager.DuelStatsRow> e : db.loadAllDuelStats().entrySet()) {
            DatabaseManager.DuelStatsRow row = e.getValue();
            Mutable m = new Mutable();
            m.wins = row.wins();
            m.losses = row.losses();
            m.currentStreak = row.currentStreak();
            m.bestStreak = row.bestStreak();
            m.coinsWagered = row.coinsWagered();
            m.coinsWon = row.coinsWon();
            m.coinsLost = row.coinsLost();
            m.duelsPlayed = row.duelsPlayed();
            stats.put(e.getKey(), m);
        }
    }

    private Mutable entry(UUID uuid) {
        return stats.computeIfAbsent(uuid, u -> new Mutable());
    }

    /** Records one player's side of a finished duel. Call once per participant, exactly once per duel. */
    public void recordDuelResult(UUID uuid, boolean won, double wagered, double coinsWon, double coinsLost) {
        if (uuid == null) {
            return;
        }
        Mutable m = entry(uuid);
        synchronized (m) {
            m.duelsPlayed++;
            m.coinsWagered += Math.max(0, wagered);
            m.coinsWon += Math.max(0, coinsWon);
            m.coinsLost += Math.max(0, coinsLost);
            if (won) {
                m.wins++;
                m.currentStreak++;
                m.bestStreak = Math.max(m.bestStreak, m.currentStreak);
            } else {
                m.losses++;
                m.currentStreak = 0;
            }
        }
        persistAsync(uuid, snapshot(m));
    }

    private DatabaseManager.DuelStatsRow snapshot(Mutable m) {
        synchronized (m) {
            return new DatabaseManager.DuelStatsRow(
                    m.wins, m.losses, m.currentStreak, m.bestStreak,
                    m.coinsWagered, m.coinsWon, m.coinsLost, m.duelsPlayed
            );
        }
    }

    private void persistAsync(UUID uuid, DatabaseManager.DuelStatsRow row) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getDatabaseManager().upsertDuelStats(uuid, row));
    }

    public int getWins(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.wins;
    }

    public int getLosses(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.losses;
    }

    public int getCurrentStreak(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.currentStreak;
    }

    public int getBestStreak(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.bestStreak;
    }

    public int getDuelsPlayed(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.duelsPlayed;
    }

    public double getCoinsWagered(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.coinsWagered;
    }

    public double getCoinsWon(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.coinsWon;
    }

    public double getCoinsLost(UUID uuid) {
        Mutable m = stats.get(uuid);
        return m == null ? 0 : m.coinsLost;
    }

    public double getWinRate(UUID uuid) {
        Mutable m = stats.get(uuid);
        if (m == null || m.duelsPlayed <= 0) {
            return 0;
        }
        return (m.wins * 100.0) / m.duelsPlayed;
    }

    /** In-memory, resets on restart by design - used only for the "flag, don't block" daily-limit signal. */
    private final Map<UUID, long[]> dailyWagerTracking = new ConcurrentHashMap<>();

    public double getDailyWagered(UUID uuid) {
        long[] entry = dailyWagerTracking.get(uuid);
        if (entry == null) {
            return 0;
        }
        long today = System.currentTimeMillis() / 86_400_000L;
        return entry[0] == today ? Double.longBitsToDouble(entry[1]) : 0;
    }

    public void addDailyWagered(UUID uuid, double amount) {
        if (uuid == null || amount <= 0) {
            return;
        }
        long today = System.currentTimeMillis() / 86_400_000L;
        dailyWagerTracking.compute(uuid, (u, entry) -> {
            double current = (entry != null && entry[0] == today) ? Double.longBitsToDouble(entry[1]) : 0;
            return new long[]{today, Double.doubleToLongBits(current + amount)};
        });
    }

    public void insertHistoryAsync(DatabaseManager.DuelHistoryRow row) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getDatabaseManager().insertDuelHistory(row));
    }

    public void loadHistoryPageAsync(UUID player, int page, int pageSize,
                                     Consumer<List<DatabaseManager.DuelHistoryRow>> onLoaded,
                                     Consumer<Integer> onCount) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(50, pageSize));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<DatabaseManager.DuelHistoryRow> rows =
                    plugin.getDatabaseManager().loadDuelHistory(player, safeSize, safePage * safeSize);
            int count = plugin.getDatabaseManager().countDuelHistory(player);
            Bukkit.getScheduler().runTask(plugin, () -> {
                onLoaded.accept(rows);
                onCount.accept(count);
            });
        });
    }
}
