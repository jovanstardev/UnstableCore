package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.KitsGui;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.manager.SettingsManager;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlayerListener implements Listener {

    private final UnstableCore plugin;

    public PlayerListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();

        if (plugin.getConfig().getBoolean("join-leave.enabled", true)) {
            String msg = plugin.getConfig().getString("join-leave.join", "&a✓ &f{player}");
            MessageUtil.broadcastFiltered(
                    MessageUtil.apply(msg, Map.of("player", player.getName())),
                    plugin.getSettingsManager().filter(SettingsManager.JOIN_LEAVE)
            );
        }

        if (plugin.getConfig().getBoolean("join.spawn-on-join", true)) {
            Location spawn = resolveJoinSpawn();
            if (spawn != null) {
                player.teleportAsync(spawn);
                plugin.getArenaManager().clearPlayer(player.getUniqueId());
                if (plugin.getArenaListener() != null) {
                    plugin.getArenaListener().clearPlayer(player.getUniqueId());
                }
            } else {
                plugin.getLogger().warning("join.spawn world is missing - use /uc setspawn");
            }
        }

        if (plugin.getConfig().getBoolean("join.title.enabled", true)) {
            String titleRaw = plugin.getConfig().getString("join.title.title", "");
            final String title = (titleRaw == null || titleRaw.isBlank())
                    ? plugin.getConfig().getString("prefix", "<#FF9C59>&lUUFFA")
                    : titleRaw;
            final String subtitle = plugin.getConfig().getString("join.title.subtitle", "&7Welcome back");
            final int seconds = plugin.getConfig().getInt("join.title.seconds", 3);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    MessageUtil.title(player, title, subtitle, seconds);
                }
            }, 10L);
        }

        if (plugin.getEventManager().isCoinActive() || plugin.getEventManager().isStreakActive()) {
            plugin.getEventManager().showBossBar(player);
        }
        if (plugin.getMapVoteManager() != null
                && plugin.getMapVoteManager().isVoting()
                && plugin.getConfig().getBoolean("arena.vote.reopen-on-join", true)) {
            plugin.getMapVoteManager().openFor(player);
        }
        if (plugin.getRewardsManager() != null) {
            plugin.getRewardsManager().handleJoin(player);
        }
        if (plugin.getDuelManager() != null) {
            plugin.getDuelManager().applyPendingCrashRestore(player);
        }
        // A player who disconnected mid-/spec (rare, but possible) has GameMode.SPECTATOR saved
        // in their player data - SpectatorManager can't restore it while they're offline, so
        // undo it here instead of leaving them permanently stuck in spectator mode. Nothing else
        // in this plugin ever expects a player to legitimately rejoin already in spectator mode.
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        if (plugin.getKitManager() != null) {
            plugin.getKitManager().ensureDefaultKit(player);
        }
        if (plugin.getLeaderboardManager() != null) {
            plugin.getLeaderboardManager().syncProfile(player);
        }
    }

    private Location resolveJoinSpawn() {
        String worldName = plugin.getConfig().getString("join.spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = plugin.getConfig().getDouble("join.spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("join.spawn.y", 64.0);
        double z = plugin.getConfig().getDouble("join.spawn.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("join.spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("join.spawn.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        if (plugin.getConfig().getBoolean("join-leave.enabled", true)) {
            String msg = plugin.getConfig().getString("join-leave.leave", "&c✗ &f{player}");
            MessageUtil.broadcastFiltered(
                    MessageUtil.apply(msg, Map.of("player", event.getPlayer().getName())),
                    plugin.getSettingsManager().filter(SettingsManager.JOIN_LEAVE)
            );
        }

        plugin.getAfkZoneManager().clear(event.getPlayer());
        plugin.getArenaManager().clearPlayer(event.getPlayer().getUniqueId());
        if (plugin.getArenaListener() != null) {
            plugin.getArenaListener().clearPlayer(event.getPlayer().getUniqueId());
        }
        if (plugin.getDuelManager() != null) {
            // Must run before handleQuitCombatTag - a duel disconnect is a forfeit routed to
            // duel payout, not an FFA combat-log kill credit (CombatListener itself also checks
            // isInDuel and no-ops, this ordering just makes the intent explicit).
            plugin.getDuelManager().handleDisconnect(event.getPlayer());
        }
        if (plugin.getCombatListener() != null) {
            plugin.getCombatListener().handleQuitCombatTag(event.getPlayer());
            plugin.getCombatListener().clearPlayer(event.getPlayer().getUniqueId());
        }
        if (plugin.getSpectatorManager() != null) {
            plugin.getSpectatorManager().handleDisconnect(event.getPlayer());
        }
        if (plugin.getDuelScoreboardManager() != null) {
            plugin.getDuelScoreboardManager().handleDisconnect(event.getPlayer());
        }
        if (plugin.getRewardsManager() != null) {
            plugin.getRewardsManager().unload(event.getPlayer().getUniqueId());
        }
        if (plugin.getTagManager() != null) {
            plugin.getTagManager().clearCooldown(event.getPlayer().getUniqueId());
        }
        if (plugin.getBountyManager() != null) {
            plugin.getBountyManager().clearPrompt(event.getPlayer().getUniqueId());
        }
        if (plugin.getLeaderboardManager() != null) {
            plugin.getLeaderboardManager().syncProfile(event.getPlayer());
            plugin.getLeaderboardManager().clearSearch(event.getPlayer().getUniqueId());
        }
        if (plugin.getActionBarManager() != null) {
            plugin.getActionBarManager().invalidate(event.getPlayer().getUniqueId());
        }

        // Stats/settings/combat are otherwise only persisted on a 5-minute timer or clean
        // shutdown - an unexpected crash/kill in between would lose whatever changed for every
        // online player in that window. Save just this player's row now so a disconnect (the
        // most common way a player's session actually ends) can't lose their own data.
        UUID quitUuid = event.getPlayer().getUniqueId();
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().save(quitUuid);
        }
        if (plugin.getSettingsManager() != null) {
            plugin.getSettingsManager().save(quitUuid);
        }
        if (plugin.getKillstreakManager() != null) {
            plugin.getKillstreakManager().save(quitUuid);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.getAfkZoneManager().clear(event.getPlayer());
    }

    /**
     * Re-gears a player on respawn, honouring the normal loadout cooldown.
     *
     * <p>Deliberately explicit rather than a side effect of the duel respawn hook: it fires only
     * when the player has nothing, so it can never delete items (including on keepInventory
     * setups), it routes through {@code LoadoutManager#tryGive} so the cooldown is respected and
     * consumed, and {@code loadout.give-on-respawn} switches it off entirely.
     *
     * <p>Runs at MONITOR, one tick later, so DuelListener's duel-respawn restore - which returns a
     * real pre-duel inventory and must win - has already been applied.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("loadout.give-on-respawn", true)) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (plugin.getDuelManager() != null
                    && (plugin.getDuelManager().isInDuel(player.getUniqueId())
                    || plugin.getDuelManager().isInGrace(player.getUniqueId()))) {
                return;
            }
            if (plugin.getLoadoutManager() == null || !KitManager.isInventoryEmpty(player)) {
                return;
            }
            plugin.getLoadoutManager().tryGive(player, false);
        });
    }

    /**
     * Catches near-misses of /kit and /kits (typos, missing/extra letter) and opens the kits
     * menu instead of letting the server say "unknown command". Exact matches for "kit"/"kits"
     * are left alone since those already resolve to their real commands, and anything that
     * matches an already-registered command label is never touched so we can't hijack it.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        int spaceIdx = message.indexOf(' ');
        String label = (spaceIdx == -1 ? message.substring(1) : message.substring(1, spaceIdx))
                .toLowerCase(Locale.ROOT);
        if (label.length() < 2 || label.length() > 6 || label.charAt(0) != 'k' || label.equals("kit") || label.equals("kits")) {
            return;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < 'a' || c > 'z') {
                return;
            }
        }
        // getPluginCommand only resolves commands declared in a plugin.yml, so a label registered
        // directly into the command map (Brigadier/Paper command API, vanilla commands, aliases
        // registered at runtime) was invisible here and would be silently swallowed and turned
        // into a kits menu. Ask the real command map instead, which knows about all of them.
        if (Bukkit.getCommandMap().getCommand(label) != null) {
            return;
        }
        if (levenshtein(label, "kit") <= 1 || levenshtein(label, "kits") <= 1) {
            event.setCancelled(true);
            KitsGui.open(plugin, event.getPlayer());
        }
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
