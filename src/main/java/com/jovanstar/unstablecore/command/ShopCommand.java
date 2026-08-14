package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.ShopGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ShopCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public ShopCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length > 0) {
            ShopGui.open(plugin, player, args[0].toLowerCase());
        } else {
            ShopGui.open(plugin, player);
        }
        return true;
    }
}
