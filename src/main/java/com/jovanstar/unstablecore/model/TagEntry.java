package com.jovanstar.unstablecore.model;

import org.bukkit.Material;

public final class TagEntry {

    private final int slot;
    private final Material material;
    private final String name;
    private final String suffix;

    public TagEntry(int slot, Material material, String name, String suffix) {
        this.slot = slot;
        this.material = material;
        this.name = name;
        this.suffix = suffix;
    }

    public int getSlot() {
        return slot;
    }

    public Material getMaterial() {
        return material;
    }

    public String getName() {
        return name;
    }

    public String getSuffix() {
        return suffix;
    }
}
