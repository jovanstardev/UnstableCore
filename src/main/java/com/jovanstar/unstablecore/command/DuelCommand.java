package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.DuelHistoryGui;
import com.jovanstar.unstablecore.gui.DuelMapGui;
import com.jovanstar.unstablecore.manager.SettingsManager;
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
import java.util.stream.Collectors;

public final class DuelCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "accept", "deny", "cancel", "toggle", "history", "forceend"
    );

    private final UnstableCore plugin;

    public DuelCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }
        if (!plugin.getDuelManager().enabled()) {
            msg(player, "disabled", Map.of());
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept" -> plugin.getDuelManager().acceptDuel(player, args.length > 1 ? args[1] : null);
            case "deny" -> plugin.getDuelManager().declineDuel(player, args.length > 1 ? args[1] : null);
            case "cancel" -> plugin.getDuelManager().cancelRequest(player, args.length > 1 ? args[1] : null);
            case "toggle" -> {
                boolean now = plugin.getSettingsManager().toggle(player.getUniqueId(), SettingsManager.DUEL_REQUESTS);
                msg(player, now ? "toggle-on" : "toggle-off", Map.of());
            }
            case "history" -> {
                int page = 0;
                if (args.length > 1) {
                    try {
                        page = Math.max(0, Integer.parseInt(args[1]) - 1);
                    } catch (NumberFormatException ignored) {
                    }
                }
                DuelHistoryGui.open(plugin, player, player.getUniqueId(), page);
            }
            case "forceend" -> {
                if (!player.hasPermission("unstablecore.duel.admin")) {
                    MessageUtil.sendConfig(sender, "no-permission", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /duel forceend <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                    return true;
                }
                boolean ok = plugin.getDuelManager().forceEnd(target);
                if (ok) {
                    msg(player, "admin-forceend-success", Map.of("player", target.getName()));
                } else {
                    msg(player, "admin-not-found", Map.of());
                }
            }
            default -> requestDuel(player, args[0]);
        }
        return true;
    }

    private void requestDuel(Player challenger, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            msg(challenger, "target-offline", Map.of());
            return;
        }
        String err = plugin.getDuelManager().validateNewRequest(challenger.getUniqueId(), target.getUniqueId());
        if (err != null) {
            msg(challenger, err, Map.of("target", target.getName()));
            return;
        }
        if (plugin.getDuelManager().getDuelArenaManager().eligibleArenas().isEmpty()) {
            msg(challenger, "no-arenas-configured", Map.of());
            return;
        }
        DuelMapGui.open(plugin, challenger, target);
    }

    private void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = plugin.getConfigManager().getDuels().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    private void sendHelp(Player player) {
        MessageUtil.send(player, "&d✦ &fDuels");
        MessageUtil.send(player, "&e/duel <player> &7- challenge a player");
        MessageUtil.send(player, "&e/duel accept|deny [id] &7- respond to a pending request");
        MessageUtil.send(player, "&e/duel cancel &7- cancel your outgoing request");
        MessageUtil.send(player, "&e/duel toggle &7- accept/reject incoming requests");
        MessageUtil.send(player, "&e/duel history [page] &7- your duel history");
        MessageUtil.send(player, "&e/leave &7- leave the post-duel grace period early");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
            return filter(options, args[0]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
