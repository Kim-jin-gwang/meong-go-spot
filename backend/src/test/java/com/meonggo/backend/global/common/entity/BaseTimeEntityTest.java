package com.meonggo.backend.global.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BaseTimeEntityTest {

    @Autowired private AuditedItemRepository repository;

    @Test
    void savingRecordsCreatedAndUpdatedAt() {
        AuditedItem saved = repository.saveAndFlush(new AuditedItem("first"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void updatingAdvancesOnlyUpdatedAt() throws InterruptedException {
        AuditedItem saved = repository.saveAndFlush(new AuditedItem("before"));
        Instant createdAt = saved.getCreatedAt();
        Instant firstUpdatedAt = saved.getUpdatedAt();

        Thread.sleep(50); // Instant 해상도 차이를 보장하기 위한 최소 대기
        saved.rename("after");
        AuditedItem updated = repository.saveAndFlush(saved);

        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(firstUpdatedAt);
    }
}
