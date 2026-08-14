package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EventManager;
import com.jovanstar.unstablecore.manager.MapVoteManager;
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

public final class VoteGui implements InventoryHolder {

    private final UnstableCore plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, String> slotActions = new HashMap<>();

    public VoteGui(UnstableCore plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        ConfigurationSection gui = section();
        int size = Math.max(9, Math.min(54, gui.getInt("size", 27)));
        if (size % 9 != 0) {
            size = 27;
        }
        Component title = MessageUtil.parse(gui.getString("title", "&8» &dMap Vote &8«"));
        this.inventory = Bukkit.createInventory(this, size, title);
        fill();
    }

    private ConfigurationSection section() {
        ConfigurationSection gui = plugin.getConfig().getConfigurationSection("guis.vote");
        if (gui != null) {
            return gui;
        }
        return plugin.getConfig().createSection("guis.vote");
    }

    public void refresh() {
        fill();
    }

    private void fill() {
        ConfigurationSection gui = section();
        MapVoteManager vote = plugin.getMapVoteManager();
        slotActions.clear();

        Material fillerMat = material(gui.getString("filler"), Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillerMat).name(" ").hideAttributes().build();
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }

        List<String> candidates = vote.getCandidates();
        int[] positions = pickPositions(candidates.size());
        String voted = vote.getVote(viewer.getUniqueId());

        for (int i = 0; i < candidates.size() && i < positions.length; i++) {
            String arenaId = candidates.get(i);
            Arena arena = plugin.getArenaManager().getArena(arenaId);
            if (arena == null) {
                continue;
            }

            Material mat = material(gui.getString("map-material"), Material.MAP);
            String nameTemplate = gui.getString("map-name", "&d&l{map}");
            String name = MessageUtil.apply(nameTemplate, Map.of(
                    "map", arena.getDisplayName(),
                    "id", arena.getId()
            ));

            int votes = vote.getVotes(arenaId);
            String percent = MapVoteManager.formatPercent(vote.getPercent(arenaId));
            List<String> lore = new ArrayList<>();
            for (String line : gui.getStringList("map-lore")) {
                lore.add(MessageUtil.apply(line, Map.of(
                        "map", MessageUtil.strip(arena.getDisplayName()),
                        "percent", percent,
                        "votes", String.valueOf(votes),
                        "total", String.valueOf(vote.getTotalVotes()),
                        "time", EventManager.formatDurationMillis(vote.getMillisUntilVoteEnds())
                )));
            }
            if (voted != null && voted.equalsIgnoreCase(arenaId)) {
                lore.add(gui.getString("voted-lore", "&a✔ Your vote"));
            }

            inventory.setItem(positions[i], new ItemBuilder(mat).name(name).lore(lore).hideAttributes().build());
            slotActions.put(positions[i], arenaId);
        }
    }

    public void handleClick(Player player, int slot) {
        String arenaId = slotActions.get(slot);
        if (arenaId == null) {
            return;
        }
        plugin.getMapVoteManager().castVote(player, arenaId);
    }

    public static void open(UnstableCore plugin, Player player) {
        player.openInventory(new VoteGui(plugin, player).getInventory());
    }

    private static int[] pickPositions(int count) {
        return switch (count) {
            case 1 -> new int[]{13};
            case 2 -> new int[]{12, 14};
            case 3 -> new int[]{11, 13, 15};
            default -> new int[]{10, 12, 14, 16};
        };
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
