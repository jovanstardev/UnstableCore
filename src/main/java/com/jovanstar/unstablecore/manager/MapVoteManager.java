package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.VoteGui;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class MapVoteManager {

    private final UnstableCore plugin;

    private boolean voting;
    private long voteEndsAt;
    private final List<String> candidates = new ArrayList<>();
    private final Map<UUID, String> votes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> voteWeights = new ConcurrentHashMap<>();
    private BukkitTask endTask;
    private BukkitTask refreshTask;
    private long lastCompletedRotation = -1L;
    private final Map<UUID, Long> lastForceOpenMs = new ConcurrentHashMap<>();

    public MapVoteManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("arena.vote.enabled", true);
    }

    public boolean isVoting() {
        return voting;
    }

    public long getMillisUntilVoteEnds() {
        if (!voting) {
            return 0L;
        }
        return Math.max(0L, voteEndsAt - System.currentTimeMillis());
    }

    public List<String> getCandidates() {
        return Collections.unmodifiableList(candidates);
    }

    public int getVotes(String arenaId) {
        if (arenaId == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<UUID, String> e : votes.entrySet()) {
            if (arenaId.equalsIgnoreCase(e.getValue())) {
                count += voteWeights.getOrDefault(e.getKey(), 1);
            }
        }
        return count;
    }

    public int getTotalVotes() {
        int total = 0;
        for (UUID uuid : votes.keySet()) {
            total += voteWeights.getOrDefault(uuid, 1);
        }
        return total;
    }

    public int voteWeight(Player player) {
        if (player == null) {
            return 1;
        }
        String perm = plugin.getConfig().getString("arena.vote.double-vote-permission", "unstablecore.vote.2x");
        if (perm != null && !perm.isBlank() && player.hasPermission(perm.trim())) {
            return Math.max(2, plugin.getConfig().getInt("arena.vote.double-vote-weight", 2));
        }
        return 1;
    }

    public double getPercent(String arenaId) {
        int total = getTotalVotes();
        if (total <= 0) {
            return 0.0;
        }
        return (getVotes(arenaId) * 100.0) / total;
    }

    public String getVote(UUID uuid) {
        return votes.get(uuid);
    }

    public void tick() {
        if (!isEnabled() || voting) {
            return;
        }

        long lastRotation = plugin.getArenaManager().getLastRotation();
        if (lastRotation > 0 && lastRotation == lastCompletedRotation) {
            return;
        }

        long until = plugin.getArenaManager().getMillisUntilRotation();
        long leadMs = Math.max(5L, plugin.getConfig().getLong("arena.vote.lead-seconds", 60L)) * 1000L;
        if (until > 0 && until <= leadMs) {
            startVote();
        }
    }

    public void startVote() {
        if (voting) {
            return;
        }
        List<Arena> pool = plugin.getArenaManager().getRotatableArenas(true);
        if (pool.isEmpty()) {
            return;
        }

        int max = Math.max(1, Math.min(4, plugin.getConfig().getInt("arena.vote.max-maps", 4)));
        Collections.shuffle(pool, ThreadLocalRandom.current());
        if (pool.size() > max) {
            pool = new ArrayList<>(pool.subList(0, max));
        }

        candidates.clear();
        votes.clear();
        voteWeights.clear();
        for (Arena arena : pool) {
            candidates.add(arena.getId());
        }
        if (candidates.isEmpty()) {
            return;
        }

        int duration = Math.max(5, plugin.getConfig().getInt("arena.vote.duration-seconds", 45));
        voting = true;
        voteEndsAt = System.currentTimeMillis() + duration * 1000L;

        broadcastVoteStart(duration);
        playSound(plugin.getConfig().getString("arena.vote.start-sound", "BLOCK_NOTE_BLOCK_PLING"));

        if (endTask != null) {
            endTask.cancel();
        }
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::endVote, duration * 20L);

        if (refreshTask != null) {
            refreshTask.cancel();
        }
        long refresh = Math.max(10L, plugin.getConfig().getLong("arena.vote.refresh-ticks", 20L));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenGuis, refresh, refresh);
    }

    /**
     * Sends a single clickable chat line announcing the vote instead of forcing the GUI open on
     * everyone's screen. Clicking it runs /mapvote, which opens the map-select GUI for whoever
     * clicked - the GUI itself is unchanged, only how it's first triggered.
     */
    private void broadcastVoteStart(int duration) {
        String template = plugin.getConfig().getString("arena.vote.start-broadcast", "");
        if (template.isBlank()) {
            return;
        }
        String hoverTemplate = plugin.getConfig().getString("arena.vote.start-hover", "&7Click to open the map vote menu");
        Component message = MessageUtil.parse(MessageUtil.apply(template, Map.of("seconds", String.valueOf(duration))))
                .clickEvent(ClickEvent.runCommand("/mapvote"))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(hoverTemplate)));

        java.util.function.Predicate<Player> filter = plugin.getSettingsManager().filter(SettingsManager.ROTATION_ALERTS);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (filter == null || filter.test(player)) {
                player.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public void castVote(Player player, String arenaId) {
        if (!voting || player == null || arenaId == null) {
            return;
        }
        String id = arenaId.toLowerCase(Locale.ROOT);
        if (!candidates.contains(id)) {
            return;
        }
        int weight = voteWeight(player);
        votes.put(player.getUniqueId(), id);
        voteWeights.put(player.getUniqueId(), weight);
        Arena arena = plugin.getArenaManager().getArena(id);
        String name = arena != null ? arena.getDisplayName() : id;
        MessageUtil.send(player, MessageUtil.apply(
                plugin.getConfig().getString("arena.vote.voted-message", "&aVoted for &f{map}&a."),
                Map.of(
                        "map", MessageUtil.strip(name),
                        "percent", formatPercent(getPercent(id)),
                        "weight", String.valueOf(weight)
                )
        ));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (voting) {
                refreshOpenGuis();
            }
        });
    }

    public boolean hasVoted(UUID uuid) {
        return uuid != null && votes.containsKey(uuid);
    }

    public void keepOpenUntilVoted(Player player) {
        if (!voting || player == null || !player.isOnline()) {
            return;
        }
        if (hasVoted(player.getUniqueId())) {
            return;
        }
        // Re-opening a GUI the player just dismissed is already aggressive; doing it to someone
        // mid-fight takes their screen away while they are being hit and cannot act. Anyone in a
        // duel or combat-tagged in FFA is left alone - they simply don't get a forced vote.
        if (plugin.getDuelManager() != null
                && (plugin.getDuelManager().isInDuel(player.getUniqueId())
                || plugin.getDuelManager().isInGrace(player.getUniqueId()))) {
            return;
        }
        if (plugin.getCombatListener() != null
                && plugin.getCombatListener().isCombatTagged(player.getUniqueId())) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof VoteGui) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastForceOpenMs.get(player.getUniqueId());
        if (last != null && now - last < 750L) {
            return;
        }
        lastForceOpenMs.put(player.getUniqueId(), now);
        VoteGui.open(plugin, player);
    }

    /** Opens the map-select GUI for a player - used by /mapvote (chat click) and on join mid-vote. */
    public void openFor(Player player) {
        if (voting && player != null) {
            VoteGui.open(plugin, player);
        }
    }

    private void endVote() {
        if (!voting) {
            return;
        }
        voting = false;
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }

        closeOpenGuis();

        String winnerId = pickWinner();
        Arena winner = winnerId == null ? null : plugin.getArenaManager().getArena(winnerId);
        if (winner == null) {
            plugin.getArenaManager().rotateActive(true);
        } else {
            MessageUtil.broadcastFiltered(plugin.getConfig().getString("arena.vote.end-broadcast", ""), Map.of(
                    "map", winner.getDisplayName(),
                    "percent", formatPercent(getPercent(winnerId)),
                    "votes", String.valueOf(getVotes(winnerId))
            ), plugin.getSettingsManager().filter(SettingsManager.ROTATION_ALERTS));
            playSound(plugin.getConfig().getString("arena.vote.end-sound", "UI_TOAST_CHALLENGE_COMPLETE"));
            plugin.getArenaManager().rotateActive(true, winnerId);
        }

        lastCompletedRotation = plugin.getArenaManager().getLastRotation();
        candidates.clear();
        votes.clear();
        voteWeights.clear();
        voteEndsAt = 0L;
        lastForceOpenMs.clear();
    }

    private String pickWinner() {
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, Integer> tallies = new LinkedHashMap<>();
        for (String id : candidates) {
            tallies.put(id, 0);
        }
        for (Map.Entry<UUID, String> e : votes.entrySet()) {
            int weight = voteWeights.getOrDefault(e.getKey(), 1);
            tallies.computeIfPresent(e.getValue(), (k, v) -> v + weight);
        }

        int best = -1;
        List<String> tied = new ArrayList<>();
        for (Map.Entry<String, Integer> e : tallies.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                tied.clear();
                tied.add(e.getKey());
            } else if (e.getValue() == best) {
                tied.add(e.getKey());
            }
        }
        if (tied.isEmpty()) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        return tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
    }

    private void refreshOpenGuis() {
        if (!voting) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof VoteGui gui) {
                gui.refresh();
            }
        }
    }

    private void closeOpenGuis() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof VoteGui) {
                player.closeInventory();
            }
        }
    }

    public void cancel() {
        voting = false;
        candidates.clear();
        votes.clear();
        voteWeights.clear();
        voteEndsAt = 0L;
        lastForceOpenMs.clear();
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        closeOpenGuis();
    }

    public static String formatPercent(double percent) {
        if (percent == (long) percent) {
            return String.valueOf((long) percent);
        }
        return String.format(Locale.US, "%.1f", percent);
    }

    private void playSound(String name) {
        try {
            Sound sound = Sound.valueOf(name);
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.playSound(player.getLocation(), sound, 1f, 1f);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }
}
