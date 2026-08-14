package com.jovanstar.unstablecore.manager;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.model.Kit;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class KitRankManager {

    private KitRankManager() {
    }

    public static String formatRank(Kit kit) {
        if (kit == null) {
            return "";
        }
        String color = kit.getNameColor() == null || kit.getNameColor().isBlank() ? "&f" : kit.getNameColor();
        String name = kit.getDisplayName() == null || kit.getDisplayName().isBlank() ? kit.getId() : kit.getDisplayName();
        return (color + "&l" + name + " ").replace('§', '&');
    }

    public static String formatRankFor(UnstableCore plugin, Player player, Kit kit) {
        if (kit == null || hidesKitRank(plugin, player)) {
            return "";
        }
        if (usesMemberPrefix(plugin, kit)) {
            String prefix = plugin.getConfig().getString("kit-rank.member-prefix", "&f&lMEMBER ");
            if (prefix == null || prefix.isBlank()) {
                prefix = "&f&lMEMBER ";
            }
            if (!prefix.endsWith(" ")) {
                prefix = prefix + " ";
            }
            return prefix.replace('§', '&');
        }
        return formatRank(kit);
    }

    private static boolean usesMemberPrefix(UnstableCore plugin, Kit kit) {
        if (kit == null) {
            return false;
        }
        List<String> ids = plugin.getConfig().getStringList("kit-rank.member-for-kits");
        if (ids != null) {
            for (String raw : ids) {
                if (raw != null && kit.getId().equalsIgnoreCase(raw.trim())) {
                    return true;
                }
            }
        }
        return kit.getTier() != null && "starter".equalsIgnoreCase(kit.getTier());
    }

    public static boolean hidesKitRank(UnstableCore plugin, Player player) {
        if (plugin == null || player == null) {
            return false;
        }
        List<String> groups = plugin.getConfig().getStringList("kit-rank.hide-for-groups");
        if (groups == null || groups.isEmpty()) {
            return false;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return false;
        }
        try {
            User user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
            if (user == null) {
                return false;
            }
            for (String raw : groups) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String want = raw.trim().toLowerCase(Locale.ROOT);
                boolean match = user.getInheritedGroups(user.getQueryOptions()).stream()
                        .anyMatch(g -> g.getName().equalsIgnoreCase(want));
                if (!match) {
                    match = user.getNodes(NodeType.INHERITANCE).stream()
                            .anyMatch(n -> n.getGroupName().equalsIgnoreCase(want));
                }
                if (match) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    public static int tabWeight(UnstableCore plugin, Player player, Kit kit) {
        if (plugin == null || kit == null || hidesKitRank(plugin, player)) {
            return 0;
        }
        int max = Math.max(2, Math.min(99, plugin.getConfig().getInt("kit-rank.tab-weight-max", 49)));
        if (usesMemberPrefix(plugin, kit) || plugin.getKitManager().isStarter(kit)) {
            return 1;
        }
        if (plugin.getKitManager() == null) {
            return 2;
        }
        List<Kit> paid = new ArrayList<>();
        for (Kit other : plugin.getKitManager().getKits().values()) {
            if (other == null || plugin.getKitManager().isStarter(other) || usesMemberPrefix(plugin, other)) {
                continue;
            }
            if (other.getPrice() <= 0) {
                continue;
            }
            paid.add(other);
        }
        if (paid.isEmpty()) {
            return 2;
        }
        paid.sort(Comparator
                .comparingDouble(Kit::getPrice)
                .thenComparing(Kit::getId, String.CASE_INSENSITIVE_ORDER));
        int index = -1;
        for (int i = 0; i < paid.size(); i++) {
            if (paid.get(i).getId().equalsIgnoreCase(kit.getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return 2;
        }
        if (paid.size() == 1) {
            return max;
        }
        int weight = 2 + (int) Math.round((index / (double) (paid.size() - 1)) * (max - 2));
        return Math.max(2, Math.min(max, weight));
    }

    public static String tabWeightPadded(UnstableCore plugin, Player player, Kit kit) {
        int weight = tabWeight(plugin, player, kit);
        int max = Math.max(1, Math.min(99, plugin.getConfig().getInt("kit-rank.tab-weight-max", 49)));
        int digits = String.valueOf(max).length();
        return String.format("%0" + digits + "d", weight);
    }
}
