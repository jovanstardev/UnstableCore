package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Opens the map-select GUI for a player inside the arena while a vote is running. */
public final class MapVoteCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public MapVoteCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!plugin.getMapVoteManager().isVoting()) {
            MessageUtil.send(player, "&cThere is no map vote running right now.");
            return true;
        }
        plugin.getMapVoteManager().openFor(player);
        return true;
    }
}
