package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelArenaManager;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

/**
 * Three-step duel request GUI:
 * Step 1: Select an arena.
 * Step 2: Select a kit (from challenger's unlocked kits).
 * Step 3: Select the wager amount (No Wager, preset coins, or custom chat amount).
 *
 * <p>Clean layout with zero filler glass panes.
 */
public final class DuelMapGui implements InventoryHolder {

    private enum Step { MAP, KIT, WAGER }

    private final UnstableCore plugin;
    private final Player viewer;
    private final UUID targetUuid;
    private final String targetName;
    private final Inventory inventory;

    private Step step = Step.MAP;
    private String selectedArenaId;
    private String selectedKitId;

    // Slot mappings
    private final Map<Integer, String> arenaSlots = new HashMap<>();
    private final Map<Integer, String> kitSlots = new HashMap<>();
    private final Map<Integer, Double> wagerSlots = new HashMap<>();

    private int cancelSlot;
    private int backSlot;
    private int customWagerSlot;

    private DuelMapGui(UnstableCore plugin, Player viewer, Player target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.targetUuid = target.getUniqueId();
        this.targetName = target.getName();

        int size = 36;
        Component title = MessageUtil.parse("&8» &dDuel Setup &8«");
        this.inventory = Bukkit.createInventory(this, size, title);
        fillMap();
    }

    private ConfigurationSection section() {
        ConfigurationSection gui = plugin.getConfigManager().getDuels().getConfigurationSection("gui");
        return gui != null ? gui : plugin.getConfigManager().getDuels().createSection("gui");
    }

    public void refreshLive() {
        if (step == Step.MAP) {
            fillMap();
        } else if (step == Step.KIT) {
            fillKit();
        } else {
            fillWager();
        }
    }

    // ----------------------------------------------------------------------------------
    // Step 1: Map selection
    // ----------------------------------------------------------------------------------

    private void fillMap() {
        inventory.clear();
        arenaSlots.clear();
        ConfigurationSection gui = section();

        // Top bar
        inventory.setItem(4, buildTargetHead(gui));

        // Arena slots
        DuelArenaManager arenaManager = plugin.getDuelManager().getDuelArenaManager();
        List<Arena> arenas = arenaManager.eligibleArenas();
        int[] positions = layoutPositions(arenas.size(), inventory.getSize());
        for (int i = 0; i < arenas.size() && i < positions.length; i++) {
            Arena arena = arenas.get(i);
            DuelArenaManager.Availability availability = arenaManager.availability(arena.getId());
            inventory.setItem(positions[i], buildArenaIcon(gui, arena, availability));
            if (availability == DuelArenaManager.Availability.AVAILABLE) {
                arenaSlots.put(positions[i], arena.getId());
            }
        }

        // Cancel
        cancelSlot = inventory.getSize() - 5;
        inventory.setItem(cancelSlot, new ItemBuilder(Material.BARRIER)
                .name("&cCancel")
                .lore("&7Click to close")
                .hideAttributes().build());
    }

    // ----------------------------------------------------------------------------------
    // Step 2: Kit selection
    // ----------------------------------------------------------------------------------

    private void fillKit() {
        inventory.clear();
        kitSlots.clear();
        ConfigurationSection gui = section();

        // Top bar: Back (slot 0), Target head (slot 4), Cancel (slot 8)
        backSlot = 0;
        inventory.setItem(backSlot, new ItemBuilder(Material.ARROW)
                .name("&7← &eBack to Arena Selection")
                .lore("&7Click to pick a different arena")
                .hideAttributes().build());

        inventory.setItem(4, buildTargetHead(gui));

        cancelSlot = 8;
        inventory.setItem(cancelSlot, new ItemBuilder(Material.BARRIER)
                .name("&cCancel")
                .lore("&7Click to close")
                .hideAttributes().build());

        // Get kits unlocked by challenger (or all starter kits if none)
        List<Kit> unlockedKits = plugin.getKitManager().getUnlockedKits(viewer);
        if (unlockedKits.isEmpty()) {
            for (Kit k : plugin.getKitManager().getKits().values()) {
                if (plugin.getKitManager().isStarter(k)) {
                    unlockedKits.add(k);
                }
            }
        }
        if (unlockedKits.isEmpty()) {
            unlockedKits.addAll(plugin.getKitManager().getKits().values());
        }

        int[] positions = layoutPositions(unlockedKits.size(), inventory.getSize());
        for (int i = 0; i < unlockedKits.size() && i < positions.length; i++) {
            Kit kit = unlockedKits.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7Tier: &f" + (kit.getTier() != null ? kit.getTier() : "Standard"));
            lore.add("&7Both players will receive this kit.");
            lore.add("");
            lore.add("&e> Click to select this kit");

            inventory.setItem(positions[i], new ItemBuilder(kit.getIcon() != null ? kit.getIcon() : Material.IRON_SWORD)
                    .name("&e&l" + MessageUtil.strip(kit.getDisplayName()))
                    .lore(lore)
                    .hideAttributes().build());
            kitSlots.put(positions[i], kit.getId());
        }

        // Bottom info
        Arena arena = plugin.getDuelManager().getDuelArenaManager().resolve(selectedArenaId);
        String arenaName = arena != null ? arena.getDisplayName() : selectedArenaId;
        inventory.setItem(31, new ItemBuilder(Material.MAP)
                .name("&d&lArena: " + arenaName)
                .lore("&7Selected arena for this duel", "&7Now select a kit above")
                .hideAttributes().build());
    }

    // ----------------------------------------------------------------------------------
    // Step 3: Wager selection
    // ----------------------------------------------------------------------------------

    private void fillWager() {
        inventory.clear();
        wagerSlots.clear();
        ConfigurationSection gui = section();

        Arena arena = plugin.getDuelManager().getDuelArenaManager().resolve(selectedArenaId);
        String arenaName = arena != null ? arena.getDisplayName() : selectedArenaId;
        Kit kit = plugin.getKitManager().getKit(selectedKitId);
        String kitName = kit != null ? MessageUtil.strip(kit.getDisplayName()) : selectedKitId;

        // Top Row: Back (slot 0), Target head (slot 4), Cancel (slot 8)
        backSlot = 0;
        inventory.setItem(backSlot, new ItemBuilder(Material.ARROW)
                .name("&7← &eBack to Kit Selection")
                .lore("&7Click to choose a different kit")
                .hideAttributes().build());

        inventory.setItem(4, buildTargetHead(gui));

        cancelSlot = 8;
        inventory.setItem(cancelSlot, new ItemBuilder(Material.BARRIER)
                .name("&cCancel")
                .lore("&7Click to close")
                .hideAttributes().build());

        // Middle Row: Wager options
        double min = Math.max(0, plugin.getConfigManager().getDuels().getDouble("wager.min", 0));
        double max = Math.max(min, plugin.getConfigManager().getDuels().getDouble("wager.max", 1_000_000));

        // Slot 19: No Wager
        inventory.setItem(19, new ItemBuilder(Material.EMERALD)
                .name("&a&lNo Wager")
                .lore("&7Play for free without betting",
                        "&7Winner gets default reward",
                        "",
                        "&e> Click to send duel request")
                .hideAttributes().build());
        wagerSlots.put(19, 0.0);

        // Preset wager amounts
        double[] presets = {100, 500, 1000, 5000, 10000};
        Material[] mats = {
                Material.GOLD_NUGGET,
                Material.GOLD_INGOT,
                Material.GOLD_BLOCK,
                Material.DIAMOND,
                Material.DIAMOND_BLOCK
        };
        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < presets.length; i++) {
            double amount = presets[i];
            boolean valid = (min <= 0 || amount >= min) && amount <= max;
            if (valid) {
                inventory.setItem(slots[i], new ItemBuilder(mats[i])
                        .name("&6&l" + EconomyManager.format(amount) + " coins")
                        .lore("&7Wager: &6" + EconomyManager.format(amount) + " &7coins",
                                "&7Winner takes: &6" + EconomyManager.format(amount * 2) + " &7coins",
                                "",
                                "&e> Click to send duel request")
                        .hideAttributes().build());
                wagerSlots.put(slots[i], amount);
            }
        }

        // Slot 25: Custom Amount
        customWagerSlot = 25;
        inventory.setItem(customWagerSlot, new ItemBuilder(Material.NAME_TAG)
                .name("&b&lCustom Amount")
                .lore("&7Type your desired wager in chat",
                        "",
                        "&e> Click to enter amount in chat")
                .hideAttributes().build());

        // Bottom Row: Summary (slot 31)
        inventory.setItem(31, new ItemBuilder(Material.NETHER_STAR)
                .name("&d&lDuel Summary")
                .lore("&7Arena: &f" + arenaName,
                        "&7Kit: &e" + kitName,
                        "&a✔ Ready to send challenge")
                .hideAttributes().build());
    }

    // ----------------------------------------------------------------------------------
    // Click handling
    // ----------------------------------------------------------------------------------

    public void handleClick(Player player, int slot) {
        if (step == Step.MAP) {
            handleMapClick(player, slot);
        } else if (step == Step.KIT) {
            handleKitClick(player, slot);
        } else {
            handleWagerClick(player, slot);
        }
    }

    private void handleMapClick(Player player, int slot) {
        if (slot == cancelSlot) {
            player.closeInventory();
            return;
        }
        String arenaId = arenaSlots.get(slot);
        if (arenaId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            MessageUtil.send(player, "&cThat player isn't online anymore.");
            player.closeInventory();
            return;
        }
        DuelArenaManager arenaManager = plugin.getDuelManager().getDuelArenaManager();
        if (arenaManager.availability(arenaId) != DuelArenaManager.Availability.AVAILABLE) {
            MessageUtil.send(player, "&cThat arena is currently unavailable - pick another.");
            fillMap();
            return;
        }
        selectedArenaId = arenaId;
        step = Step.KIT;
        fillKit();
    }

    private void handleKitClick(Player player, int slot) {
        if (slot == cancelSlot) {
            player.closeInventory();
            return;
        }
        if (slot == backSlot) {
            step = Step.MAP;
            selectedArenaId = null;
            fillMap();
            return;
        }
        String kitId = kitSlots.get(slot);
        if (kitId == null) {
            return;
        }
        selectedKitId = kitId;
        step = Step.WAGER;
        fillWager();
    }

    private void handleWagerClick(Player player, int slot) {
        if (slot == cancelSlot) {
            player.closeInventory();
            return;
        }
        if (slot == backSlot) {
            step = Step.KIT;
            selectedKitId = null;
            fillKit();
            return;
        }
        if (slot == customWagerSlot) {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target == null || !target.isOnline()) {
                MessageUtil.send(player, "&cThat player isn't online anymore.");
                player.closeInventory();
                return;
            }
            player.closeInventory();
            plugin.getDuelManager().beginWagerPrompt(player, target, selectedArenaId, selectedKitId);
            return;
        }
        Double wager = wagerSlots.get(slot);
        if (wager == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            MessageUtil.send(player, "&cThat player isn't online anymore.");
            player.closeInventory();
            return;
        }
        DuelArenaManager arenaManager = plugin.getDuelManager().getDuelArenaManager();
        if (arenaManager.availability(selectedArenaId) != DuelArenaManager.Availability.AVAILABLE) {
            MessageUtil.send(player, "&cThat arena is currently unavailable.");
            step = Step.MAP;
            selectedArenaId = null;
            selectedKitId = null;
            fillMap();
            return;
        }
        player.closeInventory();
        plugin.getDuelManager().createRequest(player, target, selectedArenaId, selectedKitId, wager);
    }

    // ----------------------------------------------------------------------------------
    // Item builders
    // ----------------------------------------------------------------------------------

    private ItemStack buildTargetHead(ConfigurationSection gui) {
        Player target = Bukkit.getPlayer(targetUuid);
        boolean online = target != null && target.isOnline();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            if (online) {
                meta.setPlayerProfile(target.getPlayerProfile());
            }
            meta.displayName(noItalic(MessageUtil.parse((online ? "&b" : "&7") + targetName)));
            List<String> loreLines = online
                    ? gui.getStringList("target-online-lore")
                    : gui.getStringList("target-offline-lore");
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(noItalic(MessageUtil.parse(line)));
            }
            meta.lore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack buildArenaIcon(ConfigurationSection gui, Arena arena, DuelArenaManager.Availability availability) {
        Material mat = material(gui.getString("map-material"), Material.MAP);
        String name = MessageUtil.apply(gui.getString("map-name", "&d&l{map}"), Map.of(
                "map", arena.getDisplayName(), "id", arena.getId()
        ));
        List<String> loreTemplate = availability == DuelArenaManager.Availability.AVAILABLE
                ? gui.getStringList("map-lore-available")
                : gui.getStringList("map-lore-unavailable");
        List<String> lore = new ArrayList<>();
        String reason = switch (availability) {
            case RESERVED -> "In use";
            case GRACE_PERIOD -> "Cooling down";
            case NO_SPAWNS -> "No valid spawns";
            case MISSING -> "Unavailable";
            case AVAILABLE -> "";
        };
        for (String line : loreTemplate) {
            lore.add(MessageUtil.apply(line, Map.of("reason", reason)));
        }
        Material effectiveMat = availability == DuelArenaManager.Availability.AVAILABLE ? mat : Material.BARRIER;
        return new ItemBuilder(effectiveMat).name(name).lore(lore).hideAttributes().build();
    }

    // ----------------------------------------------------------------------------------
    // Static factory + helpers
    // ----------------------------------------------------------------------------------

    public static void open(UnstableCore plugin, Player challenger, Player target) {
        challenger.openInventory(new DuelMapGui(plugin, challenger, target).getInventory());
    }

    private static int[] layoutPositions(int count, int size) {
        List<Integer> slots = new ArrayList<>();
        int[] rows = size >= 36
                ? new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25}
                : size >= 27
                ? new int[]{10, 11, 12, 13, 14, 15, 16}
                : new int[]{2, 3, 4, 5, 6};
        for (int slot : rows) {
            if (slots.size() >= count) {
                break;
            }
            slots.add(slot);
        }
        int[] out = new int[slots.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = slots.get(i);
        }
        return out;
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

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
