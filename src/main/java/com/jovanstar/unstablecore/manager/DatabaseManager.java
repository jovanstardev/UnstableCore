package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class DatabaseManager {

    private final UnstableCore plugin;
    private HikariDataSource dataSource;
    private boolean mysql;

    public DatabaseManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void connect() throws SQLException {
        close();

        String type = plugin.getConfig().getString("database.type", "sqlite").trim().toLowerCase();
        mysql = type.equals("mysql") || type.equals("mariadb");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("UnstableCore-DB");
        hikari.setMaximumPoolSize(Math.max(2, plugin.getConfig().getInt("database.pool-size", mysql ? 10 : 4)));
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(10_000L);
        hikari.setIdleTimeout(600_000L);
        hikari.setMaxLifetime(1_800_000L);

        if (mysql) {
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String db = plugin.getConfig().getString("database.mysql.database", "unstablecore");
            String user = plugin.getConfig().getString("database.mysql.username", "root");
            String pass = plugin.getConfig().getString("database.mysql.password", "");
            String params = plugin.getConfig().getString(
                    "database.mysql.params",
                    "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
            );
            hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?" + params);
            hikari.setUsername(user);
            hikari.setPassword(pass);
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikari.addDataSourceProperty("useServerPrepStmts", "true");
            hikari.addDataSourceProperty("useLocalSessionState", "true");
            hikari.addDataSourceProperty("rewriteBatchedStatements", "true");
            hikari.addDataSourceProperty("cacheResultSetMetadata", "true");
            hikari.addDataSourceProperty("cacheServerConfiguration", "true");
            hikari.addDataSourceProperty("elideSetAutoCommits", "true");
            hikari.addDataSourceProperty("maintainTimeStats", "false");
            hikari.setMaximumPoolSize(Math.max(4, plugin.getConfig().getInt("database.pool-size", 20)));
            hikari.setMinimumIdle(Math.max(2, plugin.getConfig().getInt("database.min-idle", 5)));
        } else {
            String fileName = plugin.getConfig().getString("database.sqlite.file", "data.db");
            File file = new File(plugin.getDataFolder(), fileName);
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder for SQLite.");
            }
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.addDataSourceProperty("journal_mode", "WAL");
            hikari.setMaximumPoolSize(Math.max(2, plugin.getConfig().getInt("database.pool-size", 4)));
        }

        dataSource = new HikariDataSource(hikari);
        createTables();
        migrateFromYamlIfNeeded();
        plugin.getLogger().info("Database connected (" + (mysql ? "MySQL" : "SQLite") + ").");
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public Connection getConnection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException("Database is not connected.");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = null;
    }

    private void createTables() throws SQLException {
        try (Connection c = getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_settings (
                      uuid VARCHAR(36) NOT NULL,
                      setting_key VARCHAR(64) NOT NULL,
                      enabled INTEGER NOT NULL,
                      PRIMARY KEY (uuid, setting_key)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_stats (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      kills INTEGER NOT NULL DEFAULT 0,
                      best_streak INTEGER NOT NULL DEFAULT 0,
                      coins_earned DOUBLE NOT NULL DEFAULT 0,
                      coins_spent DOUBLE NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_combat (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      streak INTEGER NOT NULL DEFAULT 0,
                      deaths INTEGER NOT NULL DEFAULT 0,
                      titles_enabled INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_tags (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      tag TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loadout_cooldowns (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      last_use BIGINT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS loadout_nocooldown (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      until_ms BIGINT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS uc_meta (
                      meta_key VARCHAR(64) NOT NULL PRIMARY KEY,
                      meta_value TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_rewards (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      streak INTEGER NOT NULL DEFAULT 0,
                      last_claim_day VARCHAR(16) NOT NULL DEFAULT '',
                      last_login_day VARCHAR(16) NOT NULL DEFAULT '',
                      week_id VARCHAR(16) NOT NULL DEFAULT '',
                      week_days INTEGER NOT NULL DEFAULT 0,
                      week_claimed TEXT NOT NULL DEFAULT '',
                      month_id VARCHAR(16) NOT NULL DEFAULT '',
                      month_days INTEGER NOT NULL DEFAULT 0,
                      month_claimed TEXT NOT NULL DEFAULT '',
                      booster_until BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bounties (
                      target_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      target_name VARCHAR(32) NOT NULL,
                      amount DOUBLE NOT NULL,
                      bounty_id INTEGER NOT NULL,
                      updated_at BIGINT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_profiles (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      name VARCHAR(32) NOT NULL DEFAULT '',
                      balance DOUBLE NOT NULL DEFAULT 0,
                      playtime_ticks BIGINT NOT NULL DEFAULT 0,
                      updated_at BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            // topProfiles() sorts the whole table by these columns (COINS/PLAYTIME leaderboards) -
            // without an index that's a full table scan + sort on every cache refresh, which gets
            // expensive once player_profiles has thousands of rows (every unique player ever seen).
            createIndex(st, "idx_player_profiles_balance", "player_profiles", "balance DESC");
            createIndex(st, "idx_player_profiles_playtime", "player_profiles", "playtime_ticks DESC");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS duels (
                      duel_id VARCHAR(36) NOT NULL PRIMARY KEY,
                      challenger VARCHAR(36) NOT NULL,
                      target VARCHAR(36) NOT NULL,
                      kit_id VARCHAR(64) NOT NULL DEFAULT '',
                      arena_id VARCHAR(64) NOT NULL DEFAULT '',
                      wager DOUBLE NOT NULL DEFAULT 0,
                      state VARCHAR(16) NOT NULL,
                      escrowed INTEGER NOT NULL DEFAULT 0,
                      payout_done INTEGER NOT NULL DEFAULT 0,
                      snapshot_challenger TEXT NOT NULL DEFAULT '',
                      snapshot_target TEXT NOT NULL DEFAULT '',
                      created_at BIGINT NOT NULL DEFAULT 0,
                      updated_at BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS duel_stats (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      wins INTEGER NOT NULL DEFAULT 0,
                      losses INTEGER NOT NULL DEFAULT 0,
                      current_streak INTEGER NOT NULL DEFAULT 0,
                      best_streak INTEGER NOT NULL DEFAULT 0,
                      coins_wagered DOUBLE NOT NULL DEFAULT 0,
                      coins_won DOUBLE NOT NULL DEFAULT 0,
                      coins_lost DOUBLE NOT NULL DEFAULT 0,
                      duels_played INTEGER NOT NULL DEFAULT 0,
                      elo INTEGER NOT NULL DEFAULT 1000,
                      ranked_wins INTEGER NOT NULL DEFAULT 0,
                      ranked_losses INTEGER NOT NULL DEFAULT 0,
                      casual_wins INTEGER NOT NULL DEFAULT 0,
                      casual_losses INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            try {
                st.executeUpdate("ALTER TABLE duel_stats ADD COLUMN elo INTEGER NOT NULL DEFAULT 1000");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE duel_stats ADD COLUMN ranked_wins INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE duel_stats ADD COLUMN ranked_losses INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE duel_stats ADD COLUMN casual_wins INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            try {
                st.executeUpdate("ALTER TABLE duel_stats ADD COLUMN casual_losses INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // column already exists
            }
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS duel_history (
                      duel_id VARCHAR(36) NOT NULL PRIMARY KEY,
                      challenger VARCHAR(36) NOT NULL,
                      challenger_name VARCHAR(32) NOT NULL DEFAULT '',
                      target VARCHAR(36) NOT NULL,
                      target_name VARCHAR(32) NOT NULL DEFAULT '',
                      winner VARCHAR(36) NOT NULL DEFAULT '',
                      kit_id VARCHAR(64) NOT NULL DEFAULT '',
                      arena_id VARCHAR(64) NOT NULL DEFAULT '',
                      wager DOUBLE NOT NULL DEFAULT 0,
                      payout DOUBLE NOT NULL DEFAULT 0,
                      result VARCHAR(24) NOT NULL DEFAULT '',
                      started_at BIGINT NOT NULL DEFAULT 0,
                      ended_at BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            createIndex(st, "idx_duel_history_challenger", "duel_history", "challenger");
            createIndex(st, "idx_duel_history_target", "duel_history", "target");
            // Pre-duel inventories owed to a player who is currently offline. Held in memory by
            // DuelManager until they next join; persisted here so a restart in that window - which
            // is exactly when a mid-duel disconnect is most likely - doesn't silently drop them.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pending_restores (
                      uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                      snapshot TEXT NOT NULL,
                      created_at BIGINT NOT NULL DEFAULT 0
                    )
                    """);
        }
    }

    public record PendingRestoreRow(UUID uuid, String snapshot, long createdAt) {
    }

    private String upsertPendingRestoreSql() {
        if (mysql) {
            return """
                    INSERT INTO pending_restores (uuid, snapshot, created_at) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE snapshot = VALUES(snapshot), created_at = VALUES(created_at)
                    """;
        }
        return """
                INSERT INTO pending_restores (uuid, snapshot, created_at) VALUES (?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET snapshot = excluded.snapshot, created_at = excluded.created_at
                """;
    }

    public void upsertPendingRestore(UUID uuid, String snapshot, long createdAt) {
        if (!isConnected() || uuid == null || snapshot == null || snapshot.isBlank()) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertPendingRestoreSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, snapshot);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist pending inventory restore for " + uuid, e);
        }
    }

    public void deletePendingRestore(UUID uuid) {
        if (!isConnected() || uuid == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM pending_restores WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to clear pending inventory restore for " + uuid, e);
        }
    }

    public List<PendingRestoreRow> loadAllPendingRestores() {
        List<PendingRestoreRow> out = new ArrayList<>();
        if (!isConnected()) {
            return out;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, snapshot, created_at FROM pending_restores");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(new PendingRestoreRow(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load pending inventory restores", e);
        }
        return out;
    }

    /**
     * MySQL has no {@code CREATE INDEX IF NOT EXISTS} (SQLite and MariaDB do), so issuing that form
     * against MySQL fails with a syntax error, aborts createTables(), and takes the whole plugin
     * down via the connect() failure path in onEnable. Emit the plain form there instead and
     * tolerate the "index already exists" error on later startups, the same way the elo column
     * migration above tolerates an already-applied change. All arguments are compile-time
     * constants, never user input.
     */
    private void createIndex(Statement st, String name, String table, String columns) throws SQLException {
        if (!mysql) {
            st.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")");
            return;
        }
        try {
            st.executeUpdate("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
        } catch (SQLException e) {
            // 1061 / 42000 is "duplicate key name", i.e. the index is already there from a previous
            // startup - anything else (bad column, missing table) is a real defect and must not be
            // swallowed into a silently unindexed table.
            if (e.getErrorCode() != 1061) {
                throw e;
            }
        }
    }

    /**
     * Guards the periodic bulk saves against each other. Deliberately a different monitor from
     * {@link #atomicOpLock}: these transactions touch every tracked player, can run for a long
     * time once the database is large, and execute on the async autosave task. While they all
     * shared one lock with the small per-player atomic operations below, a main-thread reward
     * claim or bounty placement would block behind an in-progress full save - turning background
     * writes into a main-thread stall that grows with the size of the player base.
     */
    private final Object bulkSaveLock = new Object();

    /**
     * Guards the short read-modify-write operations that must serialise against each other
     * (claim marking, bounty id allocation). Each is one small transaction, so contention here is
     * bounded and never waits on bulk work.
     */
    private final Object atomicOpLock = new Object();

    private String upsertProfileSql() {
        if (mysql) {
            return """
                    INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      name = IF(VALUES(name) = '', name, VALUES(name)),
                      balance = VALUES(balance),
                      playtime_ticks = GREATEST(playtime_ticks, VALUES(playtime_ticks)),
                      updated_at = VALUES(updated_at)
                    """;
        }
        return """
                INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  name = CASE WHEN excluded.name = '' THEN player_profiles.name ELSE excluded.name END,
                  balance = excluded.balance,
                  playtime_ticks = MAX(player_profiles.playtime_ticks, excluded.playtime_ticks),
                  updated_at = excluded.updated_at
                """;
    }

    private String upsertProfileBalanceSql() {
        if (mysql) {
            return """
                    INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                    VALUES (?, ?, ?, 0, ?)
                    ON DUPLICATE KEY UPDATE
                      name = IF(VALUES(name) = '', name, VALUES(name)),
                      balance = VALUES(balance),
                      updated_at = VALUES(updated_at)
                    """;
        }
        return """
                INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                VALUES (?, ?, ?, 0, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  name = CASE WHEN excluded.name = '' THEN player_profiles.name ELSE excluded.name END,
                  balance = excluded.balance,
                  updated_at = excluded.updated_at
                """;
    }

    private String upsertProfileNameSql() {
        if (mysql) {
            return """
                    INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                    VALUES (?, ?, 0, 0, ?)
                    ON DUPLICATE KEY UPDATE
                      name = IF(VALUES(name) = '', name, VALUES(name)),
                      updated_at = VALUES(updated_at)
                    """;
        }
        return """
                INSERT INTO player_profiles (uuid, name, balance, playtime_ticks, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  name = CASE WHEN excluded.name = '' THEN player_profiles.name ELSE excluded.name END,
                  updated_at = excluded.updated_at
                """;
    }

    private String upsertSettingsSql() {
        if (mysql) {
            return """
                    INSERT INTO player_settings (uuid, setting_key, enabled) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)
                    """;
        }
        return """
                INSERT INTO player_settings (uuid, setting_key, enabled) VALUES (?, ?, ?)
                ON CONFLICT(uuid, setting_key) DO UPDATE SET enabled = excluded.enabled
                """;
    }

    private String upsertStatsSql() {
        if (mysql) {
            return """
                    INSERT INTO player_stats (uuid, kills, best_streak, coins_earned, coins_spent)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      kills = VALUES(kills),
                      best_streak = VALUES(best_streak),
                      coins_earned = VALUES(coins_earned),
                      coins_spent = VALUES(coins_spent)
                    """;
        }
        return """
                INSERT INTO player_stats (uuid, kills, best_streak, coins_earned, coins_spent)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  kills = excluded.kills,
                  best_streak = excluded.best_streak,
                  coins_earned = excluded.coins_earned,
                  coins_spent = excluded.coins_spent
                """;
    }

    private String upsertCombatSql() {
        if (mysql) {
            return """
                    INSERT INTO player_combat (uuid, streak, deaths, titles_enabled)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      streak = VALUES(streak),
                      deaths = VALUES(deaths),
                      titles_enabled = VALUES(titles_enabled)
                    """;
        }
        return """
                INSERT INTO player_combat (uuid, streak, deaths, titles_enabled)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  streak = excluded.streak,
                  deaths = excluded.deaths,
                  titles_enabled = excluded.titles_enabled
                """;
    }

    private String upsertTagSql() {
        if (mysql) {
            return """
                    INSERT INTO player_tags (uuid, tag) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE tag = VALUES(tag)
                    """;
        }
        return """
                INSERT INTO player_tags (uuid, tag) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET tag = excluded.tag
                """;
    }

    private String upsertLoadoutSql() {
        if (mysql) {
            return """
                    INSERT INTO loadout_cooldowns (uuid, last_use) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE last_use = VALUES(last_use)
                    """;
        }
        return """
                INSERT INTO loadout_cooldowns (uuid, last_use) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET last_use = excluded.last_use
                """;
    }

    public Map<UUID, Map<String, Boolean>> loadAllSettings() {
        Map<UUID, Map<String, Boolean>> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, setting_key, enabled FROM player_settings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    out.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>())
                            .put(rs.getString(2), rs.getInt(3) != 0);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load settings from database", e);
        }
        return out;
    }

    /** Single-player scoped save, used for save-on-quit so a crash between periodic autosaves
     *  can't lose a departing player's toggled settings. */
    public void savePlayerSettings(UUID uuid, Map<String, Boolean> playerSettings,
                                    java.util.function.Function<String, Boolean> defaultOf) {
        if (!isConnected() || uuid == null) {
            return;
        }
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement clear = c.prepareStatement("DELETE FROM player_settings WHERE uuid = ?")) {
                    clear.setString(1, uuid.toString());
                    clear.executeUpdate();
                }
                if (playerSettings != null && !playerSettings.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(upsertSettingsSql())) {
                        for (Map.Entry<String, Boolean> s : playerSettings.entrySet()) {
                            if (s.getValue() == null || s.getValue() == defaultOf.apply(s.getKey())) {
                                continue;
                            }
                            ps.setString(1, uuid.toString());
                            ps.setString(2, s.getKey());
                            ps.setInt(3, s.getValue() ? 1 : 0);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save settings for " + uuid, e);
        }
    }

    /**
     * Periodic bulk save. Deliberately does NOT wipe the table first: the in-memory map is only a
     * best-effort mirror of the DB (a failed {@code loadAllSettings()} at boot - a transient
     * connection blip, a locked SQLite file - silently yields an empty map), and a
     * DELETE-everything-then-reinsert would turn that transient read failure into permanent,
     * total data loss for every player on the very next autosave. Every row still reaches its
     * exact intended state: values that differ from the default are upserted, values that are
     * back at the default have their row removed individually.
     */
    public void saveAllSettings(Map<UUID, Map<String, Boolean>> settings,
                                java.util.function.Function<String, Boolean> defaultOf) {
        synchronized (bulkSaveLock) {
            saveAllSettings0(settings, defaultOf);
        }
    }

    private void saveAllSettings0(Map<UUID, Map<String, Boolean>> settings,
                                  java.util.function.Function<String, Boolean> defaultOf) {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(upsertSettingsSql());
                     PreparedStatement del = c.prepareStatement(
                             "DELETE FROM player_settings WHERE uuid = ? AND setting_key = ?")) {
                    for (Map.Entry<UUID, Map<String, Boolean>> e : settings.entrySet()) {
                        for (Map.Entry<String, Boolean> s : e.getValue().entrySet()) {
                            if (s.getValue() == null) {
                                continue;
                            }
                            boolean def = defaultOf.apply(s.getKey());
                            if (s.getValue() == def) {
                                del.setString(1, e.getKey().toString());
                                del.setString(2, s.getKey());
                                del.addBatch();
                                continue;
                            }
                            ps.setString(1, e.getKey().toString());
                            ps.setString(2, s.getKey());
                            ps.setInt(3, s.getValue() ? 1 : 0);
                            ps.addBatch();
                        }
                    }
                    del.executeBatch();
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save settings to database", e);
        }
    }

    public record StatsRow(int kills, int bestStreak, double coinsEarned, double coinsSpent) {}

    public Map<UUID, StatsRow> loadAllStats() {
        Map<UUID, StatsRow> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, kills, best_streak, coins_earned, coins_spent FROM player_stats");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), new StatsRow(
                            rs.getInt(2), rs.getInt(3), rs.getDouble(4), rs.getDouble(5)
                    ));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load stats from database", e);
        }
        return out;
    }

    /** Single-player upsert, used for save-on-quit so a crash between periodic autosaves can't
     *  lose a departing player's stats. */
    public void upsertStatsRow(UUID uuid, int kills, int bestStreak, double coinsEarned, double coinsSpent) {
        if (!isConnected() || uuid == null) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertStatsSql())) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, kills);
            ps.setInt(3, bestStreak);
            ps.setDouble(4, coinsEarned);
            ps.setDouble(5, coinsSpent);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert stats for " + uuid, e);
        }
    }

    public void saveAllStats(Map<UUID, Integer> kills,
                             Map<UUID, Integer> bestStreak,
                             Map<UUID, Double> coinsEarned,
                             Map<UUID, Double> coinsSpent) {
        synchronized (bulkSaveLock) {
            saveAllStats0(kills, bestStreak, coinsEarned, coinsSpent);
        }
    }

    private void saveAllStats0(Map<UUID, Integer> kills,
                               Map<UUID, Integer> bestStreak,
                               Map<UUID, Double> coinsEarned,
                               Map<UUID, Double> coinsSpent) {
        java.util.Set<UUID> uuids = new java.util.HashSet<>();
        uuids.addAll(kills.keySet());
        uuids.addAll(bestStreak.keySet());
        uuids.addAll(coinsEarned.keySet());
        uuids.addAll(coinsSpent.keySet());

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                // See saveAllSettings: no DELETE-then-reinsert, so a failed boot-time load can
                // never escalate into wiping every player's stats on the next autosave.
                try (PreparedStatement ps = c.prepareStatement(upsertStatsSql());
                     PreparedStatement del = c.prepareStatement("DELETE FROM player_stats WHERE uuid = ?")) {
                    for (UUID uuid : uuids) {
                        int k = kills.getOrDefault(uuid, 0);
                        int b = bestStreak.getOrDefault(uuid, 0);
                        double earned = coinsEarned.getOrDefault(uuid, 0.0);
                        double spent = coinsSpent.getOrDefault(uuid, 0.0);
                        if (k <= 0 && b <= 0 && earned <= 0 && spent <= 0) {
                            del.setString(1, uuid.toString());
                            del.addBatch();
                            continue;
                        }
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, k);
                        ps.setInt(3, b);
                        ps.setDouble(4, earned);
                        ps.setDouble(5, spent);
                        ps.addBatch();
                    }
                    del.executeBatch();
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save stats to database", e);
        }
    }

    public record CombatRow(int streak, int deaths, boolean titlesEnabled) {}

    public Map<UUID, CombatRow> loadAllCombat() {
        Map<UUID, CombatRow> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, streak, deaths, titles_enabled FROM player_combat");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), new CombatRow(
                            rs.getInt(2), rs.getInt(3), rs.getInt(4) != 0
                    ));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load combat data from database", e);
        }
        return out;
    }

    /** Single-player upsert, used for save-on-quit so a crash between periodic autosaves can't
     *  lose a departing player's combat streak/deaths. */
    public void upsertCombatRow(UUID uuid, int streak, int deaths, boolean titlesEnabled) {
        if (!isConnected() || uuid == null) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertCombatSql())) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, streak);
            ps.setInt(3, deaths);
            ps.setInt(4, titlesEnabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert combat data for " + uuid, e);
        }
    }

    public void saveAllCombat(Map<UUID, Integer> streaks,
                              Map<UUID, Integer> deaths,
                              Map<UUID, Boolean> titlesEnabled) {
        synchronized (bulkSaveLock) {
            saveAllCombat0(streaks, deaths, titlesEnabled);
        }
    }

    private void saveAllCombat0(Map<UUID, Integer> streaks,
                                Map<UUID, Integer> deaths,
                                Map<UUID, Boolean> titlesEnabled) {
        java.util.Set<UUID> uuids = new java.util.HashSet<>();
        uuids.addAll(streaks.keySet());
        uuids.addAll(deaths.keySet());
        uuids.addAll(titlesEnabled.keySet());

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                // See saveAllSettings: no DELETE-then-reinsert (boot-load failure must not
                // escalate into total data loss). Rows that fall back to the all-default state
                // are removed individually instead.
                try (PreparedStatement ps = c.prepareStatement(upsertCombatSql());
                     PreparedStatement del = c.prepareStatement("DELETE FROM player_combat WHERE uuid = ?")) {
                    for (UUID uuid : uuids) {
                        int streak = streaks.getOrDefault(uuid, 0);
                        int death = deaths.getOrDefault(uuid, 0);
                        boolean titles = titlesEnabled.getOrDefault(uuid, true);
                        if (streak <= 0 && death <= 0 && titles) {
                            del.setString(1, uuid.toString());
                            del.addBatch();
                            continue;
                        }
                        ps.setString(1, uuid.toString());
                        ps.setInt(2, streak);
                        ps.setInt(3, death);
                        ps.setInt(4, titles ? 1 : 0);
                        ps.addBatch();
                    }
                    del.executeBatch();
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save combat data to database", e);
        }
    }

    public record ProfileRow(UUID uuid, String name, double balance, long playtimeTicks) {}

    public void upsertProfile(UUID uuid, String name, double balance, long playtimeTicks) {
        if (uuid == null) {
            return;
        }
        String safeName = name == null ? "" : name;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertProfileSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, safeName);
            ps.setDouble(3, Math.max(0D, balance));
            ps.setLong(4, Math.max(0L, playtimeTicks));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert player profile", e);
        }
    }

    /**
     * Batched form of {@link #upsertProfile} for the periodic online-player sync.
     *
     * <p>That sync used to schedule one async task - and therefore take one pooled connection and
     * run one statement - per online player, every cycle. At a few hundred players that is a burst
     * of hundreds of tasks contending over a pool of 4-20 connections, every 30 seconds, for what
     * is a single batched write.
     */
    public void upsertProfiles(List<ProfileRow> rows) {
        if (!isConnected() || rows == null || rows.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(upsertProfileSql())) {
                for (ProfileRow row : rows) {
                    if (row == null || row.uuid() == null) {
                        continue;
                    }
                    ps.setString(1, row.uuid().toString());
                    ps.setString(2, row.name() == null ? "" : row.name());
                    ps.setDouble(3, Math.max(0D, row.balance()));
                    ps.setLong(4, Math.max(0L, row.playtimeTicks()));
                    ps.setLong(5, now);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to batch-upsert player profiles", e);
        }
    }

    public void upsertProfileBalance(UUID uuid, String name, double balance) {
        if (uuid == null) {
            return;
        }
        String safeName = name == null ? "" : name;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertProfileBalanceSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, safeName);
            ps.setDouble(3, Math.max(0D, balance));
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert player balance profile", e);
        }
    }

    public void upsertProfileName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertProfileNameSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert player name profile", e);
        }
    }

    public Map<UUID, String> loadAllProfileNames() {
        Map<UUID, String> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, name FROM player_profiles WHERE name IS NOT NULL AND name != ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    String name = rs.getString(2);
                    if (name != null && !name.isBlank()) {
                        out.put(UUID.fromString(rs.getString(1)), name);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load profile names from database", e);
        }
        return out;
    }

    public List<ProfileRow> topBalances(int limit) {
        return topProfiles("balance", limit);
    }

    public List<ProfileRow> topPlaytimes(int limit) {
        return topProfiles("playtime_ticks", limit);
    }

    private List<ProfileRow> topProfiles(String column, int limit) {
        int cap = Math.max(1, Math.min(10_000, limit));
        String sql = "SELECT uuid, name, balance, playtime_ticks FROM player_profiles WHERE "
                + column + " > 0 ORDER BY " + column + " DESC, name ASC LIMIT ?";
        List<ProfileRow> out = new ArrayList<>();
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        String name = rs.getString(2);
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        out.add(new ProfileRow(
                                UUID.fromString(rs.getString(1)),
                                name,
                                rs.getDouble(3),
                                rs.getLong(4)
                        ));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load leaderboard profiles from database", e);
        }
        return out;
    }

    public Map<UUID, String> loadAllTags() {
        Map<UUID, String> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, tag FROM player_tags");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    String tag = rs.getString(2);
                    if (tag != null && !tag.isBlank()) {
                        out.put(UUID.fromString(rs.getString(1)), tag);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load tags from database", e);
        }
        return out;
    }

    public void saveAllTags(Map<UUID, String> tags) {
        synchronized (bulkSaveLock) {
            saveAllTags0(tags);
        }
    }

    private void saveAllTags0(Map<UUID, String> tags) {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                // See saveAllSettings for why this no longer wipes the table first. Un-equipping
                // already issues its own targeted deleteTag(); the per-row delete here covers the
                // (defensive) case of a blank value left in the map.
                try (PreparedStatement ps = c.prepareStatement(upsertTagSql());
                     PreparedStatement del = c.prepareStatement("DELETE FROM player_tags WHERE uuid = ?")) {
                    for (Map.Entry<UUID, String> e : tags.entrySet()) {
                        if (e.getValue() == null || e.getValue().isBlank()) {
                            del.setString(1, e.getKey().toString());
                            del.addBatch();
                            continue;
                        }
                        ps.setString(1, e.getKey().toString());
                        ps.setString(2, e.getValue());
                        ps.addBatch();
                    }
                    del.executeBatch();
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save tags to database", e);
        }
    }

    public void upsertTag(UUID uuid, String tag) {
        if (!isConnected() || uuid == null || tag == null || tag.isBlank()) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertTagSql())) {
            ps.setString(1, uuid.toString());
            ps.setString(2, tag);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert tag for " + uuid, e);
        }
    }

    public void deleteTag(UUID uuid) {
        if (!isConnected() || uuid == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM player_tags WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete tag for " + uuid, e);
        }
    }

    public Map<UUID, Long> loadAllLoadouts() {
        Map<UUID, Long> out = new ConcurrentHashMap<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, last_use FROM loadout_cooldowns");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load loadout cooldowns from database", e);
        }
        return out;
    }

    public void saveAllLoadouts(Map<UUID, Long> lastUse, long cooldownMs) {
        synchronized (bulkSaveLock) {
            saveAllLoadouts0(lastUse, cooldownMs);
        }
    }

    private void saveAllLoadouts0(Map<UUID, Long> lastUse, long cooldownMs) {
        long now = System.currentTimeMillis();
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                // See saveAllSettings for why this no longer wipes the table first. Expired
                // cooldowns are pruned by an explicit age predicate rather than by omission, so a
                // momentarily-empty in-memory map can't silently clear everyone's cooldown (which
                // here would hand every player an immediate free re-gear).
                try (PreparedStatement expire = c.prepareStatement(
                        "DELETE FROM loadout_cooldowns WHERE last_use <= ?")) {
                    expire.setLong(1, now - Math.max(0L, cooldownMs));
                    expire.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(upsertLoadoutSql())) {
                    for (Map.Entry<UUID, Long> e : lastUse.entrySet()) {
                        if (now - e.getValue() >= cooldownMs) {
                            continue;
                        }
                        ps.setString(1, e.getKey().toString());
                        ps.setLong(2, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save loadout cooldowns to database", e);
        }
    }

    public void upsertLoadoutCooldown(UUID uuid, long lastUseMs) {
        if (uuid == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(upsertLoadoutSql())) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, lastUseMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert loadout cooldown", e);
        }
    }

    public void deleteLoadoutCooldown(UUID uuid) {
        if (uuid == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM loadout_cooldowns WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete loadout cooldown", e);
        }
    }

    private String upsertLoadoutNoCooldownSql() {
        if (mysql) {
            return """
                    INSERT INTO loadout_nocooldown (uuid, until_ms) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE until_ms = VALUES(until_ms)
                    """;
        }
        return """
                INSERT INTO loadout_nocooldown (uuid, until_ms) VALUES (?, ?)
                ON CONFLICT(uuid) DO UPDATE SET until_ms = excluded.until_ms
                """;
    }

    public Map<UUID, Long> loadAllLoadoutNoCooldown() {
        Map<UUID, Long> out = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, until_ms FROM loadout_nocooldown");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    long until = rs.getLong(2);
                    if (until > now) {
                        out.put(UUID.fromString(rs.getString(1)), until);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load loadout no-cooldown from database", e);
        }
        return out;
    }

    public void saveAllLoadoutNoCooldown(Map<UUID, Long> untilMap) {
        synchronized (bulkSaveLock) {
            saveAllLoadoutNoCooldown0(untilMap);
        }
    }

    private void saveAllLoadoutNoCooldown0(Map<UUID, Long> untilMap) {
        long now = System.currentTimeMillis();
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                // See saveAllSettings/saveAllLoadouts: expire by predicate, never by wiping the
                // table - this one stores paid no-cooldown grants, so a spurious wipe is a direct
                // loss of purchased value.
                try (PreparedStatement expire = c.prepareStatement(
                        "DELETE FROM loadout_nocooldown WHERE until_ms <= ?")) {
                    expire.setLong(1, now);
                    expire.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(upsertLoadoutNoCooldownSql())) {
                    for (Map.Entry<UUID, Long> e : untilMap.entrySet()) {
                        if (e.getValue() <= now) {
                            continue;
                        }
                        ps.setString(1, e.getKey().toString());
                        ps.setLong(2, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save loadout no-cooldown to database", e);
        }
    }

    public void upsertLoadoutNoCooldown(UUID uuid, long untilMs) {
        if (uuid == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(upsertLoadoutNoCooldownSql())) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, untilMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert loadout no-cooldown", e);
        }
    }

    private String getMeta(String key) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT meta_value FROM uc_meta WHERE meta_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read meta " + key, e);
        }
        return null;
    }

    private void setMeta(String key, String value) {
        String sql = mysql
                ? "INSERT INTO uc_meta (meta_key, meta_value) VALUES (?, ?) ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)"
                : "INSERT INTO uc_meta (meta_key, meta_value) VALUES (?, ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write meta " + key, e);
        }
    }

    private void migrateFromYamlIfNeeded() {
        if (!plugin.getConfig().getBoolean("database.migrate-from-yaml", true)) {
            return;
        }
        if ("1".equals(getMeta("yaml_migrated"))) {
            return;
        }

        FileConfiguration data = plugin.getConfigManager().getData();
        boolean hadAny = data.isConfigurationSection("player-settings")
                || data.isConfigurationSection("stats")
                || data.isConfigurationSection("killstreaks")
                || data.isConfigurationSection("deaths")
                || data.isConfigurationSection("killstreak-titles")
                || data.isConfigurationSection("tags")
                || data.isConfigurationSection("loadout-cooldown");

        if (!hadAny) {
            setMeta("yaml_migrated", "1");
            return;
        }

        plugin.getLogger().info("Migrating player data from data.yml into the database...");
        int settings = 0, stats = 0, combat = 0, tags = 0, loadouts = 0;

        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {

            if (data.isConfigurationSection("player-settings")) {
                try (PreparedStatement ps = c.prepareStatement(upsertSettingsSql())) {
                    for (String uuidKey : data.getConfigurationSection("player-settings").getKeys(false)) {
                        try {
                            UUID.fromString(uuidKey);
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        if (!data.isConfigurationSection("player-settings." + uuidKey)) {
                            continue;
                        }
                        for (String key : data.getConfigurationSection("player-settings." + uuidKey).getKeys(false)) {
                            ps.setString(1, uuidKey);
                            ps.setString(2, key);
                            ps.setInt(3, data.getBoolean("player-settings." + uuidKey + "." + key) ? 1 : 0);
                            ps.addBatch();
                            settings++;
                        }
                    }
                    ps.executeBatch();
                }
            }

            Map<UUID, StatsRow> mergedStats = new HashMap<>();
            mergeStatInt(data, "stats.kills", mergedStats, true);
            mergeStatInt(data, "stats.best-streak", mergedStats, false);
            mergeStatDouble(data, "stats.coins-earned", mergedStats, true);
            mergeStatDouble(data, "stats.coins-spent", mergedStats, false);
            try (PreparedStatement ps = c.prepareStatement(upsertStatsSql())) {
                for (Map.Entry<UUID, StatsRow> e : mergedStats.entrySet()) {
                    StatsRow r = e.getValue();
                    ps.setString(1, e.getKey().toString());
                    ps.setInt(2, r.kills());
                    ps.setInt(3, r.bestStreak());
                    ps.setDouble(4, r.coinsEarned());
                    ps.setDouble(5, r.coinsSpent());
                    ps.addBatch();
                    stats++;
                }
                ps.executeBatch();
            }

            Map<UUID, CombatRow> mergedCombat = new HashMap<>();
            if (data.isConfigurationSection("killstreaks")) {
                for (String key : data.getConfigurationSection("killstreaks").getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        CombatRow prev = mergedCombat.getOrDefault(uuid, new CombatRow(0, 0, true));
                        mergedCombat.put(uuid, new CombatRow(data.getInt("killstreaks." + key), prev.deaths(), prev.titlesEnabled()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            if (data.isConfigurationSection("deaths")) {
                for (String key : data.getConfigurationSection("deaths").getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        CombatRow prev = mergedCombat.getOrDefault(uuid, new CombatRow(0, 0, true));
                        mergedCombat.put(uuid, new CombatRow(prev.streak(), data.getInt("deaths." + key), prev.titlesEnabled()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            if (data.isConfigurationSection("killstreak-titles")) {
                for (String key : data.getConfigurationSection("killstreak-titles").getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(key);
                        CombatRow prev = mergedCombat.getOrDefault(uuid, new CombatRow(0, 0, true));
                        mergedCombat.put(uuid, new CombatRow(prev.streak(), prev.deaths(),
                                data.getBoolean("killstreak-titles." + key, true)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(upsertCombatSql())) {
                for (Map.Entry<UUID, CombatRow> e : mergedCombat.entrySet()) {
                    CombatRow r = e.getValue();
                    ps.setString(1, e.getKey().toString());
                    ps.setInt(2, r.streak());
                    ps.setInt(3, r.deaths());
                    ps.setInt(4, r.titlesEnabled() ? 1 : 0);
                    ps.addBatch();
                    combat++;
                }
                ps.executeBatch();
            }

            if (data.isConfigurationSection("tags")) {
                try (PreparedStatement ps = c.prepareStatement(upsertTagSql())) {
                    for (String key : data.getConfigurationSection("tags").getKeys(false)) {
                        try {
                            UUID.fromString(key);
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        String tag = data.getString("tags." + key, "");
                        if (tag == null || tag.isBlank()) {
                            continue;
                        }
                        ps.setString(1, key);
                        ps.setString(2, tag);
                        ps.addBatch();
                        tags++;
                    }
                    ps.executeBatch();
                }
            }

            if (data.isConfigurationSection("loadout-cooldown")) {
                try (PreparedStatement ps = c.prepareStatement(upsertLoadoutSql())) {
                    for (String key : data.getConfigurationSection("loadout-cooldown").getKeys(false)) {
                        try {
                            UUID.fromString(key);
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        ps.setString(1, key);
                        ps.setLong(2, data.getLong("loadout-cooldown." + key));
                        ps.addBatch();
                        loadouts++;
                    }
                    ps.executeBatch();
                }
            }

            c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "YAML → database migration failed", e);
            return;
        }

        data.set("player-settings", null);
        data.set("stats", null);
        data.set("killstreaks", null);
        data.set("deaths", null);
        data.set("killstreak-titles", null);
        data.set("tags", null);
        data.set("loadout-cooldown", null);
        plugin.getConfigManager().saveData();

        setMeta("yaml_migrated", "1");
        plugin.getLogger().info("Migration complete: settings=" + settings
                + " stats=" + stats + " combat=" + combat
                + " tags=" + tags + " loadouts=" + loadouts);
    }

    private static void mergeStatInt(FileConfiguration data, String path,
                                     Map<UUID, StatsRow> into, boolean kills) {
        if (!data.isConfigurationSection(path)) {
            return;
        }
        for (String key : data.getConfigurationSection(path).getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                StatsRow prev = into.getOrDefault(uuid, new StatsRow(0, 0, 0, 0));
                int val = data.getInt(path + "." + key);
                if (kills) {
                    into.put(uuid, new StatsRow(val, prev.bestStreak(), prev.coinsEarned(), prev.coinsSpent()));
                } else {
                    into.put(uuid, new StatsRow(prev.kills(), val, prev.coinsEarned(), prev.coinsSpent()));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void mergeStatDouble(FileConfiguration data, String path,
                                        Map<UUID, StatsRow> into, boolean earned) {
        if (!data.isConfigurationSection(path)) {
            return;
        }
        for (String key : data.getConfigurationSection(path).getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                StatsRow prev = into.getOrDefault(uuid, new StatsRow(0, 0, 0, 0));
                double val = data.getDouble(path + "." + key);
                if (earned) {
                    into.put(uuid, new StatsRow(prev.kills(), prev.bestStreak(), val, prev.coinsSpent()));
                } else {
                    into.put(uuid, new StatsRow(prev.kills(), prev.bestStreak(), prev.coinsEarned(), val));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public record RewardsRow(
            int streak,
            String lastClaimDay,
            String lastLoginDay,
            String weekId,
            int weekDays,
            String weekClaimed,
            String monthId,
            int monthDays,
            String monthClaimed,
            long boosterUntil
    ) {
        public static RewardsRow empty() {
            return new RewardsRow(0, "", "", "", 0, "", "", 0, "", 0L);
        }
    }

    public RewardsRow loadRewards(UUID uuid) {
        if (uuid == null) {
            return RewardsRow.empty();
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT streak, last_claim_day, last_login_day, week_id, week_days, week_claimed, "
                             + "month_id, month_days, month_claimed, booster_until FROM player_rewards WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new RewardsRow(
                            rs.getInt(1),
                            nullToEmpty(rs.getString(2)),
                            nullToEmpty(rs.getString(3)),
                            nullToEmpty(rs.getString(4)),
                            rs.getInt(5),
                            nullToEmpty(rs.getString(6)),
                            nullToEmpty(rs.getString(7)),
                            rs.getInt(8),
                            nullToEmpty(rs.getString(9)),
                            rs.getLong(10)
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load rewards for " + uuid, e);
        }
        return RewardsRow.empty();
    }

    public void saveRewards(UUID uuid, RewardsRow row) {
        synchronized (atomicOpLock) {
            saveRewards0(uuid, row);
        }
    }

    private void saveRewards0(UUID uuid, RewardsRow row) {
        if (uuid == null || row == null) {
            return;
        }
        String sql = mysql
                ? """
                INSERT INTO player_rewards
                (uuid, streak, last_claim_day, last_login_day, week_id, week_days, week_claimed,
                 month_id, month_days, month_claimed, booster_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  streak = VALUES(streak),
                  last_claim_day = VALUES(last_claim_day),
                  last_login_day = VALUES(last_login_day),
                  week_id = VALUES(week_id),
                  week_days = VALUES(week_days),
                  week_claimed = VALUES(week_claimed),
                  month_id = VALUES(month_id),
                  month_days = VALUES(month_days),
                  month_claimed = VALUES(month_claimed),
                  booster_until = VALUES(booster_until)
                """
                : """
                INSERT INTO player_rewards
                (uuid, streak, last_claim_day, last_login_day, week_id, week_days, week_claimed,
                 month_id, month_days, month_claimed, booster_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  streak = excluded.streak,
                  last_claim_day = excluded.last_claim_day,
                  last_login_day = excluded.last_login_day,
                  week_id = excluded.week_id,
                  week_days = excluded.week_days,
                  week_claimed = excluded.week_claimed,
                  month_id = excluded.month_id,
                  month_days = excluded.month_days,
                  month_claimed = excluded.month_claimed,
                  booster_until = excluded.booster_until
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, row.streak());
            ps.setString(3, row.lastClaimDay());
            ps.setString(4, row.lastLoginDay());
            ps.setString(5, row.weekId());
            ps.setInt(6, row.weekDays());
            ps.setString(7, row.weekClaimed());
            ps.setString(8, row.monthId());
            ps.setInt(9, row.monthDays());
            ps.setString(10, row.monthClaimed());
            ps.setLong(11, row.boosterUntil());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save rewards for " + uuid, e);
        }
    }

    public boolean tryMarkDailyClaim(UUID uuid, RewardsRow proposed, String claimDay) {
        synchronized (atomicOpLock) {
            return tryMarkDailyClaim0(uuid, proposed, claimDay);
        }
    }

    private boolean tryMarkDailyClaim0(UUID uuid, RewardsRow proposed, String claimDay) {
        if (uuid == null || proposed == null || claimDay == null) {
            return false;
        }
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                String currentDay = "";
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT last_claim_day FROM player_rewards WHERE uuid = ?")) {
                    sel.setString(1, uuid.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            currentDay = nullToEmpty(rs.getString(1));
                        }
                    }
                }
                if (claimDay.equals(currentDay)) {
                    c.rollback();
                    return false;
                }
                upsertRewards(c, uuid, proposed);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed atomic daily claim for " + uuid, e);
            return false;
        }
    }

    public boolean tryMarkMilestoneClaim(
            UUID uuid, RewardsRow proposed, boolean weekly, int required) {
        synchronized (atomicOpLock) {
            return tryMarkMilestoneClaim0(uuid, proposed, weekly, required);
        }
    }

    private boolean tryMarkMilestoneClaim0(
            UUID uuid, RewardsRow proposed, boolean weekly, int required) {
        if (uuid == null || proposed == null || required <= 0) {
            return false;
        }
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try {
                String column = weekly ? "week_claimed" : "month_claimed";
                String existing = "";
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT " + column + " FROM player_rewards WHERE uuid = ?")) {
                    sel.setString(1, uuid.toString());
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            existing = nullToEmpty(rs.getString(1));
                        }
                    }
                }
                if (claimedCsvContains(existing, required)) {
                    c.rollback();
                    return false;
                }
                upsertRewards(c, uuid, proposed);
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed atomic milestone claim for " + uuid, e);
            return false;
        }
    }

    private static boolean claimedCsvContains(String csv, int required) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        String needle = String.valueOf(required);
        for (String part : csv.split(",")) {
            if (needle.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private void upsertRewards(Connection c, UUID uuid, RewardsRow row) throws SQLException {
        String sql = mysql
                ? """
                INSERT INTO player_rewards
                (uuid, streak, last_claim_day, last_login_day, week_id, week_days, week_claimed,
                 month_id, month_days, month_claimed, booster_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                  streak = VALUES(streak),
                  last_claim_day = VALUES(last_claim_day),
                  last_login_day = VALUES(last_login_day),
                  week_id = VALUES(week_id),
                  week_days = VALUES(week_days),
                  week_claimed = VALUES(week_claimed),
                  month_id = VALUES(month_id),
                  month_days = VALUES(month_days),
                  month_claimed = VALUES(month_claimed),
                  booster_until = VALUES(booster_until)
                """
                : """
                INSERT INTO player_rewards
                (uuid, streak, last_claim_day, last_login_day, week_id, week_days, week_claimed,
                 month_id, month_days, month_claimed, booster_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  streak = excluded.streak,
                  last_claim_day = excluded.last_claim_day,
                  last_login_day = excluded.last_login_day,
                  week_id = excluded.week_id,
                  week_days = excluded.week_days,
                  week_claimed = excluded.week_claimed,
                  month_id = excluded.month_id,
                  month_days = excluded.month_days,
                  month_claimed = excluded.month_claimed,
                  booster_until = excluded.booster_until
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, row.streak());
            ps.setString(3, row.lastClaimDay());
            ps.setString(4, row.lastLoginDay());
            ps.setString(5, row.weekId());
            ps.setInt(6, row.weekDays());
            ps.setString(7, row.weekClaimed());
            ps.setString(8, row.monthId());
            ps.setInt(9, row.monthDays());
            ps.setString(10, row.monthClaimed());
            ps.setLong(11, row.boosterUntil());
            ps.executeUpdate();
        }
    }

    public record BountyRow(UUID target, String targetName, double amount, int bountyId, long updatedAt) {
    }

    public Map<UUID, BountyRow> loadAllBounties() {
        Map<UUID, BountyRow> map = new ConcurrentHashMap<>();
        if (!isConnected()) {
            return map;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT target_uuid, target_name, amount, bounty_id, updated_at FROM bounties");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                map.put(uuid, new BountyRow(
                        uuid,
                        rs.getString(2),
                        rs.getDouble(3),
                        rs.getInt(4),
                        rs.getLong(5)
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load bounties", e);
        }
        return map;
    }

    public int nextBountyId() {
        synchronized (atomicOpLock) {
            return nextBountyId0();
        }
    }

    private int nextBountyId0() {
        String raw = getMeta("next_bounty_id");
        int next = 1;
        if (raw != null && !raw.isBlank()) {
            try {
                next = Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
                next = 1;
            }
        }
        setMeta("next_bounty_id", String.valueOf(next + 1));
        return next;
    }

    public void upsertBounty(BountyRow row) {
        if (!isConnected() || row == null) {
            return;
        }
        String sql = mysql
                ? """
                INSERT INTO bounties (target_uuid, target_name, amount, bounty_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE target_name = VALUES(target_name), amount = VALUES(amount),
                  bounty_id = VALUES(bounty_id), updated_at = VALUES(updated_at)
                """
                : """
                INSERT INTO bounties (target_uuid, target_name, amount, bounty_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(target_uuid) DO UPDATE SET
                  target_name = excluded.target_name,
                  amount = excluded.amount,
                  bounty_id = excluded.bounty_id,
                  updated_at = excluded.updated_at
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.target().toString());
            ps.setString(2, row.targetName() == null ? "" : row.targetName());
            ps.setDouble(3, row.amount());
            ps.setInt(4, row.bountyId());
            ps.setLong(5, row.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert bounty " + row.target(), e);
        }
    }

    public void deleteBounty(UUID target) {
        deleteBounty(target, -1, -1L);
    }

    public void deleteBounty(UUID target, int bountyId, long updatedAt) {
        if (!isConnected() || target == null) {
            return;
        }
        String sql = bountyId > 0
                ? "DELETE FROM bounties WHERE target_uuid = ? AND bounty_id = ? AND updated_at = ?"
                : "DELETE FROM bounties WHERE target_uuid = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, target.toString());
            if (bountyId > 0) {
                ps.setInt(2, bountyId);
                ps.setLong(3, updatedAt);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete bounty " + target, e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Duels
    // ---------------------------------------------------------------------------------------

    public record DuelRow(String duelId, UUID challenger, UUID target, String kitId, String arenaId,
                           double wager, String state, boolean escrowed, boolean payoutDone,
                           String snapshotChallenger, String snapshotTarget,
                           long createdAt, long updatedAt) {
    }

    private String upsertDuelSql() {
        if (mysql) {
            return """
                    INSERT INTO duels (duel_id, challenger, target, kit_id, arena_id, wager, state,
                      escrowed, payout_done, snapshot_challenger, snapshot_target, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      arena_id = VALUES(arena_id), wager = VALUES(wager), state = VALUES(state),
                      escrowed = VALUES(escrowed), payout_done = VALUES(payout_done),
                      snapshot_challenger = VALUES(snapshot_challenger),
                      snapshot_target = VALUES(snapshot_target), updated_at = VALUES(updated_at)
                    """;
        }
        return """
                INSERT INTO duels (duel_id, challenger, target, kit_id, arena_id, wager, state,
                  escrowed, payout_done, snapshot_challenger, snapshot_target, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(duel_id) DO UPDATE SET
                  arena_id = excluded.arena_id, wager = excluded.wager, state = excluded.state,
                  escrowed = excluded.escrowed, payout_done = excluded.payout_done,
                  snapshot_challenger = excluded.snapshot_challenger,
                  snapshot_target = excluded.snapshot_target, updated_at = excluded.updated_at
                """;
    }

    public void upsertDuel(DuelRow row) {
        if (!isConnected() || row == null) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertDuelSql())) {
            ps.setString(1, row.duelId());
            ps.setString(2, row.challenger().toString());
            ps.setString(3, row.target().toString());
            ps.setString(4, row.kitId() == null ? "" : row.kitId());
            ps.setString(5, row.arenaId() == null ? "" : row.arenaId());
            ps.setDouble(6, row.wager());
            ps.setString(7, row.state());
            ps.setInt(8, row.escrowed() ? 1 : 0);
            ps.setInt(9, row.payoutDone() ? 1 : 0);
            ps.setString(10, row.snapshotChallenger() == null ? "" : row.snapshotChallenger());
            ps.setString(11, row.snapshotTarget() == null ? "" : row.snapshotTarget());
            ps.setLong(12, row.createdAt());
            ps.setLong(13, row.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert duel " + row.duelId(), e);
        }
    }

    public void deleteDuel(String duelId) {
        if (!isConnected() || duelId == null) {
            return;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM duels WHERE duel_id = ?")) {
            ps.setString(1, duelId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete duel " + duelId, e);
        }
    }

    /** Rows left over from a previous run that never reached a terminal state - used for crash recovery. */
    public List<DuelRow> loadAllDuels() {
        List<DuelRow> out = new ArrayList<>();
        if (!isConnected()) {
            return out;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT duel_id, challenger, target, kit_id, arena_id, wager, state, escrowed, "
                             + "payout_done, snapshot_challenger, snapshot_target, created_at, updated_at FROM duels");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(new DuelRow(
                            rs.getString(1),
                            UUID.fromString(rs.getString(2)),
                            UUID.fromString(rs.getString(3)),
                            rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getString(7),
                            rs.getInt(8) != 0, rs.getInt(9) != 0, rs.getString(10), rs.getString(11),
                            rs.getLong(12), rs.getLong(13)
                    ));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load duels from database", e);
        }
        return out;
    }

    public record DuelStatsRow(int wins, int losses, int currentStreak, int bestStreak,
                                double coinsWagered, double coinsWon, double coinsLost, int duelsPlayed, int elo,
                                int rankedWins, int rankedLosses, int casualWins, int casualLosses) {
        public static DuelStatsRow empty() {
            return new DuelStatsRow(0, 0, 0, 0, 0, 0, 0, 0, 1000, 0, 0, 0, 0);
        }
    }

    public Map<UUID, DuelStatsRow> loadAllDuelStats() {
        Map<UUID, DuelStatsRow> out = new ConcurrentHashMap<>();
        if (!isConnected()) {
            return out;
        }
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, wins, losses, current_streak, best_streak, coins_wagered, "
                             + "coins_won, coins_lost, duels_played, elo, ranked_wins, ranked_losses, "
                             + "casual_wins, casual_losses FROM duel_stats");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString(1)), new DuelStatsRow(
                            rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                            rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getInt(9),
                            rs.getInt(10) == 0 ? 1000 : rs.getInt(10),
                            rs.getInt(11), rs.getInt(12), rs.getInt(13), rs.getInt(14)
                    ));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load duel stats from database", e);
        }
        return out;
    }

    private String upsertDuelStatsSql() {
        if (mysql) {
            return """
                    INSERT INTO duel_stats (uuid, wins, losses, current_streak, best_streak,
                      coins_wagered, coins_won, coins_lost, duels_played, elo,
                      ranked_wins, ranked_losses, casual_wins, casual_losses)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      wins = VALUES(wins), losses = VALUES(losses),
                      current_streak = VALUES(current_streak), best_streak = VALUES(best_streak),
                      coins_wagered = VALUES(coins_wagered), coins_won = VALUES(coins_won),
                      coins_lost = VALUES(coins_lost), duels_played = VALUES(duels_played),
                      elo = VALUES(elo), ranked_wins = VALUES(ranked_wins),
                      ranked_losses = VALUES(ranked_losses), casual_wins = VALUES(casual_wins),
                      casual_losses = VALUES(casual_losses)
                    """;
        }
        return """
                INSERT INTO duel_stats (uuid, wins, losses, current_streak, best_streak,
                  coins_wagered, coins_won, coins_lost, duels_played, elo,
                  ranked_wins, ranked_losses, casual_wins, casual_losses)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                  wins = excluded.wins, losses = excluded.losses,
                  current_streak = excluded.current_streak, best_streak = excluded.best_streak,
                  coins_wagered = excluded.coins_wagered, coins_won = excluded.coins_won,
                  coins_lost = excluded.coins_lost, duels_played = excluded.duels_played,
                  elo = excluded.elo, ranked_wins = excluded.ranked_wins,
                  ranked_losses = excluded.ranked_losses, casual_wins = excluded.casual_wins,
                  casual_losses = excluded.casual_losses
                """;
    }

    public void upsertDuelStats(UUID uuid, DuelStatsRow row) {
        if (!isConnected() || uuid == null || row == null) {
            return;
        }
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(upsertDuelStatsSql())) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, row.wins());
            ps.setInt(3, row.losses());
            ps.setInt(4, row.currentStreak());
            ps.setInt(5, row.bestStreak());
            ps.setDouble(6, row.coinsWagered());
            ps.setDouble(7, row.coinsWon());
            ps.setDouble(8, row.coinsLost());
            ps.setInt(9, row.duelsPlayed());
            ps.setInt(10, row.elo());
            ps.setInt(11, row.rankedWins());
            ps.setInt(12, row.rankedLosses());
            ps.setInt(13, row.casualWins());
            ps.setInt(14, row.casualLosses());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to upsert duel stats for " + uuid, e);
        }
    }

    public record DuelHistoryRow(String duelId, UUID challenger, String challengerName, UUID target,
                                  String targetName, UUID winner, String kitId, String arenaId,
                                  double wager, double payout, String result, long startedAt, long endedAt) {
    }

    public void insertDuelHistory(DuelHistoryRow row) {
        if (!isConnected() || row == null) {
            return;
        }
        String sql = """
                INSERT INTO duel_history (duel_id, challenger, challenger_name, target, target_name,
                  winner, kit_id, arena_id, wager, payout, result, started_at, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, row.duelId());
            ps.setString(2, row.challenger().toString());
            ps.setString(3, row.challengerName() == null ? "" : row.challengerName());
            ps.setString(4, row.target().toString());
            ps.setString(5, row.targetName() == null ? "" : row.targetName());
            ps.setString(6, row.winner() == null ? "" : row.winner().toString());
            ps.setString(7, row.kitId() == null ? "" : row.kitId());
            ps.setString(8, row.arenaId() == null ? "" : row.arenaId());
            ps.setDouble(9, row.wager());
            ps.setDouble(10, row.payout());
            ps.setString(11, row.result() == null ? "" : row.result());
            ps.setLong(12, row.startedAt());
            ps.setLong(13, row.endedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to insert duel history row " + row.duelId(), e);
        }
    }

    public List<DuelHistoryRow> loadDuelHistory(UUID player, int limit, int offset) {
        List<DuelHistoryRow> out = new ArrayList<>();
        if (!isConnected() || player == null) {
            return out;
        }
        String sql = "SELECT duel_id, challenger, challenger_name, target, target_name, winner, "
                + "kit_id, arena_id, wager, payout, result, started_at, ended_at FROM duel_history "
                + "WHERE challenger = ? OR target = ? ORDER BY ended_at DESC LIMIT ? OFFSET ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, player.toString());
            ps.setInt(3, Math.max(1, Math.min(200, limit)));
            ps.setInt(4, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String winnerStr = rs.getString(6);
                    out.add(new DuelHistoryRow(
                            rs.getString(1),
                            UUID.fromString(rs.getString(2)), rs.getString(3),
                            UUID.fromString(rs.getString(4)), rs.getString(5),
                            (winnerStr == null || winnerStr.isBlank()) ? null : UUID.fromString(winnerStr),
                            rs.getString(7), rs.getString(8), rs.getDouble(9), rs.getDouble(10),
                            rs.getString(11), rs.getLong(12), rs.getLong(13)
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load duel history for " + player, e);
        }
        return out;
    }

    public int countDuelHistory(UUID player) {
        if (!isConnected() || player == null) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM duel_history WHERE challenger = ? OR target = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to count duel history for " + player, e);
        }
        return 0;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
