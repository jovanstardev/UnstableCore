package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.event.DuelAcceptEvent;
import com.jovanstar.unstablecore.event.DuelCreateEvent;
import com.jovanstar.unstablecore.event.DuelEndEvent;
import com.jovanstar.unstablecore.event.DuelForfeitEvent;
import com.jovanstar.unstablecore.event.DuelPayoutEvent;
import com.jovanstar.unstablecore.event.DuelStartEvent;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelInventorySnapshot;
import com.jovanstar.unstablecore.model.DuelResult;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the entire duel lifecycle: request -> accept -> countdown -> active -> end ->
 * grace period, plus disconnect/timeout/forceend side-exits. See DUELS.md for the full spec.
 *
 * <p>Every state-mutating method here is only ever called from the main server thread (command
 * execution, GUI clicks, and scheduled Bukkit tasks all run there); DB work always hops onto an
 * async task and, where it needs to touch this manager's state again, hops back via
 * {@code runTask}. Every terminal operation (escrow, payout, stats, inventory restore, grace
 * release) is guarded by an idempotency flag on {@link Duel} so it can never run twice, and every
 * state change goes through {@link Duel#transition} so exactly one of two racing callers ever
 * wins a given transition.
 */
public final class DuelManager {

    public record WagerPrompt(UUID target, String arenaId, String kitId, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final UnstableCore plugin;
    private final DuelArenaManager duelArenaManager;
    private final DuelStatsManager duelStatsManager;

    private final Map<UUID, Duel> duels = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerDuel = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerGrace = new ConcurrentHashMap<>();
    private final Map<UUID, WagerPrompt> wagerPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRequestSentAt = new ConcurrentHashMap<>();
    private final Map<String, Long> pairCooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, DuelInventorySnapshot> pendingCrashRestores = new ConcurrentHashMap<>();
    /** Loser's pre-duel location queued until their respawn event fires, so we can set the respawn location. */
    private final Map<UUID, Location> pendingRespawnLocations = new ConcurrentHashMap<>();
    private final Map<UUID, DuelInventorySnapshot> pendingRespawnSnapshots = new ConcurrentHashMap<>();
    /**
     * Restores that finishDuel scheduled but that haven't run yet - currently the winner's 3s
     * victory delay. The duel is already terminal and its DB crash-recovery row already deleted
     * by then, so shutdown() has no other way to know a restore is still owed; see the flush at
     * the end of shutdown().
     */
    private final Map<UUID, DuelInventorySnapshot> pendingPostDuelRestores = new ConcurrentHashMap<>();
    private BukkitTask boundaryTask;

    public DuelManager(UnstableCore plugin, DuelArenaManager duelArenaManager, DuelStatsManager duelStatsManager) {
        this.plugin = plugin;
        this.duelArenaManager = duelArenaManager;
        this.duelStatsManager = duelStatsManager;
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle / bootstrap
    // ---------------------------------------------------------------------------------------

    /** Called once from onEnable, after the economy/kit/arena managers are ready. */
    public void start() {
        if (boundaryTask != null) {
            boundaryTask.cancel();
        }
        duelArenaManager.releaseAll();
        // Restores still owed from a previous run, before recovering duel rows (which may queue
        // more). Persisted, so a restart while the owed player is offline no longer loses them.
        for (DatabaseManager.PendingRestoreRow row : plugin.getDatabaseManager().loadAllPendingRestores()) {
            DuelInventorySnapshot snapshot = DuelInventorySnapshot.deserialize(row.snapshot());
            if (snapshot != null) {
                pendingCrashRestores.put(row.uuid(), snapshot);
            } else {
                plugin.getDatabaseManager().deletePendingRestore(row.uuid());
            }
        }
        if (!pendingCrashRestores.isEmpty()) {
            plugin.getLogger().info("[Duel] " + pendingCrashRestores.size()
                    + " pre-duel inventory restore(s) still owed from a previous run; each will be"
                    + " applied when that player next joins.");
        }
        for (DatabaseManager.DuelRow row : plugin.getDatabaseManager().loadAllDuels()) {
            recoverRow(row);
        }
        boundaryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBoundaryCheck, 20L, 20L);
    }

    /**
     * Records that {@code uuid} is owed {@code snapshot} the next time they join, in memory and on
     * disk. The serialization happens on the calling (main) thread so the ItemStacks are read
     * while nothing else can mutate them; only the write is deferred.
     */
    private void queuePersistentRestore(UUID uuid, DuelInventorySnapshot snapshot) {
        if (uuid == null || snapshot == null) {
            return;
        }
        pendingCrashRestores.put(uuid, snapshot);
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        String serialized = snapshot.serialize();
        long now = System.currentTimeMillis();
        if (!plugin.isEnabled()) {
            // Mid-disable: the scheduler rejects new tasks, so write inline rather than lose it.
            db.upsertPendingRestore(uuid, serialized, now);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.upsertPendingRestore(uuid, serialized, now));
    }

    private void clearPersistentRestore(UUID uuid) {
        DatabaseManager db = plugin.getDatabaseManager();
        if (uuid == null || db == null || !db.isConnected()) {
            return;
        }
        if (!plugin.isEnabled()) {
            db.deletePendingRestore(uuid);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> db.deletePendingRestore(uuid));
    }

    private void recoverRow(DatabaseManager.DuelRow row) {
        if (row.escrowed() && !row.payoutDone() && row.wager() > 0) {
            EconomyManager economy = plugin.getEconomyManager();
            boolean okC = economy.isReady() && economy.refund(Bukkit.getOfflinePlayer(row.challenger()), row.wager());
            boolean okT = economy.isReady() && economy.refund(Bukkit.getOfflinePlayer(row.target()), row.wager());
            if (!okC || !okT) {
                plugin.getLogger().severe("[Duel " + row.duelId() + "] crash-recovery refund FAILED for "
                        + (!okC ? row.challenger() + " " : "") + (!okT ? row.target() : "")
                        + " - " + EconomyManager.format(row.wager()) + " coins owed, pay manually.");
            } else {
                plugin.getLogger().warning("[Duel " + row.duelId() + "] recovered after restart - refunded "
                        + EconomyManager.format(row.wager()) + " coins each to " + row.challenger()
                        + " and " + row.target());
            }
        }
        // Inventories can't be restored to an offline player directly - queue them for the
        // moment each participant next joins (see applyPendingCrashRestore / PlayerListener.onJoin).
        queueCrashRestore(row.challenger(), DuelInventorySnapshot.deserialize(row.snapshotChallenger()));
        queueCrashRestore(row.target(), DuelInventorySnapshot.deserialize(row.snapshotTarget()));
        plugin.getDatabaseManager().deleteDuel(row.duelId());
    }

    private void queueCrashRestore(UUID uuid, DuelInventorySnapshot snapshot) {
        if (uuid == null || snapshot == null) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            snapshot.restore(online);
            plugin.getLogger().info("[Duel] restored pre-duel inventory for " + online.getName()
                    + " immediately (already online during recovery).");
        } else {
            queuePersistentRestore(uuid, snapshot);
        }
    }

    /** Called from PlayerListener.onJoin - restores an interrupted duel's pre-duel inventory, if any. */
    public void applyPendingCrashRestore(Player player) {
        DuelInventorySnapshot snapshot = pendingCrashRestores.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        clearPersistentRestore(player.getUniqueId());
        snapshot.restore(player);
        plugin.getLogger().info("[Duel] restored pre-duel inventory for " + player.getName()
                + " after a server restart interrupted their duel.");
        MessageUtil.send(player, "&eYour inventory from an interrupted duel has been restored.");
    }

    /** Called from onDisable - graceful refund/restore for anything still in flight. */
    public void shutdown() {
        if (boundaryTask != null) {
            boundaryTask.cancel();
            boundaryTask = null;
        }
        for (Duel duel : new ArrayList<>(duels.values())) {
            duel.cancelAllTasks();
            DuelState state = duel.getState();
            // REQUESTED never had a snapshot/escrow to touch, and a terminal duel (FINISHED/
            // FORFEITED, still sitting here only because it's mid grace-period) already ran its
            // real payout/inventory resolution through finishDuel - re-running the crash-recovery
            // restore below would clobber whatever the player currently has with their stale
            // pre-duel snapshot. releaseAll() on next start() reclaims its arena regardless.
            if (state == DuelState.REQUESTED || state.isTerminal()) {
                continue;
            }
            if (duel.markInventoryRestored()) {
                restoreIfOnline(duel.getChallenger(), duel.getChallengerSnapshot());
                restoreIfOnline(duel.getTarget(), duel.getTargetSnapshot());
            }
            removeDuelVisibility(duel);
            if (duel.isEscrowed() && duel.getWager() > 0 && duel.markPayoutDone()) {
                depositOrWarn(duel, duel.getChallenger(), duel.getWager(), "shutdown-refund");
                depositOrWarn(duel, duel.getTarget(), duel.getWager(), "shutdown-refund");
                plugin.getLogger().warning("[Duel " + duel.getId() + "] refunded on plugin shutdown mid-duel.");
            }
            if (duel.getArenaId() != null) {
                duelArenaManager.release(duel.getArenaId());
            }
            plugin.getDatabaseManager().deleteDuel(duel.getId().toString());
        }
        // finishDuel hands both players back their pre-duel inventory on a delay - the winner
        // after a 3s victory pause, the loser on respawn - and deletes the duel's DB
        // crash-recovery row immediately. A duel sitting in either window is already terminal, so
        // the loop above skips it (restoring there would clobber a live inventory), and the row is
        // gone, so next boot won't recover it either. Flush whatever is still owed here, or the
        // player keeps the duel kit and their real inventory is lost.
        // restoreIfOnline alone silently dropped these for anyone already offline (a winner who
        // disconnected inside the 3s victory delay, a loser still on the death screen who quit),
        // which is exactly the case that cannot be recovered any other way: the duel's DB row is
        // already deleted, so start() has nothing to replay. Persist those instead so the next
        // join hands the inventory back.
        flushOwedRestores(pendingPostDuelRestores);
        flushOwedRestores(pendingRespawnSnapshots);
        // Restores owed to players who disconnected mid-duel (forfeit loser, winner who dropped
        // during the victory delay). Anyone still online gets theirs now, and their persisted row
        // is cleared so it isn't applied a second time on next boot. Anyone offline keeps their
        // row and is picked up by start()'s recovery pass.
        int owedOffline = 0;
        for (Map.Entry<UUID, DuelInventorySnapshot> entry : pendingCrashRestores.entrySet()) {
            Player online = Bukkit.getPlayer(entry.getKey());
            if (online != null && online.isOnline()) {
                entry.getValue().restore(online);
                clearPersistentRestore(entry.getKey());
            } else {
                owedOffline++;
            }
        }
        if (owedOffline > 0) {
            plugin.getLogger().info("[Duel] " + owedOffline + " pre-duel inventory restore(s) still"
                    + " owed to offline players; persisted and will be applied on their next join.");
        }
        pendingCrashRestores.clear();
        pendingPostDuelRestores.clear();
        pendingRespawnSnapshots.clear();
        duels.clear();
        playerDuel.clear();
        playerGrace.clear();
        wagerPrompts.clear();
        pendingRespawnLocations.clear();
    }

    // ---------------------------------------------------------------------------------------
    // Config helpers
    // ---------------------------------------------------------------------------------------

    private FileConfiguration cfg() {
        return plugin.getConfigManager().getDuels();
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    public String defaultKitId() {
        return cfg().getString("default-kit", "law");
    }

    private double cutPercent() {
        return Math.max(0, Math.min(100, cfg().getDouble("house-cut-percent", 0)));
    }

    public DuelArenaManager getDuelArenaManager() {
        return duelArenaManager;
    }

    public DuelStatsManager getDuelStatsManager() {
        return duelStatsManager;
    }

    // ---------------------------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------------------------

    public boolean isInDuel(UUID uuid) {
        return uuid != null && playerDuel.containsKey(uuid);
    }

    public boolean isInActiveDuel(UUID uuid) {
        Duel duel = getDuelForPlayer(uuid);
        return duel != null && duel.getState().isActiveCombat();
    }

    /**
     * True once the players are actually committed to a fight - from accept (escrow/kit/teleport
     * setup) through the countdown and the fight itself - as opposed to {@link #isInDuel}, which
     * is already true for a merely <em>requested</em> duel.
     *
     * <p>This distinction matters a lot: {@code isInDuel} is the right gate for "don't let them
     * start a second duel", but using it to gate combat consequences let a player keep a request
     * permanently pending and thereby suppress their own death handling in FFA entirely (no
     * killstreak reset, no death counted, no kill reward/bounty for whoever killed them, and
     * their drops silently voided). Anything that suppresses or redirects normal combat/inventory
     * behaviour must use this method instead.
     *
     * <p>ACCEPTED is included on top of {@link DuelState#isActiveCombat()} deliberately. It is a
     * transient state today (setup runs synchronously straight through it), but escrow is already
     * being taken inside it, so if a death ever does land there it must reach DuelManager's own
     * cancel-and-refund path rather than being processed as an ordinary FFA death with the wagers
     * still held. isActiveCombat() itself is left alone: it additionally drives PvP isolation,
     * where treating a not-yet-teleported player as untouchable would make them invincible while
     * still standing in a live FFA fight.
     */
    public boolean isInCombatDuel(UUID uuid) {
        Duel duel = getDuelForPlayer(uuid);
        if (duel == null) {
            return false;
        }
        DuelState state = duel.getState();
        return state == DuelState.ACCEPTED || state.isActiveCombat();
    }

    public boolean isInGrace(UUID uuid) {
        return uuid != null && playerGrace.containsKey(uuid);
    }

    public Duel getDuel(UUID duelId) {
        return duelId == null ? null : duels.get(duelId);
    }

    public Duel getDuelForPlayer(UUID uuid) {
        UUID id = uuid == null ? null : playerDuel.get(uuid);
        return id == null ? null : duels.get(id);
    }

    public Duel getGraceDuelForPlayer(UUID uuid) {
        UUID id = uuid == null ? null : playerGrace.get(uuid);
        return id == null ? null : duels.get(id);
    }

    public Collection<Duel> getAllDuels() {
        return Collections.unmodifiableCollection(duels.values());
    }

    /**
     * Display name for a participant. Goes through the leaderboard's non-blocking name cache
     * rather than {@code Bukkit.getOfflinePlayer(uuid).getName()}: for a UUID missing from the
     * local usercache that call issues a blocking Mojang request, and this runs on the main
     * thread from duel announcements, history rows and the request prompt - i.e. right in the
     * middle of a fight. An 8-character UUID prefix is a fine fallback for the rare miss, and the
     * cache repairs itself in the background for next time.
     */
    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        if (plugin.getLeaderboardManager() != null) {
            String cached = plugin.getLeaderboardManager().cachedName(uuid);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        }
        return uuid.toString().substring(0, 8);
    }

    private void msg(Player player, String key, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        String raw = cfg().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }

    // ---------------------------------------------------------------------------------------
    // Request creation
    // ---------------------------------------------------------------------------------------

    /** Returns null if the request would be valid, or a duels.yml messages.* key describing why not. */
    public String validateNewRequest(UUID challengerUuid, UUID targetUuid) {
        if (!enabled()) {
            return "disabled";
        }
        if (challengerUuid == null || targetUuid == null || challengerUuid.equals(targetUuid)) {
            return "self-duel";
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            return "target-offline";
        }
        if (!plugin.getSettingsManager().isEnabled(targetUuid, SettingsManager.DUEL_REQUESTS)) {
            return "target-opted-out";
        }
        if (isInDuel(challengerUuid)) {
            return "already-in-duel";
        }
        if (isInDuel(targetUuid)) {
            return "target-already-in-duel";
        }
        if (plugin.getArenaManager().getPlayerArena(challengerUuid) != null) {
            return "already-in-arena";
        }
        if (plugin.getArenaManager().getPlayerArena(targetUuid) != null) {
            return "target-in-arena";
        }
        if (isInGrace(challengerUuid)) {
            return "already-in-duel";
        }
        if (isInGrace(targetUuid)) {
            return "target-already-in-duel";
        }
        Player challenger = Bukkit.getPlayer(challengerUuid);
        if (challenger != null && isBusy(challenger)) {
            return "challenger-busy";
        }
        if (onDeclineCooldown(challengerUuid, targetUuid)) {
            return "on-cooldown";
        }
        if (isRateLimited(challengerUuid)) {
            return "rate-limited";
        }
        return null;
    }

    private boolean isBusy(Player player) {
        if (player.isDead()) {
            return true;
        }
        if (plugin.getArenaManager().getPlayerArena(player.getUniqueId()) != null) {
            return true;
        }
        if (isInGrace(player.getUniqueId())) {
            return true;
        }
        return plugin.getCombatListener() != null && plugin.getCombatListener().isCombatTagged(player.getUniqueId());
    }

    public Duel createRequest(Player challenger, Player target, String arenaId, double rawWager) {
        return createRequest(challenger, target, arenaId, defaultKitId(), rawWager);
    }

    /** Final validation + creation, called once the wager amount is known. Sends its own error messages. */
    public Duel createRequest(Player challenger, Player target, String arenaId, String kitId, double rawWager) {
        String err = validateNewRequest(challenger.getUniqueId(), target.getUniqueId());
        if (err != null) {
            msg(challenger, err, Map.of("target", target.getName()));
            return null;
        }
        if (duelArenaManager.availability(arenaId) != DuelArenaManager.Availability.AVAILABLE) {
            msg(challenger, "arena-unavailable", Map.of());
            return null;
        }

        double wager = Math.floor(Math.max(0, rawWager));
        if (!Double.isFinite(wager)) {
            wager = 0;
        }
        double min = Math.max(0, cfg().getDouble("wager.min", 0));
        double max = Math.max(min, cfg().getDouble("wager.max", 1_000_000));
        if (wager > 0 && wager < min) {
            msg(challenger, "wager-too-low", Map.of("min", EconomyManager.format(min)));
            return null;
        }
        if (wager > max) {
            msg(challenger, "wager-too-high", Map.of("max", EconomyManager.format(max)));
            return null;
        }
        double dailyLimit = cfg().getDouble("wager.daily-limit", 0);
        if (dailyLimit > 0 && wager > 0
                && duelStatsManager.getDailyWagered(challenger.getUniqueId()) + wager > dailyLimit) {
            msg(challenger, "daily-limit-reached", Map.of("limit", EconomyManager.format(dailyLimit)));
            return null;
        }
        if (wager > 0) {
            EconomyManager economy = plugin.getEconomyManager();
            if (!economy.isReady()) {
                msg(challenger, "disabled", Map.of());
                return null;
            }
            if (!economy.has(challenger, wager)) {
                msg(challenger, "insufficient-funds", Map.of("amount", EconomyManager.format(wager)));
                return null;
            }
        }

        String effectiveKit = (kitId != null && !kitId.isBlank()) ? kitId : defaultKitId();
        long timeoutMs = Math.max(5000L, cfg().getLong("request.timeout-seconds", 30) * 1000L);
        Duel duel = new Duel(challenger.getUniqueId(), target.getUniqueId(), effectiveKit, wager, false, timeoutMs);
        duel.setArenaId(arenaId.toLowerCase(Locale.ROOT));

        duels.put(duel.getId(), duel);
        playerDuel.put(challenger.getUniqueId(), duel.getId());
        playerDuel.put(target.getUniqueId(), duel.getId());
        // Same unbounded-growth problem as pairCooldownUntil: one permanent entry per player who
        // ever sent a request, for a value that is dead after request.rate-limit-seconds.
        long requestedAt = System.currentTimeMillis();
        long rateLimitMs = Math.max(0L, cfg().getLong("request.rate-limit-seconds", 3) * 1000L);
        lastRequestSentAt.values().removeIf(sentAt -> sentAt == null || requestedAt - sentAt >= rateLimitMs);
        lastRequestSentAt.put(challenger.getUniqueId(), requestedAt);

        if (plugin.getDuelQueueManager() != null) {
            plugin.getDuelQueueManager().removeSilent(challenger.getUniqueId());
            plugin.getDuelQueueManager().removeSilent(target.getUniqueId());
        }

        Bukkit.getPluginManager().callEvent(new DuelCreateEvent(duel));

        msg(challenger, "request-sent", Map.of("target", target.getName()));
        sendRequestPrompt(duel);
        scheduleExpiry(duel);

        plugin.getLogger().info("[Duel " + duel.getId() + "] requested: " + challenger.getName()
                + " -> " + target.getName() + " arena=" + arenaId + " wager=" + wager);
        return duel;
    }

    /**
     * Sends the clickable accept/deny block exactly once (no more re-sending the whole message
     * every few seconds and burying the buttons in chat), then keeps the "expires in Ns" promise
     * from DUELS.md alive via a cheap per-second actionbar tick to both sides instead - one short
     * Component with no click/hover events, versus rebuilding and re-sending the full multi-line
     * block repeatedly.
     */
    private void sendRequestPrompt(Duel duel) {
        Player target = Bukkit.getPlayer(duel.getTarget());
        if (target != null && target.isOnline()) {
            target.sendMessage(buildRequestComponent(duel));
            target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 1.5f);
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (duel.getState() != DuelState.REQUESTED) {
                BukkitTask self = duel.getRequestTickerTask();
                if (self != null) {
                    self.cancel();
                }
                return;
            }
            long secondsLeft = duel.millisUntilExpiry() / 1000L;
            Player t = Bukkit.getPlayer(duel.getTarget());
            if (t != null && t.isOnline()) {
                MessageUtil.actionBar(t, MessageUtil.apply(
                        cfg().getString("messages.request-actionbar-target", "&d⚔ &fDuel request from &f{challenger} &8- &e{seconds}s &7to respond"),
                        Map.of("challenger", nameOf(duel.getChallenger()), "seconds", String.valueOf(secondsLeft))
                ));
            }
            Player c = Bukkit.getPlayer(duel.getChallenger());
            if (c != null && c.isOnline()) {
                MessageUtil.actionBar(c, MessageUtil.apply(
                        cfg().getString("messages.request-actionbar-challenger", "&7Waiting on &f{target} &8- &e{seconds}s"),
                        Map.of("target", nameOf(duel.getTarget()), "seconds", String.valueOf(secondsLeft))
                ));
            }
        }, 0L, 20L);
        duel.setRequestTickerTask(task);
    }

    private Component buildRequestComponent(Duel duel) {
        Arena arena = duelArenaManager.resolve(duel.getArenaId());
        String mapName = arena != null ? MessageUtil.strip(arena.getDisplayName()) : String.valueOf(duel.getArenaId());
        Kit kit = plugin.getKitManager().getKit(duel.getKitId());
        String kitName = kit != null ? MessageUtil.strip(kit.getDisplayName()) : String.valueOf(duel.getKitId());
        double wager = duel.getWager();
        double pot = wager * 2;
        double cut = cutPercent();
        double houseCut = pot * (cut / 100.0);
        double winnerReceives = wager > 0 ? (pot - houseCut) : Math.max(0, cfg().getDouble("no-wager-reward", 25));
        String cutText = (wager > 0 && cut > 0)
                ? MessageUtil.apply(" &7(house cut: {cut}%)", Map.of("cut", EconomyManager.format(cut)))
                : "";

        Map<String, String> placeholders = Map.of(
                "challenger", nameOf(duel.getChallenger()),
                "map", mapName,
                "kit", kitName,
                "wager", EconomyManager.format(wager),
                "pot", EconomyManager.format(winnerReceives),
                "cut", cutText,
                "seconds", String.valueOf(duel.millisUntilExpiry() / 1000L)
        );

        String template = cfg().getString("messages.request-received", "");
        List<Component> lines = new ArrayList<>();
        for (String line : template.split("\n")) {
            lines.add(MessageUtil.parse(MessageUtil.apply(line, placeholders)));
        }

        Component accept = MessageUtil.parse(cfg().getString("messages.accept-button", "&a&l[ACCEPT]"))
                .clickEvent(ClickEvent.runCommand("/duel accept " + duel.getId()))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(cfg().getString("messages.accept-hover", ""))));
        Component deny = MessageUtil.parse(cfg().getString("messages.deny-button", "&c&l[DENY]"))
                .clickEvent(ClickEvent.runCommand("/duel deny " + duel.getId()))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(cfg().getString("messages.deny-hover", ""))));
        lines.add(Component.text("  ").append(accept).append(Component.text("   ")).append(deny));

        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private void scheduleExpiry(Duel duel) {
        long ticks = Math.max(1L, duel.millisUntilExpiry() / 50L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(duel.getId()), ticks);
        duel.setExpiryTask(task);
    }

    private void expireRequest(UUID duelId) {
        Duel duel = duels.get(duelId);
        if (duel == null || !duel.transition(DuelState.REQUESTED, DuelState.EXPIRED)) {
            return;
        }
        setDeclineCooldown(duel.getChallenger(), duel.getTarget());
        cleanupPendingRequest(duel);
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());
        msg(challenger, "request-expired", Map.of("target", nameOf(duel.getTarget())));
        msg(target, "request-expired-target", Map.of("challenger", nameOf(duel.getChallenger())));
        plugin.getLogger().info("[Duel " + duel.getId() + "] request expired.");
    }

    public boolean declineDuel(Player target, String duelIdOrNull) {
        Duel duel = resolveIncoming(target.getUniqueId(), duelIdOrNull);
        if (duel == null) {
            msg(target, "request-not-found", Map.of());
            return false;
        }
        if (!duel.transition(DuelState.REQUESTED, DuelState.DECLINED)) {
            msg(target, "request-invalid-state", Map.of());
            return false;
        }
        setDeclineCooldown(duel.getChallenger(), duel.getTarget());
        cleanupPendingRequest(duel);
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        msg(challenger, "request-declined", Map.of("target", target.getName()));
        msg(target, "request-declined-self", Map.of("challenger", nameOf(duel.getChallenger())));
        plugin.getLogger().info("[Duel " + duel.getId() + "] declined by " + target.getName() + ".");
        return true;
    }

    public boolean cancelRequest(Player challenger, String duelIdOrNull) {
        Duel duel = resolveOutgoing(challenger.getUniqueId(), duelIdOrNull);
        if (duel == null) {
            msg(challenger, "request-not-found", Map.of());
            return false;
        }
        if (!duel.transition(DuelState.REQUESTED, DuelState.CANCELLED)) {
            msg(challenger, "request-invalid-state", Map.of());
            return false;
        }
        cleanupPendingRequest(duel);
        Player target = Bukkit.getPlayer(duel.getTarget());
        msg(challenger, "request-cancelled", Map.of("target", nameOf(duel.getTarget())));
        if (target != null) {
            MessageUtil.send(target, MessageUtil.apply(
                    cfg().getString("messages.request-expired-target", ""),
                    Map.of("challenger", challenger.getName())
            ));
        }
        return true;
    }

    private Duel resolveIncoming(UUID targetUuid, String duelIdOrNull) {
        Duel duel = resolveById(duelIdOrNull);
        if (duel == null) {
            UUID id = playerDuel.get(targetUuid);
            duel = id == null ? null : duels.get(id);
        }
        if (duel == null || !targetUuid.equals(duel.getTarget()) || duel.getState() != DuelState.REQUESTED) {
            return null;
        }
        return duel;
    }

    private Duel resolveOutgoing(UUID challengerUuid, String duelIdOrNull) {
        Duel duel = resolveById(duelIdOrNull);
        if (duel == null) {
            UUID id = playerDuel.get(challengerUuid);
            duel = id == null ? null : duels.get(id);
        }
        if (duel == null || !challengerUuid.equals(duel.getChallenger()) || duel.getState() != DuelState.REQUESTED) {
            return null;
        }
        return duel;
    }

    private Duel resolveById(String duelIdOrNull) {
        if (duelIdOrNull == null || duelIdOrNull.isBlank()) {
            return null;
        }
        try {
            return duels.get(UUID.fromString(duelIdOrNull.trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void cleanupPendingRequest(Duel duel) {
        duel.cancelAllTasks();
        playerDuel.remove(duel.getChallenger());
        playerDuel.remove(duel.getTarget());
        duels.remove(duel.getId());
    }

    private boolean isRateLimited(UUID uuid) {
        long limitMs = Math.max(0L, cfg().getLong("request.rate-limit-seconds", 3) * 1000L);
        if (limitMs <= 0) {
            return false;
        }
        Long last = lastRequestSentAt.get(uuid);
        return last != null && System.currentTimeMillis() - last < limitMs;
    }

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private boolean onDeclineCooldown(UUID a, UUID b) {
        Long until = pairCooldownUntil.get(pairKey(a, b));
        return until != null && System.currentTimeMillis() < until;
    }

    private void setDeclineCooldown(UUID a, UUID b) {
        long ms = Math.max(0L, cfg().getLong("request.cooldown-seconds", 5) * 1000L);
        if (ms <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        // Entries were only ever inserted, never removed, so this map grew for the whole uptime
        // of the server: one permanent entry per distinct pair of players that ever declined,
        // expired or cancelled a request, even though every entry is dead within
        // request.cooldown-seconds (5s by default). Sweeping the expired ones on each write keeps
        // it to just the live window - the sweep is O(size), and because it runs on every write
        // the size it walks stays small rather than being allowed to accumulate.
        pairCooldownUntil.values().removeIf(until -> until == null || until <= now);
        pairCooldownUntil.put(pairKey(a, b), now + ms);
    }

    // ---------------------------------------------------------------------------------------
    // Wager chat prompt (mirrors BountyManager's Prompt pattern)
    // ---------------------------------------------------------------------------------------

    public void beginWagerPrompt(Player challenger, Player target, String arenaId) {
        beginWagerPrompt(challenger, target, arenaId, defaultKitId());
    }

    public void beginWagerPrompt(Player challenger, Player target, String arenaId, String kitId) {
        String effectiveKit = (kitId != null && !kitId.isBlank()) ? kitId : defaultKitId();
        wagerPrompts.put(challenger.getUniqueId(), new WagerPrompt(
                target.getUniqueId(), arenaId, effectiveKit, System.currentTimeMillis() + promptTimeoutMs()));
        double min = Math.max(0, cfg().getDouble("wager.min", 0));
        double max = Math.max(min, cfg().getDouble("wager.max", 1_000_000));
        msg(challenger, "wager-prompt", Map.of(
                "target", target.getName(),
                "min", EconomyManager.format(min),
                "max", EconomyManager.format(max)
        ));
    }

    /**
     * How long a pending chat prompt stays armed. Without a bound, an unanswered prompt captured
     * every message the player typed forever: {@code handleChat} intentionally re-arms itself on
     * unparseable input so a typo doesn't lose the flow, which also meant anyone who missed the
     * "type cancel" hint simply could not chat again until they stumbled onto a valid number.
     */
    private long promptTimeoutMs() {
        return Math.max(5_000L, plugin.getConfig().getLong("chat-prompt-timeout-seconds", 60) * 1000L);
    }

    /**
     * Reads the caller's pending wager prompt, dropping it first if it has expired. This is the
     * single gate every chat-capture path goes through, so expiry is enforced here rather than
     * needing a scheduled sweep. Safe to call from the async chat thread - the backing map is
     * concurrent and the removal is atomic.
     */
    public WagerPrompt peekWagerPrompt(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        WagerPrompt prompt = wagerPrompts.get(uuid);
        if (prompt == null) {
            return null;
        }
        if (prompt.isExpired()) {
            wagerPrompts.remove(uuid, prompt);
            return null;
        }
        return prompt;
    }

    public void clearWagerPrompt(UUID uuid) {
        if (uuid != null) {
            wagerPrompts.remove(uuid);
        }
    }

    public boolean handleChat(Player player, String raw) {
        WagerPrompt prompt = peekWagerPrompt(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("c")) {
            wagerPrompts.remove(player.getUniqueId());
            msg(player, "wager-cancelled", Map.of());
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException e) {
            msg(player, "wager-invalid", Map.of());
            return true;
        }
        wagerPrompts.remove(player.getUniqueId());

        Player target = Bukkit.getPlayer(prompt.target());
        if (target == null || !target.isOnline()) {
            msg(player, "target-offline", Map.of());
            return true;
        }
        createRequest(player, target, prompt.arenaId(), prompt.kitId(), amount);
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Accept -> setup sequence
    // ---------------------------------------------------------------------------------------

    public boolean acceptDuel(Player target, String duelIdOrNull) {
        Duel duel = resolveIncoming(target.getUniqueId(), duelIdOrNull);
        if (duel == null) {
            msg(target, "request-not-found", Map.of());
            return false;
        }
        if (isBusy(target)) {
            msg(target, "challenger-busy", Map.of());
            return false;
        }
        if (!duel.transition(DuelState.REQUESTED, DuelState.ACCEPTED)) {
            msg(target, "request-invalid-state", Map.of());
            return false;
        }
        duel.cancelAllTasks();
        duel.setAcceptedAt(System.currentTimeMillis());

        if (plugin.getDuelQueueManager() != null) {
            plugin.getDuelQueueManager().removeSilent(duel.getChallenger());
            plugin.getDuelQueueManager().removeSilent(duel.getTarget());
        }

        Bukkit.getPluginManager().callEvent(new DuelAcceptEvent(duel));
        return runSetupSequence(duel);
    }

    public boolean createQueueMatch(Player challenger, Player target, String arenaId, boolean ranked) {
        return createQueueMatch(challenger, target, arenaId, defaultKitId(), ranked);
    }

    /**
     * Starts an instant duel directly from the matchmaking queue (casual or ranked).
     */
    public boolean createQueueMatch(Player challenger, Player target, String arenaId, String kitId, boolean ranked) {
        if (challenger == null || !challenger.isOnline() || target == null || !target.isOnline()) {
            return false;
        }
        if (isInDuel(challenger.getUniqueId()) || isInDuel(target.getUniqueId())) {
            return false;
        }
        // Matchmaking re-validates candidates every tick (DuelQueueManager#isValidQueuedPlayer),
        // but that check never covered arena residency - only isInDuel/isInGrace/dead/combat-tag.
        // Without isBusy() here too, a player standing in a live FFA arena fight could still be
        // matched and instantly teleported out via runSetupSequence below, the same escape this
        // isBusy() gate already blocks for the direct /duel request+accept flow.
        if (isBusy(challenger) || isBusy(target)) {
            return false;
        }
        String effectiveKit = (kitId != null && !kitId.isBlank()) ? kitId : defaultKitId();
        Duel duel = new Duel(challenger.getUniqueId(), target.getUniqueId(), effectiveKit, 0.0, ranked, 0);
        duel.setArenaId(arenaId.toLowerCase(Locale.ROOT));

        duels.put(duel.getId(), duel);
        playerDuel.put(challenger.getUniqueId(), duel.getId());
        playerDuel.put(target.getUniqueId(), duel.getId());

        if (!duel.transition(DuelState.REQUESTED, DuelState.ACCEPTED)) {
            duels.remove(duel.getId());
            playerDuel.remove(challenger.getUniqueId());
            playerDuel.remove(target.getUniqueId());
            return false;
        }
        duel.setAcceptedAt(System.currentTimeMillis());
        Bukkit.getPluginManager().callEvent(new DuelAcceptEvent(duel));
        return runSetupSequence(duel);
    }

    private boolean runSetupSequence(Duel duel) {
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());
        if (challenger == null || !challenger.isOnline() || target == null || !target.isOnline()) {
            rollbackSetup(duel, "player-offline");
            return false;
        }
        // A player about to fight in their own duel can't still be sitting in spectator mode
        // watching a different one - force them out first so kit application/teleport below
        // don't run on a player stuck in GameMode.SPECTATOR.
        if (plugin.getSpectatorManager() != null) {
            if (plugin.getSpectatorManager().isSpectating(challenger.getUniqueId())) {
                plugin.getSpectatorManager().stopSpectating(challenger, false);
            }
            if (plugin.getSpectatorManager().isSpectating(target.getUniqueId())) {
                plugin.getSpectatorManager().stopSpectating(target, false);
            }
        }

        // The challenger passed isBusy() when they sent the request, but the target can sit on it
        // for the full request.timeout-seconds. In that window the challenger can walk into an FFA
        // arena or pick a fight, and accepting would then teleport them straight out of it - the
        // exact escape isBusy() exists to prevent. Re-check at the only moment that matters.
        if (isBusy(challenger)) {
            rollbackSetup(duel, "challenger-busy");
            return false;
        }

        // A snapshot is taken a few lines below and restored verbatim when the duel ends. Any
        // still-open container is a second, live view of items that the snapshot also captures -
        // most importantly HeldShulkerListener's virtual box, whose staged contents only get
        // written back into the item on close. Capturing while that session is open records the
        // box with its *pre-edit* contents while the items already pulled out of it sit loose in
        // the storage array, so the post-duel restore hands the player both copies. Settle every
        // open view first so the snapshot describes exactly one authoritative inventory state.
        settleOpenInventories(challenger);
        settleOpenInventories(target);

        EconomyManager economy = plugin.getEconomyManager();
        if (duel.getWager() > 0) {
            if (!economy.isReady() || !economy.has(challenger, duel.getWager())) {
                rollbackSetup(duel, "insufficient-funds-challenger");
                return false;
            }
            if (!economy.has(target, duel.getWager())) {
                rollbackSetup(duel, "insufficient-funds-target");
                return false;
            }
        }

        Arena arena = duelArenaManager.resolve(duel.getArenaId());
        if (arena == null || !arena.hasCenter()) {
            rollbackSetup(duel, "arena-unavailable");
            return false;
        }
        if (!duelArenaManager.reserve(duel.getArenaId(), duel.getId())) {
            rollbackSetup(duel, "arena-unavailable");
            return false;
        }

        Location spot1 = arena.hasSpawn1() ? arena.getSpawn1() : plugin.getArenaManager().findSafeSpot(arena);
        Location spot2 = arena.hasSpawn2() ? arena.getSpawn2() : plugin.getArenaManager().findSafeSpot(arena);
        if (spot1 == null) spot1 = arena.getCenter();
        if (spot2 == null) spot2 = arena.randomSpot();
        for (int attempt = 0; attempt < 3 && spot1 != null && spot2 != null && sameBlock(spot1, spot2); attempt++) {
            spot2 = plugin.getArenaManager().findSafeSpot(arena);
        }
        if (spot1 == null || spot2 == null) {
            rollbackSetup(duel, "no-spawns");
            return false;
        }

        Kit kit = plugin.getKitManager().getKit(duel.getKitId());
        if (kit == null) {
            rollbackSetup(duel, "kit-missing");
            return false;
        }

        duel.setChallengerSnapshot(DuelInventorySnapshot.capture(challenger));
        duel.setTargetSnapshot(DuelInventorySnapshot.capture(target));
        // One synchronous write right after both snapshots exist - the earliest point a crash
        // could otherwise lose track of the players' pre-duel inventories. Everything from here
        // on (including the escrow-confirmed update) persists asynchronously.
        persistDuelRowSync(duel);

        if (duel.getWager() > 0) {
            if (!economy.takeExact(challenger, duel.getWager())) {
                rollbackSetup(duel, "insufficient-funds-challenger");
                return false;
            }
            if (!economy.takeExact(target, duel.getWager())) {
                economy.refund(challenger, duel.getWager());
                rollbackSetup(duel, "insufficient-funds-target");
                return false;
            }
            duel.markEscrowed();
            duelStatsManager.addDailyWagered(challenger.getUniqueId(), duel.getWager());
            duelStatsManager.addDailyWagered(target.getUniqueId(), duel.getWager());
            persistDuelRowAsync(duel);
        }

        try {
            plugin.getKitManager().applyKit(challenger, kit);
            plugin.getKitManager().applyKit(target, kit);
            spot1.getChunk().load();
            spot2.getChunk().load();
            challenger.teleport(spot1);
            target.teleport(spot2);
        } catch (Exception e) {
            plugin.getLogger().warning("[Duel " + duel.getId() + "] setup step threw: " + e);
            rollbackSetup(duel, "internal-error");
            return false;
        }

        if (!duel.transition(DuelState.ACCEPTED, DuelState.STARTING)) {
            rollbackSetup(duel, "internal-state");
            return false;
        }
        persistDuelRowAsync(duel);
        startCountdown(duel);
        plugin.getLogger().info("[Duel " + duel.getId() + "] started setup successfully, entering countdown.");
        return true;
    }

    /**
     * Flushes and closes anything the player currently has open so their real inventory is the
     * single source of truth before {@link DuelInventorySnapshot#capture} runs. The held-shulker
     * session is settled explicitly (its contents live in a detached Inventory until close), then
     * the container itself is closed, which also returns/drops any item left on the cursor.
     */
    private void settleOpenInventories(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (plugin.getHeldShulkerListener() != null) {
            plugin.getHeldShulkerListener().forceCloseSession(player);
        }
        player.closeInventory();
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void rollbackSetup(Duel duel, String reasonKey) {
        if (!duel.markRollbackDone()) {
            return;
        }
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());

        if (duel.isEscrowed() && duel.getWager() > 0) {
            depositOrWarn(duel, duel.getChallenger(), duel.getWager(), "setup-rollback");
            depositOrWarn(duel, duel.getTarget(), duel.getWager(), "setup-rollback");
        }
        DuelInventorySnapshot cs = duel.getChallengerSnapshot();
        if (cs != null && challenger != null && challenger.isOnline()) {
            cs.restore(challenger);
        }
        DuelInventorySnapshot ts = duel.getTargetSnapshot();
        if (ts != null && target != null && target.isOnline()) {
            ts.restore(target);
        }
        removeDuelVisibility(duel);
        if (duel.getArenaId() != null) {
            clearArenaDrops(duel.getArenaId());
            duelArenaManager.release(duel.getArenaId());
        }
        duel.transitionFromAny(DuelState.CANCELLED, DuelState.ACCEPTED, DuelState.STARTING);
        duel.cancelAllTasks();
        playerDuel.remove(duel.getChallenger());
        playerDuel.remove(duel.getTarget());
        duels.remove(duel.getId());
        deleteDuelRowAsync(duel.getId());

        msg(challenger, "accept-failed", Map.of("reason", reasonKey));
        msg(target, "accept-failed", Map.of("reason", reasonKey));
        plugin.getLogger().warning("[Duel " + duel.getId() + "] setup failed and rolled back: " + reasonKey);
    }

    private void startCountdown(Duel duel) {
        int seconds = Math.max(1, cfg().getInt("countdown-seconds", 3));
        int[] remaining = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (duel.getState() != DuelState.STARTING) {
                BukkitTask self = duel.getCountdownTask();
                if (self != null) {
                    self.cancel();
                }
                return;
            }
            Player c = Bukkit.getPlayer(duel.getChallenger());
            Player t = Bukkit.getPlayer(duel.getTarget());
            if (c == null || !c.isOnline() || t == null || !t.isOnline()) {
                return; // handleDisconnect already owns cleanup for this duel
            }
            if (remaining[0] > 0) {
                String line = MessageUtil.apply(cfg().getString("messages.countdown", "&e{seconds}..."),
                        Map.of("seconds", String.valueOf(remaining[0])));
                MessageUtil.send(c, line);
                MessageUtil.send(t, line);
                c.playSound(c.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                remaining[0]--;
                return;
            }
            activateDuel(duel);
            BukkitTask self = duel.getCountdownTask();
            if (self != null) {
                self.cancel();
            }
            duel.setCountdownTask(null);
        }, 0L, 20L);
        duel.setCountdownTask(task);
    }

    // Transition to ACTIVE, send FIGHT message + action bar, apply visibility isolation
    private void activateDuel(Duel duel) {
        if (!duel.transition(DuelState.STARTING, DuelState.ACTIVE)) {
            return;
        }
        duel.setStartedAt(System.currentTimeMillis());
        Player c = Bukkit.getPlayer(duel.getChallenger());
        Player t = Bukkit.getPlayer(duel.getTarget());
        String fight = cfg().getString("messages.fight", "&a&lFIGHT!");
        if (c != null) {
            MessageUtil.send(c, fight);
            c.playSound(c.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 2.0f);
        }
        if (t != null) {
            MessageUtil.send(t, fight);
            t.playSound(t.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 1.0f, 2.0f);
        }
        // Action bar: show opponent win-rate message for 5 seconds
        sendFightActionBar(duel);
        // Visibility isolation: hide every other player from both duelists
        applyDuelVisibility(duel);
        if (plugin.getDuelScoreboardManager() != null) {
            plugin.getDuelScoreboardManager().start(duel);
        }
        Bukkit.getPluginManager().callEvent(new DuelStartEvent(duel));
        persistDuelRowAsync(duel);
        scheduleMaxDuration(duel);
    }


    /**
     * Sends the opponent win-rate action bar to both duelists and suppresses
     * the normal action bar for 5 seconds so the message stays visible.
     */
    private void sendFightActionBar(Duel duel) {
        ActionBarManager abMgr = plugin.getActionBarManager();
        if (abMgr == null) return;
        long suppressMs = Math.max(1000L, cfg().getLong("fight-action-bar-duration-ms", 5000L));
        DuelStatsManager stats = plugin.getDuelManager().getDuelStatsManager();

        sendFightBarTo(duel.getChallenger(), duel.getTarget(), suppressMs, stats, abMgr);
        sendFightBarTo(duel.getTarget(), duel.getChallenger(), suppressMs, stats, abMgr);
    }

    private void sendFightBarTo(UUID recipientUuid, UUID opponentUuid,
                                long suppressMs, DuelStatsManager stats,
                                ActionBarManager abMgr) {
        Player recipient = Bukkit.getPlayer(recipientUuid);
        if (recipient == null || !recipient.isOnline()) return;
        String opponentName = nameOf(opponentUuid);
        double winRate = stats.getWinRate(opponentUuid);
        String winRateStr = String.format("%.2f", winRate);
        String template = cfg().getString(
                "messages.fight-action-bar",
                "&7Your opponent &b{opponent} &7has a &b{winrate}% &7winrate. Good luck!"
        );
        String bar = MessageUtil.apply(template, Map.of(
                "opponent", opponentName,
                "winrate", winRateStr
        ));
        // Suppress normal bar first, then send the duel bar on a repeating task for the duration
        abMgr.suppress(recipientUuid, suppressMs);
        // Send immediately and repeat every second so the action bar doesn't flicker away
        int ticks = (int) Math.max(1L, suppressMs / 50L);
        int[] sent = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player p = Bukkit.getPlayer(recipientUuid);
            if (p == null || sent[0] >= ticks) {
                task.cancel();
                return;
            }
            MessageUtil.actionBar(p, bar);
            sent[0] += 20; // advance by one tick-period (20 ticks = 1 s)
        }, 0L, 20L);
    }

    // ----------------------------------------------------------------------------------
    // Visibility isolation — hide all non-participants from each other during the duel
    // ----------------------------------------------------------------------------------

    /**
     * Hides every online player from each duelist (except their opponent),
     * and hides both duelists from every third-party player.
     */
    private void applyDuelVisibility(Duel duel) {
        Player c = Bukkit.getPlayer(duel.getChallenger());
        Player t = Bukkit.getPlayer(duel.getTarget());
        if (c == null || t == null) return;

        for (Player other : Bukkit.getOnlinePlayers()) {
            UUID uid = other.getUniqueId();
            if (uid.equals(duel.getChallenger()) || uid.equals(duel.getTarget())) continue;
            // Third-party players can't see either duelist
            other.hidePlayer(plugin, c);
            other.hidePlayer(plugin, t);
            // Duelists can't see third-party players
            c.hidePlayer(plugin, other);
            t.hidePlayer(plugin, other);
        }
    }

    /**
     * Restores visibility after the duel ends: re-shows both duelists to everyone
     * and re-shows everyone to both duelists.
     */
    private void removeDuelVisibility(Duel duel) {
        Player c = Bukkit.getPlayer(duel.getChallenger());
        Player t = Bukkit.getPlayer(duel.getTarget());

        for (Player other : Bukkit.getOnlinePlayers()) {
            UUID uid = other.getUniqueId();
            // A blanket show-everyone-to-everyone here used to tear down *other*, still-running
            // duels' isolation: ending duel A re-showed A's participants to B's participants and
            // vice-versa, so B's fight suddenly had bystanders rendered in it again. Only lift the
            // hide where neither side is currently isolated by a different live duel.
            boolean otherIsolated = isVisibilityIsolated(uid) && !duel.involves(uid);
            if (c != null && !otherIsolated) other.showPlayer(plugin, c);
            if (t != null && !otherIsolated) other.showPlayer(plugin, t);
            if (c != null && !uid.equals(duel.getChallenger()) && !otherIsolated) c.showPlayer(plugin, other);
            if (t != null && !uid.equals(duel.getTarget()) && !otherIsolated) t.showPlayer(plugin, other);
        }
    }

    /**
     * Whether this player is currently fighting in some duel that is still applying its
     * mutual-hide wall, and so must not have that wall lifted by an unrelated duel ending.
     *
     * <p>Deliberately keyed on duel participation only, never on "is spectating". Every pair this
     * loop touches has one side inside the ending duel, so checking the *other* side for live-duel
     * membership is already enough to keep every still-running duel sealed, in both directions and
     * symmetrically (when that duel ends in turn, its own removeDuelVisibility finishes the job).
     * Treating spectators as isolated would instead strand them: a spectator watching duel A while
     * duel B ends would be skipped by B's restore and stay mutually invisible to B's players
     * forever, since nothing re-runs that restore once they stop spectating.
     */
    private boolean isVisibilityIsolated(UUID uuid) {
        Duel other = getDuelForPlayer(uuid);
        return other != null && other.getState().isActiveCombat();
    }

    private void scheduleMaxDuration(Duel duel) {
        int seconds = cfg().getInt("max-duration-seconds", 600);
        if (seconds <= 0) {
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> onMaxDuration(duel.getId()), seconds * 20L);
        duel.setMaxDurationTask(task);
    }

    private void onMaxDuration(UUID duelId) {
        Duel duel = duels.get(duelId);
        if (duel == null || !duel.transition(DuelState.ACTIVE, DuelState.ENDING)) {
            return;
        }
        String outcome = cfg().getString("max-duration-outcome", "refund");
        if ("higher-health".equalsIgnoreCase(outcome)) {
            Player c = Bukkit.getPlayer(duel.getChallenger());
            Player t = Bukkit.getPlayer(duel.getTarget());
            double ch = (c != null && c.isOnline()) ? c.getHealth() : -1;
            double th = (t != null && t.isOnline()) ? t.getHealth() : -1;
            if (ch > th) {
                finishDuel(duel, duel.getChallenger(), DuelResult.TIMEOUT_WIN);
                return;
            } else if (th > ch) {
                finishDuel(duel, duel.getTarget(), DuelResult.TIMEOUT_WIN);
                return;
            }
        }
        finishDuel(duel, null, DuelResult.TIMEOUT_NO_CONTEST);
    }

    // ---------------------------------------------------------------------------------------
    // Death / disconnect resolution
    // ---------------------------------------------------------------------------------------

    /** Called by DuelListener's PlayerDeathEvent handler. No-op if the victim isn't in a duel. */
    public void handleDeath(Player victim) {
        Duel duel = getDuelForPlayer(victim.getUniqueId());
        if (duel == null) {
            return;
        }
        DuelState state = duel.getState();
        if (state == DuelState.ACCEPTED || state == DuelState.STARTING) {
            // Nobody has actually fought yet (pre-FIGHT countdown damage is cancelled, so this
            // can only happen via /leave's forced setHealth(0)). Previously this was silently
            // ignored: the countdown task kept running, the duel later flipped to ACTIVE with
            // one participant already dead/respawned elsewhere, and the honest opponent was
            // stranded - unable to queue/duel again - until max-duration-seconds (default 600s)
            // elapsed. Worse, with max-duration-outcome=higher-health the fled player could even
            // be awarded a TIMEOUT_WIN. Resolve it immediately as a clean, no-fault cancellation
            // instead, matching how a genuine disconnect during STARTING is already handled.
            handleStartingDeath(duel, victim.getUniqueId());
            return;
        }
        if (state != DuelState.ACTIVE) {
            return;
        }
        if (!duel.markDeathProcessed()) {
            return;
        }
        if (!duel.transition(DuelState.ACTIVE, DuelState.ENDING)) {
            return;
        }
        UUID winner = duel.opponentOf(victim.getUniqueId());
        finishDuel(duel, winner, DuelResult.NORMAL_WIN);
    }

    /**
     * Cancels a duel whose countdown was interrupted by a participant's death (only reachable via
     * /leave today). Shares rollbackSetup's idempotency flag so a death arriving alongside a
     * disconnect for the same duel can't double-cancel. The opponent is still alive and standing
     * in the arena, so their inventory is restored immediately; the victim is mid-death-screen, so
     * their restore is deferred through the normal post-duel respawn hook (restoring straight into
     * a not-yet-cleared death inventory would just be overwritten by vanilla death handling).
     */
    private void handleStartingDeath(Duel duel, UUID victimUuid) {
        if (!duel.markRollbackDone()) {
            return;
        }
        UUID opponentUuid = duel.opponentOf(victimUuid);
        Player opponent = Bukkit.getPlayer(opponentUuid);

        if (duel.isEscrowed() && duel.getWager() > 0) {
            depositOrWarn(duel, duel.getChallenger(), duel.getWager(), "starting-death-rollback");
            depositOrWarn(duel, duel.getTarget(), duel.getWager(), "starting-death-rollback");
        }

        DuelInventorySnapshot opponentSnapshot = duel.snapshotFor(opponentUuid);
        if (opponentSnapshot != null && opponent != null && opponent.isOnline()) {
            opponentSnapshot.restore(opponent);
        }
        Location spawn = resolveJoinSpawn();
        if (spawn != null) {
            pendingRespawnLocations.put(victimUuid, spawn);
        }
        DuelInventorySnapshot victimSnapshot = duel.snapshotFor(victimUuid);
        if (victimSnapshot != null) {
            pendingRespawnSnapshots.put(victimUuid, victimSnapshot);
        }

        removeDuelVisibility(duel);
        if (duel.getArenaId() != null) {
            clearArenaDrops(duel.getArenaId());
            duelArenaManager.release(duel.getArenaId());
        }
        duel.transitionFromAny(DuelState.CANCELLED, DuelState.ACCEPTED, DuelState.STARTING);
        duel.cancelAllTasks();
        playerDuel.remove(duel.getChallenger());
        playerDuel.remove(duel.getTarget());
        duels.remove(duel.getId());
        deleteDuelRowAsync(duel.getId());

        msg(opponent, "accept-failed", Map.of("reason", "opponent-left-during-starting"));
        plugin.getLogger().info("[Duel " + duel.getId() + "] cancelled: " + victimUuid
                + " died during the pre-fight countdown");
    }

    /** Called from PlayerListener.onQuit. */
    public void handleDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        clearWagerPrompt(uuid);
        pendingRespawnLocations.remove(uuid);
        if (plugin.getDuelQueueManager() != null) {
            plugin.getDuelQueueManager().removeSilent(uuid);
        }

        UUID duelId = playerDuel.get(uuid);
        if (duelId != null) {
            Duel duel = duels.get(duelId);
            if (duel != null) {
                removeDuelVisibility(duel);
                handleDisconnectDuel(duel, uuid);
            }
        }
        UUID graceId = playerGrace.get(uuid);
        if (graceId != null) {
            Duel duel = duels.get(graceId);
            if (duel != null) {
                leaveGraceInternal(duel, uuid);
            }
        }
    }

    private void handleDisconnectDuel(Duel duel, UUID uuid) {
        switch (duel.getState()) {
            case REQUESTED -> {
                if (duel.transition(DuelState.REQUESTED, DuelState.CANCELLED)) {
                    setDeclineCooldown(duel.getChallenger(), duel.getTarget());
                    cleanupPendingRequest(duel);
                    UUID otherUuid = duel.opponentOf(uuid);
                    Player other = Bukkit.getPlayer(otherUuid);
                    if (other != null) {
                        MessageUtil.send(other, MessageUtil.apply(
                                cfg().getString("messages.request-expired-target", ""),
                                Map.of("challenger", nameOf(uuid))
                        ));
                    }
                }
            }
            case ACCEPTED, STARTING -> rollbackSetup(duel, "player-disconnected");
            case ACTIVE -> {
                if (duel.transition(DuelState.ACTIVE, DuelState.ENDING)) {
                    UUID winner = duel.opponentOf(uuid);
                    finishDuel(duel, winner, DuelResult.FORFEIT_WIN);
                }
            }
            default -> {
                // ENDING/terminal - another path already owns cleanup.
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Terminal resolution (shared by win / forfeit / timeout)
    // ---------------------------------------------------------------------------------------

    private record PayoutResult(UUID recipient, double amount) {
    }

    private void finishDuel(Duel duel, UUID winner, DuelResult result) {
        duel.cancelAllTasks();
        duel.setEndedAt(System.currentTimeMillis());
        duel.setWinner(winner);
        duel.setResult(result);

        // Restore visibility for all players before anything else
        removeDuelVisibility(duel);
        if (plugin.getSpectatorManager() != null) {
            plugin.getSpectatorManager().onDuelEnd(duel.getId());
        }
        if (plugin.getDuelScoreboardManager() != null) {
            plugin.getDuelScoreboardManager().stop(duel);
        }

        // Lift action-bar suppression immediately (normal bar can resume)
        ActionBarManager abMgr = plugin.getActionBarManager();
        if (abMgr != null) {
            abMgr.unsuppress(duel.getChallenger());
            abMgr.unsuppress(duel.getTarget());
        }

        // Clear any combat-tag for both participants
        if (plugin.getCombatListener() != null) {
            plugin.getCombatListener().clearPlayer(duel.getChallenger());
            plugin.getCombatListener().clearPlayer(duel.getTarget());
        }

        double payoutAmount = 0;
        if (duel.markPayoutDone()) {
            PayoutResult pr = resolvePayout(duel, winner, result);
            payoutAmount = pr.amount();
            Bukkit.getPluginManager().callEvent(new DuelPayoutEvent(duel, pr.recipient(), pr.amount()));
        }

        if (duel.markStatsRecorded()) {
            recordStats(duel, winner, result, payoutAmount);
        }
        if (duel.isRanked() && winner != null && result != DuelResult.TIMEOUT_NO_CONTEST) {
            UUID loserUuid = duel.opponentOf(winner);
            DuelStatsManager.EloChange change = duelStatsManager.recordRankedResult(winner, loserUuid);
            if (change != null) {
                Player wp = Bukkit.getPlayer(winner);
                Player lp = Bukkit.getPlayer(loserUuid);
                if (wp != null && wp.isOnline()) {
                    MessageUtil.send(wp, "&a+" + change.winnerDelta() + " ELO &7(" + change.winnerNew() + " ELO)");
                }
                if (lp != null && lp.isOnline()) {
                    MessageUtil.send(lp, "&c" + change.loserDelta() + " ELO &7(" + change.loserNew() + " ELO)");
                }
            }
        }
        insertHistoryRow(duel, winner, result, payoutAmount);
        announceResult(duel, winner, result, payoutAmount);

        if (result == DuelResult.FORFEIT_WIN && winner != null) {
            Bukkit.getPluginManager().callEvent(new DuelForfeitEvent(duel, winner, duel.getLoser()));
        }
        Bukkit.getPluginManager().callEvent(new DuelEndEvent(duel, winner, duel.getLoser()));

        DuelState terminal = result == DuelResult.FORFEIT_WIN ? DuelState.FORFEITED : DuelState.FINISHED;
        duel.transitionFromAny(terminal, DuelState.ENDING);

        playerDuel.remove(duel.getChallenger());
        playerDuel.remove(duel.getTarget());

        // Teleport participants back to server spawn and restore inventory
        if (winner != null) {
            UUID loserUuid = duel.opponentOf(winner);
            Location spawn = resolveJoinSpawn();
            if (result == DuelResult.NORMAL_WIN) {
                // Queue spawn location and snapshot so onRespawn teleports and restores loser
                if (spawn != null) {
                    pendingRespawnLocations.put(loserUuid, spawn);
                }
                DuelInventorySnapshot loserSnapshot = duel.snapshotFor(loserUuid);
                if (loserSnapshot != null) {
                    pendingRespawnSnapshots.put(loserUuid, loserSnapshot);
                }
            } else {
                Player lp = Bukkit.getPlayer(loserUuid);
                DuelInventorySnapshot loserSnapshot = duel.snapshotFor(loserUuid);
                if (lp != null && lp.isOnline() && !lp.isDead()) {
                    teleportToSpawn(lp);
                    restorePlayerPostDuel(lp, loserSnapshot);
                } else if (loserSnapshot != null) {
                    // Forfeit/timeout loser who is offline or mid-death-screen: without this their
                    // pre-duel inventory is dropped on the floor with the terminal Duel object and
                    // they are left permanently holding the duel kit instead of their own items.
                    // Same restore-on-next-join path crash recovery uses, persisted so a restart
                    // before they reconnect can't drop it.
                    queuePersistentRestore(loserUuid, loserSnapshot);
                }
            }

            // Winner celebration: teleport to spawn after 3 seconds (60 ticks) and restore inventory
            Player wp = Bukkit.getPlayer(winner);
            if (wp != null && wp.isOnline()) {
                wp.playSound(wp.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
                DuelInventorySnapshot winnerSnapshot = duel.snapshotFor(winner);
                if (winnerSnapshot != null) {
                    pendingPostDuelRestores.put(winner, winnerSnapshot);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    pendingPostDuelRestores.remove(winner);
                    // Re-resolve by UUID rather than reusing the captured `wp` handle. A Player
                    // object is bound to one connection: if the winner disconnects and reconnects
                    // inside this 3s window, `wp.isOnline()` is false for the *stale* object even
                    // though the player is online again. That sent a live player down the
                    // restore-on-next-join branch, so they stood at spawn still wearing the
                    // plugin-issued duel kit with their real inventory owed until some later
                    // relog - and duel gear is only ever taken back by this restore. Dropping the
                    // kit and then relogging to collect the pre-duel inventory minted a full kit
                    // per duel, repeatable indefinitely through the matchmaking queue.
                    Player online = Bukkit.getPlayer(winner);
                    if (online != null && online.isOnline()) {
                        teleportToSpawn(online);
                        restorePlayerPostDuel(online, winnerSnapshot);
                    } else if (winnerSnapshot != null) {
                        // Winner disconnected during the 3s victory delay - without this, their
                        // real pre-duel inventory would be silently discarded once this terminal
                        // Duel is garbage-collected, leaving their saved inventory stuck on the
                        // duel kit. Reuse the same restore-on-next-join path as crash recovery,
                        // persisted so a restart before they reconnect can't drop it.
                        queuePersistentRestore(winner, winnerSnapshot);
                    }
                }, 60L);
            }
        } else {
            // Draw / Timeout no contest
            Player c = Bukkit.getPlayer(duel.getChallenger());
            Player t = Bukkit.getPlayer(duel.getTarget());
            if (c != null && c.isOnline() && !c.isDead()) {
                teleportToSpawn(c);
                restorePlayerPostDuel(c, duel.getChallengerSnapshot());
            }
            if (t != null && t.isOnline() && !t.isDead()) {
                teleportToSpawn(t);
                restorePlayerPostDuel(t, duel.getTargetSnapshot());
            }
        }

        deleteDuelRowAsync(duel.getId());
        sendRematchHint(duel);
        startGracePeriod(duel);

        plugin.getLogger().info("[Duel " + duel.getId() + "] finished result=" + result
                + " winner=" + winner + " payout=" + payoutAmount);
    }

    /**
     * Locks the arena out from new duels for grace-period-seconds so leftover items/mobs from
     * this duel can't bleed into the next one, then frees it. Without this, finishDuel() never
     * released the arena reservation at all (enterGrace/releaseGraceArena were dead code), so a
     * finished duel's arena would stay permanently unusable and the Duel object would never be
     * removed from the duels map.
     */
    private void startGracePeriod(Duel duel) {
        long graceSeconds = Math.max(0L, cfg().getLong("grace-period-seconds", 180));
        if (duel.getArenaId() == null || graceSeconds <= 0) {
            if (duel.getArenaId() != null) {
                clearArenaDrops(duel.getArenaId());
                duelArenaManager.release(duel.getArenaId());
            }
            duels.remove(duel.getId());
            return;
        }
        duelArenaManager.enterGrace(duel.getArenaId(), System.currentTimeMillis() + graceSeconds * 1000L);
        playerGrace.put(duel.getChallenger(), duel.getId());
        playerGrace.put(duel.getTarget(), duel.getId());
        sendGraceMessage(duel);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> endGrace(duel.getId()), graceSeconds * 20L);
        duel.setGraceTask(task);
    }

    private PayoutResult resolvePayout(Duel duel, UUID winner, DuelResult result) {
        double wager = duel.getWager();
        if (winner == null || result == DuelResult.TIMEOUT_NO_CONTEST) {
            if (duel.isEscrowed() && wager > 0) {
                depositOrWarn(duel, duel.getChallenger(), wager, "refund");
                depositOrWarn(duel, duel.getTarget(), wager, "refund");
                logPayout(duel, null, 0, "refund-no-contest");
            }
            return new PayoutResult(null, 0);
        }

        double amount;
        if (wager > 0) {
            double pot = wager * 2;
            double houseCut = pot * (cutPercent() / 100.0);
            amount = Math.max(0, pot - houseCut);
        } else {
            amount = Math.max(0, cfg().getDouble("no-wager-reward", 25));
        }
        if (amount > 0) {
            depositOrWarn(duel, winner, amount, "winner-payout");
        }
        logPayout(duel, winner, amount, result.name());
        return new PayoutResult(winner, amount);
    }

    /** Deposits are best-effort against Vault - a false return here means real money is owed and unpaid. */
    private void depositOrWarn(Duel duel, UUID recipient, double amount, String reason) {
        // Only an actual winner payout is income; every other reason here is giving the player
        // back their own escrowed wager, and counting those as earnings let anyone inflate their
        // lifetime coins-earned stat for free by accepting and immediately aborting wagered duels.
        boolean isPayout = "winner-payout".equals(reason);
        boolean ok = plugin.getEconomyManager().isReady()
                && plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(recipient), amount, isPayout);
        if (!ok) {
            plugin.getLogger().severe("[Duel " + duel.getId() + "] FAILED to deposit " + EconomyManager.format(amount)
                    + " coins to " + recipient + " (reason=" + reason + ") - economy provider rejected the deposit. "
                    + "This player is owed this amount and needs to be paid manually.");
        }
    }

    private void recordStats(Duel duel, UUID winner, DuelResult result, double payoutAmount) {
        if (winner == null || result == DuelResult.TIMEOUT_NO_CONTEST) {
            return;
        }
        UUID loser = duel.opponentOf(winner);
        double wager = duel.getWager();
        duelStatsManager.recordDuelResult(winner, true, wager, payoutAmount, 0);
        duelStatsManager.recordDuelResult(loser, false, wager, 0, wager);
        // Guarded by markStatsRecorded upstream, so this runs exactly once per decided duel, and
        // - unlike its previous home inside recordRankedResult - it now sees wagered duels too.
        // Those are always unranked (the ranked queue only ever creates zero-wager duels), so
        // win-trading for duel_coins_won/duel_wins previously raised no flag whatsoever.
        duelStatsManager.checkFarming(winner, loser);
    }

    private void insertHistoryRow(Duel duel, UUID winner, DuelResult result, double payoutAmount) {
        DatabaseManager.DuelHistoryRow row = new DatabaseManager.DuelHistoryRow(
                duel.getId().toString(), duel.getChallenger(), nameOf(duel.getChallenger()),
                duel.getTarget(), nameOf(duel.getTarget()), winner, duel.getKitId(), duel.getArenaId(),
                duel.getWager(), payoutAmount, result.name(), duel.getStartedAt(), duel.getEndedAt()
        );
        duelStatsManager.insertHistoryAsync(row);
    }

    private void announceResult(Duel duel, UUID winner, DuelResult result, double payoutAmount) {
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());

        if (winner == null || result == DuelResult.TIMEOUT_NO_CONTEST) {
            msg(challenger, "timeout-no-contest", Map.of());
            msg(target, "timeout-no-contest", Map.of());
            String drawTitle = cfg().getString("messages.draw-title", "&f&lDraw");
            String drawSubtitle = cfg().getString("messages.draw-subtitle", "&7No one died");
            if (challenger != null && challenger.isOnline()) {
                MessageUtil.title(challenger, drawTitle, drawSubtitle, 3);
            }
            if (target != null && target.isOnline()) {
                MessageUtil.title(target, drawTitle, drawSubtitle, 3);
            }
            return;
        }

        UUID loserUuid = duel.opponentOf(winner);
        Player winnerPlayer = Bukkit.getPlayer(winner);
        Player loserPlayer = Bukkit.getPlayer(loserUuid);
        String winnerName = nameOf(winner);
        String loserName = nameOf(loserUuid);

        if (winnerPlayer != null && winnerPlayer.isOnline()) {
            if (result == DuelResult.FORFEIT_WIN) {
                msg(winnerPlayer, "forfeit-win", Map.of("opponent", loserName));
            } else if (result == DuelResult.TIMEOUT_WIN) {
                msg(winnerPlayer, "timeout-win", Map.of("winner", winnerName));
            }
            if (payoutAmount > 0) {
                msg(winnerPlayer, "win-wager", Map.of("pot", EconomyManager.format(payoutAmount)));
            }
            int streak = duelStatsManager.getCurrentStreak(winner);
            if (streak > 1) {
                msg(winnerPlayer, "win-streak", Map.of("streak", String.valueOf(streak)));
            }
        }
        if (loserPlayer != null && loserPlayer.isOnline()) {
            if (result == DuelResult.FORFEIT_WIN) {
                msg(loserPlayer, "forfeit-loss", Map.of());
            } else {
                msg(loserPlayer, "loss-message", Map.of("winner", winnerName));
            }
        }

        if (cfg().getBoolean("announcements.enabled", true)
                && duel.getWager() >= cfg().getDouble("announcements.min-wager", 0)) {
            String template = duel.getWager() > 0
                    ? cfg().getString("announcements.broadcast-wager", "")
                    : cfg().getString("announcements.broadcast", "");
            MessageUtil.broadcast(template, Map.of(
                    "winner", winnerName, "loser", loserName, "pot", EconomyManager.format(payoutAmount)
            ));
        }
    }

    private void logPayout(Duel duel, UUID recipient, double amount, String reason) {
        plugin.getLogger().info("[Duel " + duel.getId() + "] payout reason=" + reason
                + " recipient=" + recipient + " amount=" + amount + " challenger=" + duel.getChallenger()
                + " target=" + duel.getTarget() + " wager=" + duel.getWager());
    }

    private void sendGraceMessage(Duel duel) {
        long seconds = Math.max(0L, cfg().getLong("grace-period-seconds", 180));
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());
        msg(challenger, "grace-period", Map.of("seconds", String.valueOf(seconds)));
        msg(target, "grace-period", Map.of("seconds", String.valueOf(seconds)));
    }

    private void sendRematchHint(Duel duel) {
        if (!cfg().getBoolean("rematch.enabled", true)) {
            return;
        }
        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player target = Bukkit.getPlayer(duel.getTarget());
        String cName = nameOf(duel.getChallenger());
        String tName = nameOf(duel.getTarget());

        if (challenger != null && challenger.isOnline()) {
            sendRematchMessage(challenger, tName);
        }
        if (target != null && target.isOnline()) {
            sendRematchMessage(target, cName);
        }
    }

    private void sendRematchMessage(Player player, String opponentName) {
        String text = cfg().getString("rematch.text", "&eWant a rematch against &f{opponent}&e? ");
        String buttonText = cfg().getString("rematch.button", "&6&l[REMATCH]");
        String hoverText = cfg().getString("rematch.hover", "&eClick to challenge &f{opponent} &eto a rematch!");

        Component button = MessageUtil.parse(MessageUtil.apply(buttonText, Map.of("opponent", opponentName)))
                .clickEvent(ClickEvent.runCommand("/duel " + opponentName))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(MessageUtil.apply(hoverText, Map.of("opponent", opponentName)))));

        Component msg = MessageUtil.parse(MessageUtil.apply(text, Map.of("opponent", opponentName)))
                .append(button);

        player.sendMessage(msg);
    }

    /**
     * Shutdown-time drain for a map of owed pre-duel inventories: hand it straight back to anyone
     * still online, and persist it for anyone who is not so their next join applies it. Used for
     * the two windows whose duel row is already deleted, where losing the snapshot here would
     * leave the player permanently holding the plugin-issued duel kit instead of their own items.
     */
    private void flushOwedRestores(Map<UUID, DuelInventorySnapshot> owed) {
        for (Map.Entry<UUID, DuelInventorySnapshot> entry : owed.entrySet()) {
            UUID uuid = entry.getKey();
            DuelInventorySnapshot snapshot = entry.getValue();
            if (uuid == null || snapshot == null) {
                continue;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                snapshot.restore(online);
            } else {
                queuePersistentRestore(uuid, snapshot);
            }
        }
    }

    private void restoreIfOnline(UUID uuid, DuelInventorySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            snapshot.restore(player);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Grace period + /leave
    // ---------------------------------------------------------------------------------------

    public boolean leaveGrace(Player player) {
        UUID duelId = playerGrace.get(player.getUniqueId());
        Duel duel = duelId == null ? null : duels.get(duelId);
        if (duel == null) {
            playerGrace.remove(player.getUniqueId());
            msg(player, "not-in-grace", Map.of());
            return false;
        }
        leaveGraceInternal(duel, player.getUniqueId());
        msg(player, "leave-success", Map.of());
        return true;
    }

    private void leaveGraceInternal(Duel duel, UUID uuid) {
        duel.markLeftGrace(uuid);
        playerGrace.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            teleportBack(player, duel.snapshotFor(uuid));
        }
        if (duel.bothLeftGrace()) {
            releaseGraceArena(duel);
        }
    }

    private void endGrace(UUID duelId) {
        Duel duel = duels.get(duelId);
        if (duel == null) {
            return;
        }
        for (UUID uuid : List.of(duel.getChallenger(), duel.getTarget())) {
            if (duel.getLeftGrace().contains(uuid)) {
                continue;
            }
            duel.markLeftGrace(uuid);
            playerGrace.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                teleportBack(player, duel.snapshotFor(uuid));
            }
        }
        releaseGraceArena(duel);
    }

    private void releaseGraceArena(Duel duel) {
        if (!duel.markGraceReleased()) {
            return;
        }
        if (duel.getArenaId() != null) {
            clearArenaDrops(duel.getArenaId());
            duelArenaManager.release(duel.getArenaId());
        }
        duel.cancelAllTasks();
        duels.remove(duel.getId());
    }

    /**
     * Removes loose item entities inside a duel arena once the duel is fully over.
     *
     * <p>Duel gear is issued by the plugin and every exit path restores the player's real
     * pre-duel inventory over the top of it, so anything a duelist managed to put on the ground
     * mid-fight is pure minted material: the arena is a normal world location that anyone can
     * simply walk into once it is free (and a third party could already stand in it during the
     * fight - the duel's mutual-hide wall is cosmetic, not a physical barrier). Combined with
     * DuelListener's drop block this closes both halves of that duplication route: the block
     * stops the common case, this sweep catches anything that lands there by other means
     * (kit-apply overflow, a plugin-forced drop, an interrupted restore).
     */
    private void clearArenaDrops(String arenaId) {
        Arena arena = duelArenaManager.resolve(arenaId);
        if (arena == null || !arena.hasCenter()) {
            return;
        }
        Location center = arena.getCenter();
        if (center == null || center.getWorld() == null) {
            return;
        }
        double radius = Math.max(1, arena.getRadius()) + 5.0;
        int removed = 0;
        // Chunk-bounded query rather than a scan of every entity in the world: arenas are small
        // relative to a live world, and this runs on the main thread each time a duel resolves.
        for (org.bukkit.entity.Entity entity : center.getWorld().getNearbyEntities(
                center, radius, center.getWorld().getMaxHeight(), radius,
                e -> e instanceof org.bukkit.entity.Item)) {
            Location loc = entity.getLocation();
            double dx = loc.getX() - arena.getX();
            double dz = loc.getZ() - arena.getZ();
            if ((dx * dx) + (dz * dz) > radius * radius) {
                continue;
            }
            entity.remove();
            removed++;
        }
        if (removed > 0) {
            plugin.getLogger().info("[Duel] cleared " + removed + " leftover item(s) from arena " + arenaId + ".");
        }
    }

    public void teleportToSpawn(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location spawn = resolveJoinSpawn();
        if (spawn != null && spawn.getWorld() != null) {
            player.teleport(spawn);
        }
    }

    private void teleportBack(Player player, DuelInventorySnapshot snapshot) {
        teleportToSpawn(player);
    }

    public Location resolveJoinSpawn() {
        String worldName = plugin.getConfig().getString("join.spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            return null;
        }
        double x = plugin.getConfig().getDouble("join.spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("join.spawn.y", 64.0);
        double z = plugin.getConfig().getDouble("join.spawn.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("join.spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("join.spawn.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    public Location consumePendingRespawnLocation(UUID uuid) {
        return uuid == null ? null : pendingRespawnLocations.remove(uuid);
    }

    public DuelInventorySnapshot consumePendingRespawnSnapshot(UUID uuid) {
        return uuid == null ? null : pendingRespawnSnapshots.remove(uuid);
    }

    public void restorePlayerPostDuel(Player player, DuelInventorySnapshot snapshot) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (snapshot != null && !snapshot.isEmpty()) {
            snapshot.restore(player);
        } else {
            // No snapshot, or the player entered the duel empty-handed -> take the duel kit back.
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setItemInOffHand(null);
            if (plugin.getKitManager() != null) {
                plugin.getKitManager().ensureDefaultKit(player);
            }
            // Re-gear through the cooldown-aware path, not KitManager.applyLoadout directly.
            // Going straight to applyLoadout handed out a complete free kit at the end of every
            // duel entered with an empty inventory - and duels are unlimited via the matchmaking
            // queue, so two cooperating players could mint kits as fast as they could requeue,
            // with loadout.cooldown-seconds never once consulted.
            if (plugin.getLoadoutManager() != null) {
                plugin.getLoadoutManager().tryGive(player, false);
            }
        }
        player.updateInventory();
    }

    // ---------------------------------------------------------------------------------------
    // Admin
    // ---------------------------------------------------------------------------------------

    public boolean forceEnd(Player targetPlayer) {
        UUID duelId = playerDuel.get(targetPlayer.getUniqueId());
        Duel duel = duelId == null ? null : duels.get(duelId);
        if (duel == null) {
            return false;
        }
        return switch (duel.getState()) {
            case REQUESTED -> {
                if (duel.transition(DuelState.REQUESTED, DuelState.CANCELLED)) {
                    cleanupPendingRequest(duel);
                    yield true;
                }
                yield false;
            }
            case ACCEPTED, STARTING -> {
                rollbackSetup(duel, "admin-forceend");
                yield true;
            }
            case ACTIVE -> {
                if (duel.transition(DuelState.ACTIVE, DuelState.ENDING)) {
                    finishDuel(duel, null, DuelResult.TIMEOUT_NO_CONTEST);
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    // ---------------------------------------------------------------------------------------
    // Persistence helpers
    // ---------------------------------------------------------------------------------------

    private DatabaseManager.DuelRow toRow(Duel duel) {
        DuelInventorySnapshot cs = duel.getChallengerSnapshot();
        DuelInventorySnapshot ts = duel.getTargetSnapshot();
        return new DatabaseManager.DuelRow(
                duel.getId().toString(), duel.getChallenger(), duel.getTarget(), duel.getKitId(),
                duel.getArenaId() == null ? "" : duel.getArenaId(), duel.getWager(), duel.getState().name(),
                duel.isEscrowed(), duel.isPayoutDone(),
                cs == null ? "" : cs.serialize(), ts == null ? "" : ts.serialize(),
                duel.getCreatedAt(), System.currentTimeMillis()
        );
    }

    private void persistDuelRowSync(Duel duel) {
        plugin.getDatabaseManager().upsertDuel(toRow(duel));
    }

    private void persistDuelRowAsync(Duel duel) {
        DatabaseManager.DuelRow row = toRow(duel);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getDatabaseManager().upsertDuel(row));
    }

    private void deleteDuelRowAsync(UUID duelId) {
        String id = duelId.toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getDatabaseManager().deleteDuel(id));
    }

    // ---------------------------------------------------------------------------------------
    // Arena boundary enforcement
    // ---------------------------------------------------------------------------------------

    private void tickBoundaryCheck() {
        for (Duel duel : duels.values()) {
            if (duel.getState() != DuelState.ACTIVE) {
                continue;
            }
            Arena arena = duelArenaManager.resolve(duel.getArenaId());
            if (arena == null || !arena.hasCenter()) {
                continue;
            }
            checkPlayerBoundary(duel.getChallenger(), arena);
            checkPlayerBoundary(duel.getTarget(), arena);
        }
    }

    private void checkPlayerBoundary(UUID uuid, Arena arena) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        boolean outside = false;
        if (!loc.getWorld().getName().equalsIgnoreCase(arena.getWorldName())) {
            outside = true;
        } else {
            double dx = loc.getX() - arena.getX();
            double dz = loc.getZ() - arena.getZ();
            double r = arena.getRadius();
            if ((dx * dx + dz * dz) > (r * r)) {
                outside = true;
            }
        }

        if (outside) {
            player.damage(2.0); // 1 heart of border damage
            MessageUtil.actionBar(player, "&c&l⚠ OUTSIDE ARENA BOUNDARY! Return immediately! &4(-1❤/s)");
            player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 1.0f, 0.5f);
        }
    }
}
