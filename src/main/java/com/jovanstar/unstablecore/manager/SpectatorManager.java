package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * `/spec` support - lets anyone watch an ACTIVE duel without affecting it. Deliberately a
 * separate manager from {@link DuelManager} (which already owns the actual duel state machine)
 * so this stays a thin, easily-auditable layer: it only ever reads duel state, it never mutates
 * it, and {@link DuelManager} calls back into {@link #onDuelEnd(UUID)} at the single point a
 * duel becomes non-spectatable rather than this class polling for that.
 *
 * <p>Isolation is mostly free: putting the spectator in {@link GameMode#SPECTATOR} already stops
 * them taking damage, dealing damage, picking up items, or interacting with blocks/containers -
 * the only thing this class has to manage by hand is poking a hole in DuelManager's mutual-hide
 * visibility wall (every third party is hidden from the two duelists and vice versa) so the
 * spectator can actually see the fight, and undoing that hole cleanly on exit.
 */
public final class SpectatorManager {

    private record PreviousState(Location location, GameMode gameMode) {
    }

    private final UnstableCore plugin;
    private final Map<UUID, UUID> spectatorToDuel = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> duelSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, PreviousState> previousStates = new ConcurrentHashMap<>();

    public SpectatorManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfigManager().getDuels().getBoolean("spectate.enabled", true);
    }

    public boolean isSpectating(UUID uuid) {
        return uuid != null && spectatorToDuel.containsKey(uuid);
    }

    public UUID getSpectatingDuelId(UUID uuid) {
        return uuid == null ? null : spectatorToDuel.get(uuid);
    }

    public int spectatorCount(UUID duelId) {
        Set<UUID> set = duelId == null ? null : duelSpectators.get(duelId);
        return set == null ? 0 : set.size();
    }

    /**
     * Starts (or switches) spectating the duel the given target is currently fighting in.
     * Returns false - with its own error message already sent - if the target isn't in a
     * spectatable duel.
     */
    public boolean startSpectating(Player spectator, Player target) {
        if (spectator.getUniqueId().equals(target.getUniqueId())) {
            msg(spectator, "spectate-self", Map.of());
            return false;
        }
        // A participant in their own live duel can't sidestep into spectating someone else's -
        // that would GameMode.SPECTATOR/teleport them out of their own arena (dodging damage,
        // stranding their opponent) while playerDuel/state still says they're mid-fight. Contrast
        // with DuelManager.runSetupSequence, which already forces the opposite direction (pulling
        // a spectator out before letting them start a duel of their own).
        if (plugin.getDuelManager().isInActiveDuel(spectator.getUniqueId())) {
            msg(spectator, "spectate-busy", Map.of());
            return false;
        }
        Duel duel = plugin.getDuelManager().getDuelForPlayer(target.getUniqueId());
        if (duel == null || duel.getState() != DuelState.ACTIVE) {
            msg(spectator, "spectate-not-dueling", Map.of("target", target.getName()));
            return false;
        }
        if (isSpectating(spectator.getUniqueId()) && duel.getId().equals(getSpectatingDuelId(spectator.getUniqueId()))) {
            stopSpectating(spectator, true);
            return true;
        }

        Player challenger = Bukkit.getPlayer(duel.getChallenger());
        Player opponent = Bukkit.getPlayer(duel.getTarget());
        if (challenger == null || opponent == null) {
            msg(spectator, "spectate-not-dueling", Map.of("target", target.getName()));
            return false;
        }

        // Switching from one duel to another - cleanly exit the old one first without
        // restoring their pre-spectate state (that only happens once, on final exit).
        if (isSpectating(spectator.getUniqueId())) {
            exitCurrent(spectator, false);
        } else {
            previousStates.put(spectator.getUniqueId(),
                    new PreviousState(spectator.getLocation().clone(), spectator.getGameMode()));
        }

        spectatorToDuel.put(spectator.getUniqueId(), duel.getId());
        duelSpectators.computeIfAbsent(duel.getId(), k -> ConcurrentHashMap.newKeySet()).add(spectator.getUniqueId());

        // Poke a hole in the duel's hide-everyone-from-everyone wall for this one spectator.
        spectator.showPlayer(plugin, challenger);
        spectator.showPlayer(plugin, opponent);
        challenger.showPlayer(plugin, spectator);
        opponent.showPlayer(plugin, spectator);

        spectator.setGameMode(GameMode.SPECTATOR);
        spectator.teleport(challenger.getLocation());
        if (plugin.getDuelScoreboardManager() != null) {
            plugin.getDuelScoreboardManager().showToSpectator(spectator, duel);
        }

        msg(spectator, "spectate-started", Map.of(
                "challenger", challenger.getName(), "target", opponent.getName()
        ));
        return true;
    }

    /** Public exit, e.g. from `/spec` toggled off or `/leave` while spectating. */
    public void stopSpectating(Player spectator, boolean notify) {
        if (!isSpectating(spectator.getUniqueId())) {
            return;
        }
        exitCurrent(spectator, true);
        if (notify) {
            msg(spectator, "spectate-stopped", Map.of());
        }
    }

    private void exitCurrent(Player spectator, boolean restore) {
        UUID specUuid = spectator.getUniqueId();
        UUID duelId = spectatorToDuel.remove(specUuid);
        if (duelId == null) {
            return;
        }
        // Only tear down the scoreboard session on a genuine final exit (restore=true). A
        // duel-switch (restore=false) leaves it alone entirely - showToSpectator's own showTo()
        // is self-contained and correctly carries the session forward into the next duel's board
        // when it finds one still active, without needing this to touch it first.
        if (restore && plugin.getDuelScoreboardManager() != null) {
            plugin.getDuelScoreboardManager().stopFor(specUuid, true);
        }
        Set<UUID> set = duelSpectators.get(duelId);
        if (set != null) {
            set.remove(specUuid);
            if (set.isEmpty()) {
                duelSpectators.remove(duelId);
            }
        }

        // If the duel is still going, re-seal the hole we poked in its visibility wall. If it
        // already ended, DuelManager's own removeDuelVisibility already restored everyone
        // globally, so there's nothing left to re-hide.
        Duel duel = plugin.getDuelManager().getDuel(duelId);
        if (duel != null && duel.getState().isActiveCombat()) {
            Player challenger = Bukkit.getPlayer(duel.getChallenger());
            Player opponent = Bukkit.getPlayer(duel.getTarget());
            if (challenger != null) {
                spectator.hidePlayer(plugin, challenger);
                challenger.hidePlayer(plugin, spectator);
            }
            if (opponent != null) {
                spectator.hidePlayer(plugin, opponent);
                opponent.hidePlayer(plugin, spectator);
            }
        }

        if (restore) {
            PreviousState prev = previousStates.remove(specUuid);
            restoreState(spectator, prev);
        }
    }

    private void restoreState(Player spectator, PreviousState prev) {
        if (!spectator.isOnline()) {
            return;
        }
        spectator.setGameMode(prev != null && prev.gameMode() != null ? prev.gameMode() : GameMode.SURVIVAL);
        if (prev != null && prev.location() != null && prev.location().getWorld() != null) {
            spectator.teleport(prev.location());
        }
    }

    /** Called by DuelManager the moment a duel it's tracking becomes non-spectatable. */
    public void onDuelEnd(UUID duelId) {
        Set<UUID> set = duelSpectators.remove(duelId);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (UUID uuid : new ArrayList<>(set)) {
            spectatorToDuel.remove(uuid);
            PreviousState prev = previousStates.remove(uuid);
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                continue;
            }
            // The duel just ended, so DuelManager's own removeDuelVisibility already restores
            // everyone's visibility globally - only gamemode/location are ours to undo here.
            restoreState(p, prev);
            msg(p, "spectate-duel-ended", Map.of());
        }
    }

    /** Called from PlayerListener.onQuit. */
    public void handleDisconnect(Player player) {
        UUID uuid = player.getUniqueId();
        UUID duelId = spectatorToDuel.remove(uuid);
        previousStates.remove(uuid);
        if (duelId == null) {
            return;
        }
        Set<UUID> set = duelSpectators.get(duelId);
        if (set != null) {
            set.remove(uuid);
            if (set.isEmpty()) {
                duelSpectators.remove(duelId);
            }
        }
    }

    /** Called from onDisable - don't leave anyone stuck in spectator mode across a restart. */
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(spectatorToDuel.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                stopSpectating(p, false);
            }
        }
        spectatorToDuel.clear();
        duelSpectators.clear();
        previousStates.clear();
    }

    public List<UUID> spectatorsOf(UUID duelId) {
        Set<UUID> set = duelId == null ? null : duelSpectators.get(duelId);
        return set == null ? List.of() : List.copyOf(set);
    }

    private void msg(Player player, String key, Map<String, String> placeholders) {
        String raw = plugin.getConfigManager().getDuels().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        MessageUtil.send(player, MessageUtil.apply(raw, placeholders));
    }
}
