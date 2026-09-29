package com.meonggo.backend.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;

@Embeddable
public record LocationId(
        @Column(nullable = false) Long animalCaseId,
        @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
                LocationType locationType)
        implements Serializable {}
