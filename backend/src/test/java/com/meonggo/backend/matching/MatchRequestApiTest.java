package com.meonggo.backend.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchRequestApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private LoginService login;
    private long memberId;
    private long postId;
    private String authorization;

    @BeforeEach
    void setup() {
        memberId = member("작성자");
        authorization = bearer(memberId);
        postId = userPost(memberId, "LOST", "ACTIVE");
    }

    @Test
    void acceptsRequestWithPinnedModelAndSnapshotWithoutMutatingCase() throws Exception {
        var response =
                request(postId, authorization)
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.code").value("SUCCESS"))
                        .andExpect(jsonPath("$.message").value("유사도 분석을 접수했습니다."))
                        .andExpect(jsonPath("$.data.postId").value(postId))
                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                        .andExpect(jsonPath("$.data.createdAt").exists())
                        .andReturn()
                        .getResponse();

        long runId =
                mapper.readTree(response.getContentAsString())
                        .path("data")
                        .path("matchRunId")
                        .asLong();
        var run = jdbc.queryForMap("select * from match_run where id=?", runId);
        assertThat(run.get("query_case_id")).isEqualTo(postId);
        assertThat(run.get("query_case_version")).isEqualTo(0L);
        assertThat(run.get("model_id")).isEqualTo("dinov2_vitb14");
        assertThat(run.get("model_version")).isEqualTo("v2");
        assertThat(
                        jdbc.queryForObject(
                                "select version from animal_case where id=?", Long.class, postId))
                .isZero();
    }

    @Test
    void reusesPendingAndRunningRequestsThenCreatesAfterTerminalRun() throws Exception {
        long pending = requestId(postId, authorization);
        assertThat(requestId(postId, authorization)).isEqualTo(pending);
        jdbc.update("update match_run set status='RUNNING',started_at=now() where id=?", pending);
        request(postId, authorization)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.matchRunId").value(pending))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        complete(pending, "SUCCEEDED");
        long afterSuccess = requestId(postId, authorization);
        assertThat(afterSuccess).isNotEqualTo(pending);
        jdbc.update(
                "update match_run set status='RUNNING',started_at=now() where id=?", afterSuccess);
        complete(afterSuccess, "FAILED");
        long afterFailure = requestId(postId, authorization);
        assertThat(afterFailure).isNotIn(pending, afterSuccess);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(3);
    }

    @Test
    void parallelRequestsReturnOneActiveRunWithoutChangingVersion() throws Exception {
        int requests = 8;
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        var ids = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(requests)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < requests; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "test synchronization timeout");
                                    }
                                    return requestId(postId, authorization);
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(ids).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select version from animal_case where id=?", Long.class, postId))
                .isZero();
    }

    @Test
    void authenticationIsRequiredBeforePostEligibilityChecks() throws Exception {
        request(postId, "")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        assertNoRuns(postId);
    }

    @Test
    void appliesEligibilityGuardsInSpecifiedOrder() throws Exception {
        long other = member("다른회원");
        request(postId, bearer(other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-002"));

        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", postId);
        request(postId, authorization)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-003"));

        long sheltering = userPost(memberId, "SHELTERING", "ACTIVE");
        request(sheltering, authorization)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH-001"));
        request(sheltering, bearer(other))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH-001"));

        long publicPost = animal("SHELTERING", "PUBLIC", "ACTIVE");
        request(publicPost, authorization)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATCH-001"));

        long withdrawnOwner = member("탈퇴회원");
        long withdrawnPost = userPost(withdrawnOwner, "LOST", "ACTIVE");
        jdbc.update(
                "update member set status='WITHDRAWN',deleted_at=now() where id=?", withdrawnOwner);
        request(withdrawnPost, bearer(other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
        request(Long.MAX_VALUE, bearer(other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
    }

    @Test
    void rejectedRequestsDoNotLeakExistingRunHistory() throws Exception {
        long runId = insertPending(postId);
        var response =
                request(postId, bearer(member("침입자")))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("POST-002"))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response)
                .doesNotContain("\"matchRunId\":" + runId, "PENDING", "model_id", "modelVersion");
    }

    private org.springframework.test.web.servlet.ResultActions request(long id, String auth)
            throws Exception {
        var builder = post("/api/v1/posts/" + id + "/match-runs");
        if (!auth.isEmpty()) builder.header("Authorization", auth);
        return mvc.perform(builder);
    }

    private long requestId(long id, String auth) throws Exception {
        var response = request(id, auth).andExpect(status().isAccepted()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString())
                .path("data")
                .path("matchRunId")
                .asLong();
    }

    private void complete(long runId, String status) {
        jdbc.update(
                "update match_run set status=?,started_at=coalesce(started_at,now()),completed_at=now(),error_code=? where id=?",
                status,
                "FAILED".equals(status) ? "WORKER_FAILURE" : null,
                runId);
    }

    private long insertPending(long id) {
        return jdbc.queryForObject(
                "insert into match_run(query_case_id,query_case_version,status,model_id,model_version,created_at) values(?,0,'PENDING','fixture','v1',now()) returning id",
                Long.class,
                id);
    }

    private void assertNoRuns(long id) {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Integer.class,
                                id))
                .isZero();
    }

    private String bearer(long id) {
        return "Bearer " + sessions.create(id).accessToken();
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                """
            insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture',?,'ACTIVE',now(),now(),'private-phone',?,now(),true, 'privacy-collection-v1',now()) returning id
            """,
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private long userPost(long owner, String type, String status) {
        long id = animal(type, "USER", status);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                id,
                owner,
                UUID.randomUUID(),
                "a".repeat(64));
        return id;
    }

    private long animal(String type, String source, String status) {
        return jdbc.queryForObject(
                "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values(?,?,?,now(),'DOG','UNKNOWN','2020-01-01',now(),now()) returning id",
                Long.class,
                type,
                source,
                status);
    }
}
