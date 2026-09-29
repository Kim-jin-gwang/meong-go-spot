package com.meonggo.backend.push.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.meonggo.backend.push.notification.ChatNotificationPublisher;
import com.meonggo.backend.push.notification.FirebaseChatNotificationPublisher;
import com.meonggo.backend.push.repository.ChatNotificationOutboxRepository;
import com.meonggo.backend.push.security.PushTokenProtection;
import com.meonggo.backend.push.service.ChatNotificationOutboxScheduler;
import com.meonggo.backend.push.service.ChatNotificationOutboxService;
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
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fcm.chat.enabled", havingValue = "true")
public class ChatNotificationConfiguration {
    @Bean(destroyMethod = "delete")
    FirebaseApp chatFirebaseApp(Environment environment) {
        String path = required(environment, "fcm.chat.credentials-path");
        String projectId = required(environment, "fcm.chat.project-id");
        try (InputStream credentials = Files.newInputStream(Path.of(path))) {
            FirebaseOptions options =
                    FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(credentials))
                            .setProjectId(projectId)
                            .build();
            return FirebaseApp.initializeApp(options, "chat-message");
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid FCM chat configuration");
        }
    }

    @Bean
    ChatNotificationPublisher chatNotificationPublisher(
            FirebaseApp chatFirebaseApp, Environment environment) {
        Duration timeout = duration(environment, "fcm.chat.timeout", "PT10S");
        requireRange(timeout, Duration.ofMillis(1), Duration.ofSeconds(10));
        return new FirebaseChatNotificationPublisher(
                FirebaseMessaging.getInstance(chatFirebaseApp), timeout);
    }

    @Bean
    ChatNotificationOutboxService chatNotificationOutboxService(
            ChatNotificationOutboxRepository outbox,
            PushTokenProtection protection,
            ChatNotificationPublisher publisher,
            @Qualifier("dataSourceClock") Clock clock,
            Environment environment,
            PlatformTransactionManager manager) {
        Duration lease = duration(environment, "fcm.chat.lease-duration", "PT30S");
        requireRange(lease, Duration.ofSeconds(5), Duration.ofMinutes(5));
        return new ChatNotificationOutboxService(
                outbox, protection, publisher, clock, lease, manager);
    }

    @Bean
    ChatNotificationOutboxScheduler chatNotificationOutboxScheduler(
            ChatNotificationOutboxService service) {
        return new ChatNotificationOutboxScheduler(service);
    }

    private static Duration duration(Environment environment, String key, String fallback) {
        try {
            return Duration.parse(environment.getProperty(key, fallback));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid FCM chat duration configuration");
        }
    }

    private static void requireRange(Duration value, Duration minimum, Duration maximum) {
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalStateException("FCM chat duration is outside the allowed range");
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalStateException("Missing FCM chat configuration");
        return value;
    }
}
