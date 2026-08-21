package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class KitManager {

    /** First slot reserved for the admin kit editor's buttons; contents at or above are not issued. */
    private static final int RESERVED_BUTTON_SLOT = 52;

    private final UnstableCore plugin;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    /**
     * Largest per-kit cooldown across all loaded kits, recomputed whenever kits are (re)loaded.
     *
     * <p>Volatile and precomputed rather than derived by iterating {@link #kits} on demand: the
     * async autosave asks for this while the main thread may be reloading kits, and that map is a
     * plain LinkedHashMap - iterating it off-thread risks ConcurrentModificationException.
     */
    private volatile int maxKitCooldownSeconds;
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, ItemStack[]>> layouts = new ConcurrentHashMap<>();
    private final Set<UUID> purchasing = ConcurrentHashMap.newKeySet();
    private final Object saveLock = new Object();
    private BukkitTask saveTask;
    private boolean saveDirty;

    private File kitsFolder;
    private File dataFile;
    private FileConfiguration dataConfig;

    public KitManager(UnstableCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        if (dataFile != null) {
            // Flush any pending batched player-data save (unlocks, selections, layouts) before
            // the in-memory maps below get wiped and repopulated from disk - otherwise a reload
            // that races a pending save silently discards the most recent player changes.
            savePlayerData();
        }
        kitsFolder = new File(plugin.getDataFolder(), "kits");
        dataFile = new File(plugin.getDataFolder(), "kit-data.yml");
        if (!kitsFolder.exists() && !kitsFolder.mkdirs()) {
            plugin.getLogger().severe("Could not create kits folder");
        }
        migrateLegacyKitsYml();
        ensureDefaultKitFiles();
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create kit-data.yml", e);
            }
        }
        // Re-read and re-parse the player data file under saveLock. savePlayerData() above has
        // already flushed and cancelled any pending batched save, but a save that was *already
        // running* only blocks until this method releases the lock - so without holding it here,
        // it could wake up and run dataConfig.set("players", null) against the freshly-loaded
        // config while loadPlayerData() was still reading it, wiping unlocks and layouts.
        synchronized (saveLock) {
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            reloadKitsFromConfig();
            loadPlayerData();
        }
    }


    private void migrateLegacyKitsYml() {
        File legacy = new File(plugin.getDataFolder(), "kits.yml");
        if (!legacy.exists()) {
            return;
        }
        File[] existing = kitsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (existing != null && existing.length > 0) {
            return;
        }
        FileConfiguration legacyConfig = YamlConfiguration.loadConfiguration(legacy);
        ConfigurationSection root = legacyConfig.getConfigurationSection("kits");
        if (root == null) {
            return;
        }
        plugin.getLogger().info("Migrating kits.yml to kits/ folder...");
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            File out = kitFile(id);
            YamlConfiguration kitConfig = new YamlConfiguration();
            for (String key : sec.getKeys(false)) {
                kitConfig.set(key, sec.get(key));
            }
            try {
                kitConfig.save(out);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not migrate kit " + id, e);
            }
        }
        File bak = new File(plugin.getDataFolder(), "kits.yml.bak");
        if (!bak.exists() && legacy.renameTo(bak)) {
            plugin.getLogger().info("Renamed kits.yml to kits.yml.bak");
        }
    }

    private void ensureDefaultKitFiles() {
        File[] existing = kitsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (existing != null && existing.length > 0) {
            return;
        }
        String[] defaults = {
                "clownpierce", "dangermarlowww", "deputy_ace", "ferremc", "flamefrags", "jaden_man",
                "law", "lettucek", "manepear", "mapicc", "minutetech", "mistrul", "nufuli", "parrot",
                "princezam", "sargelaw", "shoebilly", "spoke", "theobaldthebird", "wemmbu",
                "wifies", "wyll"
        };
        for (String id : defaults) {
            String resource = "kits/" + id + ".yml";
            if (plugin.getResource(resource) == null) {
                continue;
            }
            plugin.saveResource(resource, false);
        }
    }

    private File kitFile(String id) {
        return new File(kitsFolder, id.toLowerCase(Locale.ROOT) + ".yml");
    }

    public void reloadKitsFromConfig() {
        kits.clear();
        maxKitCooldownSeconds = 0;
        File[] files = kitsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }
        int missingCooldown = 0;
        for (File file : files) {
            String fileName = file.getName();
            String id = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            FileConfiguration kitConfig = YamlConfiguration.loadConfiguration(file);
            String display = kitConfig.getString("display-name", id.toUpperCase(Locale.ROOT));
            Material icon = resolveMaterial(kitConfig.getString("icon", "STONE"), Material.STONE);
            int slot = kitConfig.getInt("slot", 0);
            String perm = kitConfig.getString("permission", "unstablecore.kit." + id);
            String tier = kitConfig.getString("tier", "Epic");
            double price = kitConfig.getDouble("price", 0);
            String nameColor = kitConfig.getString("name-color", "&d");
            int kitCooldown = kitConfig.getInt("cooldown", 0);
            if (!kitConfig.isSet("cooldown")) {
                missingCooldown++;
            }
            ItemStack[] contents = readContents(kitConfig.getConfigurationSection("contents"));
            warnAboutReservedSlots(id, contents);
            kits.put(id, new Kit(id, display, icon, slot, perm, tier, price, nameColor, contents,
                    kitCooldown));
        }
        // A kit file written before per-kit cooldowns existed has no "cooldown" key, and Bukkit
        // never overwrites a config that is already on disk. Such a kit silently falls back to the
        // shared loadout.cooldown-seconds, so an upgraded server shows one identical cooldown on
        // every kit with nothing in the log to explain it. Say so instead of failing quietly.
        if (missingCooldown > 0) {
            plugin.getLogger().warning(missingCooldown + " of " + kits.size()
                    + " kit files have no 'cooldown' key and fall back to loadout.cooldown-seconds ("
                    + Math.max(0L, plugin.getConfig().getLong("loadout.cooldown-seconds", 1800L))
                    + "s). Add 'cooldown: <seconds>' to plugins/UnstableCore/kits/<kit>.yml to give"
                    + " that kit its own timer.");
        }

        int max = 0;
        for (Kit kit : kits.values()) {
            max = Math.max(max, kit.getCooldownSeconds());
        }
        maxKitCooldownSeconds = max;
    }

    /** Largest per-kit cooldown in seconds, or 0 when no kit overrides the shared cooldown. */
    public int getMaxKitCooldownSeconds() {
        return maxKitCooldownSeconds;
    }

    /**
     * Slots 52 and 53 are where {@link com.jovanstar.unstablecore.gui.KitAdminEditGui} puts its
     * Cancel and Save buttons, so {@link #applyKit} deliberately refuses to hand them out - the
     * alternative is giving players a dye labelled "Save Kit" whenever an older or hand-written
     * kit file happens to have something there.
     *
     * <p>The editor only writes slots 0-51, so the UI cannot produce this. A hand-edited YAML
     * can, and dropping the item silently leaves the author no way to tell why part of their kit
     * never appears - so name the kit and slot at load time.
     */
    private void warnAboutReservedSlots(String kitId, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (int slot = 52; slot < Math.min(contents.length, Kit.CONTENTS_SIZE); slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && !stack.getType().isAir()) {
                plugin.getLogger().warning("Kit '" + kitId + "' has " + stack.getType()
                        + " in slot " + slot + ", which is reserved for the kit editor's buttons"
                        + " and will not be given to players. Move it to a slot between 0 and 51.");
            }
        }
    }

    private static Material resolveMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material mat = Material.matchMaterial(raw);
        if (mat != null) {
            return mat;
        }
        String alt = switch (raw.toUpperCase(Locale.ROOT)) {
            case "NETHERITE_SPEAR", "DIAMOND_SPEAR", "IRON_SPEAR", "STONE_SPEAR", "WOODEN_SPEAR",
                 "COPPER_SPEAR" -> "TRIDENT";
            case "NETHERITE_NAUTILUS_ARMOR" -> "NETHERITE_CHESTPLATE";
            default -> null;
        };
        if (alt != null) {
            mat = Material.matchMaterial(alt);
            if (mat != null) {
                return mat;
            }
        }
        return fallback;
    }

    private void loadPlayerData() {
        selected.clear();
        unlocked.clear();
        layouts.clear();
        ConfigurationSection players = dataConfig.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidStr : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection p = players.getConfigurationSection(uuidStr);
            if (p == null) {
                continue;
            }
            String sel = p.getString("selected");
            if (sel != null && !sel.isBlank()) {
                selected.put(uuid, sel.toLowerCase(Locale.ROOT));
            }
            List<String> unlockedList = p.getStringList("unlocked");
            if (!unlockedList.isEmpty()) {
                Set<String> set = ConcurrentHashMap.newKeySet();
                for (String id : unlockedList) {
                    set.add(id.toLowerCase(Locale.ROOT));
                }
                unlocked.put(uuid, set);
            }
            ConfigurationSection layoutsSec = p.getConfigurationSection("layouts");
            if (layoutsSec != null) {
                Map<String, ItemStack[]> map = new ConcurrentHashMap<>();
                for (String kitId : layoutsSec.getKeys(false)) {
                    ItemStack[] layout = readContents(layoutsSec.getConfigurationSection(kitId));
                    map.put(kitId.toLowerCase(Locale.ROOT), layout);
                }
                layouts.put(uuid, map);
            }
        }
    }

    public void saveKits() {
        Set<String> keep = new HashSet<>();
        for (Kit kit : kits.values()) {
            saveKitFile(kit);
            keep.add(kit.getId().toLowerCase(Locale.ROOT));
        }
        File[] files = kitsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (!keep.contains(id) && !file.delete()) {
                plugin.getLogger().warning("Could not delete orphaned kit file: " + file.getName());
            }
        }
    }

    public void saveKitFile(Kit kit) {
        if (kit == null) {
            return;
        }
        if (!kitsFolder.exists() && !kitsFolder.mkdirs()) {
            plugin.getLogger().severe("Could not create kits folder");
            return;
        }
        YamlConfiguration kitConfig = new YamlConfiguration();
        kitConfig.set("display-name", kit.getDisplayName());
        kitConfig.set("icon", kit.getIcon().name());
        kitConfig.set("slot", kit.getSlot());
        kitConfig.set("permission", kit.getPermission());
        kitConfig.set("tier", kit.getTier());
        kitConfig.set("price", kit.getPrice());
        if (kit.getCooldownSeconds() > 0) {
            kitConfig.set("cooldown", kit.getCooldownSeconds());
        }
        kitConfig.set("name-color", kit.getNameColor());
        writeContents(kitConfig.createSection("contents"), kit.getContents());
        try {
            kitConfig.save(kitFile(kit.getId()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save kit " + kit.getId(), e);
        }
    }

    public void scheduleSavePlayerData() {
        synchronized (saveLock) {
            saveDirty = true;
            if (saveTask != null) {
                return;
            }
            saveTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                synchronized (saveLock) {
                    saveTask = null;
                    if (!saveDirty) {
                        return;
                    }
                    saveDirty = false;
                }
                savePlayerData();
            }, 40L);
        }
    }

    public void savePlayerData() {
        synchronized (saveLock) {
            saveDirty = false;
            if (saveTask != null) {
                saveTask.cancel();
                saveTask = null;
            }
            dataConfig.set("players", null);
            ConfigurationSection players = dataConfig.createSection("players");
            Set<UUID> all = new HashSet<>();
            all.addAll(selected.keySet());
            all.addAll(unlocked.keySet());
            all.addAll(layouts.keySet());
            for (UUID uuid : all) {
                ConfigurationSection p = players.createSection(uuid.toString());
                String sel = selected.get(uuid);
                if (sel != null) {
                    p.set("selected", sel);
                }
                Set<String> unlocks = unlocked.get(uuid);
                if (unlocks != null && !unlocks.isEmpty()) {
                    p.set("unlocked", new ArrayList<>(unlocks));
                }
                Map<String, ItemStack[]> playerLayouts = layouts.get(uuid);
                if (playerLayouts != null && !playerLayouts.isEmpty()) {
                    ConfigurationSection layoutsSec = p.createSection("layouts");
                    for (Map.Entry<String, ItemStack[]> e : playerLayouts.entrySet()) {
                        writeContents(layoutsSec.createSection(e.getKey()), e.getValue());
                    }
                }
            }
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save kit-data.yml", e);
            }
        }
    }

    private static ItemStack[] readContents(ConfigurationSection section) {
        ItemStack[] out = new ItemStack[Kit.CONTENTS_SIZE];
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
            if (slot < 0 || slot >= Kit.CONTENTS_SIZE) {
                continue;
            }
            ItemStack stack = section.getItemStack(key);
            if (stack == null || stack.getType().isAir()) {
                stack = readSimpleItem(section.getConfigurationSection(key));
            }
            if (stack != null && !stack.getType().isAir()) {
                out[slot] = stack.clone();
            }
        }
        return out;
    }


    private static ItemStack readSimpleItem(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        String matName = sec.getString("id", sec.getString("material", sec.getString("type")));
        if (matName == null || matName.isBlank()) {
            return null;
        }
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            return null;
        }
        ItemBuilder builder = new ItemBuilder(mat).amount(sec.getInt("amount", 1));
        List<String> enchList = sec.getStringList("enchants");
        if (!enchList.isEmpty()) {
            Map<String, Integer> enchants = new HashMap<>();
            for (String raw : enchList) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String[] parts = raw.split(";");
                String name = parts[0].trim();
                int level = 1;
                if (parts.length > 1) {
                    try {
                        level = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                        level = 1;
                    }
                }
                enchants.put(name, level);
            }
            builder.enchantments(enchants);
        } else if (sec.isConfigurationSection("enchantments")) {
            ConfigurationSection enchSec = sec.getConfigurationSection("enchantments");
            Map<String, Integer> enchants = new HashMap<>();
            if (enchSec != null) {
                for (String k : enchSec.getKeys(false)) {
                    enchants.put(k, enchSec.getInt(k));
                }
            }
            builder.enchantments(enchants);
        }
        if (sec.contains("potion-type") || sec.contains("potion_data")) {
            String potionType = sec.getString("potion-type");
            if (potionType == null && sec.isConfigurationSection("potion_data")) {
                potionType = sec.getString("potion_data.type");
            }
            if (potionType != null) {
                builder.potion(potionType, 0, 180);
            }
        }
        String name = sec.getString("name");
        if (name != null && !name.isBlank()) {
            builder.name(name);
        }
        return builder.build();
    }

    private static void writeContents(ConfigurationSection section, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        for (int i = 0; i < Math.min(contents.length, Kit.CONTENTS_SIZE); i++) {
            ItemStack stack = contents[i];
            if (stack != null && !stack.getType().isAir()) {
                section.set(String.valueOf(i), stack.clone());
            }
        }
    }

    public Map<String, Kit> getKits() {
        return Collections.unmodifiableMap(kits);
    }

    public Kit getKit(String id) {
        if (id == null) {
            return null;
        }
        return kits.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Kit> getKitsBySlot() {
        List<Kit> list = new ArrayList<>(kits.values());
        list.sort((a, b) -> Integer.compare(a.getSlot(), b.getSlot()));
        return list;
    }

    public boolean createKit(String id, String displayName, Material icon, int slot, ItemStack[] contents) {
        String key = id.toLowerCase(Locale.ROOT);
        if (kits.containsKey(key)) {
            return false;
        }
        Kit kit = new Kit(key, displayName == null ? key.toUpperCase(Locale.ROOT) : displayName,
                icon == null ? Material.STONE : icon, slot,
                "unstablecore.kit." + key, "Epic", 0, "&d", contents);
        kits.put(key, kit);
        saveKits();
        return true;
    }

    public boolean deleteKit(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (kits.remove(key) == null) {
            return false;
        }
        File file = kitFile(key);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete kit file: " + file.getName());
        }
        return true;
    }

    public void updateKit(Kit kit) {
        if (kit == null) {
            return;
        }
        kits.put(kit.getId(), kit);
        saveKitFile(kit);
    }

    public boolean isUnlocked(Player player, Kit kit) {
        if (player == null || kit == null) {
            return false;
        }
        if (kit.getTier() != null && kit.getTier().equalsIgnoreCase("Starter")) {
            return true;
        }
        Set<String> set = unlocked.get(player.getUniqueId());
        // Every write path - unlock(), tryPurchaseUnlock() and the player-data loader - stores the
        // id lower-cased, so the lookup has to match. Reading the raw id meant a kit whose id
        // carried any upper-case character could be paid for and still never register as unlocked,
        // charging the player for nothing. Today's ids all come from lower-case file names, so this
        // is a latent trap rather than a live loss - /kit create with mixed case would spring it.
        if (set != null && set.contains(kit.getId().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (plugin.getConfig().getBoolean("kits.unlock-by-permission", false)
                && kit.getPermission() != null && !kit.getPermission().isBlank()
                && player.hasPermission(kit.getPermission())) {
            return true;
        }
        return false;
    }

    public boolean isStarter(Kit kit) {
        return kit != null && kit.getTier() != null && "starter".equalsIgnoreCase(kit.getTier());
    }

    public Kit getRandomKit() {
        if (kits.isEmpty()) {
            return null;
        }
        List<Kit> all = new ArrayList<>(kits.values());
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    public List<Kit> getUnlockedKits(Player player) {
        List<Kit> out = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (isUnlocked(player, kit)) {
                out.add(kit);
            }
        }
        return out;
    }

    public void unlock(UUID uuid, String kitId) {
        unlocked.computeIfAbsent(uuid, u -> ConcurrentHashMap.newKeySet())
                .add(kitId.toLowerCase(Locale.ROOT));
        scheduleSavePlayerData();
    }

    public boolean tryPurchaseUnlock(Player player, Kit kit) {
        if (player == null || kit == null || isStarter(kit)) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!purchasing.add(uuid)) {
            return false;
        }
        try {
            if (isUnlocked(player, kit)) {
                return false;
            }
            double price = kit.getPrice();
            if (price <= 0) {
                return false;
            }
            EconomyManager eco = plugin.getEconomyManager();
            if (eco == null || !eco.isReady() || !eco.takeExact(player, price)) {
                return false;
            }
            Set<String> set = unlocked.computeIfAbsent(uuid, u -> ConcurrentHashMap.newKeySet());
            if (!set.add(kit.getId().toLowerCase(Locale.ROOT))) {
                // Give-back, not income - see EconomyManager.refund.
                eco.refund(player, price);
                return false;
            }
            scheduleSavePlayerData();
            return true;
        } finally {
            purchasing.remove(uuid);
        }
    }

    public void lock(UUID uuid, String kitId) {
        Set<String> set = unlocked.get(uuid);
        if (set != null) {
            set.remove(kitId.toLowerCase(Locale.ROOT));
            scheduleSavePlayerData();
        }
    }

    public String getSelectedId(UUID uuid) {
        return selected.get(uuid);
    }

    public Kit getSelectedKit(Player player) {
        if (player == null) {
            return null;
        }
        String id = selected.get(player.getUniqueId());
        if (id == null) {
            return null;
        }
        Kit kit = getKit(id);
        if (kit == null || !isUnlocked(player, kit)) {
            return null;
        }
        return kit;
    }

    public boolean selectKit(Player player, String kitId) {
        Kit kit = getKit(kitId);
        if (kit == null || !isUnlocked(player, kit)) {
            return false;
        }
        selected.put(player.getUniqueId(), kit.getId());
        scheduleSavePlayerData();
        return true;
    }

    /**
     * The free fallback kit: {@code kits.default-kit}, or the first starter-tier kit if that id
     * is missing from the config. Never a paid or rank-locked kit, so it is safe to hand out on
     * paths that deliberately bypass the loadout cooldown. Null only if no kit qualifies.
     */
    public Kit getDefaultKit() {
        Kit kit = getKit(plugin.getConfig().getString("kits.default-kit", "law"));
        if (kit != null) {
            return kit;
        }
        for (Kit candidate : kits.values()) {
            if (isStarter(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public void ensureDefaultKit(Player player) {
        if (player == null) {
            return;
        }
        if (getSelectedKit(player) != null) {
            return;
        }
        Kit kit = getDefaultKit();
        if (kit == null) {
            return;
        }
        selectKit(player, kit.getId());
    }

    public ItemStack[] getEffectiveContents(Player player, Kit kit) {
        if (kit == null) {
            return new ItemStack[45];
        }
        if (player != null) {
            Map<String, ItemStack[]> playerLayouts = layouts.get(player.getUniqueId());
            if (playerLayouts != null) {
                ItemStack[] custom = playerLayouts.get(kit.getId());
                if (custom != null) {
                    return cloneContents(custom);
                }
            }
        }
        return kit.copyContents();
    }

    public void saveLayout(Player player, String kitId, ItemStack[] layout) {
        if (player == null || kitId == null) {
            return;
        }
        layouts.computeIfAbsent(player.getUniqueId(), u -> new ConcurrentHashMap<>())
                .put(kitId.toLowerCase(Locale.ROOT), cloneContents(layout));
        scheduleSavePlayerData();
    }

    public void clearLayout(Player player, String kitId) {
        if (player == null || kitId == null) {
            return;
        }
        Map<String, ItemStack[]> playerLayouts = layouts.get(player.getUniqueId());
        if (playerLayouts != null) {
            playerLayouts.remove(kitId.toLowerCase(Locale.ROOT));
            scheduleSavePlayerData();
        }
    }

    public boolean applyLoadout(Player player) {
        Kit kit = getSelectedKit(player);
        if (kit == null) {
            return false;
        }
        return applyKit(player, kit);
    }

    /** Gives the player a random kit they have unlocked, without changing their selected kit. */
    public Kit giveRandomUnlockedKit(Player player) {
        if (player == null) {
            return null;
        }
        List<Kit> options = new ArrayList<>();
        for (Kit candidate : kits.values()) {
            if (isUnlocked(player, candidate)) {
                options.add(candidate);
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        Kit kit = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        return applyKit(player, kit) ? kit : null;
    }

    public static boolean isInventoryEmpty(Player player) {
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.getStorageContents()) {
            if (!isEmpty(stack)) {
                return false;
            }
        }
        for (ItemStack stack : inv.getArmorContents()) {
            if (!isEmpty(stack)) {
                return false;
            }
        }
        return isEmpty(inv.getItemInOffHand());
    }

    public boolean applyKit(Player player, Kit kit) {
        if (player == null || kit == null) {
            return false;
        }
        ItemStack[] layout = getEffectiveContents(player, kit);
        PlayerInventory inv = player.getInventory();
        // A held shulker box's virtual edit GUI holds its contents in a separate Inventory object
        // that inv.clear() below has no idea exists - without settling it first, the box (and
        // anything staged into it) is silently deleted, and any kit item that happens to land as
        // a shulker box in the same hand slot could later get overwritten by the stale session.
        if (plugin.getHeldShulkerListener() != null) {
            plugin.getHeldShulkerListener().forceCloseSession(player);
        }
        inv.clear();
        inv.setArmorContents(null);
        inv.setItemInOffHand(null);

        boolean[] used = new boolean[layout.length];
        for (int i = 0; i < layout.length; i++) {
            ItemStack stack = layout[i];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            Material type = stack.getType();
            if (isPreviewOnly(type)) {
                used[i] = true;
                continue;
            }
            String name = type.name();
            if ((name.endsWith("_HELMET") || name.endsWith("_SKULL") || type == Material.CARVED_PUMPKIN
                    || type == Material.PLAYER_HEAD) && isEmpty(inv.getHelmet())) {
                inv.setHelmet(stack.clone());
                used[i] = true;
            } else if (name.endsWith("_CHESTPLATE") && isEmpty(inv.getChestplate())) {
                inv.setChestplate(stack.clone());
                used[i] = true;
            } else if (name.endsWith("_LEGGINGS") && isEmpty(inv.getLeggings())) {
                inv.setLeggings(stack.clone());
                used[i] = true;
            } else if (name.endsWith("_BOOTS") && isEmpty(inv.getBoots())) {
                inv.setBoots(stack.clone());
                used[i] = true;
            }
        }
        for (int i = 0; i < layout.length; i++) {
            if (used[i]) {
                continue;
            }
            ItemStack stack = layout[i];
            if (stack != null && stack.getType() == Material.ELYTRA && isEmpty(inv.getChestplate())) {
                inv.setChestplate(stack.clone());
                used[i] = true;
                break;
            }
        }
        int[] offhandOrder = {46, 40};
        for (int idx : offhandOrder) {
            if (idx >= layout.length || used[idx]) {
                continue;
            }
            ItemStack stack = layout[idx];
            if (stack != null && !stack.getType().isAir() && !isPreviewOnly(stack.getType())) {
                inv.setItemInOffHand(stack.clone());
                used[idx] = true;
                break;
            }
        }
        if (isEmpty(inv.getItemInOffHand())) {
            for (int i = 0; i < layout.length; i++) {
                if (used[i]) {
                    continue;
                }
                ItemStack stack = layout[i];
                if (stack == null || stack.getType().isAir() || isPreviewOnly(stack.getType())) {
                    continue;
                }
                Material type = stack.getType();
                if (type == Material.SHIELD || type == Material.TOTEM_OF_UNDYING || type.name().endsWith("_BANNER")) {
                    inv.setItemInOffHand(stack.clone());
                    used[i] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < Math.min(36, layout.length); i++) {
            if (used[i]) {
                continue;
            }
            ItemStack stack = layout[i];
            if (stack != null && !stack.getType().isAir() && !isPreviewOnly(stack.getType())) {
                inv.setItem(i, stack.clone());
                used[i] = true;
            } else if (stack != null && isPreviewOnly(stack.getType())) {
                used[i] = true;
            }
        }
        for (int i = 0; i < layout.length; i++) {
            // 52/53 are the admin editor's Cancel/Save button slots and are never issued - see
            // warnAboutReservedSlots, which logs at load time so this is never a silent drop.
            if (used[i] || i >= RESERVED_BUTTON_SLOT) {
                continue;
            }
            ItemStack stack = layout[i];
            if (stack == null || stack.getType().isAir() || isPreviewOnly(stack.getType())) {
                continue;
            }
            HashMap<Integer, ItemStack> leftover = inv.addItem(stack.clone());
            if (!leftover.isEmpty()) {
                // Do not drop the remainder. Kit gear is plugin-issued, so anything left on the
                // floor at spawn is free material that anyone can pick up, minted again on every
                // claim. A kit that cannot fit in a player inventory is a configuration error, so
                // say so once with enough detail to fix it instead of leaking items.
                plugin.getLogger().warning("Kit '" + kit.getId() + "' does not fit in a player "
                        + "inventory - slot " + i + " (" + stack.getType() + ") was not given to "
                        + player.getName() + ". Remove items from the kit or free up a slot.");
            }
        }
        player.updateInventory();
        return true;
    }


    private static boolean isPreviewOnly(Material type) {
        return type == Material.GRAY_STAINED_GLASS_PANE
                || type == Material.BLACK_STAINED_GLASS_PANE
                || type == Material.LIGHT_GRAY_STAINED_GLASS_PANE
                || type == Material.NAME_TAG;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    public ItemStack[] snapshotPlayerKitLayout(Player player) {
        ItemStack[] out = new ItemStack[Kit.CONTENTS_SIZE];
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                out[i] = stack.clone();
            }
        }
        ItemStack helm = inv.getHelmet();
        ItemStack chest = inv.getChestplate();
        ItemStack legs = inv.getLeggings();
        ItemStack boots = inv.getBoots();
        if (helm != null && !helm.getType().isAir()) {
            out[0] = helm.clone();
        }
        if (chest != null && !chest.getType().isAir()) {
            out[2] = chest.clone();
        }
        if (legs != null && !legs.getType().isAir()) {
            out[4] = legs.clone();
        }
        if (boots != null && !boots.getType().isAir()) {
            out[6] = boots.clone();
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            out[40] = off.clone();
        }
        return out;
    }


    public static ItemStack[] snapshotStorage(Player player) {
        ItemStack[] out = new ItemStack[Kit.CONTENTS_SIZE];
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                out[i] = stack.clone();
            }
        }
        // Fixed slots matching Bukkit's own PlayerInventory layout (36-39 armor, 40 offhand),
        // which is also what applyKit reads back and what the shipped kit files use.
        //
        // This used to pack sequentially from 36, putting the offhand at 36 and armor after it.
        // applyKit finds armor by material name so that part still worked, but it only looks for
        // an offhand at index 46 then 40 - so an offhand item that was not a shield, totem or
        // banner fell through to the addItem fallback and, on a full inventory, was dropped on
        // the floor every single time the kit was claimed.
        ItemStack[] armor = inv.getArmorContents();
        if (armor != null) {
            for (int a = 0; a < Math.min(armor.length, 4); a++) {
                if (armor[a] != null && !armor[a].getType().isAir()) {
                    out[36 + a] = armor[a].clone();
                }
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            out[40] = off.clone();
        }
        return out;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[Kit.CONTENTS_SIZE];
        if (contents == null) {
            return out;
        }
        for (int i = 0; i < Math.min(contents.length, Kit.CONTENTS_SIZE); i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                out[i] = contents[i].clone();
            }
        }
        return out;
    }
}
