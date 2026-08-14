package com.jovanstar.unstablecore.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class WorldGuardHook {

    private WorldGuardHook() {
    }

    public static boolean isPresent() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    public static boolean isInRegion(Player player, String regionId) {
        return isInRegion(player.getLocation(), regionId);
    }

    public static boolean isInRegion(Location location, String regionId) {
        if (!isPresent() || location == null || location.getWorld() == null || regionId == null) {
            return false;
        }
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            ApplicableRegionSet set = query.getApplicableRegions(BukkitAdapter.adapt(location));
            for (ProtectedRegion region : set) {
                if (region.getId().equalsIgnoreCase(regionId)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean regionExists(String worldName, String regionId) {
        if (!isPresent()) {
            return false;
        }
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return false;
            }
            RegionManager manager = container.get(BukkitAdapter.adapt(world));
            return manager != null && manager.getRegion(regionId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
