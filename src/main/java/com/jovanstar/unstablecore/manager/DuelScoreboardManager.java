package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sidebar scoreboard shown to both duelists (and, if they're also watching, spectators) while a
 * duel is ACTIVE: opponent, wager, ranked/unranked, and a live duration timer. There's no other
 * scoreboard/sidebar system anywhere in this plugin to reuse (only a same-named but unrelated
 * `settings.scoreboard` on/off preference toggle existed before this) - this is the first actual
 * implementation, and it respects that existing toggle.
 *
 * <p>The player's pre-duel scoreboard is saved and restored on exit, so this can't clobber
 * whatever another plugin (or the server's default board) had them on.
 */
public final class DuelScoreboardManager {

    private record Session(Scoreboard previous, Team timeTeam, BukkitTask task) {
    }

    private final UnstableCore plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public DuelScoreboardManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfigManager().getDuels().getBoolean("scoreboard.enabled", true);
    }

    private boolean wantsScoreboard(Player player) {
        return plugin.getSettingsManager() == null
                || plugin.getSettingsManager().isEnabled(player, SettingsManager.SCOREBOARD);
    }

    /** Shows the duel sidebar to both participants. Called once the duel becomes ACTIVE. */
    public void start(Duel duel) {
        if (!enabled()) {
            return;
        }
        showTo(duel.getChallenger(), duel.getTarget(), duel);
        showTo(duel.getTarget(), duel.getChallenger(), duel);
    }

    /** Also lets a spectator see the same board while they're watching. */
    public void showToSpectator(Player spectator, Duel duel) {
        if (!enabled() || duel.getState() != DuelState.ACTIVE) {
            return;
        }
        showTo(spectator.getUniqueId(), duel.getChallenger(), duel);
    }

    private void showTo(UUID viewerUuid, UUID opponentUuid, Duel duel) {
        Player viewer = Bukkit.getPlayer(viewerUuid);
        if (viewer == null || !viewer.isOnline() || !wantsScoreboard(viewer)) {
            return;
        }
        // A spectator switching from one duel's board to another's must keep their *original*
        // pre-spectate scoreboard reference, not whatever duel board they were just looking at -
        // otherwise the eventual restore would leave them stuck on a stale duel scoreboard. This
        // is self-contained deliberately: callers never need to stop an old session themselves
        // before switching, this always does the right thing regardless of call order.
        Session existing = sessions.remove(viewerUuid);
        if (existing != null && existing.task() != null) {
            existing.task().cancel();
        }
        Scoreboard previous = existing != null ? existing.previous() : viewer.getScoreboard();
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = plugin.getConfigManager().getDuels().getString("scoreboard.title", "&d&l⚔ DUEL");
        Objective objective = board.registerNewObjective("duel", Criteria.DUMMY, MessageUtil.parse(title));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = new ArrayList<>();
        lines.add("&8&m                    ");
        lines.add("&7Opponent");
        lines.add("&f" + nameOf(opponentUuid));
        lines.add(" ");
        lines.add("&7Wager");
        lines.add(duel.getWager() > 0 ? "&6" + EconomyManager.format(duel.getWager()) + " coins" : "&7None");
        lines.add("  ");
        lines.add("&7Type");
        lines.add(duel.isRanked() ? "&e&lRANKED" : "&7Unranked");
        lines.add("   ");
        lines.add("&7Time");
        String timeEntry = "&a0:00";
        lines.add(timeEntry);
        lines.add("&8&m                     ");

        int score = lines.size();
        Team timeTeam = null;
        ChatColor[] colors = ChatColor.values();
        for (int i = 0; i < lines.size() && i < colors.length; i++) {
            // The entry string itself renders on the sidebar (Team.prefix only decorates around
            // it), so it must be made of nothing but invisible legacy formatting codes - a single
            // unique ChatColor per line, never any visible character.
            String entry = colors[i].toString();
            Team team = board.registerNewTeam("l" + i);
            team.addEntry(entry);
            team.prefix(MessageUtil.parse(lines.get(i)));
            objective.getScore(entry).setScore(score--);
            if (i == lines.size() - 2) {
                timeTeam = team;
            }
        }

        viewer.setScoreboard(board);

        Team finalTimeTeam = timeTeam;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(viewerUuid);
            if (p == null || !p.isOnline() || duel.getState() != DuelState.ACTIVE) {
                stopFor(viewerUuid, true);
                return;
            }
            if (finalTimeTeam != null) {
                long elapsed = Math.max(0L, (System.currentTimeMillis() - duel.getStartedAt()) / 1000L);
                long m = elapsed / 60;
                long s = elapsed % 60;
                finalTimeTeam.prefix(MessageUtil.parse(String.format("&a%d:%02d", m, s)));
            }
        }, 20L, 20L);

        sessions.put(viewerUuid, new Session(previous, timeTeam, task));
    }

    /** Restores the viewer's previous scoreboard. Safe to call on someone with no active session. */
    public void stopFor(UUID viewerUuid, boolean restore) {
        Session session = sessions.remove(viewerUuid);
        if (session == null) {
            return;
        }
        if (session.task() != null) {
            session.task().cancel();
        }
        if (restore) {
            Player p = Bukkit.getPlayer(viewerUuid);
            if (p != null && p.isOnline()) {
                p.setScoreboard(session.previous() != null
                        ? session.previous()
                        : Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    /** Called by DuelManager the moment a duel stops being ACTIVE. */
    public void stop(Duel duel) {
        stopFor(duel.getChallenger(), true);
        stopFor(duel.getTarget(), true);
    }

    public void handleDisconnect(Player player) {
        stopFor(player.getUniqueId(), false);
    }

    public void shutdown() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            stopFor(uuid, true);
        }
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        var off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }
}
