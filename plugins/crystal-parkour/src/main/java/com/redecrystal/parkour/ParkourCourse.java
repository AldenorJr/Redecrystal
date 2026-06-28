package com.redecrystal.parkour;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Immutable parkour course parsed from the centralized {@code parkour} config —
 * all command-defined, so it replicates to every (identical) lobby like the
 * spawn. Points: {@code spawn} (the compass arrival, with facing), {@code start}
 * (the start line / iron plate), ordered {@code checkpoints}, {@code finish}, a
 * kill plane ({@code fallY}, above the lobby's void-rescue threshold) and a
 * detection {@code radius}. Each point also carries a floating hologram.
 */
public final class ParkourCourse {

    private final String world;
    private final double[] spawn;             // x,y,z,yaw,pitch (compass arrival)
    private final double[] start;             // x,y,z (start line / iron plate)
    private final List<double[]> checkpoints; // each x,y,z
    private final double[] finish;            // x,y,z
    private final double fallY;
    private final double radius;

    private ParkourCourse(String world, double[] spawn, double[] start, List<double[]> checkpoints,
                          double[] finish, double fallY, double radius) {
        this.world = world;
        this.spawn = spawn;
        this.start = start;
        this.checkpoints = checkpoints;
        this.finish = finish;
        this.fallY = fallY;
        this.radius = radius;
    }

    public static ParkourCourse fromConfig(Map<String, Object> c) {
        if (c == null) {
            return new ParkourCourse(null, null, null, List.of(), null, 50, 2.0);
        }
        String world = str(c.get("world"));
        double[] spawn = point(c.get("spawn"), true);
        double[] start = point(c.get("start"), false);
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
        double fallY = num(c.get("fallY"), 50);
        double radius = num(c.get("radius"), 2.0);
        return new ParkourCourse(world, spawn, start, cps, finish, fallY, radius);
    }

    /** Runnable once the world, the start line and the finish are all set. */
    public boolean isPlayable() {
        return worldReady() && start != null && finish != null;
    }

    public boolean hasSpawn() {
        return spawn != null;
    }

    public boolean hasStart() {
        return worldReady() && start != null;
    }

    public boolean hasFinishPoint() {
        return worldReady() && finish != null;
    }

    private boolean worldReady() {
        return world != null && Bukkit.getWorld(world) != null;
    }

    public double fallY() {
        return fallY;
    }

    public int checkpointCount() {
        return checkpoints.size();
    }

    /** Compass arrival point (with facing). */
    public Location spawnLocation() {
        World w = Bukkit.getWorld(world);
        return new Location(w, spawn[0], spawn[1], spawn[2], (float) spawn[3], (float) spawn[4]);
    }

    public Location startLocation() {
        return at(start);
    }

    public Location checkpointLocation(int index) {
        return at(checkpoints.get(index));
    }

    public Location finishLocation() {
        return at(finish);
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

    private Location at(double[] p) {
        return new Location(Bukkit.getWorld(world), p[0], p[1], p[2]);
    }

    private boolean near(Location loc, double[] p) {
        if (p == null || loc.getWorld() == null || !loc.getWorld().getName().equals(world)) {
            return false;
        }
        double dx = loc.getX() - p[0];
        double dy = loc.getY() - p[1];
        double dz = loc.getZ() - p[2];
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

    public static Map<String, Object> facingMap(Location loc) {
        return Map.of("x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch()));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
