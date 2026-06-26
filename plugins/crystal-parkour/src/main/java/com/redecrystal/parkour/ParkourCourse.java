package com.redecrystal.parkour;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable parkour course parsed from the centralized {@code parkour} config:
 * a start (with facing), ordered checkpoints, a finish, a kill plane ({@code fallY})
 * and a detection {@code radius}. Coordinates are world-relative; every lobby
 * shares the same {@code WORLD_LOBBY}, so one config drives them all.
 */
public final class ParkourCourse {

    private final String world;
    private final double[] start;       // x,y,z,yaw,pitch  (null if unset)
    private final List<double[]> checkpoints; // each x,y,z
    private final double[] finish;      // x,y,z (null if unset)
    private final double fallY;
    private final double radius;

    private ParkourCourse(String world, double[] start, List<double[]> checkpoints,
                          double[] finish, double fallY, double radius) {
        this.world = world;
        this.start = start;
        this.checkpoints = checkpoints;
        this.finish = finish;
        this.fallY = fallY;
        this.radius = radius;
    }

    public static ParkourCourse fromConfig(Map<String, Object> c) {
        if (c == null) {
            return new ParkourCourse(null, null, List.of(), null, -64, 1.5);
        }
        String world = str(c.get("world"));
        double[] start = point(c.get("start"), true);
        double[] finish = point(c.get("finish"), false);
        List<double[]> cps = new ArrayList<>();
        if (c.get("checkpoints") instanceof List<?> list) {
            for (Object o : list) {
                double[] p = point(o, false);
                if (p != null) {
                    cps.add(p);
                }
            }
        }
        double fallY = num(c.get("fallY"), -64);
        double radius = num(c.get("radius"), 1.5);
        return new ParkourCourse(world, start, cps, finish, fallY, radius);
    }

    public boolean isPlayable() {
        return world != null && Bukkit.getWorld(world) != null && start != null && finish != null;
    }

    public Location startLocation() {
        World w = Bukkit.getWorld(world);
        return new Location(w, start[0], start[1], start[2], (float) start[3], (float) start[4]);
    }

    public Location checkpointLocation(int index) {
        World w = Bukkit.getWorld(world);
        double[] p = checkpoints.get(index);
        return new Location(w, p[0], p[1], p[2]);
    }

    public int checkpointCount() {
        return checkpoints.size();
    }

    public boolean inStart(Location loc) {
        return near(loc, start);
    }

    public boolean inFinish(Location loc) {
        return near(loc, finish);
    }

    /** Highest checkpoint index the player is standing on, or -1. */
    public int checkpointAt(Location loc) {
        for (int i = checkpoints.size() - 1; i >= 0; i--) {
            if (near(loc, checkpoints.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public boolean belowKillPlane(Location loc) {
        return loc.getY() < fallY;
    }

    private boolean near(Location loc, double[] p) {
        if (p == null || loc.getWorld() == null || !loc.getWorld().getName().equals(world)) {
            return false;
        }
        double dx = loc.getX() - p[0], dy = loc.getY() - p[1], dz = loc.getZ() - p[2];
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    // ── parsing helpers ──
    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double[] point(Object o, boolean withFacing) {
        if (!(o instanceof Map<?, ?> m) || m.get("x") == null) {
            return null;
        }
        if (withFacing) {
            return new double[]{num(m.get("x"), 0), num(m.get("y"), 0), num(m.get("z"), 0),
                    num(m.get("yaw"), 0), num(m.get("pitch"), 0)};
        }
        return new double[]{num(m.get("x"), 0), num(m.get("y"), 0), num(m.get("z"), 0)};
    }

    // ── serialization helpers for admin commands ──
    public static Map<String, Object> pointMap(Location loc) {
        return Map.of("x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()));
    }

    public static Map<String, Object> startMap(Location loc) {
        return Map.of("x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch()));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
