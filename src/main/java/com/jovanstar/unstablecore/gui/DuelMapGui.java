package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelArenaManager;
import com.jovanstar.unstablecore.model.Arena;
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
 * Kit is a fixed v1 default (no picker needed yet), so the duel request flow starts here: pick
 * a duel-eligible arena (with live availability), then proceed to the wager chat prompt. Mirrors
 * {@link VoteGui}'s icon-grid style, scoped to the dedicated duel arena pool.
 */
public final class DuelMapGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final UUID targetUuid;
    private final String targetName;
    private final Inventory inventory;
    private final Map<Integer, String> arenaSlots = new HashMap<>();
    private int cancelSlot;

    private DuelMapGui(UnstableCore plugin, Player viewer, Player target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.targetUuid = target.getUniqueId();
        this.targetName = target.getName();

        ConfigurationSection gui = section();
        int size = Math.max(9, Math.min(54, gui.getInt("size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        Component title = MessageUtil.parse(gui.getString("map-title", "&8» &dDuel: Select Map &8«"));
        this.inventory = Bukkit.createInventory(this, size, title);
        fill();
    }

    private ConfigurationSection section() {
        ConfigurationSection gui = plugin.getConfigManager().getDuels().getConfigurationSection("gui");
        return gui != null ? gui : plugin.getConfigManager().getDuels().createSection("gui");
    }

    public void refreshLive() {
        fill();
    }

    private void fill() {
        ConfigurationSection gui = section();
        arenaSlots.clear();

        Material fillerMat = material(gui.getString("filler"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        int targetSlot = gui.getInt("target-slot", 4);
        if (targetSlot >= 0 && targetSlot < inventory.getSize()) {
            inventory.setItem(targetSlot, buildTargetHead(gui));
        }

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

        cancelSlot = gui.getInt("cancel-slot", inventory.getSize() - 5);
        if (cancelSlot >= 0 && cancelSlot < inventory.getSize()) {
            Material cancelMat = material("BARRIER", Material.BARRIER);
            inventory.setItem(cancelSlot, new ItemBuilder(cancelMat)
                    .name(gui.getString("cancel-name", "&cCancel"))
                    .hideAttributes().build());
        }
    }

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
            meta.displayName(noItalic(MessageUtil.parse((online ? "&f" : "&7") + targetName)));
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

    public void handleClick(Player player, int slot) {
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
            fill();
            return;
        }
        player.closeInventory();
        plugin.getDuelManager().beginWagerPrompt(player, target, arenaId);
    }

    public static void open(UnstableCore plugin, Player challenger, Player target) {
        challenger.openInventory(new DuelMapGui(plugin, challenger, target).getInventory());
    }

    private static int[] layoutPositions(int count, int size) {
        List<Integer> slots = new ArrayList<>();
        int[] rows = size >= 27 ? new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25} : new int[]{2, 3, 4, 5, 6};
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
