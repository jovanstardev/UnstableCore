package com.jovanstar.unstablecore.placeholder;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.EconomyManager;
import com.jovanstar.unstablecore.manager.EventManager;
import com.jovanstar.unstablecore.manager.KitRankManager;
import com.jovanstar.unstablecore.util.MessageUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class UnstablePlaceholders extends PlaceholderExpansion {

    private final UnstableCore plugin;

    public UnstablePlaceholders(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "uc";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JovanStar";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        String key = params.toLowerCase();
        return switch (key) {
            case "coins", "balance", "money" -> {
                if (player == null) {
                    yield "0";
                }
                yield EconomyManager.format(plugin.getEconomyManager().getBalance(player));
            }
            case "multiplier", "coin_multiplier" -> {
                double m = plugin.getEventManager().getMultiplier();
                yield m == (long) m ? String.valueOf((long) m) : String.format("%.1f", m);
            }
            case "streak_multiplier" -> {
                double m = plugin.getEventManager().getStreakMultiplier();
                yield m == (long) m ? String.valueOf((long) m) : String.format("%.1f", m);
            }
            case "event_active" -> plugin.getEventManager().isCoinActive() ? "true" : "false";
            case "streak_event_active" -> plugin.getEventManager().isStreakActive() ? "true" : "false";
            case "next_event", "next_3x", "next_multiplier" -> plugin.getEventManager().formatTimeUntilNext();
            case "event_time_left" -> EventManager.formatDurationMillis(plugin.getEventManager().getMillisUntilEventEnds());
            case "streak_event_time_left" -> EventManager.formatDurationMillis(
                    plugin.getEventManager().getMillisUntilStreakEnds());
            case "killstreak", "ks" -> player == null ? "0"
                    : String.valueOf(plugin.getKillstreakManager().getStreak(player.getUniqueId()));
            case "best_killstreak", "best_ks" -> player == null ? "0"
                    : String.valueOf(plugin.getStatsManager().getBestStreak(player.getUniqueId()));
            case "deaths", "death" -> player == null ? "0"
                    : String.valueOf(plugin.getKillstreakManager().getDeaths(player.getUniqueId()));
            case "tag", "suffix" -> {
                if (player == null) {
                    yield "";
                }
                String equipped = plugin.getTagManager().getEquipped(player.getUniqueId());
                if (equipped == null || equipped.isBlank()) {
                    yield "";
                }
                yield MessageUtil.toLegacy(equipped);
            }
            case "tag_raw", "suffix_raw" -> player == null ? ""
                    : plugin.getTagManager().getEquipped(player.getUniqueId());
            case "tag_plain", "suffix_plain" -> {
                if (player == null) {
                    yield "";
                }
                String equipped = plugin.getTagManager().getEquipped(player.getUniqueId());
                if (equipped == null || equipped.isBlank()) {
                    yield "";
                }
                yield MessageUtil.strip(equipped);
            }
            case "playtime_hours" -> player == null ? "0"
                    : String.format("%.2f", plugin.getPlaytimeManager().getPlaytimeHours(player));
            case "is_newbie" -> {
                if (player == null) {
                    yield "false";
                }
                double hours = plugin.getConfig().getDouble("newbie-hours", 6);
                yield String.valueOf(plugin.getPlaytimeManager().isNewbie(player, hours));
            }
            case "arena", "active_arena" -> {
                var arena = plugin.getArenaManager().getActiveArena();
                yield arena == null ? "None" : MessageUtil.strip(arena.getDisplayName());
            }
            case "active_arena_id", "arena_id" -> {
                String id = plugin.getArenaManager().getActiveArenaId();
                yield id == null || id.isBlank() ? "none" : id;
            }
            case "arena_reset", "next_rotation" -> EventManager.formatDurationDhms(
                    plugin.getArenaManager().getMillisUntilRotation());
            case "bounty", "bounty_amount" -> {
                if (player == null || plugin.getBountyManager() == null) {
                    yield "0";
                }
                yield EconomyManager.format(plugin.getBountyManager().amountOf(player.getUniqueId()));
            }
            case "bounty_id" -> {
                if (player == null || plugin.getBountyManager() == null) {
                    yield "";
                }
                var b = plugin.getBountyManager().get(player.getUniqueId());
                yield b == null ? "" : String.valueOf(b.bountyId());
            }
            case "bounty_count" -> plugin.getBountyManager() == null ? "0"
                    : String.valueOf(plugin.getBountyManager().count());
            case "kit", "kit_name" -> {
                if (player == null || plugin.getKitManager() == null) {
                    yield "";
                }
                var kit = plugin.getKitManager().getSelectedKit(player);
                yield kit == null ? "" : kit.getDisplayName();
            }
            case "kit_id" -> {
                if (player == null || plugin.getKitManager() == null) {
                    yield "";
                }
                var kit = plugin.getKitManager().getSelectedKit(player);
                yield kit == null ? "" : kit.getId();
            }
            case "rank_kit" -> {
                if (player == null || plugin.getKitManager() == null) {
                    yield "";
                }
                var kit = plugin.getKitManager().getSelectedKit(player);
                if (kit == null) {
                    yield "";
                }
                yield MessageUtil.toLegacy(KitRankManager.formatRankFor(plugin, player, kit));
            }
            case "kit_weight", "rank_kit_weight", "tab_kit_weight" -> {
                if (player == null || plugin.getKitManager() == null) {
                    yield "0";
                }
                var kit = plugin.getKitManager().getSelectedKit(player);
                if (kit == null) {
                    yield "0";
                }
                yield KitRankManager.tabWeightPadded(plugin, player, kit);
            }
            default -> null;
        };
    }
}
