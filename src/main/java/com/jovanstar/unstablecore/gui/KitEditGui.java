package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class KitEditGui implements InventoryHolder {

    private static final String DIVIDER = "&8-----------------------";
    private static final int EDITABLE_END = 44;
    private static final int SAVE_SLOT = 47;
    private static final int RESET_SLOT = 50;
    private static final int CANCEL_SLOT = 52;

    private final UnstableCore plugin;
    private final String kitId;
    private final Inventory inventory;
    private final ItemStack[] original;

    private KitEditGui(UnstableCore plugin, Player player, Kit kit) {
        this.plugin = plugin;
        this.kitId = kit.getId();
        this.inventory = Bukkit.createInventory(this, 54, MessageUtil.parse("&aEDIT KIT"));
        this.original = plugin.getKitManager().getEffectiveContents(player, kit);
        for (int i = 0; i < Math.min(original.length, 45); i++) {
            if (original[i] != null && !original[i].getType().isAir()) {
                inventory.setItem(i, original[i].clone());
            }
        }
        placeButtons(kit);
    }

    private void placeButtons(Kit kit) {
        ItemStack blank = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .hideAttributes()
                .build();
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, blank);
        }

        inventory.setItem(45, new ItemBuilder(Material.PAPER)
                .name("&bEditing &f| &f" + kit.getDisplayName())
                .lore(
                        DIVIDER,
                        "&fRearrange kit items only",
                        "&7Hotbar: row 1",
                        "&7Inventory: rows 2-4",
                        "&cCannot take items out",
                        DIVIDER
                )
                .hideAttributes()
                .build());

        inventory.setItem(SAVE_SLOT, new ItemBuilder(Material.LIME_DYE)
                .name("&aSave Layout")
                .lore(
                        DIVIDER,
                        "&fSave this arrangement"
                )
                .hideAttributes()
                .build());

        inventory.setItem(RESET_SLOT, new ItemBuilder(Material.ORANGE_DYE)
                .name("&eReset Layout")
                .lore(
                        DIVIDER,
                        "&fRestore the default kit layout"
                )
                .hideAttributes()
                .build());

        inventory.setItem(CANCEL_SLOT, new ItemBuilder(Material.RED_DYE)
                .name("&c&lCancel")
                .lore(
                        DIVIDER,
                        "&fDiscard changes and return"
                )
                .hideAttributes()
                .build());
    }

    public boolean isEditableSlot(int slot) {
        return slot >= 0 && slot <= EDITABLE_END;
    }

    public boolean isButtonSlot(int slot) {
        return slot >= 45 && slot < 54;
    }

    public void handleClick(Player player, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();

        if (raw >= topSize || raw < 0) {
            event.setCancelled(true);
            return;
        }

        if (isButtonSlot(raw)) {
            event.setCancelled(true);
            if (raw == SAVE_SLOT) {
                clearCursor(player);
                save(player);
            } else if (raw == RESET_SLOT) {
                clearCursor(player);
                reset(player);
            } else if (raw == CANCEL_SLOT) {
                clearCursor(player);
                player.closeInventory();
                KitsGui.open(plugin, player);
            }
            return;
        }

        if (!isEditableSlot(raw)) {
            event.setCancelled(true);
            return;
        }

        if (click == ClickType.SHIFT_LEFT
                || click == ClickType.SHIFT_RIGHT
                || click == ClickType.NUMBER_KEY
                || click == ClickType.DROP
                || click == ClickType.CONTROL_DROP
                || click == ClickType.DOUBLE_CLICK
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(false);
    }

    public void onClose(Player player) {
        clearCursor(player);
    }

    private static void clearCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            player.setItemOnCursor(null);
        }
    }

    private void save(Player player) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        ItemStack[] layout = kit != null
                ? plugin.getKitManager().getEffectiveContents(player, kit)
                : new ItemStack[Kit.CONTENTS_SIZE];
        if (layout.length < Kit.CONTENTS_SIZE) {
            ItemStack[] full = new ItemStack[Kit.CONTENTS_SIZE];
            System.arraycopy(layout, 0, full, 0, layout.length);
            layout = full;
        }
        for (int i = 0; i < 45; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                layout[i] = stack.clone();
            } else {
                layout[i] = null;
            }
        }
        plugin.getKitManager().saveLayout(player, kitId, layout);
        MessageUtil.sendConfig(player, "kit-layout-saved", Map.of("kit", kitId.toUpperCase()));
        player.closeInventory();
        KitsGui.open(plugin, player);
    }

    private void reset(Player player) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            return;
        }
        plugin.getKitManager().clearLayout(player, kitId);
        ItemStack[] defaults = kit.copyContents();
        for (int i = 0; i < 45; i++) {
            inventory.setItem(i, null);
        }
        for (int i = 0; i < Math.min(defaults.length, 45); i++) {
            if (defaults[i] != null && !defaults[i].getType().isAir()) {
                inventory.setItem(i, defaults[i].clone());
            }
        }
        placeButtons(kit);
        MessageUtil.sendConfig(player, "kit-layout-reset", Map.of("kit", kit.getDisplayName()));
    }

    public static void open(UnstableCore plugin, Player player, String kitId) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            MessageUtil.sendConfig(player, "kit-not-found", Map.of("kit", kitId));
            return;
        }
        if (!plugin.getKitManager().isUnlocked(player, kit)) {
            MessageUtil.sendConfig(player, "kit-locked", Map.of("kit", kit.getDisplayName()));
            return;
        }
        player.openInventory(new KitEditGui(plugin, player, kit).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
