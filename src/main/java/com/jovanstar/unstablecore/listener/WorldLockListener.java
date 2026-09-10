package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps players inside the worlds the server is actually about: the join spawn world and every
 * arena world. Anything else - the vanilla overworld, nether, end, a build world - is off limits
 * unless the player holds the bypass permission.
 *
 * <p>Three layers, so nothing slips through:
 * <ol>
 *   <li>Teleports and portals into a disallowed world are cancelled before they happen.</li>
 *   <li>A player who nonetheless arrives in one (a plugin that skips the events, a world that
 *       loaded late) is sent back to spawn on the next tick.</li>
 *   <li>A slow sweep catches anyone the first two missed.</li>
 * </ol>
 *
 * <p>The void gets the same treatment: falling below the world floor teleports to spawn instead
 * of killing, so a knock-off in an arena is a setback rather than a death.
 */
public final class WorldLockListener implements Listener {

    private static final long VOID_COOLDOWN_MS = 1500L;

    private final UnstableCore plugin;
    private final Map<UUID, Long> lastVoidRescue = new ConcurrentHashMap<>();

    /**
     * Cached because {@link #onMove} runs for every movement packet of every player - about twenty
     * a second each - and every read of these two went through the config's string-path lookup.
     * Refreshed by {@link #reloadSettings()} on /uc reload, so behaviour is unchanged.
     */
    private boolean voidEnabled;
    private int voidDepth;

    public WorldLockListener(UnstableCore plugin) {
        this.plugin = plugin;
        reloadSettings();
        long sweep = 20L * Math.max(2, plugin.getConfig().getInt("world-lock.sweep-seconds", 5));
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, sweep, sweep);
    }

    public void reloadSettings() {
        voidEnabled = plugin.getConfig().getBoolean("world-lock.void.enabled", true);
        voidDepth = plugin.getConfig().getInt("world-lock.void.depth", 4);
    }

    /**
     * Off unless a join spawn exists: with no spawn there is nowhere to send anyone, and an
     * empty allow-list would otherwise refuse every teleport on the server.
     */
    private boolean enabled() {
        return plugin.getConfig().getBoolean("world-lock.enabled", true) && spawn() != null;
    }

    private boolean bypasses(Player player) {
        String node = plugin.getConfig().getString("world-lock.bypass-permission", "unstablecore.worldlock.bypass");
        return node != null && !node.isBlank() && player.hasPermission(node);
    }

    /** The spawn world, every arena world, and whatever the config adds on top. */
    private Set<String> allowedWorlds() {
        Set<String> allowed = new HashSet<>();
        String spawnWorld = plugin.getConfig().getString("join.spawn.world");
        if (spawnWorld != null && !spawnWorld.isBlank()) {
            allowed.add(spawnWorld.toLowerCase(Locale.ROOT));
        }
        if (plugin.getArenaManager() != null) {
            for (Arena arena : plugin.getArenaManager().getArenas().values()) {
                if (arena.getWorldName() != null && !arena.getWorldName().isBlank()) {
                    allowed.add(arena.getWorldName().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String extra : plugin.getConfig().getStringList("world-lock.extra-allowed-worlds")) {
            if (extra != null && !extra.isBlank()) {
                allowed.add(extra.toLowerCase(Locale.ROOT));
            }
        }
        return allowed;
    }

    private boolean isAllowed(World world) {
        return world != null && allowedWorlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** The join spawn from config, or null if it has not been set (then nothing is enforced). */
    private Location spawn() {
        String worldName = plugin.getConfig().getString("join.spawn.world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world,
                plugin.getConfig().getDouble("join.spawn.x"),
                plugin.getConfig().getDouble("join.spawn.y"),
                plugin.getConfig().getDouble("join.spawn.z"),
                (float) plugin.getConfig().getDouble("join.spawn.yaw"),
                (float) plugin.getConfig().getDouble("join.spawn.pitch"));
    }

    private void sendHome(Player player, String messageKey) {
        Location spawn = spawn();
        if (spawn == null) {
            plugin.getLogger().warning("world-lock: join.spawn is not set, cannot send " + player.getName() + " back - use /uc setspawn");
            return;
        }
        player.setFallDistance(0f);
        player.setVelocity(player.getVelocity().zero());
        player.teleport(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        String message = plugin.getConfig().getString(messageKey, defaultMessage(messageKey));
        if (message != null && !message.isBlank()) {
            MessageUtil.sendPrefixed(player, message);
        }
    }

    private static String defaultMessage(String key) {
        return key.endsWith("void-message")
                ? "&7You fell into the void and were sent back to spawn."
                : "&cYou can't go there.";
    }

    // --- layer 1: refuse the trip --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!enabled() || event.getTo() == null || isAllowed(event.getTo().getWorld()) || bypasses(event.getPlayer())) {
            return;
        }
        // Our own rescues always target spawn, which is allowed, so this never cancels them.
        event.setCancelled(true);
        String message = plugin.getConfig().getString("world-lock.message", defaultMessage("world-lock.message"));
        if (message != null && !message.isBlank()) {
            MessageUtil.sendPrefixed(event.getPlayer(), message);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!enabled() || !plugin.getConfig().getBoolean("world-lock.block-portals", true) || bypasses(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    // --- layer 2: got there anyway ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!enabled() || isAllowed(player.getWorld()) || bypasses(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !isAllowed(player.getWorld()) && !bypasses(player)) {
                sendHome(player, "world-lock.message");
            }
        });
    }

    // --- layer 3: the sweep -----------------------------------------------------------------------

    private void sweep() {
        if (!enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isAllowed(player.getWorld()) && !bypasses(player)) {
                sendHome(player, "world-lock.message");
            }
        }
    }

    // --- the void ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!voidEnabled) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (to.getY() >= to.getWorld().getMinHeight() - voidDepth) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isDead()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastVoidRescue.get(player.getUniqueId());
        if (last != null && now - last < VOID_COOLDOWN_MS) {
            return;
        }
        lastVoidRescue.put(player.getUniqueId(), now);
        sendHome(player, "world-lock.void-message");
    }

    /** With the rescue on, the void must not get the kill in first. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID || !(event.getEntity() instanceof Player)) {
            return;
        }
        if (voidEnabled) {
            event.setCancelled(true);
        }
    }

    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            lastVoidRescue.remove(uuid);
        }
    }
}
