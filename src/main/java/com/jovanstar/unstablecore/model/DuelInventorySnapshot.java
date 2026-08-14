package com.jovanstar.unstablecore.model;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full pre-duel player state needed for a lossless restore: inventory, armor, offhand, cursor
 * item, location (so the player isn't left in the duel arena forever) and game mode. Captured
 * once at accept-time before the kit is applied, restored exactly once on every exit path.
 */
public final class DuelInventorySnapshot {

    private final ItemStack[] storage;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final ItemStack cursor;
    private final Location location;
    private final GameMode gameMode;

    private DuelInventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offHand,
                                   ItemStack cursor, Location location, GameMode gameMode) {
        this.storage = storage;
        this.armor = armor;
        this.offHand = offHand;
        this.cursor = cursor;
        this.location = location;
        this.gameMode = gameMode;
    }

    public static DuelInventorySnapshot capture(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = cloneArray(inv.getStorageContents());
        ItemStack[] armor = cloneArray(inv.getArmorContents());
        ItemStack offHand = cloneItem(inv.getItemInOffHand());
        ItemStack cursor = cloneItem(player.getItemOnCursor());
        return new DuelInventorySnapshot(storage, armor, offHand, cursor,
                player.getLocation().clone(), player.getGameMode());
    }

    public void restore(Player player) {
        if (player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        inv.setStorageContents(cloneArray(storage));
        inv.setArmorContents(cloneArray(armor));
        inv.setItemInOffHand(cloneItem(offHand));
        player.setItemOnCursor(cloneItem(cursor));
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
        player.updateInventory();
    }

    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    /**
     * Serializes this snapshot to a YAML string for crash-recovery persistence, using the same
     * per-slot ItemStack write idiom {@code KitManager} already uses for kit contents. Only ever
     * needs to survive a restart long enough for {@link #restore} to run on the player's next
     * join - see DuelManager's pendingCrashRestores.
     */
    public String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        writeItems(cfg.createSection("storage"), storage);
        writeItems(cfg.createSection("armor"), armor);
        if (offHand != null) {
            cfg.set("offhand", offHand);
        }
        if (cursor != null) {
            cfg.set("cursor", cursor);
        }
        if (location != null && location.getWorld() != null) {
            cfg.set("loc.world", location.getWorld().getName());
            cfg.set("loc.x", location.getX());
            cfg.set("loc.y", location.getY());
            cfg.set("loc.z", location.getZ());
            cfg.set("loc.yaw", location.getYaw());
            cfg.set("loc.pitch", location.getPitch());
        }
        if (gameMode != null) {
            cfg.set("gamemode", gameMode.name());
        }
        return cfg.saveToString();
    }

    public static DuelInventorySnapshot deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(raw);
        } catch (InvalidConfigurationException e) {
            Logger.getLogger(DuelInventorySnapshot.class.getName())
                    .log(Level.WARNING, "Failed to deserialize a stored duel inventory snapshot", e);
            return null;
        }
        ItemStack[] storage = readItems(cfg.getConfigurationSection("storage"), 36);
        ItemStack[] armor = readItems(cfg.getConfigurationSection("armor"), 4);
        ItemStack offHand = cfg.getItemStack("offhand");
        ItemStack cursor = cfg.getItemStack("cursor");

        Location location = null;
        if (cfg.isSet("loc.world")) {
            World world = Bukkit.getWorld(cfg.getString("loc.world", ""));
            if (world != null) {
                location = new Location(world, cfg.getDouble("loc.x"), cfg.getDouble("loc.y"),
                        cfg.getDouble("loc.z"), (float) cfg.getDouble("loc.yaw"), (float) cfg.getDouble("loc.pitch"));
            }
        }
        GameMode gameMode = null;
        String gmName = cfg.getString("gamemode");
        if (gmName != null) {
            try {
                gameMode = GameMode.valueOf(gmName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new DuelInventorySnapshot(storage, armor, offHand, cursor, location, gameMode);
    }

    private static void writeItems(ConfigurationSection section, ItemStack[] items) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && !items[i].getType().isAir()) {
                section.set(String.valueOf(i), items[i]);
            }
        }
    }

    private static ItemStack[] readItems(ConfigurationSection section, int size) {
        ItemStack[] out = new ItemStack[size];
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            int slot;
            try {
                slot = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            if (slot < 0 || slot >= size) {
                continue;
            }
            ItemStack stack = section.getItemStack(key);
            if (stack != null) {
                out[slot] = stack;
            }
        }
        return out;
    }

    private static ItemStack[] cloneArray(ItemStack[] source) {
        if (source == null) {
            return new ItemStack[0];
        }
        ItemStack[] out = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            out[i] = cloneItem(source[i]);
        }
        return out;
    }

    private static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
