package com.redecrystal.auth.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.redecrystal.auth.domain.PlayerAccount;
import com.redecrystal.auth.domain.PlayerAccountRepository;
import com.redecrystal.shared.web.ConflictException;
import com.redecrystal.shared.web.UnauthorizedException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthServiceChangePasswordTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private PlayerAccountRepository repository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlayerAccountRepository.class);
        service = new AuthService(repository, mock(JwtService.class), mock(StringRedisTemplate.class));
        when(repository.save(any(PlayerAccount.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void changesPasswordWhenCurrentMatches() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount account = new PlayerAccount(uuid, "Steve", false, encoder.encode("old-pass"));
        when(repository.findById(uuid)).thenReturn(Optional.of(account));

        service.changePassword(uuid, "old-pass", "new-pass");

        assertTrue(encoder.matches("new-pass", account.getPasswordHash()));
        assertFalse(encoder.matches("old-pass", account.getPasswordHash()));
        verify(repository).save(account);
    }

    @Test
    void rejectsWrongCurrentPassword() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount account = new PlayerAccount(uuid, "Steve", false, encoder.encode("old-pass"));
        when(repository.findById(uuid)).thenReturn(Optional.of(account));

        assertThrows(UnauthorizedException.class,
                () -> service.changePassword(uuid, "wrong", "new-pass"));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnknownAccount() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> service.changePassword(uuid, "x", "new-pass"));
    }

    @Test
    void rejectsPremiumAccount() {
        UUID uuid = UUID.randomUUID();
        PlayerAccount premium = new PlayerAccount(uuid, "Notch", true, null);
        when(repository.findById(uuid)).thenReturn(Optional.of(premium));

        assertThrows(ConflictException.class,
                () -> service.changePassword(uuid, "x", "new-pass"));
    }
}
