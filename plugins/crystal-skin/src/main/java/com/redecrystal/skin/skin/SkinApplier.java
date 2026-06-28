package com.redecrystal.skin.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Applies a {@link SkinTexture} to a live player via the Paper PlayerProfile API.
 * Setting the profile re-sends the player entity to everyone who can see them, so
 * the hide/show cycle below forces nearby clients to re-render with the new skin.
 * NOTE: a client doesn't re-render its OWN skin without a full respawn, so the
 * owner may only see the change after relogging — to be confirmed in-game.
 * All methods touch the Bukkit API and must run on the main thread.
 */
public final class SkinApplier {

    private static final String TEXTURES = "textures";

    private final JavaPlugin plugin;

    public SkinApplier(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Apply {@code texture} to {@code player} and refresh viewers. Main thread only. */
    public void apply(Player player, SkinTexture texture) {
        if (!player.isOnline()) {
            return;
        }
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty(TEXTURES);
        profile.setProperty(new ProfileProperty(TEXTURES, texture.value(), texture.signature()));
        player.setPlayerProfile(profile);
        refreshViewers(player);
    }

    /** Hide/show the player to every online viewer so they re-render the new skin. */
    private void refreshViewers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) {
                continue;
            }
            viewer.hidePlayer(plugin, player);
            viewer.showPlayer(plugin, player);
        }
    }

    /**
     * Build a profile carrying {@code texture}, for use as a skull's owner (so the
     * GUI head renders the actual skin). Mojang ids come without dashes.
     */
    public static PlayerProfile profileFor(SkinTexture texture) {
        PlayerProfile profile = Bukkit.createProfile(dashed(texture.uuid()), texture.name());
        profile.setProperty(new ProfileProperty(TEXTURES, texture.value(), texture.signature()));
        return profile;
    }

    /** Insert dashes into a 32-char Mojang id; pass through anything already valid. */
    private static UUID dashed(String id) {
        try {
            if (id != null && id.length() == 32) {
                return UUID.fromString(id.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            }
            return UUID.fromString(id);
        } catch (RuntimeException e) {
            return UUID.randomUUID(); // cosmetic-only: the texture value still drives the render
        }
    }
}
