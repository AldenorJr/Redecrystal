package com.redecrystal.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API Gateway — the single entry point to the RedeCrystal backend and the only
 * component responsible for authentication. It validates the service token,
 * then routes authenticated traffic to downstream services discovered via
 * Eureka ({@code lb://core-service}). Downstream services trust gateway traffic
 * and run no security of their own.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
