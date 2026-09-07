package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.KitManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spend-by-consent confirm screen shown when a player clicks a locked kit in {@link KitsGui}.
 * Nothing is charged on that first click - a misclick in a busy menu must never cost coins.
 * The purchase happens only from the confirm button here, and even then
 * {@link KitManager#tryPurchaseUnlock} re-checks the unlock state and balance at click time,
 * so a balance spent elsewhere while this screen was open refuses cleanly instead of
 * overdrawing, and a kit unlocked meanwhile is never paid for twice.
 */
public final class KitPurchaseConfirmGui implements InventoryHolder {

    private static final int INFO_SLOT = 4;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private final UnstableCore plugin;
    private final Inventory inventory;
    private final String kitId;
    private final long armedAtMillis;

    private KitPurchaseConfirmGui(UnstableCore plugin, Player player, Kit kit) {
        this.plugin = plugin;
        this.kitId = kit.getId();
        this.armedAtMillis = System.currentTimeMillis()
                + Math.max(0L, plugin.getConfig().getLong("guis.confirm-arm-ms", 800L));
        this.inventory = Bukkit.createInventory(this, 27,
                MessageUtil.parse("&e$ &fBuy " + kit.getDisplayName() + "&7?"));
        fill(player, kit);
    }

    private void fill(Player player, Kit kit) {
        var filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideAttributes().build();
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }
        String price = EconomyManager.formatCommas(kit.getPrice());
        List<String> info = new ArrayList<>();
        info.add("&a$ &7Price: &e" + price + " coins");
        EconomyManager eco = plugin.getEconomyManager();
        if (eco != null && eco.isReady()) {
            info.add("&a$ &7Your balance: &e" + EconomyManager.formatCommas(eco.getBalance(player)) + " coins");
        }
        info.add("");
        info.add("&c⚠ All sales are final. The coins");
        info.add("&c   are taken the moment you confirm,");
        info.add("&c   and there are no refunds.");
        inventory.setItem(INFO_SLOT, new ItemBuilder(kit.getIcon())
                .name(kit.getNameColor() + "&l" + kit.getDisplayName())
                .lore(info)
                .hideAttributes()
                .build());
        inventory.setItem(CONFIRM_SLOT, new ItemBuilder(Material.LIME_CONCRETE)
                .name("&a&l✔ CONFIRM PURCHASE")
                .lore(
                        "&7Unlocks " + kit.getNameColor() + kit.getDisplayName() + " &7forever",
                        "&7and takes &e" + price + " coins&7.",
                        "",
                        "&cNo refunds. This cannot be undone."
                )
                .hideAttributes()
                .build());
        inventory.setItem(CANCEL_SLOT, new ItemBuilder(Material.RED_CONCRETE)
                .name("&c&l✘ CANCEL")
                .lore("&7Keep your coins, buy nothing.")
                .hideAttributes()
                .build());
    }

    public void handleClick(Player player, int slot) {
        switch (slot) {
            case CONFIRM_SLOT -> {
                if (armed()) {
                    confirmPurchase(player);
                }
            }
            case CANCEL_SLOT -> KitsGui.open(plugin, player);
            default -> {
            }
        }
    }

    /**
     * Swallows clicks on the confirm button for the first moments after this screen opens, so
     * spam-clicking a kit in {@link KitsGui} cannot click through onto a confirm button that
     * opens at the same cursor position (a kit at slot 11 lines up exactly with CONFIRM_SLOT).
     * Cancel is deliberately not gated - backing out must always work instantly.
     */
    private boolean armed() {
        return System.currentTimeMillis() >= armedAtMillis;
    }

    private void confirmPurchase(Player player) {
        KitManager kits = plugin.getKitManager();
        Kit kit = kits == null ? null : kits.getKit(kitId);
        if (kit == null) {
            player.closeInventory();
            return;
        }
        // The screen may have been open for a while; if the kit got unlocked meanwhile
        // (say, by an admin running /kit unlock), just select it - never charge for it again.
        if (kits.isUnlocked(player, kit)) {
            kits.selectKit(player, kit.getId());
            KitsGui.open(plugin, player);
            return;
        }
        EconomyManager eco = plugin.getEconomyManager();
        if (eco == null || !eco.isReady()) {
            MessageUtil.send(player, "&cEconomy is unavailable.");
            player.closeInventory();
            return;
        }
        if (!kits.tryPurchaseUnlock(player, kit)) {
            MessageUtil.sendConfig(player, "kit-cannot-afford", Map.of(
                    "kit", kit.getDisplayName(),
                    "price", EconomyManager.formatCommas(kit.getPrice())
            ));
            KitsGui.open(plugin, player);
            return;
        }
        kits.selectKit(player, kit.getId());
        MessageUtil.sendConfig(player, "kit-purchased", Map.of(
                "kit", kit.getDisplayName(),
                "price", EconomyManager.formatCommas(kit.getPrice())
        ));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 1.0f);
        // Back to the kits menu first; the empty-inventory auto-equip may then put its own
        // confirm screen on top - the same order of events as the old instant-buy path.
        KitsGui.open(plugin, player);
        KitsGui.autoEquipIfEmpty(plugin, player);
    }

    public static void open(UnstableCore plugin, Player player, Kit kit) {
        if (kit == null) {
            return;
        }
        player.openInventory(new KitPurchaseConfirmGui(plugin, player, kit).getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
