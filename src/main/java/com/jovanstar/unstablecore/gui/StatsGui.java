package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.StatsManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StatsGui implements InventoryHolder {

    private static final ConfigurationSection DEFAULTS = buildDefaults();

    private final Inventory inventory;

    public StatsGui(UnstableCore plugin, Player viewer, OfflinePlayer target) {
        ConfigurationSection gui = resolveGui(plugin);
        int size = Math.max(9, Math.min(54, gui.getInt("size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        Component title = MessageUtil.parse(gui.getString("title", "&fSTATS"));
        this.inventory = Bukkit.createInventory(this, size, title);
        fill(plugin, viewer, target, gui);
    }

    private static ConfigurationSection resolveGui(UnstableCore plugin) {
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("guis.stats");
        if (gui != null && gui.isConfigurationSection("items")
                && !gui.getConfigurationSection("items").getKeys(false).isEmpty()) {
            return gui;
        }

        return DEFAULTS;
    }

    private static ConfigurationSection buildDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("title", "&fSTATS");
        yaml.set("size", 27);
        yaml.set("filler", "");
        put(yaml, "profile", 4, "PLAYER_HEAD", "&f{player} &7STATS",
                List.of("&fViewing {player}'s lifetime stats.", "&8Everything below belongs to this profile."));
        put(yaml, "kills", 11, "DIAMOND_SWORD", "&b&lKILLS",
                List.of("&b{kills} &fkills", "&fLifetime eliminations."));
        put(yaml, "deaths", 12, "SKELETON_SKULL", "&c&lDEATHS",
                List.of("&c{deaths} &fdeaths", "&7Lifetime deaths."));
        put(yaml, "kdr", 13, "COMPASS", "&d&lKDR",
                List.of("&d{kdr} &fratio", "&fKills divided by deaths."));
        put(yaml, "streak", 14, "BLAZE_POWDER", "&6&lCURRENT STREAK",
                List.of("&6{streak} &factive streak", "&7Kills without dying right now."));
        put(yaml, "best", 15, "NETHER_STAR", "&e&lBEST STREAK",
                List.of("&e{best} &fbest streak", "&fHighest streak reached."));
        put(yaml, "coins", 20, "GOLD_BLOCK", "&e&lCOINS",
                List.of("&e{coins} &fcoins", "&fCurrent spendable balance."));
        put(yaml, "earned", 21, "GOLD_INGOT", "&e&lCOINS EARNED",
                List.of("&e{earned} &fcoins earned", "&fLifetime coin income."));
        put(yaml, "spent", 22, "HOPPER", "&f&lCOINS SPENT",
                List.of("&f{spent} coins spent", "&7Lifetime shop spending."));
        put(yaml, "playtime", 23, "CLOCK", "&a&lPLAYTIME",
                List.of("&a{playtime} &fplayed", "&fTotal time on the server."));
        put(yaml, "tag", 24, "WRITABLE_BOOK", "&b&lTAG",
                List.of("&f{tag}", "&7Currently equipped tag."));
        return yaml;
    }

    private static void put(YamlConfiguration yaml, String id, int slot, String material,
                            String name, List<String> lore) {
        String path = "items." + id;
        yaml.set(path + ".slot", slot);
        yaml.set(path + ".material", material);
        yaml.set(path + ".name", name);
        yaml.set(path + ".lore", lore);
    }

    private void fill(UnstableCore plugin, Player viewer, OfflinePlayer target, ConfigurationSection gui) {
        String fillerName = gui.getString("filler", "");
        if (fillerName != null && !fillerName.isBlank() && !fillerName.equalsIgnoreCase("AIR")) {
            Material fillerMat = material(fillerName, Material.GRAY_STAINED_GLASS_PANE);
            ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, filler);
            }
        }

        UUID uuid = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : "Unknown";
        StatsManager stats = plugin.getStatsManager();

        int kills = stats.getKills(uuid);
        int deaths = plugin.getKillstreakManager().getDeaths(uuid);
        int streak = plugin.getKillstreakManager().getStreak(uuid);
        int best = stats.getBestStreak(uuid);
        double coins = plugin.getEconomyManager().getBalance(target);
        double earned = stats.getCoinsEarned(uuid);
        double spent = stats.getCoinsSpent(uuid);
        String kdr = StatsManager.formatKdr(stats.getKdr(uuid));
        String playtime = target.isOnline() && target.getPlayer() != null
                ? StatsManager.formatPlaytime(target.getPlayer())
                : "-";
        String tag = plugin.getTagManager().getEquipped(uuid);
        if (tag == null || tag.isBlank()) {
            tag = "None";
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("player", name);
        ph.put("viewer", viewer.getName());
        ph.put("kills", String.valueOf(kills));
        ph.put("deaths", String.valueOf(deaths));
        ph.put("kdr", kdr);
        ph.put("streak", String.valueOf(streak));
        ph.put("best", String.valueOf(best));
        ph.put("coins", EconomyManager.formatCommas(coins));
        ph.put("earned", EconomyManager.formatCommas(earned));
        ph.put("spent", EconomyManager.formatCommas(spent));
        ph.put("playtime", playtime);
        ph.put("tag", MessageUtil.strip(tag));

        ConfigurationSection items = gui.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null) {
                continue;
            }
            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            Material mat = material(item.getString("material"), Material.STONE);
            ItemBuilder builder = new ItemBuilder(mat);
            if (mat == Material.PLAYER_HEAD) {
                ItemStack head = builder.build();
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (meta != null) {
                    meta.setOwningPlayer(target);
                    head.setItemMeta(meta);
                    builder = new ItemBuilder(head);
                }
            }
            String display = MessageUtil.apply(item.getString("name", key), ph);
            List<String> lore = new ArrayList<>();
            for (String line : item.getStringList("lore")) {
                lore.add(MessageUtil.apply(line, ph));
            }
            inventory.setItem(slot, builder.name(display).lore(lore).hideAttributes().build());
        }
    }

    public static void open(UnstableCore plugin, Player viewer, OfflinePlayer target) {
        viewer.openInventory(new StatsGui(plugin, viewer, target).getInventory());
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
