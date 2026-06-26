package com.redecrystal.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka service registry. Every backend microservice (gateway, core-service,
 * and future auth/profile/inventory services) registers here so the gateway can
 * route to them by logical name ({@code lb://core-service}) without hardcoded hosts.
 */
@EnableEurekaServer
@SpringBootApplication
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
