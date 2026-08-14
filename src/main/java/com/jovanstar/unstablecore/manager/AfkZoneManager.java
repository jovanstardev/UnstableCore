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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkZoneManager {

    private final UnstableCore plugin;
    private final Map<UUID, Integer> afkTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inZone = new ConcurrentHashMap<>();

    private BukkitTask task;

    private boolean enabled;
    private String region;
    private String worldFilter;
    private int tickSeconds;
    private int rewardInterval;
    private int defaultReward;
    private boolean requireWater;
    private List<Map.Entry<String, Integer>> rewardLadder = List.of();

    private List<String> enterMessages = List.of();
    private String leaveMessage = "";
    private String rewardMessage = "";
    private String actionbarMessage = "";

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
            boolean inside = WorldGuardHook.isInRegion(player, region);
            if (inside && requireWater && !player.isInWater()) {
                inside = false;
            }

            if (inside) {
                handleInZone(player);
            } else {
                leaveZone(player);
            }
        }
    }

    private void handleInZone(Player player) {
        UUID uuid = player.getUniqueId();
        int reward = getRewardAmount(player);

        if (!Boolean.TRUE.equals(inZone.get(uuid))) {
            inZone.put(uuid, true);
            afkTime.putIfAbsent(uuid, 0);
            int minutes = Math.max(1, rewardInterval / 60);
            Map<String, String> ph = Map.of(
                    "amount", String.valueOf(reward),
                    "minutes", String.valueOf(minutes)
            );
            for (String line : enterMessages) {
                MessageUtil.send(player, MessageUtil.apply(line, ph));
            }
        }

        int progress = afkTime.merge(uuid, tickSeconds, Integer::sum);

        if (progress >= rewardInterval) {
            afkTime.put(uuid, 0);
            plugin.getEconomyManager().deposit(player, reward);
            MessageUtil.send(player, MessageUtil.apply(rewardMessage, Map.of("amount", String.valueOf(reward))));
            progress = 0;
        }

        MessageUtil.actionBar(player, MessageUtil.apply(
                actionbarMessage,
                Map.of(
                        "amount", String.valueOf(reward),
                        "progress", String.valueOf(progress),
                        "interval", String.valueOf(rewardInterval)
                )
        ));
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
