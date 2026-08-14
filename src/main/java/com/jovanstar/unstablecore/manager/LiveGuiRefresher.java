package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.ArenaGui;
import com.jovanstar.unstablecore.gui.DuelMapGui;
import com.jovanstar.unstablecore.gui.SwordGui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

public final class LiveGuiRefresher {

    private final UnstableCore plugin;
    private BukkitTask task;

    public LiveGuiRefresher(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long ticks = Math.max(20L, plugin.getConfig().getLong("guis.live-refresh-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        boolean any = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ArenaGui || holder instanceof SwordGui || holder instanceof DuelMapGui) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        var arenas = plugin.getArenaManager();
        var active = arenas.getActiveArena();
        var sword = arenas.getSwordArena();
        String activeId = active != null ? active.getId() : null;
        String swordId = sword != null ? sword.getId() : null;
        String activeMap = active != null ? active.getDisplayName() : null;
        String swordMap = sword != null ? sword.getDisplayName() : null;
        int activePlayers = activeId != null ? arenas.countTrackedPlayersInArena(activeId) : 0;
        int swordPlayers = swordId != null ? arenas.countTrackedPlayersInArena(swordId) : 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ArenaGui gui) {
                gui.refreshLive(activeMap, activePlayers, active != null && active.hasCenter());
            } else if (holder instanceof SwordGui gui) {
                gui.refreshLive(swordMap, swordPlayers, sword != null && sword.hasCenter());
            } else if (holder instanceof DuelMapGui gui) {
                gui.refreshLive();
            }
        }
    }
}
