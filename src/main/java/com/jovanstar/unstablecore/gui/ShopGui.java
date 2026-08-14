package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.ShopManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

public final class ShopGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final String categoryId;
    private final Inventory inventory;
    private final Map<Integer, String> slotActions = new HashMap<>();

    public ShopGui(UnstableCore plugin, Player viewer, String categoryId) {
        this.plugin = plugin;
        this.viewer = viewer;
        ShopManager shop = plugin.getShopManager();
        ConfigurationSection cat = shop.category(categoryId);
        if (cat == null) {
            categoryId = shop.defaultCategory();
            cat = shop.category(categoryId);
        }
        this.categoryId = categoryId;

        int size = cat != null ? Math.max(9, Math.min(54, cat.getInt("size", 54))) : 54;
        if (size % 9 != 0) {
            size = 54;
        }
        String titleRaw = cat != null ? cat.getString("title", "&bShop") : "&bShop";
        Component title = MessageUtil.parse(titleRaw);
        this.inventory = Bukkit.createInventory(this, size, title);
        fill(cat);
    }

    private void fill(ConfigurationSection cat) {
        ShopManager shop = plugin.getShopManager();
        Material fillerMat = material(cat != null ? cat.getString("filler") : null, Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ConfigurationSection nav = shop.config().getConfigurationSection("navigation");
        if (nav != null) {
            for (String key : nav.getKeys(false)) {
                ConfigurationSection entry = nav.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                int slot = entry.getInt("slot", -1);
                if (slot < 0 || slot >= inventory.getSize()) {
                    continue;
                }
                String action = entry.getString("action", key);
                Material mat = material(entry.getString("material"), Material.STONE);
                ItemBuilder builder = new ItemBuilder(mat);
                if (mat == Material.PLAYER_HEAD) {
                    ItemStack head = builder.build();
                    SkullMeta meta = (SkullMeta) head.getItemMeta();
                    if (meta != null) {
                        meta.setOwningPlayer(viewer);
                        head.setItemMeta(meta);
                        builder = new ItemBuilder(head);
                    }
                }
                String name = shop.applyPlayer(entry.getString("name", key), viewer, 0, key);
                List<String> lore = new ArrayList<>();
                List<String> baseLore = entry.getStringList("lore");
                if (shop.category(key) != null && key.equalsIgnoreCase(categoryId)) {
                    ConfigurationSection selectedCat = shop.category(categoryId);
                    List<String> selected = selectedCat.getStringList("selected-lore");
                    if (!selected.isEmpty()) {
                        baseLore = selected;
                    }
                }
                for (String line : baseLore) {
                    lore.add(shop.applyPlayer(line, viewer, 0, key));
                }
                inventory.setItem(slot, builder.name(name).lore(lore).hideAttributes().build());
                if (!"none".equalsIgnoreCase(action) && shop.category(action) != null) {
                    slotActions.put(slot, "category:" + action);
                }
            }
        }

        ConfigurationSection close = shop.config().getConfigurationSection("close");
        if (close != null) {
            int slot = close.getInt("slot", 49);
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, new ItemBuilder(material(close.getString("material"), Material.BARRIER))
                        .name(close.getString("name", "&cClose"))
                        .lore(close.getStringList("lore"))
                        .hideAttributes()
                        .build());
                slotActions.put(slot, "close");
            }
        }

        if (cat == null) {
            return;
        }
        ConfigurationSection items = cat.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String itemId : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }
            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                plugin.getLogger().warning("Shop item '" + itemId + "' has invalid slot " + slot);
                continue;
            }
            try {
                ItemStack display = shop.buildDisplayItem(viewer, categoryId, itemId);
                if (display != null) {
                    inventory.setItem(slot, display);
                    slotActions.put(slot, "buy:" + itemId);
                } else {
                    plugin.getLogger().warning("Shop item '" + itemId + "' failed to build (null).");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Shop item '" + itemId + "' failed: " + ex.getMessage());
            }
        }
    }

    public void handleClick(Player player, int slot) {
        String action = slotActions.get(slot);
        if (action == null) {
            return;
        }
        if (action.equals("close")) {
            player.closeInventory();
            return;
        }
        if (action.startsWith("category:")) {
            String cat = action.substring("category:".length());
            open(plugin, player, cat);
            return;
        }
        if (action.startsWith("buy:")) {
            String itemId = action.substring("buy:".length());
            plugin.getShopManager().purchase(player, categoryId, itemId);

            open(plugin, player, categoryId);
        }
    }

    public static void open(UnstableCore plugin, Player player) {
        open(plugin, player, plugin.getShopManager().defaultCategory());
    }

    public static void open(UnstableCore plugin, Player player, String category) {
        player.openInventory(new ShopGui(plugin, player, category).getInventory());
    }

    private static Material material(String name, Material def) {
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
