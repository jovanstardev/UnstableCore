package com.jovanstar.unstablecore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ItemBuilder {

    private final ItemStack item;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material == null ? Material.STONE : material);
    }

    public ItemBuilder(ItemStack base) {
        this.item = base == null ? new ItemStack(Material.STONE) : base.clone();
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public ItemBuilder name(String name) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(MessageUtil.parse(name)));
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && lore != null) {
            List<Component> components = new ArrayList<>();
            for (String line : lore) {
                components.add(noItalic(MessageUtil.parse(line == null ? "" : line)));
            }
            meta.lore(components);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(String... lore) {
        return lore(List.of(lore));
    }

    public ItemBuilder enchantments(Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) {
            return this;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return this;
        }
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            Enchantment ench = resolveEnchantment(e.getKey());
            if (ench != null) {
                meta.addEnchant(ench, Math.max(1, e.getValue()), true);
            }
        }
        item.setItemMeta(meta);
        return this;
    }

    private static Enchantment resolveEnchantment(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Enchantment byKey = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        if (byKey != null) {
            return byKey;
        }

        String legacy = switch (key) {
            case "sharpness", "damage_all" -> "sharpness";
            case "sweeping_edge", "sweeping" -> "sweeping_edge";
            case "fire_aspect" -> "fire_aspect";
            case "knockback" -> "knockback";
            case "unbreaking", "durability" -> "unbreaking";
            case "mending" -> "mending";
            case "piercing" -> "piercing";
            case "multishot" -> "multishot";
            case "quick_charge" -> "quick_charge";
            case "efficiency", "dig_speed" -> "efficiency";
            default -> key;
        };
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(legacy));
    }

    public ItemBuilder potion(String potionTypeName, int amplifier, int durationSeconds) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return this;
        }
        try {
            PotionType type = PotionType.valueOf(potionTypeName.toUpperCase(Locale.ROOT));
            meta.setBasePotionType(type);
        } catch (IllegalArgumentException ignored) {
            PotionEffectType effect = Registry.EFFECT.get(NamespacedKey.minecraft(
                    potionTypeName.trim().toLowerCase(Locale.ROOT)));
            if (effect != null) {
                meta.addCustomEffect(new PotionEffect(effect, Math.max(1, durationSeconds) * 20, Math.max(0, amplifier)), true);
            }
        }
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder hideAttributes() {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemStack build() {
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
