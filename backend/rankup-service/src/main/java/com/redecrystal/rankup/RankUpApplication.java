package com.redecrystal.rankup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the RankUP service (economy — and later rank/prestige/plot/…).
 * Bounded contexts live as packages under {@code com.redecrystal.rankup} following
 * Clean Architecture / DDD. Reachable only through the API Gateway.
 */
@EnableDiscoveryClient
@SpringBootApplication
public class RankUpApplication {

    public static void main(String[] args) {
        SpringApplication.run(RankUpApplication.class, args);
    }
}
