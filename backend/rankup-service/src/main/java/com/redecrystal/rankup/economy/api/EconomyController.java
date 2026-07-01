package com.redecrystal.rankup.economy.api;

import com.redecrystal.rankup.economy.application.EconomyService;
import com.redecrystal.rankup.economy.domain.EconomyEntity;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Economy API: balance fetch/ensure, additive deltas, conditional debit, transfer, admin set. */
@RestController
@RequestMapping("/api/economy")
public class EconomyController {

    private final EconomyService economyService;

    public EconomyController(EconomyService economyService) {
        this.economyService = economyService;
    }

    public record EconomyResponse(String uuid, long money, long tokens, int version) {
        static EconomyResponse from(EconomyEntity e) {
            return new EconomyResponse(e.playerUuid().toString(), e.money(), e.tokens(), e.version());
        }
    }

    public record DeltaRequest(long delta, String source) {}

    public record DebitRequest(long cost, String reason) {}

    public record TransferRequest(UUID from, UUID to, long amount) {}

    public record SetRequest(long money, long tokens, int version) {}

    @GetMapping("/{uuid}")
    public EconomyResponse get(@PathVariable UUID uuid) {
        return EconomyResponse.from(economyService.get(uuid));
    }

    @PutMapping("/{uuid}")
    public EconomyResponse ensure(@PathVariable UUID uuid) {
        return EconomyResponse.from(economyService.ensure(uuid));
    }

    @PostMapping("/{uuid}/money")
    public EconomyResponse addMoney(@PathVariable UUID uuid, @RequestBody DeltaRequest body) {
        return EconomyResponse.from(economyService.addMoney(uuid, body.delta(), body.source()));
    }

    @PostMapping("/{uuid}/tokens")
    public EconomyResponse addTokens(@PathVariable UUID uuid, @RequestBody DeltaRequest body) {
        return EconomyResponse.from(economyService.addTokens(uuid, body.delta(), body.source()));
    }

    @PostMapping("/{uuid}/debit")
    public EconomyResponse debit(@PathVariable UUID uuid, @RequestBody DebitRequest body) {
        return EconomyResponse.from(economyService.debit(uuid, body.cost(), body.reason()));
    }

    @PostMapping("/transfer")
    public EconomyResponse transfer(@RequestBody @NotNull TransferRequest body) {
        return EconomyResponse.from(economyService.transfer(body.from(), body.to(), body.amount()));
    }

    @PutMapping("/{uuid}/set")
    public EconomyResponse set(@PathVariable UUID uuid, @RequestBody SetRequest body) {
        return EconomyResponse.from(economyService.set(uuid, body.money(), body.tokens(), body.version()));
    }
}
