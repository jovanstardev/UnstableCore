package com.jovanstar.unstablecore.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

public final class TagCategory {

    private final String id;
    private final String permission;
    private final int slot;
    private final Material material;
    private final String name;
    private final List<String> lore;
    private final List<TagEntry> tags = new ArrayList<>();

    public TagCategory(String id, String permission, int slot, Material material, String name, List<String> lore) {
        this.id = id;
        this.permission = permission;
        this.slot = slot;
        this.material = material;
        this.name = name;
        this.lore = lore == null ? List.of() : lore;
    }

    public String getId() {
        return id;
    }

    public String getPermission() {
        return permission;
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

    public List<String> getLore() {
        return lore;
    }

    public List<TagEntry> getTags() {
        return tags;
    }
}
