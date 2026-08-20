package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoadoutManager {

    public static final String NO_COOLDOWN_PERMISSION = "unstablecore.loadout.nocooldown";
    public static final String KIT_NO_COOLDOWN_PERMISSION = "unstablecore.kit.nocooldown";

    private final UnstableCore plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<UUID, Long> noCooldownUntil = new ConcurrentHashMap<>();

    public LoadoutManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        lastUse.clear();
        noCooldownUntil.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        lastUse.putAll(db.loadAllLoadouts());
        noCooldownUntil.putAll(db.loadAllLoadoutNoCooldown());
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

    public void resetCooldown(UUID uuid) {
        if (uuid == null) {
            return;
        }
        lastUse.remove(uuid);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.deleteLoadoutCooldown(uuid));
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
        // Duels hand both players an identical, plugin-chosen kit; /loadout re-applies the
        // player's own selected kit straight over the top of it, so without this gate a duelist
        // could simply out-gear their opponent mid-fight for the price of one loadout cooldown.
        // Enforced here rather than only in duels.yml's restricted-commands list so it also
        // covers command aliases and every internal caller (e.g. the /kits auto-equip path).
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInCombatDuel(uuid)) {
            if (sendMessages) {
                MessageUtil.send(player, plugin.getConfigManager().getDuels()
                        .getString("messages.loadout-blocked", "&cYou can't change your kit during a duel."));
            }
            return false;
        }
        // A player owed a post-duel inventory restore (the winner's victory delay, a loser still
        // on the death screen, a crash restore) is about to have their whole inventory replaced.
        // Equipping now would consume the cooldown for a kit the restore immediately overwrites.
        if (plugin.getDuelManager() != null && plugin.getDuelManager().hasPendingPostDuelRestore(uuid)) {
            if (sendMessages) {
                MessageUtil.send(player, plugin.getConfigManager().getDuels()
                        .getString("messages.loadout-blocked", "&cYou can't change your kit during a duel."));
            }
            return false;
        }
        if (plugin.getArenaManager() != null && plugin.getArenaManager().getPlayerArena(uuid) != null) {
            if (sendMessages) {
                MessageUtil.send(player, plugin.getConfigManager().getDuels()
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
        if (kits == null || kits.getSelectedKit(player) == null) {
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-no-kit", Map.of());
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
            }
            if (sendMessages) {
                MessageUtil.sendConfig(player, "loadout-no-kit", Map.of());
            }
            return false;
        }

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
        if (player == null || bypassesCooldown(player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        lastUse.put(uuid, now);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertLoadoutCooldown(uuid, now));
        }
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
    }
}
