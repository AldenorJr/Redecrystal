package com.redecrystal.rankup.economy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.redecrystal.rankup.economy.domain.EconomyEntity;
import com.redecrystal.rankup.economy.domain.EconomyRepository;
import com.redecrystal.rankup.shared.messaging.EventPublisher;
import com.redecrystal.rankup.shared.web.ConflictException;
import com.redecrystal.rankup.shared.web.InsufficientFundsException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class EconomyServiceTest {

    private EconomyRepository repository;
    private EventPublisher events;
    private EconomyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(EconomyRepository.class);
        events = mock(EventPublisher.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // Redis is fail-open in the service; stub the op accessors so cache() no-ops.
        when(redis.opsForHash()).thenReturn(mock(HashOperations.class));
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        service = new EconomyService(repository, redis, events);
        when(repository.save(any(EconomyEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private EconomyEntity row(UUID uuid, long money, long tokens, int version) {
        return new EconomyEntity(uuid, money, tokens, version);
    }

    @Test
    void addMoneyAppliesAdditiveDelta() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, 100, 0, 0)))  // ensure()
                .thenReturn(Optional.of(row(uuid, 150, 0, 0))); // re-read after update
        when(repository.addMoney(uuid, 50)).thenReturn(1);

        assertEquals(150, service.addMoney(uuid, 50, "test").money());
        verify(repository).addMoney(uuid, 50);
    }

    @Test
    void debitSucceedsWhenFundsSuffice() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid))
                .thenReturn(Optional.of(row(uuid, 100, 0, 0)))
                .thenReturn(Optional.of(row(uuid, 40, 0, 1)));
        when(repository.debit(uuid, 60)).thenReturn(1);

        assertEquals(40, service.debit(uuid, 60, "buy").money());
    }

    @Test
    void debitThrows422WhenInsufficient() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.of(row(uuid, 10, 0, 0)));
        when(repository.debit(uuid, 60)).thenReturn(0);

        assertThrows(InsufficientFundsException.class, () -> service.debit(uuid, 60, "buy"));
    }

    @Test
    void transferIsAtomicAndCreditsNothingOnInsufficient() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(repository.findById(any())).thenReturn(Optional.of(row(from, 10, 0, 0)));
        when(repository.debit(eq(from), anyLong())).thenReturn(0);

        assertThrows(InsufficientFundsException.class, () -> service.transfer(from, to, 100));
        verify(repository, never()).addMoney(eq(to), anyLong());
    }

    @Test
    void setThrows409OnStaleVersion() {
        UUID uuid = UUID.randomUUID();
        when(repository.findById(uuid)).thenReturn(Optional.of(row(uuid, 100, 0, 5)));

        assertThrows(ConflictException.class, () -> service.set(uuid, 999, 0, 3));
        verify(repository, never()).save(any());
    }
}
