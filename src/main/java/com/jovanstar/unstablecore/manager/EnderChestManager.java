package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Server-wide ender chest wipes.
 *
 * <p>Bukkit exposes no ender chest for an offline player, and the contents live inside the
 * {@code EnderItems} tag of {@code world/playerdata/<uuid>.dat}. Rewriting a few hundred of those
 * by hand would risk the inventory, experience and spawn point stored alongside them, so this does
 * not touch player data files at all.
 *
 * <p>Instead a wipe records a timestamp. Everyone online is cleared on the spot; everyone else is
 * cleared the first time they log in afterwards. Each player carries the wipe they have already
 * seen in their own {@link PersistentDataContainer}, which the server persists with the rest of
 * their data, so the check survives restarts without a table or a config section of its own.
 */
public final class EnderChestManager {

    /** data.yml key holding the most recent wipe, in epoch millis. 0 means no wipe has happened. */
    private static final String WIPE_AT_KEY = "echest-wipe-at";

    private final UnstableCore plugin;

    /** Per-player marker for the newest wipe already applied to them. */
    private final NamespacedKey seenKey;

    public EnderChestManager(UnstableCore plugin) {
        this.plugin = plugin;
        this.seenKey = new NamespacedKey(plugin, "echest_wipe_seen");
    }

    /** Epoch millis of the most recent wipe, or 0 if none has been ordered. */
    public long wipeAt() {
        return plugin.getConfigManager().getData().getLong(WIPE_AT_KEY, 0L);
    }

    /**
     * Orders a server-wide wipe.
     *
     * <p>Clears every online player immediately and stamps them as current, so the join handler
     * does not clear them a second time. Offline players are picked up by
     * {@link #applyPendingWipe(Player)} when they next log in.
     *
     * @return how many online players were cleared
     */
    public int wipeAll() {
        long now = System.currentTimeMillis();
        plugin.getConfigManager().getData().set(WIPE_AT_KEY, now);
        plugin.getConfigManager().saveData();

        int cleared = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.getEnderChest().clear();
            markSeen(online, now);
            cleared++;
        }
        return cleared;
    }

    /**
     * Clears one player's ender chest if a wipe has been ordered since they last saw one.
     *
     * <p>Called on join. Players who have never been stamped read 0, so the first wipe after this
     * feature ships reaches everyone rather than silently skipping the existing player base.
     *
     * @return true if this player's ender chest was cleared by a pending wipe
     */
    public boolean applyPendingWipe(Player player) {
        if (player == null) {
            return false;
        }
        long wipeAt = wipeAt();
        if (wipeAt <= 0L) {
            return false;
        }
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        long seen = pdc.getOrDefault(seenKey, PersistentDataType.LONG, 0L);
        if (seen >= wipeAt) {
            return false;
        }
        player.getEnderChest().clear();
        markSeen(player, wipeAt);
        return true;
    }

    private void markSeen(Player player, long stamp) {
        player.getPersistentDataContainer().set(seenKey, PersistentDataType.LONG, stamp);
    }
}
