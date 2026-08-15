package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelArenaManager;
import com.jovanstar.unstablecore.manager.DuelStatsManager;
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
 * Step 2: Select a kit (any kit the challenger has unlocked - this is a for-fun duel, not
 *         restricted to one fixed loadout).
 * Step 3: Select the wager amount (No Wager, preset coins, or custom chat amount).
 *
 * <p>Clean layout - no filler glass panes cluttering the background, just a consistent
 * red-cancel / green-confirm color language plus a stats icon, same idea as DuelQueueGui.
 */
public final class DuelMapGui implements InventoryHolder {

    private static final int SIZE = 36;
    private static final String DIVIDER = "&8&m                    ";

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

        Component title = MessageUtil.parse("&8» &d&lDuel Setup &8«");
        this.inventory = Bukkit.createInventory(this, SIZE, title);
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

    private ItemStack statsIcon() {
        DuelStatsManager stats = plugin.getDuelStatsManager();
        int wins = stats != null ? stats.getWins(viewer.getUniqueId()) : 0;
        int losses = stats != null ? stats.getLosses(viewer.getUniqueId()) : 0;
        int streak = stats != null ? stats.getCurrentStreak(viewer.getUniqueId()) : 0;
        return new ItemBuilder(Material.COMPASS)
                .name("&d&lYour Stats")
                .lore(List.of(
                        DIVIDER,
                        "&7Wins: &a" + wins + "  &7Losses: &c" + losses,
                        "&7Streak: &e" + streak,
                        "&8This duel is for fun only - no",
                        "&8effect on ranked ELO unless you",
                        "&8queued through &f/duel queue ranked"
                ))
                .hideAttributes().build();
    }

    // ----------------------------------------------------------------------------------
    // Step 1: Map selection
    // ----------------------------------------------------------------------------------

    private void fillMap() {
        arenaSlots.clear();
        inventory.clear();
        ConfigurationSection gui = section();

        inventory.setItem(0, cancelButton());
        cancelSlot = 0;
        inventory.setItem(4, buildTargetHead(gui));
        inventory.setItem(8, statsIcon());

        DuelArenaManager arenaManager = plugin.getDuelManager().getDuelArenaManager();
        List<Arena> arenas = arenaManager.eligibleArenas();
        int[] positions = layoutPositions(arenas.size());
        if (arenas.isEmpty()) {
            inventory.setItem(positions.length > 0 ? positions[0] : 13, new ItemBuilder(Material.BARRIER)
                    .name("&cNo duel arenas configured")
                    .lore(List.of(DIVIDER, "&7Ask an admin to add one to", "&7duels.yml's arenas list."))
                    .hideAttributes().build());
        }
        for (int i = 0; i < arenas.size() && i < positions.length; i++) {
            Arena arena = arenas.get(i);
            DuelArenaManager.Availability availability = arenaManager.availability(arena);
            inventory.setItem(positions[i], buildArenaIcon(gui, arena, availability));
            if (availability == DuelArenaManager.Availability.AVAILABLE) {
                arenaSlots.put(positions[i], arena.getId());
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // Step 2: Kit selection
    // ----------------------------------------------------------------------------------

    private void fillKit() {
        kitSlots.clear();
        inventory.clear();
        ConfigurationSection gui = section();

        backSlot = 0;
        inventory.setItem(backSlot, backButton("Arena Selection", 2));
        inventory.setItem(4, buildTargetHead(gui));

        Arena arena = plugin.getDuelManager().getDuelArenaManager().resolve(selectedArenaId);
        String arenaName = arena != null ? MessageUtil.strip(arena.getDisplayName()) : String.valueOf(selectedArenaId);
        inventory.setItem(8, new ItemBuilder(Material.FILLED_MAP)
                .name("&d&lArena Selected")
                .lore(List.of(DIVIDER, "&f" + arenaName, "&e▸ Click Back to change"))
                .hideAttributes().build());

        // Any kit the challenger has unlocked - a casual for-fun duel isn't locked to one loadout.
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

        int[] positions = layoutPositions(unlockedKits.size());
        for (int i = 0; i < unlockedKits.size() && i < positions.length; i++) {
            Kit kit = unlockedKits.get(i);
            List<String> lore = List.of(
                    DIVIDER,
                    "&7Tier: &f" + (kit.getTier() != null ? kit.getTier() : "Standard"),
                    "&7Both players get this kit.",
                    "&e▸ Click to select"
            );
            inventory.setItem(positions[i], new ItemBuilder(kit.getIcon() != null ? kit.getIcon() : Material.IRON_SWORD)
                    .name("&e&l" + MessageUtil.strip(kit.getDisplayName()))
                    .lore(lore)
                    .hideAttributes().build());
            kitSlots.put(positions[i], kit.getId());
        }
    }

    // ----------------------------------------------------------------------------------
    // Step 3: Wager selection
    // ----------------------------------------------------------------------------------

    private void fillWager() {
        wagerSlots.clear();
        inventory.clear();

        Arena arena = plugin.getDuelManager().getDuelArenaManager().resolve(selectedArenaId);
        String arenaName = arena != null ? MessageUtil.strip(arena.getDisplayName()) : String.valueOf(selectedArenaId);
        Kit kit = plugin.getKitManager().getKit(selectedKitId);
        String kitName = kit != null ? MessageUtil.strip(kit.getDisplayName()) : String.valueOf(selectedKitId);

        backSlot = 0;
        inventory.setItem(backSlot, backButton("Kit Selection", 3));
        inventory.setItem(4, buildTargetHead(section()));
        inventory.setItem(8, new ItemBuilder(Material.NETHER_STAR)
                .name("&d&lDuel Summary")
                .lore(List.of(
                        DIVIDER,
                        "&7Arena &8» &f" + arenaName,
                        "&7Kit &8» &e" + kitName,
                        "&a✔ &7Ready to send"
                ))
                .hideAttributes().build());

        double min = Math.max(0, plugin.getConfigManager().getDuels().getDouble("wager.min", 0));
        double max = Math.max(min, plugin.getConfigManager().getDuels().getDouble("wager.max", 1_000_000));

        inventory.setItem(19, new ItemBuilder(Material.LIME_DYE)
                .name("&a&lNo Wager &8(For Fun)")
                .lore(List.of(
                        DIVIDER,
                        "&7Play for free without betting",
                        "&7Winner gets the default reward",
                        "&e▸ Click to send the challenge"
                ))
                .hideAttributes().build());
        wagerSlots.put(19, 0.0);

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
                        .lore(List.of(
                                DIVIDER,
                                "&7Wager &8» &6" + EconomyManager.format(amount) + " &7coins each",
                                "&7Winner takes &8» &6" + EconomyManager.format(amount * 2) + " &7coins",
                                "&e▸ Click to send the challenge"
                        ))
                        .hideAttributes().build());
                wagerSlots.put(slots[i], amount);
            }
        }

        customWagerSlot = 25;
        inventory.setItem(customWagerSlot, new ItemBuilder(Material.NAME_TAG)
                .name("&b&lCustom Amount")
                .lore(List.of(
                        DIVIDER,
                        "&7Type your desired wager in chat",
                        "&e▸ Click to enter an amount"
                ))
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
            MessageUtil.send(player, "&cThat arena just became unavailable - pick another.");
            fillMap();
            return;
        }
        selectedArenaId = arenaId;
        step = Step.KIT;
        fillKit();
    }

    private void handleKitClick(Player player, int slot) {
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
            MessageUtil.send(player, "&cThat arena just became unavailable.");
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
                // Target is online by construction (this GUI only opens for an online target),
                // so the live profile already carries resolved textures - no Mojang lookup needed.
                meta.setPlayerProfile(target.getPlayerProfile());
            }
            meta.displayName(noItalic(MessageUtil.parse((online ? "&f&l" : "&7&l") + targetName)));
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
        boolean available = availability == DuelArenaManager.Availability.AVAILABLE;
        Material mat = available ? material(gui.getString("map-material"), Material.FILLED_MAP) : Material.BARRIER;
        String name = MessageUtil.apply(gui.getString("map-name", "&d&l{map}"), Map.of(
                "map", arena.getDisplayName(), "id", arena.getId()
        ));
        List<String> loreTemplate = available
                ? gui.getStringList("map-lore-available")
                : gui.getStringList("map-lore-unavailable");
        String reason = switch (availability) {
            case RESERVED -> "In use";
            case GRACE_PERIOD -> "Cooling down";
            case NO_SPAWNS -> "No valid spawns";
            case MISSING -> "Unavailable";
            case AVAILABLE -> "";
        };
        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            lore.add(MessageUtil.apply(line, Map.of("reason", reason)));
        }
        return new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build();
    }

    private ItemStack cancelButton() {
        return new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name("&c&lCancel")
                .lore(List.of(DIVIDER, "&7Close without sending a request."))
                .hideAttributes().build();
    }

    private ItemStack backButton(String backTo, int stepNum) {
        return new ItemBuilder(Material.ARROW)
                .name("&7« &eBack")
                .lore(List.of(
                        DIVIDER,
                        "&7Step " + stepNum + "&8/&73",
                        "&7Return to " + backTo
                ))
                .hideAttributes().build();
    }

    // ----------------------------------------------------------------------------------
    // Static factory + helpers
    // ----------------------------------------------------------------------------------

    public static void open(UnstableCore plugin, Player challenger, Player target) {
        challenger.openInventory(new DuelMapGui(plugin, challenger, target).getInventory());
    }

    private static int[] layoutPositions(int count) {
        int[] rows = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int len = Math.min(count, rows.length);
        int[] out = new int[len];
        System.arraycopy(rows, 0, out, 0, len);
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
