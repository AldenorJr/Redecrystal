package com.redecrystal.login;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.AccountStatus;
import com.redecrystal.core.http.AuthToken;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.redecrystal.core.messaging.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Login server. A freshly-connected player lands here frozen and must register
 * or log in. On success the backend issues a player JWT; we mirror the session
 * to Redis, hand the token to the proxy over the {@code crystal:auth} channel,
 * and the proxy routes the player to a lobby. No player reaches a backend server
 * without passing through here — the proxy gate (crystal-bungee) enforces it.
 *
 * <p>The network runs cracked ({@code online-mode=false}), so every player must
 * set and prove a password. Premium accounts are supported by the backend for
 * when online-mode is enabled, but are not auto-detected in this slice.
 */
public final class CrystalLoginPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String AUTH_CHANNEL = "crystal:auth";
    private static final String SESSION_PREFIX = "session:";
    private static final String CONFIG_KEY = "login";
    private static final int MIN_PASSWORD_LENGTH = 3;
    private static final int MAX_PASSWORD_LENGTH = 64;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final Duration SESSION_TTL = Duration.ofHours(6);

    private CrystalCore crystal;
    private int loginTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> timeouts = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(CONFIG_KEY);
        this.loginTimeoutSeconds = crystal.configProvider().get(CONFIG_KEY)
                .integer("loginTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        crystal.registerThisServer(crystal.configProvider().get(CONFIG_KEY).integer("maxPlayers", 200));
        crystal.startHeartbeat(() -> getServer().getOnlinePlayers().size(),
                () -> getServer().getTPS()[0]);

        getServer().getMessenger().registerOutgoingPluginChannel(this, AUTH_CHANNEL);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new LoginGuard(this, crystal), this);
        LoginScoreboard scoreboard = new LoginScoreboard(this, crystal);
        getServer().getPluginManager().registerEvents(scoreboard, this);
        scoreboard.start();
        getLogger().info("CrystalLogin enabled (timeout=" + loginTimeoutSeconds + "s).");
    }

    @Override
    public void onDisable() {
        timeouts.values().forEach(BukkitTask::cancel);
        timeouts.clear();
        if (crystal != null) {
            crystal.close();
        }
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    // ── join: freeze + prompt ──

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.getInventory().clear();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));

        // Kick the player if they sit on the login screen too long (frees the slot).
        timeouts.put(uuid, getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !isAuthenticated(uuid)) {
                player.kick(MM.deserialize("<red>Tempo de login esgotado. Reconecte para tentar novamente."));
            }
        }, loginTimeoutSeconds * 20L));

        // Tailor the prompt to whether the account exists (off the main thread).
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean registered;
            try {
                AccountStatus status = crystal.backend().accountStatus(uuid.toString());
                registered = status.registered();
            } catch (BackendException e) {
                registered = false; // best-effort; the generic prompt still works
                getLogger().warning("Account status lookup failed for " + player.getName() + ": " + e.getMessage());
            }
            final boolean exists = registered;
            getServer().getScheduler().runTask(this, () -> promptAuth(player, exists));
        });
    }

    private void promptAuth(Player player, boolean registered) {
        if (!player.isOnline() || isAuthenticated(player.getUniqueId())) {
            return;
        }
        player.showTitle(Title.title(
                MM.deserialize("<gradient:#b14aed:#8e2de2><bold>REDECRYSTAL</bold></gradient>"),
                MM.deserialize(registered ? "<gray>Use <white>/login <senha>"
                        : "<gray>Use <white>/registrar <senha> <senha>")));
        if (registered) {
            send(player, "<#b14aed>» <white>Bem-vindo de volta! Entre com <#b14aed>/login <senha>");
        } else {
            send(player, "<#b14aed>» <white>Crie sua conta com <#b14aed>/registrar <senha> <repita a senha>");
        }
    }

    // ── auth commands (intercepted so the password is never logged or broadcast) ──

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isAuthenticated(player.getUniqueId())) {
            return; // already in; let it through (they are being routed away anyway)
        }
        event.setCancelled(true); // nothing but the auth commands is allowed pre-login

        String[] parts = event.getMessage().trim().split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/login", "/l" -> {
                if (parts.length < 2) {
                    send(player, "<red>Uso: /login <senha>");
                    return;
                }
                authenticate(player, parts[1], false);
            }
            case "/registrar", "/reg" -> {
                if (parts.length < 3) {
                    send(player, "<red>Uso: /registrar <senha> <repita a senha>");
                    return;
                }
                if (!parts[1].equals(parts[2])) {
                    send(player, "<red>As senhas não conferem. Tente novamente.");
                    return;
                }
                authenticate(player, parts[1], true);
            }
            default -> send(player, "<red>Faça login primeiro: <white>/login <senha> <red>ou <white>/registrar <senha> <senha>");
        }
    }

    private void authenticate(Player player, String password, boolean register) {
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            send(player, "<red>A senha deve ter entre " + MIN_PASSWORD_LENGTH + " e " + MAX_PASSWORD_LENGTH + " caracteres.");
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                AuthToken token = register
                        ? crystal.backend().register(uuid.toString(), name, false, password)
                        : crystal.backend().login(uuid.toString(), name, false, password);
                getServer().getScheduler().runTask(this, () -> onAuthSuccess(player, token));
            } catch (BackendException e) {
                getServer().getScheduler().runTask(this, () -> onAuthError(player, e, register));
            }
        });
    }

    private void onAuthSuccess(Player player, AuthToken token) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        authenticated.add(uuid);
        attempts.remove(uuid);
        cancelTimeout(uuid);

        // Presence marker (defensive lobby check) + hand the JWT to the proxy, which
        // verifies it and routes the player to a lobby. Kafka event is for history.
        crystal.redis().setex(SESSION_PREFIX + uuid, player.getName(), SESSION_TTL);
        player.sendPluginMessage(this, AUTH_CHANNEL, token.token().getBytes(StandardCharsets.UTF_8));
        crystal.kafka().publish(KafkaTopics.PLAYER_AUTHENTICATED, uuid.toString(), Map.of(
                "player", player.getName(), "uuid", uuid.toString(), "premium", token.premium()));

        player.clearActivePotionEffects();
        player.showTitle(Title.title(
                MM.deserialize("<green><bold>Login efetuado!</bold>"),
                MM.deserialize("<gray>Conectando ao lobby...")));
        getLogger().info("Authenticated " + player.getName() + "; proxy will route to lobby.");
    }

    private void onAuthError(Player player, BackendException e, boolean register) {
        if (!player.isOnline()) {
            return;
        }
        switch (e.statusCode()) {
            case 409 -> send(player, "<red>Esta conta já existe. Use <white>/login <senha><red>.");
            case 401 -> send(player, register
                    ? "<red>Não foi possível registrar. Tente novamente."
                    : "<red>Senha incorreta ou conta não registrada. Use <white>/registrar <senha> <senha><red>.");
            default -> {
                send(player, "<red>Serviço de login indisponível. Tente novamente em instantes.");
                getLogger().warning("Auth backend error for " + player.getName() + ": " + e.getMessage());
                return; // backend outage isn't a failed credential attempt
            }
        }
        int count = attempts.merge(player.getUniqueId(), 1, Integer::sum);
        if (count >= MAX_LOGIN_ATTEMPTS) {
            player.kick(MM.deserialize("<red>Muitas tentativas. Reconecte para tentar novamente."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Local state only — the proxy's DisconnectEvent revokes the real session,
        // so we must NOT clear Redis here (the player may just be moving to a lobby).
        authenticated.remove(uuid);
        attempts.remove(uuid);
        cancelTimeout(uuid);
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = timeouts.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void send(Player player, String mini) {
        player.sendMessage(MM.deserialize(mini));
    }
}
