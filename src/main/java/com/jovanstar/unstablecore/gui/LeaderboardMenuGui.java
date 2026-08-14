package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.leaderboard.LeaderboardCategory;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import com.jovanstar.unstablecore.util.SmallCaps;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LeaderboardMenuGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Inventory inventory;
    private final Map<Integer, LeaderboardCategory> slotCategories = new HashMap<>();

    public LeaderboardMenuGui(UnstableCore plugin, Player viewer) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfigManager().getLeaderboard();
        String title = cfg.getString("gui.main.title", "&8Leaderboards");
        if (cfg.getBoolean("gui.main.small-caps-title", true)) {
            title = SmallCaps.colored(title);
        }
        int size = Math.max(9, Math.min(54, cfg.getInt("gui.main.size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        this.inventory = Bukkit.createInventory(this, size, MessageUtil.parse(title));
        fill(cfg, viewer);
    }

    private void fill(FileConfiguration cfg, Player viewer) {
        Material fill = mat(cfg.getString("gui.main.fill-material"), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fill).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ConfigurationSection slots = cfg.getConfigurationSection("gui.main.slots");
        if (slots == null) {
            return;
        }
        boolean smallCapsNames = cfg.getBoolean("gui.main.small-caps-menu-names", true);
        for (String key : slots.getKeys(false)) {
            LeaderboardCategory cat = LeaderboardCategory.fromId(key);
            if (cat == null || !plugin.getLeaderboardManager().isCategoryEnabled(cat)) {
                continue;
            }
            int slot = slots.getInt(key, -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            String base = "categories." + cat.id();
            Material icon = mat(cfg.getString(base + ".material"), Material.PAPER);
            Map<String, String> ph = Map.of(
                    "stat_label", cfg.getString(base + ".stat-label", cat.id()),
                    "category_id", cat.id()
            );
            String name = MessageUtil.apply(cfg.getString(base + ".menu-name", cat.id()), ph);
            if (smallCapsNames) {
                name = SmallCaps.colored(name);
            }
            List<String> lore = new ArrayList<>();
            for (String line : cfg.getStringList(base + ".menu-lore")) {
                lore.add(MessageUtil.apply(line, ph));
            }
            inventory.setItem(slot, new ItemBuilder(icon).name(name).lore(lore).hideAttributes().build());
            slotCategories.put(slot, cat);
        }
    }

    public void handleClick(Player player, int slot) {
        LeaderboardCategory cat = slotCategories.get(slot);
        if (cat == null) {
            return;
        }
        plugin.getLeaderboardManager().openCategory(player, cat, 0);
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
