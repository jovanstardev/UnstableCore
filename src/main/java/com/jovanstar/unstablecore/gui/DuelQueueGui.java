package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelQueueManager;
import com.jovanstar.unstablecore.manager.DuelQueueManager.QueueType;
import com.jovanstar.unstablecore.manager.DuelStatsManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Modern visual GUI for joining/leaving Casual and Ranked duel queues.
 * Clean layout without filler glass panes.
 */
public final class DuelQueueGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final Inventory inventory;

    private final int casualSlot = 11;
    private final int rankedSlot = 15;
    private final int leaveSlot = 22;
    private final int statsSlot = 4;

    private DuelQueueGui(UnstableCore plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Component title = MessageUtil.parse("&8» &dDuel Matchmaking &8«");
        this.inventory = Bukkit.createInventory(this, 27, title);
        fill();
    }

    public void refreshLive() {
        fill();
    }

    private void fill() {
        inventory.clear();
        DuelQueueManager queueMgr = plugin.getDuelQueueManager();
        if (queueMgr == null) return;

        DuelStatsManager statsMgr = plugin.getDuelStatsManager();
        boolean inQueue = queueMgr.isInQueue(viewer.getUniqueId());
        QueueType currentType = queueMgr.getQueueType(viewer.getUniqueId());

        if (statsMgr != null) {
            UUID uuid = viewer.getUniqueId();
            inventory.setItem(statsSlot, new ItemBuilder(Material.COMPASS)
                    .name("&d&lYour Duel Stats")
                    .lore(List.of(
                            "&7Wins: &a" + statsMgr.getWins(uuid) + "  &7Losses: &c" + statsMgr.getLosses(uuid),
                            "&7Streak: &e" + statsMgr.getCurrentStreak(uuid) + " &8(Best: &6" + statsMgr.getBestStreak(uuid) + "&8)",
                            "&7ELO: &b" + statsMgr.getElo(uuid) + " &7(" + statsMgr.getRankTier(statsMgr.getElo(uuid)) + "&7)"
                    ))
                    .hideAttributes().build());
        }

        // 1. Casual Queue Button (Slot 11)
        int casualCount = queueMgr.getQueueCount(QueueType.CASUAL);
        boolean isCasual = inQueue && currentType == QueueType.CASUAL;
        List<String> casualLore = new ArrayList<>();
        casualLore.add("&7Jump into a quick, unranked match.");
        casualLore.add("&7No rating at stake.");
        casualLore.add("");
        casualLore.add("&fIn queue: &e" + casualCount + " players");
        casualLore.add("");
        if (isCasual) {
            casualLore.add("&a✔ Currently waiting in this queue");
            casualLore.add("&e> Click to leave");
        } else {
            casualLore.add("&e> Click to join Casual Queue");
        }
        inventory.setItem(casualSlot, new ItemBuilder(Material.IRON_SWORD)
                .name(isCasual ? "&a&l● Casual Queue (Active)" : "&f&lCasual Queue")
                .lore(casualLore)
                .hideAttributes().build());

        // 2. Ranked Queue Button (Slot 15)
        int rankedCount = queueMgr.getQueueCount(QueueType.RANKED);
        boolean isRanked = inQueue && currentType == QueueType.RANKED;
        int elo = statsMgr != null ? statsMgr.getElo(viewer.getUniqueId()) : 1000;
        String tier = statsMgr != null ? statsMgr.getRankTier(elo) : "&7&lBronze";

        List<String> rankedLore = new ArrayList<>();
        rankedLore.add("&7Competitive 1v1 match with ELO rating.");
        rankedLore.add("&7Matched with players near your skill.");
        rankedLore.add("");
        rankedLore.add("&fYour ELO: &b" + elo + " &7(" + tier + "&7)");
        rankedLore.add("&fIn queue: &e" + rankedCount + " players");
        rankedLore.add("");
        if (isRanked) {
            rankedLore.add("&b✔ Currently waiting in this queue");
            rankedLore.add("&e> Click to leave");
        } else {
            rankedLore.add("&e> Click to join Ranked Queue");
        }
        inventory.setItem(rankedSlot, new ItemBuilder(Material.DIAMOND_SWORD)
                .name(isRanked ? "&b&l● Ranked Queue (Active)" : "&b&lRanked Queue")
                .lore(rankedLore)
                .hideAttributes().build());

        // 3. Bottom Button: Leave Queue (if in queue) or Close
        if (inQueue) {
            inventory.setItem(leaveSlot, new ItemBuilder(Material.RED_BED)
                    .name("&c&lLeave Current Queue")
                    .lore("&7Click to exit matchmaking")
                    .hideAttributes().build());
        } else {
            inventory.setItem(leaveSlot, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                    .name("&c&lClose")
                    .lore("&7Click to close")
                    .hideAttributes().build());
        }
    }

    public void handleClick(Player player, int slot) {
        DuelQueueManager queueMgr = plugin.getDuelQueueManager();
        if (queueMgr == null) return;

        if (slot == casualSlot) {
            if (queueMgr.isInQueue(player.getUniqueId()) && queueMgr.getQueueType(player.getUniqueId()) == QueueType.CASUAL) {
                queueMgr.leaveQueue(player);
            } else {
                queueMgr.joinQueue(player, QueueType.CASUAL);
            }
            fill();
            return;
        }

        if (slot == rankedSlot) {
            if (queueMgr.isInQueue(player.getUniqueId()) && queueMgr.getQueueType(player.getUniqueId()) == QueueType.RANKED) {
                queueMgr.leaveQueue(player);
            } else {
                queueMgr.joinQueue(player, QueueType.RANKED);
            }
            fill();
            return;
        }

        if (slot == leaveSlot) {
            if (queueMgr.isInQueue(player.getUniqueId())) {
                queueMgr.leaveQueue(player);
                fill();
            } else {
                player.closeInventory();
            }
        }
    }

    public static void open(UnstableCore plugin, Player player) {
        player.openInventory(new DuelQueueGui(plugin, player).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
