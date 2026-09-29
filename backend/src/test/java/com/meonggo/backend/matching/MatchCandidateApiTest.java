package com.meonggo.backend.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.post.location.LocationProtection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchCandidateApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private LocationProtection protection;
    @MockitoBean private LoginService login;
    private long owner;
    private long viewer;
    private long post;
    private String token;

    @BeforeEach
    void setup() {
        owner = member();
        viewer = member();
        post = animal("LOST", "USER", owner);
        token = "Bearer " + sessions.create(owner).accessToken();
    }

    @Test
    void unrequestedReadDoesNotCreateRuns() throws Exception {
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.data.usingPreviousResult").value(false))
                .andExpect(jsonPath("$.data.latestRun").doesNotExist())
                .andExpect(jsonPath("$.data.resultRunId").doesNotExist())
                .andExpect(jsonPath("$.data.recommendedPollAfterMs").doesNotExist());
        assertThat(runCount()).isZero();
    }

    @Test
    void pendingAndRunningExposePollingAndOnlyAvailableTimestamps() throws Exception {
        long run = run("PENDING", 0, 0, null);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.latestRun.matchRunId").value(run))
                .andExpect(jsonPath("$.data.latestRun.startedAt").doesNotExist())
                .andExpect(jsonPath("$.data.latestRun.candidateCount").doesNotExist())
                .andExpect(jsonPath("$.data.recommendedPollAfterMs").value(1000));
        jdbc.update("update match_run set status='RUNNING',started_at=now() where id=?", run);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.latestRun.startedAt").exists())
                .andExpect(jsonPath("$.data.recommendedPollAfterMs").value(1000));
    }

    @Test
    void successfulEmptyResultIsDifferentFromFailedAndSanitizesWorkerError() throws Exception {
        long success = run("SUCCEEDED", 0, 0, null);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.latestRun.candidateCount").value(0))
                .andExpect(jsonPath("$.data.resultRunId").value(success))
                .andExpect(jsonPath("$.data.candidates").isEmpty());
        long failure = run("FAILED", 0, 0, "private-storage-secret-exception");
        String body =
                candidates(post, token)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.analysisStatus").value("FAILED"))
                        .andExpect(jsonPath("$.data.latestRun.errorCode").value("MATCH_FAILED"))
                        .andExpect(jsonPath("$.data.latestRun.matchRunId").value(failure))
                        .andExpect(jsonPath("$.data.latestRun.completedAt").exists())
                        .andExpect(jsonPath("$.data.usingPreviousResult").value(true))
                        .andExpect(jsonPath("$.data.resultRunId").value(success))
                        .andExpect(jsonPath("$.data.recommendedPollAfterMs").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("private-storage", "exception");
    }

    @Test
    void timeoutWithoutPreviousSuccessIsSafeFailure() throws Exception {
        run("FAILED", 0, 0, "MATCH_TIMEOUT");
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.latestRun.errorCode").value("MATCH_TIMEOUT"))
                .andExpect(jsonPath("$.data.resultRunId").doesNotExist())
                .andExpect(jsonPath("$.data.usingPreviousResult").value(false));
    }

    @Test
    void runningKeepsPreviousSuccessAndStaleRetainsActualLatestStateAndPolling() throws Exception {
        long success = run("SUCCEEDED", 0, 1, null);
        long target = animal("SHELTERING", "USER", viewer);
        candidate(success, target, 1);
        long latest = run("RUNNING", 0, 0, null);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("RUNNING"))
                .andExpect(jsonPath("$.data.usingPreviousResult").value(true))
                .andExpect(jsonPath("$.data.resultRunId").value(success))
                .andExpect(jsonPath("$.data.candidates[0].post.postId").value(target));
        jdbc.update("update animal_case set version=1 where id=?", post);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("STALE"))
                .andExpect(jsonPath("$.data.latestRun.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.recommendedPollAfterMs").value(1000))
                .andExpect(jsonPath("$.data.candidates[0].rank").value(1));
        jdbc.update(
                "update match_run set status='FAILED',completed_at=now(),error_code='MATCH_TIMEOUT' where id=?",
                latest);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("STALE"))
                .andExpect(jsonPath("$.data.latestRun.status").value("FAILED"))
                .andExpect(jsonPath("$.data.recommendedPollAfterMs").doesNotExist());
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void actualConsentPatchStaysFreshAndContentPatchBecomesStaleWithoutNewRun() throws Exception {
        long success = run("SUCCEEDED", 0, 1, null);
        candidate(success, animal("SHELTERING", "USER", viewer), 1);
        mvc.perform(
                        patch("/api/v1/posts/" + post)
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content(
                                        "{\"version\":0,\"eventLocation\":{\"exactLocationVisible\":true,\"disclosurePolicyVersion\":\"exact-location-v1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("SUCCEEDED"));
        mvc.perform(
                        patch("/api/v1/posts/" + post)
                                .header("Authorization", token)
                                .contentType("application/json")
                                .content("{\"version\":0,\"color\":\"흰색\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("STALE"))
                .andExpect(jsonPath("$.data.resultRunId").value(success))
                .andExpect(jsonPath("$.data.candidates.length()").value(1));
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void newSuccessfulEmptyResultReplacesOlderCandidates() throws Exception {
        long old = run("SUCCEEDED", 0, 1, null);
        candidate(old, animal("SHELTERING", "USER", viewer), 1);
        long newest = run("SUCCEEDED", 0, 0, null);
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultRunId").value(newest))
                .andExpect(jsonPath("$.data.usingPreviousResult").value(false))
                .andExpect(jsonPath("$.data.candidates").isEmpty());
    }

    @Test
    void candidatesKeepRankAndOnlySourceSpecificPublicFields() throws Exception {
        long run = run("SUCCEEDED", 0, 2, null);
        long user = animal("SHELTERING", "USER", viewer);
        long publicPost = animal("SHELTERING", "PUBLIC", 0);
        jdbc.update(
                "update animal_case set status='CLOSED',closed_at=now() where id=?", publicPost);
        photo(user, "USER_UPLOAD", "/unused", 0);
        photo(publicPost, "PUBLIC_URL", "https://public.example.com/animal.jpg", 0);
        candidate(run, user, 7);
        candidate(run, publicPost, 2);
        String body =
                candidates(post, token)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.candidates[0].rank").value(2))
                        .andExpect(jsonPath("$.data.candidates[0].post.source").value("SHELTER"))
                        .andExpect(jsonPath("$.data.candidates[0].post.status").value("CLOSED"))
                        .andExpect(jsonPath("$.data.candidates[0].post.shelter.name").value("보호센터"))
                        .andExpect(
                                jsonPath("$.data.candidates[0].post.shelter.phone")
                                        .value(org.hamcrest.Matchers.nullValue()))
                        .andExpect(jsonPath("$.data.candidates[0].post.author").doesNotExist())
                        .andExpect(jsonPath("$.data.candidates[1].rank").value(7))
                        .andExpect(
                                jsonPath("$.data.candidates[1].post.author.nickname").value("보호자"))
                        .andExpect(jsonPath("$.data.candidates[1].post.shelter").doesNotExist())
                        .andExpect(
                                jsonPath("$.data.candidates[1].post.thumbnailUrl")
                                        .value(org.hamcrest.Matchers.startsWith("/api/v1/photos/")))
                        .andExpect(
                                jsonPath("$.data.candidates[1].post.publicLocation")
                                        .value("사건 공개 지역"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body)
                .contains("\"phone\":null")
                .doesNotContain(
                        "totalScore",
                        "imageScore",
                        "distanceKm",
                        "timeGapDays",
                        "latitude",
                        "longitude",
                        "exactLocation",
                        "currentLocation",
                        "현재 공개 지역",
                        "address",
                        "memberId",
                        "featureText",
                        "/data/user",
                        "ciphertext",
                        "modelId");
    }

    @Test
    void liveFiltersHideIneligibleTargetsWithoutRewritingHistoricalCountOrRanks() throws Exception {
        long run = run("SUCCEEDED", 0, 8, null);
        for (int rank = 1; rank <= 8; rank++) {
            long member = member();
            long target = animal("SHELTERING", rank == 3 || rank == 8 ? "PUBLIC" : "USER", member);
            candidate(run, target, rank);
            switch (rank) {
                case 1 ->
                        jdbc.update(
                                "update animal_case set status='CLOSED',closed_at=now() where id=?",
                                target);
                case 2, 3 ->
                        jdbc.update("update animal_case set is_matchable=false where id=?", target);
                case 4 ->
                        jdbc.update(
                                "update animal_case set status='DELETED',deleted_at=now() where id=?",
                                target);
                case 5 ->
                        jdbc.update(
                                "update member set status='WITHDRAWN',deleted_at=now() where id=?",
                                member);
                case 6 ->
                        jdbc.update(
                                "delete from animal_case_location where animal_case_id=? and location_type='EVENT'",
                                target);
                case 7 -> jdbc.update("update animal_case set case_type='LOST' where id=?", target);
                default -> {}
            }
        }
        candidates(post, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latestRun.candidateCount").value(8))
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates[0].rank").value(8));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_candidate where match_run_id=?",
                                Integer.class,
                                run))
                .isEqualTo(8);
    }

    @Test
    void unsafePublicThumbnailIsNotExposed() throws Exception {
        long run = run("SUCCEEDED", 0, 1, null);
        long target = animal("SHELTERING", "PUBLIC", 0);
        long photo = photo(target, "PUBLIC_URL", "https://name:secret@example.com/a.jpg", 0);
        candidate(run, target, 1);
        for (String url :
                new String[] {
                    "https://name:secret@example.com/a.jpg",
                    "file:///private/a.jpg",
                    "https://example.com:0/a",
                    "https://example.com:65536/a",
                    "//example.com/a"
                }) {
            jdbc.update("update animal_photo set storage_uri=? where id=?", url, photo);
            candidates(post, token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidates[0].post.thumbnailUrl").doesNotExist());
        }
    }

    @Test
    void authenticationAndQueryOwnershipAreCheckedBeforeAnyRunData() throws Exception {
        run("SUCCEEDED", 0, 0, null);
        mvc.perform(get("/api/v1/posts/" + post + "/candidates"))
                .andExpect(status().isUnauthorized());
        String otherToken = "Bearer " + sessions.create(viewer).accessToken();
        candidates(post, otherToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-002"))
                .andExpect(jsonPath("$.data").doesNotExist());
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", post);
        candidates(post, otherToken).andExpect(status().isForbidden());
        candidates(post, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-003"));
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", post);
        candidates(post, token).andExpect(status().isNotFound());
        candidates(Long.MAX_VALUE, token).andExpect(status().isNotFound());
        long sheltering = animal("SHELTERING", "USER", viewer);
        candidates(sheltering, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH-001"));
        candidates(animal("SHELTERING", "PUBLIC", 0), token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH-001"));
    }

    private ResultActions candidates(long id, String authorization) throws Exception {
        return mvc.perform(
                get("/api/v1/posts/" + id + "/candidates").header("Authorization", authorization));
    }

    private int runCount() {
        return jdbc.queryForObject(
                "select count(*) from match_run where query_case_id=?", Integer.class, post);
    }

    private long run(String status, long version, int count, String error) {
        return jdbc.queryForObject(
                """
                insert into match_run(query_case_id,query_case_version,status,model_id,model_version,candidate_count,error_code,started_at,completed_at,created_at)
                values(?,?,?,'dinov2_vitb14','v2',?,?,case when ?='PENDING' then null else clock_timestamp() end,
                  case when ? in ('SUCCEEDED','FAILED') then clock_timestamp() else null end,clock_timestamp()) returning id
                """,
                Long.class,
                post,
                version,
                status,
                count,
                error,
                status,
                status);
    }

    private void candidate(long run, long target, int rank) {
        jdbc.update(
                "insert into match_candidate(match_run_id,target_case_id,rank,total_score,image_score,distance_km,time_gap_days,created_at) values(?,?,?,0.87,0.9,1.25,3,now())",
                run,
                target,
                rank);
    }

    private long animal(String type, String source, long member) {
        long id =
                jdbc.queryForObject(
                        "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,feature_text,created_at,updated_at) values(?,?,'ACTIVE',now(),'DOG','UNKNOWN',current_date,'private feature',now(),now()) returning id",
                        Long.class,
                        type,
                        source);
        if ("USER".equals(source)) {
            jdbc.update(
                    "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                    id,
                    member,
                    UUID.randomUUID(),
                    "a".repeat(64));
        } else {
            long shelter =
                    jdbc.queryForObject(
                            "insert into shelter(care_reg_no,name,address,created_at,updated_at) values(?,'보호센터','private shelter address',now(),now()) returning id",
                            Long.class,
                            UUID.randomUUID().toString());
            jdbc.update(
                    "insert into shelter_animal(animal_case_id,desertion_no,shelter_id,neuter_status,last_synced_at) values(?,?,?,'UNKNOWN',now())",
                    id,
                    UUID.randomUUID().toString(),
                    shelter);
        }
        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,public_location,exact_location_ciphertext) values(?,'EVENT','11680','사건 공개 지역',?)",
                id,
                protection.encrypt("private exact address"));
        if ("SHELTERING".equals(type)) {
            jdbc.update(
                    "insert into animal_case_location(animal_case_id,location_type,region_code,public_location) values(?,'CURRENT','11680','현재 공개 지역')",
                    id);
        }
        return id;
    }

    private long photo(long target, String storage, String uri, int order) {
        return jdbc.queryForObject(
                "insert into animal_photo(animal_case_id,storage_type,storage_uri,sort_order,created_at,content_type,byte_size,width_px,height_px,checksum_sha256) values(?,?,?,?,now(),'image/jpeg',3,512,512,?) returning id",
                Long.class,
                target,
                storage,
                "USER_UPLOAD".equals(storage) ? "/data/user/images/" + target + "/1.jpg" : uri,
                order,
                "b".repeat(64));
    }

    private long member() {
        return jdbc.queryForObject(
                "insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at) values(?,'fixture','보호자','ACTIVE',now(),now(),'private phone',?,now(),true, 'privacy-collection-v1',now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }
}
