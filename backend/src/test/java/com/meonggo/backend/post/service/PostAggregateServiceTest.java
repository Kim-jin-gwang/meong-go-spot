package com.meonggo.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.photo.entity.AnimalPhoto;
import com.meonggo.backend.post.entity.AnimalCase;
import com.meonggo.backend.post.entity.AnimalCaseLocation;
import com.meonggo.backend.post.entity.AnimalDetails;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.LocationType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.entity.UserPost;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PostAggregateServiceTest {
    @Autowired private PostAggregateService posts;
    @Autowired private JdbcTemplate jdbc;
    private long memberId;
    private long postId;

    @BeforeEach
    void setup() {
        memberId =
                jdbc.queryForObject(
                        """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                phone_ciphertext,phone_lookup_hash,phone_verified_at,
                privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values (?, 'fixture', '작성자', 'ACTIVE', now(), now(),
                'test-envelope', ?, now(), true, 'privacy-collection-v1', now()) returning id
                """,
                        Long.class,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
        postId = posts.reservePostId();
    }

    @Test
    void lostPersistsOneEventAndOrderedPhotos() {
        save(CaseType.LOST, List.of(location(LocationType.EVENT)), 2);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from animal_case_location where animal_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForList(
                                "select sort_order from animal_photo where animal_case_id=? order by sort_order",
                                Integer.class,
                                postId))
                .containsExactly(0, 1);
    }

    @Test
    void shelteringRequiresBothRolesAndAcceptsTenPhotos() {
        assertThatThrownBy(
                        () -> save(CaseType.SHELTERING, List.of(location(LocationType.EVENT)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        save(
                CaseType.SHELTERING,
                List.of(location(LocationType.EVENT), location(LocationType.CURRENT)),
                10);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from animal_photo where animal_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(10);
    }

    @Test
    void rejectsLostCurrentDuplicateRolesAndMissingPhotosBeforeWriting() {
        assertThatThrownBy(
                        () ->
                                save(
                                        CaseType.LOST,
                                        List.of(
                                                location(LocationType.EVENT),
                                                location(LocationType.CURRENT)),
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                save(
                                        CaseType.LOST,
                                        List.of(
                                                location(LocationType.EVENT),
                                                location(LocationType.EVENT)),
                                        1))
                .isInstanceOf(IllegalArgumentException.class);
        for (int count : new int[] {0, 11}) {
            assertThatThrownBy(
                            () -> save(CaseType.LOST, List.of(location(LocationType.EVENT)), count))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from animal_case where id=?",
                                Integer.class,
                                postId))
                .isZero();
    }

    @Test
    void disclosureRequiresCiphertextAndEvidenceAndRedactsCoordinates() {
        assertThatThrownBy(
                        () ->
                                new AnimalCaseLocation(
                                        postId,
                                        LocationType.EVENT,
                                        "11680",
                                        null,
                                        "서울 강남구",
                                        null,
                                        true,
                                        "exact-location-v1",
                                        Instant.now(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new AnimalCaseLocation(
                                        postId,
                                        LocationType.EVENT,
                                        "11680",
                                        null,
                                        "서울 강남구",
                                        "encrypted-fixture",
                                        true,
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        var protectedLocation =
                new AnimalCaseLocation(
                        postId,
                        LocationType.EVENT,
                        "11680",
                        null,
                        "서울 강남구",
                        "encrypted-fixture",
                        true,
                        "exact-location-v1",
                        Instant.now(),
                        null,
                        null);
        assertThat(protectedLocation.toString()).doesNotContain("encrypted-fixture", "서울 강남구");
    }

    private AnimalCaseLocation location(LocationType role) {
        return new AnimalCaseLocation(
                postId, role, "11680", null, "서울 강남구", null, false, null, null, null, null);
    }

    private void save(CaseType type, List<AnimalCaseLocation> locations, int count) {
        var animalCase =
                AnimalCase.user(
                        postId,
                        type,
                        new AnimalDetails(
                                null,
                                Species.DOG,
                                null,
                                Sex.UNKNOWN,
                                null,
                                LocalDate.of(2026, 9, 1),
                                null,
                                null),
                        Instant.now());
        var post = new UserPost(postId, memberId, UUID.randomUUID(), "a".repeat(64));
        var photos =
                IntStream.range(0, count)
                        .mapToObj(
                                index ->
                                        AnimalPhoto.upload(
                                                posts.reservePhotoId(),
                                                postId,
                                                index,
                                                new byte[] {1},
                                                512,
                                                512,
                                                "b".repeat(64),
                                                Instant.now()))
                        .toList();
        posts.persist(animalCase, post, locations, photos);
    }
}
