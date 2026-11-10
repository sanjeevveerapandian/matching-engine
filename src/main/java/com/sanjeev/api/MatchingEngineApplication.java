package com.sanjeev.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the REST API: {@code mvn spring-boot:run}. Spring scans only this package and below, so
 * the engine and infrastructure packages are plain Java that Spring never sees.
 */
@SpringBootApplication
public class MatchingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchingEngineApplication.class, args);
    }
}
