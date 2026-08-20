package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.ArenaGui;
import com.jovanstar.unstablecore.gui.BountyBoardGui;
import com.jovanstar.unstablecore.gui.DisposalGui;
import com.jovanstar.unstablecore.gui.DuelHistoryGui;
import com.jovanstar.unstablecore.gui.DuelMapGui;
import com.jovanstar.unstablecore.gui.DuelQueueGui;
import com.jovanstar.unstablecore.gui.KitAdminEditGui;
import com.jovanstar.unstablecore.gui.KitConfirmGui;
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
                || topHolder instanceof KitConfirmGui
                || topHolder instanceof KitPreviewGui
                || topHolder instanceof KitEditGui
                || topHolder instanceof KitAdminEditGui
                || topHolder instanceof DuelMapGui
                || topHolder instanceof DuelHistoryGui
                || topHolder instanceof DuelQueueGui)) {
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
                || topHolder instanceof KitConfirmGui
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
                || topHolder instanceof PlaceBountyGui
                || topHolder instanceof DuelMapGui
                || topHolder instanceof DuelHistoryGui
                || topHolder instanceof DuelQueueGui) {
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

        // Almost every handler below reacts by opening another inventory (next page, next step,
        // reopen-after-purchase). Doing that from inside InventoryClickEvent - while the server is
        // still mid-way through processing the click on the container it is about to replace -
        // leaves the client's view and the server's container desynced, which shows up as ghost
        // items and, on the item-bearing screens, as items that appear to survive the swap.
        // Running the handler on the next tick lets the click finish first; the event is already
        // cancelled above, and the per-player click cooldown still throttles double-clicks, so
        // nothing here becomes double-triggerable by deferring it.
        final int slot = event.getSlot();
        final org.bukkit.event.inventory.ClickType click = event.getClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != topHolder) {
                return;
            }
            if (topHolder instanceof ArenaGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof VoteGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof SwordGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof ShopGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof SettingsGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof RewardsGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof TagsGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof BountyBoardGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof PlaceBountyGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof LeaderboardMenuGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof LeaderboardCategoryGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof DuelMapGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof DuelHistoryGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof DuelQueueGui gui) {
                gui.handleClick(player, slot);
            } else if (topHolder instanceof KitsGui gui) {
                gui.handleClick(player, slot, click);
                syncCursor(player);
            } else if (topHolder instanceof KitConfirmGui gui) {
                gui.handleClick(player, slot);
                syncCursor(player);
            } else if (topHolder instanceof KitPreviewGui gui) {
                gui.handleClick(player, slot, click);
                syncCursor(player);
            }
        });
    }

    /**
     * Whether it is safe to pop the kits menu back open after the player closes the disposal bin.
     * The bin can be force-closed by death, and the player may have been pulled into a fight or an
     * arena while it was open, so the reopen must not fire while dead, in an arena, combat-tagged,
     * in a duel or grace, or awaiting a post-duel restore - only when they are idle at spawn.
     */
    private boolean canReopenKits(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        if (plugin.getArenaManager() != null
                && plugin.getArenaManager().getPlayerArena(player.getUniqueId()) != null) {
            return false;
        }
        if (plugin.getDuelManager() != null
                && (plugin.getDuelManager().isInDuel(player.getUniqueId())
                || plugin.getDuelManager().isInGrace(player.getUniqueId())
                || plugin.getDuelManager().hasPendingPostDuelRestore(player.getUniqueId()))) {
            return false;
        }
        if (plugin.getCombatListener() != null
                && plugin.getCombatListener().isCombatTagged(player.getUniqueId())) {
            return false;
        }
        return true;
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
                || holder instanceof KitsGui || holder instanceof KitConfirmGui
                || holder instanceof KitPreviewGui
                || holder instanceof DuelMapGui || holder instanceof DuelHistoryGui
                || holder instanceof DuelQueueGui) {
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
            // Came here from the kit confirm screen: hand them back to the kits menu so they
            // can claim the kit they were binning space for. Next tick, because reopening an
            // inventory from inside a close event desyncs the client view.
            if (event.getPlayer() instanceof Player closer
                    && DisposalGui.consumeReturnToKits(closer.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (canReopenKits(closer)) {
                        KitsGui.open(plugin, closer);
                    }
                });
            }
        }
        if (event.getPlayer() instanceof Player player) {
            if (holder instanceof KitEditGui editGui) {
                editGui.onClose(player);
            }
            if (holder instanceof KitAdminEditGui adminEditGui) {
                adminEditGui.onClose(player);
            }
            if (holder instanceof KitsGui
                    || holder instanceof KitConfirmGui
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
                    || holder instanceof LeaderboardCategoryGui
                    || holder instanceof DuelMapGui
                    || holder instanceof DuelHistoryGui
                    || holder instanceof DuelQueueGui) {
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
        DisposalGui.clearPlayer(event.getPlayer().getUniqueId());
    }
}
