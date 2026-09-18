package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps players inside a buffer zone short of the world border.
 *
 * <p>Vanilla only reacts once a player is already touching the border - it nudges them back and
 * starts applying border damage - which leaves the edge itself usable as a place to fight from and
 * as the spot clip cheats aim for. This pushes players back while they are still a configurable
 * number of blocks clear of the edge, so in normal play the border is never reached at all.
 */
public final class WorldBorderListener implements Listener {

    private static final long WARN_COOLDOWN_MS = 1500L;
    /** Also used by AntiGlitchListener's pearl/chorus-fruit border-buffer check. */
    public static final String BYPASS_PERMISSION = "unstablecore.border.bypass";

    private final UnstableCore plugin;
    private final Map<UUID, Long> warnCooldown = new ConcurrentHashMap<>();

    private boolean enabled;
    private double distance;
    private double pushStrength;
    private double pushUp;

    public WorldBorderListener(UnstableCore plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("world-border.push-back.enabled", true);
        distance = Math.max(0.0, plugin.getConfig().getDouble("world-border.push-back.distance", 5.0));
        pushStrength = plugin.getConfig().getDouble("world-border.push-back.push-strength", 0.55);
        pushUp = plugin.getConfig().getDouble("world-border.push-back.push-up", 0.12);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getDistance() {
        return distance;
    }

    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            warnCooldown.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!enabled) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        // The border is a vertical prism, so only a horizontal block change can alter the gap.
        // Falling, jumping and looking around leave it identical and must not cost a check - this
        // runs for every player on every movement packet.
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        pushBackIfNearEdge(event.getPlayer(), to);
    }

    private void pushBackIfNearEdge(Player player, Location to) {
        // Spectators are meant to pass through everything, and staff with the bypass node are
        // usually the ones setting the border up in the first place.
        if (player.getGameMode() == GameMode.SPECTATOR || player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        double[] gaps = bufferGaps(to, distance);
        if (gaps == null) {
            return;
        }
        double dx = gaps[2];
        double dz = gaps[3];
        double gapX = gaps[0];
        double gapZ = gaps[1];
        if (gapX > distance && gapZ > distance) {
            return;
        }

        // Push inward only along the axis actually close to a wall, so a player near the north
        // edge is shoved south rather than also being dragged sideways along a wall they were
        // nowhere near. In a corner both axes engage and the push comes out diagonal.
        Vector push = new Vector(0, 0, 0);
        if (gapX <= distance) {
            push.setX(dx >= 0 ? -1 : 1);
        }
        if (gapZ <= distance) {
            push.setZ(dz >= 0 ? -1 : 1);
        }
        // Unreachable while the gap check above holds, but normalize() on a zero vector yields NaN
        // and a NaN velocity wedges the player's movement - too costly to risk on a later edit.
        if (push.lengthSquared() < 0.0001) {
            return;
        }
        push.normalize().multiply(pushStrength).setY(pushUp);
        player.setVelocity(push);
        warn(player);
    }

    /**
     * Whether a point falls inside the buffer zone of its world's border - including already past
     * it. Shared by the walk-based push above and {@code AntiGlitchListener}'s ender-pearl/chorus
     * fruit landing check, so a pearl thrown into the buffer is refused by the exact same geometry
     * that would have pushed a player walking into the same spot, instead of a second
     * hand-maintained copy silently drifting out of sync with this one.
     */
    public static boolean isWithinBorderBuffer(Location loc, double distance) {
        double[] gaps = bufferGaps(loc, distance);
        return gaps != null && (gaps[0] <= distance || gaps[1] <= distance);
    }

    /**
     * @return {gapX, gapZ, dx, dz} - the horizontal gap to the nearest wall on each axis (negative
     * once past it) and the raw offset from center each gap was derived from - or {@code null} if
     * the location has no world or its border is too narrow for a buffer to mean anything.
     */
    private static double[] bufferGaps(Location loc, double distance) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        WorldBorder border = loc.getWorld().getWorldBorder();
        if (border == null) {
            return null;
        }
        double half = border.getSize() / 2.0;
        // A border narrower than two buffer zones has no interior left to stand in: every point
        // would count as too close to an edge and the push would rattle the player between the two
        // sides forever. Leave those worlds to vanilla.
        if (half <= distance) {
            return null;
        }
        Location center = border.getCenter();
        double dx = loc.getX() - center.getX();
        double dz = loc.getZ() - center.getZ();
        return new double[] {half - Math.abs(dx), half - Math.abs(dz), dx, dz};
    }

    private void warn(Player player) {
        long now = System.currentTimeMillis();
        Long last = warnCooldown.get(player.getUniqueId());
        if (last != null && now - last < WARN_COOLDOWN_MS) {
            return;
        }
        warnCooldown.put(player.getUniqueId(), now);
        // Blank is a deliberate "push silently" setting, not a missing value to substitute for.
        String msg = plugin.getConfig().getString(
                "messages.world-border-push",
                "&c&l(!) &r&cYou can't go past the world border."
        );
        if (msg == null || msg.isBlank()) {
            return;
        }
        MessageUtil.send(player, msg);
    }
}
