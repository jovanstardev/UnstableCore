package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.DisposalGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class DisposalCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public DisposalCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        // The disposal GUI is a void-trash. During a duel it doubles as a way to empty the
        // plugin-issued kit on demand, which was the setup step for re-gearing mid-fight through
        // the "inventory is empty" auto-equip paths - and it also lets a losing player delete the
        // gear their opponent is fighting for. Duel inventories are managed end-to-end by
        // DuelManager; nothing should be able to void items out from under it.
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInCombatDuel(player.getUniqueId())) {
            com.jovanstar.unstablecore.util.MessageUtil.send(player,
                    plugin.getConfigManager().getDuels().getString(
                            "messages.disposal-blocked", "&cYou can't use the disposal during a duel."));
            return true;
        }
        DisposalGui.open(plugin, player);
        return true;
    }
}
