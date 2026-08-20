package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.LeaderboardCategoryGui;
import com.jovanstar.unstablecore.gui.LeaderboardMenuGui;
import com.jovanstar.unstablecore.leaderboard.LeaderboardCategory;
import com.jovanstar.unstablecore.leaderboard.LeaderboardEntry;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LeaderboardManager {

    public record SearchSession(LeaderboardCategory category, int returnPage, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private record Cache(List<LeaderboardEntry> entries, long createdAtMs) {
    }

    private final UnstableCore plugin;
    private final Map<LeaderboardCategory, Cache> cache = new ConcurrentHashMap<>();
    private final Map<LeaderboardCategory, Object> computeLocks = new ConcurrentHashMap<>();
    private final Map<UUID, SearchSession> searchSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> openGeneration = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRefreshMs = new ConcurrentHashMap<>();
    private final Set<UUID> switchingGui = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();
    /**
     * Lower-cased name -> uuid reverse view of {@link #nameCache}. findUuidByName is on the
     * command path for /stats, /uc economy and /dueladmin, all of which are reachable often
     * enough (and, for /stats, by any player) that a linear scan over every player the server has
     * ever seen is not an acceptable per-call cost.
     */
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    // Raw DB rows backing the COINS/PLAYTIME categories, refreshed off-thread on a timer by
    // refreshDbRowsAsync. compute() and list() only read this cache and never touch the database,
    // so a cache expiry cannot block the main thread on a JDBC round trip - which under load
    // means the full 10s Hikari connection timeout.
    private final Map<LeaderboardCategory, List<DatabaseManager.ProfileRow>> dbRowsCache = new ConcurrentHashMap<>();
    private volatile boolean dbRefreshInFlight = false;
    private int syncTaskId = -1;

    public LeaderboardManager(UnstableCore plugin) {
        this.plugin = plugin;
        reloadNameCache();
        refreshDbRowsAsync();
        startProfileSync();
    }

    public FileConfiguration cfg() {
        return plugin.getConfigManager().getLeaderboard();
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    public int pageSize() {
        return Math.max(1, Math.min(45, cfg().getInt("page-size", 45)));
    }

    public long cacheTtlMs() {
        return Math.max(1L, cfg().getLong("cache-ttl-seconds", 45L)) * 1000L;
    }

    public int maxEntries() {
        return Math.max(50, cfg().getInt("max-entries", cfg().getInt("max-candidates", 500)));
    }

    public void clearCache() {
        cache.clear();
    }

    public void reloadNameCache() {
        nameCache.clear();
        nameToUuid.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        for (Map.Entry<UUID, String> e : db.loadAllProfileNames().entrySet()) {
            rememberName(e.getKey(), e.getValue());
        }
    }

    public void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        String previous = nameCache.put(uuid, name);
        if (previous != null && !previous.equalsIgnoreCase(name)) {
            // Renamed account: drop the stale reverse entry, but only if it still points at this
            // uuid - if someone else has since taken the old name, theirs must win.
            nameToUuid.remove(previous.toLowerCase(Locale.ROOT), uuid);
        }
        nameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    public UUID findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return nameToUuid.get(name.toLowerCase(Locale.ROOT));
    }

    public void syncProfile(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        rememberName(uuid, name);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        final double balance = plugin.getEconomyManager() != null && plugin.getEconomyManager().isReady()
                ? plugin.getEconomyManager().getBalance(player)
                : 0D;
        final long playtime = plugin.getPlaytimeManager() != null
                ? plugin.getPlaytimeManager().getPlaytimeTicks(player)
                : 0L;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                db.upsertProfile(uuid, name, balance, playtime));
    }

    public void syncBalance(UUID uuid, String name, double balance) {
        if (uuid == null) {
            return;
        }
        rememberName(uuid, name);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        String resolved = name != null && !name.isBlank()
                ? name
                : nameCache.getOrDefault(uuid, "");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                db.upsertProfileBalance(uuid, resolved, Math.max(0D, balance)));
    }

    public void rememberPlayer(Player player) {
        if (player == null) {
            return;
        }
        rememberName(player.getUniqueId(), player.getName());
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertProfileName(uuid, name));
    }

    private void startProfileSync() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
        }
        long period = Math.max(20L * 30L, Math.min(20L * 120L, cacheTtlMs() / 50L));
        syncTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // One batched write for the whole online set instead of one async task (and one
            // pooled connection) per player - see DatabaseManager.upsertProfiles. The balance and
            // playtime reads have to stay on this thread (Vault provider + player statistics are
            // main-thread APIs), so only the collection happens here.
            DatabaseManager db = plugin.getDatabaseManager();
            boolean economyReady = plugin.getEconomyManager() != null && plugin.getEconomyManager().isReady();
            List<DatabaseManager.ProfileRow> batch = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                rememberName(player.getUniqueId(), player.getName());
                if (db == null || !db.isConnected()) {
                    continue;
                }
                batch.add(new DatabaseManager.ProfileRow(
                        player.getUniqueId(),
                        player.getName(),
                        economyReady ? plugin.getEconomyManager().getBalance(player) : 0D,
                        plugin.getPlaytimeManager() != null
                                ? plugin.getPlaytimeManager().getPlaytimeTicks(player) : 0L
                ));
            }
            if (db != null && db.isConnected() && !batch.isEmpty()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertProfiles(batch));
            }
            refreshDbRowsAsync();
        }, period, period).getTaskId();
    }

    /** Off-thread refresh of the COINS/PLAYTIME DB row cache. Never called from the main thread's
     *  compute() path - only from this periodic timer and the constructor's initial warm-up. */
    private void refreshDbRowsAsync() {
        if (dbRefreshInFlight) {
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        dbRefreshInFlight = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                dbRowsCache.put(LeaderboardCategory.COINS, db.topBalances(maxEntries()));
                dbRowsCache.put(LeaderboardCategory.PLAYTIME, db.topPlaytimes(maxEntries()));
            } finally {
                dbRefreshInFlight = false;
            }
        });
    }

    public void shutdown() {
        if (syncTaskId != -1) {
            Bukkit.getScheduler().cancelTask(syncTaskId);
            syncTaskId = -1;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String name = player.getName();
            rememberName(uuid, name);
            DatabaseManager db = plugin.getDatabaseManager();
            if (db == null || !db.isConnected()) {
                continue;
            }
            double balance = plugin.getEconomyManager() != null && plugin.getEconomyManager().isReady()
                    ? plugin.getEconomyManager().getBalance(player)
                    : 0D;
            long playtime = plugin.getPlaytimeManager() != null
                    ? plugin.getPlaytimeManager().getPlaytimeTicks(player)
                    : 0L;
            db.upsertProfile(uuid, name, balance, playtime);
        }
    }

    public void clearSearch(UUID uuid) {
        searchSessions.remove(uuid);
        if (uuid != null) {
            openGeneration.remove(uuid);
            lastRefreshMs.remove(uuid);
            switchingGui.remove(uuid);
        }
    }

    public boolean isSwitchingGui(UUID uuid) {
        return uuid != null && switchingGui.contains(uuid);
    }

    private void openGuiSafely(Player player, long gen, Runnable open) {
        if (!player.isOnline() || gen != openGeneration.getOrDefault(player.getUniqueId(), -1L)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        switchingGui.add(uuid);
        try {
            player.setItemOnCursor(null);
            open.run();
            player.updateInventory();
        } finally {
            Bukkit.getScheduler().runTask(plugin, () -> switchingGui.remove(uuid));
        }
    }

    private long nextOpenGeneration(UUID uuid) {
        return openGeneration.merge(uuid, 1L, Long::sum);
    }

    public void invalidatePendingOpens(UUID uuid) {
        if (uuid != null && !isSwitchingGui(uuid)) {
            nextOpenGeneration(uuid);
        }
    }

    /**
     * How long a leaderboard search prompt keeps capturing chat. Bounded for the same reason as
     * the duel and bounty prompts: a search that is re-armed on too-short input would otherwise
     * swallow the player's chat indefinitely.
     */
    private long promptTimeoutMs() {
        return Math.max(5_000L, plugin.getConfig().getLong("chat-prompt-timeout-seconds", 60) * 1000L);
    }

    /** Reads the pending search session, discarding it first if it has expired. */
    public SearchSession peekSearch(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        SearchSession session = searchSessions.get(uuid);
        if (session == null) {
            return null;
        }
        if (session.isExpired()) {
            searchSessions.remove(uuid, session);
            return null;
        }
        return session;
    }

    public void beginSearch(Player player, LeaderboardCategory category, int page) {
        nextOpenGeneration(player.getUniqueId());
        searchSessions.put(player.getUniqueId(),
                new SearchSession(category, page, System.currentTimeMillis() + promptTimeoutMs()));
        msg(player, "search-prompt", Map.of());
    }

    public void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = cfg().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    public void openMenu(Player player) {
        if (!enabled()) {
            msg(player, "disabled", Map.of());
            return;
        }
        long gen = nextOpenGeneration(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> openGuiSafely(player, gen, () ->
                player.openInventory(new LeaderboardMenuGui(plugin, player).getInventory())));
    }

    public void openCategory(Player player, LeaderboardCategory category, int page) {
        if (!enabled()) {
            msg(player, "disabled", Map.of());
            return;
        }
        if (category == null || !isCategoryEnabled(category)) {
            openMenu(player);
            return;
        }
        long gen = nextOpenGeneration(player.getUniqueId());
        // list()/compute() reads Bukkit.getOnlinePlayers() and the Vault economy provider, which
        // must stay on the main thread - every caller here (commands, GUI clicks) already runs on
        // the main thread, so this is computed synchronously rather than hopping to an async task.
        // It's still safe to call from the main thread because compute() never touches the
        // database directly - see dbRowsCache/refreshDbRowsAsync().
        // The GUI-open itself still defers to the next tick, matching openMenu()'s behavior.
        List<LeaderboardEntry> entries = list(category);
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) pageSize()));
        int safePage = Math.max(0, Math.min(page, pages - 1));
        Bukkit.getScheduler().runTask(plugin, () -> openGuiSafely(player, gen, () ->
                player.openInventory(new LeaderboardCategoryGui(
                        plugin, player, category, safePage, entries).getInventory())));
    }

    public void refreshCategory(Player player, LeaderboardCategory category, int page) {
        long now = System.currentTimeMillis();
        Long last = lastRefreshMs.get(player.getUniqueId());
        if (last != null && now - last < 750L) {
            return;
        }
        lastRefreshMs.put(player.getUniqueId(), now);
        cache.remove(category);
        openCategory(player, category, page);
    }

    public boolean isCategoryEnabled(LeaderboardCategory category) {
        return cfg().getBoolean("categories." + category.id() + ".enabled", true);
    }

    public List<LeaderboardEntry> list(LeaderboardCategory category) {
        long ttl = cacheTtlMs();
        long now = System.currentTimeMillis();
        Cache c = cache.get(category);
        if (c != null && now - c.createdAtMs() < ttl) {
            return c.entries();
        }
        Object lock = computeLocks.computeIfAbsent(category, k -> new Object());
        synchronized (lock) {
            now = System.currentTimeMillis();
            c = cache.get(category);
            if (c != null && now - c.createdAtMs() < ttl) {
                return c.entries();
            }
            List<LeaderboardEntry> computed = compute(category);
            cache.put(category, new Cache(computed, System.currentTimeMillis()));
            return computed;
        }
    }

    private List<LeaderboardEntry> compute(LeaderboardCategory category) {
        if (category == LeaderboardCategory.COINS && !plugin.getEconomyManager().isReady()) {
            plugin.getLogger().warning("Vault economy unavailable - coins leaderboard empty.");
            return List.of();
        }

        return switch (category) {
            case COINS -> rank(computeCoins());
            case PLAYTIME -> rank(computePlaytime());
            case KILLS -> rank(computeStatMap(plugin.getStatsManager().trackedKills(), true));
            case BIGGEST_KILLSTREAK -> rank(computeStatMap(plugin.getStatsManager().trackedBestStreaks(), true));
            case DEATHS -> rank(computeStatMap(plugin.getKillstreakManager().trackedDeaths(), true));
            // Every duel category resolves through duelStats(), which returns null when duels are
            // switched off - those boards then render empty instead of throwing on every open.
            case DUEL_WINS -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedWins(), true));
            case DUEL_BEST_STREAK -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedBestStreaks(), true));
            case DUEL_COINS_WON -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedCoinsWon(), true));
            case DUEL_ELO -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedElo(), true));
            case RANKED_DUEL_WINS -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedRankedWins(), true));
            case CASUAL_DUEL_WINS -> rank(duelStats() == null
                    ? Map.of() : computeStatMap(duelStats().trackedCasualWins(), true));
        };
    }

    /** Duel stats, or null when duels.enabled is false and no duel manager was created. */
    private com.jovanstar.unstablecore.manager.DuelStatsManager duelStats() {
        return plugin.getDuelManager() == null ? null : plugin.getDuelManager().getDuelStatsManager();
    }

    private Map<UUID, Double> computeCoins() {
        Map<UUID, Double> scores = new HashMap<>();
        List<DatabaseManager.ProfileRow> rows = dbRowsCache.get(LeaderboardCategory.COINS);
        if (rows != null) {
            for (DatabaseManager.ProfileRow row : rows) {
                if (row.name() != null && !row.name().isBlank()) {
                    rememberName(row.uuid(), row.name());
                }
                if (row.balance() > 0D) {
                    scores.put(row.uuid(), row.balance());
                }
            }
        }
        if (plugin.getEconomyManager() != null && plugin.getEconomyManager().isReady()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                double bal = plugin.getEconomyManager().getBalance(online);
                rememberName(online.getUniqueId(), online.getName());
                if (bal > 0D) {
                    scores.put(online.getUniqueId(), bal);
                } else {
                    scores.remove(online.getUniqueId());
                }
            }
        }
        return scores;
    }

    private Map<UUID, Double> computePlaytime() {
        Map<UUID, Double> scores = new HashMap<>();
        List<DatabaseManager.ProfileRow> rows = dbRowsCache.get(LeaderboardCategory.PLAYTIME);
        if (rows != null) {
            for (DatabaseManager.ProfileRow row : rows) {
                if (row.name() != null && !row.name().isBlank()) {
                    rememberName(row.uuid(), row.name());
                }
                if (row.playtimeTicks() > 0L) {
                    scores.put(row.uuid(), (double) row.playtimeTicks());
                }
            }
        }
        if (plugin.getPlaytimeManager() != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                long ticks = plugin.getPlaytimeManager().getPlaytimeTicks(online);
                rememberName(online.getUniqueId(), online.getName());
                if (ticks > 0L) {
                    scores.put(online.getUniqueId(), (double) ticks);
                }
            }
        }
        return scores;
    }

    private Map<UUID, Double> computeStatMap(Map<UUID, Integer> values, boolean preferLive) {
        Map<UUID, Double> scores = new HashMap<>();
        if (values != null) {
            for (Map.Entry<UUID, Integer> e : values.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    scores.put(e.getKey(), e.getValue().doubleValue());
                }
            }
        }
        if (preferLive) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                rememberName(online.getUniqueId(), online.getName());
            }
        }
        return scores;
    }

    private List<LeaderboardEntry> rank(Map<UUID, Double> scores) {
        int limit = maxEntries();
        List<LeaderboardEntry> rows = new ArrayList<>(Math.min(scores.size(), limit));
        for (Map.Entry<UUID, Double> e : scores.entrySet()) {
            double value = e.getValue() == null ? 0D : e.getValue();
            if (value <= 0D) {
                continue;
            }
            String name = resolveName(e.getKey());
            if (name == null || name.isBlank()) {
                continue;
            }
            rows.add(new LeaderboardEntry(0, e.getKey(), name, value));
        }
        rows.sort(Comparator.comparingDouble(LeaderboardEntry::value).reversed()
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER));
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<LeaderboardEntry> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardEntry e = rows.get(i);
            ranked.add(new LeaderboardEntry(i + 1, e.uuid(), e.name(), e.value()));
        }
        return List.copyOf(ranked);
    }

    /**
     * Non-blocking uuid -> name lookup: online player, then the profile-name cache, then nothing.
     *
     * <p>Never calls {@code Bukkit.getOfflinePlayer(uuid).getName()}. For a UUID that isn't in the
     * server's local usercache that call falls through to a blocking Mojang HTTP request, and this
     * method runs once per leaderboard entry - up to {@code max-entries} (500 by default) of them,
     * on the main thread, every time a category's cache expires. One cold leaderboard open could
     * therefore stall the server for as long as 500 sequential web requests take.
     *
     * <p>Unknown names are resolved in the background instead (see {@link #resolveNameAsync}) and
     * simply appear on the next refresh; {@code rank()} already drops entries with no name.
     */
    private String resolveName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            rememberName(uuid, online.getName());
            return online.getName();
        }
        String cached = nameCache.get(uuid);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        resolveNameAsync(uuid);
        return null;
    }

    /** UUIDs currently being resolved off-thread, or already known to be unresolvable, so a
     *  repeatedly-refreshed leaderboard doesn't re-queue the same lookups every cycle. */
    private final Set<UUID> nameLookupsDone = ConcurrentHashMap.newKeySet();

    private void resolveNameAsync(UUID uuid) {
        if (uuid == null || !nameLookupsDone.add(uuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null || name.isBlank()) {
                return;
            }
            rememberName(uuid, name);
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.isConnected()) {
                db.upsertProfileName(uuid, name);
            }
        });
    }

    /**
     * Best-effort, never-blocking name for a UUID - for callers that just need something to show.
     * Returns null when nothing is cached, leaving the caller to pick its own placeholder.
     */
    public String cachedName(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            rememberName(uuid, online.getName());
            return online.getName();
        }
        String cached = nameCache.get(uuid);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        resolveNameAsync(uuid);
        return null;
    }

    public boolean handleSearchChat(Player player, String raw) {
        SearchSession session = peekSearch(player.getUniqueId());
        if (session == null) {
            return false;
        }
        searchSessions.remove(player.getUniqueId());
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("c")) {
            msg(player, "search-cancelled", Map.of());
            openCategory(player, session.category(), session.returnPage());
            return true;
        }
        if (text.length() < 2) {
            msg(player, "search-too-short", Map.of());
            // Re-arm with a fresh window rather than carrying the original deadline forward.
            searchSessions.put(player.getUniqueId(), new SearchSession(
                    session.category(), session.returnPage(), System.currentTimeMillis() + promptTimeoutMs()));
            return true;
        }
        String needle = text.toLowerCase(Locale.ROOT);
        List<LeaderboardEntry> entries = list(session.category());
        int foundRank = -1;
        for (LeaderboardEntry e : entries) {
            if (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(needle)) {
                foundRank = e.rank();
                break;
            }
        }
        if (foundRank < 1) {
            msg(player, "search-not-found", Map.of());
            openCategory(player, session.category(), session.returnPage());
            return true;
        }
        int page = (foundRank - 1) / pageSize();
        msg(player, "search-jump", Map.of("page", String.valueOf(page + 1)));
        openCategory(player, session.category(), page);
        return true;
    }
}
