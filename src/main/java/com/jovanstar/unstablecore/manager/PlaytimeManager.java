package com.jovanstar.unstablecore.manager;

import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

public final class PlaytimeManager {

    public long getPlaytimeTicks(Player player) {
        if (player == null) {
            return 0L;
        }
        return player.getStatistic(Statistic.PLAY_ONE_MINUTE);
    }

    public long getPlaytimeTicks(OfflinePlayer player) {
        if (player == null) {
            return 0L;
        }
        if (player.isOnline() && player.getPlayer() != null) {
            return getPlaytimeTicks(player.getPlayer());
        }
        try {
            return player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return 0L;
        }
    }

    public double getPlaytimeHours(Player player) {
        return getPlaytimeTicks(player) / 20.0 / 3600.0;
    }

    public boolean isNewbie(Player player, double maxHours) {
        return getPlaytimeHours(player) < maxHours;
    }
}
