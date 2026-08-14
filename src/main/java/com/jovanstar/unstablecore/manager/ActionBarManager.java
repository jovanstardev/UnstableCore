package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActionBarManager {

    private final UnstableCore plugin;
    private BukkitTask task;
    private final Map<UUID, CachedBalance> balanceCache = new ConcurrentHashMap<>();
    private static final long BALANCE_TTL_MS = 1500L;

    private record CachedBalance(double balance, long atMs) {
    }

    public ActionBarManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("action-bar.enabled", true)) {
            return;
        }
        long interval = Math.max(1L, plugin.getConfig().getLong("action-bar.interval-ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        balanceCache.clear();
    }

    public void reload() {
        start();
    }

    public void invalidate(UUID uuid) {
        if (uuid != null) {
            balanceCache.remove(uuid);
        }
    }

    private double cachedBalance(Player player) {
        long now = System.currentTimeMillis();
        CachedBalance cached = balanceCache.get(player.getUniqueId());
        if (cached != null && now - cached.atMs() < BALANCE_TTL_MS) {
            return cached.balance();
        }
        double bal = plugin.getEconomyManager().getBalance(player);
        balanceCache.put(player.getUniqueId(), new CachedBalance(bal, now));
        return bal;
    }

    private void tick() {
        String format = plugin.getConfig().getString(
                "action-bar.format",
                "&c⚔ {killstreak} &8| &6☠ {deaths} &8| &e⛃ {coins}"
        );
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            if (plugin.getAfkZoneManager() != null && plugin.getAfkZoneManager().isInZone(player)) {
                continue;
            }
            String message = MessageUtil.apply(format, Map.of(
                    "killstreak", String.valueOf(plugin.getKillstreakManager().getStreak(player.getUniqueId())),
                    "deaths", String.valueOf(plugin.getKillstreakManager().getDeaths(player.getUniqueId())),
                    "coins", EconomyManager.formatCommas(cachedBalance(player))
            ));
            MessageUtil.actionBar(player, message);
        }
    }
}
