package com.redecrystal.login.listener;

import com.redecrystal.login.CrystalLoginPlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * On join the player lands frozen on the login canvas: ADVENTURE mode, full
 * health, an empty inventory and permanent blindness. We then arm the login
 * timeout and ask the backend whether the account exists so the prompt can be
 * tailored — both delegated to the plugin, which owns the auth lifecycle.
 */
public final class PlayerJoinListener implements Listener {

    private final CrystalLoginPlugin plugin;

    public PlayerJoinListener(CrystalLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.getInventory().clear();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));

        // Kick the player if they sit on the login screen too long (frees the slot).
        plugin.scheduleLoginTimeout(player);
        // Tailor the prompt to whether the account exists (off the main thread).
        plugin.resolveAndPrompt(player);
    }
}
