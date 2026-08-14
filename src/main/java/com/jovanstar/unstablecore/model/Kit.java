package com.jovanstar.unstablecore.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

public final class Kit {

    public static final int CONTENTS_SIZE = 54;

    private final String id;
    private String displayName;
    private Material icon;
    private int slot;
    private String permission;
    private String tier;
    private double price;
    private String nameColor;
    private ItemStack[] contents;

    public Kit(String id, String displayName, Material icon, int slot, String permission, ItemStack[] contents) {
        this(id, displayName, icon, slot, permission, "Epic", 0, "&d", contents);
    }

    public Kit(String id, String displayName, Material icon, int slot, String permission,
               String tier, double price, String nameColor, ItemStack[] contents) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.slot = slot;
        this.permission = permission;
        this.tier = tier == null || tier.isBlank() ? "Epic" : tier;
        this.price = Math.max(0, price);
        this.nameColor = nameColor == null || nameColor.isBlank() ? "&d" : nameColor;
        this.contents = normalize(contents);
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Material getIcon() {
        return icon;
    }

    public void setIcon(Material icon) {
        this.icon = icon;
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier == null || tier.isBlank() ? "Epic" : tier;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = Math.max(0, price);
    }

    public String getNameColor() {
        return nameColor;
    }

    public void setNameColor(String nameColor) {
        this.nameColor = nameColor == null || nameColor.isBlank() ? "&d" : nameColor;
    }

    public ItemStack[] getContents() {
        return contents;
    }

    public void setContents(ItemStack[] contents) {
        this.contents = normalize(contents);
    }

    public ItemStack[] copyContents() {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static ItemStack[] normalize(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[CONTENTS_SIZE];
        if (contents == null) {
            return out;
        }
        for (int i = 0; i < Math.min(contents.length, out.length); i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }
        return out;
    }

    public boolean isEmpty() {
        return Arrays.stream(contents).allMatch(i -> i == null || i.getType().isAir());
    }
}
