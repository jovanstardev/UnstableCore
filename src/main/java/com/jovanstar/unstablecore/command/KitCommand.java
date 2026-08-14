package com.jovanstar.unstablecore.command;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.gui.KitAdminEditGui;
import com.jovanstar.unstablecore.gui.KitPreviewGui;
import com.jovanstar.unstablecore.gui.KitsGui;
import com.jovanstar.unstablecore.manager.KitManager;
import com.jovanstar.unstablecore.model.Kit;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class KitCommand implements CommandExecutor, TabCompleter {

    private final UnstableCore plugin;

    public KitCommand(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.admin")) {
            if (sender instanceof Player player) {
                KitsGui.open(plugin, player);
                return true;
            }
            MessageUtil.sendConfig(sender, "no-permission", Map.of());
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        KitManager kits = plugin.getKitManager();
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendConfig(sender, "player-only", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit create <id> [slot]");
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                int slot = args.length >= 3 ? parseInt(args[2], 10) : nextFreeSlot(kits);
                ItemStack hand = player.getInventory().getItemInMainHand();
                Material icon = hand == null || hand.getType().isAir() ? Material.CHEST : hand.getType();
                ItemStack[] contents = KitManager.snapshotStorage(player);
                if (!kits.createKit(id, id.toUpperCase(Locale.ROOT), icon, slot, contents)) {
                    MessageUtil.sendConfig(sender, "kit-exists", Map.of("kit", id));
                    return true;
                }
                MessageUtil.sendConfig(sender, "kit-created", Map.of("kit", id.toUpperCase(Locale.ROOT)));
            }
            case "delete" -> {
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit delete <id>");
                    return true;
                }
                if (!kits.deleteKit(args[1])) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[1]));
                    return true;
                }
                MessageUtil.sendConfig(sender, "kit-deleted", Map.of("kit", args[1].toUpperCase(Locale.ROOT)));
            }
            case "setcontents", "edit" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendConfig(sender, "player-only", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit edit <id>");
                    return true;
                }
                KitAdminEditGui.open(plugin, player, args[1]);
            }
            case "setfrominv" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendConfig(sender, "player-only", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit setfrominv <id>");
                    return true;
                }
                Kit kit = kits.getKit(args[1]);
                if (kit == null) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[1]));
                    return true;
                }
                kit.setContents(KitManager.snapshotStorage(player));
                kits.updateKit(kit);
                MessageUtil.sendConfig(sender, "kit-contents-saved", Map.of("kit", kit.getDisplayName()));
            }
            case "seticon" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendConfig(sender, "player-only", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit seticon <id>");
                    return true;
                }
                Kit kit = kits.getKit(args[1]);
                if (kit == null) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[1]));
                    return true;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) {
                    MessageUtil.send(sender, "&cHold an item to use as the icon.");
                    return true;
                }
                kit.setIcon(hand.getType());
                kits.updateKit(kit);
                MessageUtil.sendConfig(sender, "kit-icon-set", Map.of("kit", kit.getDisplayName()));
            }
            case "setslot" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /kit setslot <id> <slot>");
                    return true;
                }
                Kit kit = kits.getKit(args[1]);
                if (kit == null) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[1]));
                    return true;
                }
                int slot = parseInt(args[2], -1);
                if (slot < 0 || slot > 53) {
                    MessageUtil.send(sender, "&cSlot must be 0-53.");
                    return true;
                }
                kit.setSlot(slot);
                kits.updateKit(kit);
                MessageUtil.sendConfig(sender, "kit-slot-set", Map.of(
                        "kit", kit.getDisplayName(),
                        "slot", String.valueOf(slot)
                ));
            }
            case "setname" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /kit setname <id> <name>");
                    return true;
                }
                Kit kit = kits.getKit(args[1]);
                if (kit == null) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[1]));
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                kit.setDisplayName(name);
                kits.updateKit(kit);
                MessageUtil.sendConfig(sender, "kit-name-set", Map.of("kit", name));
            }
            case "preview" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.sendConfig(sender, "player-only", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    MessageUtil.send(sender, "&cUsage: /kit preview <id>");
                    return true;
                }
                KitPreviewGui.open(plugin, player, args[1]);
            }
            case "unlock" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /kit unlock <player> <id>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                    return true;
                }
                if (kits.getKit(args[2]) == null) {
                    MessageUtil.sendConfig(sender, "kit-not-found", Map.of("kit", args[2]));
                    return true;
                }
                kits.unlock(target.getUniqueId(), args[2]);
                MessageUtil.sendConfig(sender, "kit-unlocked", Map.of(
                        "player", target.getName(),
                        "kit", args[2].toUpperCase(Locale.ROOT)
                ));
            }
            case "lock" -> {
                if (args.length < 3) {
                    MessageUtil.send(sender, "&cUsage: /kit lock <player> <id>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    MessageUtil.sendConfig(sender, "player-not-found", Map.of());
                    return true;
                }
                kits.lock(target.getUniqueId(), args[2]);
                MessageUtil.sendConfig(sender, "kit-locked-admin", Map.of(
                        "player", target.getName(),
                        "kit", args[2].toUpperCase(Locale.ROOT)
                ));
            }
            case "list" -> {
                if (kits.getKits().isEmpty()) {
                    MessageUtil.send(sender, "&7No kits configured.");
                    return true;
                }
                for (Kit kit : kits.getKitsBySlot()) {
                    MessageUtil.send(sender, "&8• &f" + kit.getId() + " &7slot=&f" + kit.getSlot()
                            + " &7icon=&f" + kit.getIcon().name());
                }
            }
            case "reload" -> {
                kits.load();
                MessageUtil.sendConfig(sender, "kit-reloaded", Map.of());
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private static int nextFreeSlot(KitManager kits) {
        for (int s = 10; s <= 33; s++) {
            boolean used = false;
            for (Kit kit : kits.getKits().values()) {
                if (kit.getSlot() == s) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return s;
            }
        }
        return 10;
    }

    private static int parseInt(String raw, int def) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, "&d✦ &fKit Admin");
        MessageUtil.send(sender, "&e/kit create <id> [slot] &7- create from inventory + hand icon");
        MessageUtil.send(sender, "&e/kit delete <id>");
        MessageUtil.send(sender, "&e/kit edit <id> &7- edit default contents GUI");
        MessageUtil.send(sender, "&e/kit setfrominv <id> &7- overwrite contents from inventory");
        MessageUtil.send(sender, "&e/kit seticon|setslot|setname <id> ...");
        MessageUtil.send(sender, "&e/kit preview <id> &7| &eunlock|lock <player> <id>");
        MessageUtil.send(sender, "&e/kit list &7| &ereload");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("unstablecore.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of(
                    "create", "delete", "edit", "setcontents", "setfrominv",
                    "seticon", "setslot", "setname", "preview",
                    "unlock", "lock", "list", "reload"
            ), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("unlock") || sub.equals("lock")) {
                return null;
            }
            if (List.of("delete", "edit", "setcontents", "setfrominv", "seticon", "setslot",
                    "setname", "preview").contains(sub)) {
                return filter(new ArrayList<>(plugin.getKitManager().getKits().keySet()), args[1]);
            }
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("unlock") || args[0].equalsIgnoreCase("lock"))) {
            return filter(new ArrayList<>(plugin.getKitManager().getKits().keySet()), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
