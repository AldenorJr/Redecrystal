package com.redecrystal.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway's authentication gate — the single place requests are
 * authenticated in the whole backend. Every {@code /api/**} request must carry a
 * valid {@code Authorization: Bearer <service-token>}; everything else (actuator
 * health, the Eureka-less public paths) passes through.
 *
 * <p>Runs as a {@link GlobalFilter} so it applies to all routes before they are
 * forwarded to discovered services. Swappable for JWT validation later without
 * touching downstream services.
 */
@Component
public class ServiceTokenAuthFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${redecrystal.security.service-token}")
    private String serviceToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Only guard the API surface; health/info stay public for probes.
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)
                && !serviceToken.isBlank()
                && serviceToken.equals(header.substring(BEARER_PREFIX.length()).trim())) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        // Run before routing/load-balancing filters.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
