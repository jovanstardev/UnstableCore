package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.BountyManager;
import com.jovanstar.unstablecore.manager.BountyManager.Bounty;
import com.jovanstar.unstablecore.manager.BountyManager.Prompt;
import com.jovanstar.unstablecore.manager.BountyManager.PromptType;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BountyBoardGui implements InventoryHolder {

    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final UnstableCore plugin;
    private final Player viewer;
    private final int page;
    private final String filter;
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();

    public BountyBoardGui(UnstableCore plugin, Player viewer, int page, String filter) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.filter = filter;
        FileConfiguration cfg = plugin.getConfigManager().getBounty();
        int size = Math.max(9, Math.min(54, cfg.getInt("gui.size", 54)));
        if (size % 9 != 0) {
            size = 54;
        }
        BountyManager mgr = plugin.getBountyManager();
        List<Bounty> list = mgr.sortedFiltered(filter);
        int perPage = Math.min(CONTENT.length, Math.max(1, cfg.getInt("gui.slots-per-page", CONTENT.length)));
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        this.page = Math.max(0, Math.min(page, pages - 1));
        this.inventory = Bukkit.createInventory(this, size,
                MessageUtil.parse(cfg.getString("gui.board-title", "&aBOUNTY BOARD")));
        fill(list, perPage, pages);
    }

    public static void open(UnstableCore plugin, Player player, int page, String filter) {
        player.openInventory(new BountyBoardGui(plugin, player, page, filter).getInventory());
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().getBounty();
    }

    private void fill(List<Bounty> list, int perPage, int pages) {
        actions.clear();
        FileConfiguration cfg = cfg();
        Material border = mat(cfg.getString("gui.border"), Material.YELLOW_STAINED_GLASS_PANE);
        Material empty = mat(cfg.getString("gui.empty"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack borderItem = pane(border);
        ItemStack emptyItem = pane(empty);

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, borderItem);
        }
        for (int slot : CONTENT) {
            inventory.setItem(slot, emptyItem);
        }

        int start = page * perPage;
        int end = Math.min(list.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int slot = CONTENT[i - start];
            inventory.setItem(slot, bountyHead(list.get(i)));
        }

        Map<String, String> ph = basePh(list.size(), pages);
        int infoSlot = cfg.getInt("board.info-slot", 4);
        inventory.setItem(infoSlot, named(Material.PAPER,
                cfg.getString("board.info-name", "&6Page {page}/{pages}"),
                applyList(cfg.getStringList("board.info-lore"), ph), ph));

        int placeSlot = cfg.getInt("board.place-slot", 46);
        inventory.setItem(placeSlot, named(Material.WRITABLE_BOOK,
                cfg.getString("board.place-name", "&bPlace Bounty"),
                applyList(cfg.getStringList("board.place-lore"), ph), ph));
        actions.put(placeSlot, "place");

        int searchSlot = cfg.getInt("board.search-slot", 48);
        inventory.setItem(searchSlot, named(Material.ENDER_EYE,
                cfg.getString("board.search-name", "&bSearch Player"),
                applyList(cfg.getStringList("board.search-lore"), ph), ph));
        actions.put(searchSlot, "search");

        int yoursSlot = cfg.getInt("board.yours-slot", 50);
        inventory.setItem(yoursSlot, yoursItem(ph));
        actions.put(yoursSlot, "refresh");

        int prevSlot = cfg.getInt("board.prev-slot", 45);
        int nextSlot = cfg.getInt("board.next-slot", 52);
        if (page > 0) {
            Map<String, String> prevPh = new HashMap<>(ph);
            prevPh.put("page", String.valueOf(page));
            inventory.setItem(prevSlot, named(Material.ARROW,
                    cfg.getString("board.prev-name", "&cPrevious Page"),
                    applyList(cfg.getStringList("board.prev-lore"), prevPh), prevPh));
            actions.put(prevSlot, "prev");
        }
        if (page + 1 < pages) {
            Map<String, String> nextPh = new HashMap<>(ph);
            nextPh.put("page", String.valueOf(page + 2));
            inventory.setItem(nextSlot, named(Material.ARROW,
                    cfg.getString("board.next-name", "&aNext Page"),
                    applyList(cfg.getStringList("board.next-lore"), nextPh), nextPh));
            actions.put(nextSlot, "next");
        }
    }

    private ItemStack yoursItem(Map<String, String> base) {
        FileConfiguration cfg = cfg();
        Bounty mine = plugin.getBountyManager().get(viewer.getUniqueId());
        Map<String, String> ph = new HashMap<>(base);
        ItemStack head = new ItemBuilder(Material.PLAYER_HEAD).build();
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(viewer);
            head.setItemMeta(meta);
        }
        ItemBuilder builder = new ItemBuilder(head)
                .name(MessageUtil.apply(cfg.getString("board.yours-name", "&eYour Bounty"), ph));
        if (mine == null) {
            builder.lore(applyList(cfg.getStringList("board.yours-none-lore"), ph));
        } else {
            ph.put("amount", EconomyManager.format(mine.amount()));
            ph.put("id", String.valueOf(mine.bountyId()));
            builder.lore(applyList(cfg.getStringList("board.yours-active-lore"), ph));
        }
        return builder.hideAttributes().build();
    }

    private ItemStack bountyHead(Bounty bounty) {
        FileConfiguration cfg = cfg();
        OfflinePlayer target = Bukkit.getOfflinePlayer(bounty.target());
        ItemStack head = new ItemBuilder(Material.PLAYER_HEAD).build();
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            head.setItemMeta(meta);
        }
        Map<String, String> ph = Map.of(
                "id", String.valueOf(bounty.bountyId()),
                "target", bounty.targetName() == null ? "?" : bounty.targetName(),
                "amount", EconomyManager.format(bounty.amount())
        );
        return new ItemBuilder(head)
                .name(MessageUtil.apply(cfg.getString("board.bounty-name", "&6#{id} {target}"), ph))
                .lore(applyList(cfg.getStringList("board.bounty-lore"), ph))
                .hideAttributes()
                .build();
    }

    public void handleClick(Player player, int slot) {
        String action = actions.get(slot);
        if (action == null) {
            return;
        }
        BountyManager mgr = plugin.getBountyManager();
        switch (action) {
            case "place" -> mgr.openPlace(player);
            case "search" -> {
                player.closeInventory();
                mgr.beginPrompt(player, new Prompt(PromptType.BOARD_SEARCH, null, null));
                mgr.msg(player, "search-prompt", Map.of());
            }
            case "refresh" -> BountyBoardGui.open(plugin, player, page, filter);
            case "prev" -> BountyBoardGui.open(plugin, player, page - 1, filter);
            case "next" -> BountyBoardGui.open(plugin, player, page + 1, filter);
            default -> {
            }
        }
    }

    private Map<String, String> basePh(int count, int pages) {
        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(page + 1));
        ph.put("pages", String.valueOf(pages));
        ph.put("count", String.valueOf(count));
        return ph;
    }

    private static List<String> applyList(List<String> lines, Map<String, String> ph) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(MessageUtil.apply(line, ph));
        }
        return out;
    }

    private static ItemStack named(Material material, String name, List<String> lore, Map<String, String> ph) {
        return new ItemBuilder(material)
                .name(MessageUtil.apply(name, ph))
                .lore(lore)
                .hideAttributes()
                .build();
    }

    private static ItemStack pane(Material material) {
        return new ItemBuilder(material).name(" ").hideAttributes().build();
    }

    private static Material mat(String name, Material def) {
        if (name == null || name.isBlank()) {
            return def;
        }
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
