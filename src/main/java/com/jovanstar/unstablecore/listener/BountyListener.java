package com.jovanstar.unstablecore.listener;

import com.jovanstar.unstablecore.UnstableCore;
import com.jovanstar.unstablecore.manager.BountyManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BountyListener implements Listener {

    private final UnstableCore plugin;

    public BountyListener(UnstableCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        BountyManager mgr = plugin.getBountyManager();
        if (mgr == null) {
            return;
        }
        Player player = event.getPlayer();
        if (mgr.peekPrompt(player.getUniqueId()) == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> mgr.handleChat(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BountyManager mgr = plugin.getBountyManager();
        if (mgr != null) {
            mgr.clearPrompt(event.getPlayer().getUniqueId());
        }
    }
}
