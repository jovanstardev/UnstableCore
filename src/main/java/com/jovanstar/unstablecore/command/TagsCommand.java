package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.TagsGui;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class TagsCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public TagsCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "tags" -> TagsGui.openMain(plugin, player);
            case "emojitags" -> TagsGui.openCategory(plugin, player, "emoji");
            case "ranktags" -> TagsGui.openCategory(plugin, player, "ranks");
            case "auratags" -> TagsGui.openCategory(plugin, player, "aura");
            default -> {
            }
        }
        return true;
    }
}
