package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public DuelArenaManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public List<String> configuredArenaIds() {
        List<String> ids = plugin.getConfigManager().getDuels().getStringList("arenas");
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public Arena resolve(String arenaId) {
        if (arenaId == null || arenaId.isBlank()) {
            return null;
        }
        String key = arenaId.toLowerCase(Locale.ROOT);
        if (!configuredArenaIds().contains(key)) {
            return null;
        }
        return plugin.getArenaManager().getArena(key);
    }

    public List<Arena> eligibleArenas() {
        List<Arena> out = new ArrayList<>();
        for (String id : configuredArenaIds()) {
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
        String key = arenaId.toLowerCase(Locale.ROOT);
        Arena arena = resolve(key);
        if (arena == null || !arena.hasCenter()) {
            return Availability.MISSING;
        }
        return Availability.AVAILABLE;
    }

    public boolean isAvailable(String arenaId) {
        return availability(arenaId) == Availability.AVAILABLE;
    }

    /** No exclusive lock needed: returns true if arena exists and is ready. */
    public boolean reserve(String arenaId, UUID duelId) {
        return isAvailable(arenaId);
    }

    /** No-op for shared arenas. */
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
