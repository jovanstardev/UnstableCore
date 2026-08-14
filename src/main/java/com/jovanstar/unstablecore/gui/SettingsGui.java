package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.SettingsManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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

public final class SettingsGui implements InventoryHolder {

    private static final ConfigurationSection DEFAULTS = buildDefaults();

    private final UnstableCore plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, String> slotActions = new HashMap<>();

    public SettingsGui(UnstableCore plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        ConfigurationSection gui = resolveGui(plugin);
        int size = Math.max(9, Math.min(54, gui.getInt("size", 45)));
        if (size % 9 != 0) {
            size = 45;
        }
        Component title = MessageUtil.parse(gui.getString("title", "&bSettings | Dashboard"));
        this.inventory = Bukkit.createInventory(this, size, title);
        fill(gui);
    }

    private static ConfigurationSection resolveGui(UnstableCore plugin) {
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("guis.settings");
        if (gui != null && gui.isConfigurationSection("items")
                && !gui.getConfigurationSection("items").getKeys(false).isEmpty()) {
            return gui;
        }
        return DEFAULTS;
    }

    private void fill(ConfigurationSection gui) {
        slotActions.clear();
        Material fillerMat = material(gui.getString("filler"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        ConfigurationSection items = gui.getConfigurationSection("items");
        if (items == null) {
            return;
        }

        SettingsManager settings = plugin.getSettingsManager();
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }

            String type = item.getString("type", "toggle").toLowerCase(Locale.ROOT);
            Material mat = material(item.getString("material"), Material.PAPER);
            String name = item.getString("name", id);
            List<String> lore = new ArrayList<>();

            if ("info".equals(type)) {
                for (String line : item.getStringList("lore")) {
                    lore.add(line);
                }
                inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
                continue;
            }

            if ("close".equals(type)) {
                for (String line : item.getStringList("lore")) {
                    lore.add(line);
                }
                inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
                slotActions.put(slot, "close");
                continue;
            }

            lore.add("&7Details");
            String detail = item.getString("details", "&f| Setting.");
            lore.add(detail);
            String extra = item.getString("details-extra", "");
            if (extra != null && !extra.isBlank()) {
                lore.add(extra);
            }
            lore.add("&r");

            String settingKey = item.getString("setting", id);
            boolean enabled = settings.isEnabled(viewer, settingKey);
            if (enabled) {
                lore.add("&7Currently &aEnabled");
                lore.add("&d> &fClick to disable this");
            } else {
                lore.add("&7Currently &cDisabled");
                lore.add("&d> &fClick to enable this");
            }
            inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());

            if ("command".equals(type)) {
                String cmd = item.getString("command", "");
                slotActions.put(slot, "command:" + settingKey + "|" + cmd);
            } else {
                slotActions.put(slot, "toggle:" + settingKey);
            }
        }
    }

    public void handleClick(Player player, int slot) {
        String action = slotActions.get(slot);
        if (action == null) {
            return;
        }
        if (action.equals("close")) {
            player.closeInventory();
            return;
        }
        if (action.startsWith("command:")) {
            String payload = action.substring("command:".length());
            int sep = payload.indexOf('|');
            String key = sep >= 0 ? payload.substring(0, sep) : payload;
            String cmd = sep >= 0 ? payload.substring(sep + 1).trim() : "";
            boolean enabled = plugin.getSettingsManager().toggle(player.getUniqueId(), key);
            try {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, enabled ? 1.6f : 0.7f);
            } catch (IllegalArgumentException ignored) {
            }
            if (!cmd.isBlank()) {
                cmd = MessageUtil.apply(cmd, Map.of("player", player.getName()));
                player.performCommand(cmd);
            }
            fill(resolveGui(plugin));
            player.updateInventory();
            return;
        }
        if (action.startsWith("toggle:")) {
            String key = action.substring("toggle:".length());
            boolean enabled = plugin.getSettingsManager().toggle(player.getUniqueId(), key);
            try {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, enabled ? 1.6f : 0.7f);
            } catch (IllegalArgumentException ignored) {
            }
            fill(resolveGui(plugin));
            player.updateInventory();
        }
    }

    public static void open(UnstableCore plugin, Player player) {
        player.openInventory(new SettingsGui(plugin, player).getInventory());
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

    private static ConfigurationSection buildDefaults() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("title", "&bSettings | Dashboard");
        y.set("size", 45);
        y.set("filler", "GRAY_STAINED_GLASS_PANE");

        putInfo(y, "header", 4, "NOTE_BLOCK", "&fSettings &dDashboard", List.of(
                "&7Click a setting to switch it.",
                "&aHigher note = on",
                "&cLower note = off"
        ));
        putCommand(y, "private_messages", 10, "PAPER", "&aPrivate Messages", "private_messages",
                "msg", "&a| Allow /msg and /reply.", "&7Controls private chat completely.");
        putToggle(y, "broadcasts", 11, "BELL", "&eBroadcasts",
                "broadcasts", "&e| Show timed server broadcasts.", "&7Server tips, links, store, and info messages.");
        putToggle(y, "broadcast_ping", 12, "NOTE_BLOCK", "&bBroadcast Ping",
                "broadcast_ping", "&b| Play the broadcast ping sound.", "&8Only affects the sound, not the message.");
        putToggle(y, "join_leave", 13, "OAK_DOOR", "&fJoin & Leave",
                SettingsManager.JOIN_LEAVE, "&f| Show join and leave announcements.", "&7Keeps public connection messages quieter.");
        putToggle(y, "kill_messages", 14, "IRON_SWORD", "&cKill Messages",
                SettingsManager.KILL_MESSAGES, "&c| Show kill and death messages.", "&7Useful if combat chat gets too busy.");
        putToggle(y, "streak_alerts", 15, "BLAZE_POWDER", "&6Streak Alerts",
                SettingsManager.STREAK_ALERTS, "&6| Show kill streak callouts.", "&7Controls streak messages in chat.");
        putToggle(y, "coin_notices", 19, "GOLD_INGOT", "&eCoin Notices",
                "coin_notices", "&e| Show coin reward messages.", "&7Kill rewards and economy tips.");
        putCommand(y, "scoreboard", 20, "CLOCK", "&bScoreboard", SettingsManager.SCOREBOARD,
                "sb toggle", "&b| Show your right-side board.", "&7Turns the sidebar on or off.");
        putToggle(y, "rotation_alerts", 21, "FILLED_MAP", "&dRotation Alerts",
                SettingsManager.ROTATION_ALERTS, "&d| Show warnings before arena rotation.", "&7Helps you catch the next map swap.");
        putToggle(y, "private_profile", 22, "PLAYER_HEAD", "&dPrivate Profile",
                SettingsManager.PRIVATE_PROFILE, "&d| Hide profile details from other players.", "&7Other players see less profile data.");
        putToggle(y, "cleanup_alerts", 23, "BRUSH", "&aCleanup Alerts",
                SettingsManager.CLEANUP_ALERTS, "&a| Show item cleanup countdowns.", "&7Useful when fighting around dropped gear.");
        putInfo(y, "close", 40, "BARRIER", "&cClose", List.of("&7Click to close"));
        y.set("items.close.type", "close");
        return y;
    }

    private static void putInfo(YamlConfiguration y, String id, int slot, String mat, String name, List<String> lore) {
        String p = "items." + id;
        y.set(p + ".slot", slot);
        y.set(p + ".material", mat);
        y.set(p + ".type", "info");
        y.set(p + ".name", name);
        y.set(p + ".lore", lore);
    }

    private static void putToggle(YamlConfiguration y, String id, int slot, String mat, String name,
                                  String setting, String details, String extra) {
        String p = "items." + id;
        y.set(p + ".slot", slot);
        y.set(p + ".material", mat);
        y.set(p + ".type", "toggle");
        y.set(p + ".setting", setting);
        y.set(p + ".name", name);
        y.set(p + ".details", details);
        y.set(p + ".details-extra", extra);
    }

    private static void putCommand(YamlConfiguration y, String id, int slot, String mat, String name,
                                   String setting, String command, String details, String extra) {
        String p = "items." + id;
        y.set(p + ".slot", slot);
        y.set(p + ".material", mat);
        y.set(p + ".type", "command");
        y.set(p + ".setting", setting);
        y.set(p + ".command", command);
        y.set(p + ".name", name);
        y.set(p + ".details", details);
        y.set(p + ".details-extra", extra);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
