package com.jovanstar.unstablecore.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Arena {

    private final String id;
    private String displayName;
    private ArenaType type;
    private boolean permanent;
    private String worldName;
    private double x, y, z;
    private float yaw, pitch;
    private int radius;
    private final List<int[]> spots = new ArrayList<>();

    // Duel-specific spawns
    private double spawn1X, spawn1Y, spawn1Z;
    private float spawn1Yaw, spawn1Pitch;
    private boolean hasSpawn1;

    private double spawn2X, spawn2Y, spawn2Z;
    private float spawn2Yaw, spawn2Pitch;
    private boolean hasSpawn2;

    public Arena(String id, String displayName, ArenaType type) {
        this.id = id.toLowerCase();
        this.displayName = displayName;
        this.type = type;
    }

    public static Arena fromConfig(String id, ConfigurationSection section) {
        Arena arena = new Arena(
                id,
                section.getString("display-name", id),
                ArenaType.from(section.getString("type", "mace"))
        );
        arena.permanent = section.getBoolean("permanent", false);
        arena.worldName = section.getString("world", "");
        arena.x = section.getDouble("x");
        arena.y = section.getDouble("y");
        arena.z = section.getDouble("z");
        arena.yaw = (float) section.getDouble("yaw");
        arena.pitch = (float) section.getDouble("pitch");
        arena.radius = section.getInt("radius", 50);

        if (section.isConfigurationSection("spawn1")) {
            ConfigurationSection s1 = section.getConfigurationSection("spawn1");
            arena.spawn1X = s1.getDouble("x");
            arena.spawn1Y = s1.getDouble("y");
            arena.spawn1Z = s1.getDouble("z");
            arena.spawn1Yaw = (float) s1.getDouble("yaw");
            arena.spawn1Pitch = (float) s1.getDouble("pitch");
            arena.hasSpawn1 = true;
        }

        if (section.isConfigurationSection("spawn2")) {
            ConfigurationSection s2 = section.getConfigurationSection("spawn2");
            arena.spawn2X = s2.getDouble("x");
            arena.spawn2Y = s2.getDouble("y");
            arena.spawn2Z = s2.getDouble("z");
            arena.spawn2Yaw = (float) s2.getDouble("yaw");
            arena.spawn2Pitch = (float) s2.getDouble("pitch");
            arena.hasSpawn2 = true;
        }

        for (String spot : section.getStringList("spots")) {
            String[] parts = spot.split(",");
            if (parts.length >= 3) {
                try {
                    arena.spots.add(new int[]{
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim())
                    });
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return arena;
    }

    public void write(ConfigurationSection section) {
        section.set("display-name", displayName);
        section.set("type", type.name().toLowerCase());
        section.set("permanent", permanent);
        section.set("world", worldName);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", yaw);
        section.set("pitch", pitch);
        section.set("radius", radius);

        if (hasSpawn1) {
            ConfigurationSection s1 = section.createSection("spawn1");
            s1.set("x", spawn1X);
            s1.set("y", spawn1Y);
            s1.set("z", spawn1Z);
            s1.set("yaw", spawn1Yaw);
            s1.set("pitch", spawn1Pitch);
        }

        if (hasSpawn2) {
            ConfigurationSection s2 = section.createSection("spawn2");
            s2.set("x", spawn2X);
            s2.set("y", spawn2Y);
            s2.set("z", spawn2Z);
            s2.set("yaw", spawn2Yaw);
            s2.set("pitch", spawn2Pitch);
        }

        List<String> spotStrings = new ArrayList<>(spots.size());
        for (int[] s : spots) {
            spotStrings.add(s[0] + "," + s[1] + "," + s[2]);
        }
        section.set("spots", spotStrings);
    }

    public void setCenter(Location loc, int radius) {
        this.worldName = loc.getWorld().getName();
        this.x = loc.getX();
        this.y = loc.getY();
        this.z = loc.getZ();
        this.yaw = loc.getYaw();
        this.pitch = loc.getPitch();
        this.radius = Math.max(5, radius);
    }

    public Location getCenter() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setSpawn1(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        if (this.worldName == null || this.worldName.isBlank()) {
            this.worldName = loc.getWorld().getName();
        }
        this.spawn1X = loc.getX();
        this.spawn1Y = loc.getY();
        this.spawn1Z = loc.getZ();
        this.spawn1Yaw = loc.getYaw();
        this.spawn1Pitch = loc.getPitch();
        this.hasSpawn1 = true;
    }

    public void setSpawn2(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        if (this.worldName == null || this.worldName.isBlank()) {
            this.worldName = loc.getWorld().getName();
        }
        this.spawn2X = loc.getX();
        this.spawn2Y = loc.getY();
        this.spawn2Z = loc.getZ();
        this.spawn2Yaw = loc.getYaw();
        this.spawn2Pitch = loc.getPitch();
        this.hasSpawn2 = true;
    }

    public Location getSpawn1() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        if (hasSpawn1) {
            return new Location(world, spawn1X, spawn1Y, spawn1Z, spawn1Yaw, spawn1Pitch);
        }
        return getCenter();
    }

    public Location getSpawn2() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        if (hasSpawn2) {
            return new Location(world, spawn2X, spawn2Y, spawn2Z, spawn2Yaw, spawn2Pitch);
        }
        return randomSpot();
    }

    public boolean hasSpawn1() {
        return hasSpawn1;
    }

    public boolean hasSpawn2() {
        return hasSpawn2;
    }

    public Location randomSpot() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        if (!spots.isEmpty()) {
            int[] s = spots.get(ThreadLocalRandom.current().nextInt(spots.size()));
            return new Location(world, s[0] + 0.5, s[1], s[2] + 0.5, yaw, pitch);
        }
        return getCenter();
    }

    public void setSpots(List<int[]> newSpots) {
        spots.clear();
        spots.addAll(newSpots);
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

    public ArenaType getType() {
        return type;
    }

    public void setType(ArenaType type) {
        this.type = type;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public void setPermanent(boolean permanent) {
        this.permanent = permanent;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getRadius() {
        return radius;
    }

    public List<int[]> getSpots() {
        return spots;
    }

    public boolean hasCenter() {
        return worldName != null && !worldName.isBlank() && Bukkit.getWorld(worldName) != null;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || worldName == null || worldName.isBlank()) {
            return false;
        }
        if (!worldName.equalsIgnoreCase(loc.getWorld().getName())) {
            return false;
        }
        double dx = loc.getX() - x;
        double dz = loc.getZ() - z;
        double r = radius + 5;
        return (dx * dx) + (dz * dz) <= r * r;
    }
}
