package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages matchmaking queues for Casual (unranked) and Ranked duels.
 * A periodic task scans waiting players and automatically spins up matches
 * with a shared random kit when valid pairings and duel arenas are ready.
 */
public final class DuelQueueManager {

    public enum QueueType {
        CASUAL("Casual", "&a&lCasual Queue"),
        RANKED("Ranked", "&b&lRanked Queue");

        private final String displayName;
        private final String formattedName;

        QueueType(String displayName, String formattedName) {
            this.displayName = displayName;
            this.formattedName = formattedName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getFormattedName() {
            return formattedName;
        }
    }

    public record QueueEntry(UUID uuid, QueueType type, long joinedAt) {
    }

    private final UnstableCore plugin;
    private final Map<UUID, QueueEntry> queue = new ConcurrentHashMap<>();
    private BukkitTask matchmakerTask;

    public DuelQueueManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long interval = Math.max(10L, plugin.getConfigManager().getDuels().getLong("queue.match-interval-ticks", 20L));
        matchmakerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMatchmaker, interval, interval);
    }

    public void stop() {
        if (matchmakerTask != null) {
            matchmakerTask.cancel();
            matchmakerTask = null;
        }
        queue.clear();
    }

    public boolean isInQueue(UUID uuid) {
        return uuid != null && queue.containsKey(uuid);
    }

    public QueueType getQueueType(UUID uuid) {
        QueueEntry entry = uuid == null ? null : queue.get(uuid);
        return entry == null ? null : entry.type();
    }

    public int getQueueCount(QueueType type) {
        int count = 0;
        for (QueueEntry entry : queue.values()) {
            if (entry.type() == type) {
                count++;
            }
        }
        return count;
    }

    public boolean joinQueue(Player player, QueueType type) {
        if (player == null || type == null) {
            return false;
        }
        DuelManager duelMgr = plugin.getDuelManager();
        if (duelMgr == null || !duelMgr.enabled()) {
            MessageUtil.send(player, plugin.getConfigManager().getDuels().getString("messages.disabled", "&cDuels are currently disabled."));
            return false;
        }
        if (duelMgr.isInDuel(player.getUniqueId()) || duelMgr.isInGrace(player.getUniqueId())) {
            MessageUtil.send(player, plugin.getConfigManager().getDuels().getString("messages.already-in-duel", "&cYou're already in a duel."));
            return false;
        }
        if (player.isDead()) {
            MessageUtil.send(player, "&cYou cannot join the queue while dead.");
            return false;
        }
        if (plugin.getCombatListener() != null && plugin.getCombatListener().isCombatTagged(player.getUniqueId())) {
            MessageUtil.send(player, plugin.getConfigManager().getDuels().getString("messages.challenger-busy", "&cYou cannot join the queue while in combat."));
            return false;
        }

        QueueEntry existing = queue.get(player.getUniqueId());
        if (existing != null && existing.type() == type) {
            MessageUtil.send(player, "&eYou are already in the " + type.getFormattedName() + "&e!");
            return false;
        }

        queue.put(player.getUniqueId(), new QueueEntry(player.getUniqueId(), type, System.currentTimeMillis()));
        MessageUtil.send(player, "&aYou joined the " + type.getFormattedName() + "&a! Looking for an opponent...");
        return true;
    }

    public boolean leaveQueue(Player player) {
        if (player == null) {
            return false;
        }
        QueueEntry removed = queue.remove(player.getUniqueId());
        if (removed != null) {
            MessageUtil.send(player, "&eYou left the " + removed.type().getFormattedName() + "&e.");
            return true;
        }
        MessageUtil.send(player, "&cYou are not in any duel queue.");
        return false;
    }

    public void removeSilent(UUID uuid) {
        if (uuid != null) {
            queue.remove(uuid);
        }
    }

    // ----------------------------------------------------------------------------------
    // Matchmaker Tick Loop
    // ----------------------------------------------------------------------------------

    private void tickMatchmaker() {
        DuelManager duelMgr = plugin.getDuelManager();
        if (duelMgr == null || !duelMgr.enabled()) {
            return;
        }

        // Clean up offline/invalid queue entries
        Iterator<Map.Entry<UUID, QueueEntry>> it = queue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, QueueEntry> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || p.isDead() || duelMgr.isInDuel(e.getKey())) {
                it.remove();
            }
        }

        // Process Casual Queue
        processCasualQueue(duelMgr);

        // Process Ranked Queue
        processRankedQueue(duelMgr);
    }

    private void processCasualQueue(DuelManager duelMgr) {
        List<Player> casualPlayers = new ArrayList<>();
        for (QueueEntry entry : queue.values()) {
            if (entry.type() != QueueType.CASUAL) continue;
            Player p = Bukkit.getPlayer(entry.uuid());
            if (isValidQueuedPlayer(p, duelMgr)) {
                casualPlayers.add(p);
            }
        }

        while (casualPlayers.size() >= 2) {
            Arena arena = findAvailableArena(duelMgr);
            if (arena == null) {
                break; // No available arenas right now
            }
            Player p1 = casualPlayers.remove(0);
            Player p2 = casualPlayers.remove(0);
            queue.remove(p1.getUniqueId());
            queue.remove(p2.getUniqueId());

            Kit randomKit = plugin.getKitManager().getRandomKit();
            String kitId = randomKit != null ? randomKit.getId() : duelMgr.defaultKitId();
            String kitDisplayName = randomKit != null ? MessageUtil.strip(randomKit.getDisplayName()) : kitId;

            notifyMatchFound(p1, p2, QueueType.CASUAL, kitDisplayName);
            duelMgr.createQueueMatch(p1, p2, arena.getId(), kitId, false);
        }
    }

    private void processRankedQueue(DuelManager duelMgr) {
        List<QueueEntry> rankedEntries = new ArrayList<>();
        for (QueueEntry entry : queue.values()) {
            if (entry.type() != QueueType.RANKED) continue;
            Player p = Bukkit.getPlayer(entry.uuid());
            if (isValidQueuedPlayer(p, duelMgr)) {
                rankedEntries.add(entry);
            }
        }

        long now = System.currentTimeMillis();
        DuelStatsManager statsMgr = duelMgr.getDuelStatsManager();

        for (int i = 0; i < rankedEntries.size(); i++) {
            QueueEntry e1 = rankedEntries.get(i);
            Player p1 = Bukkit.getPlayer(e1.uuid());
            if (p1 == null || !queue.containsKey(e1.uuid())) continue;

            int elo1 = statsMgr.getElo(e1.uuid());
            long waitSecs1 = (now - e1.joinedAt()) / 1000L;
            // Expand Elo range by 50 every 5s, start at 100, max 500
            int maxEloDiff1 = (int) Math.min(500, 100 + (waitSecs1 / 5) * 50);

            for (int j = i + 1; j < rankedEntries.size(); j++) {
                QueueEntry e2 = rankedEntries.get(j);
                Player p2 = Bukkit.getPlayer(e2.uuid());
                if (p2 == null || !queue.containsKey(e2.uuid())) continue;

                int elo2 = statsMgr.getElo(e2.uuid());
                int diff = Math.abs(elo1 - elo2);

                long waitSecs2 = (now - e2.joinedAt()) / 1000L;
                int maxEloDiff2 = (int) Math.min(500, 100 + (waitSecs2 / 5) * 50);
                int allowedDiff = Math.max(maxEloDiff1, maxEloDiff2);

                if (diff <= allowedDiff) {
                    Arena arena = findAvailableArena(duelMgr);
                    if (arena == null) {
                        return; // No arena available
                    }
                    queue.remove(e1.uuid());
                    queue.remove(e2.uuid());

                    Kit randomKit = plugin.getKitManager().getRandomKit();
                    String kitId = randomKit != null ? randomKit.getId() : duelMgr.defaultKitId();
                    String kitDisplayName = randomKit != null ? MessageUtil.strip(randomKit.getDisplayName()) : kitId;

                    notifyMatchFound(p1, p2, QueueType.RANKED, kitDisplayName);
                    duelMgr.createQueueMatch(p1, p2, arena.getId(), kitId, true);
                    break;
                }
            }
        }
    }

    private boolean isValidQueuedPlayer(Player p, DuelManager duelMgr) {
        if (p == null || !p.isOnline() || p.isDead()) {
            return false;
        }
        if (duelMgr.isInDuel(p.getUniqueId()) || duelMgr.isInGrace(p.getUniqueId())) {
            return false;
        }
        if (plugin.getCombatListener() != null && plugin.getCombatListener().isCombatTagged(p.getUniqueId())) {
            return false;
        }
        return true;
    }

    private Arena findAvailableArena(DuelManager duelMgr) {
        DuelArenaManager arenaMgr = duelMgr.getDuelArenaManager();
        for (Arena arena : arenaMgr.eligibleArenas()) {
            if (arenaMgr.availability(arena.getId()) == DuelArenaManager.Availability.AVAILABLE) {
                return arena;
            }
        }
        return null;
    }

    private void notifyMatchFound(Player p1, Player p2, QueueType type, String kitDisplayName) {
        String msg1 = "&a&lMatch found! &7Opponent: &f" + p2.getName() + " &7(" + type.getFormattedName() + "&7) &8| &7Kit: &e" + kitDisplayName;
        String msg2 = "&a&lMatch found! &7Opponent: &f" + p1.getName() + " &7(" + type.getFormattedName() + "&7) &8| &7Kit: &e" + kitDisplayName;
        MessageUtil.send(p1, msg1);
        MessageUtil.send(p2, msg2);
    }
}
