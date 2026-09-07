package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Item rules the server enforces everywhere an item can be, not just where it is used.
 *
 * <p><b>Enchantment caps</b> ({@code item-restrictions.enchant-limits}): any enchantment over its
 * cap is lowered to the cap - or removed when the cap is 0 - the moment it is seen: on join, in
 * the player's inventory and ender chest, in any container they open, on the item they pick up,
 * switch to, click, or hit someone with, inside shulker boxes and bundles, and on enchanted
 * books. A slow sweep over everyone online catches whatever the events missed. Density V is the
 * reason this exists: it is only reachable by anvil-combining and turns a mace into a one-shot.
 *
 * <p><b>Anvils</b> ({@code item-restrictions.anvils.disabled}): using one is refused, both at the
 * block and at the inventory, unless the player holds the bypass permission.
 */
public final class ItemRestrictionListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 3000L;

    private final UnstableCore plugin;
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    private Map<Enchantment, Integer> limits = new LinkedHashMap<>();

    public ItemRestrictionListener(UnstableCore plugin) {
        this.plugin = plugin;
        reload();
        long sweep = 20L * Math.max(2, plugin.getConfig().getInt("item-restrictions.sweep-seconds", 10));
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, sweep, sweep);
    }

    /** Re-reads the caps from the config. Unknown enchantment names are logged and skipped. */
    public void reload() {
        Map<Enchantment, Integer> parsed = new LinkedHashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("item-restrictions.enchant-limits");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                String key = name.toLowerCase(Locale.ROOT).trim();
                NamespacedKey nk = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
                Enchantment enchantment = nk == null ? null : Registry.ENCHANTMENT.get(nk);
                if (enchantment == null) {
                    plugin.getLogger().warning("item-restrictions: unknown enchantment '" + name + "' ignored");
                    continue;
                }
                parsed.put(enchantment, Math.max(0, section.getInt(name)));
            }
        }
        this.limits = parsed;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("item-restrictions.enabled", true) && !limits.isEmpty();
    }

    private boolean anvilsDisabled() {
        return plugin.getConfig().getBoolean("item-restrictions.enabled", true)
                && plugin.getConfig().getBoolean("item-restrictions.anvils.disabled", true);
    }

    private boolean bypassesAnvil(Player player) {
        String node = plugin.getConfig().getString("item-restrictions.anvils.bypass-permission", "unstablecore.anvil.bypass");
        return node != null && !node.isBlank() && player.hasPermission(node);
    }

    // --- the fix itself ----------------------------------------------------------------------

    /**
     * Lowers every capped enchantment on the stack (and on anything stored inside it) to its
     * cap. Returns true when something changed.
     */
    public boolean fix(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> limit : limits.entrySet()) {
            int level = item.getEnchantmentLevel(limit.getKey());
            if (level > limit.getValue()) {
                item.removeEnchantment(limit.getKey());
                if (limit.getValue() > 0) {
                    item.addUnsafeEnchantment(limit.getKey(), limit.getValue());
                }
                changed = true;
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta book) {
            boolean bookChanged = false;
            for (Map.Entry<Enchantment, Integer> limit : limits.entrySet()) {
                int level = book.getStoredEnchantLevel(limit.getKey());
                if (level > limit.getValue()) {
                    book.removeStoredEnchant(limit.getKey());
                    if (limit.getValue() > 0) {
                        book.addStoredEnchant(limit.getKey(), limit.getValue(), true);
                    }
                    bookChanged = true;
                }
            }
            if (bookChanged) {
                item.setItemMeta(book);
                changed = true;
            }
        }
        if (plugin.getConfig().getBoolean("item-restrictions.scan-containers", true)) {
            if (meta instanceof BlockStateMeta state && state.hasBlockState() && state.getBlockState() instanceof ShulkerBox box) {
                boolean inner = false;
                for (ItemStack stored : box.getInventory().getContents()) {
                    inner |= fix(stored);
                }
                if (inner) {
                    state.setBlockState(box);
                    item.setItemMeta(state);
                    changed = true;
                }
            } else if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                List<ItemStack> items = new ArrayList<>();
                boolean inner = false;
                for (ItemStack stored : bundle.getItems()) {
                    ItemStack copy = stored.clone();
                    inner |= fix(copy);
                    items.add(copy);
                }
                if (inner) {
                    bundle.setItems(items);
                    item.setItemMeta(bundle);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Fixes every stack in the inventory in place; returns how many changed. */
    public int fix(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int changed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (fix(contents[i])) {
                inventory.setItem(i, contents[i]);
                changed++;
            }
        }
        return changed;
    }

    /** Inventory, armour, off-hand, cursor and ender chest. */
    private void scan(Player player) {
        if (!enabled() || player == null || !player.isOnline()) {
            return;
        }
        int changed = fix(player.getInventory()) + fix(player.getEnderChest());
        ItemStack cursor = player.getItemOnCursor();
        if (fix(cursor)) {
            player.setItemOnCursor(cursor);
            changed++;
        }
        if (changed > 0) {
            notify(player);
        }
    }

    private void notify(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        String message = plugin.getConfig().getString("item-restrictions.message",
                "&cThat enchantment level is disabled here. Your item was adjusted.");
        if (message != null && !message.isBlank()) {
            MessageUtil.sendPrefixed(player, message);
        }
    }

    private void sweep() {
        if (!enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            scan(player);
        }
    }

    // --- where items show up ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> scan(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.ANVIL && anvilsDisabled() && !bypassesAnvil(player)) {
            event.setCancelled(true);
            refuseAnvil(player);
            return;
        }
        if (enabled() && fix(event.getInventory()) > 0) {
            notify(player);
        }
        Bukkit.getScheduler().runTask(plugin, () -> scan(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!enabled() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (fix(top) > 0) {
                notify(player);
            }
            scan(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        if (!enabled()) {
            return;
        }
        ItemStack item = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (fix(item)) {
            event.getPlayer().getInventory().setItem(event.getNewSlot(), item);
            notify(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!enabled()) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (fix(stack)) {
            event.getItem().setItemStack(stack);
            if (event.getEntity() instanceof Player player) {
                notify(player);
            }
        }
    }

    /** The hit that lands with an over-cap weapon is the last one: the weapon is fixed as it lands. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!enabled() || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (fix(hand)) {
            attacker.getInventory().setItemInMainHand(hand);
            notify(attacker);
        }
    }

    /** For servers that keep anvils on: the result can never exceed the cap. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!enabled()) {
            return;
        }
        ItemStack result = event.getResult();
        if (result != null && fix(result)) {
            event.setResult(result);
        }
    }

    // --- anvils -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !anvilsDisabled()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isAnvil(block.getType()) || bypassesAnvil(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        refuseAnvil(event.getPlayer());
    }

    private static boolean isAnvil(Material material) {
        return Tag.ANVIL.isTagged(material);
    }

    private void refuseAnvil(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        String message = plugin.getConfig().getString("item-restrictions.anvils.message", "&cAnvils are disabled on this server.");
        if (message != null && !message.isBlank()) {
            MessageUtil.sendPrefixed(player, message);
        }
    }
}
