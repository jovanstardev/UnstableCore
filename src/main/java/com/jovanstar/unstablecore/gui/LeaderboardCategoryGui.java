package com.jovanstar.unstablecore.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.leaderboard.LeaderboardCategory;
import com.jovanstar.unstablecore.leaderboard.LeaderboardEntry;
import com.jovanstar.unstablecore.manager.LeaderboardManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import com.jovanstar.unstablecore.util.SmallCaps;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

public final class LeaderboardCategoryGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final LeaderboardCategory category;
    private final int page;
    private final List<LeaderboardEntry> entries;
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();

    public LeaderboardCategoryGui(
            UnstableCore plugin,
            Player viewer,
            LeaderboardCategory category,
            int page,
            List<LeaderboardEntry> entries
    ) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.category = category;
        this.entries = entries == null ? List.of() : entries;
        LeaderboardManager mgr = plugin.getLeaderboardManager();
        FileConfiguration cfg = mgr.cfg();
        int pageSize = mgr.pageSize();
        int pages = Math.max(1, (int) Math.ceil(this.entries.size() / (double) pageSize));
        this.page = Math.max(0, Math.min(page, pages - 1));

        String prefix = cfg.getString("categories." + category.id() + ".detail-prefix", category.id());
        if (cfg.getBoolean("gui.category.small-caps-prefix", true)) {
            prefix = SmallCaps.colored(prefix);
        }
        Map<String, String> titlePh = Map.of(
                "prefix", prefix,
                "page", String.valueOf(this.page + 1),
                "total_pages", String.valueOf(pages),
                "category_id", category.id()
        );
        String title = MessageUtil.apply(
                cfg.getString("gui.category.title-template", "{prefix} &8(Page {page}/{total_pages})"),
                titlePh
        );
        this.inventory = Bukkit.createInventory(this, 54, MessageUtil.parse(title));
        fill(pageSize, pages);
    }

    private void fill(int pageSize, int pages) {
        actions.clear();
        FileConfiguration cfg = plugin.getLeaderboardManager().cfg();
        Material fill = mat(cfg.getString("gui.category.fill-material"), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fill).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int start = page * pageSize;
        int end = Math.min(entries.size(), start + pageSize);
        for (int i = start; i < end; i++) {
            int slot = i - start;
            if (slot >= pageSize || slot >= 45) {
                break;
            }
            inventory.setItem(slot, headItem(entries.get(i)));
        }

        boolean smallCapsButtons = cfg.getBoolean("gui.category.small-caps-buttons", true);
        int prevSlot = cfg.getInt("gui.category.slots.previous", 45);
        int backSlot = cfg.getInt("gui.category.slots.back", 46);
        int viewerSlot = cfg.getInt("gui.category.slots.viewer", 48);
        int refreshSlot = cfg.getInt("gui.category.slots.refresh", 49);
        int searchSlot = cfg.getInt("gui.category.slots.search", 50);
        int nextSlot = cfg.getInt("gui.category.slots.next", 53);

        if (page > 0 && validSlot(prevSlot)) {
            inventory.setItem(prevSlot, button("previous", smallCapsButtons, Map.of()));
            actions.put(prevSlot, "prev");
        }
        if (validSlot(backSlot)) {
            inventory.setItem(backSlot, button("back", smallCapsButtons, Map.of()));
            actions.put(backSlot, "back");
        }

        if (validSlot(viewerSlot)) {
            inventory.setItem(viewerSlot, viewerItem());
        }
        if (validSlot(refreshSlot)) {
            inventory.setItem(refreshSlot, button("refresh", smallCapsButtons, Map.of()));
            actions.put(refreshSlot, "refresh");
        }
        if (validSlot(searchSlot)) {
            inventory.setItem(searchSlot, button("search", smallCapsButtons, Map.of()));
            actions.put(searchSlot, "search");
        }

        if (page + 1 < pages && validSlot(nextSlot)) {
            inventory.setItem(nextSlot, button("next", smallCapsButtons, Map.of()));
            actions.put(nextSlot, "next");
        }

        if (category == LeaderboardCategory.COINS && !plugin.getEconomyManager().isReady()) {
            int slot = cfg.getInt("gui.category.slots.no-economy", 22);
            if (validSlot(slot)) {
                inventory.setItem(slot, button("no-economy", smallCapsButtons, Map.of()));
            }
        }
    }

    private boolean validSlot(int slot) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private ItemStack headItem(LeaderboardEntry entry) {
        FileConfiguration cfg = plugin.getLeaderboardManager().cfg();
        String base = "categories." + category.id();
        Map<String, String> ph = vars(entry);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            PlayerProfile profile = Bukkit.createProfile(entry.uuid(), entry.name());
            meta.setPlayerProfile(profile);
            head.setItemMeta(meta);
        }
        return new ItemBuilder(head)
                .name(MessageUtil.apply(cfg.getString(base + ".head-name", "&#00ffae{name}"), ph))
                .lore(applyList(cfg.getStringList(base + ".head-lore"), ph))
                .hideAttributes()
                .build();
    }

    private ItemStack viewerItem() {
        FileConfiguration cfg = plugin.getLeaderboardManager().cfg();
        LeaderboardEntry mine = null;
        for (LeaderboardEntry e : entries) {
            if (e.uuid().equals(viewer.getUniqueId())) {
                mine = e;
                break;
            }
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", viewer.getName());
        ph.put("uuid", viewer.getUniqueId().toString());
        ph.put("stat_label", cfg.getString("categories." + category.id() + ".stat-label", category.id()));
        ph.put("category_id", category.id());
        if (mine != null) {
            ph.put("rank", String.valueOf(mine.rank()));
            ph.put("value_raw", String.valueOf(mine.value()));
            ph.put("value_formatted", category.formatValue(mine.value()));
        } else {
            ph.put("rank", "-");
            ph.put("value_raw", "0");
            ph.put("value_formatted", "-");
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setPlayerProfile(Bukkit.createProfile(viewer.getUniqueId(), viewer.getName()));
            head.setItemMeta(meta);
        }
        String name = MessageUtil.apply(cfg.getString("gui.buttons.viewer.name", "&#00ffae{name}"), ph);
        if (cfg.getBoolean("gui.category.small-caps-buttons", true)) {
            name = SmallCaps.colored(name);
        }
        return new ItemBuilder(head)
                .name(name)
                .lore(applyList(cfg.getStringList("gui.buttons.viewer.lore"), ph))
                .hideAttributes()
                .build();
    }

    private ItemStack button(String id, boolean smallCaps, Map<String, String> ph) {
        FileConfiguration cfg = plugin.getLeaderboardManager().cfg();
        String path = "gui.buttons." + id;
        Material mat = mat(cfg.getString(path + ".material"), Material.ARROW);
        String name = MessageUtil.apply(cfg.getString(path + ".name", id), ph);
        if (smallCaps) {
            name = SmallCaps.colored(name);
        }
        return new ItemBuilder(mat)
                .name(name)
                .lore(applyList(cfg.getStringList(path + ".lore"), ph))
                .hideAttributes()
                .build();
    }

    private Map<String, String> vars(LeaderboardEntry entry) {
        FileConfiguration cfg = plugin.getLeaderboardManager().cfg();
        Map<String, String> ph = new HashMap<>();
        ph.put("name", entry.name());
        ph.put("uuid", entry.uuid().toString());
        ph.put("rank", String.valueOf(entry.rank()));
        ph.put("value_raw", String.valueOf(entry.value()));
        ph.put("value_formatted", category.formatValue(entry.value()));
        ph.put("stat_label", cfg.getString("categories." + category.id() + ".stat-label", category.id()));
        ph.put("category_id", category.id());
        return ph;
    }

    public void handleClick(Player player, int slot) {
        String action = actions.get(slot);
        if (action == null) {
            return;
        }
        LeaderboardManager mgr = plugin.getLeaderboardManager();
        switch (action) {
            case "prev" -> mgr.openCategory(player, category, page - 1);
            case "next" -> mgr.openCategory(player, category, page + 1);
            case "back" -> mgr.openMenu(player);
            case "refresh" -> mgr.refreshCategory(player, category, page);
            case "search" -> {
                player.closeInventory();
                mgr.beginSearch(player, category, page);
            }
            default -> {
            }
        }
    }

    public LeaderboardCategory category() {
        return category;
    }

    public int page() {
        return page;
    }

    private static List<String> applyList(List<String> lines, Map<String, String> ph) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(MessageUtil.apply(line, ph));
        }
        return out;
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
