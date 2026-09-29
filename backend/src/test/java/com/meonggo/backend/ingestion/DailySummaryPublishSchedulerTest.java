package com.meonggo.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.meonggo.backend.ingestion.service.DailySummaryPublishScheduler;
import com.meonggo.backend.ingestion.service.DailySummaryPublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DailySummaryPublishSchedulerTest {
    @Test
    void logsFailureTypesAndSanitizedStackWithoutProviderDetails(CapturedOutput output) {
        DailySummaryPublishService service = mock(DailySummaryPublishService.class);
        doThrow(
                        new IllegalStateException(
                                "private-provider-message",
                                new IllegalArgumentException("private-credential-value")))
                .when(service)
                .publishNext();

        new DailySummaryPublishScheduler(service).publishNext();

        assertThat(output)
                .contains("code=FCM-001")
                .contains("exceptionType=IllegalStateException")
                .contains("rootCauseType=IllegalArgumentException")
                .contains("details redacted")
                .doesNotContain("private-provider-message")
                .doesNotContain("private-credential-value");
    }
}
