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

    public record WagerPrompt(UUID target, String arenaId) {
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
        duelArenaManager.releaseAll();
        for (DatabaseManager.DuelRow row : plugin.getDatabaseManager().loadAllDuels()) {
            recoverRow(row);
        }
    }

    private void recoverRow(DatabaseManager.DuelRow row) {
        if (row.escrowed() && !row.payoutDone() && row.wager() > 0) {
            EconomyManager economy = plugin.getEconomyManager();
            boolean okC = economy.isReady() && economy.deposit(Bukkit.getOfflinePlayer(row.challenger()), row.wager());
            boolean okT = economy.isReady() && economy.deposit(Bukkit.getOfflinePlayer(row.target()), row.wager());
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
            pendingCrashRestores.put(uuid, snapshot);
        }
    }

    /** Called from PlayerListener.onJoin - restores an interrupted duel's pre-duel inventory, if any. */
    public void applyPendingCrashRestore(Player player) {
        DuelInventorySnapshot snapshot = pendingCrashRestores.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        snapshot.restore(player);
        plugin.getLogger().info("[Duel] restored pre-duel inventory for " + player.getName()
                + " after a server restart interrupted their duel.");
        MessageUtil.send(player, "&eYour inventory from an interrupted duel has been restored.");
    }

    /** Called from onDisable - graceful refund/restore for anything still in flight. */
    public void shutdown() {
        for (Duel duel : new ArrayList<>(duels.values())) {
            duel.cancelAllTasks();
            DuelState state = duel.getState();
            if (state == DuelState.REQUESTED) {
                continue;
            }
            if (duel.markInventoryRestored()) {
                restoreIfOnline(duel.getChallenger(), duel.getChallengerSnapshot());
                restoreIfOnline(duel.getTarget(), duel.getTargetSnapshot());
            }
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
        duels.clear();
        playerDuel.clear();
        playerGrace.clear();
        wagerPrompts.clear();
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

    private String defaultKitId() {
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

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
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
        return plugin.getCombatListener() != null && plugin.getCombatListener().isCombatTagged(player.getUniqueId());
    }

    /** Final validation + creation, called once the wager amount is known. Sends its own error messages. */
    public Duel createRequest(Player challenger, Player target, String arenaId, double rawWager) {
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

        long timeoutMs = Math.max(5000L, cfg().getLong("request.timeout-seconds", 30) * 1000L);
        Duel duel = new Duel(challenger.getUniqueId(), target.getUniqueId(), defaultKitId(), wager, false, timeoutMs);
        duel.setArenaId(arenaId.toLowerCase(Locale.ROOT));

        duels.put(duel.getId(), duel);
        playerDuel.put(challenger.getUniqueId(), duel.getId());
        playerDuel.put(target.getUniqueId(), duel.getId());
        lastRequestSentAt.put(challenger.getUniqueId(), System.currentTimeMillis());

        Bukkit.getPluginManager().callEvent(new DuelCreateEvent(duel));

        msg(challenger, "request-sent", Map.of("target", target.getName()));
        sendRequestPrompt(duel);
        scheduleExpiry(duel);

        plugin.getLogger().info("[Duel " + duel.getId() + "] requested: " + challenger.getName()
                + " -> " + target.getName() + " arena=" + arenaId + " wager=" + wager);
        return duel;
    }

    private void sendRequestPrompt(Duel duel) {
        Player target = Bukkit.getPlayer(duel.getTarget());
        if (target != null && target.isOnline()) {
            target.sendMessage(buildRequestComponent(duel));
        }
        long refreshMs = Math.max(1000L, cfg().getLong("request.chat-refresh-seconds", 5) * 1000L);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (duel.getState() != DuelState.REQUESTED) {
                BukkitTask self = duel.getChatRefreshTask();
                if (self != null) {
                    self.cancel();
                }
                return;
            }
            Player t = Bukkit.getPlayer(duel.getTarget());
            if (t != null && t.isOnline()) {
                t.sendMessage(buildRequestComponent(duel));
            }
        }, refreshMs / 50L, refreshMs / 50L);
        duel.setChatRefreshTask(task);
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

        Component accept = MessageUtil.parse(cfg().getString("messages.accept-button", "&a[ACCEPT]"))
                .clickEvent(ClickEvent.runCommand("/duel accept " + duel.getId()))
                .hoverEvent(HoverEvent.showText(MessageUtil.parse(cfg().getString("messages.accept-hover", ""))));
        Component deny = MessageUtil.parse(cfg().getString("messages.deny-button", "&c[DENY]"))
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
        pairCooldownUntil.put(pairKey(a, b), System.currentTimeMillis() + ms);
    }

    // ---------------------------------------------------------------------------------------
    // Wager chat prompt (mirrors BountyManager's Prompt pattern)
    // ---------------------------------------------------------------------------------------

    public void beginWagerPrompt(Player challenger, Player target, String arenaId) {
        wagerPrompts.put(challenger.getUniqueId(), new WagerPrompt(target.getUniqueId(), arenaId));
        double min = Math.max(0, cfg().getDouble("wager.min", 0));
        double max = Math.max(min, cfg().getDouble("wager.max", 1_000_000));
        msg(challenger, "wager-prompt", Map.of(
                "target", target.getName(),
                "min", EconomyManager.format(min),
                "max", EconomyManager.format(max)
        ));
    }

    public WagerPrompt peekWagerPrompt(UUID uuid) {
        return uuid == null ? null : wagerPrompts.get(uuid);
    }

    public void clearWagerPrompt(UUID uuid) {
        if (uuid != null) {
            wagerPrompts.remove(uuid);
        }
    }

    public boolean handleChat(Player player, String raw) {
        WagerPrompt prompt = wagerPrompts.get(player.getUniqueId());
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
        createRequest(player, target, prompt.arenaId(), amount);
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

        Location spot1 = plugin.getArenaManager().findSafeSpot(arena);
        Location spot2 = plugin.getArenaManager().findSafeSpot(arena);
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
                economy.deposit(challenger, duel.getWager());
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
        if (duel.getArenaId() != null) {
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
                remaining[0]--;
                return;
            }
            if (duel.transition(DuelState.STARTING, DuelState.ACTIVE)) {
                duel.setStartedAt(System.currentTimeMillis());
                String fight = cfg().getString("messages.fight", "&a&lFIGHT!");
                MessageUtil.send(c, fight);
                MessageUtil.send(t, fight);
                Bukkit.getPluginManager().callEvent(new DuelStartEvent(duel));
                persistDuelRowAsync(duel);
                scheduleMaxDuration(duel);
            }
            BukkitTask self = duel.getCountdownTask();
            if (self != null) {
                self.cancel();
            }
            duel.setCountdownTask(null);
        }, 0L, 20L);
        duel.setCountdownTask(task);
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

    /** Called by DuelListener's PlayerDeathEvent handler. No-op if the victim isn't in an active duel. */
    public void handleDeath(Player victim) {
        Duel duel = getDuelForPlayer(victim.getUniqueId());
        if (duel == null || duel.getState() != DuelState.ACTIVE) {
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

    /** Called from PlayerListener.onQuit. */
    public void handleDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        clearWagerPrompt(uuid);

        UUID duelId = playerDuel.get(uuid);
        if (duelId != null) {
            Duel duel = duels.get(duelId);
            if (duel != null) {
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

        if (duel.markInventoryRestored()) {
            restoreIfOnline(duel.getChallenger(), duel.getChallengerSnapshot());
            restoreIfOnline(duel.getTarget(), duel.getTargetSnapshot());
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
        playerGrace.put(duel.getChallenger(), duel.getId());
        playerGrace.put(duel.getTarget(), duel.getId());

        long graceMs = Math.max(0L, cfg().getLong("grace-period-seconds", 180) * 1000L);
        duel.setGraceEndsAt(System.currentTimeMillis() + graceMs);
        if (duel.getArenaId() != null) {
            duelArenaManager.enterGrace(duel.getArenaId(), duel.getGraceEndsAt());
        }
        BukkitTask graceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> endGrace(duel.getId()),
                Math.max(1L, graceMs / 50L));
        duel.setGraceTask(graceTask);

        deleteDuelRowAsync(duel.getId());
        sendGraceMessage(duel);
        sendRematchHint(duel);

        plugin.getLogger().info("[Duel " + duel.getId() + "] finished result=" + result
                + " winner=" + winner + " payout=" + payoutAmount);
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
        boolean ok = plugin.getEconomyManager().isReady()
                && plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(recipient), amount);
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
        msg(challenger, "rematch-offered", Map.of("opponent", nameOf(duel.getTarget())));
        msg(target, "rematch-offered", Map.of("opponent", nameOf(duel.getChallenger())));
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
            duelArenaManager.release(duel.getArenaId());
        }
        duel.cancelAllTasks();
        duels.remove(duel.getId());
    }

    private void teleportBack(Player player, DuelInventorySnapshot snapshot) {
        Location loc = snapshot == null ? null : snapshot.getLocation();
        if (loc != null && loc.getWorld() != null) {
            player.teleport(loc);
            return;
        }
        Location fallback = resolveJoinSpawn();
        if (fallback != null) {
            player.teleport(fallback);
        }
    }

    private Location resolveJoinSpawn() {
        String worldName = plugin.getConfig().getString("join.spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = plugin.getConfig().getDouble("join.spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("join.spawn.y", 64.0);
        double z = plugin.getConfig().getDouble("join.spawn.z", 0.5);
        return new Location(world, x, y, z);
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
}
