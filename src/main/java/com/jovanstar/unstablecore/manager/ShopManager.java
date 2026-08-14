package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopManager {

    private final UnstableCore plugin;
    private final Set<UUID> purchasing = ConcurrentHashMap.newKeySet();

    public ShopManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public FileConfiguration config() {
        return plugin.getConfigManager().getShop();
    }

    public String defaultCategory() {
        return config().getString("default-category", "items");
    }

    public ConfigurationSection category(String id) {
        return config().getConfigurationSection("categories." + id);
    }

    public ItemStack buildDisplayItem(Player player, String categoryId, String itemId) {
        ConfigurationSection item = config().getConfigurationSection("categories." + categoryId + ".items." + itemId);
        if (item == null) {
            return null;
        }
        Material mat = material(item.getString("material"), Material.STONE);
        ItemBuilder builder = new ItemBuilder(mat).amount(item.getInt("amount", 1));
        String name = item.getString("name", mat.name());
        builder.name(applyPlayer(name, player, item.getDouble("price", 0), name));

        if (item.isConfigurationSection("enchantments")) {
            Map<String, Integer> enchants = new HashMap<>();
            ConfigurationSection enchSec = item.getConfigurationSection("enchantments");
            if (enchSec != null) {
                for (String key : enchSec.getKeys(false)) {
                    enchants.put(key, enchSec.getInt(key));
                }
                builder.enchantments(enchants);
            }
        }
        if (item.contains("potion-type")) {
            builder.potion(
                    item.getString("potion-type", "WATER"),
                    item.getInt("potion-amplifier", 0),
                    item.getInt("potion-duration-seconds", 180)
            );
        }

        List<String> lore = new ArrayList<>();
        for (String line : item.getStringList("lore")) {
            lore.add(applyPlayer(line, player, item.getDouble("price", 0), name));
        }
        LoadoutManager loadouts = plugin.getLoadoutManager();
        if (item.getBoolean("reset-loadout-cooldown", false) && loadouts != null) {
            long remain = loadouts.remainingMillis(player.getUniqueId());
            if (remain > 0L) {
                lore.add("&7Loadout CD: &e" + EventManager.formatDurationMillis(remain));
            } else {
                lore.add("&7Loadout CD: &aReady");
            }
        }
        long noCdSecs = item.getLong("loadout-nocooldown-seconds", 0L);
        if (noCdSecs > 0L && loadouts != null) {
            long active = loadouts.noCooldownRemainingMillis(player.getUniqueId());
            if (active > 0L) {
                lore.add("&7Active no-CD: &a" + EventManager.formatDurationMillis(active));
            }
            lore.add("&7Grants &f" + EventManager.formatDurationMillis(noCdSecs * 1000L) + " &7loadout no-CD");
        }
        double price = item.getDouble("price", 0);
        String priceLine = config().getString("price-lore", "&7Price: &e{price} coins");
        lore.add(MessageUtil.apply(priceLine, Map.of("price", EconomyManager.formatCommas(price))));
        lore.add("&r");
        lore.add(config().getString("click-lore", "&aClick to buy"));

        builder.lore(lore);
        return builder.build();
    }

    public ItemStack buildRewardItem(String categoryId, String itemId) {
        ConfigurationSection item = config().getConfigurationSection("categories." + categoryId + ".items." + itemId);
        if (item == null) {
            return null;
        }
        Material mat = material(item.getString("material"), Material.STONE);
        ItemBuilder builder = new ItemBuilder(mat).amount(item.getInt("amount", 1));
        String name = item.getString("name");
        if (name != null && !name.isBlank()) {
            builder.name(name);
        }
        if (item.isConfigurationSection("enchantments")) {
            Map<String, Integer> enchants = new HashMap<>();
            ConfigurationSection enchSec = item.getConfigurationSection("enchantments");
            if (enchSec != null) {
                for (String key : enchSec.getKeys(false)) {
                    enchants.put(key, enchSec.getInt(key));
                }
                builder.enchantments(enchants);
            }
        }
        if (item.contains("potion-type")) {
            builder.potion(
                    item.getString("potion-type", "WATER"),
                    item.getInt("potion-amplifier", 0),
                    item.getInt("potion-duration-seconds", 180)
            );
        }
        List<String> lore = item.getStringList("item-lore");
        if (!lore.isEmpty()) {
            builder.lore(lore);
        }
        return builder.build();
    }

    public boolean purchase(Player player, String categoryId, String itemId) {
        if (player == null || !purchasing.add(player.getUniqueId())) {
            return false;
        }
        try {
            return purchaseLocked(player, categoryId, itemId);
        } finally {
            purchasing.remove(player.getUniqueId());
        }
    }

    private boolean purchaseLocked(Player player, String categoryId, String itemId) {
        ConfigurationSection item = config().getConfigurationSection("categories." + categoryId + ".items." + itemId);
        if (item == null) {
            return false;
        }
        if (!plugin.getEconomyManager().isReady()) {
            MessageUtil.send(player, config().getString("messages.no-economy", "&cEconomy is unavailable."));
            return false;
        }

        double price = item.getDouble("price", 0);
        String displayName = item.getString("name", itemId);
        boolean free = item.getBoolean("free", false);
        if (price < 0 || (price <= 0 && !free)) {
            MessageUtil.send(player, "&cThis shop item is misconfigured.");
            plugin.getLogger().warning("Shop item " + categoryId + "/" + itemId + " has invalid price.");
            return false;
        }

        boolean giveItem = item.getBoolean("give-item", true);
        ItemStack reward = null;
        if (giveItem) {
            reward = buildRewardItem(categoryId, itemId);
            if (reward != null && !canFit(player, reward)) {
                MessageUtil.send(player, config().getString("messages.inventory-full", "&cYour inventory is full."));
                return false;
            }
        }

        boolean resetCd = item.getBoolean("reset-loadout-cooldown", false);
        long noCdSeconds = item.getLong("loadout-nocooldown-seconds", 0L);
        LoadoutManager loadouts = plugin.getLoadoutManager();
        if ((resetCd || noCdSeconds > 0L) && loadouts == null) {
            MessageUtil.send(player, "&cLoadout system is unavailable.");
            return false;
        }

        if (price > 0 && !plugin.getEconomyManager().takeExact(player, price)) {
            MessageUtil.send(player, MessageUtil.apply(
                    config().getString("messages.cannot-afford", "&cYou need &e{price} &ccoins for that."),
                    Map.of("price", EconomyManager.formatCommas(price))
            ));
            return false;
        }

        if (reward != null) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(reward);
            for (ItemStack left : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }

        if (resetCd && loadouts != null) {
            loadouts.resetCooldown(player.getUniqueId());
            MessageUtil.send(player, config().getString(
                    "messages.loadout-cooldown-skipped",
                    "&aLoadout cooldown skipped. Normal cooldown applies on your next claim."
            ));
        }
        if (noCdSeconds > 0L && loadouts != null) {
            loadouts.grantNoCooldown(player.getUniqueId(), noCdSeconds);
            MessageUtil.send(player, MessageUtil.apply(
                    config().getString(
                            "messages.loadout-nocooldown-granted",
                            "&aLoadout has no cooldown for &f{time}&a. After that, normal cooldown returns."
                    ),
                    Map.of("time", EventManager.formatDurationMillis(noCdSeconds * 1000L))
            ));
        }

        for (String command : item.getStringList("commands")) {
            if (command == null || command.isBlank()) {
                continue;
            }
            String parsed = applyPlayer(command, player, price, displayName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }

        MessageUtil.send(player, MessageUtil.apply(
                config().getString("messages.bought", "&aPurchased &f{name} &afor &e{price} &acoins."),
                Map.of(
                        "name", MessageUtil.strip(displayName),
                        "price", EconomyManager.formatCommas(price)
                )
        ));
        return true;
    }

    public String applyPlayer(String input, Player player, double price, String name) {
        return MessageUtil.apply(input, Map.of(
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "price", EconomyManager.formatCommas(price),
                "coins", EconomyManager.formatCommas(plugin.getEconomyManager().getBalance(player)),
                "name", MessageUtil.strip(name == null ? "" : name)
        ));
    }

    private static boolean canFit(Player player, ItemStack reward) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        org.bukkit.inventory.Inventory probe = Bukkit.createInventory(null, 36);
        for (int i = 0; i < Math.min(storage.length, 36); i++) {
            if (storage[i] != null && !storage[i].getType().isAir()) {
                probe.setItem(i, storage[i].clone());
            }
        }
        return probe.addItem(reward.clone()).isEmpty();
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
}
