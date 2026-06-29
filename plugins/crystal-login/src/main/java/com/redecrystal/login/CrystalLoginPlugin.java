package com.redecrystal.login;

import com.redecrystal.core.CrystalConfig;
import com.redecrystal.core.CrystalCore;
import com.redecrystal.core.http.AccountStatus;
import com.redecrystal.core.http.AuthToken;
import com.redecrystal.core.http.BackendHttpClient.BackendException;
import com.redecrystal.core.http.RemoteConfig;
import com.redecrystal.core.messaging.KafkaTopics;
import com.redecrystal.login.listener.CommandFilterListener;
import com.redecrystal.login.listener.LoginGuard;
import com.redecrystal.login.listener.LoginScoreboard;
import com.redecrystal.login.listener.PlayerJoinListener;
import com.redecrystal.login.listener.PlayerQuitListener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
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
public final class CrystalLoginPlugin extends JavaPlugin {

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
    private final Set<UUID> editing = ConcurrentHashMap.newKeySet();
    private volatile Location loginSpawn;

    @Override
    public void onEnable() {
        this.crystal = CrystalCore.bootstrap(CrystalConfig.fromEnv());
        crystal.configProvider().preload(CONFIG_KEY);
        this.loginTimeoutSeconds = crystal.configProvider().get(CONFIG_KEY)
                .integer("loginTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
        applyLoginSpawnConfig(crystal.configProvider().get(CONFIG_KEY));
        crystal.configProvider().onChange(CONFIG_KEY, updated -> {
            applyLoginSpawnConfig(updated);
            getLogger().info("Hot-reloaded login spawn: " + describe(loginSpawn));
        });
        crystal.registerThisServer(crystal.configProvider().get(CONFIG_KEY).integer("maxPlayers", 200));
        crystal.startHeartbeat(() -> getServer().getOnlinePlayers().size(),
                () -> getServer().getTPS()[0]);

        getServer().getMessenger().registerOutgoingPluginChannel(this, AUTH_CHANNEL);
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this), this);
        pm.registerEvents(new CommandFilterListener(this), this);
        pm.registerEvents(new PlayerQuitListener(this), this);
        pm.registerEvents(new LoginGuard(this, crystal), this);
        LoginScoreboard scoreboard = new LoginScoreboard(this, crystal);
        pm.registerEvents(scoreboard, this);
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

    // ── login spawn (config-driven; set live with /login setspawn) ──

    /** Parse the spawn from the {@code login} config; null when unset. */
    private void applyLoginSpawnConfig(RemoteConfig cfg) {
        this.loginSpawn = parseSpawn(cfg.value("spawn"));
    }

    private Location parseSpawn(Object raw) {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        if (world == null || !(raw instanceof Map<?, ?> m) || m.get("x") == null) {
            return null;
        }
        return new Location(world, num(m.get("x")), num(m.get("y")), num(m.get("z")),
                (float) num(m.get("yaw")), (float) num(m.get("pitch")));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** The configured login spawn, or null when none is set. */
    public Location getLoginSpawn() {
        return loginSpawn == null ? null : loginSpawn.clone();
    }

    /** Teleport the player to the configured login spawn, if any. */
    public void applyLoginSpawn(Player player) {
        Location s = getLoginSpawn();
        if (s != null) {
            player.teleport(s);
        }
    }

    public boolean isEditing(UUID uuid) {
        return editing.contains(uuid);
    }

    private static String describe(Location l) {
        return l == null ? "unset"
                : (Math.round(l.getX()) + "," + Math.round(l.getY()) + "," + Math.round(l.getZ()));
    }

    // ── staff edit mode (/login manutencao): unfreeze one staff member to fly + set spawn ──

    /**
     * Enter edit mode after verifying the caller's password against the backend.
     * The login server is offline-mode, so the permission alone is keyed to a
     * spoofable username — we never unfreeze a staff member who can't prove the
     * account. Verification reuses the normal login call but discards the token:
     * no session, no JWT, no proxy routing.
     */
    public void enterEditMode(Player player, String password) {
        UUID uuid = player.getUniqueId();
        if (isEditing(uuid)) {
            return; // already editing
        }
        String name = player.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                crystal.backend().login(uuid.toString(), name, false, password); // throws on bad credentials
                getServer().getScheduler().runTask(this, () -> applyEditMode(player));
            } catch (BackendException e) {
                send(player, "<red>Senha incorreta. Use <white>/login manutencao <senha><red>.");
            }
        });
    }

    /** Unfreeze the staff member after a verified password (main thread). */
    private void applyEditMode(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        editing.add(uuid);
        cancelTimeout(uuid);
        player.clearActivePotionEffects();
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        send(player, "<#b14aed>» <white>Modo edição <green>ON<white>. Voe até o local e use <#b14aed>/login setspawn<white>.");
    }

    /** Leave edit mode: restore the frozen login state and re-show the auth prompt. */
    public void exitEditMode(Player player) {
        if (!editing.remove(player.getUniqueId())) {
            return; // not editing
        }
        freeze(player);
        applyLoginSpawn(player);
        scheduleLoginTimeout(player);
        resolveAndPrompt(player);
        send(player, "<#b14aed>» <white>Modo edição <red>OFF<white>.");
    }

    /** Re-apply the frozen login state (mirror of PlayerJoinListener). */
    private void freeze(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.getInventory().clear();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    /** Persist the caller's current location as the login spawn for the whole network. */
    public void setLoginSpawn(Player player) {
        Location loc = player.getLocation();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Map<String, Object> cfg = new HashMap<>(crystal.configProvider().get(CONFIG_KEY).config());
                cfg.put("spawn", Map.of(
                        "x", round(loc.getX()), "y", round(loc.getY()), "z", round(loc.getZ()),
                        "yaw", round(loc.getYaw()), "pitch", round(loc.getPitch())));
                crystal.backend().putConfig(CONFIG_KEY, cfg);
                send(player, "<green>Spawn do login definido para todos os servidores de login.");
            } catch (Exception e) {
                send(player, "<red>Falha ao salvar o spawn. Tente novamente.");
                getLogger().warning("setLoginSpawn failed for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── join: freeze + prompt ──

    /** Arm the login timeout that kicks a player who never authenticates. */
    public void scheduleLoginTimeout(Player player) {
        UUID uuid = player.getUniqueId();
        timeouts.put(uuid, getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !isAuthenticated(uuid)) {
                player.kick(MM.deserialize("<red>Tempo de login esgotado. Reconecte para tentar novamente."));
            }
        }, loginTimeoutSeconds * 20L));
    }

    /** Off the main thread, tailor the auth prompt to whether the account exists. */
    public void resolveAndPrompt(Player player) {
        UUID uuid = player.getUniqueId();
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

    /** Validate the password and submit the credentials to the backend. */
    public void authenticate(Player player, String password, boolean register) {
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

    /** Drop the player's local login state on quit. */
    public void clearLocalState(UUID uuid) {
        // Local state only — the proxy's DisconnectEvent revokes the real session,
        // so we must NOT clear Redis here (the player may just be moving to a lobby).
        authenticated.remove(uuid);
        attempts.remove(uuid);
        editing.remove(uuid);
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
