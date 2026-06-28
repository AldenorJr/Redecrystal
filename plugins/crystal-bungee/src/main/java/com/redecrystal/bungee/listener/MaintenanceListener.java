package com.redecrystal.bungee.listener;

import com.redecrystal.bungee.BrandColors;
import com.redecrystal.core.CrystalCore;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

/**
 * Network-wide maintenance gate: while {@code global.maintenance} is on, denies
 * logins (except staff listed in {@code global.maintenanceBypass}) and swaps the
 * server-list MOTD for a maintenance banner.
 */
public final class MaintenanceListener {

    private static final String GLOBAL_CONFIG = "global";

    private final CrystalCore crystal;
    private final Logger logger;

    public MaintenanceListener(CrystalCore crystal, Logger logger) {
        this.crystal = crystal;
        this.logger = logger;
    }

    /**
     * Deny logins while the network is in maintenance, except for staff listed in
     * {@code global.maintenanceBypass} (player names), so they can get in to work.
     */
    @Subscribe
    public void onLogin(LoginEvent event) {
        if (!crystal.configProvider().get(GLOBAL_CONFIG).bool("maintenance", false)) {
            return;
        }
        String name = event.getPlayer().getUsername().toLowerCase(Locale.ROOT);
        if (bypassNames().contains(name)) {
            logger.info("Maintenance bypass for {}", event.getPlayer().getUsername());
            return;
        }
        event.setResult(ResultedEvent.ComponentResult.denied(maintenanceKick()));
    }

    /** Swap the server-list MOTD for a maintenance banner while maintenance is on. */
    @Subscribe
    public void onPing(ProxyPingEvent event) {
        if (!crystal.configProvider().get(GLOBAL_CONFIG).bool("maintenance", false)) {
            return; // normal MOTD comes from velocity.toml
        }
        ServerPing maintenancePing = event.getPing().asBuilder()
                .description(maintenanceMotd())
                .build();
        event.setPing(maintenancePing);
    }

    /** Lower-cased set of player names allowed in during maintenance. */
    private Set<String> bypassNames() {
        Object raw = crystal.configProvider().get(GLOBAL_CONFIG).value("maintenanceBypass");
        if (raw instanceof List<?> list) {
            return list.stream().map(o -> String.valueOf(o).toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    /** Two-line maintenance MOTD with the purple RedeCrystal wordmark. */
    private static Component maintenanceMotd() {
        Component line1 = Component.text("  REDECRYSTAL", BrandColors.PURPLE, TextDecoration.BOLD)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text("Em manutenção", NamedTextColor.RED, TextDecoration.BOLD));
        Component line2 = Component.text("  Estamos melhorando a experiência. Voltamos já!", BrandColors.PURPLE_SOFT);
        return line1.append(Component.newline()).append(line2);
    }

    /** Kick screen shown to non-bypass players during maintenance. */
    private static Component maintenanceKick() {
        return Component.text("REDECRYSTAL", BrandColors.PURPLE, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("A rede está em manutenção.", NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Estamos melhorando a experiência — volte em breve!", BrandColors.PURPLE_SOFT));
    }
}
