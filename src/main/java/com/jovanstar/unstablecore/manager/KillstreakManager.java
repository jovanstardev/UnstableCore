package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class KillstreakManager {

    private final UnstableCore plugin;
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> titlesEnabled = new ConcurrentHashMap<>();

    public KillstreakManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        streaks.clear();
        deaths.clear();
        titlesEnabled.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        for (Map.Entry<UUID, DatabaseManager.CombatRow> e : db.loadAllCombat().entrySet()) {
            DatabaseManager.CombatRow row = e.getValue();
            if (row.streak() > 0) {
                streaks.put(e.getKey(), row.streak());
            }
            if (row.deaths() > 0) {
                deaths.put(e.getKey(), row.deaths());
            }
            if (!row.titlesEnabled()) {
                titlesEnabled.put(e.getKey(), false);
            }
        }
    }

    public void reload() {

    }

    public void save() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        db.saveAllCombat(streaks, deaths, titlesEnabled);
    }

    public int getStreak(UUID uuid) {
        return streaks.getOrDefault(uuid, 0);
    }

    public int getDeaths(UUID uuid) {
        return deaths.getOrDefault(uuid, 0);
    }

    public java.util.Set<UUID> trackedUuids() {
        java.util.Set<UUID> set = new java.util.HashSet<>(streaks.keySet());
        set.addAll(deaths.keySet());
        set.addAll(titlesEnabled.keySet());
        return set;
    }

    public Map<UUID, Integer> trackedDeaths() {
        return java.util.Collections.unmodifiableMap(deaths);
    }

    public int addDeath(UUID uuid) {
        return deaths.merge(uuid, 1, Integer::sum);
    }

    public void reset(UUID uuid) {
        streaks.put(uuid, 0);
    }

    public int addKill(Player killer) {
        int gain = 1;
        if (plugin.getEventManager() != null) {
            gain = plugin.getEventManager().streakGain();
        }
        int streak = streaks.merge(killer.getUniqueId(), gain, Integer::sum);
        plugin.getStatsManager().addKill(killer.getUniqueId());
        plugin.getStatsManager().updateBestStreak(killer.getUniqueId(), streak);
        if (plugin.getLeaderboardManager() != null) {
            plugin.getLeaderboardManager().rememberPlayer(killer);
        }
        int titleMinimum = Math.max(1, plugin.getConfig().getInt("killstreak.title-minimum", 2));
        if (streak >= titleMinimum && isTitlesEnabled(killer.getUniqueId())) {
            int seconds = plugin.getConfig().getInt("killstreak.title-seconds", 2);
            MessageUtil.title(
                    killer,
                    MessageUtil.apply(plugin.getConfig().getString("messages.killstreak-title", "&a&l{streak}"),
                            Map.of("streak", String.valueOf(streak))),
                    plugin.getConfig().getString("messages.killstreak-subtitle", "&a&lKILLSTREAK!"),
                    seconds
            );
            try {
                Sound sound = Sound.valueOf(plugin.getConfig().getString("killstreak.sound", "ENTITY_PLAYER_LEVELUP"));
                float vol = (float) plugin.getConfig().getDouble("killstreak.sound-volume", 1.0);
                float pitch = (float) plugin.getConfig().getDouble("killstreak.sound-pitch", 1.5);
                killer.playSound(killer.getLocation(), sound, vol, pitch);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return streak;
    }

    public void broadcastMilestone(Player killer, int streak) {
        if (!plugin.getConfig().getBoolean("killstreak.milestones.enabled", true)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("kill-messages.enabled", true)) {
            return;
        }
        java.util.List<Integer> milestones = plugin.getConfig().getIntegerList("killstreak.milestones.values");
        if (milestones.isEmpty() || !milestones.contains(streak)) {
            return;
        }
        String icon = plugin.getConfig().getString("kill-messages.icon", "&c⚔");
        MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.milestone", ""), Map.of(
                "icon", icon,
                "killer", killer.getName(),
                "streak", String.valueOf(streak)
        ), p -> plugin.getSettingsManager().isEnabled(p, SettingsManager.KILL_MESSAGES)
                && plugin.getSettingsManager().isEnabled(p, SettingsManager.STREAK_ALERTS));
    }

    public boolean isTitlesEnabled(UUID uuid) {
        return titlesEnabled.getOrDefault(uuid, true);
    }

    public boolean toggleTitles(UUID uuid) {
        boolean next = !isTitlesEnabled(uuid);
        titlesEnabled.put(uuid, next);
        return next;
    }

    public void clear(UUID uuid) {
        streaks.remove(uuid);
    }
}
