package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DisposalGui implements InventoryHolder {

    /** Players warned this session that the bin destroys its contents on close. */
    private static final Set<UUID> WARNED = ConcurrentHashMap.newKeySet();
    /** Players who reached the bin from the kit confirm screen and should return to /kits. */
    private static final Set<UUID> RETURN_TO_KITS = ConcurrentHashMap.newKeySet();

    private final Inventory inventory;

    public DisposalGui(UnstableCore plugin) {
        int size = Math.max(9, Math.min(54, plugin.getConfig().getInt("disposal.size", 36)));
        if (size % 9 != 0) {
            size = 36;
        }
        Component title = MessageUtil.parse(plugin.getConfig().getString("disposal.title", "&8Disposal"));
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public void disposeContents() {
        inventory.clear();
    }

    public static void open(UnstableCore plugin, Player player) {
        // Once per session, not per open - a reminder helps a new player, a nag helps nobody.
        if (WARNED.add(player.getUniqueId())) {
            MessageUtil.send(player, plugin.getConfig().getString(
                    "messages.disposal-warning",
                    "&c\u26a0 &7Items left in the bin are &cdestroyed &7when you close it."));
        }
        player.openInventory(new DisposalGui(plugin).getInventory());
    }

    /** Opens the bin from the kit confirm screen; closing it returns the player to /kits. */
    public static void openWithReturn(UnstableCore plugin, Player player) {
        RETURN_TO_KITS.add(player.getUniqueId());
        open(plugin, player);
    }

    /** One-shot read of the return flag, consumed by GuiListener's close handler. */
    public static boolean consumeReturnToKits(UUID uuid) {
        return uuid != null && RETURN_TO_KITS.remove(uuid);
    }

    public static void clearPlayer(UUID uuid) {
        if (uuid != null) {
            WARNED.remove(uuid);
            RETURN_TO_KITS.remove(uuid);
        }
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
