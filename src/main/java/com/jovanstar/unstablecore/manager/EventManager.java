package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EventManager {

    private final UnstableCore plugin;

    private double coinMultiplier = 1.0;
    private boolean coinActive;
    private long coinEndsAt;
    private long coinDurationMs;
    private long nextCoinAutoAt;
    private BukkitTask coinEndTask;
    private BukkitTask coinBossTask;
    private BossBar coinBossBar;

    private double streakMultiplier = 1.0;
    private boolean streakActive;
    private long streakEndsAt;
    private long streakDurationMs;
    private long nextStreakAutoAt;
    private BukkitTask streakEndTask;
    private BukkitTask streakBossTask;
    private BossBar streakBossBar;

    private BukkitTask ticker;

    public EventManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reload();
    }

    public void reload() {
        stopTicker();
        cancelTask(coinEndTask);
        coinEndTask = null;
        cancelTask(streakEndTask);
        streakEndTask = null;

        long now = System.currentTimeMillis();
        nextCoinAutoAt = resolveNext("next-auto-event", "auto-event.interval-seconds", 10800, now);
        nextStreakAutoAt = resolveNext("next-streak-event", "streak-event.interval-seconds", 14400, now);

        if (plugin.getConfig().getBoolean("auto-event.enabled", true)
                || plugin.getConfig().getBoolean("streak-event.enabled", true)) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long t = System.currentTimeMillis();
                if (plugin.getConfig().getBoolean("auto-event.enabled", true)
                        && !coinActive && t >= nextCoinAutoAt) {
                    double multi = plugin.getConfig().getDouble("auto-event.multiplier", 2.0);
                    int duration = plugin.getConfig().getInt("auto-event.duration-seconds", 300);
                    startCoinEvent(multi, duration, true);
                }
                if (plugin.getConfig().getBoolean("streak-event.enabled", true)
                        && !streakActive && t >= nextStreakAutoAt) {
                    double multi = plugin.getConfig().getDouble("streak-event.multiplier", 2.0);
                    int duration = plugin.getConfig().getInt("streak-event.duration-seconds", 300);
                    startStreakEvent(multi, duration, true);
                }
            }, 20L, 20L * 10);
        }

        if (coinActive) {
            long remainMs = coinEndsAt - System.currentTimeMillis();
            if (remainMs > 0) {
                coinEndTask = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> stopCoinEvent(true), Math.max(1L, remainMs / 50L));
                startCoinBossBar();
            } else {
                stopCoinEvent(false);
            }
        } else {
            clearCoinBossBar();
        }

        if (streakActive) {
            long remainMs = streakEndsAt - System.currentTimeMillis();
            if (remainMs > 0) {
                streakEndTask = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> stopStreakEvent(true), Math.max(1L, remainMs / 50L));
                startStreakBossBar();
            } else {
                stopStreakEvent(false);
            }
        } else {
            clearStreakBossBar();
        }
    }

    public void stop() {
        stopTicker();
        cancelTask(coinEndTask);
        coinEndTask = null;
        cancelTask(streakEndTask);
        streakEndTask = null;
        clearCoinBossBar();
        clearStreakBossBar();
        persistNextCoin();
        persistNextStreak();
    }

    private void stopTicker() {
        cancelTask(ticker);
        ticker = null;
    }

    private static void cancelTask(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private long resolveNext(String dataKey, String intervalPath, long defSeconds, long now) {
        long interval = plugin.getConfig().getLong(intervalPath, defSeconds);
        long saved = plugin.getConfigManager().getData().getLong(dataKey, 0L);
        if (saved > now) {
            return saved;
        }
        long next = now + interval * 1000L;
        plugin.getConfigManager().getData().set(dataKey, next);
        plugin.getConfigManager().saveData();
        return next;
    }

    public void startEvent(double multi, int durationSeconds, boolean automatic) {
        startCoinEvent(multi, durationSeconds, automatic);
    }

    public void startCoinEvent(double multi, int durationSeconds, boolean automatic) {
        cancelTask(coinEndTask);
        coinMultiplier = Math.max(1.0, multi);
        coinActive = true;
        coinDurationMs = Math.max(1, durationSeconds) * 1000L;
        coinEndsAt = System.currentTimeMillis() + coinDurationMs;

        broadcastConfig("auto-event.start-broadcast", List.of(
                "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                "&dUNSTABLEPVP &f» &6★ &6&l{multiplier}x COIN BONUS",
                " &d■ &7Every kill pays out &6{multiplier}x &7for &f{duration}&7!",
                " &d■ &eStack kills while the bonus lasts!",
                "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
        ), Map.of(
                "multiplier", formatMulti(coinMultiplier),
                "duration", formatDuration(durationSeconds)
        ));
        playSound(plugin.getConfig().getString("auto-event.start-sound", "ENTITY_PLAYER_LEVELUP"));

        coinEndTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> stopCoinEvent(true), durationSeconds * 20L);
        startCoinBossBar();
    }

    public void startStreakEvent(double multi, int durationSeconds, boolean automatic) {
        cancelTask(streakEndTask);
        streakMultiplier = Math.max(1.0, multi);
        streakActive = true;
        streakDurationMs = Math.max(1, durationSeconds) * 1000L;
        streakEndsAt = System.currentTimeMillis() + streakDurationMs;

        broadcastConfig("streak-event.start-broadcast", List.of(
                "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                "&dUNSTABLEPVP &f» &d★ &d&l{multiplier}x STREAK BONUS",
                " &d■ &7Every kill adds &d{multiplier}x &7to your streak for &f{duration}&7!",
                " &d■ &ePush your streak while the bonus lasts!",
                "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
        ), Map.of(
                "multiplier", formatMulti(streakMultiplier),
                "duration", formatDuration(durationSeconds)
        ));
        playSound(plugin.getConfig().getString("streak-event.start-sound", "ENTITY_PLAYER_LEVELUP"));

        streakEndTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> stopStreakEvent(true), durationSeconds * 20L);
        startStreakBossBar();
    }

    public void stopEvent(boolean broadcast) {
        stopCoinEvent(broadcast);
    }

    public void stopCoinEvent(boolean broadcast) {
        if (!coinActive) {
            return;
        }
        coinActive = false;
        coinMultiplier = 1.0;
        coinEndsAt = 0;
        coinDurationMs = 0;
        cancelTask(coinEndTask);
        coinEndTask = null;
        clearCoinBossBar();

        long interval = plugin.getConfig().getLong("auto-event.interval-seconds", 10800);
        nextCoinAutoAt = System.currentTimeMillis() + interval * 1000L;
        persistNextCoin();

        if (broadcast) {
            broadcastConfig("auto-event.end-broadcast", List.of(
                    "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                    "&dUNSTABLEPVP &f» &6★ &7The &6coin bonus &7has &cended&7.",
                    "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            ), Map.of());
            playSound(plugin.getConfig().getString("auto-event.end-sound", "BLOCK_NOTE_BLOCK_BASS"));
        }
    }

    public void stopStreakEvent(boolean broadcast) {
        if (!streakActive) {
            return;
        }
        streakActive = false;
        streakMultiplier = 1.0;
        streakEndsAt = 0;
        streakDurationMs = 0;
        cancelTask(streakEndTask);
        streakEndTask = null;
        clearStreakBossBar();

        long interval = plugin.getConfig().getLong("streak-event.interval-seconds", 14400);
        nextStreakAutoAt = System.currentTimeMillis() + interval * 1000L;
        persistNextStreak();

        if (broadcast) {
            broadcastConfig("streak-event.end-broadcast", List.of(
                    "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                    "&dUNSTABLEPVP &f» &d★ &7The &dstreak bonus &7has &cended&7.",
                    "&8▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            ), Map.of());
            playSound(plugin.getConfig().getString("streak-event.end-sound", "BLOCK_NOTE_BLOCK_BASS"));
        }
    }

    private void broadcastConfig(String path, List<String> fallback, Map<String, String> placeholders) {
        List<String> lines = plugin.getConfig().getStringList(path);
        if (lines == null || lines.isEmpty()) {
            String single = plugin.getConfig().getString(path, "");
            if (single != null && !single.isBlank()) {
                MessageUtil.broadcast(single, placeholders);
                return;
            }
            lines = fallback;
        }
        MessageUtil.broadcastLines(lines, placeholders);
    }

    public void showBossBar(Player player) {
        if (player == null) {
            return;
        }
        if (coinActive && coinBossBar != null) {
            player.showBossBar(coinBossBar);
        }
        if (streakActive && streakBossBar != null) {
            player.showBossBar(streakBossBar);
        }
    }

    private void startCoinBossBar() {
        clearCoinBossBar();
        if (!plugin.getConfig().getBoolean("auto-event.bossbar.enabled", true)) {
            return;
        }
        BossBar.Color color = parseColor(plugin.getConfig().getString("auto-event.bossbar.color", "YELLOW"));
        BossBar.Overlay overlay = parseOverlay(plugin.getConfig().getString("auto-event.bossbar.style", "SOLID"));
        coinBossBar = BossBar.bossBar(MessageUtil.parse(" "), 1.0f, color, overlay);
        updateCoinBossBar();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(coinBossBar);
        }
        long updateTicks = Math.max(1L, plugin.getConfig().getLong("auto-event.bossbar.update-ticks", 20L));
        coinBossTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateCoinBossBar, updateTicks, updateTicks);
    }

    private void startStreakBossBar() {
        clearStreakBossBar();
        if (!plugin.getConfig().getBoolean("streak-event.bossbar.enabled", true)) {
            return;
        }
        BossBar.Color color = parseColor(plugin.getConfig().getString("streak-event.bossbar.color", "PINK"));
        BossBar.Overlay overlay = parseOverlay(plugin.getConfig().getString("streak-event.bossbar.style", "SOLID"));
        streakBossBar = BossBar.bossBar(MessageUtil.parse(" "), 1.0f, color, overlay);
        updateStreakBossBar();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(streakBossBar);
        }
        long updateTicks = Math.max(1L, plugin.getConfig().getLong("streak-event.bossbar.update-ticks", 20L));
        streakBossTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateStreakBossBar, updateTicks, updateTicks);
    }

    private void updateCoinBossBar() {
        if (!coinActive || coinBossBar == null) {
            return;
        }
        long remainMs = Math.max(0, coinEndsAt - System.currentTimeMillis());
        float progress = coinDurationMs <= 0 ? 0f
                : (float) Math.max(0.0, Math.min(1.0, (double) remainMs / (double) coinDurationMs));
        String template = plugin.getConfig().getString(
                "auto-event.bossbar.title",
                "&6★ &6{multiplier}x &cCOIN &6BONUS &6★ &f| &e{time} &7remaining"
        );
        coinBossBar.name(MessageUtil.parse(MessageUtil.apply(template, Map.of(
                "multiplier", formatMulti(coinMultiplier),
                "time", formatDurationMillis(remainMs),
                "duration", formatDurationMillis(coinDurationMs)
        ))));
        coinBossBar.progress(progress);
    }

    private void updateStreakBossBar() {
        if (!streakActive || streakBossBar == null) {
            return;
        }
        long remainMs = Math.max(0, streakEndsAt - System.currentTimeMillis());
        float progress = streakDurationMs <= 0 ? 0f
                : (float) Math.max(0.0, Math.min(1.0, (double) remainMs / (double) streakDurationMs));
        String template = plugin.getConfig().getString(
                "streak-event.bossbar.title",
                "&d★ &d{multiplier}x &cSTREAK &dBONUS &d★ &f| &e{time} &7remaining"
        );
        streakBossBar.name(MessageUtil.parse(MessageUtil.apply(template, Map.of(
                "multiplier", formatMulti(streakMultiplier),
                "time", formatDurationMillis(remainMs),
                "duration", formatDurationMillis(streakDurationMs)
        ))));
        streakBossBar.progress(progress);
    }

    private void clearCoinBossBar() {
        cancelTask(coinBossTask);
        coinBossTask = null;
        if (coinBossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(coinBossBar);
            }
            coinBossBar = null;
        }
    }

    private void clearStreakBossBar() {
        cancelTask(streakBossTask);
        streakBossTask = null;
        if (streakBossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(streakBossBar);
            }
            streakBossBar = null;
        }
    }

    private static BossBar.Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return BossBar.Color.YELLOW;
        }
        try {
            return BossBar.Color.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossBar.Color.YELLOW;
        }
    }

    private static BossBar.Overlay parseOverlay(String raw) {
        if (raw == null || raw.isBlank()) {
            return BossBar.Overlay.PROGRESS;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "SOLID", "PROGRESS" -> BossBar.Overlay.PROGRESS;
            case "SEGMENTED_6", "NOTCHED_6" -> BossBar.Overlay.NOTCHED_6;
            case "SEGMENTED_10", "NOTCHED_10" -> BossBar.Overlay.NOTCHED_10;
            case "SEGMENTED_12", "NOTCHED_12" -> BossBar.Overlay.NOTCHED_12;
            case "SEGMENTED_20", "NOTCHED_20" -> BossBar.Overlay.NOTCHED_20;
            default -> {
                try {
                    yield BossBar.Overlay.valueOf(key);
                } catch (IllegalArgumentException ignored) {
                    yield BossBar.Overlay.PROGRESS;
                }
            }
        };
    }

    private void persistNextCoin() {
        plugin.getConfigManager().getData().set("next-auto-event", nextCoinAutoAt);
        plugin.getConfigManager().saveData();
    }

    private void persistNextStreak() {
        plugin.getConfigManager().getData().set("next-streak-event", nextStreakAutoAt);
        plugin.getConfigManager().saveData();
    }

    private void playSound(String name) {
        try {
            Sound sound = Sound.valueOf(name);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), sound, 1f, 1f);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    public double getMultiplier() {
        return coinActive ? coinMultiplier : 1.0;
    }

    public double getStreakMultiplier() {
        return streakActive ? streakMultiplier : 1.0;
    }

    public int streakGain() {
        if (!streakActive) {
            return 1;
        }
        return Math.max(1, (int) Math.round(streakMultiplier));
    }

    public boolean isActive() {
        return coinActive;
    }

    public boolean isCoinActive() {
        return coinActive;
    }

    public boolean isStreakActive() {
        return streakActive;
    }

    public long getMillisUntilNextAuto() {
        if (coinActive) {
            return 0;
        }
        return Math.max(0, nextCoinAutoAt - System.currentTimeMillis());
    }

    public long getMillisUntilEventEnds() {
        if (!coinActive) {
            return 0;
        }
        return Math.max(0, coinEndsAt - System.currentTimeMillis());
    }

    public long getMillisUntilStreakEnds() {
        if (!streakActive) {
            return 0;
        }
        return Math.max(0, streakEndsAt - System.currentTimeMillis());
    }

    public String formatTimeUntilNext() {
        return formatDurationMillis(getMillisUntilNextAuto());
    }

    public static String formatDuration(int seconds) {
        return formatDurationMillis(seconds * 1000L);
    }

    public static String formatDurationMillis(long millis) {
        long totalSec = Math.max(0, millis / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    public static String formatDurationDhms(long millis) {
        long totalSec = Math.max(0, millis / 1000);
        long d = totalSec / 86400;
        long h = (totalSec % 86400) / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (d == 0 && h == 0 && m == 0) {
            return s + "s";
        }
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append('d');
        }
        if (h > 0 || d > 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(h).append('h');
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(m).append('m').append(' ').append(s).append('s');
        return sb.toString();
    }

    private static String formatMulti(double multi) {
        if (multi == (long) multi) {
            return String.valueOf((long) multi);
        }
        return String.format("%.1f", multi);
    }
}
