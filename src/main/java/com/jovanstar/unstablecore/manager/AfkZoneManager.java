package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.hook.WorldGuardHook;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkZoneManager {

    public static final class AfkDailyData {
        public String dayId;
        public int coinsEarned;
        public boolean notified;

        public AfkDailyData(String dayId, int coinsEarned, boolean notified) {
            this.dayId = dayId;
            this.coinsEarned = coinsEarned;
            this.notified = notified;
        }
    }

    private final UnstableCore plugin;
    private final Map<UUID, Integer> afkTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inZone = new ConcurrentHashMap<>();
    private final Map<UUID, AfkDailyData> dailyData = new ConcurrentHashMap<>();

    private BukkitTask task;

    private boolean enabled;
    private String region;
    private String worldFilter;
    private int tickSeconds;
    private int rewardInterval;
    private int defaultReward;
    private int dailyCap;
    private boolean requireWater;
    private List<Map.Entry<String, Integer>> rewardLadder = List.of();

    private List<String> enterMessages = List.of();
    private String leaveMessage = "";
    private String rewardMessage = "";
    private String actionbarMessage = "";
    private String actionbarCappedMessage = "";
    private String capReachedMessage = "";

    public AfkZoneManager(UnstableCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.getConfigManager().reloadAfk();
        FileConfiguration cfg = plugin.getConfigManager().getAfk();

        enabled = cfg.getBoolean("enabled", true);
        region = cfg.getString("region", "afk");
        worldFilter = cfg.getString("world", "");
        tickSeconds = Math.max(1, cfg.getInt("tick-seconds", 5));
        rewardInterval = Math.max(tickSeconds, cfg.getInt("reward-interval", 300));
        defaultReward = cfg.getInt("default-reward", 5);
        dailyCap = cfg.getInt("daily-cap", 1000);
        requireWater = cfg.getBoolean("require-water", true);

        List<Map.Entry<String, Integer>> ladder = new ArrayList<>();
        for (Object obj : cfg.getMapList("rewards")) {
            if (obj instanceof Map<?, ?> map) {
                Object perm = map.get("permission");
                Object amount = map.get("amount");
                if (perm != null && amount != null) {
                    ladder.add(Map.entry(String.valueOf(perm), ((Number) amount).intValue()));
                }
            }
        }
        ConfigurationSection section = cfg.getConfigurationSection("rewards");
        if (ladder.isEmpty() && section != null) {
            for (String key : section.getKeys(false)) {
                ladder.add(Map.entry(
                        section.getString(key + ".permission", key),
                        section.getInt(key + ".amount", defaultReward)
                ));
            }
        }
        rewardLadder = List.copyOf(ladder);

        enterMessages = List.copyOf(cfg.getStringList("messages.enter"));
        leaveMessage = cfg.getString("messages.leave", "");
        rewardMessage = cfg.getString("messages.reward", "");
        actionbarMessage = cfg.getString("messages.actionbar", "");
        actionbarCappedMessage = cfg.getString("messages.actionbar-capped", "&e&lAFK ZONE &8| &cDaily Cap Reached &8(&e{earned}&7/&e{cap}&8)");
        capReachedMessage = cfg.getString("messages.cap-reached", "<#FF9C59>&lUUFFA &8• &cYou have reached your daily AFK coin cap of {cap} coins for today.");

        if (task != null) {
            task.cancel();
            task = null;
        }
        if (enabled) {
            start();
        }
    }

    public void start() {
        if (task != null || !enabled) {
            return;
        }
        if (!WorldGuardHook.isPresent()) {
            plugin.getLogger().warning("WorldGuard not found - AFK zone disabled.");
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, tickSeconds * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        afkTime.clear();
        inZone.clear();
    }

    private void tick() {

        Iterable<? extends Player> players;
        if (worldFilter != null && !worldFilter.isBlank()) {
            World world = Bukkit.getWorld(worldFilter);
            if (world == null) {
                return;
            }
            players = world.getPlayers();
        } else {
            players = Bukkit.getOnlinePlayers();
        }

        for (Player player : players) {
            boolean wasInZone = inZone.containsKey(player.getUniqueId());
            boolean inside = WorldGuardHook.isInRegion(player, region);
            if (inside && requireWater && !player.isInWater()) {
                inside = false;
            }

            if (inside) {
                handleInZone(player);
            } else if (wasInZone) {
                leaveZone(player);
            }
        }
    }

    private void handleInZone(Player player) {
        UUID uuid = player.getUniqueId();
        int reward = getRewardAmount(player);
        int earnedToday = getEarnedToday(uuid);

        if (!Boolean.TRUE.equals(inZone.get(uuid))) {
            inZone.put(uuid, true);
            afkTime.putIfAbsent(uuid, 0);
            int minutes = Math.max(1, rewardInterval / 60);
            Map<String, String> ph = Map.of(
                    "amount", String.valueOf(reward),
                    "minutes", String.valueOf(minutes),
                    "earned", String.valueOf(earnedToday),
                    "cap", String.valueOf(dailyCap)
            );
            for (String line : enterMessages) {
                MessageUtil.send(player, MessageUtil.apply(line, ph));
            }
        }

        if (dailyCap > 0 && earnedToday >= dailyCap) {
            MessageUtil.actionBar(player, MessageUtil.apply(
                    actionbarCappedMessage,
                    Map.of(
                            "earned", String.valueOf(earnedToday),
                            "cap", String.valueOf(dailyCap)
                    )
            ));
            return;
        }

        int progress = afkTime.merge(uuid, tickSeconds, Integer::sum);

        if (progress >= rewardInterval) {
            afkTime.put(uuid, 0);
            int toPay = reward;
            if (dailyCap > 0) {
                int remaining = Math.max(0, dailyCap - earnedToday);
                toPay = Math.min(reward, remaining);
            }
            if (toPay > 0) {
                plugin.getEconomyManager().deposit(player, toPay);
                addEarnedToday(uuid, toPay);
                earnedToday += toPay;
                MessageUtil.send(player, MessageUtil.apply(rewardMessage, Map.of(
                        "amount", String.valueOf(toPay),
                        "earned", String.valueOf(earnedToday),
                        "cap", String.valueOf(dailyCap)
                )));
                if (dailyCap > 0 && earnedToday >= dailyCap) {
                    AfkDailyData d = getOrLoadDailyData(uuid);
                    if (!d.notified) {
                        d.notified = true;
                        MessageUtil.send(player, MessageUtil.apply(capReachedMessage, Map.of(
                                "cap", String.valueOf(dailyCap),
                                "earned", String.valueOf(earnedToday)
                        )));
                    }
                }
            }
            progress = 0;
        }

        if (dailyCap > 0 && earnedToday >= dailyCap) {
            MessageUtil.actionBar(player, MessageUtil.apply(
                    actionbarCappedMessage,
                    Map.of(
                            "earned", String.valueOf(earnedToday),
                            "cap", String.valueOf(dailyCap)
                    )
            ));
        } else {
            MessageUtil.actionBar(player, MessageUtil.apply(
                    actionbarMessage,
                    Map.of(
                            "amount", String.valueOf(reward),
                            "progress", String.valueOf(progress),
                            "interval", String.valueOf(rewardInterval),
                            "earned", String.valueOf(earnedToday),
                            "cap", String.valueOf(dailyCap)
                    )
            ));
        }
    }

    private AfkDailyData getOrLoadDailyData(UUID uuid) {
        String today = LocalDate.now().toString();
        return dailyData.compute(uuid, (id, existing) -> {
            if (existing != null && today.equals(existing.dayId)) {
                return existing;
            }
            DatabaseManager.AfkDailyRow row = plugin.getDatabaseManager().loadAfkDaily(uuid);
            if (row != null && today.equals(row.dayId())) {
                return new AfkDailyData(today, row.coinsEarned(), row.coinsEarned() >= dailyCap && dailyCap > 0);
            }
            return new AfkDailyData(today, 0, false);
        });
    }

    public int getEarnedToday(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        return getOrLoadDailyData(uuid).coinsEarned;
    }

    public int getDailyCap() {
        return dailyCap;
    }

    public void resetDailyEarned(UUID uuid) {
        if (uuid == null) {
            return;
        }
        String today = LocalDate.now().toString();
        dailyData.put(uuid, new AfkDailyData(today, 0, false));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().saveAfkDaily(uuid, new DatabaseManager.AfkDailyRow(today, 0));
        });
    }

    public void addEarnedToday(UUID uuid, int amount) {
        if (uuid == null || amount <= 0) {
            return;
        }
        String today = LocalDate.now().toString();
        AfkDailyData data = getOrLoadDailyData(uuid);
        if (!today.equals(data.dayId)) {
            data.dayId = today;
            data.coinsEarned = 0;
            data.notified = false;
        }
        data.coinsEarned += amount;
        int total = data.coinsEarned;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().saveAfkDaily(uuid, new DatabaseManager.AfkDailyRow(today, total));
        });
    }

    private void leaveZone(Player player) {
        UUID uuid = player.getUniqueId();
        if (inZone.remove(uuid) != null) {
            afkTime.remove(uuid);
            MessageUtil.send(player, leaveMessage);
        }
    }

    public void clear(Player player) {
        UUID uuid = player.getUniqueId();
        inZone.remove(uuid);
        afkTime.remove(uuid);
    }

    public boolean isInZone(Player player) {
        return player != null && Boolean.TRUE.equals(inZone.get(player.getUniqueId()));
    }

    public int getRewardAmount(Player player) {
        for (Map.Entry<String, Integer> entry : rewardLadder) {
            if (player.hasPermission(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultReward;
    }
}
