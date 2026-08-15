package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.SpectatorManager;
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

/** `/spec <player>` - watch an active duel without affecting it. See SpectatorManager. */
public final class SpectateCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public SpectateCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }
        if (!player.hasPermission("unstablecore.duel.spectate")) {
            MessageUtil.sendConfig(sender, "no-permission", Map.of());
            return true;
        }
        SpectatorManager spec = plugin.getSpectatorManager();
        if (spec == null || !plugin.getConfigManager().getDuels().getBoolean("spectate.enabled", true)) {
            msg(player, "spectate-disabled", Map.of());
            return true;
        }

        if (args.length == 0) {
            if (spec.isSpectating(player.getUniqueId())) {
                spec.stopSpectating(player, true);
            } else {
                MessageUtil.send(player, "&cUsage: /spec <player>");
            }
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            MessageUtil.sendConfig(sender, "player-not-found", Map.of());
            return true;
        }
        spec.startSpectating(player, target);
        return true;
    }

    private void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = plugin.getConfigManager().getDuels().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        // Bukkit's getOnlinePlayers() returns vanished players regardless of visibility - without
        // filtering, a regular player could type /spec <partial> to enumerate the real online name
        // of a staff member hidden from them (see StatsCommand.onTabComplete for the same fix).
        Player viewer = sender instanceof Player pl ? pl : null;
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if ((viewer == null || viewer.canSee(online)) && !online.hasMetadata("vanished")
                    && online.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(online.getName());
            }
        }
        return out;
    }
}
