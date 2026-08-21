package com.jovanstar.unstablecore;

import com.jovanstar.unstablecore.command.ArenasCommand;
import com.jovanstar.unstablecore.command.BountyCommand;
import com.jovanstar.unstablecore.command.DisposalCommand;
import com.jovanstar.unstablecore.command.KillstreakCommand;
import com.jovanstar.unstablecore.command.KitCommand;
import com.jovanstar.unstablecore.command.KitsCommand;
import com.jovanstar.unstablecore.command.LeaderboardCommand;
import com.jovanstar.unstablecore.command.LoadoutCommand;
import com.jovanstar.unstablecore.command.MapVoteCommand;
import com.jovanstar.unstablecore.command.RewardsCommand;
import com.jovanstar.unstablecore.command.SettingsCommand;
import com.jovanstar.unstablecore.command.ShopCommand;
import com.jovanstar.unstablecore.command.StatsCommand;
import com.jovanstar.unstablecore.command.SwordCommand;
import com.jovanstar.unstablecore.command.TagsCommand;
import com.jovanstar.unstablecore.command.UnstableCoreCommand;
import com.jovanstar.unstablecore.listener.ArenaListener;
import com.jovanstar.unstablecore.listener.AntiGlitchListener;
import com.jovanstar.unstablecore.listener.BountyListener;
import com.jovanstar.unstablecore.listener.CombatListener;
import com.jovanstar.unstablecore.listener.GuiListener;
import com.jovanstar.unstablecore.listener.HeldShulkerListener;
import com.jovanstar.unstablecore.listener.LeaderboardListener;
import com.jovanstar.unstablecore.listener.PlayerListener;
import com.jovanstar.unstablecore.manager.ActionBarManager;
import com.jovanstar.unstablecore.manager.AfkZoneManager;
import com.jovanstar.unstablecore.manager.ArenaManager;
import com.jovanstar.unstablecore.manager.BountyManager;
import com.jovanstar.unstablecore.manager.ConfigManager;
import com.jovanstar.unstablecore.manager.DatabaseManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.EventManager;
import com.jovanstar.unstablecore.manager.ItemCleanupManager;
import com.jovanstar.unstablecore.manager.KillstreakManager;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.manager.LeaderboardManager;
import com.jovanstar.unstablecore.manager.LiveGuiRefresher;
import com.jovanstar.unstablecore.manager.LoadoutManager;
import com.jovanstar.unstablecore.manager.MapVoteManager;
import com.jovanstar.unstablecore.manager.PlaytimeManager;
import com.jovanstar.unstablecore.manager.RewardsManager;
import com.jovanstar.unstablecore.manager.SettingsManager;
import com.jovanstar.unstablecore.manager.ShopManager;
import com.jovanstar.unstablecore.manager.StatsManager;
import com.jovanstar.unstablecore.manager.TagManager;
import com.jovanstar.unstablecore.placeholder.UnstablePlaceholders;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class UnstableCore extends JavaPlugin {

    private static UnstableCore instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private AfkZoneManager afkZoneManager;
    private ArenaManager arenaManager;
    private EventManager eventManager;
    private KillstreakManager killstreakManager;
    private PlaytimeManager playtimeManager;
    private TagManager tagManager;
    private ShopManager shopManager;
    private KitManager kitManager;
    private LoadoutManager loadoutManager;
    private ActionBarManager actionBarManager;
    private MapVoteManager mapVoteManager;
    private LiveGuiRefresher liveGuiRefresher;
    private StatsManager statsManager;
    private SettingsManager settingsManager;
    private RewardsManager rewardsManager;
    private BountyManager bountyManager;
    private LeaderboardManager leaderboardManager;
    private ItemCleanupManager itemCleanupManager;
    private ArenaListener arenaListener;
    private CombatListener combatListener;
    private HeldShulkerListener heldShulkerListener;
    private org.bukkit.scheduler.BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        instance = this;

        this.configManager = new ConfigManager(this);
        this.configManager.loadAll();

        MessageUtil.init(this);

        this.databaseManager = new DatabaseManager(this);
        try {
            this.databaseManager.connect();
        } catch (SQLException e) {
            getLogger().severe("Failed to connect to the database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.statsManager = new StatsManager(this);
        this.settingsManager = new SettingsManager(this);
        this.rewardsManager = new RewardsManager(this);

        this.economyManager = new EconomyManager(this);
        this.economyManager.setup();

        this.bountyManager = new BountyManager(this);

        this.playtimeManager = new PlaytimeManager();
        this.killstreakManager = new KillstreakManager(this);
        this.leaderboardManager = new LeaderboardManager(this);
        this.tagManager = new TagManager(this);
        this.tagManager.load();

        this.mapVoteManager = new MapVoteManager(this);

        this.arenaManager = new ArenaManager(this);
        this.arenaManager.load();

        this.eventManager = new EventManager(this);
        this.eventManager.start();

        this.afkZoneManager = new AfkZoneManager(this);
        this.afkZoneManager.start();

        this.shopManager = new ShopManager(this);
        this.kitManager = new KitManager(this);
        this.loadoutManager = new LoadoutManager(this);
        this.actionBarManager = new ActionBarManager(this);
        this.actionBarManager.start();

        this.liveGuiRefresher = new LiveGuiRefresher(this);
        this.liveGuiRefresher.start();

        this.itemCleanupManager = new ItemCleanupManager(this);
        this.itemCleanupManager.start();

        autosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            killstreakManager.save();
            statsManager.save();
            settingsManager.save();
            loadoutManager.save();
            if (kitManager != null) {
                kitManager.savePlayerData();
            }
            tagManager.save();
            arenaManager.shutdownSaveDataOnly();
        }, 20L * 60 * 5, 20L * 60 * 5);

        registerCommands();
        registerListeners();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new UnstablePlaceholders(this).register();
            getLogger().info("PlaceholderAPI hooked (identifier: uc).");
        }

        getLogger().info("UnstableCore enabled.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (itemCleanupManager != null) {
            itemCleanupManager.stop();
        }
        if (mapVoteManager != null) {
            mapVoteManager.cancel();
        }
        if (actionBarManager != null) {
            actionBarManager.stop();
        }
        if (liveGuiRefresher != null) {
            liveGuiRefresher.stop();
        }
        if (afkZoneManager != null) {
            afkZoneManager.stop();
        }
        if (eventManager != null) {
            eventManager.stop();
        }
        if (arenaManager != null) {
            arenaManager.shutdown();
        }
        if (killstreakManager != null) {
            killstreakManager.save();
        }
        if (statsManager != null) {
            statsManager.save();
        }
        if (settingsManager != null) {
            settingsManager.save();
        }
        if (loadoutManager != null) {
            loadoutManager.save();
        }
        if (kitManager != null) {
            kitManager.savePlayerData();
        }
        if (tagManager != null) {
            tagManager.save();
        }
        if (leaderboardManager != null) {
            leaderboardManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("UnstableCore disabled.");
    }

    public void reloadPlugin() {
        if (loadoutManager != null) {
            loadoutManager.save();
        }
        if (kitManager != null) {
            kitManager.savePlayerData();
        }
        if (arenaManager != null) {
            // Flush arenas.yml and the dirty placed-block set BEFORE loadAll() swaps in a freshly
            // read data.yml. arenaManager.reload() below repopulates placedBlocks from whatever is
            // on disk, so without this every block players placed since the last autosave (up to
            // 5 minutes' worth) is forgotten - and forgotten player-placed blocks are treated as
            // natural terrain, which the arena protection then refuses to let anyone break.
            arenaManager.shutdown();
        }
        configManager.loadAll();
        MessageUtil.init(this);
        tagManager.load();
        arenaManager.reload();
        afkZoneManager.reload();
        eventManager.reload();
        killstreakManager.reload();
        if (kitManager != null) {
            kitManager.load();
        }
        loadoutManager.load();
        actionBarManager.reload();
        if (liveGuiRefresher != null) {
            liveGuiRefresher.start();
        }
        if (settingsManager != null) {
            settingsManager.reloadDefaults();
        }
        if (itemCleanupManager != null) {
            itemCleanupManager.reload();
        }
        if (bountyManager != null) {
            bountyManager.reload();
        }
        if (leaderboardManager != null) {
            leaderboardManager.clearCache();
        }
        if (arenaListener != null) {
            arenaListener.reloadSettings();
        }
    }

    private void registerCommands() {
        UnstableCoreCommand core = new UnstableCoreCommand(this);
        getCommand("unstablecore").setExecutor(core);
        getCommand("unstablecore").setTabCompleter(core);

        ArenasCommand arenas = new ArenasCommand(this);
        getCommand("arenas").setExecutor(arenas);

        getCommand("sword").setExecutor(new SwordCommand(this));
        getCommand("loadout").setExecutor(new LoadoutCommand(this));
        getCommand("kits").setExecutor(new KitsCommand(this));
        getCommand("mapvote").setExecutor(new MapVoteCommand(this));
        KitCommand kit = new KitCommand(this);
        getCommand("kit").setExecutor(kit);
        getCommand("kit").setTabCompleter(kit);
        getCommand("shop").setExecutor(new ShopCommand(this));

        StatsCommand stats = new StatsCommand(this);
        getCommand("stats").setExecutor(stats);
        getCommand("stats").setTabCompleter(stats);

        getCommand("settings").setExecutor(new SettingsCommand(this));

        RewardsCommand rewards = new RewardsCommand(this);
        getCommand("rewards").setExecutor(rewards);
        getCommand("rewards").setTabCompleter(rewards);
        getCommand("daily").setExecutor(rewards);
        getCommand("daily").setTabCompleter(rewards);

        DisposalCommand disposal = new DisposalCommand(this);
        getCommand("trash").setExecutor(disposal);
        getCommand("disposal").setExecutor(disposal);

        KillstreakCommand ks = new KillstreakCommand(this);
        getCommand("killstreak").setExecutor(ks);
        getCommand("killstreaktoggle").setExecutor(ks);
        getCommand("resetkillstreak").setExecutor(ks);

        TagsCommand tags = new TagsCommand(this);
        getCommand("tags").setExecutor(tags);
        getCommand("emojitags").setExecutor(tags);
        getCommand("ranktags").setExecutor(tags);
        getCommand("auratags").setExecutor(tags);

        BountyCommand bounty = new BountyCommand(this);
        getCommand("bounty").setExecutor(bounty);
        getCommand("bounty").setTabCompleter(bounty);

        LeaderboardCommand leaderboard = new LeaderboardCommand(this);
        getCommand("leaderboard").setExecutor(leaderboard);
        getCommand("leaderboard").setTabCompleter(leaderboard);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        this.combatListener = new CombatListener(this);
        Bukkit.getPluginManager().registerEvents(combatListener, this);
        this.arenaListener = new ArenaListener(this);
        Bukkit.getPluginManager().registerEvents(arenaListener, this);
        Bukkit.getPluginManager().registerEvents(new AntiGlitchListener(this), this);
        this.heldShulkerListener = new HeldShulkerListener(this);
        Bukkit.getPluginManager().registerEvents(heldShulkerListener, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BountyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new LeaderboardListener(this), this);
        if (itemCleanupManager != null) {
            Bukkit.getPluginManager().registerEvents(itemCleanupManager, this);
        }
    }

    public static UnstableCore getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public AfkZoneManager getAfkZoneManager() {
        return afkZoneManager;
    }

    public ArenaManager getArenaManager() {
        return arenaManager;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public KillstreakManager getKillstreakManager() {
        return killstreakManager;
    }

    public PlaytimeManager getPlaytimeManager() {
        return playtimeManager;
    }

    public TagManager getTagManager() {
        return tagManager;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }

    public LoadoutManager getLoadoutManager() {
        return loadoutManager;
    }

    public ActionBarManager getActionBarManager() {
        return actionBarManager;
    }

    public MapVoteManager getMapVoteManager() {
        return mapVoteManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public RewardsManager getRewardsManager() {
        return rewardsManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public LeaderboardManager getLeaderboardManager() {
        return leaderboardManager;
    }

    public ItemCleanupManager getItemCleanupManager() {
        return itemCleanupManager;
    }

    public ArenaListener getArenaListener() {
        return arenaListener;
    }

    public CombatListener getCombatListener() {
        return combatListener;
    }

    public HeldShulkerListener getHeldShulkerListener() {
        return heldShulkerListener;
    }
}
