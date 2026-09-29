package com.meonggo.backend.ingestion.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.meonggo.backend.ingestion.notification.DailySummaryPublisher;
import com.meonggo.backend.ingestion.notification.FirebaseDailySummaryPublisher;
import com.meonggo.backend.ingestion.repository.DailySummaryPublishRepository;
import com.meonggo.backend.ingestion.service.DailySummaryPublishScheduler;
import com.meonggo.backend.ingestion.service.DailySummaryPublishService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fcm.daily-summary.enabled", havingValue = "true")
@EnableScheduling
public class DailySummaryNotificationConfiguration {
    @Bean(destroyMethod = "delete")
    FirebaseApp dailySummaryFirebaseApp(Environment environment) {
        String credentialsPath = required(environment, "fcm.daily-summary.credentials-path");
        String projectId = required(environment, "fcm.daily-summary.project-id");
        try (InputStream credentials = Files.newInputStream(Path.of(credentialsPath))) {
            FirebaseOptions options =
                    FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(credentials))
                            .setProjectId(projectId)
                            .build();
            return FirebaseApp.initializeApp(options, "daily-intake-summary");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Invalid FCM daily summary configuration");
        }
    }

    @Bean
    DailySummaryPublisher dailySummaryPublisher(
            FirebaseApp dailySummaryFirebaseApp, Environment environment) {
        Duration timeout = timeout(environment);
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalStateException("FCM send timeout must be between 1ms and 10s");
        return new FirebaseDailySummaryPublisher(
                FirebaseMessaging.getInstance(dailySummaryFirebaseApp), timeout);
    }

    private static Duration timeout(Environment environment) {
        try {
            return Duration.parse(environment.getProperty("fcm.daily-summary.timeout", "PT10S"));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid FCM send timeout");
        }
    }

    @Bean
    DailySummaryPublishService dailySummaryPublishService(
            DailySummaryPublishRepository runs,
            DailySummaryPublisher publisher,
            @Qualifier("dataSourceClock") Clock clock,
            PlatformTransactionManager transactions) {
        return new DailySummaryPublishService(runs, publisher, clock, transactions);
    }

    @Bean
    DailySummaryPublishScheduler dailySummaryPublishScheduler(DailySummaryPublishService service) {
        return new DailySummaryPublishScheduler(service);
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "").trim();
        if (value.isEmpty())
            throw new IllegalStateException("Missing FCM daily summary configuration");
        return value;
    }
}
