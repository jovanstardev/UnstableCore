package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class KillstreakCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public KillstreakCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }

        String name = command.getName().toLowerCase();
        switch (name) {
            case "killstreak" -> MessageUtil.sendConfig(player, "killstreak-show", Map.of(
                    "streak", String.valueOf(plugin.getKillstreakManager().getStreak(player.getUniqueId()))
            ));
            case "killstreaktoggle" -> {
                boolean enabled = plugin.getKillstreakManager().toggleTitles(player.getUniqueId());
                MessageUtil.sendConfig(player, enabled ? "killstreak-titles-on" : "killstreak-titles-off", Map.of());
            }
            case "resetkillstreak" -> {
                if (!player.hasPermission("unstablecore.killstreak.reset")) {
                    MessageUtil.sendConfig(player, "no-permission", Map.of());
                    return true;
                }
                plugin.getKillstreakManager().reset(player.getUniqueId());
                MessageUtil.sendConfig(player, "killstreak-reset", Map.of());
            }
            default -> {
            }
        }
        return true;
    }
}
