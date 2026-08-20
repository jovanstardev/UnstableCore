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
        // One shared claim cooldown for the whole menu (it is per-player, not per-kit), shown on
        // every claimable kit so players know before clicking whether equipping will work.
        long cooldownRemain = plugin.getLoadoutManager() == null
                ? 0L : plugin.getLoadoutManager().remainingMillis(player.getUniqueId());
        String cooldownLine = cooldownRemain <= 0 ? null
                : "&e\u23f3 Claim cooldown: &f"
                        + com.jovanstar.unstablecore.manager.EventManager.formatDurationMillis(cooldownRemain);
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
                java.util.List<String> lore = new java.util.ArrayList<>();
                if (isStarter(kit)) {
                    lore.add("&7Type: &fStarter Kit");
                    lore.add("&aSelected as your current kit.");
                } else {
                    lore.add(tierLine(kit));
                    lore.add("");
                    lore.add("&aSelected as your current kit.");
                }
                if (cooldownLine != null) {
                    lore.add(cooldownLine);
                }
                lore.add("");
                if (!isStarter(kit)) {
                    lore.add("&6> Left-click &fto select");
                }
                lore.add("&b> Right-click &fto preview");
                builder.lore(lore);
            } else if (unlocked) {
                builder.name("&aUnlocked &8| " + color + "&l" + kit.getDisplayName());
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add(tierLine(kit));
                if (cooldownLine != null) {
                    lore.add(cooldownLine);
                }
                lore.add("");
                lore.add("&6> Left-click &fto select");
                lore.add("&b> Right-click &fto preview");
                builder.lore(lore);
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
     * we never wipe them silently: a {@link KitConfirmGui} asks first, with a bin-and-equip
     * button, a "sort it myself" /trash shortcut, and a cancel.
     */
    private void autoEquipIfEmpty(Player player) {
        LoadoutManager loadouts = plugin.getLoadoutManager();
        if (loadouts == null) {
            return;
        }
        if (KitManager.isInventoryEmpty(player)) {
            loadouts.tryGive(player, true);
            return;
        }
        // Inventory not empty: only offer the destructive confirm when a claim would actually be
        // allowed right now. canClaim refuses - with the reason messaged - during a duel, inside
        // an arena, while a post-duel restore is owed, on cooldown, or with no kit. None of those
        // should reach a screen whose confirm button destroys the player's inventory, and gating
        // here is what keeps the confirm screen (and its /trash shortcut) off-limits mid-duel.
        if (!loadouts.canClaim(player, true)) {
            return;
        }
        Kit selected = plugin.getKitManager().getSelectedKit(player);
        if (selected != null) {
            KitConfirmGui.open(plugin, player, selected);
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
