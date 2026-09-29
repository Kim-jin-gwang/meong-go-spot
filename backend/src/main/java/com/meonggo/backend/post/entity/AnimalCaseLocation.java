package com.meonggo.backend.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class AnimalCaseLocation {
    @EmbeddedId private LocationId id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 5)
    private String regionCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 10)
    private String emdCode;

    @Column(nullable = false, length = 255)
    private String publicLocation;

    @Column(columnDefinition = "text")
    private String exactLocationCiphertext;

    @Column(nullable = false)
    private boolean exactLocationVisible;

    @Column(length = 20)
    private String disclosurePolicyVersion;

    private Instant disclosureConsentedAt;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    protected AnimalCaseLocation() {}

    /** 암호화와 행정구역 사전 검증을 끝낸 서비스 내부 값만 받는다. */
    public AnimalCaseLocation(
            long caseId,
            LocationType role,
            String regionCode,
            String emdCode,
            String publicLocation,
            String ciphertext,
            boolean visible,
            String policyVersion,
            Instant consentedAt,
            BigDecimal latitude,
            BigDecimal longitude) {
        if (caseId <= 0
                || role == null
                || regionCode == null
                || !regionCode.matches("[0-9]{5}")
                || (emdCode != null
                        && (!emdCode.matches("[0-9]{10}") || !emdCode.startsWith(regionCode)))
                || publicLocation == null
                || publicLocation.isBlank()
                || (latitude == null) != (longitude == null)
                || (visible
                        && (ciphertext == null
                                || ciphertext.isBlank()
                                || !"exact-location-v1".equals(policyVersion)
                                || consentedAt == null))) {
            throw new IllegalArgumentException("Invalid protected location");
        }
        this.id = new LocationId(caseId, role);
        this.regionCode = regionCode;
        this.emdCode = emdCode;
        this.publicLocation = publicLocation;
        this.exactLocationCiphertext = ciphertext;
        this.exactLocationVisible = visible;
        this.disclosurePolicyVersion = policyVersion;
        this.disclosureConsentedAt = consentedAt;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public LocationId getId() {
        return id;
    }

    @Override
    public String toString() {
        return "AnimalCaseLocation[redacted]";
    }
}
