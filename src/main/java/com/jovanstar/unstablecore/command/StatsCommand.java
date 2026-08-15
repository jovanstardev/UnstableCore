package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.StatsGui;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class StatsCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public StatsCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }

        OfflinePlayer target = player;
        if (args.length >= 1) {
            target = plugin.getStatsManager().resolvePlayer(args[0]);
            if (target == null || target.getName() == null) {
                MessageUtil.sendConfig(player, "player-not-found", Map.of());
                return true;
            }
        }

        StatsGui.open(plugin, player, target);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            // Bukkit's getOnlinePlayers() returns vanished players regardless of visibility -
            // without filtering, a regular player could type /stats <partial> to enumerate the
            // real online name of a staff member hidden from them.
            Player viewer = sender instanceof Player p ? p : null;
            return plugin.getServer().getOnlinePlayers().stream()
                    .filter(p -> (viewer == null || viewer.canSee(p)) && !p.hasMetadata("vanished"))
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return List.of();
    }
}
