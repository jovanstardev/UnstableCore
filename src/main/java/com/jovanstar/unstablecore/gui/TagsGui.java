package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.TagManager;
import com.jovanstar.unstablecore.model.TagCategory;
import com.jovanstar.unstablecore.model.TagEntry;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public final class TagsGui implements InventoryHolder {

    public enum Mode {
        MAIN, CATEGORY
    }

    private final UnstableCore plugin;
    private final Mode mode;
    private final String categoryId;
    private final Inventory inventory;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    private TagsGui(UnstableCore plugin, Player player, Mode mode, String categoryId) {
        this.plugin = plugin;
        this.mode = mode;
        this.categoryId = categoryId;
        TagManager tags = plugin.getTagManager();
        String titleRaw = mode == Mode.MAIN ? tags.getMainTitle() : tags.getCategoryTitle(categoryId);
        this.inventory = Bukkit.createInventory(this, 27, MessageUtil.parse(titleRaw));
        fill(player);
    }

    private void fill(Player player) {
        TagManager tags = plugin.getTagManager();
        ItemStack filler = new ItemBuilder(tags.getFiller()).name(" ").hideAttributes().build();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        if (mode == Mode.MAIN) {
            for (TagCategory category : tags.getCategories().values()) {
                if (!validSlot(category.getSlot())) {
                    continue;
                }
                inventory.setItem(category.getSlot(), new ItemBuilder(category.getMaterial())
                        .name(category.getName())
                        .lore(category.getLore())
                        .hideAttributes()
                        .build());
                actions.put(category.getSlot(), () -> openCategory(plugin, player, category.getId()));
            }
        } else {
            TagCategory category = tags.getCategory(categoryId);
            if (category != null) {
                for (TagEntry entry : category.getTags()) {
                    if (!validSlot(entry.getSlot())) {
                        continue;
                    }
                    inventory.setItem(entry.getSlot(), new ItemBuilder(entry.getMaterial())
                            .name(entry.getName())
                            .lore("&7Click to equip")
                            .hideAttributes()
                            .build());
                    actions.put(entry.getSlot(), () -> {
                        if (!category.getPermission().isBlank() && !player.hasPermission(category.getPermission())) {
                            MessageUtil.sendConfig(player, "tag-no-own", Map.of("category", category.getId()));
                            return;
                        }
                        player.closeInventory();
                        tags.equip(player, entry.getSuffix());
                    });
                }
            }
        }

        if (validSlot(tags.getClearSlot())) {
            inventory.setItem(tags.getClearSlot(), new ItemBuilder(tags.getClearMaterial())
                    .name(tags.getClearName())
                    .lore(tags.getClearLore())
                    .hideAttributes()
                    .build());
            actions.put(tags.getClearSlot(), () -> {
                player.closeInventory();
                tags.clear(player);
            });
        }
    }

    private boolean validSlot(int slot) {
        return slot >= 0 && slot < inventory.getSize();
    }

    public void handleClick(Player player, int slot) {
        Runnable action = actions.get(slot);
        if (action != null) {
            action.run();
        }
    }

    public static void openMain(UnstableCore plugin, Player player) {
        player.openInventory(new TagsGui(plugin, player, Mode.MAIN, null).getInventory());
    }

    public static void openCategory(UnstableCore plugin, Player player, String categoryId) {
        player.openInventory(new TagsGui(plugin, player, Mode.CATEGORY, categoryId).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
