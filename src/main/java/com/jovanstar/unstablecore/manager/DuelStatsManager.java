package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
        int elo = 1000;
        int rankedWins;
        int rankedLosses;
        int casualWins;
        int casualLosses;
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
            m.elo = row.elo() <= 0 ? 1000 : row.elo();
            m.rankedWins = row.rankedWins();
            m.rankedLosses = row.rankedLosses();
            m.casualWins = row.casualWins();
            m.casualLosses = row.casualLosses();
            stats.put(e.getKey(), m);
        }
    }

    private Mutable entry(UUID uuid) {
        return stats.computeIfAbsent(uuid, u -> new Mutable());
    }

    /** Records one player's side of a finished duel. Call once per participant, exactly once per duel. */
    public void recordDuelResult(UUID uuid, boolean won, boolean ranked, double wagered, double coinsWon, double coinsLost) {
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
                if (ranked) {
                    m.rankedWins++;
                } else {
                    m.casualWins++;
                }
            } else {
                m.losses++;
                m.currentStreak = 0;
                if (ranked) {
                    m.rankedLosses++;
                } else {
                    m.casualLosses++;
                }
            }
        }
        persistAsync(uuid, snapshot(m));
    }

    private DatabaseManager.DuelStatsRow snapshot(Mutable m) {
        synchronized (m) {
            return new DatabaseManager.DuelStatsRow(
                    m.wins, m.losses, m.currentStreak, m.bestStreak,
                    m.coinsWagered, m.coinsWon, m.coinsLost, m.duelsPlayed,
                    m.elo <= 0 ? 1000 : m.elo,
                    m.rankedWins, m.rankedLosses, m.casualWins, m.casualLosses
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

    public double getRankedWinRate(UUID uuid) {
        Mutable m = stats.get(uuid);
        if (m == null) {
            return 0;
        }
        int played = m.rankedWins + m.rankedLosses;
        return played <= 0 ? 0 : (m.rankedWins * 100.0) / played;
    }

    public double getCasualWinRate(UUID uuid) {
        Mutable m = stats.get(uuid);
        if (m == null) {
            return 0;
        }
        int played = m.casualWins + m.casualLosses;
        return played <= 0 ? 0 : (m.casualWins * 100.0) / played;
    }

    public int getElo(UUID uuid) {
        if (uuid == null) {
            return 1000;
        }
        Mutable m = stats.get(uuid);
        return m == null || m.elo <= 0 ? 1000 : m.elo;
    }

    // -----------------------------------------------------------------------------------
    // Leaderboard sourcing - snapshot views, same pattern as StatsManager.trackedKills()/
    // trackedBestStreaks(), so LeaderboardManager can rank duel stats the same way it ranks
    // FFA ones.
    // -----------------------------------------------------------------------------------

    public Map<UUID, Integer> trackedWins() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().wins > 0) {
                    out.put(e.getKey(), e.getValue().wins);
                }
            }
        }
        return out;
    }

    public Map<UUID, Integer> trackedRankedWins() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().rankedWins > 0) {
                    out.put(e.getKey(), e.getValue().rankedWins);
                }
            }
        }
        return out;
    }

    public Map<UUID, Integer> trackedCasualWins() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().casualWins > 0) {
                    out.put(e.getKey(), e.getValue().casualWins);
                }
            }
        }
        return out;
    }

    public Map<UUID, Integer> trackedBestStreaks() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().bestStreak > 0) {
                    out.put(e.getKey(), e.getValue().bestStreak);
                }
            }
        }
        return out;
    }

    public Map<UUID, Integer> trackedCoinsWon() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().coinsWon > 0) {
                    out.put(e.getKey(), (int) Math.floor(e.getValue().coinsWon));
                }
            }
        }
        return out;
    }

    /** Only includes players who've actually played a duel - everyone else sits at the default
     * 1000 rating, which would otherwise flood the leaderboard with people who never queued. */
    public Map<UUID, Integer> trackedElo() {
        Map<UUID, Integer> out = new java.util.HashMap<>();
        for (Map.Entry<UUID, Mutable> e : stats.entrySet()) {
            synchronized (e.getValue()) {
                if (e.getValue().duelsPlayed > 0) {
                    out.put(e.getKey(), e.getValue().elo <= 0 ? 1000 : e.getValue().elo);
                }
            }
        }
        return out;
    }

    public String getRankTier(int elo) {
        if (elo >= 2000) return "&d&lMaster";
        if (elo >= 1750) return "&b&lDiamond";
        if (elo >= 1500) return "&a&lPlatinum";
        if (elo >= 1250) return "&e&lGold";
        if (elo >= 1000) return "&f&lSilver";
        return "&7&lBronze";
    }

    public record EloChange(int winnerOld, int winnerNew, int winnerDelta,
                             int loserOld, int loserNew, int loserDelta) {
    }

    public EloChange recordRankedResult(UUID winner, UUID loser) {
        if (winner == null || loser == null || winner.equals(loser)) {
            return null;
        }
        Mutable mw = entry(winner);
        Mutable ml = entry(loser);
        int winnerOld, winnerNew, winnerDelta;
        int loserOld, loserNew, loserDelta;

        int kFactor = Math.max(1, plugin.getConfigManager().getDuels().getInt("ranked.k-factor", 32));

        synchronized (mw) {
            winnerOld = mw.elo <= 0 ? 1000 : mw.elo;
        }
        synchronized (ml) {
            loserOld = ml.elo <= 0 ? 1000 : ml.elo;
        }

        // Standard Elo probability
        double expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (loserOld - winnerOld) / 400.0));
        double expectedLoser = 1.0 / (1.0 + Math.pow(10.0, (winnerOld - loserOld) / 400.0));

        winnerDelta = (int) Math.round(kFactor * (1.0 - expectedWinner));
        loserDelta = (int) Math.round(kFactor * (0.0 - expectedLoser)); // negative

        winnerDelta = Math.max(1, winnerDelta);
        loserDelta = Math.min(-1, loserDelta);

        winnerNew = Math.max(100, winnerOld + winnerDelta);
        loserNew = Math.max(100, loserOld + loserDelta);

        synchronized (mw) {
            mw.elo = winnerNew;
        }
        synchronized (ml) {
            ml.elo = loserNew;
        }

        persistAsync(winner, snapshot(mw));
        persistAsync(loser, snapshot(ml));

        checkFarming(winner, loser);

        return new EloChange(winnerOld, winnerNew, winnerDelta, loserOld, loserNew, loserDelta);
    }

    // -----------------------------------------------------------------------------------
    // ELO-farming detection - "flag, don't auto-punish" per DUELS.md. Purely in-memory and
    // resets on restart, same as dailyWagerTracking below: this is a staff-visible signal
    // (surfaced via /dueladmin flags), not an economic safety boundary, so it doesn't need
    // to survive a restart the way escrow/payout state does.
    // -----------------------------------------------------------------------------------

    private record RankedMatch(long atMs, UUID winner) {
    }

    private static final class PairRecord {
        final Deque<RankedMatch> recent = new ArrayDeque<>();
        String flagReason;
        long flaggedAt;
    }

    public record FarmFlag(UUID playerA, UUID playerB, String reason, long flaggedAt, int recentMatches) {
    }

    private final Map<String, PairRecord> pairHistory = new ConcurrentHashMap<>();

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private void checkFarming(UUID winner, UUID loser) {
        if (!plugin.getConfigManager().getDuels().getBoolean("anti-farm.enabled", true)) {
            return;
        }
        PairRecord rec = pairHistory.computeIfAbsent(pairKey(winner, loser), k -> new PairRecord());
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, plugin.getConfigManager().getDuels().getInt("anti-farm.window-minutes", 30)) * 60_000L;

        synchronized (rec) {
            rec.recent.addLast(new RankedMatch(now, winner));
            while (!rec.recent.isEmpty() && now - rec.recent.peekFirst().atMs() > windowMs) {
                rec.recent.removeFirst();
            }

            int maxInWindow = Math.max(1, plugin.getConfigManager().getDuels().getInt("anti-farm.max-duels-per-window", 5));
            if (rec.recent.size() >= maxInWindow) {
                flag(rec, winner, loser, "high-frequency", rec.recent.size()
                        + " ranked duels between this pair in the last " + (windowMs / 60_000L) + "m");
                return;
            }

            int altThreshold = Math.max(2, plugin.getConfigManager().getDuels().getInt("anti-farm.alternating-threshold", 4));
            if (rec.recent.size() >= altThreshold && isAlternating(rec.recent, altThreshold)) {
                flag(rec, winner, loser, "alternating-wins", "last " + altThreshold
                        + " ranked duels between this pair alternated winners - possible win-trading");
            }
        }
    }

    /** True if the most recent {@code count} matches strictly alternate winners (A beats B, B beats A, ...). */
    private static boolean isAlternating(Deque<RankedMatch> recent, int count) {
        List<RankedMatch> list = new ArrayList<>(recent);
        if (list.size() < count) {
            return false;
        }
        List<RankedMatch> last = list.subList(list.size() - count, list.size());
        for (int i = 1; i < last.size(); i++) {
            if (last.get(i).winner().equals(last.get(i - 1).winner())) {
                return false;
            }
        }
        return true;
    }

    private void flag(PairRecord rec, UUID a, UUID b, String reasonCode, String detail) {
        boolean wasAlreadyFlagged = rec.flagReason != null;
        rec.flagReason = reasonCode + ": " + detail;
        rec.flaggedAt = System.currentTimeMillis();
        if (!wasAlreadyFlagged) {
            plugin.getLogger().warning("[Duel][AntiFarm] Possible ELO farming between " + a + " and " + b
                    + " - " + rec.flagReason + " - see /dueladmin flags");
        }
    }

    /** Staff-facing view for /dueladmin flags - most recently flagged pairs first. */
    public List<FarmFlag> activeFarmFlags() {
        List<FarmFlag> out = new ArrayList<>();
        for (Map.Entry<String, PairRecord> e : pairHistory.entrySet()) {
            PairRecord rec = e.getValue();
            synchronized (rec) {
                if (rec.flagReason == null) {
                    continue;
                }
                String[] parts = e.getKey().split("\\|", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    out.add(new FarmFlag(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                            rec.flagReason, rec.flaggedAt, rec.recent.size()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        out.sort(Comparator.comparingLong(FarmFlag::flaggedAt).reversed());
        return out;
    }

    /** Clears a specific pair's flag (e.g. after staff review clears them) - leaves match history intact. */
    public boolean clearFarmFlag(UUID a, UUID b) {
        PairRecord rec = pairHistory.get(pairKey(a, b));
        if (rec == null) {
            return false;
        }
        synchronized (rec) {
            if (rec.flagReason == null) {
                return false;
            }
            rec.flagReason = null;
            return true;
        }
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
