package com.redecrystal.auth.api;

import com.redecrystal.auth.application.AuthService;
import com.redecrystal.auth.application.IssuedSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Auth API: register, login and refresh — the only issuers of player JWTs. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record CredentialsRequest(@NotNull UUID uuid, @NotBlank String username,
                                     boolean premium, String password) {}

    public record RefreshRequest(@NotBlank String token) {}

    public record AccountStatusResponse(boolean registered, boolean premium) {}

    public record SessionResponse(String uuid, String username, boolean premium,
                                  String token, long expiresAt) {
        static SessionResponse from(IssuedSession s) {
            return new SessionResponse(s.uuid().toString(), s.username(), s.premium(),
                    s.token(), s.expiresAtEpochSec());
        }
    }

    @PostMapping("/register")
    public SessionResponse register(@RequestBody @NotNull CredentialsRequest body) {
        return SessionResponse.from(
                authService.register(body.uuid(), body.username(), body.premium(), body.password()));
    }

    @PostMapping("/login")
    public SessionResponse login(@RequestBody @NotNull CredentialsRequest body) {
        return SessionResponse.from(
                authService.login(body.uuid(), body.username(), body.premium(), body.password()));
    }

    @PostMapping("/refresh")
    public SessionResponse refresh(@RequestBody @NotNull RefreshRequest body) {
        return SessionResponse.from(authService.refresh(body.token()));
    }

    @GetMapping("/account/{uuid}")
    public AccountStatusResponse account(@PathVariable UUID uuid) {
        return authService.find(uuid)
                .map(a -> new AccountStatusResponse(true, a.isPremium()))
                .orElse(new AccountStatusResponse(false, false));
    }
}
