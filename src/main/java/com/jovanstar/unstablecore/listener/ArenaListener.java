package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.ArenaType;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ArenaListener implements Listener {

    private static final long BREAK_MSG_COOLDOWN_MS = 1500L;

    private final UnstableCore plugin;
    private final EnumSet<Material> bushMaterials = EnumSet.noneOf(Material.class);
    private final Map<UUID, Long> breakMsgCooldown = new ConcurrentHashMap<>();
    private boolean antiBush;
    private double pushStrength;

    public ArenaListener(UnstableCore plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        bushMaterials.clear();
        antiBush = plugin.getConfig().getBoolean("anti-bush.enabled", true);
        pushStrength = plugin.getConfig().getDouble("anti-bush.push-strength", 0.45);
        List<String> mats = plugin.getConfig().getStringList("anti-bush.materials");
        for (String name : mats) {
            try {
                bushMaterials.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlaceLowest(BlockPlaceEvent event) {
        // Only handle the axe-strip force-allow here, before other protection plugins can
        // cancel the event. The full deny-check/marking logic below must run exactly once,
        // so it is left to onPlaceHighest - calling handlePlace(event) unconditionally from
        // both handlers double-sent the deny message and double-executed the arena marking.
        if (isAxeStripPlace(event)) {
            handlePlace(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlaceHighest(BlockPlaceEvent event) {
        handlePlace(event);
    }

    private void handlePlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();

        if (isAxeStripPlace(event)) {
            Arena arena = plugin.getArenaManager().resolveArenaAt(blockLoc);
            if (arena == null) {
                return;
            }
            event.setCancelled(false);
            if (plugin.getArenaManager().isPlayerPlaced(blockLoc)) {
                plugin.getArenaManager().setPlayerArena(player.getUniqueId(), arena.getId());
            }
            return;
        }

        if (plugin.getArenaManager().shouldDenyNomacePlace(player, blockLoc)) {
            event.setCancelled(true);
            sendPlaceDenied(player);
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        Arena arena = plugin.getArenaManager().resolveArenaAt(blockLoc);
        if (arena == null) {
            return;
        }

        if (arena.getType() == ArenaType.MACE) {
            plugin.getArenaManager().markPlaced(blockLoc);
            plugin.getArenaManager().setPlayerArena(player.getUniqueId(), arena.getId());
        }
    }

    private static boolean isAxeStripPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        if (hand == null || hand.getType().isAir() || !Tag.ITEMS_AXES.isTagged(hand.getType())) {
            return false;
        }
        Material from = event.getBlockReplacedState().getType();
        return isStrippableWood(from);
    }

    private static boolean isStrippableWood(Material type) {
        if (type == null || type.isAir()) {
            return false;
        }
        String name = type.name();
        if (name.startsWith("STRIPPED_")) {
            return false;
        }
        if (Tag.LOGS.isTagged(type)) {
            return true;
        }
        return name.endsWith("_WOOD")
                || name.endsWith("_HYPHAE")
                || name.endsWith("_STEM");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (plugin.getArenaManager().shouldDenyNomacePlace(
                event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            sendPlaceDenied(event.getPlayer());
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        Arena arena = plugin.getArenaManager().resolveArenaAt(event.getBlock().getLocation());
        if (arena == null || arena.getType() != ArenaType.MACE) {
            return;
        }

        for (org.bukkit.block.BlockState state : event.getReplacedBlockStates()) {
            plugin.getArenaManager().markPlaced(state.getLocation());
        }
        plugin.getArenaManager().markPlaced(event.getBlock().getLocation());
        plugin.getArenaManager().setPlayerArena(event.getPlayer().getUniqueId(), arena.getId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();
        Arena arena = resolveArena(player, loc);
        if (arena == null) {
            return;
        }

        if (isCropOrFarmPlant(block.getType())
                && !plugin.getArenaManager().isPlayerPlaced(loc)) {
            if (plugin.getArenaManager().canBypassBuild(player)) {
                return;
            }
            event.setCancelled(true);
            sendNaturalBreak(player);
            return;
        }

        if (plugin.getArenaManager().isPlayerPlaced(loc)) {
            event.setCancelled(false);
            plugin.getArenaManager().unmarkPlaced(loc);

            Block other = otherHalf(block);
            if (other != null && plugin.getArenaManager().isPlayerPlaced(other.getLocation())) {
                plugin.getArenaManager().unmarkPlaced(other.getLocation());
            }
            return;
        }

        if (plugin.getArenaManager().canBypassBuild(player)) {
            return;
        }

        event.setCancelled(true);
        sendNaturalBreak(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ExplosiveMinecart || entity instanceof TNTPrimed)) {
            return;
        }
        Location loc = entity.getLocation();
        if (loc.getWorld() == null || !plugin.getArenaManager().hasArenasInWorld(loc.getWorld().getName())) {
            return;
        }
        if (plugin.getArenaManager().resolveArenaAt(loc) != null) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        Location loc = event.getLocation();
        if (loc.getWorld() == null || !plugin.getArenaManager().hasArenasInWorld(loc.getWorld().getName())) {
            return;
        }
        if (!explosionTouchesArena(loc, event.blockList())) {
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof ExplosiveMinecart || entity instanceof TNTPrimed)) {
            protectNaturalArenaBlocks(event.blockList());
            return;
        }

        event.setCancelled(false);
        protectNaturalArenaBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        Location loc = event.getBlock().getLocation();
        if (loc.getWorld() == null || !plugin.getArenaManager().hasArenasInWorld(loc.getWorld().getName())) {
            return;
        }
        if (!explosionTouchesArena(loc, event.blockList())) {
            return;
        }
        protectNaturalArenaBlocks(event.blockList());
    }

    private boolean explosionTouchesArena(Location center, List<Block> blocks) {
        if (plugin.getArenaManager().resolveArenaAt(center) != null) {
            return true;
        }
        for (Block block : blocks) {
            if (plugin.getArenaManager().resolveArenaAt(block.getLocation()) != null) {
                return true;
            }
        }
        return false;
    }

    private void protectNaturalArenaBlocks(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        List<Location> brokenPlaced = new ArrayList<>();
        while (it.hasNext()) {
            Block block = it.next();
            Location bLoc = block.getLocation();
            boolean inArena = plugin.getArenaManager().resolveArenaAt(bLoc) != null;
            if (!inArena) {
                continue;
            }
            if (plugin.getArenaManager().isPlayerPlaced(bLoc)) {
                brokenPlaced.add(bLoc.clone());
                continue;
            }
            it.remove();
        }
        for (Location placed : brokenPlaced) {
            plugin.getArenaManager().unmarkPlaced(placed);
        }
    }

    private static Block otherHalf(Block block) {
        Material type = block.getType();
        String name = type.name();
        if (name.endsWith("_DOOR") && !name.endsWith("TRAPDOOR")
                && block.getBlockData() instanceof org.bukkit.block.data.Bisected bisected) {
            return bisected.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM
                    ? block.getRelative(0, 1, 0)
                    : block.getRelative(0, -1, 0);
        }
        if (name.endsWith("_BED") && block.getBlockData() instanceof org.bukkit.block.data.type.Bed bed) {
            return bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD
                    ? block.getRelative(bed.getFacing().getOppositeFace())
                    : block.getRelative(bed.getFacing());
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Location loc = event.getBlock() != null ? event.getBlock().getLocation() : player.getLocation();
        if (plugin.getArenaManager().shouldDenyNomacePlace(player, loc)) {
            event.setCancelled(true);
            sendPlaceDenied(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlaceInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();

        ItemStack item = event.getItem();
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && clicked != null
                && item != null
                && Tag.ITEMS_AXES.isTagged(item.getType())
                && isStrippableWood(clicked.getType())) {
            Arena arena = plugin.getArenaManager().resolveArenaAt(clicked.getLocation());
            if (arena != null) {
                event.setCancelled(false);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
                event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
                return;
            }
        }

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && clicked != null
                && item != null
                && Tag.ITEMS_HOES.isTagged(item.getType())
                && isTillableSoil(clicked.getType())) {
            Arena arena = plugin.getArenaManager().resolveArenaAt(clicked.getLocation());
            if (arena != null
                    && !plugin.getArenaManager().isPlayerPlaced(clicked.getLocation())
                    && !plugin.getArenaManager().canBypassBuild(player)) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }

        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && clicked != null
                && isBlockedMapInteract(clicked.getType())) {
            Arena arena = plugin.getArenaManager().resolveArenaAt(clicked.getLocation());
            if (arena != null
                    && !plugin.getArenaManager().isPlayerPlaced(clicked.getLocation())
                    && !plugin.getArenaManager().canBypassBuild(player)) {
                event.setCancelled(true);
                event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                return;
            }
        }

        if (clicked != null && isDoor(clicked.getType())) {
            return;
        }

        if (item == null || item.getType().isAir() || !isPlaceableItem(item.getType())) {
            return;
        }
        Location click = clicked != null
                ? clicked.getRelative(event.getBlockFace()).getLocation()
                : player.getLocation();
        if (plugin.getArenaManager().shouldDenyNomacePlace(player, click)) {
            event.setCancelled(true);
            sendPlaceDenied(player);
        }
    }

    private static boolean isTillableSoil(Material type) {
        return switch (type) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, DIRT_PATH, ROOTED_DIRT -> true;
            default -> false;
        };
    }

    private static boolean isCropOrFarmPlant(Material type) {
        if (Tag.CROPS.isTagged(type) || Tag.FLOWERS.isTagged(type)) {
            return true;
        }
        return switch (type) {
            case SWEET_BERRY_BUSH, COCOA, NETHER_WART,
                 SUGAR_CANE, MELON_STEM, PUMPKIN_STEM,
                 ATTACHED_MELON_STEM, ATTACHED_PUMPKIN_STEM,
                 TORCHFLOWER_CROP, PITCHER_CROP, PITCHER_PLANT,
                 ROSE_BUSH, LILAC, PEONY, SUNFLOWER,
                 SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN,
                 DEAD_BUSH, PINK_PETALS -> true;
            default -> type.name().endsWith("_SAPLING");
        };
    }

    private static boolean isNaturalFragilePlant(Block block, UnstableCore plugin) {
        return isCropOrFarmPlant(block.getType())
                && !plugin.getArenaManager().isPlayerPlaced(block.getLocation());
    }

    private static boolean isTrapdoor(Material type) {
        String name = type.name();
        return name.endsWith("TRAPDOOR");
    }

    private static boolean isDoor(Material type) {
        String name = type.name();
        return name.endsWith("_DOOR") && !name.endsWith("TRAPDOOR");
    }

    private static boolean isBlockedMapInteract(Material type) {
        if (isTrapdoor(type)) {
            return true;
        }
        if (Tag.ALL_SIGNS.isTagged(type)) {
            return true;
        }
        String name = type.name();
        if (name.endsWith("_SHULKER_BOX") || name.equals("SHULKER_BOX")) {
            return true;
        }
        return switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, ENDER_CHEST,
                 CRAFTING_TABLE, FURNACE, BLAST_FURNACE, SMOKER,
                 BREWING_STAND, ENCHANTING_TABLE,
                 ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                 HOPPER, DROPPER, DISPENSER, LECTERN, LOOM,
                 CARTOGRAPHY_TABLE, SMITHING_TABLE, GRINDSTONE, STONECUTTER,
                 BEACON, JUKEBOX, CRAFTER, DECORATED_POT,
                 CHISELED_BOOKSHELF -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Arena arena = plugin.getArenaManager().resolveArenaAt(block.getLocation());
        if (arena == null) {
            return;
        }
        if (plugin.getArenaManager().canBypassBuild(player)) {
            return;
        }
        if (plugin.getArenaManager().isPlayerPlaced(block.getLocation())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Block destination = event.getBlock();
        Location destLoc = destination.getLocation();
        if (plugin.getArenaManager().shouldDenyNomacePlace(player, destLoc)) {
            event.setCancelled(true);
            sendPlaceDenied(player);
            return;
        }

        Arena arena = plugin.getArenaManager().resolveArenaAt(destLoc);
        if (arena != null
                && isNaturalFragilePlant(destination, plugin)
                && !plugin.getArenaManager().canBypassBuild(player)) {
            event.setCancelled(true);
            return;
        }

        if (event.isCancelled()) {
            return;
        }

        if (arena == null || arena.getType() != ArenaType.MACE) {
            return;
        }
        plugin.getArenaManager().markPlaced(destLoc);
        plugin.getArenaManager().setPlayerArena(player.getUniqueId(), arena.getId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Arena arena = resolveArena(player, block.getLocation());
        if (arena == null) {
            return;
        }
        Location loc = block.getLocation();
        if (plugin.getArenaManager().isPlayerPlaced(loc)) {
            event.setCancelled(false);
            plugin.getArenaManager().unmarkPlaced(loc);
            return;
        }
        if (plugin.getArenaManager().canBypassBuild(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        if (!from.isLiquid()) {
            return;
        }
        if (!plugin.getArenaManager().hasArenasInWorld(from.getWorld().getName())) {
            return;
        }
        Block to = event.getToBlock();
        if (plugin.getArenaManager().resolveArenaAt(to.getLocation()) == null) {
            return;
        }
        if (isNaturalFragilePlant(to, plugin)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        Arena arena = null;
        String tracked = plugin.getArenaManager().getPlayerArena(player.getUniqueId());
        if (tracked != null) {
            arena = plugin.getArenaManager().getArena(tracked);
            if (arena != null && !arena.contains(to)) {
                arena = null;
                plugin.getArenaManager().setPlayerArena(player.getUniqueId(), null);
            }
        }
        if (arena == null) {
            if (!plugin.getArenaManager().hasArenasInWorld(to.getWorld().getName())) {
                return;
            }
            arena = plugin.getArenaManager().resolveArenaAt(to);
            if (arena != null) {
                plugin.getArenaManager().setPlayerArena(player.getUniqueId(), arena.getId());
            }
        }

        // Only the bush push-back below is optional. The arena entry/exit tracking above must run
        // regardless: it is the sole path that clears a player's arena tag when they walk out, and
        // build protection refuses to act on a player still tagged as in-arena. Gating it on
        // anti-bush.enabled left anyone who left an arena permanently tagged on servers with the
        // toggle off.
        if (!antiBush || arena == null || arena.getType() != ArenaType.NOMACE) {
            return;
        }

        Block block = to.getBlock();
        Material type = block.getType();
        if (!bushMaterials.contains(type)) {
            Block above = block.getRelative(0, 1, 0);
            if (!bushMaterials.contains(above.getType())) {
                return;
            }
            block = above;
        }

        Vector push = player.getLocation().toVector()
                .subtract(block.getLocation().add(0.5, 0, 0.5).toVector())
                .setY(0);
        if (push.lengthSquared() < 0.0001) {
            push = player.getLocation().getDirection().clone().multiply(-1).setY(0);
        }
        if (push.lengthSquared() < 0.0001) {
            push = new Vector(1, 0, 0);
        }
        push.normalize().multiply(pushStrength).setY(0.12);
        player.setVelocity(push);
    }

    private Arena resolveArena(Player player, Location loc) {
        String tracked = plugin.getArenaManager().getPlayerArena(player.getUniqueId());
        if (tracked != null) {
            Arena arena = plugin.getArenaManager().getArena(tracked);
            if (arena != null && arena.contains(loc)) {
                return arena;
            }
        }
        if (!plugin.getArenaManager().hasArenasInWorld(loc.getWorld().getName())) {
            return null;
        }
        Arena arena = plugin.getArenaManager().resolveArenaAt(loc);
        if (arena != null) {
            plugin.getArenaManager().setPlayerArena(player.getUniqueId(), arena.getId());
        }
        return arena;
    }

    private static boolean isPlaceableItem(Material type) {
        if (type.isBlock()) {
            return true;
        }
        return switch (type) {
            case WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET,
                 BUCKET, COD_BUCKET, SALMON_BUCKET, PUFFERFISH_BUCKET,
                 TROPICAL_FISH_BUCKET, AXOLOTL_BUCKET, TADPOLE_BUCKET,
                 END_CRYSTAL, ARMOR_STAND, ITEM_FRAME, GLOW_ITEM_FRAME,
                 PAINTING, MINECART, CHEST_MINECART, HOPPER_MINECART,
                 FURNACE_MINECART, TNT_MINECART,
                 OAK_BOAT, SPRUCE_BOAT, BIRCH_BOAT, JUNGLE_BOAT,
                 ACACIA_BOAT, DARK_OAK_BOAT, MANGROVE_BOAT, CHERRY_BOAT,
                 BAMBOO_RAFT, PALE_OAK_BOAT,
                 OAK_CHEST_BOAT, SPRUCE_CHEST_BOAT, BIRCH_CHEST_BOAT,
                 JUNGLE_CHEST_BOAT, ACACIA_CHEST_BOAT, DARK_OAK_CHEST_BOAT,
                 MANGROVE_CHEST_BOAT, CHERRY_CHEST_BOAT, BAMBOO_CHEST_RAFT,
                 PALE_OAK_CHEST_BOAT,
                 COBWEB, STRING, SWEET_BERRIES, GLOW_BERRIES,
                 FIRE_CHARGE, FLINT_AND_STEEL, BONE_MEAL -> true;
            default -> type.name().endsWith("_BOAT")
                    || type.name().endsWith("_RAFT")
                    || type.name().endsWith("_BUCKET")
                    || type.name().endsWith("_SPAWN_EGG");
        };
    }

    private void sendPlaceDenied(Player player) {
        String msg = plugin.getConfig().getString(
                "messages.arena-nomace-no-place",
                "&c&l(!) &r&cYou can't place blocks in no-mace arenas."
        );
        if (msg == null || msg.isBlank()) {
            msg = "&c&l(!) &r&cYou can't place blocks in no-mace arenas.";
        }
        MessageUtil.send(player, msg);
    }

    public void clearPlayer(UUID uuid) {
        breakMsgCooldown.remove(uuid);
    }

    private void sendNaturalBreak(Player player) {
        long now = System.currentTimeMillis();
        Long last = breakMsgCooldown.get(player.getUniqueId());
        if (last != null && now - last < BREAK_MSG_COOLDOWN_MS) {
            return;
        }
        breakMsgCooldown.put(player.getUniqueId(), now);
        String msg = plugin.getConfig().getString(
                "messages.arena-natural-break",
                "&c&l(!) &r&cSorry, you can't break natural blocks."
        );
        if (msg == null || msg.isBlank()) {
            msg = "&c&l(!) &r&cSorry, you can't break natural blocks.";
        }
        MessageUtil.send(player, msg);
    }
}
