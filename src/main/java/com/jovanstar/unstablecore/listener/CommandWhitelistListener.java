package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;

/**
 * Restricts non-op players to the command whitelist in allowed_cmds.yml - anything not listed
 * there, including other plugins' commands and vanilla commands, is blocked before it runs.
 * Runs at LOWEST so a blocked command short-circuits before any other command-preprocess
 * listener (e.g. the kit-typo catcher in PlayerListener) does work on it.
 */
public final class CommandWhitelistListener implements Listener {

    private final UnstableCore plugin;

    public CommandWhitelistListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().getAllowedCmds().getBoolean("enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        String bypassPermission = plugin.getConfigManager().getAllowedCmds()
                .getString("bypass-permission", "unstablecore.admin");
        if (bypassPermission != null && !bypassPermission.isBlank() && player.hasPermission(bypassPermission)) {
            return;
        }

        String message = event.getMessage();
        int spaceIdx = message.indexOf(' ');
        String label = (spaceIdx == -1 ? message.substring(1) : message.substring(1, spaceIdx))
                .toLowerCase(Locale.ROOT);
        // "/minecraft:help" and "/uc:kit" resolve to the same command as "/help"/"/kit" - strip
        // a namespace prefix so it can't be used to slip past the plain-label whitelist below.
        int colonIdx = label.indexOf(':');
        if (colonIdx != -1 && colonIdx + 1 < label.length()) {
            label = label.substring(colonIdx + 1);
        }

        List<String> allowed = plugin.getConfigManager().getAllowedCmds().getStringList("commands");
        for (String a : allowed) {
            if (a != null && a.equalsIgnoreCase(label)) {
                return;
            }
        }

        event.setCancelled(true);
        MessageUtil.send(player, plugin.getConfigManager().getAllowedCmds()
                .getString("blocked-message", "&cThat command isn't allowed on this server."));
    }
}
