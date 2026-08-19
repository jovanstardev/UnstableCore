package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.ArenaType;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class UnstableCoreCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public UnstableCoreCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.admin")) {
            MessageUtil.sendConfig(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPlugin();
                MessageUtil.sendConfig(sender, "reload", Map.of());
            }
            case "shop" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                    plugin.getConfigManager().resetShopToDefault();
                    MessageUtil.send(sender, "&aReset &fshop.yml &ato jar defaults.");
                } else {
                    MessageUtil.send(sender, "&e/uc shop reset &7- restore default shop.yml");
                }
            }
            case "vote" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("start")) {
                    if (plugin.getMapVoteManager().isVoting()) {
                        MessageUtil.send(sender, "&cA map vote is already running.");
                    } else {
                        plugin.getMapVoteManager().startVote();
                        MessageUtil.send(sender, "&aStarted map vote.");
                    }
                } else {
                    MessageUtil.send(sender, "&e/uc vote start &7- force start map vote");
                }
            }
            case "arena" -> handleArena(sender, args);
            case "economy", "eco" -> handleEconomy(sender, args);
            case "event" -> handleEvent(sender, args);
            case "mine" -> handleMine(sender, args);
            case "setspawn" -> handleSetSpawn(sender);
            case "loadout" -> handleLoadout(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleSetSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return;
        }
        Location loc = player.getLocation();
        plugin.getConfig().set("join.spawn.world", loc.getWorld().getName());
        plugin.getConfig().set("join.spawn.x", loc.getX());
        plugin.getConfig().set("join.spawn.y", loc.getY());
        plugin.getConfig().set("join.spawn.z", loc.getZ());
        plugin.getConfig().set("join.spawn.yaw", loc.getYaw());
        plugin.getConfig().set("join.spawn.pitch", loc.getPitch());
        plugin.saveConfig();
        MessageUtil.sendConfig(player, "spawn-set", Map.of());
    }

    private void handleMine(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("bypass")) {
            MessageUtil.send(sender, "&e/uc mine bypass [on|off]");
            return;
        }

        boolean enabled;
        if (args.length >= 3) {
            String mode = args[2].toLowerCase(Locale.ROOT);
            if (mode.equals("on") || mode.equals("true") || mode.equals("enable")) {
                enabled = true;
            } else if (mode.equals("off") || mode.equals("false") || mode.equals("disable")) {
                enabled = false;
            } else {
                MessageUtil.send(sender, "&cUsage: /uc mine bypass [on|off]");
                return;
            }
            plugin.getArenaManager().setMineBypass(player.getUniqueId(), enabled);
        } else {
            enabled = plugin.getArenaManager().toggleMineBypass(player.getUniqueId());
        }

        MessageUtil.sendConfig(player, enabled ? "mine-bypass-on" : "mine-bypass-off", Map.of());
    }

    private void handleArena(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&e/unstablecore arena create <name> <displayname> <mace|nomace>");
            MessageUtil.send(sender, "&e/unstablecore arena <name> center <radius>");
            MessageUtil.send(sender, "&e/unstablecore arena setnewbie <radius>");
            MessageUtil.send(sender, "&e/unstablecore arena permanent <name> <true|false>");
            MessageUtil.send(sender, "&e/unstablecore arena <name> type <mace|nomace>");
            MessageUtil.send(sender, "&e/unstablecore arena delete <name>");
            MessageUtil.send(sender, "&e/unstablecore arena rotate");
            MessageUtil.send(sender, "&e/unstablecore arena list");
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        if (sub.equals("create")) {
            if (args.length < 5) {
                MessageUtil.send(sender, "&cUsage: /unstablecore arena create <name> <displayname> <mace|nomace>");
                return;
            }
            String name = args[2];

            String typeRaw = args[args.length - 1];
            StringBuilder display = new StringBuilder(args[3]);
            for (int i = 4; i < args.length - 1; i++) {
                display.append(' ').append(args[i]);
            }
            ArenaType type = ArenaType.from(typeRaw);
            if (type == ArenaType.NOMACE && plugin.getArenaManager().isNomaceLimitReached()) {
                MessageUtil.sendConfig(sender, "arena-nomace-limit", Map.of(
                        "max", String.valueOf(plugin.getArenaManager().getMaxNomaceArenas()),
                        "count", String.valueOf(plugin.getArenaManager().countNomaceArenas())
                ));
                return;
            }
            if (plugin.getArenaManager().createArena(name, display.toString(), type)) {
                MessageUtil.sendConfig(sender, "arena-created", Map.of(
                        "name", name,
                        "display", display.toString(),
                        "type", type.name().toLowerCase()
                ));
            } else {
                MessageUtil.sendConfig(sender, "arena-exists", Map.of());
            }
            return;
        }

        if (sub.equals("delete")) {
            if (args.length < 3) {
                MessageUtil.send(sender, "&cUsage: /unstablecore arena delete <name>");
                return;
            }
            if (plugin.getArenaManager().deleteArena(args[2])) {
                MessageUtil.sendConfig(sender, "arena-deleted", Map.of("name", args[2]));
            } else {
                MessageUtil.sendConfig(sender, "arena-not-found", Map.of());
            }
            return;
        }

        if (sub.equals("rotate")) {
            plugin.getArenaManager().rotateActive(true);
            var active = plugin.getArenaManager().getActiveArena();
            MessageUtil.sendConfig(sender, "arena-rotated", Map.of(
                    "name", active == null ? "none" : active.getDisplayName()
            ));
            return;
        }

        if (sub.equals("list")) {
            MessageUtil.send(sender, "&eArenas: &7(nomace "
                    + plugin.getArenaManager().countNomaceArenas() + "/"
                    + plugin.getArenaManager().getMaxNomaceArenas() + ", under6h counts)");
            Arena newbie = plugin.getArenaManager().getNewbieArena();
            if (newbie != null) {
                MessageUtil.send(sender, "&7- &fnewbie &8| &f" + newbie.getDisplayName()
                        + " &8| &enomace &8| &b[under 6h]"
                        + (newbie.hasCenter() ? "" : " &c[no center]"));
            }
            plugin.getArenaManager().getArenas().values().forEach(a ->
                    MessageUtil.send(sender, "&7- &f" + a.getId() + " &8| &f" + a.getDisplayName()
                            + " &8| &f" + a.getType().name().toLowerCase()
                            + (a.isPermanent() ? " &d[permanent]" : "")
                            + (a.getId().equals(plugin.getArenaManager().getActiveArenaId()) ? " &a[active]" : "")
                            + (a.hasCenter() ? "" : " &c[no center]")));
            return;
        }

        if (sub.equals("setnewbie")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.sendConfig(sender, "player-only", Map.of());
                return;
            }
            if (args.length < 3) {
                MessageUtil.send(sender, "&cUsage: /unstablecore arena setnewbie <radius>");
                return;
            }
            int radius = parseInt(args[2], 50);
            plugin.getArenaManager().setNewbieCenter(player.getLocation(), radius);
            MessageUtil.sendConfig(sender, "arena-newbie-set", Map.of());
            return;
        }

        if (sub.equals("permanent")) {
            if (args.length < 4) {
                MessageUtil.send(sender, "&cUsage: /unstablecore arena permanent <name> <true|false>");
                return;
            }
            boolean value = Boolean.parseBoolean(args[3]);
            if (plugin.getArenaManager().getArena(args[2]) == null) {
                MessageUtil.sendConfig(sender, "arena-not-found", Map.of());
                return;
            }
            plugin.getArenaManager().setPermanent(args[2], value);
            MessageUtil.send(sender, "&aSet permanent for &f" + args[2] + " &ato &f" + value);
            return;
        }

        if (args.length >= 4 && args[2].equalsIgnoreCase("center")) {
            if (!(sender instanceof Player player)) {
                MessageUtil.sendConfig(sender, "player-only", Map.of());
                return;
            }
            String name = args[1];
            int radius = parseInt(args[3], 50);
            if (plugin.getArenaManager().setCenter(name, player.getLocation(), radius)) {
                MessageUtil.sendConfig(sender, "arena-center-set", Map.of(
                        "name", name,
                        "radius", String.valueOf(radius)
                ));
            } else {
                MessageUtil.sendConfig(sender, "arena-not-found", Map.of());
            }
            return;
        }

        if (args.length >= 4 && args[2].equalsIgnoreCase("type")) {
            String name = args[1];
            ArenaType type = ArenaType.from(args[3]);
            if ("newbie".equalsIgnoreCase(name) && type != ArenaType.NOMACE) {
                MessageUtil.send(sender, "&cUnder 6h / newbie arena is always nomace.");
                return;
            }
            Arena existing = plugin.getArenaManager().getArena(name);
            if (existing == null) {
                MessageUtil.sendConfig(sender, "arena-not-found", Map.of());
                return;
            }
            if (type == ArenaType.NOMACE && plugin.getArenaManager().wouldExceedNomaceLimit(existing)) {
                MessageUtil.sendConfig(sender, "arena-nomace-limit", Map.of(
                        "max", String.valueOf(plugin.getArenaManager().getMaxNomaceArenas()),
                        "count", String.valueOf(plugin.getArenaManager().countNomaceArenas())
                ));
                return;
            }
            if (plugin.getArenaManager().setArenaType(name, type)) {
                MessageUtil.sendConfig(sender, "arena-type-set", Map.of(
                        "name", name,
                        "type", type.name().toLowerCase()
                ));
            } else {
                MessageUtil.sendConfig(sender, "arena-not-found", Map.of());
            }
            return;
        }

        MessageUtil.send(sender, "&cUnknown arena subcommand.");
    }

    private void handleEconomy(CommandSender sender, String[] args) {

        if (args.length < 3) {
            MessageUtil.send(sender, "&e/unstablecore economy give <amount> <user>");
            MessageUtil.send(sender, "&e/unstablecore economy take <amount> <user>");
            MessageUtil.send(sender, "&e/unstablecore economy remove <amount> <user>");
            MessageUtil.send(sender, "&e/unstablecore economy set <amount> <user>");
            MessageUtil.send(sender, "&e/unstablecore economy reset <user>");
            return;
        }

        if (!plugin.getEconomyManager().isReady()) {
            MessageUtil.send(sender, "&cVault economy is not available.");
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("reset")) {
            // Resolved via resolvePlayer (known UUID: online, leaderboard cache, or a real prior
            // join) rather than Bukkit.getOfflinePlayer(String), which fabricates/looks up a UUID
            // for any name - including one a griefer just renamed into after the real target
            // renamed away, silently redirecting the command to the wrong account.
            OfflinePlayer target = plugin.getStatsManager().resolvePlayer(args[2]);
            if (target == null) {
                MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                return;
            }
            plugin.getEconomyManager().reset(target);
            MessageUtil.sendConfig(sender, "economy-reset", Map.of("player", args[2]));
            return;
        }

        if (args.length < 4) {
            MessageUtil.send(sender, "&cUsage: /unstablecore economy <give|take|remove|set> <amount> <user>");
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            MessageUtil.send(sender, "&cInvalid amount.");
            return;
        }
        OfflinePlayer target = plugin.getStatsManager().resolvePlayer(args[3]);
        if (target == null) {
            MessageUtil.sendConfig(sender, "player-not-found", Map.of());
            return;
        }
        String amountStr = EconomyManager.format(amount);

        switch (action) {
            case "give" -> {
                plugin.getEconomyManager().deposit(target, amount);
                MessageUtil.sendConfig(sender, "economy-give", Map.of("amount", amountStr, "player", args[3]));
            }
            case "take", "remove" -> {
                plugin.getEconomyManager().withdraw(target, amount);
                MessageUtil.sendConfig(sender, "economy-take", Map.of("amount", amountStr, "player", args[3]));
            }
            case "set" -> {
                plugin.getEconomyManager().set(target, amount);
                MessageUtil.sendConfig(sender, "economy-set", Map.of("amount", amountStr, "player", args[3]));
            }
            default -> MessageUtil.send(sender, "&cUnknown economy action.");
        }
    }

    private void handleEvent(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.send(sender, "&e/uc event start <multiplier> <seconds>");
            MessageUtil.send(sender, "&e/uc event stop");
            MessageUtil.send(sender, "&e/uc event streak start <multiplier> <seconds>");
            MessageUtil.send(sender, "&e/uc event streak stop");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("streak")) {
            handleStreakEvent(sender, args);
            return;
        }
        if (sub.equals("stop")) {
            if (!plugin.getEventManager().isCoinActive()) {
                MessageUtil.sendConfig(sender, "event-none", Map.of());
                return;
            }
            plugin.getEventManager().stopCoinEvent(true);
            MessageUtil.sendConfig(sender, "event-stopped", Map.of());
            return;
        }
        if (sub.equals("start")) {
            if (args.length < 4) {
                MessageUtil.send(sender, "&cUsage: /uc event start <multiplier> <seconds>");
                return;
            }
            double multi;
            int seconds;
            try {
                multi = Double.parseDouble(args[2]);
                seconds = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                MessageUtil.send(sender, "&cInvalid number.");
                return;
            }
            plugin.getEventManager().startCoinEvent(multi, seconds, false);
            MessageUtil.sendConfig(sender, "event-started", Map.of(
                    "multiplier", String.valueOf(multi),
                    "seconds", String.valueOf(seconds)
            ));
            return;
        }
        MessageUtil.send(sender, "&cUnknown event subcommand.");
    }

    private void handleStreakEvent(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageUtil.send(sender, "&e/uc event streak start <multiplier> <seconds>");
            MessageUtil.send(sender, "&e/uc event streak stop");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("stop")) {
            if (!plugin.getEventManager().isStreakActive()) {
                MessageUtil.send(sender, "&cNo active streak event.");
                return;
            }
            plugin.getEventManager().stopStreakEvent(true);
            MessageUtil.send(sender, "&aStreak event stopped.");
            return;
        }
        if (action.equals("start")) {
            if (args.length < 5) {
                MessageUtil.send(sender, "&cUsage: /uc event streak start <multiplier> <seconds>");
                return;
            }
            double multi;
            int seconds;
            try {
                multi = Double.parseDouble(args[3]);
                seconds = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                MessageUtil.send(sender, "&cInvalid number.");
                return;
            }
            plugin.getEventManager().startStreakEvent(multi, seconds, false);
            MessageUtil.send(sender, "&aStarted &f" + multi + "x &astreak event for &f" + seconds + "s&a.");
            return;
        }
        MessageUtil.send(sender, "&cUnknown streak event subcommand.");
    }

    private void handleLoadout(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            MessageUtil.send(sender, "&e/uc loadout reset <player> &7- clear loadout cooldown");
            return;
        }
        // Resolved via resolvePlayer rather than Bukkit.getOfflinePlayer(String) - see the same
        // fix in handleEconomy for why a raw name lookup can land on the wrong UUID.
        OfflinePlayer off = plugin.getStatsManager().resolvePlayer(args[2]);
        if (off == null) {
            MessageUtil.sendConfig(sender, "player-not-found", Map.of());
            return;
        }
        java.util.UUID uuid = off.getUniqueId();
        String name = off.getName() != null ? off.getName() : args[2];
        plugin.getLoadoutManager().resetCooldown(uuid);
        MessageUtil.sendConfig(sender, "loadout-cooldown-reset", Map.of("player", name));
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&d&lUnstableCore &7commands:");
        MessageUtil.send(sender, "&e/unstablecore arena ...");
        MessageUtil.send(sender, "&e/unstablecore economy ...");
        MessageUtil.send(sender, "&e/unstablecore event ...");
        MessageUtil.send(sender, "&e/uc mine bypass [on|off] &7- break natural arena blocks");
        MessageUtil.send(sender, "&e/uc setspawn &7- set join spawn to your location");
        MessageUtil.send(sender, "&e/uc shop reset &7- restore default shop.yml");
        MessageUtil.send(sender, "&e/uc vote start &7- force start map vote");
        MessageUtil.send(sender, "&e/uc loadout reset <player> &7- clear loadout cooldown");
        MessageUtil.send(sender, "&e/unstablecore reload");
        MessageUtil.send(sender, "&e/arenas &7- open arena GUI");
        MessageUtil.send(sender, "&e/kits &7| &e/kit &7- kit browser / admin");
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("arena", "economy", "event", "mine", "shop", "vote", "setspawn", "loadout", "reload"), args[0]);
        }
        if (args[0].equalsIgnoreCase("loadout")) {
            if (args.length == 2) {
                return filter(List.of("reset"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("reset")) {
                return null;
            }
        }
        if (args[0].equalsIgnoreCase("shop") && args.length == 2) {
            return filter(List.of("reset"), args[1]);
        }
        if (args[0].equalsIgnoreCase("vote") && args.length == 2) {
            return filter(List.of("start"), args[1]);
        }
        if (args[0].equalsIgnoreCase("mine")) {
            if (args.length == 2) {
                return filter(List.of("bypass"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("bypass")) {
                return filter(List.of("on", "off"), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length == 2) {
                List<String> opts = new ArrayList<>(List.of("create", "delete", "rotate", "list", "setnewbie", "permanent"));
                opts.addAll(plugin.getArenaManager().getArenas().keySet());
                return filter(opts, args[1]);
            }
            if (args.length == 3 && !List.of("create", "delete", "rotate", "list", "setnewbie", "permanent")
                    .contains(args[1].toLowerCase())) {
                return filter(List.of("center", "type"), args[2]);
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("type")) {
                return filter(List.of("mace", "nomace"), args[3]);
            }
            if (args.length == 5 && args[1].equalsIgnoreCase("create")) {
                return filter(List.of("mace", "nomace"), args[4]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("permanent")) {
                return filter(List.of("true", "false"), args[3]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                return filter(new ArrayList<>(plugin.getArenaManager().getArenas().keySet()), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("permanent")) {
                return filter(new ArrayList<>(plugin.getArenaManager().getArenas().keySet()), args[2]);
            }
        }
        if (args[0].equalsIgnoreCase("economy") && args.length == 2) {
            return filter(List.of("give", "take", "remove", "set", "reset"), args[1]);
        }
        if (args[0].equalsIgnoreCase("economy") && args.length == 4) {
            return null;
        }
        if (args[0].equalsIgnoreCase("event") && args.length == 2) {
            return filter(List.of("start", "stop", "streak"), args[1]);
        }
        if (args[0].equalsIgnoreCase("event") && args.length == 3 && args[1].equalsIgnoreCase("streak")) {
            return filter(List.of("start", "stop"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
