package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class SettingsManager {

    public static final String JOIN_LEAVE = "join_leave";
    public static final String CLEANUP_ALERTS = "cleanup_alerts";
    public static final String KILL_MESSAGES = "kill_messages";
    public static final String STREAK_ALERTS = "streak_alerts";
    public static final String ROTATION_ALERTS = "rotation_alerts";
    public static final String PRIVATE_PROFILE = "private_profile";
    public static final String SCOREBOARD = "scoreboard";
    public static final String DUEL_REQUESTS = "duel_requests";

    private final Map<String, Boolean> defaults = new ConcurrentHashMap<>();
    private final UnstableCore plugin;
    private final Map<UUID, Map<String, Boolean>> settings = new ConcurrentHashMap<>();

    public SettingsManager(UnstableCore plugin) {
        this.plugin = plugin;
        reloadDefaults();
        load();
    }

    public void reloadDefaults() {
        defaults.clear();
        defaults.put(PRIVATE_PROFILE, false);
        if (plugin.getConfig().isConfigurationSection("settings-defaults")) {
            for (String key : plugin.getConfig().getConfigurationSection("settings-defaults").getKeys(false)) {
                defaults.put(key, plugin.getConfig().getBoolean("settings-defaults." + key));
            }
        }
    }

    public boolean defaultEnabled(String key) {
        if (key == null) {
            return true;
        }
        return defaults.getOrDefault(key, true);
    }

    public void load() {
        settings.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        settings.putAll(db.loadAllSettings());
    }

    public void save() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        db.saveAllSettings(settings, this::defaultEnabled);
    }

    /** Save-on-quit for a single player, so a crash between the 5-minute autosaves can't lose
     *  their toggled settings since the last periodic save. */
    public void save(UUID uuid) {
        if (uuid == null) {
            return;
        }
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        Map<String, Boolean> playerSettings = settings.get(uuid);
        if (playerSettings == null || playerSettings.isEmpty()) {
            return;
        }
        Map<String, Boolean> snapshot = Map.copyOf(playerSettings);
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> db.savePlayerSettings(uuid, snapshot, this::defaultEnabled));
    }

    public boolean isEnabled(UUID uuid, String key) {
        if (uuid == null || key == null) {
            return true;
        }
        Map<String, Boolean> map = settings.get(uuid);
        if (map == null) {
            return defaultEnabled(key);
        }
        return map.getOrDefault(key, defaultEnabled(key));
    }

    public boolean isEnabled(Player player, String key) {
        return player != null && isEnabled(player.getUniqueId(), key);
    }

    public boolean toggle(UUID uuid, String key) {
        Map<String, Boolean> map = settings.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>());
        boolean next = !map.getOrDefault(key, defaultEnabled(key));
        map.put(key, next);
        return next;
    }

    public void set(UUID uuid, String key, boolean enabled) {
        settings.computeIfAbsent(uuid, u -> new ConcurrentHashMap<>()).put(key, enabled);
    }

    public Predicate<Player> filter(String key) {
        return player -> isEnabled(player, key);
    }
}
