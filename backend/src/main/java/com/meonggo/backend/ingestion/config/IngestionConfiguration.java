package com.meonggo.backend.ingestion.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionConfiguration {
    @Bean("dataSourceClock")
    Clock dataSourceClock() {
        return Clock.systemUTC();
    }
}
