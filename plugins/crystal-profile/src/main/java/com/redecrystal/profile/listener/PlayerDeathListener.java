package com.redecrystal.profile.listener;

import com.redecrystal.core.CrystalCore;
import com.redecrystal.profile.CrystalProfilePlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Records combat stats and the activity trail on death: a DEATH for the victim
 * and, when there is a killer, a KILL for them. All persistence goes through the
 * backend (HTTP), off the main thread.
 */
public final class PlayerDeathListener implements Listener {

    private final CrystalProfilePlugin plugin;
    private final CrystalCore crystal;

    public PlayerDeathListener(CrystalProfilePlugin plugin, CrystalCore crystal) {
        this.plugin = plugin;
        this.crystal = crystal;
    }

    private String server() {
        return crystal.config().serverId();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        String vUuid = victim.getUniqueId().toString();
        String vName = victim.getName();
        String detail = event.deathMessage() == null ? null
                : PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        String kUuid = killer == null ? null : killer.getUniqueId().toString();
        String kName = killer == null ? null : killer.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                crystal.backend().addCombat(vUuid, 0, 1);
                crystal.backend().recordActivity(vUuid, vName, "DEATH", detail, server());
                if (kUuid != null) {
                    crystal.backend().addCombat(kUuid, 1, 0);
                    crystal.backend().recordActivity(kUuid, kName, "KILL", "matou " + vName, server());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Combat record failed: " + e);
            }
        });
    }
}
