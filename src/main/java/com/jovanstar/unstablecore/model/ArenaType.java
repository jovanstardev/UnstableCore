package com.jovanstar.unstablecore.model;

public enum ArenaType {
    MACE,
    NOMACE;

    public static ArenaType from(String input) {
        if (input == null || input.isBlank()) {
            return MACE;
        }
        String key = input.toLowerCase()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
                .trim();
        return switch (key) {
            case "nomace", "nomacearena", "nomaces", "pure", "sword" -> NOMACE;
            default -> MACE;
        };
    }
}
