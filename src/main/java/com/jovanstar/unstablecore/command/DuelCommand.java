package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.DuelHistoryGui;
import com.jovanstar.unstablecore.gui.DuelMapGui;
import com.jovanstar.unstablecore.gui.DuelQueueGui;
import com.jovanstar.unstablecore.manager.DuelQueueManager;
import com.jovanstar.unstablecore.manager.DuelStatsManager;
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
import java.util.UUID;
import java.util.stream.Collectors;

public final class DuelCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "queue", "accept", "deny", "cancel", "toggle", "history", "elo", "stats", "forceend"
    );

    private static final List<String> QUEUE_SUBS = List.of(
            "casual", "ranked", "leave"
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
            case "queue" -> handleQueue(player, args);
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
            case "elo", "stats" -> handleElo(player, args);
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

    private void handleQueue(Player player, String[] args) {
        if (args.length == 1) {
            DuelQueueGui.open(plugin, player);
            return;
        }
        String queueType = args[1].toLowerCase(Locale.ROOT);
        DuelQueueManager queueMgr = plugin.getDuelQueueManager();
        if (queueMgr == null) {
            MessageUtil.send(player, "&cQueue system is not available.");
            return;
        }
        switch (queueType) {
            case "casual", "unranked" -> queueMgr.joinQueue(player, DuelQueueManager.QueueType.CASUAL);
            case "ranked" -> queueMgr.joinQueue(player, DuelQueueManager.QueueType.RANKED);
            case "leave" -> queueMgr.leaveQueue(player);
            default -> {
                MessageUtil.send(player, "&cUsage: /duel queue [casual|ranked|leave]");
                DuelQueueGui.open(plugin, player);
            }
        }
    }

    private void handleElo(Player sender, String[] args) {
        Player target = sender;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                return;
            }
        }
        DuelStatsManager stats = plugin.getDuelStatsManager();
        if (stats == null) return;
        UUID uuid = target.getUniqueId();
        int elo = stats.getElo(uuid);
        String tier = stats.getRankTier(elo);
        int wins = stats.getWins(uuid);
        int losses = stats.getLosses(uuid);
        int streak = stats.getCurrentStreak(uuid);
        int best = stats.getBestStreak(uuid);
        double winrate = stats.getWinRate(uuid);

        MessageUtil.send(sender, "&8&m----------------------------------");
        MessageUtil.send(sender, "&d&lDUEL STATS &8» &f" + target.getName());
        MessageUtil.send(sender, "&7Rating: &b" + elo + " ELO &8(" + tier + "&8)");
        MessageUtil.send(sender, "&7Wins: &a" + wins + " &8| &7Losses: &c" + losses + " &8(&e" + String.format("%.1f", winrate) + "%&8)");
        MessageUtil.send(sender, "&7Streak: &e" + streak + " &8(Best: &6" + best + "&8)");
        MessageUtil.send(sender, "&8&m----------------------------------");
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
        MessageUtil.send(player, "&e/duel queue &7- open queue menu (casual / ranked)");
        MessageUtil.send(player, "&e/duel queue casual &7- join casual matchmaking");
        MessageUtil.send(player, "&e/duel queue ranked &7- join ranked matchmaking");
        MessageUtil.send(player, "&e/duel queue leave &7- leave matchmaking queue");
        MessageUtil.send(player, "&e/duel elo [player] &7- view duel rating & stats");
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
        if (args.length == 2 && args[0].equalsIgnoreCase("queue")) {
            return filter(QUEUE_SUBS, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("elo") || args[0].equalsIgnoreCase("stats"))) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                players.add(p.getName());
            }
            return filter(players, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
