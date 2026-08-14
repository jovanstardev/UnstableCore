package com.jovanstar.unstablecore.util;

import com.jovanstar.unstablecore.UnstableCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    private static final Pattern HEX_ANGLE = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern HEX_AMP = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static UnstableCore plugin;

    private MessageUtil() {
    }

    public static void init(UnstableCore plugin) {
        MessageUtil.plugin = plugin;
    }

    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        String text = normalize(input);

        if (looksLikeMiniMessage(text)) {
            try {

                text = legacyToMini(text);
                return MINI.deserialize(text);
            } catch (Exception ignored) {

            }
        }
        return LEGACY.deserialize(text);
    }

    public static List<Component> parseList(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(parse(line));
        }
        return out;
    }

    public static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return result;
    }

    public static void send(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        sender.sendMessage(parse(message));
    }

    public static void send(CommandSender sender, String message, Map<String, String> placeholders) {
        send(sender, apply(message, placeholders));
    }

    public static void sendPrefixed(CommandSender sender, String message) {
        String prefix = plugin != null ? plugin.getConfig().getString("prefix", "") : "";
        send(sender, prefix + message);
    }

    public static void sendConfig(CommandSender sender, String path, Map<String, String> placeholders) {
        String msg = plugin.getConfig().getString("messages." + path, "");
        send(sender, apply(msg, placeholders));
    }

    public static void broadcast(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.contains("\n")) {
            for (String line : normalized.split("\n", -1)) {
                sendBroadcastLine(line);
            }
            return;
        }
        sendBroadcastLine(normalized);
    }

    public static void broadcast(String message, Map<String, String> placeholders) {
        broadcast(apply(message, placeholders));
    }

    public static void broadcastLines(List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            sendBroadcastLine(apply(line, placeholders));
        }
    }

    private static void sendBroadcastLine(String line) {
        if (line == null) {
            return;
        }
        Component component = parse(line.isEmpty() ? " " : line);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(component);
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public static void broadcastFiltered(String message, java.util.function.Predicate<Player> filter) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Component component = parse(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (filter == null || filter.test(player)) {
                player.sendMessage(component);
            }
        }
        Bukkit.getConsoleSender().sendMessage(component);
    }

    public static void broadcastFiltered(String message, Map<String, String> placeholders,
                                         java.util.function.Predicate<Player> filter) {
        broadcastFiltered(apply(message, placeholders), filter);
    }

    public static void actionBar(Player player, String message) {
        player.sendActionBar(parse(message));
    }

    public static void title(Player player, String title, String subtitle, int seconds) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(100),
                Duration.ofSeconds(Math.max(1, seconds)),
                Duration.ofMillis(200)
        );
        player.showTitle(Title.title(parse(title), parse(subtitle), times));
    }

    public static String strip(String input) {
        return LegacyComponentSerializer.legacySection().serialize(parse(input))
                .replaceAll("§[0-9a-fk-or]", "");
    }

    public static String toLegacy(String input) {
        return LEGACY.serialize(parse(input));
    }

    private static String normalize(String input) {
        String text = input.replace('§', '&');

        Matcher m = HEX_ANGLE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "&#" + m.group(1));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeMiniMessage(String text) {
        return text.contains("<gradient")
                || text.contains("<b>")
                || text.contains("<bold>")
                || text.contains("<rainbow")
                || text.contains("<hover")
                || text.contains("<click")
                || text.contains("<reset")
                || text.contains("</");
    }

    private static String legacyToMini(String text) {

        Matcher m = HEX_AMP.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "<#" + m.group(1) + ">");
        }
        m.appendTail(sb);
        String result = sb.toString();

        result = result
                .replace("&l", "<bold>")
                .replace("&o", "<italic>")
                .replace("&n", "<underlined>")
                .replace("&m", "<strikethrough>")
                .replace("&k", "<obfuscated>")
                .replace("&r", "<reset>")
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>");
        return result;
    }
}
