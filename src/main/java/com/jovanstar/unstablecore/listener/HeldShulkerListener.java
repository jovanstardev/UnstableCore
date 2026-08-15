package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.util.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeldShulkerListener implements Listener {

    private final UnstableCore plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public HeldShulkerListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("held-shulker.enabled", true)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (player.isSneaking() && action == Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isShulker(item)) {
            return;
        }
        if (item.getAmount() != 1) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }

        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        EquipmentSlot hand = event.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> open(player, hand));
    }

    private void open(Player player, EquipmentSlot hand) {
        if (player == null || !player.isOnline() || sessions.containsKey(player.getUniqueId())) {
            return;
        }
        ItemStack item = itemIn(player, hand);
        if (!isShulker(item) || item.getAmount() != 1) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm) || !(bsm.getBlockState() instanceof ShulkerBox box)) {
            return;
        }

        Component title;
        if (meta.hasDisplayName()) {
            title = meta.displayName();
        } else {
            title = MessageUtil.parse(plugin.getConfig().getString(
                    "held-shulker.title",
                    "&dShulker Box"
            ));
        }

        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, title);
        ItemStack[] contents = box.getInventory().getContents();
        inventory.setContents(cloneContents(contents));

        Session session = new Session(hand, item.getType(), inventory);
        sessions.put(player.getUniqueId(), session);
        holder.session = session;
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        int raw = event.getRawSlot();
        boolean topClick = raw < top.getSize();

        if (isShulker(current) || isShulker(cursor)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (isShulker(hotbar)) {
                event.setCancelled(true);
                return;
            }
            if (session.hand == EquipmentSlot.HAND
                    && event.getHotbarButton() == player.getInventory().getHeldItemSlot()) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            event.setCancelled(true);
            return;
        }
        if (!topClick && isShulker(current)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        if (isShulker(event.getOldCursor()) || isShulker(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        if (!player.getUniqueId().equals(holder.owner)) {
            return;
        }
        closeAndSave(player, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (isShulker(dropped)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        closeAndSave(event.getEntity(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        closeAndSave(event.getPlayer(), true);
    }

    /**
     * Force-closes and synchronously saves any open held-shulker-box session for this player.
     * Must be called before anything wipes the player's live inventory (kit apply, loadout
     * apply) - those call {@code inv.clear()} directly with no awareness of an open session, so
     * without this the box (and anything staged into its GUI) is silently destroyed, and if a
     * kit item later lands as a shulker box in the same hand slot, the stale session contents
     * would later get written into that unrelated new item on close.
     */
    public void forceCloseSession(Player player) {
        if (player == null) {
            return;
        }
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        saveToItem(player, session);
        if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            player.closeInventory();
        }
    }

    private void closeAndSave(Player player, boolean forceClose) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        saveToItem(player, session);
        if (forceClose && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()
                        && player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                    player.closeInventory();
                }
            });
        }
    }

    private void saveToItem(Player player, Session session) {
        ItemStack item = itemIn(player, session.hand);
        if (!isShulker(item) || item.getType() != session.type || item.getAmount() != 1) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm) || !(bsm.getBlockState() instanceof ShulkerBox box)) {
            return;
        }
        box.getInventory().setContents(cloneContents(session.inventory.getContents()));
        bsm.setBlockState(box);
        item.setItemMeta(bsm);
        setItemIn(player, session.hand, item);
    }

    private static ItemStack itemIn(Player player, EquipmentSlot hand) {
        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }
        return player.getInventory().getItemInMainHand();
    }

    private static void setItemIn(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private static boolean isShulker(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.equals("SHULKER_BOX") || name.endsWith("_SHULKER_BOX");
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        if (contents == null) {
            return new ItemStack[27];
        }
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            clone[i] = stack == null ? null : stack.clone();
        }
        return clone;
    }

    private static final class Session {
        private final EquipmentSlot hand;
        private final Material type;
        private final Inventory inventory;

        private Session(EquipmentSlot hand, Material type, Inventory inventory) {
            this.hand = hand;
            this.type = type;
            this.inventory = inventory;
        }
    }

    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private Session session;

        private Holder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return session != null ? session.inventory : Bukkit.createInventory(this, 27);
        }
    }
}
