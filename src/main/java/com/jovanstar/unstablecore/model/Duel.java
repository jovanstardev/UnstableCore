package com.jovanstar.unstablecore.model;

import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single duel's full lifecycle state. Identified by {@link #getId()} - every long-lived
 * reference to a participant elsewhere in the plugin uses their UUID, never a held Player.
 *
 * <p>All state-mutating methods are only ever called from the main server thread (accept clicks,
 * command execution, and scheduled Bukkit tasks all run there); async work (DB writes) always
 * hops back via {@code Bukkit.getScheduler().runTask} before touching this object again. That
 * removes an entire class of cross-thread races, leaving only same-thread races between
 * different callbacks (accept vs timeout, death vs disconnect, etc.) - those are what the
 * {@link #state} compare-and-set exists to resolve deterministically: exactly one caller ever
 * wins a given transition, everyone else no-ops.
 */
public final class Duel {

    private final UUID id;
    private final UUID challenger;
    private final UUID target;
    private final String kitId;
    private final double wager;
    private final boolean ranked;
    private final long createdAt;

    private volatile String arenaId;
    private volatile long expiresAt;
    private volatile long acceptedAt;
    private volatile long startedAt;
    private volatile long endedAt;
    private volatile long graceEndsAt;

    private final AtomicReference<DuelState> state = new AtomicReference<>(DuelState.REQUESTED);

    private volatile DuelInventorySnapshot challengerSnapshot;
    private volatile DuelInventorySnapshot targetSnapshot;

    private final AtomicBoolean escrowed = new AtomicBoolean(false);
    private final AtomicBoolean payoutDone = new AtomicBoolean(false);
    private final AtomicBoolean statsRecorded = new AtomicBoolean(false);
    private final AtomicBoolean inventoryRestored = new AtomicBoolean(false);
    private final AtomicBoolean rollbackDone = new AtomicBoolean(false);
    private final AtomicBoolean deathProcessed = new AtomicBoolean(false);
    private final AtomicBoolean graceReleased = new AtomicBoolean(false);

    private volatile UUID winner;
    private volatile DuelResult result;

    private final Set<UUID> leftGrace = ConcurrentHashMap.newKeySet();

    private volatile BukkitTask expiryTask;
    private volatile BukkitTask requestTickerTask;
    private volatile BukkitTask countdownTask;
    private volatile BukkitTask maxDurationTask;
    private volatile BukkitTask graceTask;

    public Duel(UUID challenger, UUID target, String kitId, double wager, boolean ranked, long requestTimeoutMs) {
        this.id = UUID.randomUUID();
        this.challenger = challenger;
        this.target = target;
        this.kitId = kitId;
        this.wager = wager;
        this.ranked = ranked;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + Math.max(0L, requestTimeoutMs);
    }

    public UUID getId() {
        return id;
    }

    public UUID getChallenger() {
        return challenger;
    }

    public UUID getTarget() {
        return target;
    }

    public boolean involves(UUID uuid) {
        return uuid != null && (uuid.equals(challenger) || uuid.equals(target));
    }

    public UUID opponentOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (uuid.equals(challenger)) {
            return target;
        }
        if (uuid.equals(target)) {
            return challenger;
        }
        return null;
    }

    public String getKitId() {
        return kitId;
    }

    public double getWager() {
        return wager;
    }

    public boolean isRanked() {
        return ranked;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getArenaId() {
        return arenaId;
    }

    public void setArenaId(String arenaId) {
        this.arenaId = arenaId;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long millisUntilExpiry() {
        return Math.max(0L, expiresAt - System.currentTimeMillis());
    }

    public long getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(long acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public long getGraceEndsAt() {
        return graceEndsAt;
    }

    public void setGraceEndsAt(long graceEndsAt) {
        this.graceEndsAt = graceEndsAt;
    }

    public DuelState getState() {
        return state.get();
    }

    /**
     * Attempts the transition, but only from exactly {@code expectedCurrent}. Returns true iff
     * this caller won the race - false means either the transition itself is invalid, or (far
     * more commonly) another caller already moved the duel past {@code expectedCurrent} first.
     */
    public boolean transition(DuelState expectedCurrent, DuelState next) {
        if (!expectedCurrent.canTransitionTo(next)) {
            return false;
        }
        return state.compareAndSet(expectedCurrent, next);
    }

    /** Attempts a transition from any of the given source states. */
    public boolean transitionFromAny(DuelState next, DuelState... acceptableSources) {
        DuelState current = state.get();
        for (DuelState source : acceptableSources) {
            if (current == source && current.canTransitionTo(next) && state.compareAndSet(current, next)) {
                return true;
            }
        }
        return false;
    }

    public DuelInventorySnapshot getChallengerSnapshot() {
        return challengerSnapshot;
    }

    public void setChallengerSnapshot(DuelInventorySnapshot snapshot) {
        this.challengerSnapshot = snapshot;
    }

    public DuelInventorySnapshot getTargetSnapshot() {
        return targetSnapshot;
    }

    public void setTargetSnapshot(DuelInventorySnapshot snapshot) {
        this.targetSnapshot = snapshot;
    }

    public DuelInventorySnapshot snapshotFor(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (uuid.equals(challenger)) {
            return challengerSnapshot;
        }
        if (uuid.equals(target)) {
            return targetSnapshot;
        }
        return null;
    }

    /** Idempotency guard: returns true only the first time it's called (escrow may run once). */
    public boolean markEscrowed() {
        return escrowed.compareAndSet(false, true);
    }

    public boolean isEscrowed() {
        return escrowed.get();
    }

    /** Idempotency guard for payout - the single source of truth for "has this duel paid out". */
    public boolean markPayoutDone() {
        return payoutDone.compareAndSet(false, true);
    }

    public boolean isPayoutDone() {
        return payoutDone.get();
    }

    public boolean markStatsRecorded() {
        return statsRecorded.compareAndSet(false, true);
    }

    public boolean markInventoryRestored() {
        return inventoryRestored.compareAndSet(false, true);
    }

    public boolean markRollbackDone() {
        return rollbackDone.compareAndSet(false, true);
    }

    /** Idempotency guard against duplicate/simultaneous death events for the same duel. */
    public boolean markDeathProcessed() {
        return deathProcessed.compareAndSet(false, true);
    }

    /** Idempotency guard so the grace-period release (arena free, teleport back) runs exactly once,
     * whichever of "/leave" or the automatic expiry task fires it first. */
    public boolean markGraceReleased() {
        return graceReleased.compareAndSet(false, true);
    }

    public UUID getWinner() {
        return winner;
    }

    public void setWinner(UUID winner) {
        this.winner = winner;
    }

    public UUID getLoser() {
        return winner == null ? null : opponentOf(winner);
    }

    public DuelResult getResult() {
        return result;
    }

    public void setResult(DuelResult result) {
        this.result = result;
    }

    public Set<UUID> getLeftGrace() {
        return leftGrace;
    }

    public boolean markLeftGrace(UUID uuid) {
        return uuid != null && leftGrace.add(uuid);
    }

    public boolean bothLeftGrace() {
        return leftGrace.contains(challenger) && leftGrace.contains(target);
    }

    public BukkitTask getExpiryTask() {
        return expiryTask;
    }

    public void setExpiryTask(BukkitTask expiryTask) {
        this.expiryTask = expiryTask;
    }

    /** Live actionbar countdown ticker shown to both sides while a request is pending. */
    public BukkitTask getRequestTickerTask() {
        return requestTickerTask;
    }

    public void setRequestTickerTask(BukkitTask requestTickerTask) {
        this.requestTickerTask = requestTickerTask;
    }

    public BukkitTask getCountdownTask() {
        return countdownTask;
    }

    public void setCountdownTask(BukkitTask countdownTask) {
        this.countdownTask = countdownTask;
    }

    public BukkitTask getMaxDurationTask() {
        return maxDurationTask;
    }

    public void setMaxDurationTask(BukkitTask maxDurationTask) {
        this.maxDurationTask = maxDurationTask;
    }

    public BukkitTask getGraceTask() {
        return graceTask;
    }

    public void setGraceTask(BukkitTask graceTask) {
        this.graceTask = graceTask;
    }

    /** Cancels every scheduled task tied to this duel. Safe to call more than once. */
    public void cancelAllTasks() {
        cancelQuietly(expiryTask);
        cancelQuietly(requestTickerTask);
        cancelQuietly(countdownTask);
        cancelQuietly(maxDurationTask);
        cancelQuietly(graceTask);
        expiryTask = null;
        requestTickerTask = null;
        countdownTask = null;
        maxDurationTask = null;
        graceTask = null;
    }

    private static void cancelQuietly(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
            }
        }
    }
}
