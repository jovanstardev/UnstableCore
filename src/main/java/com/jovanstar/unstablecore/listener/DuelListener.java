package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.DuelManager;
import com.jovanstar.unstablecore.model.Duel;
import com.jovanstar.unstablecore.model.DuelInventorySnapshot;
import com.jovanstar.unstablecore.model.DuelState;
import com.jovanstar.unstablecore.util.MessageUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Duel-specific event wiring: the wager chat prompt (mirrors {@link BountyListener}), death
 * resolution, pre-FIGHT damage prevention, escape/teleport-item restrictions during an
 * active duel, respawn teleport for the loser, and visibility isolation on join.
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

    /**
     * After the loser respawns, teleport them back to their pre-duel location.
     * The DuelManager marks the loser as having left grace immediately upon NORMAL_WIN,
     * so we just need to find their snapshot and teleport them there.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // The loser was already marked as "left grace" but may still be referenced in a grace duel
        // that only the winner is using. We need their snapshot to teleport them back.
        // We store the pending respawn-teleport in a small helper on DuelManager.
        Location loc = mgr.consumePendingRespawnLocation(uuid);
        if (loc != null) {
            event.setRespawnLocation(loc);
        }
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

    /**
     * When a new player joins while a duel is active, hide the duelists from them
     * and hide them from the duelists — keeps visibility isolation intact for late joiners.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) return;
        Player joiner = event.getPlayer();
        // For every active duel, hide the two participants from the joiner and vice-versa
        for (Duel duel : mgr.getAllDuels()) {
            if (!duel.getState().isActiveCombat()) continue;
            Player c = Bukkit.getPlayer(duel.getChallenger());
            Player t = Bukkit.getPlayer(duel.getTarget());
            UUID joinerId = joiner.getUniqueId();
            if (joinerId.equals(duel.getChallenger()) || joinerId.equals(duel.getTarget())) {
                // One of the duelists reconnected — hide all non-opponents from them
                if (c != null && t != null) {
                    // handled by applyDuelVisibility at fight start; just re-apply for this player
                    for (Player other : Bukkit.getOnlinePlayers()) {
                        UUID uid = other.getUniqueId();
                        if (uid.equals(duel.getChallenger()) || uid.equals(duel.getTarget())) continue;
                        joiner.hidePlayer(plugin, other);
                        other.hidePlayer(plugin, joiner);
                    }
                }
            } else {
                // Third-party player joining — hide duelists from them and them from duelists
                if (c != null) {
                    joiner.hidePlayer(plugin, c);
                    c.hidePlayer(plugin, joiner);
                }
                if (t != null) {
                    joiner.hidePlayer(plugin, t);
                    t.hidePlayer(plugin, joiner);
                }
            }
        }
    }

    /**
     * Combat isolation: ensures duelists can only damage their opponent in the same active duel,
     * and non-duelists cannot damage or be damaged by duelists.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDuelPvp(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }

        DuelManager mgr = plugin.getDuelManager();
        if (mgr == null) {
            return;
        }

        UUID victimId = victim.getUniqueId();
        UUID attackerId = attacker.getUniqueId();

        Duel victimDuel = mgr.getDuelForPlayer(victimId);
        Duel attackerDuel = mgr.getDuelForPlayer(attackerId);

        // If either player is in a duel
        if (victimDuel != null || attackerDuel != null) {
            // Must be in the EXACT same duel AND that duel must be in ACTIVE state
            if (victimDuel == null || attackerDuel == null || !victimDuel.getId().equals(attackerDuel.getId())) {
                event.setCancelled(true);
                return;
            }
            if (victimDuel.getState() != DuelState.ACTIVE) {
                event.setCancelled(true);
            }
        }
    }

    private static Player resolveAttacker(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}

