package com.jovanstar.unstablecore.util;

public final class SmallCaps {

    private static final char[] MAP = new char[128];

    static {
        for (int i = 0; i < MAP.length; i++) {
            MAP[i] = (char) i;
        }

        put('a', 'ᴀ');
        put('b', 'ʙ');
        put('c', 'ᴄ');
        put('d', 'ᴅ');
        put('e', 'ᴇ');
        put('f', 'ғ');
        put('g', 'ɢ');
        put('h', 'ʜ');
        put('i', 'ɪ');
        put('j', 'ᴊ');
        put('k', 'ᴋ');
        put('l', 'ʟ');
        put('m', 'ᴍ');
        put('n', 'ɴ');
        put('o', 'ᴏ');
        put('p', 'ᴘ');
        put('q', 'ǫ');
        put('r', 'ʀ');
        put('s', 'ѕ');
        put('t', 'ᴛ');
        put('u', 'ᴜ');
        put('v', 'ᴠ');
        put('w', 'ᴡ');
        put('x', 'x');
        put('y', 'ʏ');
        put('z', 'ᴢ');
    }

    private SmallCaps() {
    }

    private static void put(char from, char to) {
        MAP[from] = to;
        MAP[Character.toUpperCase(from)] = to;
    }

    public static String of(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < MAP.length && ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                sb.append(MAP[c]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String colored(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 7 < input.length() && isHex6(input, i + 2)) {
                    out.append(input, i, i + 8);
                    i += 7;
                    continue;
                }
                out.append(c).append(next);
                i++;
                continue;
            }
            if (c == '<' && i + 1 < input.length() && input.charAt(i + 1) == '#') {
                int end = input.indexOf('>', i);
                if (end > i) {
                    out.append(input, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (c == '<') {
                int end = input.indexOf('>', i);
                if (end > i) {
                    out.append(input, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (c < MAP.length && ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                out.append(MAP[c]);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex6(String input, int start) {
        if (start + 6 > input.length()) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            char ch = input.charAt(start + i);
            boolean hex = (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
