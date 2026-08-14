package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.TagCategory;
import com.jovanstar.unstablecore.model.TagEntry;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.SuffixNode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TagManager {

    private final UnstableCore plugin;
    private final Map<String, TagCategory> categories = new LinkedHashMap<>();
    private final Map<UUID, String> equipped = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clearCooldown = new ConcurrentHashMap<>();

    private int clearCooldownSeconds = 30;
    private int suffixPriority = 1;
    private String suffixSpacer = " ";

    private String mainTitle;
    private String emojiTitle;
    private String rankTitle;
    private String auraTitle;
    private Material filler;
    private int clearSlot;
    private Material clearMaterial;
    private String clearName;
    private List<String> clearLore = List.of();

    public TagManager(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        categories.clear();
        plugin.getConfigManager().reloadTags();
        FileConfiguration cfg = plugin.getConfigManager().getTags();

        clearCooldownSeconds = cfg.getInt("clear-cooldown-seconds", 30);
        suffixPriority = cfg.getInt("suffix-priority", 1);
        suffixSpacer = cfg.getString("suffix-spacer", " ");

        ConfigurationSection gui = cfg.getConfigurationSection("gui");
        if (gui != null) {
            mainTitle = gui.getString("main-title", "&eTags");
            emojiTitle = gui.getString("emoji-title", "&eEmoji Tags");
            rankTitle = gui.getString("rank-title", "&eRank Tags");
            auraTitle = gui.getString("aura-title", "&eAura Tags");
            filler = material(gui.getString("filler"), Material.GRAY_STAINED_GLASS_PANE);
            clearSlot = gui.getInt("clear-slot", 22);
            clearMaterial = material(gui.getString("clear-material"), Material.BARRIER);
            clearName = gui.getString("clear-name", "&cClear Tag");
            clearLore = gui.getStringList("clear-lore");
        }

        ConfigurationSection cats = cfg.getConfigurationSection("categories");
        if (cats != null) {
            for (String key : cats.getKeys(false)) {
                ConfigurationSection sec = cats.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                TagCategory category = new TagCategory(
                        key,
                        sec.getString("permission", ""),
                        sec.getInt("slot", 0),
                        material(sec.getString("material"), Material.PAPER),
                        sec.getString("name", key),
                        sec.getStringList("lore")
                );
                List<Map<?, ?>> tagList = sec.getMapList("tags");
                for (Map<?, ?> map : tagList) {
                    Object slotObj = map.get("slot");
                    if (slotObj == null) {
                        continue;
                    }
                    int slot = slotObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(slotObj));
                    Object nameObj = map.get("name");
                    Object suffixObj = map.get("suffix");
                    category.getTags().add(new TagEntry(
                            slot,
                            material(String.valueOf(map.get("material")), Material.NAME_TAG),
                            nameObj == null ? "" : String.valueOf(nameObj),
                            suffixObj == null ? "" : String.valueOf(suffixObj)
                    ));
                }
                categories.put(key.toLowerCase(), category);
            }
        }

        equipped.clear();
        DatabaseManager db = plugin.getDatabaseManager();
        if (db != null && db.isConnected()) {
            equipped.putAll(db.loadAllTags());
        }
    }

    public void save() {
        DatabaseManager db = plugin.getDatabaseManager();
        if (db == null || !db.isConnected()) {
            return;
        }
        db.saveAllTags(equipped);
    }

    public boolean equip(Player player, String suffixDisplay) {
        if (!hasLuckPerms()) {
            MessageUtil.send(player, "&cLuckPerms is required for tags.");
            return false;
        }
        String rawSuffix = suffixSpacer + suffixDisplay;

        String lpSuffix = toLuckPerms(rawSuffix);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LuckPerms api = LuckPermsProvider.get();
                User user = api.getUserManager().loadUser(player.getUniqueId()).join();
                if (user == null) {
                    return;
                }
                user.data().clear(NodeType.SUFFIX.predicate(n -> n.getPriority() == suffixPriority));
                user.data().add(SuffixNode.builder(lpSuffix, suffixPriority).build());
                api.getUserManager().saveUser(user);
                equipped.put(player.getUniqueId(), suffixDisplay);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                        plugin.getDatabaseManager().upsertTag(player.getUniqueId(), suffixDisplay));
                Bukkit.getScheduler().runTask(plugin, () ->
                        MessageUtil.sendConfig(player, "tag-equipped", Map.of()));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to set tag for " + player.getName() + ": " + e.getMessage());
            }
        });
        return true;
    }

    public boolean clear(Player player) {
        long now = System.currentTimeMillis();
        Long last = clearCooldown.get(player.getUniqueId());
        if (last != null && now - last < clearCooldownSeconds * 1000L) {
            MessageUtil.sendConfig(player, "tag-clear-cooldown", Map.of());
            return false;
        }
        clearCooldown.put(player.getUniqueId(), now);

        if (!hasLuckPerms()) {
            MessageUtil.send(player, "&cLuckPerms is required for tags.");
            return false;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LuckPerms api = LuckPermsProvider.get();
                User user = api.getUserManager().loadUser(player.getUniqueId()).join();
                if (user == null) {
                    return;
                }
                user.data().clear(NodeType.SUFFIX.predicate(n -> n.getPriority() == suffixPriority));
                api.getUserManager().saveUser(user);
                equipped.remove(player.getUniqueId());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                        plugin.getDatabaseManager().deleteTag(player.getUniqueId()));
                Bukkit.getScheduler().runTask(plugin, () ->
                        MessageUtil.sendConfig(player, "tag-cleared", Map.of()));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to clear tag for " + player.getName() + ": " + e.getMessage());
            }
        });
        return true;
    }

    public String getEquipped(UUID uuid) {
        return equipped.getOrDefault(uuid, "");
    }

    public void clearCooldown(UUID uuid) {
        if (uuid != null) {
            clearCooldown.remove(uuid);
        }
    }

    public Map<String, TagCategory> getCategories() {
        return categories;
    }

    public TagCategory getCategory(String id) {
        return categories.get(id.toLowerCase());
    }

    public String getMainTitle() {
        return mainTitle;
    }

    public String getCategoryTitle(String id) {
        return switch (id.toLowerCase()) {
            case "emoji" -> emojiTitle;
            case "ranks", "rank" -> rankTitle;
            case "aura" -> auraTitle;
            default -> mainTitle;
        };
    }

    public Material getFiller() {
        return filler;
    }

    public int getClearSlot() {
        return clearSlot;
    }

    public Material getClearMaterial() {
        return clearMaterial;
    }

    public String getClearName() {
        return clearName;
    }

    public List<String> getClearLore() {
        return clearLore;
    }

    private boolean hasLuckPerms() {
        return Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
    }

    private static Material material(String name, Material def) {
        if (name == null) {
            return def;
        }
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static String toLuckPerms(String input) {
        return input.replace('§', '&');
    }
}
