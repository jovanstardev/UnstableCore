package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.manager.LoadoutManager;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class KitsGui implements InventoryHolder {

    private static final int EDIT_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int INFO_SLOT = 4;

    private final UnstableCore plugin;
    private final Inventory inventory;
    private final Map<Integer, String> kitBySlot = new HashMap<>();

    private KitsGui(UnstableCore plugin, Player player) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 54, MessageUtil.parse("&d★ &f&lUNSTABLE KITS"));
        fill(player);
    }

    private void fill(Player player) {
        KitManager kits = plugin.getKitManager();
        inventory.clear();
        kitBySlot.clear();

        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(" ")
                .hideAttributes()
                .build();
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(INFO_SLOT, new ItemBuilder(Material.NETHER_STAR)
                .name("&d★ &f&lUNSTABLE KITS")
                .lore("&7Select a kit to fight with.")
                .hideAttributes()
                .build());

        String selectedId = kits.getSelectedId(player.getUniqueId());
        for (Kit kit : kits.getKitsBySlot()) {
            int slot = kit.getSlot();
            if (slot < 0 || slot >= 54 || slot == EDIT_SLOT || slot == CLOSE_SLOT || slot == INFO_SLOT) {
                continue;
            }
            boolean unlocked = kits.isUnlocked(player, kit);
            boolean selected = kit.getId().equalsIgnoreCase(selectedId);
            String color = kit.getNameColor();

            ItemBuilder builder = new ItemBuilder(kit.getIcon()).hideAttributes();
            if (selected) {
                builder.name("&aSelected &8| " + color + "&l" + kit.getDisplayName());
                if (isStarter(kit)) {
                    builder.lore(
                            "&7Type: &fStarter Kit",
                            "&aSelected as your current kit.",
                            "&b> Right-click &fto preview"
                    );
                } else {
                    builder.lore(
                            tierLine(kit),
                            "",
                            "&aSelected as your current kit.",
                            "",
                            "&6> Left-click &fto select",
                            "&b> Right-click &fto preview"
                    );
                }
            } else if (unlocked) {
                builder.name("&aUnlocked &8| " + color + "&l" + kit.getDisplayName());
                builder.lore(
                        tierLine(kit),
                        "",
                        "&6> Left-click &fto select",
                        "&b> Right-click &fto preview"
                );
            } else {
                builder.name("&cLocked &8| " + color + "&l" + kit.getDisplayName());
                builder.lore(
                        tierLine(kit),
                        "&a$ &7Price: &e" + EconomyManager.formatCommas(kit.getPrice()) + " coins",
                        "",
                        "&6> Left-click &fto unlock",
                        "&b> Right-click &fto preview"
                );
            }
            inventory.setItem(slot, builder.build());
            kitBySlot.put(slot, kit.getId());
        }

        inventory.setItem(EDIT_SLOT, new ItemBuilder(Material.ANVIL)
                .name("&d★ &fEDIT LAYOUT")
                .lore(
                        "&8---------------------",
                        "&7Rearrange your selected kit",
                        "&dClick to edit"
                )
                .hideAttributes()
                .build());

        inventory.setItem(CLOSE_SLOT, new ItemBuilder(Material.BARRIER)
                .name("&cCLOSE")
                .lore(
                        "&8---------------------",
                        "&7Close this menu"
                )
                .hideAttributes()
                .build());
    }

    private static boolean isStarter(Kit kit) {
        return "starter".equalsIgnoreCase(kit.getTier());
    }

    private static String tierLine(Kit kit) {
        String tier = kit.getTier() == null ? "Epic" : kit.getTier();
        String tierColor = switch (tier.toLowerCase(Locale.ROOT)) {
            case "starter" -> "&f";
            case "common" -> "&f";
            case "uncommon" -> "&a";
            case "rare" -> "&b";
            case "epic" -> "&d";
            case "legendary" -> "&6";
            case "mythic" -> "&c";
            default -> "&d";
        };
        if (isStarter(kit)) {
            return "&7Type: &fStarter Kit";
        }
        return "&7Tier: " + tierColor + tier;
    }

    public void handleClick(Player player, int slot, ClickType click) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == EDIT_SLOT) {
            Kit selected = plugin.getKitManager().getSelectedKit(player);
            if (selected == null) {
                MessageUtil.sendConfig(player, "kit-none-selected", Map.of());
                return;
            }
            KitEditGui.open(plugin, player, selected.getId());
            return;
        }
        if (slot == INFO_SLOT) {
            return;
        }

        String kitId = kitBySlot.get(slot);
        if (kitId == null) {
            return;
        }
        Kit kit = plugin.getKitManager().getKit(kitId);
        if (kit == null) {
            return;
        }

        if (click.isRightClick()) {
            KitPreviewGui.open(plugin, player, kit.getId());
            return;
        }

        if (!plugin.getKitManager().isUnlocked(player, kit)) {
            tryUnlock(player, kit);
            return;
        }

        if (plugin.getKitManager().selectKit(player, kit.getId())) {
            pling(player);
            MessageUtil.sendConfig(player, "kit-selected", Map.of("kit", kit.getDisplayName()));
            autoEquipIfEmpty(player);
            fill(player);
        }
    }

    /**
     * Auto-applies the just-selected kit if the player's inventory is completely empty, so
     * picking a kit from /kits doesn't require a separate /loadout. If they still have items,
     * we refuse to silently wipe them and instead point them at /trash - the whole message is
     * clickable and opens the disposal bin directly. The kit is already selected by the time
     * this runs, so once they've binned their items a reselect or /loadout claims it.
     */
    private void autoEquipIfEmpty(Player player) {
        if (!KitManager.isInventoryEmpty(player)) {
            String raw = plugin.getConfig().getString(
                    "messages.kit-selected-inventory-not-empty",
                    "&cYour inventory isn't empty. Click here or use &f/trash &cto bin your items"
                            + " and armor, then reselect the kit to claim it.");
            player.sendMessage(MessageUtil.parse(raw)
                    .clickEvent(ClickEvent.runCommand("/trash"))
                    .hoverEvent(HoverEvent.showText(MessageUtil.parse("&7Click to open the disposal bin"))));
            return;
        }
        LoadoutManager loadouts = plugin.getLoadoutManager();
        if (loadouts != null) {
            loadouts.tryGive(player, true);
        }
    }

    private void tryUnlock(Player player, Kit kit) {
        if (isStarter(kit)) {
            if (plugin.getKitManager().selectKit(player, kit.getId())) {
                pling(player);
                autoEquipIfEmpty(player);
            }
            fill(player);
            return;
        }
        if (kit.getPrice() <= 0) {
            MessageUtil.send(player, "&cThis kit is not available for purchase.");
            return;
        }
        EconomyManager eco = plugin.getEconomyManager();
        if (eco == null || !eco.isReady()) {
            MessageUtil.send(player, "&cEconomy is unavailable.");
            return;
        }
        if (!eco.has(player, kit.getPrice())) {
            MessageUtil.sendConfig(player, "kit-cannot-afford", Map.of(
                    "kit", kit.getDisplayName(),
                    "price", EconomyManager.formatCommas(kit.getPrice())
            ));
            return;
        }
        if (!plugin.getKitManager().tryPurchaseUnlock(player, kit)) {
            MessageUtil.sendConfig(player, "kit-cannot-afford", Map.of(
                    "kit", kit.getDisplayName(),
                    "price", EconomyManager.formatCommas(kit.getPrice())
            ));
            return;
        }
        plugin.getKitManager().selectKit(player, kit.getId());
        MessageUtil.sendConfig(player, "kit-purchased", Map.of(
                "kit", kit.getDisplayName(),
                "price", EconomyManager.formatCommas(kit.getPrice())
        ));
        autoEquipIfEmpty(player);
        pling(player);
        fill(player);
    }

    private static void pling(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    public static void open(UnstableCore plugin, Player player) {
        player.openInventory(new KitsGui(plugin, player).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
