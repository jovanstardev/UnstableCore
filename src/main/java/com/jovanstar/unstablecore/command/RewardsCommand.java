package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.RewardsGui;
import com.jovanstar.unstablecore.manager.RewardsManager;
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

public final class RewardsCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public RewardsCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", java.util.Map.of());
            return true;
        }
        if (!plugin.getRewardsManager().isEnabled()) {
            MessageUtil.send(player, plugin.getConfigManager().getRewards()
                    .getString("messages.disabled", "&cDaily rewards are disabled."));
            return true;
        }
        RewardsManager.Tab tab = RewardsManager.Tab.DAILY;
        if (args.length >= 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            if (a.startsWith("w")) {
                tab = RewardsManager.Tab.WEEKLY;
            } else if (a.startsWith("m")) {
                tab = RewardsManager.Tab.MONTHLY;
            }
        }
        RewardsGui.open(plugin, player, tab);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("daily", "weekly", "monthly")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }
}
