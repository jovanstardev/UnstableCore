package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelArenaManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.model.Duel;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Staff inspection/cleanup tools. `/duels` is registered as an alias in plugin.yml to this same executor. */
public final class DuelAdminCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public DuelAdminCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.duel.admin")) {
            MessageUtil.sendConfig(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> listActive(sender);
            case "inspect" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin inspect <player>");
                    return true;
                }
                inspect(sender, args[1]);
            }
            case "forceend" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin forceend <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                    return true;
                }
                boolean ok = plugin.getDuelManager().forceEnd(target);
                MessageUtil.send(sender, ok
                        ? "&aForce-ended the duel involving &f" + target.getName() + "&a."
                        : "&c" + target.getName() + " isn't in a duel.");
            }
            case "arena" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin arena <arena>");
                    return true;
                }
                inspectArena(sender, args[1]);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void listActive(CommandSender sender) {
        var duels = plugin.getDuelManager().getAllDuels();
        if (duels.isEmpty()) {
            MessageUtil.send(sender, "&7No active duels.");
            return;
        }
        MessageUtil.send(sender, "&d&lActive Duels &7(" + duels.size() + ")");
        long now = System.currentTimeMillis();
        for (Duel duel : duels) {
            long elapsedSec = Math.max(0, (now - duel.getCreatedAt()) / 1000L);
            MessageUtil.send(sender, "&8• &f#" + shortId(duel) + " &7"
                    + nameOf(duel.getChallenger()) + " vs " + nameOf(duel.getTarget())
                    + " &7| arena=&f" + duel.getArenaId()
                    + " &7| state=&f" + duel.getState()
                    + " &7| wager=&f" + EconomyManager.format(duel.getWager())
                    + " &7| " + elapsedSec + "s");
        }
    }

    private void inspect(CommandSender sender, String playerName) {
        // Avoid the legacy name-based Bukkit.getOfflinePlayer(String), which can trigger a
        // blocking Mojang lookup for a name never seen on this server - reuse the same
        // cache-only resolver StatsManager already built for /stats.
        OfflinePlayer target = plugin.getStatsManager().resolvePlayer(playerName);
        if (target == null) {
            MessageUtil.sendConfig(sender, "player-not-found", Map.of());
            return;
        }
        UUID uuid = target.getUniqueId();
        Duel duel = plugin.getDuelManager().getDuelForPlayer(uuid);
        if (duel == null) {
            duel = plugin.getDuelManager().getGraceDuelForPlayer(uuid);
        }
        if (duel == null) {
            MessageUtil.send(sender, "&c" + playerName + " isn't in a duel.");
            return;
        }
        long elapsedSec = Math.max(0, (System.currentTimeMillis() - duel.getCreatedAt()) / 1000L);
        MessageUtil.send(sender, "&d&lDuel Inspect &7» &f#" + shortId(duel));
        MessageUtil.send(sender, "&7Challenger: &f" + nameOf(duel.getChallenger())
                + " &7Target: &f" + nameOf(duel.getTarget()));
        MessageUtil.send(sender, "&7State: &f" + duel.getState()
                + " &7Arena: &f" + duel.getArenaId() + " &7Kit: &f" + duel.getKitId());
        MessageUtil.send(sender, "&7Wager: &f" + EconomyManager.format(duel.getWager())
                + " &7Escrowed: &f" + duel.isEscrowed() + " &7Payout done: &f" + duel.isPayoutDone());
        MessageUtil.send(sender, "&7Elapsed: &f" + elapsedSec + "s &7Full ID: &7" + duel.getId());
    }

    private void inspectArena(CommandSender sender, String arenaId) {
        DuelArenaManager arenaManager = plugin.getDuelManager().getDuelArenaManager();
        DuelArenaManager.Availability availability = arenaManager.availability(arenaId);
        UUID reservedBy = arenaManager.reservedByDuel(arenaId);
        MessageUtil.send(sender, "&d&lArena &f" + arenaId + " &7» &f" + availability
                + (reservedBy != null ? " &7(duel #" + reservedBy.toString().substring(0, 8) + ")" : ""));
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    private static String shortId(Duel duel) {
        return duel.getId().toString().substring(0, 8);
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&d✦ &fDuel Admin");
        MessageUtil.send(sender, "&e/dueladmin list &7- all active/pending duels");
        MessageUtil.send(sender, "&e/dueladmin inspect <player> &7- inspect a player's duel");
        MessageUtil.send(sender, "&e/dueladmin forceend <player> &7- safely force-end a duel");
        MessageUtil.send(sender, "&e/dueladmin arena <arena> &7- arena reservation status");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.duel.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("list", "inspect", "forceend", "arena");
        }
        return List.of();
    }
}
