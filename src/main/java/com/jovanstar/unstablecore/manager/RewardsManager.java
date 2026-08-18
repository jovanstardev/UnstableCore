package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RewardsManager {

    public enum Tab { DAILY, WEEKLY, MONTHLY }

    public enum DayState { CLAIMABLE, CLAIMED, LOCKED }

    private final UnstableCore plugin;
    private final Map<UUID, PlayerRewards> cache = new ConcurrentHashMap<>();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public RewardsManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    private Object lockFor(UUID uuid) {
        return locks.computeIfAbsent(uuid, id -> new Object());
    }

    public org.bukkit.configuration.file.FileConfiguration cfg() {
        return plugin.getConfigManager().getRewards();
    }

    public boolean isEnabled() {
        return cfg() != null && cfg().getBoolean("enabled", true);
    }

    public ZoneId zone() {
        String raw = cfg().getString("timezone", "");
        if (raw != null && !raw.isBlank()) {
            try {
                return ZoneId.of(raw.trim());
            } catch (Exception ignored) {
            }
        }
        return ZoneId.systemDefault();
    }

    public LocalDate today() {
        return LocalDate.now(zone());
    }

    public PlayerRewards get(UUID uuid) {
        synchronized (lockFor(uuid)) {
            return cache.computeIfAbsent(uuid, this::load);
        }
    }

    
    public PlayerRewards peek(UUID uuid) {
        return cache.get(uuid);
    }

    private PlayerRewards load(UUID uuid) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return PlayerRewards.empty();
        }
        return PlayerRewards.from(db.loadRewards(uuid));
    }

    /**
     * Called from PlayerQuitEvent. The write goes off-thread (it used to be a synchronous JDBC
     * round trip on the main thread for every single disconnect - fine one at a time, a stall
     * proportional to player count during a mass disconnect or restart), but the cache entry is
     * kept until that write lands: dropping it first would let a fast reconnect re-read the
     * pre-save row from the database and re-claim an already-claimed reward.
     */
    public void unload(UUID uuid) {
        PlayerRewards data;
        synchronized (lockFor(uuid)) {
            data = cache.get(uuid);
            claiming.remove(uuid);
        }
        if (data == null) {
            cache.remove(uuid);
            locks.remove(uuid);
            return;
        }
        DatabaseManager.RewardsRow snapshot;
        synchronized (lockFor(uuid)) {
            snapshot = data.toRow();
        }
        if (!plugin.isEnabled()) {
            // Mid-disable (e.g. a plugin reload kicking players): the scheduler refuses tasks from
            // a disabled plugin, so deferring here would throw and drop the write entirely. Take
            // the synchronous path - correctness beats latency on a path that is shutting down.
            save(uuid, data);
            cache.remove(uuid);
            locks.remove(uuid);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.isConnected()) {
                db.saveRewards(uuid, snapshot);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Only evict if they are still gone - a reconnect in the meantime must keep its
                // (now authoritative) in-memory state rather than fall back to a DB re-read.
                if (Bukkit.getPlayer(uuid) == null) {
                    cache.remove(uuid);
                    locks.remove(uuid);
                }
            });
        });
    }

    public void save(UUID uuid, PlayerRewards data) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected() || data == null) {
            return;
        }
        db.saveRewards(uuid, data.toRow());
    }

    public void touchLogin(UUID uuid) {
        synchronized (lockFor(uuid)) {
            PlayerRewards data = cache.computeIfAbsent(uuid, this::load);
            if (registerLoginDay(data)) {
                DatabaseManager.RewardsRow snapshot = data.toRow();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    DatabaseManager db = plugin.getDatabaseManager();
                    if (db != null && db.isConnected()) {
                        db.saveRewards(uuid, snapshot);
                    }
                });
            }
        }
    }

    public void handleJoin(Player player) {
        if (player == null || !isEnabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean hasDaily;
            boolean hasWeekly;
            boolean hasMonthly;
            synchronized (lockFor(uuid)) {
                PlayerRewards data = cache.computeIfAbsent(uuid, this::load);
                if (registerLoginDay(data)) {
                    save(uuid, data);
                }
                hasDaily = claimableDailyDay(data) > 0;
                hasWeekly = hasUnclaimedMilestone(data, true);
                hasMonthly = hasUnclaimedMilestone(data, false);
            }
            if (!hasDaily && !hasWeekly && !hasMonthly) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (hasDaily) {
                    msg(player, "unclaimed-daily", Map.of());
                } else if (hasWeekly) {
                    msg(player, "unclaimed-weekly", Map.of());
                } else {
                    msg(player, "unclaimed-monthly", Map.of());
                }
            });
        });
    }

    private boolean hasUnclaimedMilestone(PlayerRewards data, boolean weekly) {
        for (Integer required : milestones(weekly).keySet()) {
            if (milestoneState(data, weekly, required) == DayState.CLAIMABLE) {
                return true;
            }
        }
        return false;
    }

    public boolean registerLoginDay(PlayerRewards data) {
        LocalDate today = today();
        String todayStr = today.toString();
        if (todayStr.equals(data.lastLoginDay)) {
            return false;
        }

        if (!data.lastClaimDay.isBlank()) {
            LocalDate lastClaim = LocalDate.parse(data.lastClaimDay);
            long gap = ChronoUnit.DAYS.between(lastClaim, today);
            if (gap > 1) {
                data.streak = 0;
            }
        }

        String weekId = weekId(today);
        if (!weekId.equals(data.weekId)) {
            data.weekId = weekId;
            data.weekDays = 0;
            data.weekClaimed.clear();
        }
        data.weekDays = Math.min(7, data.weekDays + 1);

        String monthId = monthId(today);
        if (!monthId.equals(data.monthId)) {
            data.monthId = monthId;
            data.monthDays = 0;
            data.monthClaimed.clear();
        }
        int daysInMonth = today.lengthOfMonth();
        data.monthDays = Math.min(daysInMonth, data.monthDays + 1);

        data.lastLoginDay = todayStr;
        return true;
    }

    public String weekId(LocalDate date) {
        WeekFields wf = WeekFields.of(DayOfWeek.MONDAY, 4);
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return year + "-W" + String.format("%02d", week);
    }

    public String monthId(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public int claimableDailyDay(PlayerRewards data) {
        LocalDate today = today();
        String todayStr = today.toString();
        if (todayStr.equals(data.lastClaimDay)) {
            return 0;
        }
        if (data.lastClaimDay.isBlank()) {
            return 1;
        }
        LocalDate last = LocalDate.parse(data.lastClaimDay);
        long gap = ChronoUnit.DAYS.between(last, today);
        if (gap == 1) {
            int next = data.streak + 1;
            int max = maxDailyDay();
            return next > max ? 1 : next;
        }

        return 1;
    }

    public DayState dailyState(PlayerRewards data, int day) {
        int claimable = claimableDailyDay(data);
        String todayStr = today().toString();
        if (todayStr.equals(data.lastClaimDay) && data.streak == day) {
            return DayState.CLAIMED;
        }

        if (claimable == 0) {

            if (day < data.streak) {
                return DayState.CLAIMED;
            }
            if (day == data.streak) {
                return DayState.CLAIMED;
            }
            return DayState.LOCKED;
        }
        if (day == claimable) {
            return DayState.CLAIMABLE;
        }
        if (data.streak > 0 && day < claimable && ChronoUnit.DAYS.between(LocalDate.parse(data.lastClaimDay), today()) == 1) {
            return DayState.CLAIMED;
        }

        if (claimable == 1 && day > 1) {
            return DayState.LOCKED;
        }
        if (day < claimable) {
            return DayState.CLAIMED;
        }
        return DayState.LOCKED;
    }

    public boolean claimDaily(Player player, int day) {
        if (player == null || day < 1 || day > maxDailyDay()) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        synchronized (lockFor(uuid)) {
            if (!claiming.add(uuid)) {
                msg(player, "busy", Map.of());
                return false;
            }
            try {
                PlayerRewards data = cache.computeIfAbsent(uuid, this::load);
                registerLoginDay(data);

                if (claimableDailyDay(data) != day) {
                    msg(player, "not-ready", Map.of());
                    return false;
                }

                ConfigurationSection dayCfg = cfg().getConfigurationSection("daily.days." + day);
                double coins = dayCfg == null ? 0 : dayCfg.getDouble("coins", 0);
                int boosterHours = dayCfg == null ? 0 : dayCfg.getInt("booster-hours", 0);

                String todayStr = today().toString();
                long boosterUntil = data.boosterUntil;
                if (boosterHours > 0) {
                    long add = boosterHours * 3_600_000L;
                    long base = Math.max(System.currentTimeMillis(), data.boosterUntil);
                    boosterUntil = base + add;
                }

                PlayerRewards proposed = PlayerRewards.from(data.toRow());
                proposed.streak = day;
                proposed.lastClaimDay = todayStr;
                proposed.boosterUntil = boosterUntil;

                if (!plugin.getDatabaseManager().tryMarkDailyClaim(uuid, proposed.toRow(), todayStr)) {
                    msg(player, "already-claimed", Map.of());
                    PlayerRewards fresh = PlayerRewards.from(plugin.getDatabaseManager().loadRewards(uuid));
                    cache.put(uuid, fresh);
                    return false;
                }

                if (coins > 0 && !plugin.getEconomyManager().deposit(player, coins)) {
                    plugin.getLogger().warning("Daily reward deposit failed for " + player.getName()
                            + " day " + day + " (" + coins + " coins). Claim kept.");
                }

                cache.put(uuid, proposed);
                msg(player, "daily-claimed", Map.of(
                        "day", String.valueOf(day),
                        "coins", EconomyManager.format(coins)
                ));
                if (boosterHours > 0) {
                    msg(player, "booster-activated", Map.of(
                            "hours", String.valueOf(boosterHours)
                    ));
                }
                return true;
            } finally {
                claiming.remove(uuid);
            }
        }
    }

    public DayState milestoneState(PlayerRewards data, boolean weekly, int required) {
        Set<Integer> claimed = weekly ? data.weekClaimed : data.monthClaimed;
        int progress = weekly ? data.weekDays : data.monthDays;
        if (claimed.contains(required)) {
            return DayState.CLAIMED;
        }
        if (progress >= required) {
            return DayState.CLAIMABLE;
        }
        return DayState.LOCKED;
    }

    public boolean claimMilestone(Player player, boolean weekly, int required) {
        if (player == null || required <= 0 || !isEnabled()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        synchronized (lockFor(uuid)) {
            if (!claiming.add(uuid)) {
                msg(player, "busy", Map.of());
                return false;
            }
            try {
                PlayerRewards data = cache.computeIfAbsent(uuid, this::load);
                registerLoginDay(data);

                if (milestoneState(data, weekly, required) != DayState.CLAIMABLE) {
                    msg(player, "not-ready", Map.of());
                    return false;
                }

                String path = weekly ? "weekly.milestones." + required : "monthly.milestones." + required;
                ConfigurationSection sec = cfg().getConfigurationSection(path);
                double coins = sec == null ? 0 : sec.getDouble("coins", 0);
                int boosterHours = sec == null ? 0 : sec.getInt("booster-hours", 0);

                PlayerRewards proposed = PlayerRewards.from(data.toRow());
                Set<Integer> claimed = weekly ? proposed.weekClaimed : proposed.monthClaimed;
                if (!claimed.add(required)) {
                    msg(player, "already-claimed", Map.of());
                    return false;
                }

                if (boosterHours > 0) {
                    long add = boosterHours * 3_600_000L;
                    long base = Math.max(System.currentTimeMillis(), proposed.boosterUntil);
                    proposed.boosterUntil = base + add;
                }

                if (!plugin.getDatabaseManager().tryMarkMilestoneClaim(
                        uuid, proposed.toRow(), weekly, required)) {
                    msg(player, "already-claimed", Map.of());
                    PlayerRewards fresh = PlayerRewards.from(plugin.getDatabaseManager().loadRewards(uuid));
                    cache.put(uuid, fresh);
                    return false;
                }

                if (coins > 0 && !plugin.getEconomyManager().deposit(player, coins)) {
                    plugin.getLogger().warning("Milestone reward deposit failed for " + player.getName()
                            + " (" + coins + " coins). Claim kept.");
                }

                cache.put(uuid, proposed);
                msg(player, "milestone-claimed", Map.of(
                        "days", String.valueOf(required),
                        "coins", EconomyManager.format(coins),
                        "type", weekly ? "weekly" : "monthly"
                ));
                if (boosterHours > 0) {
                    msg(player, "booster-activated", Map.of(
                            "hours", String.valueOf(boosterHours)
                    ));
                }
                return true;
            } finally {
                claiming.remove(uuid);
            }
        }
    }

    public boolean hasBooster(UUID uuid) {
        if (hasBoosterPermission(uuid)) {
            return true;
        }
        return getBoosterRemainingMs(uuid) > 0;
    }

    public boolean hasBoosterPermission(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return false;
        }
        String perm = cfg().getString("booster.permission", "unstablecore.booster.2x");
        if (perm == null || perm.isBlank()) {
            return false;
        }
        return player.hasPermission(perm.trim());
    }

    public long getBoosterRemainingMs(UUID uuid) {
        PlayerRewards data = peek(uuid);
        if (data == null) {
            return 0L;
        }
        return Math.max(0L, data.boosterUntil - System.currentTimeMillis());
    }

    public double boosterMultiplier() {
        return Math.max(1.0, cfg().getDouble("booster.multiplier", 2.0));
    }

    public long millisUntilNextDailyReset() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(zone());
        return Math.max(0L, ChronoUnit.MILLIS.between(now, next));
    }

    public long millisUntilWeekReset() {
        LocalDate today = today();
        LocalDate nextMonday = today.with(DayOfWeek.MONDAY);
        if (!nextMonday.isAfter(today)) {
            nextMonday = nextMonday.plusWeeks(1);
        }
        ZonedDateTime now = ZonedDateTime.now(zone());
        ZonedDateTime end = nextMonday.atStartOfDay(zone());
        return Math.max(0L, ChronoUnit.MILLIS.between(now, end));
    }

    public static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long d = totalSec / 86400;
        long h = (totalSec % 86400) / 3600;
        long m = (totalSec % 3600) / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return Math.max(1, m) + "m";
    }

    public int maxDailyDay() {
        ConfigurationSection root = cfg().getConfigurationSection("daily.days");
        if (root == null) {
            return 7;
        }
        int max = 0;
        for (String key : root.getKeys(false)) {
            try {
                max = Math.max(max, Integer.parseInt(key));
            } catch (NumberFormatException ignored) {
            }
        }
        return Math.max(1, max);
    }

    public Map<Integer, ConfigurationSection> dailyDays() {
        Map<Integer, ConfigurationSection> map = new LinkedHashMap<>();
        ConfigurationSection root = cfg().getConfigurationSection("daily.days");
        if (root == null) {
            return map;
        }
        root.getKeys(false).stream()
                .map(k -> {
                    try {
                        return Integer.parseInt(k);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(i -> i != null)
                .sorted()
                .forEach(i -> {
                    ConfigurationSection sec = root.getConfigurationSection(String.valueOf(i));
                    if (sec != null) {
                        map.put(i, sec);
                    }
                });
        return map;
    }

    public Map<Integer, ConfigurationSection> milestones(boolean weekly) {
        Map<Integer, ConfigurationSection> map = new LinkedHashMap<>();
        String path = weekly ? "weekly.milestones" : "monthly.milestones";
        ConfigurationSection root = cfg().getConfigurationSection(path);
        if (root == null) {
            return map;
        }
        root.getKeys(false).stream()
                .map(k -> {
                    try {
                        return Integer.parseInt(k);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(i -> i != null)
                .sorted()
                .forEach(i -> {
                    ConfigurationSection sec = root.getConfigurationSection(String.valueOf(i));
                    if (sec != null) {
                        map.put(i, sec);
                    }
                });
        return map;
    }

    public void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = cfg().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    public static final class PlayerRewards {
        public int streak;
        public String lastClaimDay = "";
        public String lastLoginDay = "";
        public String weekId = "";
        public int weekDays;
        public final Set<Integer> weekClaimed = ConcurrentHashMap.newKeySet();
        public String monthId = "";
        public int monthDays;
        public final Set<Integer> monthClaimed = ConcurrentHashMap.newKeySet();
        public long boosterUntil;

        public static PlayerRewards empty() {
            return new PlayerRewards();
        }

        public static PlayerRewards from(DatabaseManager.RewardsRow row) {
            PlayerRewards r = new PlayerRewards();
            if (row == null) {
                return r;
            }
            r.streak = row.streak();
            r.lastClaimDay = row.lastClaimDay() == null ? "" : row.lastClaimDay();
            r.lastLoginDay = row.lastLoginDay() == null ? "" : row.lastLoginDay();
            r.weekId = row.weekId() == null ? "" : row.weekId();
            r.weekDays = row.weekDays();
            r.weekClaimed.addAll(parseInts(row.weekClaimed()));
            r.monthId = row.monthId() == null ? "" : row.monthId();
            r.monthDays = row.monthDays();
            r.monthClaimed.addAll(parseInts(row.monthClaimed()));
            r.boosterUntil = row.boosterUntil();
            return r;
        }

        public DatabaseManager.RewardsRow toRow() {
            return new DatabaseManager.RewardsRow(
                    streak,
                    lastClaimDay,
                    lastLoginDay,
                    weekId,
                    weekDays,
                    joinInts(weekClaimed),
                    monthId,
                    monthDays,
                    joinInts(monthClaimed),
                    boosterUntil
            );
        }

        private static Set<Integer> parseInts(String raw) {
            if (raw == null || raw.isBlank()) {
                return Collections.emptySet();
            }
            Set<Integer> set = new HashSet<>();
            for (String part : raw.split(",")) {
                try {
                    set.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
            return set;
        }

        private static String joinInts(Set<Integer> set) {
            if (set == null || set.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            set.stream().sorted().forEach(i -> {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(i);
            });
            return sb.toString();
        }
    }
}
