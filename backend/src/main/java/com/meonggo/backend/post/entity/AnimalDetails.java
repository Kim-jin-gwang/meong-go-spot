package com.meonggo.backend.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import java.time.LocalTime;

@Embeddable
public record AnimalDetails(
        @Column(length = 50) String name,
        @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) Species species,
        @Column(length = 100) String breedName,
        @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) Sex sex,
        @Column(length = 100) String color,
        @Column(nullable = false) LocalDate eventDate,
        LocalTime eventTime,
        @Column(columnDefinition = "text") String featureText) {
    @Override
    public String toString() {
        return "AnimalDetails[redacted]";
    }
}
