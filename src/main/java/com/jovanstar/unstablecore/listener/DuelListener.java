package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelManager;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.util.MessageUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Duel-specific event wiring: the wager chat prompt (mirrors {@link BountyListener}), death
 * resolution, pre-FIGHT damage prevention, and escape/teleport-item restrictions during an
 * active duel. Combat isolation from FFA (kill/streak/bounty) is handled by early-return checks
 * directly in {@link CombatListener} - this listener only ever handles the duel side.
 */
public final class DuelListener implements Listener {

    private final UnstableCore plugin;

    public DuelListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) {
            return;
        }
        Player player = event.getPlayer();
        if (mgr.peekWagerPrompt(player.getUniqueId()) == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> mgr.handleChat(player, text));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) {
            return;
        }
        Player victim = event.getEntity();
        if (!mgr.isInDuel(victim.getUniqueId())) {
            return;
        }
        // Duel win/loss messaging replaces the vanilla death message; kit-item drops are left
        // alone on purpose - the post-duel grace period exists specifically so the loser can
        // collect them, while their original pre-duel inventory is restored separately.
        event.deathMessage(null);
        mgr.handleDeath(victim);
    }

    /** No damage is permitted before FIGHT, even though both players are already teleported in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreFightDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) {
            return;
        }
        Duel duel = mgr.getDuelForPlayer(player.getUniqueId());
        if (duel != null && duel.getState() == DuelState.STARTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null || !mgr.isInActiveDuel(event.getPlayer().getUniqueId())) {
            return;
        }
        String message = event.getMessage();
        int spaceIdx = message.indexOf(' ');
        String label = (spaceIdx == -1 ? message.substring(1) : message.substring(1, spaceIdx)).toLowerCase(Locale.ROOT);
        List<String> restricted = plugin.getConfigManager().getDuels().getStringList("restricted-commands");
        for (String r : restricted) {
            if (r != null && r.equalsIgnoreCase(label)) {
                event.setCancelled(true);
                MessageUtil.send(event.getPlayer(), plugin.getConfigManager().getDuels()
                        .getString("messages.escape-blocked", "&cThat command is blocked during an active duel."));
                return;
            }
        }
    }

    /** v1 has no per-kit "allow teleport items" flag yet, so this is a blanket restriction while ACTIVE. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        DuelManager mgr = plugin.getDuelManager();
        Player player = event.getPlayer();
        if (mgr == null || !mgr.isInActiveDuel(player.getUniqueId())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == Material.ENDER_PEARL || type == Material.CHORUS_FRUIT) {
            event.setCancelled(true);
            MessageUtil.send(player, plugin.getConfigManager().getDuels()
                    .getString("messages.teleport-item-blocked", "&cThat item is disabled during this duel."));
        }
    }
}
