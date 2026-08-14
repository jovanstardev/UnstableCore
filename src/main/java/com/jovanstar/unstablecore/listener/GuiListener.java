package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.ArenaGui;
import com.jovanstar.unstablecore.gui.BountyBoardGui;
import com.jovanstar.unstablecore.gui.DisposalGui;
import com.jovanstar.unstablecore.gui.KitAdminEditGui;
import com.jovanstar.unstablecore.gui.KitEditGui;
import com.jovanstar.unstablecore.gui.KitPreviewGui;
import com.jovanstar.unstablecore.gui.KitsGui;
import com.jovanstar.unstablecore.gui.LeaderboardCategoryGui;
import com.jovanstar.unstablecore.gui.LeaderboardMenuGui;
import com.jovanstar.unstablecore.gui.PlaceBountyGui;
import com.jovanstar.unstablecore.gui.RewardsGui;
import com.jovanstar.unstablecore.gui.SettingsGui;
import com.jovanstar.unstablecore.gui.ShopGui;
import com.jovanstar.unstablecore.gui.StatsGui;
import com.jovanstar.unstablecore.gui.SwordGui;
import com.jovanstar.unstablecore.gui.TagsGui;
import com.jovanstar.unstablecore.gui.VoteGui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiListener implements Listener {

    private final UnstableCore plugin;
    private final Map<UUID, Long> lastClickMs = new ConcurrentHashMap<>();

    public GuiListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    private boolean onCooldown(Player player) {
        long cooldown = Math.max(0L, plugin.getConfig().getLong("guis.click-cooldown-ms", 250L));
        if (cooldown <= 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastClickMs.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            return true;
        }
        lastClickMs.put(player.getUniqueId(), now);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (!(topHolder instanceof ArenaGui
                || topHolder instanceof VoteGui
                || topHolder instanceof SwordGui
                || topHolder instanceof ShopGui
                || topHolder instanceof StatsGui
                || topHolder instanceof SettingsGui
                || topHolder instanceof RewardsGui
                || topHolder instanceof TagsGui
                || topHolder instanceof BountyBoardGui
                || topHolder instanceof PlaceBountyGui
                || topHolder instanceof LeaderboardMenuGui
                || topHolder instanceof LeaderboardCategoryGui
                || topHolder instanceof DisposalGui
                || topHolder instanceof KitsGui
                || topHolder instanceof KitPreviewGui
                || topHolder instanceof KitEditGui
                || topHolder instanceof KitAdminEditGui)) {
            return;
        }

        if (topHolder instanceof DisposalGui) {
            return;
        }

        if (topHolder instanceof KitEditGui gui) {
            gui.handleClick(player, event);
            return;
        }
        if (topHolder instanceof KitAdminEditGui gui) {
            gui.handleClick(player, event);
            return;
        }

        event.setCancelled(true);
        if (topHolder instanceof KitsGui
                || topHolder instanceof KitPreviewGui
                || topHolder instanceof LeaderboardMenuGui
                || topHolder instanceof LeaderboardCategoryGui
                || topHolder instanceof ShopGui
                || topHolder instanceof ArenaGui
                || topHolder instanceof SwordGui
                || topHolder instanceof VoteGui
                || topHolder instanceof StatsGui
                || topHolder instanceof SettingsGui
                || topHolder instanceof RewardsGui
                || topHolder instanceof TagsGui
                || topHolder instanceof BountyBoardGui
                || topHolder instanceof PlaceBountyGui) {
            syncCursor(player);
        }

        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (onCooldown(player)) {
            syncCursor(player);
            return;
        }

        if (topHolder instanceof ArenaGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof VoteGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof SwordGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof ShopGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof SettingsGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof RewardsGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof TagsGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof BountyBoardGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof PlaceBountyGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof LeaderboardMenuGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof LeaderboardCategoryGui gui) {
            gui.handleClick(player, event.getSlot());
        } else if (topHolder instanceof KitsGui gui) {
            gui.handleClick(player, event.getSlot(), event.getClick());
            syncCursor(player);
        } else if (topHolder instanceof KitPreviewGui gui) {
            gui.handleClick(player, event.getSlot(), event.getClick());
            syncCursor(player);
        }
    }

    private static void syncCursor(Player player) {
        if (player.getItemOnCursor() != null && !player.getItemOnCursor().getType().isAir()) {
            player.setItemOnCursor(null);
        }
        player.updateInventory();
    }

    private void syncCursorNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                syncCursor(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof DisposalGui) {
            return;
        }
        if (holder instanceof KitEditGui) {
            int topSize = event.getView().getTopInventory().getSize();
            for (int slot : event.getRawSlots()) {
                if (slot >= topSize || slot < 0 || slot > 44) {
                    event.setCancelled(true);
                    return;
                }
            }
            event.setCancelled(false);
            return;
        }
        if (holder instanceof KitAdminEditGui) {
            int topSize = event.getView().getTopInventory().getSize();
            for (int slot : event.getRawSlots()) {
                if (slot >= topSize || slot < 0 || slot == 52 || slot == 53) {
                    event.setCancelled(true);
                    return;
                }
            }
            event.setCancelled(false);
            return;
        }
        if (holder instanceof ArenaGui || holder instanceof VoteGui || holder instanceof SwordGui
                || holder instanceof ShopGui || holder instanceof StatsGui || holder instanceof SettingsGui
                || holder instanceof RewardsGui || holder instanceof TagsGui
                || holder instanceof BountyBoardGui || holder instanceof PlaceBountyGui
                || holder instanceof LeaderboardMenuGui || holder instanceof LeaderboardCategoryGui
                || holder instanceof KitsGui || holder instanceof KitPreviewGui) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                syncCursor(player);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof DisposalGui disposal) {
            disposal.disposeContents();
        }
        if (event.getPlayer() instanceof Player player) {
            if (holder instanceof VoteGui
                    && plugin.getMapVoteManager() != null
                    && plugin.getMapVoteManager().isVoting()
                    && !plugin.getMapVoteManager().hasVoted(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getMapVoteManager().keepOpenUntilVoted(player));
            }
            if (holder instanceof KitEditGui editGui) {
                editGui.onClose(player);
            }
            if (holder instanceof KitsGui
                    || holder instanceof KitPreviewGui
                    || holder instanceof ShopGui
                    || holder instanceof ArenaGui
                    || holder instanceof SwordGui
                    || holder instanceof VoteGui
                    || holder instanceof StatsGui
                    || holder instanceof SettingsGui
                    || holder instanceof RewardsGui
                    || holder instanceof TagsGui
                    || holder instanceof BountyBoardGui
                    || holder instanceof PlaceBountyGui
                    || holder instanceof LeaderboardMenuGui
                    || holder instanceof LeaderboardCategoryGui) {
                if (plugin.getLeaderboardManager() != null
                        && (holder instanceof LeaderboardMenuGui || holder instanceof LeaderboardCategoryGui)) {
                    plugin.getLeaderboardManager().invalidatePendingOpens(player.getUniqueId());
                }
                syncCursor(player);
                syncCursorNextTick(player);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClickMs.remove(event.getPlayer().getUniqueId());
    }
}
