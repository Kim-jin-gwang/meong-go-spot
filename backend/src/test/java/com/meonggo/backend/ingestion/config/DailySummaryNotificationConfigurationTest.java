package com.meonggo.backend.ingestion.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.firebase.FirebaseApp;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

class DailySummaryNotificationConfigurationTest {
    private final DailySummaryNotificationConfiguration configuration =
            new DailySummaryNotificationConfiguration();

    @ParameterizedTest
    @MethodSource("missingRequiredSettings")
    void rejectsMissingRequiredSettings(String credentialsPath, String projectId) {
        MockEnvironment environment = new MockEnvironment();
        environment
                .withProperty("fcm.daily-summary.credentials-path", credentialsPath)
                .withProperty("fcm.daily-summary.project-id", projectId);

        assertThatThrownBy(() -> configuration.dailySummaryFirebaseApp(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    void rejectsTimeoutOutsideAllowedRange(String timeout) {
        MockEnvironment environment =
                new MockEnvironment().withProperty("fcm.daily-summary.timeout", timeout);

        assertThatThrownBy(
                        () ->
                                configuration.dailySummaryPublisher(
                                        mock(FirebaseApp.class), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("FCM send timeout must be between 1ms and 10s")
                .hasNoCause();
    }

    @Test
    void rejectsMalformedTimeoutWithoutExposingInput() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("fcm.daily-summary.timeout", "private-invalid-timeout");

        assertThatThrownBy(
                        () ->
                                configuration.dailySummaryPublisher(
                                        mock(FirebaseApp.class), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("private-invalid-timeout");
    }

    private static Stream<Arguments> missingRequiredSettings() {
        return Stream.of(Arguments.of("", "project"), Arguments.of("credentials.json", ""));
    }

    private static Stream<String> invalidTimeouts() {
        return Stream.of("PT0S", "PT-1S", "PT20S");
    }
}
