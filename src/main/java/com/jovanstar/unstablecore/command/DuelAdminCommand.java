package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelArenaManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import java.util.UUID;
import java.util.stream.Collectors;

/** Staff inspection/cleanup tools. `/duels` is registered as an alias in plugin.yml to this same executor. */
public final class DuelAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "list", "inspect", "forceend", "arena", "setspawn1", "setspawn2", "setcenter", "spawns"
    );

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
            case "setspawn1", "spawn1" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "&cPlayers only.");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin setspawn1 <arena>");
                    return true;
                }
                String arenaName = args[1];
                boolean ok = plugin.getArenaManager().setSpawn1(arenaName, player.getLocation());
                if (ok) {
                    MessageUtil.send(sender, "&aSet &fSpawn 1 &a(Challenger spawn) for arena &f" + arenaName + " &aat your location.");
                } else {
                    MessageUtil.send(sender, "&cArena '" + arenaName + "' not found.");
                }
            }
            case "setspawn2", "spawn2" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "&cPlayers only.");
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin setspawn2 <arena>");
                    return true;
                }
                String arenaName = args[1];
                boolean ok = plugin.getArenaManager().setSpawn2(arenaName, player.getLocation());
                if (ok) {
                    MessageUtil.send(sender, "&aSet &fSpawn 2 &a(Target spawn) for arena &f" + arenaName + " &aat your location.");
                } else {
                    MessageUtil.send(sender, "&cArena '" + arenaName + "' not found.");
                }
            }
            case "setcenter" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, "&cPlayers only.");
                    return true;
                }
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin setcenter <arena> <radius>");
                    return true;
                }
                String arenaName = args[1];
                int radius = 50;
                try {
                    radius = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
                boolean ok = plugin.getArenaManager().setCenter(arenaName, player.getLocation(), radius);
                if (ok) {
                    MessageUtil.send(sender, "&aSet center and boundary radius (&f" + radius + "m&a) for arena &f" + arenaName + "&a.");
                } else {
                    MessageUtil.send(sender, "&cArena '" + arenaName + "' not found.");
                }
            }
            case "spawns" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /dueladmin spawns <arena>");
                    return true;
                }
                String arenaName = args[1];
                Arena arena = plugin.getArenaManager().getArena(arenaName);
                if (arena == null) {
                    MessageUtil.send(sender, "&cArena '" + arenaName + "' not found.");
                    return true;
                }
                MessageUtil.send(sender, "&d&lArena Spawns &8» &f" + arena.getDisplayName() + " &7(" + arena.getId() + ")");
                MessageUtil.send(sender, "&7Center: &f" + formatLoc(arena.getCenter()) + " &8| &7Radius: &e" + arena.getRadius() + "m");
                MessageUtil.send(sender, "&7Spawn 1 (Challenger): &f" + (arena.hasSpawn1() ? formatLoc(arena.getSpawn1()) : "&cNot set (using center)"));
                MessageUtil.send(sender, "&7Spawn 2 (Target): &f" + (arena.hasSpawn2() ? formatLoc(arena.getSpawn2()) : "&cNot set (using random spot)"));
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private String formatLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) return "None";
        return String.format("%.1f, %.1f, %.1f (%s)", loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
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
        MessageUtil.send(sender, "&d&lArena &f" + arenaId + " &7» &f" + availability);
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
        MessageUtil.send(sender, "&e/dueladmin arena <arena> &7- inspect arena status");
        MessageUtil.send(sender, "&e/dueladmin setspawn1 <arena> &7- set Challenger spawn at your position");
        MessageUtil.send(sender, "&e/dueladmin setspawn2 <arena> &7- set Target spawn at your position");
        MessageUtil.send(sender, "&e/dueladmin setcenter <arena> <radius> &7- set center and border radius");
        MessageUtil.send(sender, "&e/dueladmin spawns <arena> &7- check configured spawns for arena");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.duel.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(lower)).collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("arena")
                || args[0].equalsIgnoreCase("setspawn1")
                || args[0].equalsIgnoreCase("setspawn2")
                || args[0].equalsIgnoreCase("setcenter")
                || args[0].equalsIgnoreCase("spawns"))) {
            String lower = args[1].toLowerCase(Locale.ROOT);
            return plugin.getArenaManager().getArenas().keySet().stream()
                    .filter(s -> s.startsWith(lower)).collect(Collectors.toList());
        }
        return List.of();
    }
}
