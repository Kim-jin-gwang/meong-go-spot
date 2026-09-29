package com.meonggo.backend.photo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class AnimalPhoto {
    @Id private Long id;

    @Column(nullable = false)
    private Long animalCaseId;

    @Column(nullable = false, length = 20)
    private String storageType;

    @Column(nullable = false, columnDefinition = "text")
    private String storageUri;

    @Column(length = 50)
    private String contentType;

    private Long byteSize;
    private Integer widthPx;
    private Integer heightPx;

    @Column(nullable = false)
    private short sortOrder;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String checksumSha256;

    @Column(nullable = false)
    private Instant createdAt;

    protected AnimalPhoto() {}

    public static AnimalPhoto upload(
            long id,
            long caseId,
            int order,
            byte[] bytes,
            int width,
            int height,
            String checksum,
            Instant createdAt) {
        if (id <= 0
                || caseId <= 0
                || order < 0
                || order > 9
                || bytes == null
                || bytes.length == 0
                || width < 1
                || width > 4096
                || height < 1
                || height > 4096
                || checksum == null
                || !checksum.matches("[0-9a-f]{64}")
                || createdAt == null) {
            throw new IllegalArgumentException("Invalid photo metadata");
        }
        var photo = new AnimalPhoto();
        photo.id = id;
        photo.animalCaseId = caseId;
        photo.storageType = "USER_UPLOAD";
        photo.storageUri = "/data/user/images/" + caseId + "/" + id + ".jpg";
        photo.contentType = "image/jpeg";
        photo.byteSize = (long) bytes.length;
        photo.widthPx = width;
        photo.heightPx = height;
        photo.sortOrder = (short) order;
        photo.checksumSha256 = checksum;
        photo.createdAt = createdAt;
        return photo;
    }

    public Long getId() {
        return id;
    }

    public Long getAnimalCaseId() {
        return animalCaseId;
    }

    public String getStorageType() {
        return storageType;
    }

    public String getStorageUri() {
        return storageUri;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    @Override
    public String toString() {
        return "AnimalPhoto[redacted]";
    }
}
