package com.redecrystal.hologram;

import java.util.List;

/** A network hologram: an id, a world + position, and its (un-coloured) lines. */
record HologramDef(String id, String world, double x, double y, double z, List<String> lines) {
}
