package com.jovanstar.unstablecore.gui;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.BountyManager;
import com.jovanstar.unstablecore.manager.BountyManager.Bounty;
import com.jovanstar.unstablecore.manager.BountyManager.Prompt;
import com.jovanstar.unstablecore.manager.BountyManager.PromptType;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.util.ItemBuilder;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlaceBountyGui implements InventoryHolder {

    private static final int[] CONTENT = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final UnstableCore plugin;
    private final Player viewer;
    private final int page;
    private final String filter;
    private final Inventory inventory;
    private final Map<Integer, String> actions = new HashMap<>();
    private final Map<Integer, UUID> slotTargets = new HashMap<>();

    public PlaceBountyGui(UnstableCore plugin, Player viewer, int page, String filter) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.filter = filter;
        FileConfiguration cfg = plugin.getConfigManager().getBounty();
        int size = Math.max(9, Math.min(54, cfg.getInt("gui.size", 54)));
        if (size % 9 != 0) {
            size = 54;
        }
        List<Player> targets = onlineTargets(filter);
        int perPage = Math.min(CONTENT.length, Math.max(1, cfg.getInt("gui.slots-per-page", CONTENT.length)));
        int pages = Math.max(1, (int) Math.ceil(targets.size() / (double) perPage));
        this.page = Math.max(0, Math.min(page, pages - 1));
        this.inventory = Bukkit.createInventory(this, size,
                MessageUtil.parse(cfg.getString("gui.place-title", "&aPlace Bounty")));
        fill(targets, perPage, pages);
    }

    public static void open(UnstableCore plugin, Player player, int page, String filter) {
        player.openInventory(new PlaceBountyGui(plugin, player, page, filter).getInventory());
    }

    private FileConfiguration cfg() {
        return plugin.getConfigManager().getBounty();
    }

    private List<Player> onlineTargets(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(viewer.getUniqueId())
                    && !cfg().getBoolean("allow-self", false)) {
                continue;
            }
            if (!q.isEmpty() && !p.getName().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            list.add(p);
        }
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    private void fill(List<Player> targets, int perPage, int pages) {
        actions.clear();
        slotTargets.clear();
        FileConfiguration cfg = cfg();
        Material border = mat(cfg.getString("gui.border"), Material.YELLOW_STAINED_GLASS_PANE);
        ItemStack borderItem = pane(border);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, borderItem);
        }

        int start = page * perPage;
        int end = Math.min(targets.size(), start + perPage);
        for (int i = start; i < end; i++) {
            int slot = CONTENT[i - start];
            Player target = targets.get(i);
            inventory.setItem(slot, targetHead(target));
            actions.put(slot, "pick");
            slotTargets.put(slot, target.getUniqueId());
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(page + 1));
        ph.put("pages", String.valueOf(pages));
        ph.put("count", String.valueOf(targets.size()));

        int infoSlot = cfg.getInt("place.info-slot", 4);
        inventory.setItem(infoSlot, named(Material.PAPER,
                cfg.getString("place.info-name", "&aSelect a target"),
                applyList(cfg.getStringList("place.info-lore"), ph), ph));

        int navSlot = cfg.getInt("place.back-slot", 45);
        if (page > 0) {
            Map<String, String> prevPh = new HashMap<>(ph);
            prevPh.put("page", String.valueOf(page));
            inventory.setItem(navSlot, named(Material.ARROW,
                    cfg.getString("place.prev-name", "&cPrevious Page"),
                    applyList(cfg.getStringList("place.prev-lore"), prevPh), prevPh));
            actions.put(navSlot, "prev");
        } else {
            inventory.setItem(navSlot, named(Material.ARROW,
                    cfg.getString("place.back-name", "&cGo Back"),
                    applyList(cfg.getStringList("place.back-lore"), ph), ph));
            actions.put(navSlot, "back");
        }

        int searchSlot = cfg.getInt("place.search-slot", 49);
        inventory.setItem(searchSlot, named(Material.ENDER_EYE,
                cfg.getString("place.search-name", "&bSearch Player"),
                applyList(cfg.getStringList("place.search-lore"), ph), ph));
        actions.put(searchSlot, "search");

        int nextSlot = cfg.getInt("place.next-slot", 53);
        if (page + 1 < pages) {
            Map<String, String> nextPh = new HashMap<>(ph);
            nextPh.put("page", String.valueOf(page + 2));
            inventory.setItem(nextSlot, named(Material.ARROW,
                    cfg.getString("place.next-name", "&aNext Page"),
                    applyList(cfg.getStringList("place.next-lore"), nextPh), nextPh));
            actions.put(nextSlot, "next");
        }
    }

    private ItemStack targetHead(Player target) {
        FileConfiguration cfg = cfg();
        ItemStack head = new ItemBuilder(Material.PLAYER_HEAD).build();
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            head.setItemMeta(meta);
        }
        Bounty current = plugin.getBountyManager().get(target.getUniqueId());
        Map<String, String> ph = new HashMap<>();
        ph.put("target", target.getName());
        ItemBuilder builder = new ItemBuilder(head)
                .name(MessageUtil.apply(cfg.getString("place.target-name", "&b{target}"), ph));
        if (current == null) {
            builder.lore(applyList(cfg.getStringList("place.target-none-lore"), ph));
        } else {
            ph.put("amount", EconomyManager.format(current.amount()));
            ph.put("id", String.valueOf(current.bountyId()));
            builder.lore(applyList(cfg.getStringList("place.target-active-lore"), ph));
        }
        return builder.hideAttributes().build();
    }

    public void handleClick(Player player, int slot) {
        String action = actions.get(slot);
        if (action == null) {
            return;
        }
        BountyManager mgr = plugin.getBountyManager();
        switch (action) {
            case "back" -> mgr.openBoard(player);
            case "prev" -> PlaceBountyGui.open(plugin, player, page - 1, filter);
            case "next" -> PlaceBountyGui.open(plugin, player, page + 1, filter);
            case "search" -> {
                player.closeInventory();
                mgr.beginPrompt(player, new Prompt(PromptType.PLACE_SEARCH, null, null));
                mgr.msg(player, "search-prompt", Map.of());
            }
            case "pick" -> {
                UUID targetId = slotTargets.get(slot);
                if (targetId == null) {
                    return;
                }
                Player target = Bukkit.getPlayer(targetId);
                if (target == null) {
                    mgr.msg(player, "offline", Map.of());
                    PlaceBountyGui.open(plugin, player, page, filter);
                    return;
                }
                player.closeInventory();
                mgr.promptAmount(player, target);
            }
            default -> {
            }
        }
    }

    private static List<String> applyList(List<String> lines, Map<String, String> ph) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(MessageUtil.apply(line, ph));
        }
        return out;
    }

    private static ItemStack named(Material material, String name, List<String> lore, Map<String, String> ph) {
        return new ItemBuilder(material)
                .name(MessageUtil.apply(name, ph))
                .lore(lore)
                .hideAttributes()
                .build();
    }

    private static ItemStack pane(Material material) {
        return new ItemBuilder(material).name(" ").hideAttributes().build();
    }

    private static Material mat(String name, Material def) {
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
