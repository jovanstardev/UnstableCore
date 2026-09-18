package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.KitsGui;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.manager.SettingsManager;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerListener implements Listener {

    /** Stable id for the announcer pack, so re-sending replaces it rather than stacking copies. */
    private static final UUID ANNOUNCER_PACK_ID = UUID.fromString("a9d4f1c2-0e77-4b3a-9c61-5f2e8b0d7a44");

    /**
     * Last time each player was told off for a blocked spawn drop. Holding ctrl+Q fires the drop
     * handlers many times per second, so the reminder is rate limited to keep chat readable.
     */
    private final Map<UUID, Long> lastDropWarning = new ConcurrentHashMap<>();

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

        // A pending server-wide ender chest wipe is applied on the way in, before the player can
        // open anything. Offline players cannot be reached any other way - see EnderChestManager.
        if (plugin.getEnderChestManager() != null
                && plugin.getEnderChestManager().applyPendingWipe(player)) {
            MessageUtil.send(player, plugin.getConfig().getString(
                    "messages.echest-wiped", "&c&l(!) &r&cYour ender chest was cleared by a server-wide reset."));
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
        if (plugin.getRewardsManager() != null) {
            plugin.getRewardsManager().handleJoin(player);
        }
        // A player who disconnected mid-spectate (rare, but possible) has GameMode.SPECTATOR
        // saved in their player data and nothing to restore it while they're offline, so undo it
        // here instead of leaving them permanently stuck in spectator mode. Nothing else in this
        // plugin ever expects a player to legitimately rejoin already in spectator mode.
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        if (plugin.getKitManager() != null) {
            plugin.getKitManager().ensureDefaultKit(player);
        }
        if (plugin.getLeaderboardManager() != null) {
            plugin.getLeaderboardManager().syncProfile(player);
        }
        sendAnnouncerPack(player);
    }

    /**
     * Sends the announcer resource pack as an <em>additional</em> pack.
     *
     * <p>Deliberately not {@code server.properties}: that field holds a single pack, and on a
     * server where ItemsAdder or SkinVault already serves one, setting it there means one pack
     * replaces the other and something loses its assets. The UUID overload stacks instead, so the
     * voice lines layer on top of whatever else is already applied.
     *
     * <p>No-op when the url is blank, so servers that would rather serve the pack through
     * server.properties (or not at all) just leave it empty.
     */
    private void sendAnnouncerPack(Player player) {
        String url = plugin.getConfig().getString("killstreak.announcer.resource-pack.url", "");
        if (url == null || url.isBlank()) {
            return;
        }
        byte[] hash = parseSha1(plugin.getConfig().getString("killstreak.announcer.resource-pack.sha1", ""));
        if (hash == null) {
            plugin.getLogger().warning("killstreak.announcer.resource-pack.sha1 must be 40 hex "
                    + "characters matching the uploaded zip - announcer pack not sent.");
            return;
        }
        String prompt = plugin.getConfig().getString("killstreak.announcer.resource-pack.prompt", "");
        boolean required = plugin.getConfig().getBoolean("killstreak.announcer.resource-pack.required", false);
        try {
            player.setResourcePack(ANNOUNCER_PACK_ID, url, hash,
                    prompt == null || prompt.isBlank() ? null : MessageUtil.parse(prompt), required);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send the announcer resource pack: " + e.getMessage());
        }
    }

    /** 40 hex characters -> the 20-byte digest the client verifies the download against. */
    private static byte[] parseSha1(String hex) {
        if (hex == null) {
            return null;
        }
        String clean = hex.trim();
        if (clean.length() != 40) {
            return null;
        }
        byte[] out = new byte[20];
        for (int i = 0; i < 20; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
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

    private boolean isInSpawnProtection(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Location spawn = resolveJoinSpawn();
        if (spawn == null || !spawn.getWorld().equals(loc.getWorld())) {
            return false;
        }
        double radius = plugin.getConfig().getDouble("join.spawn.protect-radius", 25.0);
        if (radius <= 0) {
            return false;
        }
        double dx = loc.getX() - spawn.getX();
        double dz = loc.getZ() - spawn.getZ();
        return (dx * dx) + (dz * dz) <= radius * radius;
    }

    /** Only OPs/admins may drop items within the spawn protection radius. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropAtSpawn(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("unstablecore.admin")) {
            return;
        }
        if (!isInSpawnProtection(player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        warnDropBlocked(player);
    }

    /**
     * Closes the inventory-screen route around {@link #onDropAtSpawn}.
     *
     * <p>{@code PlayerDropItemEvent} only covers throwing an item while no screen is open. Pressing Q
     * with the inventory (or any container) open, or clicking an item outside the window, travels
     * through {@link InventoryClickEvent} instead - so without this a player could drop anything at
     * spawn simply by opening their own inventory first. Runs with {@code ignoreCancelled} so the
     * plugin's own menus, which already cancel their clicks, neither double-fire nor warn.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDropAtSpawn(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryAction action = event.getAction();
        if (action != InventoryAction.DROP_ALL_SLOT
                && action != InventoryAction.DROP_ONE_SLOT
                && action != InventoryAction.DROP_ALL_CURSOR
                && action != InventoryAction.DROP_ONE_CURSOR) {
            return;
        }
        if (player.hasPermission("unstablecore.admin")) {
            return;
        }
        if (!isInSpawnProtection(player.getLocation())) {
            return;
        }
        event.setCancelled(true);
        warnDropBlocked(player);
    }

    /** Sends the "no dropping at spawn" reminder, at most once every two seconds per player. */
    private void warnDropBlocked(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastDropWarning.get(player.getUniqueId());
        if (last != null && now - last < 2000L) {
            return;
        }
        lastDropWarning.put(player.getUniqueId(), now);
        MessageUtil.send(player, plugin.getConfig().getString(
                "messages.spawn-drop-blocked", "&c&l(!) &r&cYou can't drop items at spawn."));
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

        lastDropWarning.remove(event.getPlayer().getUniqueId());
        plugin.getAfkZoneManager().clear(event.getPlayer());
        plugin.getArenaManager().clearPlayer(event.getPlayer().getUniqueId());
        if (plugin.getArenaListener() != null) {
            plugin.getArenaListener().clearPlayer(event.getPlayer().getUniqueId());
        }
        if (plugin.getWorldBorderListener() != null) {
            plugin.getWorldBorderListener().clearPlayer(event.getPlayer().getUniqueId());
        }
        if (plugin.getCombatListener() != null) {
            plugin.getCombatListener().handleQuitCombatTag(event.getPlayer());
            plugin.getCombatListener().clearPlayer(event.getPlayer().getUniqueId());
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
     * Re-gears a player on respawn. What they get is set by {@code loadout.respawn-kit}:
     *
     * <ul>
     *   <li>{@code default} - the free starter kit ({@code kits.default-kit}). Ignores the loadout
     *       cooldown and does not consume it, because this kit is free to claim anyway.</li>
     *   <li>{@code none} - nothing; the player respawns empty.</li>
     *   <li>{@code selected} - the player's own selected kit through
     *       {@link com.jovanstar.unstablecore.manager.LoadoutManager#tryGive}, cooldown-gated.</li>
     * </ul>
     *
     * <p>{@code selected} is not the default because a premium kit is exactly what the cooldown
     * exists to ration: once it lapses, dying hands the kit straight back, which makes death a
     * cheaper way to re-gear than waiting. Only ever fires on a completely empty inventory, so it
     * can never delete items (including on keepInventory setups), and
     * {@code loadout.give-on-respawn} still switches the whole thing off.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("loadout.give-on-respawn", true)) {
            return;
        }
        String configured = plugin.getConfig().getString("loadout.respawn-kit", "default");
        final String mode = configured == null ? "default" : configured.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("none")) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!KitManager.isInventoryEmpty(player)) {
                return;
            }
            if (mode.equals("selected")) {
                if (plugin.getLoadoutManager() != null) {
                    plugin.getLoadoutManager().tryGive(player, false);
                }
                return;
            }
            KitManager kits = plugin.getKitManager();
            Kit starter = kits == null ? null : kits.getDefaultKit();
            if (starter != null) {
                kits.applyKit(player, starter);
            }
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
