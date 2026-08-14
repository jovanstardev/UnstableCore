package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.BountyManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
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

public final class BountyCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public BountyCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getConfigManager().getBounty()
                    .getString("messages.player-only", "&cPlayers only."));
            return true;
        }
        BountyManager mgr = plugin.getBountyManager();
        if (mgr == null) {
            MessageUtil.send(player, "&cBounties are unavailable.");
            return true;
        }
        if (!mgr.enabled()) {
            mgr.msg(player, "disabled", Map.of());
            return true;
        }

        if (args.length == 0) {
            mgr.openBoard(player);
            return true;
        }

        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("place")) {
                mgr.openPlace(player);
                return true;
            }
            if (args[0].equalsIgnoreCase("me") || args[0].equalsIgnoreCase("mine")) {
                BountyManager.Bounty mine = mgr.get(player.getUniqueId());
                if (mine == null) {
                    mgr.msg(player, "none-on-you", Map.of());
                } else {
                    mgr.msg(player, "yours", Map.of(
                            "amount", EconomyManager.format(mine.amount()),
                            "id", String.valueOf(mine.bountyId())
                    ));
                }
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                mgr.msg(player, "offline", Map.of());
                return true;
            }
            player.closeInventory();
            mgr.promptAmount(player, target);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            mgr.msg(player, "offline", Map.of());
            return true;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1].replace(",", ""));
        } catch (NumberFormatException e) {
            mgr.msg(player, "invalid-amount", Map.of(
                    "min", EconomyManager.format(mgr.minAmount()),
                    "max", EconomyManager.format(mgr.maxAmount())
            ));
            return true;
        }
        mgr.placeOrStack(player, target, amount);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : List.of("place", "me")) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                    out.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            out.add(String.valueOf((int) plugin.getBountyManager().minAmount()));
            out.add("100");
            out.add("1000");
        }
        return out;
    }
}
