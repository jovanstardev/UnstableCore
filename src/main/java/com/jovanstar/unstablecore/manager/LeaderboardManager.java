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

    public record SearchSession(LeaderboardCategory category, int returnPage) {
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
    private int syncTaskId = -1;

    public LeaderboardManager(UnstableCore plugin) {
        this.plugin = plugin;
        reloadNameCache();
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
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        nameCache.putAll(db.loadAllProfileNames());
    }

    public void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        nameCache.put(uuid, name);
    }

    public UUID findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Map.Entry<UUID, String> e : nameCache.entrySet()) {
            if (e.getValue() != null && e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
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
            for (Player player : Bukkit.getOnlinePlayers()) {
                syncProfile(player);
            }
        }, period, period).getTaskId();
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

    public SearchSession peekSearch(UUID uuid) {
        return searchSessions.get(uuid);
    }

    public void beginSearch(Player player, LeaderboardCategory category, int page) {
        nextOpenGeneration(player.getUniqueId());
        searchSessions.put(player.getUniqueId(), new SearchSession(category, page));
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (gen != openGeneration.getOrDefault(player.getUniqueId(), -1L)) {
                return;
            }
            List<LeaderboardEntry> entries = list(category);
            int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) pageSize()));
            int safePage = Math.max(0, Math.min(page, pages - 1));
            Bukkit.getScheduler().runTask(plugin, () -> openGuiSafely(player, gen, () ->
                    player.openInventory(new LeaderboardCategoryGui(
                            plugin, player, category, safePage, entries).getInventory())));
        });
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
        };
    }

    private Map<UUID, Double> computeCoins() {
        Map<UUID, Double> scores = new HashMap<>();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            for (DatabaseManager.ProfileRow row : db.topBalances(maxEntries())) {
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
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            for (DatabaseManager.ProfileRow row : db.topPlaytimes(maxEntries())) {
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
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        if (name != null && !name.isBlank()) {
            rememberName(uuid, name);
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.isConnected()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertProfileName(uuid, name));
            }
            return name;
        }
        return null;
    }

    public boolean handleSearchChat(Player player, String raw) {
        SearchSession session = searchSessions.remove(player.getUniqueId());
        if (session == null) {
            return false;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("c")) {
            msg(player, "search-cancelled", Map.of());
            openCategory(player, session.category(), session.returnPage());
            return true;
        }
        if (text.length() < 2) {
            msg(player, "search-too-short", Map.of());
            searchSessions.put(player.getUniqueId(), session);
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
