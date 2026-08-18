package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DatabaseManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.model.Arena;
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
 * concern about avoiding repeated Mojang API calls from GUI rendering. Same border/content-grid
 * visual language as BountyBoardGui, for consistency across the plugin.
 */
public final class DuelHistoryGui implements InventoryHolder {

    private static final int PAGE_SIZE = 28;
    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final UnstableCore plugin;
    private final UUID owner;
    private final String ownerName;
    private final int page;
    private final int totalCount;
    private final Inventory inventory;

    private DuelHistoryGui(UnstableCore plugin, UUID owner, String ownerName, int page,
                           List<DatabaseManager.DuelHistoryRow> rows, int totalCount) {
        this.plugin = plugin;
        this.owner = owner;
        this.ownerName = ownerName;
        this.page = page;
        this.totalCount = totalCount;
        Component title = MessageUtil.parse("&8» &d&lDuel History &8«");
        this.inventory = Bukkit.createInventory(this, 54, title);
        fill(rows);
    }

    private void fill(List<DatabaseManager.DuelHistoryRow> rows) {
        ItemStack border = new ItemBuilder(Material.MAGENTA_STAINED_GLASS_PANE).name(" ").hideAttributes().build();
        ItemStack empty = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, border);
        }
        for (int slot : CONTENT) {
            inventory.setItem(slot, empty);
        }

        int pages = Math.max(1, (int) Math.ceil(totalCount / (double) PAGE_SIZE));
        int wins = 0;
        for (DatabaseManager.DuelHistoryRow row : rows) {
            if (owner.equals(row.winner())) {
                wins++;
            }
        }
        inventory.setItem(4, new ItemBuilder(Material.BOOK)
                .name(MessageUtil.apply("&d&l{player}'s Duel History", Map.of("player", ownerName)))
                .lore(List.of(
                        "&8&m                                ",
                        "&7Page &f" + (page + 1) + "&7/&f" + pages,
                        "&7Total duels: &f" + totalCount
                ))
                .hideAttributes().build());

        if (rows.isEmpty()) {
            inventory.setItem(CONTENT[CONTENT.length / 2], new ItemBuilder(Material.BARRIER)
                    .name("&7No duel history yet")
                    .lore(List.of("&8Go fight someone with &f/duel <player>&8!"))
                    .hideAttributes().build());
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
            for (int i = 0; i < rows.size() && i < CONTENT.length; i++) {
                inventory.setItem(CONTENT[i], buildEntry(rows.get(i), fmt));
            }
        }

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW)
                    .name("&c&l« Previous Page").hideAttributes().build());
        }
        if ((page + 1) * PAGE_SIZE < totalCount) {
            inventory.setItem(50, new ItemBuilder(Material.ARROW)
                    .name("&a&lNext Page »").hideAttributes().build());
        }
        inventory.setItem(49, new ItemBuilder(Material.BARRIER).name("&c&lClose").hideAttributes().build());
    }

    private ItemStack buildEntry(DatabaseManager.DuelHistoryRow row, SimpleDateFormat fmt) {
        boolean challengerIsOwner = owner.equals(row.challenger());
        String opponentName = challengerIsOwner ? row.targetName() : row.challengerName();
        boolean voided = row.winner() == null;
        boolean won = !voided && row.winner().equals(owner);

        Material mat = voided ? Material.GRAY_DYE : (won ? Material.LIME_DYE : Material.RED_DYE);
        String resultLine = voided ? "&8✦ &7Void &8(no contest)" : (won ? "&a✔ &a&lWIN" : "&c✘ &c&lLOSS");
        String mapName = resolveMapName(row.arenaId());

        List<String> lore = new ArrayList<>(List.of(
                resultLine,
                "&8&m                                ",
                "&7Kit: &f" + (row.kitId() == null || row.kitId().isBlank() ? "-" : row.kitId()),
                "&7Map: &f" + mapName,
                "&7Wager: &f" + EconomyManager.format(row.wager()) + " &7coins"
        ));
        if (!voided && won) {
            lore.add("&7Payout: &6" + EconomyManager.format(row.payout()) + " &7coins");
        }
        lore.add("&7Date: &f" + fmt.format(new Date(row.endedAt())));

        return new ItemBuilder(mat)
                .name((won ? "&a&l" : voided ? "&7&l" : "&c&l") + "vs " + opponentName)
                .lore(lore)
                .hideAttributes().build();
    }

    private String resolveMapName(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return "-";
        }
        Arena arena = plugin.getDuelManager().getDuelArenaManager().resolve(arenaId);
        return arena != null ? MessageUtil.strip(arena.getDisplayName()) : arenaId;
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
        // Non-blocking: getOfflinePlayer(uuid).getName() falls through to a Mojang request for a
        // UUID that isn't in the local usercache, and this runs on the main thread from a command.
        String ownerName = owner.equals(viewer.getUniqueId())
                ? viewer.getName()
                : (plugin.getLeaderboardManager() != null
                        ? plugin.getLeaderboardManager().cachedName(owner) : null);
        String safeName = (ownerName == null || ownerName.isBlank()) ? "Unknown" : ownerName;
        // loadHistoryPageAsync invokes both callbacks back-to-back on the main thread once the
        // single async round trip completes, so this holder is only ever touched from that thread.
        RowsHolder holder = new RowsHolder();
        plugin.getDuelManager().getDuelStatsManager().loadHistoryPageAsync(owner, page, PAGE_SIZE,
                rows -> holder.rows = rows,
                count -> {
                    if (!viewer.isOnline() || holder.rows == null) {
                        return;
                    }
                    viewer.openInventory(new DuelHistoryGui(plugin, owner, safeName, page, holder.rows, count).getInventory());
                });
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
