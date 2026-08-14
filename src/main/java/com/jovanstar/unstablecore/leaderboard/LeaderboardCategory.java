package com.jovanstar.unstablecore.leaderboard;

import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.StatsManager;

import java.util.Locale;

public enum LeaderboardCategory {
    COINS("coins"),
    KILLS("kills"),
    BIGGEST_KILLSTREAK("biggest_killstreak"),
    DEATHS("deaths"),
    PLAYTIME("playtime"),
    DUEL_WINS("duel_wins"),
    DUEL_BEST_STREAK("duel_best_streak"),
    DUEL_COINS_WON("duel_coins_won"),
    DUEL_ELO("duel_elo");

    private final String id;

    LeaderboardCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static LeaderboardCategory fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        if (key.equals("money") || key.equals("coin") || key.equals("balance")) {
            return COINS;
        }
        if (key.equals("kill")) {
            return KILLS;
        }
        if (key.equals("streak") || key.equals("best_streak") || key.equals("killstreak")
                || key.equals("biggeststreak") || key.equals("ks")) {
            return BIGGEST_KILLSTREAK;
        }
        if (key.equals("death")) {
            return DEATHS;
        }
        if (key.equals("time") || key.equals("pt")) {
            return PLAYTIME;
        }
        if (key.equals("duelwins") || key.equals("duel_win")) {
            return DUEL_WINS;
        }
        if (key.equals("duelstreak") || key.equals("duel_streak") || key.equals("dueleststreak")) {
            return DUEL_BEST_STREAK;
        }
        if (key.equals("duelcoins") || key.equals("duel_coins") || key.equals("duelwinnings")) {
            return DUEL_COINS_WON;
        }
        if (key.equals("elo") || key.equals("rank") || key.equals("duelrank")) {
            return DUEL_ELO;
        }
        for (LeaderboardCategory cat : values()) {
            if (cat.id.equals(key)) {
                return cat;
            }
        }
        return null;
    }

    public String formatValue(double value) {
        return switch (this) {
            case COINS, DUEL_COINS_WON -> EconomyManager.formatCommas(value);
            case PLAYTIME -> formatPlaytimeTicks((long) value);
            default -> String.valueOf((long) Math.floor(value));
        };
    }

    private static String formatPlaytimeTicks(long ticks) {
        long totalSec = Math.max(0L, ticks / 20L);
        long d = totalSec / 86400;
        long h = (totalSec % 86400) / 3600;
        long m = (totalSec % 3600) / 60;
        if (d > 0) {
            return d + "d " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return Math.max(0, m) + "m";
    }

    
    public static String formatPlaytimeOfflineTicks(long ticks) {
        return formatPlaytimeTicks(ticks);
    }

    public static String formatPlaytimePlayer(org.bukkit.entity.Player player) {
        return StatsManager.formatPlaytime(player);
    }
}
