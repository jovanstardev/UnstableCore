package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelManager;
import com.jovanstar.unstablecore.manager.DuelQueueManager;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Handles /leave:
 * - If in an active duel or countdown: kills the player (forfeiting the duel and respawning at spawn).
 * - If in a queue: leaves the matchmaking queue.
 * - Otherwise: teleports the player directly to server spawn.
 */
public final class LeaveCommand implements CommandExecutor {

    private final UnstableCore plugin;

    public LeaveCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendConfig(sender, "player-only", Map.of());
            return true;
        }

        if (plugin.getSpectatorManager() != null && plugin.getSpectatorManager().isSpectating(player.getUniqueId())) {
            plugin.getSpectatorManager().stopSpectating(player, true);
            return true;
        }

        DuelManager duelMgr = plugin.getDuelManager();
        if (duelMgr != null && duelMgr.isInDuel(player.getUniqueId())) {
            Duel duel = duelMgr.getDuelForPlayer(player.getUniqueId());
            if (duel != null) {
                if (duel.getState() == DuelState.ACTIVE || duel.getState() == DuelState.STARTING || duel.getState() == DuelState.ACCEPTED) {
                    MessageUtil.send(player, "&cYou left the duel.");
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_HURT, org.bukkit.SoundCategory.PLAYERS, 0.8f, 0.8f);
                    player.setHealth(0.0);
                    return true;
                } else if (duel.getState() == DuelState.REQUESTED) {
                    if (player.getUniqueId().equals(duel.getChallenger())) {
                        duelMgr.cancelRequest(player, duel.getId().toString());
                    } else {
                        duelMgr.declineDuel(player, duel.getId().toString());
                    }
                    return true;
                }
            }
        }

        DuelQueueManager queueMgr = plugin.getDuelQueueManager();
        if (queueMgr != null && queueMgr.isInQueue(player.getUniqueId())) {
            queueMgr.leaveQueue(player);
            return true;
        }

        if (duelMgr != null) {
            duelMgr.teleportToSpawn(player);
            MessageUtil.send(player, "&aTeleported to spawn.");
            return true;
        }

        MessageUtil.send(player, "&cYou are not in a duel.");
        return true;
    }
}
