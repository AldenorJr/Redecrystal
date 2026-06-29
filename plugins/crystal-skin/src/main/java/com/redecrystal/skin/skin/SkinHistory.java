package com.redecrystal.skin.skin;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's ordered skin history (most-recent first). Immutable: every mutation
 * returns a fresh instance. Capped at {@link #MAX_ENTRIES} so the GUI stays a
 * single page and the stored blob never grows without bound.
 */
public record SkinHistory(List<SkinTexture> entries) {

    /** Matches the GUI's single-page capacity (3 content rows × 9). */
    public static final int MAX_ENTRIES = 27;

    public SkinHistory {
        entries = List.copyOf(entries);
    }

    public static SkinHistory empty() {
        return new SkinHistory(List.of());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Push a freshly-applied texture to the top. De-duplicates by texture
     * {@code value} (re-using a skin just moves it up rather than adding a
     * duplicate) and trims to {@link #MAX_ENTRIES}.
     */
    public SkinHistory withMostRecent(SkinTexture texture) {
        List<SkinTexture> next = new ArrayList<>(entries.size() + 1);
        next.add(texture);
        for (SkinTexture e : entries) {
            if (!e.value().equals(texture.value())) {
                next.add(e);
            }
        }
        if (next.size() > MAX_ENTRIES) {
            next = next.subList(0, MAX_ENTRIES);
        }
        return new SkinHistory(next);
    }
}
