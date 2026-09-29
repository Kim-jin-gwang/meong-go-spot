package com.meonggo.backend.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class UserPost {
    @Id private Long animalCaseId;

    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private UUID clientRequestId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(length = 30)
    private String closeReason;

    protected UserPost() {}

    public UserPost(long caseId, long memberId, UUID requestId, String requestHash) {
        if (caseId <= 0
                || memberId <= 0
                || requestId == null
                || requestHash == null
                || !requestHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid post identity");
        this.animalCaseId = caseId;
        this.memberId = memberId;
        this.clientRequestId = requestId;
        this.requestHash = requestHash;
    }

    public Long getAnimalCaseId() {
        return animalCaseId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
