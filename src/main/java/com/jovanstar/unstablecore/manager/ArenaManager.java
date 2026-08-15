package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.ArenaType;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class ArenaManager {

    private final UnstableCore plugin;
    // LinkedHashMap so config/rotation logic that iterates arenas.values() (e.g. the nomace-limit
    // enforcement below) is deterministic and follows arenas.yml's declared order.
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    private final Map<String, List<Arena>> arenasByWorld = new HashMap<>();

    private final Set<String> placedBlocks = ConcurrentHashMap.newKeySet();

    private final Map<UUID, String> playerArena = new ConcurrentHashMap<>();

    private final Map<String, Deque<long[]>> recentSpawns = new ConcurrentHashMap<>();

    private final Set<UUID> mineBypass = ConcurrentHashMap.newKeySet();
    private volatile boolean placedDirty;

    private Arena newbieArena;
    private String activeArenaId = "";
    private long lastRotation;
    private BukkitTask rotateTask;

    public ArenaManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        arenas.clear();
        placedBlocks.clear();
        FileConfiguration cfg = plugin.getConfigManager().getArenas();

        ConfigurationSection section = cfg.getConfigurationSection("arenas");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection arenaSec = section.getConfigurationSection(key);
                if (arenaSec != null) {
                    arenas.put(key.toLowerCase(Locale.ROOT), Arena.fromConfig(key, arenaSec));
                }
            }
        }

        newbieArena = new Arena("newbie", "Newbie Arena", ArenaType.NOMACE);
        ConfigurationSection newbieSec = cfg.getConfigurationSection("newbie");
        if (newbieSec != null) {
            newbieArena = Arena.fromConfig("newbie", newbieSec);
        }

        ensureNewbieIsNomace();

        activeArenaId = cfg.getString("active-arena", "");
        lastRotation = cfg.getLong("last-rotation", 0L);

        loadPlacedBlocks();
        rebuildWorldIndex();
        placedDirty = false;

        enforceNomaceLimit();

        if (activeArenaId.isBlank() || !arenas.containsKey(activeArenaId)
                || (plugin.getConfig().getBoolean("arena.rotate-mace-only", true)
                && arenas.get(activeArenaId) != null
                && arenas.get(activeArenaId).getType() == ArenaType.NOMACE)) {
            rotateActive(false);
        }

        startRotationTask();
    }

    private void ensureNewbieIsNomace() {
        if (newbieArena == null) {
            newbieArena = new Arena("newbie", "Newbie Arena", ArenaType.NOMACE);
        }
        newbieArena.setType(ArenaType.NOMACE);
        newbieArena.setDisplayName(plugin.getConfig().getString(
                "arena.newbie.display-name", "Newbie Arena » Under 6h"));
    }

    public int getMaxNomaceArenas() {
        return Math.max(1, plugin.getConfig().getInt("arena.max-nomace-arenas", 2));
    }

    public int countNomaceArenas() {
        int count = 0;
        if (newbieArena != null && newbieArena.getType() == ArenaType.NOMACE) {
            count++;
        }
        for (Arena arena : arenas.values()) {
            if (arena.getType() == ArenaType.NOMACE) {
                count++;
            }
        }
        return count;
    }

    public boolean canAddNomaceArena() {
        return countNomaceArenas() < getMaxNomaceArenas();
    }

    public boolean wouldExceedNomaceLimit(Arena changing) {
        if (changing != null && changing.getType() == ArenaType.NOMACE) {
            return false;
        }
        return !canAddNomaceArena();
    }

    private void enforceNomaceLimit() {
        int max = getMaxNomaceArenas();
        int count = countNomaceArenas();
        if (count <= max) {
            return;
        }

        int allowedExtra = Math.max(0, max - (newbieArena != null && newbieArena.getType() == ArenaType.NOMACE ? 1 : 0));
        int kept = 0;
        for (Arena arena : arenas.values()) {
            if (arena.getType() != ArenaType.NOMACE) {
                continue;
            }
            if (kept < allowedExtra) {
                kept++;
            } else {
                arena.setType(ArenaType.MACE);
                plugin.getLogger().warning("Arena '" + arena.getId()
                        + "' converted to mace - nomace limit is " + max + " (includes under-6h).");
            }
        }
        save();
    }

    private void rebuildWorldIndex() {
        arenasByWorld.clear();
        for (Arena arena : arenas.values()) {
            indexArena(arena);
        }
        if (newbieArena != null) {
            indexArena(newbieArena);
        }
    }

    private void indexArena(Arena arena) {
        if (arena == null || arena.getWorldName() == null || arena.getWorldName().isBlank()) {
            return;
        }
        arenasByWorld.computeIfAbsent(arena.getWorldName().toLowerCase(Locale.ROOT), w -> new ArrayList<>()).add(arena);
    }

    public void reload() {
        if (rotateTask != null) {
            rotateTask.cancel();
        }
        plugin.getConfigManager().reloadArenas();
        load();
    }

    public void shutdown() {
        if (rotateTask != null) {
            rotateTask.cancel();
        }
        save();
        savePlacedBlocks();
    }

    public void shutdownSaveDataOnly() {
        // Invoked from an async periodic task (see UnstableCore's autosave timer). The actual
        // FileConfiguration mutation/save must happen on the main thread since the same
        // data.yml-backed config object is also read/written synchronously elsewhere
        // (e.g. EventManager's timers), and FileConfiguration is not thread-safe.
        if (!placedDirty) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placedDirty) {
                savePlacedBlocks();
            }
        });
    }

    public void save() {
        FileConfiguration cfg = plugin.getConfigManager().getArenas();
        cfg.set("arenas", null);
        for (Arena arena : arenas.values()) {
            arena.write(cfg.createSection("arenas." + arena.getId()));
        }
        if (newbieArena != null) {
            newbieArena.write(cfg.createSection("newbie"));
        }
        cfg.set("active-arena", activeArenaId);
        cfg.set("last-rotation", lastRotation);
        plugin.getConfigManager().saveArenas();
    }

    private void loadPlacedBlocks() {
        placedBlocks.clear();
        List<String> list = plugin.getConfigManager().getData().getStringList("placed-blocks");
        placedBlocks.addAll(list);
    }

    private void savePlacedBlocks() {

        List<String> list = new ArrayList<>(placedBlocks);
        if (list.size() > 50_000) {
            list = new ArrayList<>(list.subList(list.size() - 50_000, list.size()));
        }
        plugin.getConfigManager().getData().set("placed-blocks", list);
        plugin.getConfigManager().saveData();
        placedDirty = false;
    }

    private void startRotationTask() {
        long interval = plugin.getConfig().getLong("arena.rotate-interval-seconds", 7200);

        rotateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            if (lastRotation <= 0) {
                lastRotation = now;
                save();
                return;
            }

            MapVoteManager votes = plugin.getMapVoteManager();
            if (votes != null && votes.isEnabled()) {
                votes.tick();
                if (votes.isVoting()) {
                    return;
                }

                long leadMs = Math.max(5L, plugin.getConfig().getLong("arena.vote.lead-seconds", 60L)) * 1000L;
                if (now - lastRotation >= interval * 1000L + leadMs) {
                    rotateActive(true);
                }
                return;
            }

            if (now - lastRotation >= interval * 1000L) {
                rotateActive(true);
            }
        }, 20L * 10, 20L * 10);
    }

    public boolean createArena(String name, String displayName, ArenaType type) {
        String id = name.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(id) || id.equals("newbie")) {
            return false;
        }
        if (type == ArenaType.NOMACE && wouldExceedNomaceLimit(null)) {
            return false;
        }
        Arena arena = new Arena(id, displayName, type);

        if (type == ArenaType.NOMACE) {
            arena.setPermanent(true);
        }
        arenas.put(id, arena);
        rebuildWorldIndex();
        if (activeArenaId.isBlank() && type == ArenaType.MACE) {
            activeArenaId = id;
            lastRotation = System.currentTimeMillis();
        }
        save();
        return true;
    }

    public boolean isNomaceLimitReached() {
        return !canAddNomaceArena();
    }

    public boolean deleteArena(String name) {
        String id = name.toLowerCase(Locale.ROOT);
        if (arenas.remove(id) == null) {
            return false;
        }
        rebuildWorldIndex();
        if (id.equals(activeArenaId)) {
            rotateActive(false);
        }
        save();
        return true;
    }

    public boolean setCenter(String name, Location loc, int radius) {
        Arena arena = getArena(name);
        if (arena == null) {
            return false;
        }
        arena.setCenter(loc, radius);
        cacheSafeSpots(arena);
        rebuildWorldIndex();
        save();
        return true;
    }

    public boolean setNewbieCenter(Location loc, int radius) {
        ensureNewbieIsNomace();
        newbieArena.setCenter(loc, radius);
        cacheSafeSpots(newbieArena);
        rebuildWorldIndex();
        save();
        return true;
    }

    public boolean setSpawn1(String name, Location loc) {
        Arena arena = getArena(name);
        if (arena == null) {
            return false;
        }
        arena.setSpawn1(loc);
        save();
        return true;
    }

    public boolean setSpawn2(String name, Location loc) {
        Arena arena = getArena(name);
        if (arena == null) {
            return false;
        }
        arena.setSpawn2(loc);
        save();
        return true;
    }

    public void setPermanent(String name, boolean permanent) {
        Arena arena = arenas.get(name.toLowerCase(Locale.ROOT));
        if (arena != null) {
            arena.setPermanent(permanent);
            save();
        }
    }

    public void rotateActive(boolean broadcast) {
        rotateActive(broadcast, null);
    }

    public void rotateActive(boolean broadcast, String forcedArenaId) {
        String previousId = activeArenaId == null ? "" : activeArenaId;
        Arena previous = previousId.isBlank() ? null : arenas.get(previousId);

        List<Arena> rotatable = getRotatableArenas(false);
        if (rotatable.isEmpty()) {
            activeArenaId = "";
            return;
        }

        Arena chosen = null;
        if (forcedArenaId != null && !forcedArenaId.isBlank()) {
            Arena forced = getArena(forcedArenaId);
            if (forced != null && forced.hasCenter()) {
                chosen = forced;
            }
        }
        if (chosen == null) {
            if (rotatable.size() == 1) {
                chosen = rotatable.get(0);
            } else {
                List<Arena> pool = new ArrayList<>(rotatable);
                pool.removeIf(a -> a.getId().equals(activeArenaId));
                if (pool.isEmpty()) {
                    pool = rotatable;
                }
                chosen = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            }
        }

        activeArenaId = chosen.getId();
        lastRotation = System.currentTimeMillis();
        save();

        if (broadcast) {
            String msg = plugin.getConfig().getString("arena.rotate-broadcast", "");
            MessageUtil.broadcastFiltered(msg, Map.of("map", chosen.getDisplayName()),
                    plugin.getSettingsManager().filter(SettingsManager.ROTATION_ALERTS));
            try {
                Sound sound = Sound.valueOf(plugin.getConfig().getString("arena.rotate-sound", "UI_TOAST_CHALLENGE_COMPLETE"));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(), sound, 1f, 1f);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (plugin.getConfig().getBoolean("arena.rotate-transfer-players", true)
                && previous != null
                && !previous.getId().equals(chosen.getId())
                && chosen.hasCenter()) {
            transferPlayersOnRotate(previous, chosen);
        }
    }

    public List<Arena> getRotatableArenas(boolean excludeActive) {
        boolean maceOnly = plugin.getConfig().getBoolean("arena.rotate-mace-only", true);
        List<Arena> rotatable = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (!arena.isPermanent() && arena.hasCenter()) {
                if (maceOnly && arena.getType() == ArenaType.NOMACE) {
                    continue;
                }
                if (excludeActive && arena.getId().equals(activeArenaId)) {
                    continue;
                }
                rotatable.add(arena);
            }
        }
        if (rotatable.isEmpty()) {
            for (Arena arena : arenas.values()) {
                if (arena.isPermanent() || !arena.hasCenter()) {
                    continue;
                }
                if (maceOnly && arena.getType() == ArenaType.NOMACE) {
                    continue;
                }
                if (excludeActive && arena.getId().equals(activeArenaId)) {
                    continue;
                }
                rotatable.add(arena);
            }
        }
        return rotatable;
    }

    public long getLastRotation() {
        return lastRotation;
    }

    private void transferPlayersOnRotate(Arena from, Arena to) {
        List<Player> movers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (shouldTransferOnRotate(player, from)) {
                movers.add(player);
            }
        }
        if (movers.isEmpty()) {
            return;
        }

        int delay = 0;
        for (Player player : movers) {
            final int ticks = delay;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                MessageUtil.sendConfig(player, "arena-rotate-transfer", Map.of("map", to.getDisplayName()));
                teleportToArena(player, to.getId());
            }, ticks);
            delay += 2;
        }
        plugin.getLogger().info("Arena rotate: transferring " + movers.size()
                + " player(s) from '" + from.getId() + "' to '" + to.getId() + "'.");
    }

    private boolean shouldTransferOnRotate(Player player, Arena from) {
        if (plugin.getDuelManager() != null) {
            UUID uuid = player.getUniqueId();
            if (plugin.getDuelManager().isInDuel(uuid) || plugin.getDuelManager().isInGrace(uuid)) {
                return false;
            }
        }

        String tracked = playerArena.get(player.getUniqueId());

        if ("newbie".equalsIgnoreCase(tracked)) {
            return false;
        }

        if (!from.contains(player.getLocation())) {
            return false;
        }

        if (tracked != null) {
            Arena trackedArena = getArena(tracked);
            if (trackedArena != null
                    && !trackedArena.getId().equals(from.getId())
                    && trackedArena.isPermanent()
                    && trackedArena.contains(player.getLocation())) {
                return false;
            }
        }
        return true;
    }

    public void cacheSafeSpots(Arena arena) {
        Location center = arena.getCenter();
        if (center == null) {
            return;
        }
        World world = center.getWorld();
        int radius = Math.max(5, arena.getRadius());
        int target = plugin.getConfig().getInt("arena.rtp-cache-size", 48);
        int minDist = plugin.getConfig().getInt("arena.rtp-min-spot-distance", 12);
        int maxAttempts = plugin.getConfig().getInt("arena.rtp-max-attempts", 120);

        List<int[]> found = new ArrayList<>();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int step = Math.max(4, Math.min(minDist, Math.max(4, radius / 6)));
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        List<int[]> grid = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                grid.add(new int[]{cx + dx, cz + dz});
            }
        }
        Collections.shuffle(grid, rng);
        for (int[] xz : grid) {
            if (found.size() >= target) {
                break;
            }
            tryAddSpreadSpot(world, center, radius, found, xz[0], xz[1], minDist);
        }

        for (int i = 0; i < maxAttempts * 5 && found.size() < target; i++) {
            int[] xz = randomInArena(world, center, radius, rng);
            tryAddSpreadSpot(world, center, radius, found, xz[0], xz[1], minDist);
        }

        if (found.isEmpty()) {
            int floorY = findSafeOpenY(world, cx, cz);
            if (floorY != Integer.MIN_VALUE
                    && isInsideArenaBounds(world, center, radius, cx, floorY, cz)) {
                found.add(new int[]{cx, floorY, cz});
            } else if (isSafeOpenSpot(world, cx, center.getBlockY(), cz)
                    && isInsideArenaBounds(world, center, radius, cx, center.getBlockY(), cz)) {
                found.add(new int[]{cx, center.getBlockY(), cz});
            }
        }

        arena.setSpots(found);
        plugin.getLogger().info("Cached " + found.size() + " RTP spots for arena '" + arena.getId() + "'.");
    }

    private void tryAddSpreadSpot(World world, Location center, int radius,
                                  List<int[]> found, int x, int z, int minDist) {
        int floorY = findSafeOpenY(world, x, z);
        if (floorY == Integer.MIN_VALUE) {
            return;
        }
        if (!isInsideArenaBounds(world, center, radius, x, floorY, z)) {
            return;
        }
        if (isTooClose(found, x, z, minDist)) {
            return;
        }
        found.add(new int[]{x, floorY, z});
    }

    private boolean isTooClose(List<int[]> spots, int x, int z, int minDist) {
        int minSq = minDist * minDist;
        for (int[] s : spots) {
            int dx = s[0] - x;
            int dz = s[2] - z;
            if (dx * dx + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    private int[] randomInArena(World world, Location center, int radius, ThreadLocalRandom rng) {
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int maxR = Math.max(0, radius - 1);
        for (int attempt = 0; attempt < 16; attempt++) {

            double dist = Math.sqrt(rng.nextDouble()) * maxR;
            double angle = rng.nextDouble() * Math.PI * 2;
            int bx = cx + (int) Math.round(Math.cos(angle) * dist);
            int bz = cz + (int) Math.round(Math.sin(angle) * dist);
            if (isInsideArenaBounds(world, center, radius, bx, center.getBlockY(), bz)) {
                return new int[]{bx, bz};
            }
        }
        return new int[]{cx, cz};
    }

    private boolean isInsideArenaBounds(World world, Location center, int radius, int x, int y, int z) {
        if (world == null || center == null || center.getWorld() == null) {
            return false;
        }
        if (!center.getWorld().equals(world)) {
            return false;
        }
        double dx = (x + 0.5) - center.getX();
        double dz = (z + 0.5) - center.getZ();
        double maxDist = Math.max(0, radius);
        if ((dx * dx) + (dz * dz) > maxDist * maxDist) {
            return false;
        }

        WorldBorder border = world.getWorldBorder();
        if (border == null) {
            return true;
        }

        double pad = Math.max(1.0, plugin.getConfig().getDouble("arena.rtp-border-padding", 2.0));
        Location probe = new Location(world, x + 0.5, y, z + 0.5);
        if (!border.isInside(probe)) {
            return false;
        }

        double half = (border.getSize() / 2.0) - pad;
        if (half <= 0) {
            return false;
        }
        Location borderCenter = border.getCenter();
        double bdx = probe.getX() - borderCenter.getX();
        double bdz = probe.getZ() - borderCenter.getZ();
        return (bdx * bdx) + (bdz * bdz) <= half * half;
    }

    private int findSafeOpenY(World world, int x, int z) {
        int highest = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int surfaceFeet = highest + 1;
        if (surfaceFeet < world.getMinHeight() + 2 || surfaceFeet > world.getMaxHeight() - 2) {
            return Integer.MIN_VALUE;
        }

        if (isSafeOpenSpot(world, x, surfaceFeet, z)) {
            return surfaceFeet;
        }

        for (int y = surfaceFeet; y >= surfaceFeet - 6 && y > world.getMinHeight() + 2; y--) {
            if (isSafeOpenSpot(world, x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean isSafeOpenSpot(World world, int x, int y, int z) {
        if (y < world.getMinHeight() + 2 || y > world.getMaxHeight() - 2) {
            return false;
        }
        if (!isSafe(world, x, y, z)) {
            return false;
        }

        Material ground = world.getBlockAt(x, y - 1, z).getType();
        if (isUnsafeStandMaterial(ground)
                || isUnsafeStandMaterial(world.getBlockAt(x, y, z).getType())
                || isUnsafeStandMaterial(world.getBlockAt(x, y + 1, z).getType())) {
            return false;
        }

        int support = Math.max(1, plugin.getConfig().getInt("arena.rtp-min-support-depth", 2));
        for (int i = 2; i <= support + 1; i++) {
            if (!world.getBlockAt(x, y - i, z).getType().isSolid()) {
                return false;
            }
        }

        int clearance = plugin.getConfig().getInt("arena.rtp-clearance-above", 6);
        for (int scanY = y + 2; scanY <= y + 1 + clearance; scanY++) {
            if (scanY >= world.getMaxHeight()) {
                break;
            }
            if (world.getBlockAt(x, scanY, z).getType().isSolid()) {
                return false;
            }
        }

        if (plugin.getConfig().getBoolean("arena.rtp-require-sky-light", true)
                && world.getBlockAt(x, y, z).getLightFromSky() <= 0) {
            return false;
        }

        if (isThinRoofOverRoom(world, x, y, z, support)) {
            return false;
        }
        return true;
    }

    private boolean isThinRoofOverRoom(World world, int x, int feetY, int z, int support) {
        int airNeeded = plugin.getConfig().getInt("arena.rtp-roof-air-needed", 3);
        int startY = feetY - (support + 2);
        int air = 0;
        for (int i = 0; i < 5; i++) {
            int y = startY - i;
            if (y <= world.getMinHeight()) {
                break;
            }
            if (!world.getBlockAt(x, y, z).getType().isSolid()) {
                air++;
            }
        }
        return air >= airNeeded;
    }

    private boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        if (isUnsafeStandMaterial(ground.getType())
                || isUnsafeStandMaterial(feet.getType())
                || isUnsafeStandMaterial(head.getType())) {
            return false;
        }
        if (!ground.getType().isSolid()) {
            return false;
        }
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        Material groundType = ground.getType();
        if (groundType == Material.LAVA || groundType == Material.MAGMA_BLOCK
                || groundType == Material.CACTUS || groundType == Material.FIRE
                || groundType == Material.SOUL_FIRE || groundType == Material.CAMPFIRE
                || groundType == Material.WATER || groundType.name().endsWith("_LEAVES")) {
            return false;
        }
        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        return true;
    }

    private boolean isUnsafeStandMaterial(Material type) {
        if (type == null) {
            return true;
        }
        return type == Material.BARRIER
                || type == Material.STRUCTURE_VOID
                || type == Material.LIGHT
                || type.name().contains("GLASS")
                || type.name().contains("SLAB")
                || type.name().contains("CARPET")
                || type == Material.SNOW
                || type.name().contains("FENCE")
                || type.name().contains("WALL")
                || type.name().contains("GATE");
    }

    public boolean teleportToArena(Player player, String arenaKey) {
        if (plugin.getDuelManager() != null) {
            UUID uuid = player.getUniqueId();
            if (plugin.getDuelManager().isInDuel(uuid) || plugin.getDuelManager().isInGrace(uuid)) {
                MessageUtil.send(player, "&cYou cannot join an arena while in a duel.");
                return false;
            }
        }

        Arena arena;
        if ("newbie".equalsIgnoreCase(arenaKey)) {
            arena = newbieArena;
        } else {
            arena = arenas.get(arenaKey.toLowerCase(Locale.ROOT));
        }
        if (arena == null || !arena.hasCenter()) {
            MessageUtil.sendConfig(player, "arena-no-spots", Map.of());
            return false;
        }

        Location spot = pickValidatedSpot(arena);
        if (spot == null) {
            cacheSafeSpots(arena);
            save();
            spot = pickValidatedSpot(arena);
        }
        if (spot == null) {
            Location center = arena.getCenter();
            if (center != null
                    && isSafe(center.getWorld(), center.getBlockX(), center.getBlockY(), center.getBlockZ())
                    && isInsideArenaBounds(center.getWorld(), center, arena.getRadius(),
                    center.getBlockX(), center.getBlockY(), center.getBlockZ())) {
                spot = center.clone().add(0.5, 0, 0.5);
            }
        }
        if (spot == null || !isInsideArenaBounds(spot.getWorld(), arena.getCenter(), arena.getRadius(),
                spot.getBlockX(), spot.getBlockY(), spot.getBlockZ())) {
            MessageUtil.sendConfig(player, "arena-no-spots", Map.of());
            return false;
        }

        spot.getChunk().load();
        final Location destination = spot;
        player.teleportAsync(destination).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                playerArena.put(player.getUniqueId(), arena.getId());
                MessageUtil.sendConfig(player, "arena-teleported", Map.of("map", arena.getDisplayName()));
                giveRandomKitIfEmpty(player);

                String title = MessageUtil.apply(
                        plugin.getConfig().getString("arena.teleport-title", "&a&l{map}"),
                        Map.of("map", arena.getDisplayName())
                );
                String subtitle = MessageUtil.apply(
                        plugin.getConfig().getString("arena.teleport-subtitle", "&fYou are teleported to arena"),
                        Map.of("map", arena.getDisplayName())
                );
                int seconds = plugin.getConfig().getInt("arena.teleport-title-seconds", 2);
                MessageUtil.title(player, title, subtitle, seconds);
                playTeleportSound(player, destination);
            });
        });
        return true;
    }

    /**
     * Safety net: a player entering an arena with a completely empty inventory gets a random
     * unlocked kit. Must still respect the /loadout cooldown - otherwise dying (which empties
     * your inventory) and immediately re-entering the arena re-gears you for free, defeating the
     * whole point of that cooldown.
     */
    private void giveRandomKitIfEmpty(Player player) {
        KitManager kitManager = plugin.getKitManager();
        if (kitManager == null || !KitManager.isInventoryEmpty(player)) {
            return;
        }
        LoadoutManager loadoutManager = plugin.getLoadoutManager();
        if (loadoutManager != null && loadoutManager.remainingMillis(player.getUniqueId()) > 0) {
            return;
        }
        Kit kit = kitManager.giveRandomUnlockedKit(player);
        if (kit != null) {
            MessageUtil.sendConfig(player, "arena-auto-kit", Map.of("kit", kit.getDisplayName()));
        }
    }

    private void playTeleportSound(Player player, Location destination) {
        float vol = (float) plugin.getConfig().getDouble("arena.teleport-sound-volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("arena.teleport-sound-pitch", 1.0);
        String key = plugin.getConfig().getString(
                "arena.teleport-sound-key",
                "minecraft:item.mace.smash_ground_heavy"
        );
        try {
            player.playSound(destination, key, SoundCategory.PLAYERS, vol, pitch);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Sound sound = Sound.valueOf(plugin.getConfig().getString(
                    "arena.teleport-sound", "ITEM_MACE_SMASH_GROUND_HEAVY"));
            player.playSound(destination, sound, SoundCategory.PLAYERS, vol, pitch);
        } catch (IllegalArgumentException ignored) {
            player.playSound(destination, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, vol, 0.7f);
        }
    }

    /**
     * Public entry point for other systems (e.g. duels) that need a single safe teleport spot
     * inside an arena without going through the FFA-specific {@link #teleportToArena} flow
     * (which sends FFA messages/sounds and auto-gives a random kit on empty inventory). Reuses
     * the exact same spot-picking/validation logic FFA rotation relies on.
     */
    public Location findSafeSpot(Arena arena) {
        return pickValidatedSpot(arena);
    }

    private Location pickValidatedSpot(Arena arena) {
        Location center = arena.getCenter();
        if (center == null) {
            return null;
        }
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        int radius = Math.max(5, arena.getRadius());
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int avoidDist = plugin.getConfig().getInt("arena.rtp-avoid-recent-distance", 18);
        int attempts = plugin.getConfig().getInt("arena.rtp-max-attempts", 120);

        Location live = findLiveSpreadSpot(world, center, radius, arena.getId(), attempts, avoidDist, rng);
        if (live != null) {
            rememberSpawn(arena.getId(), live.getBlockX(), live.getBlockZ());
            return live;
        }

        List<int[]> spots = new ArrayList<>(arena.getSpots());
        Collections.shuffle(spots, rng);
        Location fallback = null;
        for (int[] s : spots) {
            if (!isSafeOpenSpot(world, s[0], s[1], s[2])
                    || !isInsideArenaBounds(world, center, radius, s[0], s[1], s[2])) {
                continue;
            }
            if (!isRecentlyUsed(arena.getId(), s[0], s[2], avoidDist)) {
                Location loc = new Location(world, s[0] + 0.5, s[1], s[2] + 0.5, center.getYaw(), center.getPitch());
                rememberSpawn(arena.getId(), s[0], s[2]);
                return loc;
            }
            if (fallback == null) {
                fallback = new Location(world, s[0] + 0.5, s[1], s[2] + 0.5, center.getYaw(), center.getPitch());
            }
        }
        if (fallback != null) {
            rememberSpawn(arena.getId(), fallback.getBlockX(), fallback.getBlockZ());
        }
        return fallback;
    }

    private Location findLiveSpreadSpot(World world, Location center, int radius, String arenaId,
                                        int attempts, int avoidDist, ThreadLocalRandom rng) {
        Location anyValid = null;
        for (int i = 0; i < attempts; i++) {
            int[] xz = randomInArena(world, center, radius, rng);
            int floorY = findSafeOpenY(world, xz[0], xz[1]);
            if (floorY == Integer.MIN_VALUE) {
                continue;
            }
            if (!isInsideArenaBounds(world, center, radius, xz[0], floorY, xz[1])) {
                continue;
            }
            Location loc = new Location(world, xz[0] + 0.5, floorY, xz[1] + 0.5, center.getYaw(), center.getPitch());
            if (!isRecentlyUsed(arenaId, xz[0], xz[1], avoidDist)) {
                return loc;
            }
            if (anyValid == null) {
                anyValid = loc;
            }
        }
        return anyValid;
    }

    private boolean isRecentlyUsed(String arenaId, int x, int z, int avoidDist) {
        Deque<long[]> recent = recentSpawns.get(arenaId);
        if (recent == null || recent.isEmpty()) {
            return false;
        }
        int minSq = avoidDist * avoidDist;
        for (long[] p : recent) {
            long dx = p[0] - x;
            long dz = p[1] - z;
            if (dx * dx + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    private void rememberSpawn(String arenaId, int x, int z) {
        int memory = Math.max(1, plugin.getConfig().getInt("arena.rtp-recent-memory", 8));
        Deque<long[]> recent = recentSpawns.computeIfAbsent(arenaId, id -> new ArrayDeque<>());
        recent.addFirst(new long[]{x, z});
        while (recent.size() > memory) {
            recent.removeLast();
        }
    }
    public Arena getArena(String name) {
        if ("newbie".equalsIgnoreCase(name)) {
            return newbieArena;
        }
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public Arena getActiveArena() {
        if (activeArenaId == null || activeArenaId.isBlank()) {
            return null;
        }
        return arenas.get(activeArenaId);
    }

    public Arena getSwordArena() {
        String configured = plugin.getConfig().getString("sword.arena-id", "");
        if (configured != null && !configured.isBlank()) {
            Arena arena = getArena(configured.trim());
            if (arena != null && arena.hasCenter()) {
                return arena;
            }
        }
        for (Arena arena : arenas.values()) {
            if (arena.getType() == ArenaType.NOMACE && arena.hasCenter()) {
                return arena;
            }
        }
        return null;
    }

    public int countTrackedPlayersInArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return 0;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String tracked = playerArena.get(online.getUniqueId());
            if (tracked != null && tracked.equalsIgnoreCase(key)) {
                count++;
            }
        }
        return count;
    }

    public Arena getNewbieArena() {
        return newbieArena;
    }

    public Map<String, Arena> getArenas() {
        return Collections.unmodifiableMap(arenas);
    }

    public List<Arena> getPermanentArenas() {
        List<Arena> list = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.isPermanent() && arena.hasCenter()) {
                list.add(arena);
            }
        }
        return list;
    }

    public String getPlayerArena(UUID uuid) {
        return playerArena.get(uuid);
    }

    public void setPlayerArena(UUID uuid, String arenaId) {
        if (arenaId == null) {
            playerArena.remove(uuid);
        } else {
            playerArena.put(uuid, arenaId);
        }
    }

    public void clearPlayer(UUID uuid) {
        playerArena.remove(uuid);
        mineBypass.remove(uuid);
    }

    public boolean hasMineBypass(UUID uuid) {
        return mineBypass.contains(uuid);
    }

    public boolean toggleMineBypass(UUID uuid) {
        if (mineBypass.contains(uuid)) {
            mineBypass.remove(uuid);
            return false;
        }
        mineBypass.add(uuid);
        return true;
    }

    public void setMineBypass(UUID uuid, boolean enabled) {
        if (enabled) {
            mineBypass.add(uuid);
        } else {
            mineBypass.remove(uuid);
        }
    }

    public boolean hasArenasInWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        List<Arena> list = arenasByWorld.get(worldName.toLowerCase(Locale.ROOT));
        return list != null && !list.isEmpty();
    }

    public Arena resolveArenaAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        List<Arena> candidates = arenasByWorld.get(loc.getWorld().getName().toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        Arena best = null;
        double bestDist = Double.MAX_VALUE;
        double lx = loc.getX();
        double lz = loc.getZ();
        for (Arena arena : candidates) {
            if (arena.getWorldName() == null || arena.getWorldName().isBlank()) {
                continue;
            }
            double dx = lx - arena.getX();
            double dz = lz - arena.getZ();
            double dist = dx * dx + dz * dz;
            double r = arena.getRadius() + 5;
            if (dist <= r * r && dist < bestDist) {
                best = arena;
                bestDist = dist;
            }
        }
        return best;
    }

    public boolean isInNomaceArena(Player player) {
        Arena arena = getTrackedOrResolve(player);
        return arena != null && arena.getType() == ArenaType.NOMACE;
    }

    public boolean isInNewbieArena(Player player) {
        Arena arena = getTrackedOrResolve(player);
        return arena != null && "newbie".equalsIgnoreCase(arena.getId());
    }

    public boolean isInMaceArena(Player player) {
        Arena arena = getTrackedOrResolve(player);
        return arena != null && arena.getType() == ArenaType.MACE;
    }

    public boolean isNomacePlaceDeniedAt(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        List<Arena> candidates = arenasByWorld.get(loc.getWorld().getName().toLowerCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (Arena arena : candidates) {
            if (arena.getType() == ArenaType.NOMACE && arena.contains(loc)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldDenyNomacePlace(Player player, Location... locations) {
        if (canBypassBuild(player)) {
            return false;
        }

        String trackedId = playerArena.get(player.getUniqueId());
        if (trackedId != null) {
            Arena tracked = getArena(trackedId);
            if (tracked != null && tracked.getType() == ArenaType.NOMACE
                    && tracked.contains(player.getLocation())) {
                return true;
            }
        }
        if (isNomacePlaceDeniedAt(player.getLocation())) {
            return true;
        }
        if (locations != null) {
            for (Location loc : locations) {
                if (loc != null && isNomacePlaceDeniedAt(loc)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean setArenaType(String name, ArenaType type) {
        if ("newbie".equalsIgnoreCase(name)) {

            ensureNewbieIsNomace();
            save();
            return type == ArenaType.NOMACE;
        }
        Arena arena = arenas.get(name.toLowerCase(Locale.ROOT));
        if (arena == null) {
            return false;
        }
        if (type == ArenaType.NOMACE && wouldExceedNomaceLimit(arena)) {
            return false;
        }
        arena.setType(type);
        if (type == ArenaType.NOMACE) {
            arena.setPermanent(true);
            if (arena.getId().equals(activeArenaId)
                    && plugin.getConfig().getBoolean("arena.rotate-mace-only", true)) {
                rotateActive(false);
            }
        }
        save();
        return true;
    }

    public List<Arena> getNomaceArenas() {
        List<Arena> list = new ArrayList<>();
        if (newbieArena != null && newbieArena.getType() == ArenaType.NOMACE && newbieArena.hasCenter()) {
            list.add(newbieArena);
        }
        for (Arena arena : arenas.values()) {
            if (arena.getType() == ArenaType.NOMACE && arena.hasCenter()) {
                list.add(arena);
            }
        }
        return list;
    }

    public boolean canBypassBuild(Player player) {
        return player != null
                && player.hasPermission("unstablecore.admin")
                && hasMineBypass(player.getUniqueId());
    }

    private Arena getTrackedOrResolve(Player player) {
        String id = playerArena.get(player.getUniqueId());
        if (id != null) {
            Arena tracked = getArena(id);
            if (tracked != null && tracked.contains(player.getLocation())) {
                return tracked;
            }
        }
        Arena resolved = resolveArenaAt(player.getLocation());
        if (resolved != null) {
            playerArena.put(player.getUniqueId(), resolved.getId());
        }
        return resolved;
    }

    public String blockKey(Location loc) {
        return loc.getWorld().getName() + '|' + loc.getBlockX() + '|' + loc.getBlockY() + '|' + loc.getBlockZ();
    }

    public void markPlaced(Location loc) {
        if (placedBlocks.add(blockKey(loc))) {
            placedDirty = true;
        }
    }

    public void unmarkPlaced(Location loc) {
        if (placedBlocks.remove(blockKey(loc))) {
            placedDirty = true;
        }
    }

    public boolean isPlayerPlaced(Location loc) {
        return placedBlocks.contains(blockKey(loc));
    }

    public long getMillisUntilRotation() {
        long interval = plugin.getConfig().getLong("arena.rotate-interval-seconds", 7200) * 1000L;
        long elapsed = System.currentTimeMillis() - lastRotation;
        return Math.max(0, interval - elapsed);
    }

    public String getActiveArenaId() {
        return activeArenaId;
    }
}
