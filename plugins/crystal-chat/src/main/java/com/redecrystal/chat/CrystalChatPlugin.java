package com.redecrystal.chat;

import com.redecrystal.chat.commands.ReplyCommand;
import com.redecrystal.chat.commands.TellCommand;
import com.redecrystal.chat.commands.TellToggleCommand;
import com.redecrystal.chat.listener.ChatListener;
import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.messaging.KafkaTopics;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Network-wide chat over the {@code player-chat} topic. Global messages are
 * fanned out to everyone; private messages ({@code /tell}) are scoped to a target
 * and delivered only on the server where that player is online. This class only
 * boots the SDK, builds the services and wires up the listener/commands — the
 * behaviour lives in {@link ChatService}, {@link TellService}, the
 * {@code listener/} and {@code commands/} packages.
 */
public final class CrystalChatPlugin extends JavaPlugin {

    private CrystalCore crystal;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());

        ChatService chat = new ChatService(this, crystal);
        chat.register();
        TellService tells = new TellService(this, crystal, chat);

        // Consume the shared chat topic: tells are delivered locally, global
        // lines are broadcast. Both hop back onto the main thread before touching
        // the Bukkit API.
        crystal.events().on(KafkaTopics.PLAYER_CHAT, event -> {
            String scope = event.get("scope");
            if ("tell".equals(scope)) {
                getServer().getScheduler().runTask(this, () -> tells.deliverTell(event.get("from"),
                        event.get("targetUuid"), event.get("message")));
            } else {
                String server = event.get("server");
                String player = event.get("player");
                String message = event.get("message");
                String prefix = event.get("prefix");
                String nameColor = event.get("nameColor");
                if (player != null && message != null) {
                    getServer().getScheduler().runTask(this,
                            () -> chat.broadcast(server, player, message, prefix, nameColor));
                }
            }
        });

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new ChatListener(this, crystal, chat), this);

        getCommand("tell").setExecutor(new TellCommand(tells));
        getCommand("r").setExecutor(new ReplyCommand(tells));
        getCommand("telltoggle").setExecutor(new TellToggleCommand(tells));

        getLogger().info("CrystalChat enabled.");
    }

    @Override
    public void onDisable() {
        if (crystal != null) {
            crystal.close();
        }
    }
}
