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

public final class KitAdminEditGui implements InventoryHolder {

    private static final String DIVIDER = "&8-----------------------";
    private static final int EDITABLE_END = 51;
    private static final int SAVE_SLOT = 53;
    private static final int CANCEL_SLOT = 52;

    private final UnstableCore plugin;
    private final String kitId;
    private final Inventory inventory;

    private KitAdminEditGui(UnstableCore plugin, Kit kit) {
        this.plugin = plugin;
        this.kitId = kit.getId();
        this.inventory = Bukkit.createInventory(this, 54, MessageUtil.parse("&aEDIT KIT"));
        ItemStack[] contents = kit.copyContents();
        for (int i = 0; i < Math.min(contents.length, 54); i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                inventory.setItem(i, contents[i].clone());
            }
        }
        placeButtons(kit);
    }

    private void placeButtons(Kit kit) {
        inventory.setItem(SAVE_SLOT, new ItemBuilder(Material.LIME_DYE)
                .name("&aSave Kit")
                .lore(DIVIDER, "&fSave default kit contents", "&7Slots 0-51 editable")
                .hideAttributes()
                .build());
        inventory.setItem(CANCEL_SLOT, new ItemBuilder(Material.RED_DYE)
                .name("&c&lCancel")
                .lore(DIVIDER, "&fDiscard changes and return")
                .hideAttributes()
                .build());
    }

    public boolean isEditableSlot(int slot) {
        return slot >= 0 && slot <= EDITABLE_END;
    }

    public boolean isButtonSlot(int slot) {
        return slot == SAVE_SLOT || slot == CANCEL_SLOT;
    }

    public void handleClick(Player player, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();

        if (raw >= topSize || raw < 0) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(false);
                return;
            }
            event.setCancelled(false);
            return;
        }

        if (isButtonSlot(raw)) {
            event.setCancelled(true);
            if (raw == SAVE_SLOT) {
                save(player);
            } else if (raw == CANCEL_SLOT) {
                player.closeInventory();
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

    private void save(Player player) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            return;
        }
        ItemStack[] layout = new ItemStack[Kit.CONTENTS_SIZE];
        for (int i = 0; i <= EDITABLE_END; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                Material type = stack.getType();
                if (type == Material.LIME_DYE || type == Material.RED_DYE
                        || type == Material.GRAY_STAINED_GLASS_PANE || type == Material.PAPER
                        || type == Material.ORANGE_DYE) {
                    continue;
                }
                layout[i] = stack.clone();
            }
        }
        kit.setContents(layout);
        plugin.getKitManager().updateKit(kit);
        MessageUtil.sendConfig(player, "kit-contents-saved", Map.of("kit", kit.getDisplayName()));
        player.closeInventory();
    }

    public static void open(UnstableCore plugin, Player player, String kitId) {
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            MessageUtil.sendConfig(player, "kit-not-found", Map.of("kit", kitId));
            return;
        }
        player.openInventory(new KitAdminEditGui(plugin, kit).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
