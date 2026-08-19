package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public final class ConfigManager {

    private final UnstableCore plugin;

    private FileConfiguration afkConfig;
    private FileConfiguration arenasConfig;
    private FileConfiguration tagsConfig;
    private FileConfiguration shopConfig;
    private FileConfiguration rewardsConfig;
    private FileConfiguration bountyConfig;
    private FileConfiguration leaderboardConfig;
    private FileConfiguration duelsConfig;
    private FileConfiguration dataConfig;

    private File afkFile;
    private File arenasFile;
    private File tagsFile;
    private File shopFile;
    private File rewardsFile;
    private File bountyFile;
    private File leaderboardFile;
    private File duelsFile;
    private File dataFile;

    public ConfigManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        afkFile = saveResourceIfMissing("afkzone.yml");
        arenasFile = saveResourceIfMissing("arenas.yml");
        tagsFile = saveResourceIfMissing("tags.yml");
        shopFile = saveResourceIfMissing("shop.yml");
        migrateShopIfOutdated();
        rewardsFile = saveResourceIfMissing("dailyrewards.yml");
        bountyFile = saveResourceIfMissing("bounty.yml");
        leaderboardFile = saveResourceIfMissing("leaderboard.yml");
        duelsFile = saveResourceIfMissing("duels.yml");
        dataFile = new File(plugin.getDataFolder(), "data.yml");

        afkConfig = YamlConfiguration.loadConfiguration(afkFile);
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);
        tagsConfig = YamlConfiguration.loadConfiguration(tagsFile);
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
        bountyConfig = YamlConfiguration.loadConfiguration(bountyFile);
        leaderboardConfig = YamlConfiguration.loadConfiguration(leaderboardFile);
        duelsConfig = YamlConfiguration.loadConfiguration(duelsFile);
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create data.yml", e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private File saveResourceIfMissing(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return file;
    }

    private void migrateShopIfOutdated() {
        File file = new File(plugin.getDataFolder(), "shop.yml");
        if (!file.exists()) {
            return;
        }
        FileConfiguration live = YamlConfiguration.loadConfiguration(file);
        int liveVersion = live.getInt("version", 0);
        try (java.io.InputStream in = plugin.getResource("shop.yml")) {
            if (in == null) {
                return;
            }
            FileConfiguration jar = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            int jarVersion = jar.getInt("version", 0);
            if (jarVersion > liveVersion) {
                plugin.saveResource("shop.yml", true);
                shopFile = new File(plugin.getDataFolder(), "shop.yml");
                plugin.getLogger().info("Updated shop.yml to version " + jarVersion + ".");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not migrate shop.yml", e);
        }
    }

    public void resetShopToDefault() {
        plugin.saveResource("shop.yml", true);
        shopFile = new File(plugin.getDataFolder(), "shop.yml");
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    public void saveArenas() {
        save(arenasConfig, arenasFile);
    }

    public void saveDuels() {
        save(duelsConfig, duelsFile);
    }

    public void saveData() {
        save(dataConfig, dataFile);
    }

    public void reloadArenas() {
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);
    }

    public void reloadAfk() {
        afkConfig = YamlConfiguration.loadConfiguration(afkFile);
    }

    public void reloadTags() {
        tagsConfig = YamlConfiguration.loadConfiguration(tagsFile);
    }

    public void reloadShop() {
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);
    }

    public void reloadRewards() {
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
    }

    public void reloadBounty() {
        bountyConfig = YamlConfiguration.loadConfiguration(bountyFile);
    }

    public void reloadLeaderboard() {
        leaderboardConfig = YamlConfiguration.loadConfiguration(leaderboardFile);
    }

    public void reloadDuels() {
        duelsConfig = YamlConfiguration.loadConfiguration(duelsFile);
    }

    private void save(FileConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
        }
    }

    public FileConfiguration getAfk() {
        return afkConfig;
    }

    public FileConfiguration getArenas() {
        return arenasConfig;
    }

    public FileConfiguration getTags() {
        return tagsConfig;
    }

    public FileConfiguration getShop() {
        return shopConfig;
    }

    public FileConfiguration getRewards() {
        return rewardsConfig;
    }

    public FileConfiguration getBounty() {
        return bountyConfig;
    }

    public FileConfiguration getLeaderboard() {
        return leaderboardConfig;
    }

    public FileConfiguration getDuels() {
        return duelsConfig;
    }

    public FileConfiguration getData() {
        return dataConfig;
    }
}
