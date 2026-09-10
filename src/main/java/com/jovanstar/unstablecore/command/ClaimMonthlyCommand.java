package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
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

public final class ClaimMonthlyCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public ClaimMonthlyCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reset")) {
                if (!sender.hasPermission("unstablecore.admin")) {
                    MessageUtil.send(sender, "&cYou do not have permission to reset monthly cooldowns.");
                    return true;
                }
                String targetName = args.length >= 2 ? args[1] : sender.getName();
                Player target = Bukkit.getPlayerExact(targetName);
                if (target != null) {
                    plugin.getRewardsManager().resetRankMonthlyPayout(target.getUniqueId());
                    MessageUtil.send(sender, "&aSuccessfully reset monthly rank cooldown for &e" + target.getName() + "&a. They can now claim immediately.");
                    return true;
                }
                OfflinePlayer offline = plugin.getStatsManager().resolvePlayer(targetName);
                if (offline != null && offline.getUniqueId() != null) {
                    plugin.getRewardsManager().resetRankMonthlyPayout(offline.getUniqueId());
                    MessageUtil.send(sender, "&aSuccessfully reset monthly rank cooldown for &e" + targetName + "&a.");
                    return true;
                }
                MessageUtil.send(sender, "&cPlayer not found: " + targetName);
                return true;
            }

            if (sub.equals("force")) {
                if (!sender.hasPermission("unstablecore.admin")) {
                    MessageUtil.send(sender, "&cYou do not have permission to force monthly rank payouts.");
                    return true;
                }
                Player target = (args.length >= 2) ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
                if (target == null) {
                    MessageUtil.send(sender, "&cUsage: /" + label + " force <online player>");
                    return true;
                }
                plugin.getRewardsManager().claimMonthlyRankReward(target, true);
                if (!target.equals(sender)) {
                    MessageUtil.send(sender, "&aForced monthly rank payout claim for &e" + target.getName() + "&a.");
                }
                return true;
            }
        }

        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", java.util.Map.of());
            return true;
        }

        plugin.getRewardsManager().claimMonthlyRankReward(player, false);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            for (String sub : List.of("reset", "force")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    list.add(sub);
                }
            }
            return list;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("force"))) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    players.add(p.getName());
                }
            }
            return players;
        }
        return List.of();
    }
}
