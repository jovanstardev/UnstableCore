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

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatListener implements Listener {

    private static final long ELYTRA_MSG_COOLDOWN_MS = 2000L;

    private final UnstableCore plugin;
    private final Map<UUID, Long> elytraMsgCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hitSoundCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentDeathHandled = new ConcurrentHashMap<>();

    public CombatListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            elytraMsgCooldown.remove(uuid);
            hitSoundCooldown.remove(uuid);
            recentDeathHandled.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        long now = System.currentTimeMillis();
        Long prevDeath = recentDeathHandled.put(victim.getUniqueId(), now);
        if (prevDeath != null && now - prevDeath < 250L) {
            return;
        }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitSound(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (event.getFinalDamage() <= 0) {
            return;
        }
        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            return;
        }

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
