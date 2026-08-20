package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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

    /** Save-on-quit for a single player, so a crash between the 5-minute autosaves can't lose
     *  their combat streak/deaths since the last periodic save. */
    public void save(UUID uuid) {
        if (uuid == null) {
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        int streak = streaks.getOrDefault(uuid, 0);
        int death = deaths.getOrDefault(uuid, 0);
        boolean titles = titlesEnabled.getOrDefault(uuid, true);
        if (streak <= 0 && death <= 0 && titles) {
            return;
        }
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> db.upsertCombatRow(uuid, streak, death, titles));
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
        playStreakAnnouncer(killer, streak);
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

    /**
     * Plays the announcer voice line for a killstreak that just crossed a configured tier.
     *
     * <p>Only fires on the {@code killstreak.milestones.values} thresholds, never on every kill,
     * and picks the highest tier the streak has reached - so 5 announces "mega kill" and 10 and
     * up announce "monster kill".
     *
     * <p>The tier's {@code sound} is a resource-pack key. A client without the pack silently
     * ignores an unknown key, so the {@code fallback} vanilla sound is played to everyone
     * regardless and the voice simply layers on top for players who have it. That keeps the
     * feature working for every player without needing to know who accepted the pack.
     */
    public void playStreakAnnouncer(Player killer, int streak) {
        if (!plugin.getConfig().getBoolean("killstreak.announcer.enabled", true)) {
            return;
        }
        java.util.List<Integer> milestones = plugin.getConfig().getIntegerList("killstreak.milestones.values");
        if (!milestones.contains(streak)) {
            return;
        }
        org.bukkit.configuration.ConfigurationSection tiers =
                plugin.getConfig().getConfigurationSection("killstreak.announcer.tiers");
        if (tiers == null) {
            return;
        }
        int bestThreshold = -1;
        String bestKey = null;
        for (String key : tiers.getKeys(false)) {
            int threshold;
            try {
                threshold = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (streak >= threshold && threshold > bestThreshold) {
                bestThreshold = threshold;
                bestKey = key;
            }
        }
        if (bestKey == null) {
            return;
        }
        org.bukkit.configuration.ConfigurationSection tier = tiers.getConfigurationSection(bestKey);
        if (tier == null) {
            return;
        }
        String customSound = tier.getString("sound", "");
        String fallbackName = tier.getString("fallback", "");
        float volume = (float) tier.getDouble("volume", 1.0);
        float pitch = (float) tier.getDouble("pitch", 1.0);
        boolean everyone = tier.getBoolean("broadcast", false);

        SoundCategory category;
        try {
            category = SoundCategory.valueOf(
                    plugin.getConfig().getString("killstreak.announcer.category", "MASTER"));
        } catch (IllegalArgumentException ignored) {
            category = SoundCategory.MASTER;
        }
        Sound fallback = null;
        if (!fallbackName.isBlank()) {
            try {
                fallback = Sound.valueOf(fallbackName);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("killstreak.announcer.tiers." + bestKey
                        + ".fallback is not a valid Sound: " + fallbackName);
            }
        }

        if (everyone) {
            for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                // Same toggle that already gates the milestone message, so a player who muted
                // streak alerts does not get the voice line either.
                if (plugin.getSettingsManager() != null
                        && !plugin.getSettingsManager().isEnabled(online, SettingsManager.STREAK_ALERTS)) {
                    continue;
                }
                playAnnouncerTo(online, customSound, fallback, category, volume, pitch);
            }
            return;
        }
        if (plugin.getSettingsManager() == null
                || plugin.getSettingsManager().isEnabled(killer, SettingsManager.STREAK_ALERTS)) {
            playAnnouncerTo(killer, customSound, fallback, category, volume, pitch);
        }
    }

    private void playAnnouncerTo(Player listener, String customSound, Sound fallback,
                                 SoundCategory category, float volume, float pitch) {
        if (listener == null || !listener.isOnline()) {
            return;
        }
        if (fallback != null) {
            listener.playSound(listener.getLocation(), fallback, category, volume, pitch);
        }
        if (customSound != null && !customSound.isBlank()) {
            // Unknown keys are a no-op client-side, so this is safe without the resource pack.
            listener.playSound(listener.getLocation(), customSound, category, volume, pitch);
        }
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
