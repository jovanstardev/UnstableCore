package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Arena;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.Objects;

public final class ArenaGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Inventory inventory;
    private final Map<Integer, String> slotActions = new HashMap<>();
    private int lastPlayers = Integer.MIN_VALUE;
    private String lastMap = null;
    private boolean lastJoinable;

    public ArenaGui(UnstableCore plugin) {
        this.plugin = plugin;
        ConfigurationSection gui = section();
        int size = Math.max(9, Math.min(54, gui.getInt("size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        Component title = MessageUtil.parse(gui.getString("title", "&8» Select Arena «"));
        this.inventory = Bukkit.createInventory(this, size, title);
        fill(gui);
    }

    private ConfigurationSection section() {
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("guis.arenas");
        if (gui != null) {
            return gui;
        }
        return plugin.getConfig().createSection("guis.arenas");
    }

    private void fill(ConfigurationSection gui) {
        Material fillerMat = material(gui.getString("filler"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        Arena active = plugin.getArenaManager().getActiveArena();
        String mapName = active != null ? active.getDisplayName() : gui.getString("no-arena-map", "&cNone");
        int players = active != null
                ? plugin.getArenaManager().countTrackedPlayersInArena(active.getId())
                : 0;
        applyJoinItem(gui, mapName, players, active != null && active.hasCenter());
    }

    public void refreshLive(String mapName, int players, boolean joinable) {
        ConfigurationSection gui = section();
        if (mapName == null || mapName.isBlank()) {
            mapName = gui.getString("no-arena-map", "&cNone");
        }
        if (players == lastPlayers && Objects.equals(mapName, lastMap) && joinable == lastJoinable) {
            return;
        }
        applyJoinItem(gui, mapName, players, joinable);
    }

    private void applyJoinItem(ConfigurationSection gui, String mapName, int players, boolean joinable) {
        lastPlayers = players;
        lastMap = mapName;
        lastJoinable = joinable;

        int slot = gui.getInt("join-slot", 13);
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        Material mat = material(gui.getString("join-material"), Material.PINK_DYE);
        String name = gui.getString("join-name", "&dᴊᴏɪɴ ꜰꜰᴀ");
        List<String> lore = new ArrayList<>();
        for (String line : gui.getStringList("join-lore")) {
            lore.add(MessageUtil.apply(line, Map.of(
                    "map", MessageUtil.strip(mapName),
                    "displayname", MessageUtil.strip(mapName),
                    "players", String.valueOf(players)
            )));
        }
        inventory.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
        if (joinable) {
            slotActions.put(slot, "active");
        } else {
            slotActions.remove(slot);
        }
    }

    public void handleClick(Player player, int slot) {
        String action = slotActions.get(slot);
        if (action == null) {
            return;
        }
        player.closeInventory();
        if ("active".equals(action)) {
            Arena active = plugin.getArenaManager().getActiveArena();
            if (active == null || !active.hasCenter()) {
                MessageUtil.sendConfig(player, "arena-none-active", Map.of());
                return;
            }
            plugin.getArenaManager().teleportToArena(player, active.getId());
        }
    }

    public static void open(UnstableCore plugin, Player player) {
        player.openInventory(new ArenaGui(plugin).getInventory());
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
