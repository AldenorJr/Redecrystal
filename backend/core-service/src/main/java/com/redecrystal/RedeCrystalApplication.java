package com.redecrystal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the RedeCrystal core service (Config Service + Service
 * Discovery). Bounded contexts are organised as packages under
 * {@code com.redecrystal} following Clean Architecture / DDD. Scheduling is
 * enabled for the Service Discovery stale-heartbeat reaper.
 */
@EnableScheduling
@SpringBootApplication
public class RedeCrystalApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedeCrystalApplication.class, args);
    }
}
