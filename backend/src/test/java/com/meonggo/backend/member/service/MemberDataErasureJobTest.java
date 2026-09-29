package com.meonggo.backend.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meonggo.backend.member.repository.MemberRepository;
import com.meonggo.backend.photo.storage.PhotoStorage;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MemberDataErasureJobTest {
    private static final Instant NOW = Instant.parse("2026-09-16T03:00:00Z");
    private static final String PREFIX = "erasure-test-";

    @Autowired private MemberDataErasureJob job;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private MemberRepository members;
    @Autowired private MemberPhotoErasureService photoErasure;

    @MockitoBean(name = "authClock")
    private Clock clock;

    @MockitoBean private PhotoStorage photoStorage;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();
    private final List<String> photoPaths = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        dropFailureGuard();
    }

    @AfterEach
    void cleanUp() {
        dropFailureGuard();
        for (String photoPath : photoPaths) {
            jdbc.update("delete from member_photo_erasure_task where storage_uri=?", photoPath);
        }
        for (long memberId : memberIds) {
            jdbc.update(
                    "delete from animal_case where id in "
                            + "(select animal_case_id from user_post where member_id=?)",
                    memberId);
            jdbc.update(
                    "delete from chat_room where owner_member_id=? or requester_member_id=?",
                    memberId,
                    memberId);
            jdbc.update("delete from auth_session where member_id=?", memberId);
            jdbc.update("delete from member where id=?", memberId);
        }
        for (long caseId : caseIds) {
            jdbc.update("delete from animal_case where id=?", caseId);
        }
    }

    @Test
    void erasesOnlyMembersWhoseThirtyDayRetentionExpired() {
        String duePhoneHash = hash('a');
        long due = member("due", "WITHDRAWN", NOW.minus(Duration.ofDays(30)), duePhoneHash);
        long recent =
                member(
                        "recent",
                        "WITHDRAWN",
                        NOW.minus(Duration.ofDays(30)).plusMillis(1),
                        hash('b'));
        long active = member("active", "ACTIVE", null, hash('c'));

        job.runOnce();

        Map<String, Object> erased = memberRow(due);
        assertThat(erased)
                .containsEntry("status", "WITHDRAWN")
                .containsEntry("phone_ciphertext", null)
                .containsEntry("phone_lookup_hash", null)
                .containsEntry("phone_verified_at", null)
                .containsEntry("privacy_collection_agreed", null)
                .containsEntry("privacy_collection_policy_version", null)
                .containsEntry("privacy_collection_consented_at", null);
        assertThat(erased.get("login_id").toString()).doesNotContain(PREFIX + "due");
        assertThat(erased.get("nickname")).isNotEqualTo("탈퇴대상-due");
        assertThat(erased.get("password_hash")).isNotEqualTo("{argon2id-v1}due");
        assertThat(erased.get("personal_data_erased_at")).isNotNull();

        assertThat(memberRow(recent))
                .containsEntry("login_id", PREFIX + "recent")
                .containsEntry("phone_lookup_hash", hash('b'))
                .containsEntry("personal_data_erased_at", null);
        assertThat(memberRow(active))
                .containsEntry("login_id", PREFIX + "active")
                .containsEntry("phone_lookup_hash", hash('c'))
                .containsEntry("personal_data_erased_at", null);
        assertThat(members.existsByPhoneLookupHash(duePhoneHash)).isFalse();
        assertThat(members.findByLoginId(PREFIX + "due")).isEmpty();

        long replacement = activeMember("replacement", duePhoneHash);
        assertThat(memberRow(replacement)).containsEntry("phone_lookup_hash", duePhoneHash);
    }

    @Test
    void usesDistinctAnonymousValuesAndDoesNotChangeThemOnRetry() {
        long first = withdrawnMember("first", hash('d'));
        long second = withdrawnMember("second", hash('e'));

        job.runOnce();
        Map<String, Object> firstPass = memberRow(first);
        Map<String, Object> secondPass = memberRow(second);
        job.runOnce();

        assertThat(firstPass.get("login_id")).isNotEqualTo(secondPass.get("login_id"));
        assertThat(firstPass.get("password_hash")).isNotEqualTo(secondPass.get("password_hash"));
        assertThat(memberRow(first)).containsAllEntriesOf(firstPass);
        assertThat(memberRow(second)).containsAllEntriesOf(secondPass);
    }

    @Test
    void processesAtMostOneHundredMembersPerRun() {
        List<Long> inserted =
                jdbc.queryForList(
                        """
                        insert into member(
                          login_id,password_hash,nickname,status,created_at,updated_at,deleted_at,
                          phone_ciphertext,phone_lookup_hash,phone_verified_at,
                          privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                        select ? || value, '{argon2id-v1}batch-' || value, '탈퇴대상',
                          'WITHDRAWN', ?, ?, ?,
                          'enc:v1:batch-' || value, lpad(to_hex(value),64,'0'), ?,
                          true, 'privacy-collection-v1', ?
                        from generate_series(1,101) value
                        returning id
                        """,
                        Long.class,
                        PREFIX + "batch-" + UUID.randomUUID().toString().substring(0, 8) + "-",
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(31))),
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))));
        memberIds.addAll(inserted);

        job.runOnce();
        assertThat(erasedCount(inserted)).isEqualTo(100);
        assertThat(relationallyErasedCount(inserted)).isEqualTo(100);

        job.runOnce();
        assertThat(erasedCount(inserted)).isEqualTo(101);
        assertThat(relationallyErasedCount(inserted)).isEqualTo(101);
    }

    @Test
    void personalDataFailureKeepsThePhoneBlockedAndContinues(CapturedOutput output) {
        String failedLogin = PREFIX + "personal-failure";
        String failedCiphertext = "enc:v1:personal-failure";
        String failedHash = hash('8');
        long failed = withdrawnMember("personal-failure", failedHash);
        jdbc.update("update member set phone_ciphertext=? where id=?", failedCiphertext, failed);
        long conflict = activeMember("anonymous-login-conflict", randomHash());
        jdbc.update("update member set login_id=? where id=?", "withdrawn:" + failed, conflict);
        long succeeded = withdrawnMember("personal-success", hash('a'));

        job.runOnce();

        assertThat(memberRow(failed))
                .containsEntry("login_id", failedLogin)
                .containsEntry("phone_ciphertext", failedCiphertext)
                .containsEntry("phone_lookup_hash", failedHash)
                .containsEntry("personal_data_erased_at", null);
        assertThat(members.existsByPhoneLookupHash(failedHash)).isTrue();
        assertThat(memberRow(succeeded).get("personal_data_erased_at")).isNotNull();
        assertThat(output)
                .doesNotContain(failedLogin)
                .doesNotContain(failedCiphertext)
                .doesNotContain(failedHash);
    }

    @Test
    void relationalFailureIsRetriedWithoutBlockingOtherMembers(CapturedOutput output) {
        String failedLogin = PREFIX + "relational-failure";
        String failedCiphertext = "enc:v1:must-not-be-logged";
        String failedHash = hash('f');
        long failed =
                member(
                        "relational-failure",
                        "WITHDRAWN",
                        NOW.minus(Duration.ofDays(31)),
                        failedHash);
        jdbc.update("update member set phone_ciphertext=? where id=?", failedCiphertext, failed);
        long succeeded = withdrawnMember("relational-success", hash('1'));
        long blockedCase = userCase(failed);
        String blockedPhoto = userPhoto(blockedCase);
        userCase(succeeded);
        installFailureGuard(blockedCase);

        job.runOnce();

        assertThat(memberRow(failed)).containsEntry("relational_data_erased_at", null);
        assertThat(memberRow(failed).get("personal_data_erased_at")).isNotNull();
        assertThat(caseExists(blockedCase)).isTrue();
        assertThat(photoTaskCount(blockedPhoto)).isZero();
        assertThat(memberRow(succeeded).get("relational_data_erased_at")).isNotNull();
        assertThat(userCaseCount(succeeded)).isZero();
        assertThat(output)
                .doesNotContain(failedLogin)
                .doesNotContain(failedCiphertext)
                .doesNotContain(failedHash);

        dropFailureGuard();
        job.runOnce();

        assertThat(memberRow(failed).get("relational_data_erased_at")).isNotNull();
        assertThat(caseExists(blockedCase)).isFalse();
        assertThat(photoTaskCount(blockedPhoto)).isOne();
    }

    @Test
    void deletesOwnedRelationshipsAndPreservesOtherMembersAndPublicData() {
        long due = withdrawnMember("relations", hash('2'));
        long other = activeMember("other", hash('3'));
        long ownedCase = userCase(due);
        long otherCase = userCase(other);
        long publicCase = animalCase("SHELTERING", "PUBLIC");

        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,"
                        + "public_location,exact_location_ciphertext,exact_location_visible,"
                        + "disclosure_policy_version,disclosure_consented_at,latitude,longitude) "
                        + "values (?,'EVENT','11680','서울 강남구','enc:v1:private',true,"
                        + "'exact-location-v1',?,37.5,127.0)",
                ownedCase,
                timestamp(NOW.minus(Duration.ofDays(40))));
        String ownedPhoto = "/data/user/images/" + ownedCase + "/1.jpg";
        photoPaths.add(ownedPhoto);
        jdbc.update(
                "insert into animal_photo(id,animal_case_id,storage_type,storage_uri,content_type,"
                        + "byte_size,width_px,height_px,sort_order,checksum_sha256,created_at) "
                        + "values (nextval('animal_photo_id_seq'),?,'USER_UPLOAD',?,"
                        + "'image/jpeg',128,512,512,0,?,?)",
                ownedCase,
                ownedPhoto,
                hash('4'),
                timestamp(NOW.minus(Duration.ofDays(40))));
        long runId =
                jdbc.queryForObject(
                        "insert into match_run(query_case_id,query_case_version,status,model_id,"
                                + "model_version,candidate_count,started_at,completed_at,created_at) "
                                + "values (?,0,'SUCCEEDED','dino','v1',1,?,?,?) returning id",
                        Long.class,
                        ownedCase,
                        timestamp(NOW.minusSeconds(10)),
                        timestamp(NOW),
                        timestamp(NOW.minusSeconds(10)));
        jdbc.update(
                "insert into match_candidate(match_run_id,target_case_id,rank,total_score,created_at) "
                        + "values (?,?,1,0.9,?)",
                runId,
                publicCase,
                timestamp(NOW));
        jdbc.update(
                "insert into adoption_favorite(member_id,animal_case_id,created_at) values(?,?,?)",
                due,
                publicCase,
                timestamp(NOW.minus(Duration.ofDays(20))));
        jdbc.update(
                "insert into adoption_favorite(member_id,animal_case_id,created_at) values(?,?,?)",
                other,
                publicCase,
                timestamp(NOW.minus(Duration.ofDays(10))));
        jdbc.update(
                "insert into adoption_swipe(member_id,animal_case_id,swiped_at) values(?,?,?)",
                due,
                publicCase,
                timestamp(NOW.minus(Duration.ofDays(20))));
        jdbc.update(
                "insert into adoption_swipe(member_id,animal_case_id,swiped_at) values(?,?,?)",
                other,
                publicCase,
                timestamp(NOW.minus(Duration.ofDays(10))));
        long chatRoom =
                jdbc.queryForObject(
                        "insert into chat_room(animal_case_id,owner_member_id,requester_member_id,"
                                + "created_at,updated_at) values (?,?,?,?,?) returning id",
                        Long.class,
                        otherCase,
                        other,
                        due,
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))));
        jdbc.update(
                "insert into chat_message(chat_room_id,sender_member_id,client_message_id,"
                        + "request_hash,content,created_at) values (?,?,?,?,?,?)",
                chatRoom,
                due,
                UUID.randomUUID(),
                hash('5'),
                "개인 대화",
                timestamp(NOW.minus(Duration.ofDays(40))));
        authSession(due);

        job.runOnce();

        assertThat(userCaseCount(due)).isZero();
        assertThat(count("animal_case_location", "animal_case_id", ownedCase)).isZero();
        assertThat(count("animal_photo", "animal_case_id", ownedCase)).isZero();
        assertThat(count("match_run", "query_case_id", ownedCase)).isZero();
        assertThat(count("chat_room", "requester_member_id", due)).isZero();
        assertThat(count("chat_message", "sender_member_id", due)).isZero();
        assertThat(count("auth_session", "member_id", due)).isZero();
        assertThat(count("adoption_favorite", "member_id", due)).isZero();
        assertThat(count("adoption_favorite", "member_id", other)).isOne();
        assertThat(count("adoption_swipe", "member_id", due)).isZero();
        assertThat(count("adoption_swipe", "member_id", other)).isOne();
        assertThat(photoTaskCount(ownedPhoto)).isOne();
        assertThat(caseExists(otherCase)).isTrue();
        assertThat(caseExists(publicCase)).isTrue();
        assertThat(memberRow(other)).containsEntry("status", "ACTIVE");

        photoErasure.eraseDuePhotos();

        verify(photoStorage).delete(ownedPhoto);
        assertThat(photoTaskCount(ownedPhoto)).isZero();
    }

    @Test
    void hdfsFailureIsRetriedWithoutRestoringErasedData(CapturedOutput output) {
        long due = withdrawnMember("hdfs-failure", hash('0'));
        long ownedCase = userCase(due);
        String photoPath = userPhoto(ownedCase);
        doThrow(new IllegalStateException("private:" + photoPath))
                .doNothing()
                .when(photoStorage)
                .delete(photoPath);

        job.runOnce();
        photoErasure.eraseDuePhotos();

        assertThat(memberRow(due).get("personal_data_erased_at")).isNotNull();
        assertThat(memberRow(due).get("relational_data_erased_at")).isNotNull();
        assertThat(caseExists(ownedCase)).isFalse();
        assertThat(photoTask(photoPath))
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_until", null)
                .containsEntry("last_error_code", "PHOTO-006");
        assertThat(output).doesNotContain(photoPath).doesNotContain("private:");

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofMinutes(2)));
        photoErasure.eraseDuePhotos();

        verify(photoStorage, times(2)).delete(photoPath);
        assertThat(photoTaskCount(photoPath)).isZero();
    }

    @Test
    void photoErasureClaimsAtMostOneHundredAndSkipsActiveLeases() {
        long pathPrefix = Integer.toUnsignedLong(UUID.randomUUID().hashCode()) * 1000L;
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < 102; index++) {
            String path = "/data/user/images/" + (pathPrefix + index + 1) + "/1.jpg";
            paths.add(path);
            photoPaths.add(path);
            jdbc.update(
                    """
                    insert into member_photo_erasure_task(
                      storage_uri,deadline_at,attempt_count,next_attempt_at,lease_until,
                      created_at,updated_at)
                    values (?,?,0,?,?,?,?)
                    """,
                    path,
                    timestamp(NOW.minus(Duration.ofDays(1))),
                    timestamp(NOW.minus(Duration.ofMinutes(1))),
                    index == 0 ? timestamp(NOW.plus(Duration.ofMinutes(10))) : null,
                    timestamp(NOW.minus(Duration.ofDays(1))),
                    timestamp(NOW.minus(Duration.ofDays(1))));
        }

        photoErasure.eraseDuePhotos();

        assertThat(paths.stream().mapToInt(this::photoTaskCount).sum()).isEqualTo(2);
        assertThat(photoTask(paths.getFirst())).containsEntry("attempt_count", 0);
        verify(photoStorage, times(100)).delete(anyString());

        photoErasure.eraseDuePhotos();

        assertThat(paths.stream().mapToInt(this::photoTaskCount).sum()).isOne();
        verify(photoStorage, times(101)).delete(anyString());
    }

    @Test
    void skipsTheBatchWhenAnotherInstanceHoldsTheAdvisoryLock() throws Exception {
        long due = withdrawnMember("locked", hash('6'));
        try (Connection connection = dataSource.getConnection();
                var lock = connection.prepareStatement("select pg_advisory_lock(hashtext(?))")) {
            lock.setString(1, MemberDataErasureJob.ADVISORY_LOCK_NAME);
            lock.execute();
            try {
                job.runOnce();
                assertThat(memberRow(due).get("personal_data_erased_at")).isNull();
            } finally {
                try (var unlock =
                        connection.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
                    unlock.setString(1, MemberDataErasureJob.ADVISORY_LOCK_NAME);
                    unlock.execute();
                }
            }
        }
    }

    private long withdrawnMember(String suffix, String phoneHash) {
        return member(suffix, "WITHDRAWN", NOW.minus(Duration.ofDays(31)), phoneHash);
    }

    private long activeMember(String suffix, String phoneHash) {
        return member(suffix, "ACTIVE", null, phoneHash);
    }

    private long member(String suffix, String status, Instant deletedAt, String phoneHash) {
        long id =
                jdbc.queryForObject(
                        """
                        insert into member(
                          login_id,password_hash,nickname,status,created_at,updated_at,deleted_at,
                          phone_ciphertext,phone_lookup_hash,phone_verified_at,
                          privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                        values (?,?,?, ?,?,?,?, ?,?,?, true, 'privacy-collection-v1',?) returning id
                        """,
                        Long.class,
                        PREFIX + suffix,
                        "{argon2id-v1}" + suffix,
                        "탈퇴대상-" + suffix,
                        status,
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(deletedAt),
                        "enc:v1:" + suffix,
                        phoneHash,
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))));
        memberIds.add(id);
        return id;
    }

    private long userCase(long memberId) {
        long caseId = animalCase("LOST", "USER");
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) "
                        + "values (?,?,?,?)",
                caseId,
                memberId,
                UUID.randomUUID(),
                hash('7'));
        return caseId;
    }

    private String userPhoto(long caseId) {
        long photoId = jdbc.queryForObject("select nextval('animal_photo_id_seq')", Long.class);
        String path = "/data/user/images/" + caseId + "/" + photoId + ".jpg";
        jdbc.update(
                """
                insert into animal_photo(
                  id,animal_case_id,storage_type,storage_uri,content_type,byte_size,
                  width_px,height_px,sort_order,checksum_sha256,created_at)
                values (? ,?,'USER_UPLOAD',?,'image/jpeg',128,512,512,0,?,?)
                """,
                photoId,
                caseId,
                path,
                hash('4'),
                timestamp(NOW.minus(Duration.ofDays(40))));
        photoPaths.add(path);
        return path;
    }

    private long animalCase(String caseType, String sourceType) {
        long caseId =
                jdbc.queryForObject(
                        "insert into animal_case(case_type,source_type,status,listed_at,species,sex,"
                                + "event_date,created_at,updated_at) "
                                + "values (?,?,'ACTIVE',?,'DOG','UNKNOWN','2026-08-01',?,?) returning id",
                        Long.class,
                        caseType,
                        sourceType,
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))),
                        timestamp(NOW.minus(Duration.ofDays(40))));
        caseIds.add(caseId);
        return caseId;
    }

    private void authSession(long memberId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "insert into auth_session(member_id,refresh_token_selector,refresh_token_hash,"
                        + "expires_at,revoked_at,created_at) values (?,?,?,?,?,?)",
                memberId,
                token.substring(0, 22),
                token + token,
                timestamp(NOW.plus(Duration.ofDays(1))),
                timestamp(NOW.minus(Duration.ofDays(30))),
                timestamp(NOW.minus(Duration.ofDays(40))));
    }

    private Map<String, Object> memberRow(long memberId) {
        return jdbc.queryForMap(
                "select login_id,password_hash,nickname,status,phone_ciphertext,phone_lookup_hash,"
                        + "phone_verified_at,privacy_collection_agreed,privacy_collection_policy_version,"
                        + "privacy_collection_consented_at,personal_data_erased_at,"
                        + "relational_data_erased_at from member where id=?",
                memberId);
    }

    private int erasedCount(List<Long> ids) {
        return markedCount(ids, "personal_data_erased_at");
    }

    private int relationallyErasedCount(List<Long> ids) {
        return markedCount(ids, "relational_data_erased_at");
    }

    private int markedCount(List<Long> ids, String column) {
        if (!List.of("personal_data_erased_at", "relational_data_erased_at").contains(column)) {
            throw new IllegalArgumentException("Unexpected column");
        }
        return ids.stream()
                .mapToInt(
                        id ->
                                Boolean.TRUE.equals(
                                                jdbc.queryForObject(
                                                        "select "
                                                                + column
                                                                + " is not null from member where id=?",
                                                        Boolean.class,
                                                        id))
                                        ? 1
                                        : 0)
                .sum();
    }

    private int userCaseCount(long memberId) {
        return jdbc.queryForObject(
                "select count(*) from user_post where member_id=?", Integer.class, memberId);
    }

    private boolean caseExists(long caseId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from animal_case where id=?)",
                        Boolean.class,
                        caseId));
    }

    private int count(String table, String column, long id) {
        if (!List.of(
                        "animal_case_location",
                        "animal_photo",
                        "match_run",
                        "chat_room",
                        "chat_message",
                        "auth_session",
                        "adoption_favorite",
                        "adoption_swipe")
                .contains(table)) throw new IllegalArgumentException("Unexpected table");
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + "=?", Integer.class, id);
    }

    private int photoTaskCount(String storageUri) {
        return jdbc.queryForObject(
                "select count(*) from member_photo_erasure_task where storage_uri=?",
                Integer.class,
                storageUri);
    }

    private Map<String, Object> photoTask(String storageUri) {
        return jdbc.queryForMap(
                "select attempt_count,lease_until,last_error_code "
                        + "from member_photo_erasure_task where storage_uri=?",
                storageUri);
    }

    private void installFailureGuard(long animalCaseId) {
        jdbc.execute(
                "create table erasure_failure_guard (animal_case_id bigint primary key "
                        + "references animal_case(id) on delete restrict)");
        jdbc.update("insert into erasure_failure_guard(animal_case_id) values (?)", animalCaseId);
    }

    private void dropFailureGuard() {
        jdbc.execute("drop table if exists erasure_failure_guard");
    }

    private static String hash(char value) {
        return String.valueOf(value).repeat(64);
    }

    private static String randomHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
