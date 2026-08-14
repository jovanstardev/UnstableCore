package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.SwordGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class SwordCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public SwordCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        SwordGui.open(plugin, player);
        return true;
    }
}
