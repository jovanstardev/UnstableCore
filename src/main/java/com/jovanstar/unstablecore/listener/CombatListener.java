package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.SettingsManager;
import com.jovanstar.unstablecore.util.MessageUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatListener implements Listener {

    private static final long ELYTRA_MSG_COOLDOWN_MS = 2000L;

    private record CombatTag(UUID attacker, long atMs) {
    }

    private final UnstableCore plugin;
    private final Map<UUID, Long> elytraMsgCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hitSoundCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentDeathHandled = new ConcurrentHashMap<>();
    private final Map<UUID, CombatTag> combatTags = new ConcurrentHashMap<>();
    private final Set<PlayerDeathEvent> handledDeathEvents = Collections.newSetFromMap(new WeakHashMap<>());

    public CombatListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            elytraMsgCooldown.remove(uuid);
            hitSoundCooldown.remove(uuid);
            recentDeathHandled.remove(uuid);
            combatTags.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!handledDeathEvents.add(event)) {
            return;
        }
        Player victim = event.getEntity();
        // Must be the *committed* duel check, not isInDuel(): the latter is already true while a
        // duel request merely sits pending, so gating on it meant a player with a standing request
        // (trivially kept alive - requests are free and re-sendable every few seconds) died in FFA
        // with no killstreak reset, no death recorded, and no kill reward, streak credit or bounty
        // payout for whoever actually killed them.
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInCombatDuel(victim.getUniqueId())) {
            // Duel deaths are handled entirely by DuelListener/DuelManager - never feed FFA
            // kill/streak/bounty systems from a duel.
            return;
        }
        recentDeathHandled.put(victim.getUniqueId(), System.currentTimeMillis());
        int brokenStreak = plugin.getKillstreakManager().getStreak(victim.getUniqueId());
        Player killer = victim.getKiller();

        if (plugin.getConfig().getBoolean("kill-messages.enabled", true)) {
            event.deathMessage(null);
            String icon = plugin.getConfig().getString("kill-messages.icon", "&c⚔");

            if (killer != null && !killer.equals(victim)) {
                int minBreak = plugin.getConfig().getInt("killstreak.streak-end-minimum", 1);
                if (brokenStreak >= minBreak) {
                    MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.streak-end", ""), Map.of(
                            "icon", icon,
                            "killer", killer.getName(),
                            "victim", victim.getName(),
                            "streak", String.valueOf(brokenStreak)
                    ), p -> plugin.getSettingsManager().isEnabled(p, SettingsManager.KILL_MESSAGES)
                            && plugin.getSettingsManager().isEnabled(p, SettingsManager.STREAK_ALERTS));
                }
                MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.kill", ""), Map.of(
                        "icon", icon,
                        "killer", killer.getName(),
                        "victim", victim.getName()
                ), plugin.getSettingsManager().filter(SettingsManager.KILL_MESSAGES));
            } else if (killer != null) {
                MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.suicide", ""), Map.of(
                        "icon", icon,
                        "victim", victim.getName(),
                        "killer", killer.getName()
                ), plugin.getSettingsManager().filter(SettingsManager.KILL_MESSAGES));
            } else {
                MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.death", ""), Map.of(
                        "icon", icon,
                        "victim", victim.getName()
                ), plugin.getSettingsManager().filter(SettingsManager.KILL_MESSAGES));
            }
        }

        plugin.getKillstreakManager().reset(victim.getUniqueId());
        plugin.getKillstreakManager().addDeath(victim.getUniqueId());

        if (killer == null || killer.equals(victim)) {
            return;
        }

        playCombatSound(killer, "kill");

        int newStreak = plugin.getKillstreakManager().addKill(killer);
        plugin.getEconomyManager().rewardKill(killer, plugin.getConfig().getDouble("kill-reward", 10));
        plugin.getKillstreakManager().broadcastMilestone(killer, newStreak);
        if (plugin.getBountyManager() != null) {
            plugin.getBountyManager().claimOnKill(killer, victim);
        }
    }

    /**
     * Called on player quit. If the player recently took damage from another player and never
     * respawned, treats the disconnect as a death credited to that attacker - closing the
     * combat-log exploit where quitting denies the attacker their kill reward/streak/bounty
     * and lets the victim's own streak survive the reconnect.
     */
    public void handleQuitCombatTag(Player victim) {
        UUID uuid = victim.getUniqueId();
        // Committed-duel check, not isInDuel() - otherwise a standing duel request also converted
        // an FFA combat-log into a free escape (see onDeath for the same fix).
        if (plugin.getDuelManager() != null && plugin.getDuelManager().isInCombatDuel(uuid)) {
            // Duel disconnects are forfeits handled by DuelManager.handleDisconnect, routed to
            // duel payout instead of FFA killstreak - never double-credit both systems.
            return;
        }
        CombatTag tag = combatTags.remove(uuid);
        if (tag == null || !plugin.getConfig().getBoolean("combat-tag.enabled", true)) {
            return;
        }
        long windowMs = Math.max(0L, plugin.getConfig().getLong("combat-tag.seconds", 15) * 1000L);
        long now = System.currentTimeMillis();
        if (now - tag.atMs() > windowMs) {
            return;
        }
        Long lastRealDeath = recentDeathHandled.get(uuid);
        if (lastRealDeath != null && now - lastRealDeath < windowMs) {
            return;
        }
        Player killer = plugin.getServer().getPlayer(tag.attacker());
        if (killer == null || !killer.isOnline() || killer.getUniqueId().equals(uuid)) {
            return;
        }
        recentDeathHandled.put(uuid, now);

        int brokenStreak = plugin.getKillstreakManager().getStreak(uuid);
        if (plugin.getConfig().getBoolean("kill-messages.enabled", true)) {
            String icon = plugin.getConfig().getString("kill-messages.icon", "&c⚔");
            int minBreak = plugin.getConfig().getInt("killstreak.streak-end-minimum", 1);
            if (brokenStreak >= minBreak) {
                MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.streak-end", ""), Map.of(
                        "icon", icon,
                        "killer", killer.getName(),
                        "victim", victim.getName(),
                        "streak", String.valueOf(brokenStreak)
                ), p -> plugin.getSettingsManager().isEnabled(p, SettingsManager.KILL_MESSAGES)
                        && plugin.getSettingsManager().isEnabled(p, SettingsManager.STREAK_ALERTS));
            }
            MessageUtil.broadcastFiltered(plugin.getConfig().getString("kill-messages.kill", ""), Map.of(
                    "icon", icon,
                    "killer", killer.getName(),
                    "victim", victim.getName()
            ), plugin.getSettingsManager().filter(SettingsManager.KILL_MESSAGES));
        }

        plugin.getKillstreakManager().reset(uuid);
        plugin.getKillstreakManager().addDeath(uuid);

        playCombatSound(killer, "kill");

        int newStreak = plugin.getKillstreakManager().addKill(killer);
        plugin.getEconomyManager().rewardKill(killer, plugin.getConfig().getDouble("kill-reward", 10));
        plugin.getKillstreakManager().broadcastMilestone(killer, newStreak);
        if (plugin.getBountyManager() != null) {
            plugin.getBountyManager().claimOnKill(killer, victim);
        }
    }

    /** Whether the player is currently within the post-hit combat-tag window (used by duel-request validation). */
    public boolean isCombatTagged(UUID uuid) {
        if (uuid == null || !plugin.getConfig().getBoolean("combat-tag.enabled", true)) {
            return false;
        }
        CombatTag tag = combatTags.get(uuid);
        if (tag == null) {
            return false;
        }
        long windowMs = Math.max(0L, plugin.getConfig().getLong("combat-tag.seconds", 15) * 1000L);
        return System.currentTimeMillis() - tag.atMs() <= windowMs;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatMonitor(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        // Combat Tagging. Committed-duel check again: with plain isInDuel(), simply holding a
        // pending duel request made a player un-taggable, so they could brawl in FFA and still
        // pass every "not in combat" gate (arena join, /spec, queue join) as an instant escape.
        boolean inDuel = plugin.getDuelManager() != null
                && (plugin.getDuelManager().isInCombatDuel(victim.getUniqueId())
                || plugin.getDuelManager().isInCombatDuel(attacker.getUniqueId()));
        if (!inDuel) {
            combatTags.put(victim.getUniqueId(), new CombatTag(attacker.getUniqueId(), System.currentTimeMillis()));
        }

        // Hit Sound
        if (victim.getHealth() - event.getFinalDamage() > 0) {
            long cooldown = Math.max(0L, plugin.getConfig().getLong("combat-sounds.hit.cooldown-ms", 80L));
            if (cooldown > 0) {
                long now = System.currentTimeMillis();
                Long last = hitSoundCooldown.get(attacker.getUniqueId());
                if (last != null && now - last < cooldown) {
                    return;
                }
                hitSoundCooldown.put(attacker.getUniqueId(), now);
            }
            playCombatSound(attacker, "hit");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }

        if (!isRestricted(attacker) && !isRestricted(victim)) {
            return;
        }

        ItemStack hand = attacker.getInventory().getItemInMainHand();
        if (hand.getType() == Material.MACE) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof WindCharge) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return;
        }
        if (!isRestricted(player)) {
            return;
        }
        if (event.getEntity() instanceof WindCharge || event.getEntity() instanceof Firework) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!isRestricted(player)) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type == Material.WIND_CHARGE
                || type == Material.FIREWORK_ROCKET
                || type == Material.MACE
                || type == Material.TNT
                || type == Material.TNT_MINECART
                || type.name().contains("SPEAR")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!event.isGliding()) {
            return;
        }
        if (!isRestricted(player)) {
            return;
        }
        event.setCancelled(true);
        player.setGliding(false);
        sendElytraDenied(player);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGlideMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!player.isGliding()) {
            return;
        }
        if (!isRestricted(player)) {
            return;
        }
        player.setGliding(false);
        sendElytraDenied(player);
    }

    private void playCombatSound(Player player, String key) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("combat-sounds." + key);
        if (sec == null || !sec.getBoolean("enabled", true)) {
            return;
        }
        String name = sec.getString("sound", key.equals("kill") ? "ENTITY_WITHER_SPAWN" : "ENTITY_PLAYER_ATTACK_STRONG");
        if (name == null || name.isBlank()) {
            return;
        }
        float volume = (float) sec.getDouble("volume", 1.0);
        float pitch = (float) sec.getDouble("pitch", 1.0);
        try {
            Sound sound = Sound.valueOf(name.trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    private void sendElytraDenied(Player player) {
        long now = System.currentTimeMillis();
        Long last = elytraMsgCooldown.get(player.getUniqueId());
        if (last != null && now - last < ELYTRA_MSG_COOLDOWN_MS) {
            return;
        }
        elytraMsgCooldown.put(player.getUniqueId(), now);
        String msg = plugin.getConfig().getString(
                "messages.arena-nomace-no-elytra",
                "&c&l(!) &r&cElytra flying is disabled in no-mace arenas."
        );
        if (msg == null || msg.isBlank()) {
            msg = "&c&l(!) &r&cElytra flying is disabled in no-mace arenas.";
        }
        MessageUtil.send(player, msg);
    }

    private boolean isRestricted(Player player) {
        return plugin.getArenaManager().isInNomaceArena(player)
                || plugin.getArenaManager().isInNewbieArena(player);
    }
}
