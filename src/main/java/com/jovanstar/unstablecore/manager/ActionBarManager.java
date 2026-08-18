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
    /**
     * A 1500ms TTL against a 1000ms tick meant roughly every other tick fell through to a live
     * Vault lookup for every online player - and Vault's provider is frequently SQL-backed, so
     * that is a per-player database round trip on the main thread, several hundred times a second
     * at a few hundred players. Any deposit/withdrawal already calls invalidate(), so a longer
     * TTL never shows a stale figure after the player's own balance changes; it only delays
     * picking up out-of-band edits made by other plugins.
     */
    private static final long BALANCE_TTL_MS = 15_000L;

    /** uuid → timestamp (ms) until which the normal action-bar is suppressed. */
    private final Map<UUID, Long> suppressUntil = new ConcurrentHashMap<>();

    private record CachedBalance(double balance, long atMs) {
    }

    public ActionBarManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Suppress the normal action-bar for {@code durationMs} milliseconds.
     * Used by DuelManager to hold off the normal bar while the duel-start message is shown.
     */
    public void suppress(UUID uuid, long durationMs) {
        if (uuid == null || durationMs <= 0) return;
        suppressUntil.put(uuid, System.currentTimeMillis() + durationMs);
    }

    /** Immediately lift any active suppression for this player (called on duel end). */
    public void unsuppress(UUID uuid) {
        if (uuid != null) suppressUntil.remove(uuid);
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
        suppressUntil.clear();
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
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            if (plugin.getAfkZoneManager() != null && plugin.getAfkZoneManager().isInZone(player)) {
                continue;
            }
            // Suppress normal action-bar temporarily (e.g. while duel-start message is shown)
            Long until = suppressUntil.get(player.getUniqueId());
            if (until != null) {
                if (now < until) {
                    continue; // still suppressed — duel-start bar is being shown by DuelManager
                } else {
                    suppressUntil.remove(player.getUniqueId()); // expired, resume normal
                }
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
