package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class AntiGlitchListener implements Listener {

    private final UnstableCore plugin;

    public AntiGlitchListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWindChargeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof AbstractWindCharge)) {
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        if (!isWindToggleable(type)) {
            return;
        }
        Arena arena = plugin.getArenaManager().resolveArenaAt(block.getLocation());
        if (arena == null) {
            return;
        }
        if (plugin.getArenaManager().isPlayerPlaced(block.getLocation())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemCactus(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.CONTACT) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (stack == null || !isPotionDrop(stack.getType())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        // A pearl outlives its thrower being moved to another map (rotation transfer, arena
        // join), and when it lands it drags them back to wherever it fell -
        // typically the just-rotated-out map, empty but for them. A pearl never legitimately
        // crosses worlds (the teleport only does when the *player* changed world mid-flight),
        // and one landing in an arena may only complete for a player still in that arena.
        Player player = event.getPlayer();
        if (!to.getWorld().equals(player.getWorld())) {
            event.setCancelled(true);
            MessageUtil.sendConfig(player, "pearl-stale", Map.of());
            return;
        }
        // The pearl entity flies straight through the world border - vanilla only constrains
        // player movement, not the entity or its landing teleport. Completing this teleport puts
        // the player outside the border: invisible to most of the server, effectively softlocked,
        // and free to act where nobody can reach them. Cancel instead of clamping - a clamped
        // landing on the border edge is exactly the "stand on the border" spot cheats want.
        if (!to.getWorld().getWorldBorder().isInside(to)) {
            event.setCancelled(true);
            MessageUtil.sendConfig(player, "pearl-border", Map.of());
            return;
        }

        // The walk-based border push-back (WorldBorderListener) only reacts to PlayerMoveEvent,
        // which a teleport never fires - so without this, pearling or chorus-fruiting straight
        // into the buffer zone landed cleanly and skipped the push-back entirely, letting a player
        // reach the wall in one throw instead of being shoved back on the walk in.
        WorldBorderListener borderListener = plugin.getWorldBorderListener();
        if (borderListener != null && borderListener.isEnabled()
                && !player.hasPermission(WorldBorderListener.BYPASS_PERMISSION)
                && WorldBorderListener.isWithinBorderBuffer(to, borderListener.getDistance())) {
            event.setCancelled(true);
            MessageUtil.sendConfig(player, "pearl-border-buffer", Map.of());
            return;
        }

        // Arena containment was previously just a build/break permission tag - nothing stopped
        // an ender pearl or chorus fruit from carrying a player straight through the boundary,
        // since both teleport clean through solid blocks instead of colliding with them the way
        // walking does. A player tracked as inside an arena can't teleport themselves past it.
        String trackedId = plugin.getArenaManager().getPlayerArena(player.getUniqueId());
        Arena trackedArena = trackedId == null ? null : plugin.getArenaManager().getArena(trackedId);
        if (trackedArena != null && !trackedArena.contains(to)) {
            event.setCancelled(true);
            String msg = plugin.getConfig().getString(
                    "messages.arena-boundary-blocked",
                    "&c&l(!) &r&cYou can't leave the arena boundary like that."
            );
            MessageUtil.send(player, msg == null || msg.isBlank()
                    ? "&c&l(!) &r&cYou can't leave the arena boundary like that." : msg);
            return;
        }

        if (!plugin.getArenaManager().hasArenasInWorld(to.getWorld().getName())) {
            return;
        }
        Arena arena = plugin.getArenaManager().resolveArenaAt(to);
        if (arena == null) {
            return;
        }
        String tracked = plugin.getArenaManager().getPlayerArena(player.getUniqueId());
        boolean trackedHere = tracked != null && tracked.equalsIgnoreCase(arena.getId());
        if (!trackedHere && !arena.contains(player.getLocation())) {
            event.setCancelled(true);
            MessageUtil.sendConfig(player, "pearl-stale", Map.of());
            return;
        }

        Location safe = findSafePearlLanding(to);
        if (safe == null) {
            event.setCancelled(true);
            return;
        }
        if (!sameBlock(to, safe)) {
            event.setTo(safe);
        }
    }

    private static Location findSafePearlLanding(Location to) {
        if (isSafePearlFeet(to)) {
            return centerFeet(to);
        }

        Block origin = to.getBlock();
        BlockFace[] faces = {
                BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN
        };
        for (BlockFace face : faces) {
            Location candidate = origin.getRelative(face).getLocation().add(0.5, 0, 0.5);
            candidate.setYaw(to.getYaw());
            candidate.setPitch(to.getPitch());
            if (isSafePearlFeet(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSafePearlFeet(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        Material feetType = feet.getType();
        if (feetType == Material.COBWEB
                || feetType.name().contains("PORTAL")
                || feetType == Material.END_PORTAL
                || feetType == Material.NETHER_PORTAL) {
            return false;
        }
        return true;
    }

    private static Location centerFeet(Location loc) {
        Location centered = loc.clone();
        centered.setX(centered.getBlockX() + 0.5);
        centered.setZ(centered.getBlockZ() + 0.5);
        return centered;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() != null
                && a.getWorld().equals(b.getWorld());
    }

    private static boolean isWindToggleable(Material type) {
        String name = type.name();
        return name.endsWith("TRAPDOOR")
                || (name.endsWith("_DOOR") && !name.endsWith("TRAPDOOR"))
                || name.endsWith("_FENCE_GATE");
    }

    private static boolean isPotionDrop(Material type) {
        return switch (type) {
            case POTION, SPLASH_POTION, LINGERING_POTION, GLASS_BOTTLE -> true;
            default -> false;
        };
    }
}
