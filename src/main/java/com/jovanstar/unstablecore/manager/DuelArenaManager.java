package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns reservation state for the fixed pool of dedicated duel arenas. Deliberately separate
 * from {@link ArenaManager} - it only ever *reads* arenas from there (by id, from the configured
 * duel-eligible list in duels.yml) and never touches FFA rotation/tracking state, so duels can
 * never interfere with the public FFA arena pool.
 */
public final class DuelArenaManager {

    public enum Availability {
        AVAILABLE,
        RESERVED,
        GRACE_PERIOD,
        NO_SPAWNS,
        MISSING
    }

    private final UnstableCore plugin;
    private final Map<String, UUID> reservedBy = new ConcurrentHashMap<>();
    private final Map<String, Long> graceEndsAt = new ConcurrentHashMap<>();

    // duels.yml's arena list, parsed and lower-cased once on first use. Without the cache every
    // availability() call re-reads it - once per arena, every couple of seconds per open map GUI.
    // reload() invalidates it.
    private volatile Set<String> configuredIdsCache;

    public DuelArenaManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    /** Call after duels.yml is reloaded so a changed arena list takes effect immediately. */
    public void reload() {
        configuredIdsCache = null;
    }

    private Set<String> configuredIdSet() {
        Set<String> cached = configuredIdsCache;
        if (cached != null) {
            return cached;
        }
        List<String> raw = plugin.getConfigManager().getDuels().getStringList("arenas");
        Set<String> set = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                set.add(id.toLowerCase(Locale.ROOT));
            }
        }
        configuredIdsCache = set;
        return set;
    }

    public List<String> configuredArenaIds() {
        return new ArrayList<>(configuredIdSet());
    }

    public boolean addArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return false;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(plugin.getConfigManager().getDuels().getStringList("arenas"));
        for (String id : list) {
            if (id.equalsIgnoreCase(key)) {
                return false;
            }
        }
        list.add(key);
        plugin.getConfigManager().getDuels().set("arenas", list);
        plugin.getConfigManager().saveDuels();
        configuredIdsCache = null;
        return true;
    }

    public boolean removeArena(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return false;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(plugin.getConfigManager().getDuels().getStringList("arenas"));
        boolean removed = list.removeIf(id -> id.equalsIgnoreCase(key));
        if (removed) {
            plugin.getConfigManager().getDuels().set("arenas", list);
            plugin.getConfigManager().saveDuels();
            configuredIdsCache = null;
        }
        return removed;
    }

    public Arena resolve(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return null;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        if (!configuredIdSet().contains(key)) {
            return null;
        }
        return plugin.getArenaManager().getArena(key);
    }

    public List<Arena> eligibleArenas() {
        List<Arena> out = new ArrayList<>();
        for (String id : configuredIdSet()) {
            Arena arena = plugin.getArenaManager().getArena(id);
            if (arena != null) {
                out.add(arena);
            }
        }
        return out;
    }

    public Availability availability(String arenaId) {
        if (arenaId == null) {
            return Availability.MISSING;
        }
        Arena arena = resolve(arenaId);
        return availability(arenaId.toLowerCase(Locale.ROOT), arena);
    }

    /** Skips the config-membership re-check when the caller already has a resolved Arena in hand. */
    public Availability availability(Arena arena) {
        if (arena == null) {
            return Availability.MISSING;
        }
        return availability(arena.getId(), arena);
    }

    private Availability availability(String key, Arena arena) {
        if (arena == null || !arena.hasCenter()) {
            return Availability.MISSING;
        }
        Long graceEnd = graceEndsAt.get(key);
        if (graceEnd != null) {
            if (System.currentTimeMillis() < graceEnd) {
                return Availability.GRACE_PERIOD;
            }
            graceEndsAt.remove(key);
        }
        if (reservedBy.containsKey(key)) {
            return Availability.RESERVED;
        }
        return Availability.AVAILABLE;
    }

    public boolean isAvailable(String arenaId) {
        return availability(arenaId) == Availability.AVAILABLE;
    }

    /**
     * Atomically claims the arena for {@code duelId} - fails if another duel already holds it or
     * it's still in its post-duel grace window. Each arena has exactly one fixed spawn1/spawn2
     * pair (see runSetupSequence), so without this exclusivity check two duels started in the
     * same tick would teleport four different players onto the same two coordinates.
     */
    public boolean reserve(String arenaId, UUID duelId) {
        if (arenaId == null || duelId == null || !isAvailable(arenaId)) {
            return false;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        return reservedBy.putIfAbsent(key, duelId) == null;
    }

    /** Frees the arena immediately - used for setup-rollback/shutdown paths that skip grace. */
    public void release(String arenaId) {
        if (arenaId == null) {
            return;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        reservedBy.remove(key);
        graceEndsAt.remove(key);
    }

    /** Duel has ended - arena stays unavailable to new duels until the grace window passes. */
    public void enterGrace(String arenaId, long graceEndsAtMs) {
        if (arenaId == null) {
            return;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        reservedBy.remove(key);
        graceEndsAt.put(key, graceEndsAtMs);
    }

    public UUID reservedByDuel(String arenaId) {
        return arenaId == null ? null : reservedBy.get(arenaId.toLowerCase(Locale.ROOT));
    }

    /** Releases every reservation - used once at startup after crash-recovery cleanup. */
    public void releaseAll() {
        reservedBy.clear();
        graceEndsAt.clear();
    }
}
