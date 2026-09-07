package com.jovanstar.unstablecore.manager;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Caches player skin textures so player heads in GUIs never trigger a Mojang session-server
 * lookup on the main thread's behalf.
 *
 * <p>Paper's {@code CraftMetaSkull} fills in textures asynchronously (on its "Download-N" profile
 * executor) for every skull whose profile has no textures property. A leaderboard page is 45
 * heads, so one {@code /lb} open used to fire 45 concurrent session-server requests; a handful of
 * opens in a minute got the server's IP rate limited (HTTP 429), every head rendered as Steve and
 * the same session server is the one that verifies logins. The Sep 2026 logs show 13,000+ such
 * failures in four days.
 *
 * <p>This manager keeps a uuid -> textures map, populated from three sources:
 * <ul>
 *   <li>online players on join ({@link #capture(Player)}) - their profile already carries signed
 *       textures, no network call needed;</li>
 *   <li>the {@code player_skins} table on startup, so nothing is refetched across restarts;</li>
 *   <li>a single background fetcher that resolves at most one missing UUID per
 *       {@code skins.fetch-interval-millis} (default 1.5 s, ~40/min), far under Mojang's limit.</li>
 * </ul>
 * GUIs call {@link #applySkull(SkullMeta, UUID, String)}: a cached or online player gets a fully
 * textured profile (no lookup); an unknown offline player gets the default head now and is queued,
 * so their real head appears the next time the GUI is built.
 */
public final class SkinCacheManager {

    public record Skin(String value, String signature, long updatedAt) {}

    private static final String TEXTURES = "textures";
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final UnstableCore plugin;
    private final Map<UUID, Skin> cache = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> retryAfter = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService fetcher;
    private final long refreshMillis;
    private final long failRetryMillis;
    private final long failBackoffMillis;
    private volatile long backoffUntil;
    private volatile boolean shuttingDown;

    public SkinCacheManager(UnstableCore plugin) {
        this.plugin = plugin;
        long interval = Math.max(250L, plugin.getConfig().getLong("skins.fetch-interval-millis", 1500L));
        this.refreshMillis = TimeUnit.DAYS.toMillis(Math.max(1L, plugin.getConfig().getLong("skins.refresh-days", 7L)));
        this.failRetryMillis = TimeUnit.MINUTES.toMillis(Math.max(1L, plugin.getConfig().getLong("skins.fail-retry-minutes", 15L)));
        this.failBackoffMillis = TimeUnit.SECONDS.toMillis(Math.max(5L, plugin.getConfig().getLong("skins.fail-backoff-seconds", 60L)));
        this.fetcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UnstableCore-SkinFetcher");
            t.setDaemon(true);
            return t;
        });
        this.fetcher.scheduleWithFixedDelay(this::drainOne, interval, interval, TimeUnit.MILLISECONDS);
        loadFromDatabaseAsync();
    }

    private void loadFromDatabaseAsync() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<DatabaseManager.SkinRow> rows = db.loadAllSkins();
            int added = 0;
            for (DatabaseManager.SkinRow row : rows) {
                if (row.value() == null || row.value().isBlank()) {
                    continue;
                }
                // A join that happened while we were loading is fresher than the DB copy.
                Skin fresh = new Skin(row.value(), row.signature() == null ? "" : row.signature(), row.updatedAt());
                if (cache.putIfAbsent(row.uuid(), fresh) == null) {
                    added++;
                }
            }
            if (added > 0) {
                plugin.getLogger().info("Loaded " + added + " cached player skin(s).");
            }
        });
    }

    /**
     * Records the textures the client presented on login. Safe to call from any thread; the
     * database write is deferred to an async task.
     */
    public void capture(Player player) {
        if (player == null) {
            return;
        }
        PlayerProfile profile;
        try {
            profile = player.getPlayerProfile();
        } catch (Throwable t) {
            return;
        }
        ProfileProperty textures = findTextures(profile);
        if (textures != null) {
            store(player.getUniqueId(), textures.getValue(), textures.getSignature());
        }
    }

    /**
     * Gives the skull a textured profile without ever causing a Mojang lookup on the render path.
     *
     * @return true if the head has real textures now; false if the default head was left in place
     *         (the UUID is queued and will be cached for the next build).
     */
    public boolean applySkull(SkullMeta meta, UUID uuid, String name) {
        if (meta == null || uuid == null) {
            return false;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            // CraftMetaSkull copies the live GameProfile (with textures) for online players.
            meta.setOwningPlayer(online);
            capture(online);
            return true;
        }
        Skin skin = cache.get(uuid);
        if (skin != null) {
            PlayerProfile profile = Bukkit.createProfile(uuid, safeName(name));
            profile.setProperty(new ProfileProperty(TEXTURES, skin.value(), emptyToNull(skin.signature())));
            meta.setPlayerProfile(profile);
            if (System.currentTimeMillis() - skin.updatedAt() > refreshMillis) {
                request(uuid);
            }
            return true;
        }
        request(uuid);
        return false;
    }

    public boolean has(UUID uuid) {
        return uuid != null && cache.containsKey(uuid);
    }

    /** Queues a background fetch for a UUID that is not cached yet. No-op if already known/pending. */
    public void request(UUID uuid) {
        if (uuid == null || shuttingDown) {
            return;
        }
        // Only real Mojang accounts (random v4 UUIDs) can be resolved. Floodgate (v0) and
        // offline-mode (v3) UUIDs always 404 and would just burn the rate budget.
        if (uuid.version() != 4) {
            return;
        }
        Long retry = retryAfter.get(uuid);
        if (retry != null && retry > System.currentTimeMillis()) {
            return;
        }
        if (pending.add(uuid)) {
            queue.offer(uuid);
        }
    }

    private void drainOne() {
        if (shuttingDown || System.currentTimeMillis() < backoffUntil) {
            return;
        }
        UUID uuid = queue.poll();
        if (uuid == null) {
            return;
        }
        try {
            // Skip if a join filled it in while it sat in the queue.
            Skin existing = cache.get(uuid);
            if (existing != null && System.currentTimeMillis() - existing.updatedAt() <= refreshMillis) {
                return;
            }
            PlayerProfile profile = Bukkit.createProfile(uuid);
            boolean ok = profile.complete(true);
            ProfileProperty textures = ok ? findTextures(profile) : null;
            if (textures == null) {
                markFailed(uuid);
                return;
            }
            store(uuid, textures.getValue(), textures.getSignature());
        } catch (Throwable t) {
            markFailed(uuid);
            if (!shuttingDown) {
                plugin.getLogger().log(Level.FINE, "Skin fetch failed for " + uuid, t);
            }
        } finally {
            pending.remove(uuid);
        }
    }

    private void markFailed(UUID uuid) {
        long now = System.currentTimeMillis();
        retryAfter.put(uuid, now + failRetryMillis);
        // One failure usually means the session server is throttling us; stop hammering it.
        backoffUntil = now + failBackoffMillis;
    }

    private void store(UUID uuid, String value, String signature) {
        if (uuid == null || value == null || value.isBlank()) {
            return;
        }
        String sig = signature == null ? "" : signature;
        long now = System.currentTimeMillis();
        Skin old = cache.get(uuid);
        if (old != null && old.value().equals(value) && old.signature().equals(sig)
                && now - old.updatedAt() < TimeUnit.HOURS.toMillis(6)) {
            return;
        }
        cache.put(uuid, new Skin(value, sig, now));
        retryAfter.remove(uuid);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected() || shuttingDown) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertSkin(uuid, value, sig, now));
        } else {
            db.upsertSkin(uuid, value, sig, now);
        }
    }

    public void shutdown() {
        shuttingDown = true;
        queue.clear();
        pending.clear();
        fetcher.shutdownNow();
    }

    private static ProfileProperty findTextures(PlayerProfile profile) {
        if (profile == null) {
            return null;
        }
        for (ProfileProperty property : profile.getProperties()) {
            if (TEXTURES.equals(property.getName()) && property.getValue() != null
                    && !property.getValue().isBlank()) {
                return property;
            }
        }
        return null;
    }

    private static String safeName(String name) {
        return name != null && VALID_NAME.matcher(name).matches() ? name : null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
