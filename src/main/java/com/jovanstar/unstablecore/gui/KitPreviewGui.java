package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

public final class KitPreviewGui implements InventoryHolder {

    private static final int BACK_SLOT = 53;
    private static final int DEFAULT_NAMETAG_SLOT = 49;
    private static final int[] GLASS_FILL_SLOTS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            45, 46, 47, 48, 49, 50, 51, 52
    };

    private final UnstableCore plugin;
    private final Inventory inventory;

    private KitPreviewGui(UnstableCore plugin, Kit kit, ItemStack[] contents) {
        this.plugin = plugin;
        String color = kit.getNameColor();
        this.inventory = Bukkit.createInventory(
                this,
                54,
                MessageUtil.parse("&aPREVIEW: " + color + "&l" + kit.getDisplayName())
        );

        int nametagSlot = DEFAULT_NAMETAG_SLOT;
        for (int i = 0; i < Math.min(contents.length, 54); i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == Material.NAME_TAG) {
                nametagSlot = i;
                break;
            }
        }

        ItemStack glass = unnamedGlass();
        for (int i = 0; i < Math.min(contents.length, 54); i++) {
            if (i == BACK_SLOT || i == nametagSlot) {
                continue;
            }
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || isPreviewFiller(stack.getType())) {
                continue;
            }
            if (stack.getType() == Material.NAME_TAG) {
                continue;
            }
            if (i >= 52 && stack.getType() == Material.ARROW) {
                continue;
            }
            inventory.setItem(i, stack.clone());
        }

        for (int slot : GLASS_FILL_SLOTS) {
            if (slot == nametagSlot || slot == BACK_SLOT) {
                continue;
            }
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir() || isPreviewFiller(existing.getType())) {
                inventory.setItem(slot, glass.clone());
            }
        }

        inventory.setItem(nametagSlot, buildPreviewNametag(kit));

        inventory.setItem(BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name("&bBack to Kits")
                .lore(
                        "&8---------------------------",
                        "&7Return to kit selection",
                        "&8---------------------------"
                )
                .hideAttributes()
                .build());
    }

    private static ItemStack buildPreviewNametag(Kit kit) {
        String color = kit.getNameColor() == null || kit.getNameColor().isBlank() ? "&f" : kit.getNameColor();
        String tier = kit.getTier() == null || kit.getTier().isBlank() ? "Common" : kit.getTier();
        String tierColor = tierColor(tier);
        return new ItemBuilder(Material.NAME_TAG)
                .name(color + "&l" + kit.getDisplayName())
                .lore(
                        "&8-----------------------",
                        "&7Tier: " + tierColor + tier,
                        "&8-----------------------"
                )
                .hideAttributes()
                .build();
    }

    private static String tierColor(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "starter" -> "&f";
            case "common" -> "&f";
            case "uncommon" -> "&a";
            case "rare" -> "&b";
            case "epic" -> "&d";
            case "legendary" -> "&6";
            case "mythic" -> "&c";
            default -> "&7";
        };
    }

    private static ItemStack unnamedGlass() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.parse(" "));
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private static boolean isPreviewFiller(Material type) {
        return type == Material.GRAY_STAINED_GLASS_PANE
                || type == Material.BLACK_STAINED_GLASS_PANE
                || type == Material.LIGHT_GRAY_STAINED_GLASS_PANE;
    }

    public void handleClick(Player player, int slot, ClickType click) {
        if (slot == BACK_SLOT) {
            KitsGui.open(plugin, player);
        }
    }

    public static void open(UnstableCore plugin, Player player, String kitId) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            MessageUtil.sendConfig(player, "kit-not-found", Map.of("kit", kitId));
            return;
        }
        player.openInventory(new KitPreviewGui(plugin, kit, kit.copyContents()).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
