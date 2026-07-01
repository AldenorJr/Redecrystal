package com.redecrystal.bungee.command;

import com.redecrystal.bungee.BrandColors;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * {@code /ping}: tells a player their current latency to the proxy. Registered
 * on the proxy so it works from every backend server at once with a single,
 * authoritative measurement — Velocity already tracks each player's round-trip
 * time, so no game server has to compute (or be trusted for) it.
 */
public final class PingCommand implements SimpleCommand {

    // Latency thresholds (ms) that pick the colour of the reported number.
    private static final long PING_GOOD_MS = 100;
    private static final long PING_OKAY_MS = 250;

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Apenas jogadores.", NamedTextColor.RED));
            return;
        }
        long ping = player.getPing();
        if (ping < 0) {
            // Velocity hasn't measured a round-trip yet (just connected).
            player.sendMessage(Component.text("Seu ping ainda está sendo calculado…", BrandColors.PURPLE_SOFT));
            return;
        }
        player.sendMessage(Component.text("Seu ping: ", BrandColors.PURPLE_SOFT)
                .append(Component.text(ping + "ms", colorFor(ping))));
    }

    /** Green for snappy, yellow for playable, red for laggy. */
    private static TextColor colorFor(long ping) {
        if (ping <= PING_GOOD_MS) {
            return NamedTextColor.GREEN;
        }
        return ping <= PING_OKAY_MS ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }
}
