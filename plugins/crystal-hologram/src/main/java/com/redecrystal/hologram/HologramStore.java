package com.redecrystal.hologram;

import com.redecrystal.core.CrystalCore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads/writes the network hologram list in the central {@code holograms} config.
 * Read is fail-open (empty on a backend blip); writes do a read-modify-write of the
 * whole list and {@code putConfig} it back, which hot-reloads every server.
 *
 * <p>All methods are synchronous (they may hit the backend) — callers schedule them
 * off the main thread.
 */
final class HologramStore {

    static final String CONFIG_KEY = "holograms";
    private static final String ITEMS = "items";

    private final CrystalCore crystal;

    HologramStore(CrystalCore crystal) {
        this.crystal = crystal;
    }

    /** Current holograms; empty if the config is missing or the backend is down. */
    List<HologramDef> all() {
        Object raw = crystal.configProvider().get(CONFIG_KEY).value(ITEMS);
        List<HologramDef> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    HologramDef def = fromMap(m);
                    if (def != null) {
                        out.add(def);
                    }
                }
            }
        }
        return out;
    }

    /** Create or replace by id (case-insensitive), persisting the full list. */
    void put(HologramDef def) {
        List<HologramDef> current = all();
        current.removeIf(d -> d.id().equalsIgnoreCase(def.id()));
        current.add(def);
        save(current);
    }

    /** Remove by id; true if something was removed. */
    boolean remove(String id) {
        List<HologramDef> current = all();
        boolean removed = current.removeIf(d -> d.id().equalsIgnoreCase(id));
        if (removed) {
            save(current);
        }
        return removed;
    }

    private void save(List<HologramDef> defs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (HologramDef d : defs) {
            items.add(toMap(d));
        }
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ITEMS, items);
        crystal.backend().putConfig(CONFIG_KEY, cfg);
    }

    private static HologramDef fromMap(Map<?, ?> m) {
        Object id = m.get("id");
        Object world = m.get("world");
        if (id == null || world == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        if (m.get("lines") instanceof List<?> raw) {
            for (Object l : raw) {
                lines.add(String.valueOf(l));
            }
        }
        return new HologramDef(String.valueOf(id), String.valueOf(world),
                num(m.get("x")), num(m.get("y")), num(m.get("z")), lines);
    }

    private static Map<String, Object> toMap(HologramDef d) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", d.id());
        m.put("world", d.world());
        m.put("x", d.x());
        m.put("y", d.y());
        m.put("z", d.z());
        m.put("lines", d.lines());
        return m;
    }

    private static double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }
}
