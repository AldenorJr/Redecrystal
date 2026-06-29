package com.redecrystal.skin.listener;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.redecrystal.skin.skin.MojangSkinService;
import com.redecrystal.skin.skin.SkinHistory;
import com.redecrystal.skin.skin.SkinTexture;
import com.redecrystal.skin.store.SkinHistoryStore;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies each player's skin at pre-login, so it is part of their INITIAL profile —
 * the only point where the owner's own client renders the skin without relogging.
 * A chosen skin (from history) wins; otherwise the player defaults to the Mojang
 * skin of their own nick. Offline-mode servers show Steve by default, so this
 * restores the expected "my nick's skin" on join. Everything fails open (a backend
 * or Mojang hiccup just leaves the default skin). The constant {@code "textures"}
 * is the Mojang profile property carrying the Base64 skin + signature.
 */
public final class SkinSessionListener implements Listener {

    private static final String TEXTURES = "textures";
    /** How long a resolved own-nick texture is cached, to spare the Mojang API. */
    private static final long OWN_SKIN_TTL_MS = 30L * 60L * 1000L;

    private final JavaPlugin plugin;
    private final SkinHistoryStore store;
    private final MojangSkinService mojang;

    /** name (lowercase) → cached own-nick texture, to avoid a Mojang hit per join. */
    private final Map<String, Cached> ownSkinCache = new ConcurrentHashMap<>();

    public SkinSessionListener(JavaPlugin plugin, SkinHistoryStore store, MojangSkinService mojang) {
        this.plugin = plugin;
        this.store = store;
        this.mojang = mojang;
    }

    private record Cached(SkinTexture texture, long expiresAt) { }

    /** Pre-login runs off the main thread — safe to do the blocking HTTP here. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        SkinTexture texture = resolveInitialSkin(event.getUniqueId(), event.getName());
        if (texture == null || texture.value() == null) {
            return; // no skin available → keep the default (fail open)
        }
        PlayerProfile profile = event.getPlayerProfile();
        profile.removeProperty(TEXTURES);
        profile.setProperty(new ProfileProperty(TEXTURES, texture.value(), texture.signature()));
    }

    /** A chosen skin (history) wins; otherwise the player's own-nick Mojang skin. */
    private SkinTexture resolveInitialSkin(UUID uuid, String name) {
        SkinHistory history = store.fetchBlocking(uuid); // also warms the cache for the GUI
        if (!history.isEmpty()) {
            return history.entries().get(0);
        }
        return ownSkin(name);
    }

    /** The Mojang skin for the player's own nick, cached for {@link #OWN_SKIN_TTL_MS}. */
    private SkinTexture ownSkin(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Cached cached = ownSkinCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.texture();
        }
        try {
            SkinTexture texture = mojang.fetch(name, now);
            ownSkinCache.put(key, new Cached(texture, now + OWN_SKIN_TTL_MS));
            return texture;
        } catch (MojangSkinService.SkinLookupException e) {
            return null; // nick has no Mojang skin / Mojang down → stay default
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        store.evict(event.getPlayer().getUniqueId());
    }
}
