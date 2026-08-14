package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DatabaseManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paginated `/duel history` view. Deliberately avoids player-head icons for opponents (some of
 * whom may be offline/long gone) to not add any Mojang skin lookups - see DUELS.md's explicit
 * concern about avoiding repeated Mojang API calls from GUI rendering.
 */
public final class DuelHistoryGui implements InventoryHolder {

    private static final int PAGE_SIZE = 28;
    private static final int[] ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final UnstableCore plugin;
    private final UUID owner;
    private final int page;
    private final int totalCount;
    private final Inventory inventory;

    private DuelHistoryGui(UnstableCore plugin, UUID owner, int page,
                           List<DatabaseManager.DuelHistoryRow> rows, int totalCount) {
        this.plugin = plugin;
        this.owner = owner;
        this.page = page;
        this.totalCount = totalCount;
        Component title = MessageUtil.parse("&8» &dDuel History &8«");
        this.inventory = Bukkit.createInventory(this, 54, title);
        fill(rows);
    }

    private void fill(List<DatabaseManager.DuelHistoryRow> rows) {
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int pages = Math.max(1, (int) Math.ceil(totalCount / (double) PAGE_SIZE));
        inventory.setItem(4, new ItemBuilder(Material.BOOK)
                .name(MessageUtil.apply("&d&lDuel History &7(page {page}/{pages})",
                        Map.of("page", String.valueOf(page + 1), "pages", String.valueOf(pages))))
                .lore(List.of("&7Total duels: &f" + totalCount))
                .hideAttributes().build());

        if (rows.isEmpty()) {
            inventory.setItem(22, new ItemBuilder(Material.BARRIER).name("&7No duel history yet.").hideAttributes().build());
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            for (int i = 0; i < rows.size() && i < ENTRY_SLOTS.length; i++) {
                DatabaseManager.DuelHistoryRow row = rows.get(i);
                boolean challengerIsOwner = owner.equals(row.challenger());
                String opponentName = challengerIsOwner ? row.targetName() : row.challengerName();
                boolean won = row.winner() != null && row.winner().equals(owner);
                boolean voided = row.winner() == null;

                Material mat = voided ? Material.GRAY_DYE : (won ? Material.LIME_DYE : Material.RED_DYE);
                String resultText = voided ? "&7Void (no contest)" : (won ? "&aWin" : "&cLoss");
                List<String> lore = new ArrayList<>(List.of(
                        "&7Result: " + resultText,
                        "&7Wager: &f" + EconomyManager.format(row.wager()) + " coins",
                        "&7Map: &f" + row.arenaId(),
                        "&7Date: &f" + fmt.format(new Date(row.endedAt()))
                ));
                if (!voided && won) {
                    lore.add("&7Payout: &6" + EconomyManager.format(row.payout()) + " coins");
                }
                inventory.setItem(ENTRY_SLOTS[i], new ItemBuilder(mat)
                        .name("&f" + opponentName)
                        .lore(lore)
                        .hideAttributes().build());
            }
        }

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW).name("&cPrevious Page").hideAttributes().build());
        }
        if ((page + 1) * PAGE_SIZE < totalCount) {
            inventory.setItem(50, new ItemBuilder(Material.ARROW).name("&aNext Page").hideAttributes().build());
        }
        inventory.setItem(49, new ItemBuilder(Material.BARRIER).name("&cClose").hideAttributes().build());
    }

    public void handleClick(Player player, int slot) {
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 48 && page > 0) {
            open(plugin, player, owner, page - 1);
            return;
        }
        if (slot == 50 && (page + 1) * PAGE_SIZE < totalCount) {
            open(plugin, player, owner, page + 1);
        }
    }

    private static final class RowsHolder {
        List<DatabaseManager.DuelHistoryRow> rows;
    }

    public static void open(UnstableCore plugin, Player viewer, UUID owner, int page) {
        // loadHistoryPageAsync invokes both callbacks back-to-back on the main thread once the
        // single async round trip completes, so this holder is only ever touched from that thread.
        RowsHolder holder = new RowsHolder();
        plugin.getDuelManager().getDuelStatsManager().loadHistoryPageAsync(owner, page, PAGE_SIZE,
                rows -> holder.rows = rows,
                count -> {
                    if (!viewer.isOnline() || holder.rows == null) {
                        return;
                    }
                    viewer.openInventory(new DuelHistoryGui(plugin, owner, page, holder.rows, count).getInventory());
                });
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
