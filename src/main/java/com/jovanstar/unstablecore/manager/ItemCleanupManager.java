package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemCleanupManager implements Listener {

    private final UnstableCore plugin;
    private final Set<UUID> droppedItems = ConcurrentHashMap.newKeySet();
    private BukkitTask cycleTask;
    private final List<BukkitTask> pending = new ArrayList<>();

    public ItemCleanupManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) {
            return;
        }
        long interval = Math.max(60L, plugin.getConfig().getLong("cleanup.interval-seconds", 900L));
        cycleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::beginCountdown, interval * 20L, interval * 20L);
    }

    public void stop() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
        cancelPending();
    }

    private void cancelPending() {
        for (BukkitTask task : pending) {
            if (task != null) {
                task.cancel();
            }
        }
        pending.clear();
    }

    public void reload() {
        start();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        droppedItems.add(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntityType() == EntityType.ITEM) {
            droppedItems.remove(event.getEntity().getUniqueId());
        }
    }

    private void beginCountdown() {
        if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) {
            return;
        }
        cancelPending();
        List<Integer> warnings = plugin.getConfig().getIntegerList("cleanup.warnings");
        if (warnings.isEmpty()) {
            warnings = List.of(30, 15, 5, 3, 2, 1);
        }

        int max = warnings.stream().mapToInt(Integer::intValue).max().orElse(30);
        for (int seconds : warnings) {
            int delayTicks = Math.max(0, (max - seconds) * 20);
            final int secs = seconds;
            pending.add(Bukkit.getScheduler().runTaskLater(plugin, () -> warn(secs), delayTicks));
        }
        pending.add(Bukkit.getScheduler().runTaskLater(plugin, this::clearItems, max * 20L));
    }

    private void warn(int seconds) {
        String template = plugin.getConfig().getString(
                "cleanup.warning-message",
                "<b><gradient:#A100FF:#E9D5FF>UNSTABLE FFA</gradient></b> &8» &fDropped items will be cleared in <#A100FF>{seconds}s &f!"
        );
        MessageUtil.broadcastFiltered(
                MessageUtil.apply(template, Map.of("seconds", String.valueOf(seconds))),
                plugin.getSettingsManager().filter(SettingsManager.CLEANUP_ALERTS)
        );
    }

    private void clearItems() {
        int removed = 0;
        for (UUID id : List.copyOf(droppedItems)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Item item && item.isValid() && !item.isDead()) {
                item.remove();
                removed++;
            }
            droppedItems.remove(id);
        }
        String done = plugin.getConfig().getString(
                "cleanup.cleared-message",
                "<b><gradient:#A100FF:#E9D5FF>UNSTABLE FFA</gradient></b> &8» &fDropped items have been &acleared&f."
        );
        MessageUtil.broadcastFiltered(done, plugin.getSettingsManager().filter(SettingsManager.CLEANUP_ALERTS));
        plugin.getLogger().info("Item cleanup removed " + removed + " dropped item(s).");
        pending.clear();
    }
}
