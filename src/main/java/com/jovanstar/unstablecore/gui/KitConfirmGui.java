package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.manager.LoadoutManager;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Destructive-by-consent confirm screen shown when a player selects a kit from {@link KitsGui}
 * while still holding items: "bin everything and equip?". The wipe only ever happens from the
 * confirm button here, after {@link LoadoutManager#canClaim} has re-approved the claim at click
 * time - so an arena entry that began after the screen opened refuses cleanly instead of
 * destroying an inventory it shouldn't.
 */
public final class KitConfirmGui implements InventoryHolder {

    private static final int INFO_SLOT = 4;
    private static final int CONFIRM_SLOT = 11;
    private static final int TRASH_SLOT = 13;
    private static final int CANCEL_SLOT = 15;

    private final UnstableCore plugin;
    private final Inventory inventory;
    private final String kitId;

    private KitConfirmGui(UnstableCore plugin, Player player, Kit kit) {
        this.plugin = plugin;
        this.kitId = kit.getId();
        this.inventory = Bukkit.createInventory(this, 27,
                MessageUtil.parse("&c⚠ &fBin items &7& &fequip " + kit.getDisplayName() + "&7?"));
        fill(kit);
    }

    private void fill(Kit kit) {
        var filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideAttributes().build();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(INFO_SLOT, new ItemBuilder(kit.getIcon())
                .name(kit.getNameColor() + "&l" + kit.getDisplayName())
                .lore(
                        "&7Your inventory isn't empty, and",
                        "&7equipping needs it cleared first.",
                        "",
                        "&c⚠ Confirming destroys every item",
                        "&c   and armor piece you carry."
                )
                .hideAttributes()
                .build());
        inventory.setItem(CONFIRM_SLOT, new ItemBuilder(Material.LIME_CONCRETE)
                .name("&a&l✔ BIN ITEMS & EQUIP")
                .lore(
                        "&7Destroys everything you carry,",
                        "&7then equips " + kit.getNameColor() + kit.getDisplayName() + "&7.",
                        "",
                        "&cThis cannot be undone."
                )
                .hideAttributes()
                .build());
        inventory.setItem(TRASH_SLOT, new ItemBuilder(Material.CHEST)
                .name("&e&l☰ SORT IT MYSELF")
                .lore(
                        "&7Opens the &f/trash &7bin so you",
                        "&7can keep some items and dump",
                        "&7the rest. Closing the bin brings",
                        "&7you back here to the kits menu."
                )
                .hideAttributes()
                .build());
        inventory.setItem(CANCEL_SLOT, new ItemBuilder(Material.RED_CONCRETE)
                .name("&c&l✘ CANCEL")
                .lore("&7Keep your items, change nothing.")
                .hideAttributes()
                .build());
    }

    public void handleClick(Player player, int slot) {
        switch (slot) {
            case CONFIRM_SLOT -> confirmEquip(player);
            case TRASH_SLOT -> DisposalGui.openWithReturn(plugin, player);
            case CANCEL_SLOT -> KitsGui.open(plugin, player);
            default -> {
            }
        }
    }

    private void confirmEquip(Player player) {
        KitManager kits = plugin.getKitManager();
        LoadoutManager loadouts = plugin.getLoadoutManager();
        Kit kit = kits == null ? null : kits.getKit(kitId);
        if (kit == null || loadouts == null) {
            player.closeInventory();
            return;
        }
        // The screen may have been open for a while; re-align the selection with what this
        // screen promised to equip, then re-approve the claim *before* anything is destroyed.
        // canClaim refuses inside an arena and on cooldown - either of which may have started
        // after the screen opened. If the kit became unclaimable (locked/removed by a reload),
        // never wipe: equipping the stale prior selection is not what the player consented to.
        if (!kits.selectKit(player, kit.getId())) {
            player.closeInventory();
            return;
        }
        if (!loadouts.canClaim(player, true)) {
            player.closeInventory();
            return;
        }
        player.setItemOnCursor(null);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        // Same tick as the canClaim above, so nothing can have invalidated the claim in between.
        loadouts.tryGive(player, true);
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    public static void open(UnstableCore plugin, Player player, Kit kit) {
        if (kit == null) {
            return;
        }
        player.openInventory(new KitConfirmGui(plugin, player, kit).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
