package com.meonggo.backend.post.service;

import com.meonggo.backend.photo.entity.AnimalPhoto;
import com.meonggo.backend.post.entity.AnimalCase;
import com.meonggo.backend.post.entity.AnimalCaseLocation;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.LocationType;
import com.meonggo.backend.post.entity.SourceType;
import com.meonggo.backend.post.entity.UserPost;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장이 끝난 사진과 검증·암호화가 끝난 메타데이터를 같은 DB 트랜잭션으로 기록한다. */
@Service
public class PostAggregateService {
    private final EntityManager entityManager;

    public PostAggregateService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public long reservePostId() {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "select nextval(pg_get_serial_sequence('animal_case','id'))",
                                        Long.class)
                                .getSingleResult())
                .longValue();
    }

    public long reservePhotoId() {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "select nextval(pg_get_serial_sequence('animal_photo','id'))",
                                        Long.class)
                                .getSingleResult())
                .longValue();
    }

    @Transactional
    public void persist(
            AnimalCase animalCase,
            UserPost post,
            List<AnimalCaseLocation> locations,
            List<AnimalPhoto> photos) {
        validate(animalCase, post, locations, photos);
        entityManager.persist(animalCase);
        entityManager.persist(post);
        locations.forEach(entityManager::persist);
        photos.forEach(entityManager::persist);
        entityManager.flush();
    }

    private void validate(
            AnimalCase animalCase,
            UserPost post,
            List<AnimalCaseLocation> locations,
            List<AnimalPhoto> photos) {
        var expected =
                animalCase.getCaseType() == CaseType.LOST
                        ? Set.of(LocationType.EVENT)
                        : Set.of(LocationType.EVENT, LocationType.CURRENT);
        if (animalCase.getSourceType() != SourceType.USER
                || !animalCase.getId().equals(post.getAnimalCaseId())
                || locations == null
                || locations.size() != expected.size()
                || !locations.stream()
                        .map(v -> v.getId().locationType())
                        .collect(Collectors.toSet())
                        .equals(expected)
                || locations.stream()
                        .anyMatch(v -> !v.getId().animalCaseId().equals(animalCase.getId()))
                || photos == null
                || photos.isEmpty()
                || photos.size() > 10) {
            throw new IllegalArgumentException("Invalid user post aggregate");
        }
        for (int index = 0; index < photos.size(); index++) {
            var photo = photos.get(index);
            if (!photo.getAnimalCaseId().equals(animalCase.getId())
                    || photo.getSortOrder() != index
                    || !"USER_UPLOAD".equals(photo.getStorageType())) {
                throw new IllegalArgumentException("Invalid ordered user photos");
            }
        }
    }
}
