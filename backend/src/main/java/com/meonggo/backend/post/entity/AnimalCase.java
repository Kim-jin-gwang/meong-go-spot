package com.meonggo.backend.post.entity;

import com.meonggo.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
public class AnimalCase extends BaseTimeEntity {
    @Id private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseStatus status;

    @Column(nullable = false)
    private boolean isMatchable;

    @Version private long version;

    @Column(nullable = false)
    private Instant listedAt;

    @Embedded private AnimalDetails details;
    private Instant closedAt;
    private Instant deletedAt;

    protected AnimalCase() {}

    public static AnimalCase user(long id, CaseType type, AnimalDetails details, Instant listedAt) {
        if (id <= 0) throw new IllegalArgumentException("Invalid case identity");
        var value = new AnimalCase();
        value.id = id;
        value.caseType = Objects.requireNonNull(type);
        value.sourceType = SourceType.USER;
        value.status = CaseStatus.ACTIVE;
        value.isMatchable = true;
        value.details = Objects.requireNonNull(details);
        value.listedAt = Objects.requireNonNull(listedAt);
        return value;
    }

    public Long getId() {
        return id;
    }

    public CaseType getCaseType() {
        return caseType;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public AnimalDetails getDetails() {
        return details;
    }
}
