package com.meonggo.backend.adoption.query;

import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;

/**
 * {@code regionCode} 는 5자리 시·군·구, 2자리 시·도 접두, 또는 null(전국)이다.
 *
 * <p>{@code sex} 는 {@code MALE}·{@code FEMALE} 또는 null(전체)이다. 고르면 원천 성별이 {@code UNKNOWN} 인 건은 제외한다
 * (api-spec AD1, 2026-09-25 추가).
 */
public record AdoptionListQuery(String regionCode, Species species, Sex sex, String cursor) {
    @Override
    public String toString() {
        return "AdoptionListQuery[redacted]";
    }
}
