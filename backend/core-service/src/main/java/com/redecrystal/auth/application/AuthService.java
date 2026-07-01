package com.redecrystal.auth.application;

import com.redecrystal.auth.domain.PlayerAccount;
import com.redecrystal.auth.domain.PlayerAccountRepository;
import com.redecrystal.shared.web.ConflictException;
import com.redecrystal.shared.web.UnauthorizedException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login/registration + JWT issuance. PostgreSQL is the source of truth for
 * credentials; on success the active session id is mirrored to Redis
 * ({@code jwt:{uuid}}) so the proxy can revoke a session before token expiry.
 *
 * <p>Premium (Mojang-authenticated) accounts never carry a password — trust is
 * delegated to Mojang and a token is issued on sight. Cracked accounts must
 * register a password and prove it on every login (bcrypt).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String JWT_KEY_PREFIX = "jwt:";

    private final PlayerAccountRepository repository;
    private final JwtService jwtService;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(PlayerAccountRepository repository, JwtService jwtService, StringRedisTemplate redis) {
        this.repository = repository;
        this.jwtService = jwtService;
        this.redis = redis;
    }

    /**
     * Register a new account. Premium accounts ignore the password; cracked
     * accounts require one. Fails with 409 if the username already exists.
     */
    @Transactional
    public IssuedSession register(UUID uuid, String username, boolean premium, String password) {
        if (repository.findByUsernameLower(username.toLowerCase()).isPresent()
                || repository.existsById(uuid)) {
            throw new ConflictException("conta já existe");
        }
        String hash = null;
        if (!premium) {
            requirePassword(password);
            hash = passwordEncoder.encode(password);
        }
        PlayerAccount account = new PlayerAccount(uuid, username, premium, hash);
        repository.save(account);
        return issueFor(account);
    }

    /**
     * Authenticate an existing account. Premium accounts are auto-created on
     * first login; cracked accounts must already exist and present the password.
     */
    @Transactional
    public IssuedSession login(UUID uuid, String username, boolean premium, String password) {
        PlayerAccount account = repository.findById(uuid).orElse(null);

        if (account == null) {
            if (premium) {
                // First time we see a premium player — trust Mojang and register them.
                account = new PlayerAccount(uuid, username, true, null);
                repository.save(account);
                return issueFor(account);
            }
            throw new UnauthorizedException("conta não registrada");
        }

        if (!account.isPremium()) {
            requirePassword(password);
            if (!account.hasPassword() || !passwordEncoder.matches(password, account.getPasswordHash())) {
                throw new UnauthorizedException("senha incorreta");
            }
        }

        account.touchLogin(username);
        repository.save(account);
        return issueFor(account);
    }

    /**
     * Change a cracked account's password after proving the current one. Premium
     * accounts have no password and are rejected. The active session stays valid
     * (no token is re-issued).
     */
    @Transactional
    public void changePassword(UUID uuid, String currentPassword, String newPassword) {
        PlayerAccount account = repository.findById(uuid)
                .orElseThrow(() -> new UnauthorizedException("conta não registrada"));
        if (account.isPremium()) {
            throw new ConflictException("conta premium não usa senha");
        }
        requirePassword(newPassword);
        if (!account.hasPassword() || !passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new UnauthorizedException("senha atual incorreta");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        repository.save(account);
    }

    /** Look up an account by id — used by the login server to tailor its prompt. */
    @Transactional(readOnly = true)
    public Optional<PlayerAccount> find(UUID uuid) {
        return repository.findById(uuid);
    }

    /** Re-issue a token for a still-valid token (used near expiry on server switch). */
    @Transactional(readOnly = true)
    public IssuedSession refresh(String token) {
        TokenClaims claims = jwtService.verify(token)
                .orElseThrow(() -> new UnauthorizedException("token inválido ou expirado"));
        PlayerAccount account = repository.findById(claims.uuid())
                .orElseThrow(() -> new UnauthorizedException("conta não encontrada"));
        return issueFor(account);
    }

    private IssuedSession issueFor(PlayerAccount account) {
        JwtService.IssuedToken issued = jwtService.issue(account.getId(), account.getUsername(), account.isPremium());
        mirrorToRedis(account.getId(), issued);
        return new IssuedSession(account.getId(), account.getUsername(), account.isPremium(),
                issued.token(), issued.expiresAtEpochSec());
    }

    /** Record the live session id for revocation; a Redis hiccup must not block login. */
    private void mirrorToRedis(UUID uuid, JwtService.IssuedToken issued) {
        try {
            long ttl = issued.expiresAtEpochSec() - Instant.now().getEpochSecond();
            if (ttl > 0) {
                redis.opsForValue().set(JWT_KEY_PREFIX + uuid, issued.sessionId(), Duration.ofSeconds(ttl));
            }
        } catch (Exception e) {
            log.warn("Failed to mirror session to Redis for {}", uuid, e);
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new UnauthorizedException("senha obrigatória");
        }
    }
}
