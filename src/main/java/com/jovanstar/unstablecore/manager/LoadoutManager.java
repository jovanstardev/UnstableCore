package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoadoutManager {

    public static final String NO_COOLDOWN_PERMISSION = "unstablecore.loadout.nocooldown";
    /**
     * Kept out of the {@code unstablecore.kit.*} namespace on purpose. Kit unlocks live there
     * ({@code unstablecore.kit.<id>}, see KitManager's default permission), so naming the bypass
     * {@code unstablecore.kit.nocooldown} meant any rank granted {@code unstablecore.kit.*} to
     * unlock every kit silently also got permanent cooldown bypass.
     */
    public static final String KIT_NO_COOLDOWN_PERMISSION = "unstablecore.loadout.kit-nocooldown";

    private final UnstableCore plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    /** uuid -> (kitId -> last claim millis), for kits that declare their own cooldown. */
    private final Map<UUID, Map<String, Long>> kitLastUse = new ConcurrentHashMap<>();
    private final Map<UUID, Long> noCooldownUntil = new ConcurrentHashMap<>();

    public LoadoutManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        DatabaseManager db = plugin.getDatabaseManager();
        // Read first, and only replace what is in memory once the data is actually in hand.
        // Clearing up front meant a /uc reload while the database was unreachable left all three
        // maps empty and returned - which reads as "nobody is on cooldown" and hands the whole
        // server a free kit claim.
        if (db == null || !db.isConnected()) {
            plugin.getLogger().warning(
                    "Database unavailable while loading loadout cooldowns - keeping the cooldowns "
                            + "already in memory rather than clearing them.");
            return;
        }
        Map<UUID, Long> freshLast = db.loadAllLoadouts();
        Map<UUID, Map<String, Long>> freshKit = db.loadAllKitCooldowns();
        Map<UUID, Long> freshNoCooldown = db.loadAllLoadoutNoCooldown();

        lastUse.clear();
        lastUse.putAll(freshLast);
        kitLastUse.clear();
        kitLastUse.putAll(freshKit);
        noCooldownUntil.clear();
        noCooldownUntil.putAll(freshNoCooldown);
        pruneExpired();
    }

    public void save() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        pruneExpired();
        db.saveAllLoadouts(lastUse, cooldownMillis());
        db.saveAllLoadoutNoCooldown(noCooldownUntil);
        // Rows are written incrementally as kits are claimed, so the table needs its own sweep -
        // saveAllLoadouts only prunes loadout_cooldowns.
        db.pruneKitCooldowns(System.currentTimeMillis() - maxCooldownMillis());
    }

    public long cooldownMillis() {
        return Math.max(0L, plugin.getConfig().getLong("loadout.cooldown-seconds", 1800L)) * 1000L;
    }

    public boolean bypassesCooldown(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission(NO_COOLDOWN_PERMISSION)
                || player.hasPermission(KIT_NO_COOLDOWN_PERMISSION)) {
            return true;
        }
        Long until = noCooldownUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public long remainingMillis(UUID uuid) {
        if (uuid == null) {
            return 0L;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && bypassesCooldown(online)) {
            return 0L;
        }
        Long until = noCooldownUntil.get(uuid);
        if (until != null && until > System.currentTimeMillis()) {
            return 0L;
        }
        Long last = lastUse.get(uuid);
        if (last == null) {
            return 0L;
        }
        long remain = (last + cooldownMillis()) - System.currentTimeMillis();
        if (remain <= 0L) {
            return 0L;
        }
        return remain;
    }

    /**
     * Seconds this kit is locked for after a claim: the kit's own {@code cooldown}, or
     * {@code loadout.cooldown-seconds} when the kit does not declare one.
     */
    public long kitCooldownMillis(Kit kit) {
        if (kit == null || kit.getCooldownSeconds() <= 0) {
            return cooldownMillis();
        }
        return kit.getCooldownSeconds() * 1000L;
    }

    /**
     * Time left on <em>this kit's</em> own cooldown. Independent of the shared cooldown, so a
     * player waiting out a 10-minute kit can still claim a cheaper one - subject to
     * {@link #remainingMillis}, which still rate-limits claiming in general.
     */
    public long kitRemainingMillis(UUID uuid, Kit kit) {
        if (uuid == null || kit == null) {
            return 0L;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && bypassesCooldown(online)) {
            return 0L;
        }
        Map<String, Long> byKit = kitLastUse.get(uuid);
        Long last = byKit == null ? null : byKit.get(kit.getId());
        if (last == null) {
            return 0L;
        }
        long remain = (last + kitCooldownMillis(kit)) - System.currentTimeMillis();
        return Math.max(0L, remain);
    }

    /** Longest of the two gates, for display: what the player is actually waiting on. */
    public long effectiveRemainingMillis(UUID uuid, Kit kit) {
        return Math.max(remainingMillis(uuid), kitRemainingMillis(uuid, kit));
    }

    private void markKitUsed(Player player, Kit kit) {
        if (player == null || kit == null || bypassesCooldown(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        kitLastUse.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>()).put(kit.getId(), now);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            String kitId = kit.getId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertKitCooldown(uuid, kitId, now));
        }
    }

    public void resetCooldown(UUID uuid) {
        if (uuid == null) {
            return;
        }
        lastUse.remove(uuid);
        kitLastUse.remove(uuid);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                db.deleteLoadoutCooldown(uuid);
                db.deleteKitCooldowns(uuid);
            });
        }
    }

    public void grantNoCooldown(UUID uuid, long seconds) {
        if (uuid == null || seconds <= 0L) {
            return;
        }
        resetCooldown(uuid);
        long until = System.currentTimeMillis() + (seconds * 1000L);
        noCooldownUntil.merge(uuid, until, Math::max);
        long finalUntil = noCooldownUntil.get(uuid);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertLoadoutNoCooldown(uuid, finalUntil));
        }
    }

    public long noCooldownRemainingMillis(UUID uuid) {
        if (uuid == null) {
            return 0L;
        }
        Long until = noCooldownUntil.get(uuid);
        if (until == null) {
            return 0L;
        }
        long remain = until - System.currentTimeMillis();
        if (remain <= 0L) {
            noCooldownUntil.remove(uuid);
            return 0L;
        }
        return remain;
    }

    public boolean tryGive(Player player) {
        return tryGive(player, true);
    }

    /**
     * Whether a loadout claim would be allowed for this player right now, without consuming the
     * cooldown or touching their inventory. This is the single precondition gate shared by
     * {@link #tryGive} and by callers that must destroy something before claiming (the kit
     * confirm screen), so "may I claim" and "claim" can never disagree within a tick.
     */
    public boolean canClaim(Player player, boolean sendMessages) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        // Both the cached tag and the real location: the tag can be null while the body is inside
        // an arena (a relog with join.spawn-on-join off, or the one-tick gap before
        // teleportToArena writes the tag), and trusting the tag alone let a kit be claimed there.
        if (plugin.getArenaManager() != null
                && (plugin.getArenaManager().getPlayerArena(uuid) != null
                || plugin.getArenaManager().resolveArenaAt(player.getLocation()) != null)) {
            if (sendMessages) {
                MessageUtil.send(player, plugin.getConfig()
                        .getString("messages.loadout-arena-blocked", "&cYou can't change your kit while inside an arena."));
            }
            return false;
        }
        long remain = remainingMillis(uuid);
        if (remain > 0) {
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-cooldown", Map.of(
                        "time", EventManager.formatDurationMillis(remain)
                ));
            }
            return false;
        }
        KitManager kits = plugin.getKitManager();
        Kit selected = kits == null ? null : kits.getSelectedKit(player);
        if (selected == null) {
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-no-kit", Map.of());
            }
            return false;
        }
        // Second, independent gate: this particular kit's own cooldown. The shared one above
        // limits how often a player may claim anything at all - without it, per-kit timers alone
        // would let someone claim every kit back to back and dump 20-odd loadouts on the floor.
        long kitRemain = kitRemainingMillis(uuid, selected);
        if (kitRemain > 0) {
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-kit-cooldown", Map.of(
                        "time", EventManager.formatDurationMillis(kitRemain),
                        "kit", selected.getDisplayName()
                ));
            }
            return false;
        }
        return true;
    }

    public boolean tryGive(Player player, boolean sendMessages) {
        if (!canClaim(player, sendMessages)) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        KitManager kits = plugin.getKitManager();

        Kit claimed = kits == null ? null : kits.getSelectedKit(player);
        boolean bypass = bypassesCooldown(player);
        long now = System.currentTimeMillis();
        if (!bypass) {
            lastUse.put(uuid, now);
            DatabaseManager db = plugin.getDatabaseManager();
            if (db != null && db.isConnected()) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertLoadoutCooldown(uuid, now));
            }
        }

        if (!kits.applyLoadout(player)) {
            if (!bypass) {
                lastUse.remove(uuid);
                Map<String, Long> byKit = kitLastUse.get(uuid);
                if (byKit != null && claimed != null) {
                    byKit.remove(claimed.getId());
                }
            }
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-no-kit", Map.of());
            }
            return false;
        }

        markKitUsed(player, claimed);
        if (sendMessages) {
            MessageUtil.sendConfig(player, "loadout-given", Map.of());
        }
        return true;
    }

    /**
     * Starts the normal loadout cooldown for a player who was just handed a full kit through some
     * path other than {@link #tryGive} (currently only the arena "empty inventory" safety net).
     * That path checked the cooldown but never consumed it, so re-gearing was free and unlimited:
     * drop everything, click the arena join button again, receive another complete kit, repeat.
     * No-op for players who legitimately bypass the cooldown, matching tryGive's behaviour.
     */
    public void markUsed(Player player) {
        markUsed(player, null);
    }

    /** @param kit the kit actually handed out, so its own cooldown starts too; null for none. */
    public void markUsed(Player player, Kit kit) {
        if (player == null || bypassesCooldown(player)) {
            return;
        }
        markKitUsed(player, kit);
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lastUse.put(uuid, now);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertLoadoutCooldown(uuid, now));
        }
    }

    /**
     * Longest cooldown any claim can currently impose - the shared one, or the largest per-kit
     * override if that is bigger. A kit entry older than this cannot still be blocking anything,
     * which is what makes it safe to prune.
     */
    private long maxCooldownMillis() {
        KitManager kits = plugin.getKitManager();
        long widest = kits == null ? 0L : kits.getMaxKitCooldownSeconds() * 1000L;
        return Math.max(cooldownMillis(), widest);
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        long cd = cooldownMillis();
        Iterator<Map.Entry<UUID, Long>> lastIt = lastUse.entrySet().iterator();
        while (lastIt.hasNext()) {
            Map.Entry<UUID, Long> e = lastIt.next();
            if (now - e.getValue() >= cd) {
                lastIt.remove();
            }
        }
        Iterator<Map.Entry<UUID, Long>> noIt = noCooldownUntil.entrySet().iterator();
        while (noIt.hasNext()) {
            Map.Entry<UUID, Long> e = noIt.next();
            if (e.getValue() <= now) {
                noIt.remove();
            }
        }
        // kitLastUse holds one entry per (player, kit) pair and is loaded whole at startup, so
        // without this it grows for every kit every player has ever claimed and is never released.
        long kitCutoff = now - maxCooldownMillis();
        Iterator<Map.Entry<UUID, Map<String, Long>>> kitIt = kitLastUse.entrySet().iterator();
        while (kitIt.hasNext()) {
            Map.Entry<UUID, Map<String, Long>> e = kitIt.next();
            e.getValue().values().removeIf(v -> v == null || v <= kitCutoff);
            if (e.getValue().isEmpty()) {
                kitIt.remove();
            }
        }
    }
}
