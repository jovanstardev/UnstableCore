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

public final class DisposalGui implements InventoryHolder {

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
        player.openInventory(new DisposalGui(plugin).getInventory());
    }

    public static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
