package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.RewardsManager;
import com.jovanstar.unstablecore.manager.RewardsManager.DayState;
import com.jovanstar.unstablecore.manager.RewardsManager.PlayerRewards;
import com.jovanstar.unstablecore.manager.RewardsManager.Tab;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import com.jovanstar.unstablecore.util.SmallCaps;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class RewardsGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final Tab tab;
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();

    public RewardsGui(UnstableCore plugin, Player viewer, Tab tab) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.tab = tab == null ? Tab.DAILY : tab;
        FileConfiguration cfg = plugin.getConfigManager().getRewards();
        String title = switch (this.tab) {
            case WEEKLY -> cfg.getString("weekly.title", "&dWEEKLY REWARDS");
            case MONTHLY -> cfg.getString("monthly.title", "&bMONTHLY REWARDS");
            default -> cfg.getString("daily.title", "&6DAILY REWARDS");
        };
        int size = Math.max(9, Math.min(54, cfg.getInt("gui.size", 54)));
        if (size % 9 != 0) {
            size = 54;
        }
        this.inventory = Bukkit.createInventory(this, size, MessageUtil.parse(title));
        fill();
    }

    public static void open(UnstableCore plugin, Player player, Tab tab) {
        RewardsManager mgr = plugin.getRewardsManager();
        if (mgr == null || mgr.peek(player.getUniqueId()) != null) {
            if (mgr != null) {
                mgr.touchLogin(player.getUniqueId());
            }
            player.openInventory(new RewardsGui(plugin, player, tab).getInventory());
            return;
        }
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            mgr.get(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                mgr.touchLogin(uuid);
                player.openInventory(new RewardsGui(plugin, player, tab).getInventory());
            });
        });
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().getRewards();
    }

    private static String tc(String text) {
        return SmallCaps.of(text);
    }

    private static String tcLine(String line) {
        return SmallCaps.colored(line == null ? "" : line);
    }

    private void fill() {
        actions.clear();
        FileConfiguration cfg = cfg();
        Material borderMat = material(cfg.getString("gui.border"), Material.PURPLE_STAINED_GLASS_PANE);
        Material fillerMat = material(cfg.getString("gui.filler"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack border = pane(borderMat);
        ItemStack filler = pane(fillerMat);
        for (int i = 0; i < inventory.getSize(); i++) {
            int row = i / 9;
            int col = i % 9;
            int rows = inventory.getSize() / 9;
            boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
            inventory.setItem(i, edge ? border : filler);
        }

        putTab("daily", Tab.DAILY);
        putTab("weekly", Tab.WEEKLY);
        putTab("monthly", Tab.MONTHLY);

        RewardsManager mgr = plugin.getRewardsManager();
        PlayerRewards data = mgr.get(viewer.getUniqueId());
        switch (tab) {
            case WEEKLY -> fillMilestones(mgr, data, true);
            case MONTHLY -> fillMilestones(mgr, data, false);
            default -> fillDaily(mgr, data);
        }
        putBoosterInfo(mgr);
    }

    private void putTab(String key, Tab target) {
        ConfigurationSection sec = cfg().getConfigurationSection("gui.tabs." + key);
        if (sec == null) {
            return;
        }
        int slot = sec.getInt("slot", -1);
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        boolean current = tab == target;
        String loreLine = current
                ? sec.getString("lore-current", "&7CURRENT TAB")
                : sec.getString("lore-switch", "&eCLICK TO SWITCH");
        inventory.setItem(slot, new ItemBuilder(material(sec.getString("material"), Material.PAPER))
                .name(sec.getString("name", key))
                .lore(tcLine(loreLine))
                .hideAttributes().build());
        actions.put(slot, "tab:" + target.name());
    }

    private void fillDaily(RewardsManager mgr, PlayerRewards data) {
        FileConfiguration cfg = cfg();
        int claimable = mgr.claimableDailyDay(data);
        int displayStreak;
        if (data.lastClaimDay.equals(mgr.today().toString())) {
            displayStreak = data.streak;
        } else if (claimable == 1 && data.streak == 0) {
            displayStreak = 0;
        } else {
            displayStreak = Math.max(0, data.streak);
        }

        ConfigurationSection streak = cfg.getConfigurationSection("daily.streak");
        if (streak != null) {
            int slot = streak.getInt("slot", 13);
            Map<String, String> ph = Map.of(
                    "streak", String.valueOf(displayStreak),
                    "streak-label", displayStreak == 1 ? "DAY" : "DAYS",
                    "best", String.valueOf(Math.max(displayStreak, data.streak)),
                    "today", mgr.today().toString()
            );
            inventory.setItem(slot, buildConfigured(streak, Material.SUNFLOWER, "&e☀ DAILY STREAK", ph, true));
        }

        ConfigurationSection footer = cfg.getConfigurationSection("daily.footer");
        if (footer != null) {
            int slot = footer.getInt("slot", 49);
            inventory.setItem(slot, buildConfigured(footer, Material.EMERALD, "&aDAILY CYCLE", Map.of(), true));
        }

        for (Map.Entry<Integer, ConfigurationSection> e : mgr.dailyDays().entrySet()) {
            int day = e.getKey();
            ConfigurationSection dayCfg = e.getValue();
            int slot = dayCfg.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            double coins = dayCfg.getDouble("coins", 0);
            int boosterH = dayCfg.getInt("booster-hours", 0);
            DayState state = mgr.dailyState(data, day);
            Material mat = material(dayCfg.getString("material"), Material.PAPER);

            Map<String, String> ph = Map.of(
                    "day", String.valueOf(day),
                    "coins", EconomyManager.format(coins),
                    "booster-hours", String.valueOf(boosterH)
            );

            String nameKey = switch (state) {
                case CLAIMABLE -> "name-claimable";
                case CLAIMED -> "name-claimed";
                case LOCKED -> "name-locked";
            };
            String name = apply(cfg.getString("daily." + nameKey, "&fDAY {day}"), ph);

            List<String> lore = new ArrayList<>();
            for (String line : cfg.getStringList("daily.lore")) {
                if (line.contains("{booster-hours}") && boosterH <= 0) {
                    continue;
                }
                lore.add(tcLine(apply(line, ph)));
            }
            lore.add("&r");
            String stateLoreKey = switch (state) {
                case CLAIMABLE -> "lore-claimable";
                case CLAIMED -> "lore-claimed";
                case LOCKED -> "lore-locked";
            };
            for (String line : cfg.getStringList("daily." + stateLoreKey)) {
                lore.add(tcLine(apply(line, ph)));
            }

            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
            if (state == DayState.CLAIMABLE) {
                actions.put(slot, "daily:" + day);
            }
        }
    }

    private void fillMilestones(RewardsManager mgr, PlayerRewards data, boolean weekly) {
        FileConfiguration cfg = cfg();
        String root = weekly ? "weekly" : "monthly";
        int progress = weekly ? data.weekDays : data.monthDays;
        int monthDays = mgr.today().lengthOfMonth();

        ConfigurationSection info = cfg.getConfigurationSection(root + ".info");
        if (info != null) {
            int slot = info.getInt("slot", 13);
            Map<String, String> ph = Map.of(
                    "days", String.valueOf(progress),
                    "month-days", String.valueOf(monthDays),
                    "reset", RewardsManager.formatDuration(mgr.millisUntilWeekReset())
            );
            inventory.setItem(slot, buildConfigured(info, Material.BOOK, "&fINFO", ph, true));
        }

        for (Map.Entry<Integer, ConfigurationSection> e : mgr.milestones(weekly).entrySet()) {
            int required = e.getKey();
            ConfigurationSection mile = e.getValue();
            int slot = mile.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            double coins = mile.getDouble("coins", 0);
            int boosterH = mile.getInt("booster-hours", 0);
            DayState state = mgr.milestoneState(data, weekly, required);
            Material mat = material(mile.getString("material"), Material.CHEST);

            Map<String, String> ph = Map.of(
                    "days", String.valueOf(required),
                    "coins", EconomyManager.format(coins),
                    "booster-hours", String.valueOf(boosterH),
                    "progress", String.valueOf(Math.min(progress, required))
            );

            String nameKey = switch (state) {
                case CLAIMABLE -> "name-claimable";
                case CLAIMED -> "name-claimed";
                case LOCKED -> "name-locked";
            };
            String name = apply(cfg.getString(root + "." + nameKey,
                    cfg.getString(root + ".name", "&f{days}-DAY MILESTONE")), ph);

            List<String> lore = new ArrayList<>();
            for (String line : cfg.getStringList(root + ".lore")) {
                if (line.contains("{booster-hours}") && boosterH <= 0) {
                    continue;
                }
                lore.add(tcLine(apply(line, ph)));
            }
            lore.add("&r");
            String stateLoreKey = switch (state) {
                case CLAIMABLE -> "lore-claimable";
                case CLAIMED -> "lore-claimed";
                case LOCKED -> "lore-locked";
            };
            for (String line : cfg.getStringList(root + "." + stateLoreKey)) {
                lore.add(tcLine(apply(line, ph)));
            }

            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
            if (state == DayState.CLAIMABLE) {
                actions.put(slot, (weekly ? "weekly:" : "monthly:") + required);
            }
        }
    }

    private void putBoosterInfo(RewardsManager mgr) {
        ConfigurationSection sec = cfg().getConfigurationSection("gui.booster-info");
        if (sec == null) {
            return;
        }
        int slot = sec.getInt("slot", 40);
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        boolean permBoost = mgr.hasBoosterPermission(viewer.getUniqueId());
        long remain = mgr.getBoosterRemainingMs(viewer.getUniqueId());
        if (permBoost) {
            Map<String, String> ph = Map.of(
                    "time", "Permission",
                    "multiplier", EconomyManager.format(mgr.boosterMultiplier())
            );
            Material mat = material(sec.getString("permission-material", sec.getString("active-material")),
                    Material.EXPERIENCE_BOTTLE);
            String name = sec.getString("permission-name", sec.getString("active-name", "&6ACTIVE COIN BOOSTER"));
            List<String> lore = new ArrayList<>();
            List<String> lines = sec.getStringList("permission-lore");
            if (lines.isEmpty()) {
                lines = sec.getStringList("active-lore");
            }
            for (String line : lines) {
                lore.add(tcLine(apply(line, ph)));
            }
            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
        } else if (remain > 0) {
            Map<String, String> ph = Map.of(
                    "time", RewardsManager.formatDuration(remain),
                    "multiplier", EconomyManager.format(mgr.boosterMultiplier())
            );
            Material mat = material(sec.getString("active-material"), Material.EXPERIENCE_BOTTLE);
            String name = sec.getString("active-name", "&6ACTIVE COIN BOOSTER");
            List<String> lore = new ArrayList<>();
            for (String line : sec.getStringList("active-lore")) {
                lore.add(tcLine(apply(line, ph)));
            }
            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
        } else {
            Material mat = material(sec.getString("inactive-material"), Material.GLASS_BOTTLE);
            String name = sec.getString("inactive-name", "&7NO ACTIVE BOOSTER");
            List<String> lore = new ArrayList<>();
            for (String line : sec.getStringList("inactive-lore")) {
                lore.add(tcLine(line));
            }
            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
        }
    }

    private ItemStack buildConfigured(ConfigurationSection sec, Material defMat, String defName,
                                      Map<String, String> ph, boolean tinyLore) {
        Material mat = material(sec.getString("material"), defMat);
        String name = apply(sec.getString("name", defName), ph);
        List<String> lore = new ArrayList<>();
        for (String line : sec.getStringList("lore")) {
            String applied = apply(line, ph);
            lore.add(tinyLore ? tcLine(applied) : applied);
        }
        return new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build();
    }

    private static String apply(String input, Map<String, String> ph) {
        return MessageUtil.apply(input == null ? "" : input, ph);
    }

    private ItemStack pane(Material mat) {
        return new ItemBuilder(mat).name(" ").hideAttributes().build();
    }

    public void handleClick(Player player, int slot) {
        String action = actions.get(slot);
        if (action == null) {
            return;
        }
        if (action.startsWith("tab:")) {
            open(plugin, player, Tab.valueOf(action.substring(4)));
            return;
        }

        RewardsManager mgr = plugin.getRewardsManager();
        boolean ok = false;
        if (action.startsWith("daily:")) {
            ok = mgr.claimDaily(player, Integer.parseInt(action.substring(6)));
        } else if (action.startsWith("weekly:")) {
            ok = mgr.claimMilestone(player, true, Integer.parseInt(action.substring(7)));
        } else if (action.startsWith("monthly:")) {
            ok = mgr.claimMilestone(player, false, Integer.parseInt(action.substring(8)));
        }

        try {
            player.playSound(player.getLocation(),
                    ok ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO,
                    1f, ok ? 1.2f : 1f);
        } catch (IllegalArgumentException ignored) {
        }
        open(plugin, player, tab);
    }

    private static Material material(String name, Material def) {
        if (name == null || name.isBlank()) {
            return def;
        }
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
