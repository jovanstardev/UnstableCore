package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.leaderboard.LeaderboardCategory;
import com.jovanstar.unstablecore.manager.LeaderboardManager;
import com.jovanstar.unstablecore.util.MessageUtil;
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

public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public LeaderboardCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        LeaderboardManager mgr = plugin.getLeaderboardManager();
        if (mgr == null) {
            MessageUtil.send(sender, "&cLeaderboards unavailable.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("unstablecore.leaderboard.reload")
                    && !sender.hasPermission("unstablecore.admin")) {
                MessageUtil.send(sender, "&cNo permission.");
                return true;
            }
            plugin.getConfigManager().reloadLeaderboard();
            mgr.clearCache();
            MessageUtil.send(sender, mgr.cfg().getString("messages.reload-success", "&aReloaded."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, mgr.cfg().getString("messages.player-only", "&cPlayers only."));
            return true;
        }

        if (args.length == 0) {
            mgr.openMenu(player);
            return true;
        }

        LeaderboardCategory cat = LeaderboardCategory.fromId(args[0]);
        if (cat == null) {
            MessageUtil.send(player, mgr.cfg().getString("messages.usage",
                    "&7Usage: &f/leaderboard"));
            return true;
        }
        mgr.openCategory(player, cat, 0);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) {
            return out;
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        for (LeaderboardCategory cat : LeaderboardCategory.values()) {
            if (cat.id().startsWith(p)) {
                out.add(cat.id());
            }
        }
        if ("reload".startsWith(p) && (sender.hasPermission("unstablecore.leaderboard.reload")
                || sender.hasPermission("unstablecore.admin"))) {
            out.add("reload");
        }
        return out;
    }
}
