package com.redecrystal.hologram;

import java.util.List;

/**
 * A network hologram: an id, the server {@code type} it belongs to (only servers
 * of that type render it), a world + position, and its (un-coloured) lines.
 */
record HologramDef(String id, String type, String world, double x, double y, double z, List<String> lines) {
}
