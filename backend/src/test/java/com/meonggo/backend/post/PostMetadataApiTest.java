package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class PostMetadataApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private LocationProtection protection;
    @MockitoBean private LoginService login;
    private long owner;
    private long viewer;
    private long post;

    @BeforeEach
    void setup() {
        owner = member();
        viewer = member();
        post = animal("LOST", "USER");
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                post,
                owner,
                UUID.randomUUID(),
                "a".repeat(64));
        location("EVENT", "사건 비밀 위치", false);
        photo("USER_UPLOAD", "/data/user/images/private.jpg", 1);
    }

    @Test
    void partialContentUpdatePreservesOmittedAndClearsOptional() throws Exception {
        patch(owner, "{\"version\":0,\"name\":\" 새 이름 \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(
                        jdbc.queryForObject(
                                "select name from animal_case where id=?", String.class, post))
                .isEqualTo("새 이름");
        patch(owner, "{\"version\":1,\"name\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void rejectsInvalidShapesVersionsAndRequiredNulls() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    "{\"version\":null}",
                    "{\"version\":\"0\"}",
                    "{\"version\":0.0}",
                    "{\"version\":-1}",
                    "{\"version\":9223372036854775808}",
                    "{\"version\":0,\"species\":null}",
                    "{\"version\":0,\"sex\":null}",
                    "{\"version\":0,\"eventDate\":null}",
                    "{\"version\":0,\"eventLocation\":null}",
                    "{\"version\":0,\"type\":\"LOST\"}",
                    "{\"version\":0,\"exactLocation\":\"secret\"}",
                    "{\"version\":0,\"currentLocation\":{}}",
                    "{\"version\":0,\"eventLocation\":{\"publicLocation\":\"fake\"}}",
                    "{\"version\":0,\"eventLocation\":{\"regionCode\":null}}",
                    "{\"version\":0,\"eventDate\":\"9999-12-31\"}",
                    "{\"version\":0,\"name\":12}"
                }) {
            patch(owner, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON-001"));
        }
    }

    @Test
    void guardsOwnerStatusSourceAndVersion() throws Exception {
        patch(viewer, "{\"version\":0}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-002"));
        patch(owner, "{\"version\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-004"));
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", post);
        patch(owner, "{\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-003"));
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", post);
        patch(owner, "{\"version\":0}").andExpect(status().isNotFound());
        jdbc.update(
                "update animal_case set status='ACTIVE',deleted_at=null,closed_at=null,source_type='PUBLIC',case_type='SHELTERING' where id=?",
                post);
        patch(owner, "{\"version\":0}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-005"));
    }

    @Test
    void consentOnlyPreservesVersionAndDisablingPreservesProof() throws Exception {
        String cipher = locationText("exact_location_ciphertext");
        patch(owner, "{\"version\":0,\"eventLocation\":{\"exactLocationVisible\":true}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-007"));
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"exactLocationVisible\":true,\"disclosurePolicyVersion\":\"exact-location-v1\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        String proof = locationText("disclosure_consented_at");
        patch(owner, "{\"version\":0,\"eventLocation\":{\"exactLocationVisible\":false}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        assertThat(locationText("disclosure_consented_at")).isEqualTo(proof);
        assertThat(locationText("exact_location_ciphertext")).isEqualTo(cipher);
        patch(owner, "{\"version\":0,\"eventLocation\":{\"disclosurePolicyVersion\":null}}")
                .andExpect(status().isConflict());
    }

    @Test
    void normalizedNoopPreservesTimestampCiphertextAndHistoricalDisplay() throws Exception {
        String timestamp =
                jdbc.queryForObject(
                        "select updated_at::text from animal_case where id=?", String.class, post);
        String cipher = locationText("exact_location_ciphertext");
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"exactLocation\":\" 사건 비밀 위치 \",\"latitude\":37.5000000}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        assertThat(locationText("exact_location_ciphertext")).isEqualTo(cipher);
        assertThat(
                        jdbc.queryForObject(
                                "select updated_at::text from animal_case where id=?",
                                String.class,
                                post))
                .isEqualTo(timestamp);
        jdbc.update(
                "update animal_case_location set region_code='99999',emd_code=null,public_location='과거 지역' where animal_case_id=?",
                post);
        patch(owner, "{\"version\":0,\"color\":\"흰색\"}").andExpect(status().isOk());
        assertThat(locationText("public_location")).isEqualTo("과거 지역");
    }

    @Test
    void mergesCoordinatesAndRegionAndRejectsInvalidFinalLocation() throws Exception {
        patch(owner, "{\"version\":0,\"eventLocation\":{\"latitude\":null}}")
                .andExpect(status().isBadRequest());
        patch(owner, "{\"version\":0,\"eventLocation\":{\"latitude\":91}}")
                .andExpect(status().isBadRequest());
        patch(owner, "{\"version\":0,\"eventLocation\":{\"emdCode\":\"1168010200\"}}")
                .andExpect(status().isBadRequest());
        patch(owner, "{\"version\":0,\"eventLocation\":{\"regionCode\":\"99999\"}}")
                .andExpect(status().isBadRequest());
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"latitude\":null,\"longitude\":null,\"emdCode\":null}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(locationText("emd_code")).isNull();
    }

    @Test
    void visibleExactChangeNeedsNewConsentAndAllRolesRollback() throws Exception {
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", post);
        location("CURRENT", "현재 비밀 위치", true);
        String eventCipher = locationText("exact_location_ciphertext");
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"exactLocation\":\"새 사건 위치\"},\"currentLocation\":{\"exactLocation\":\"새 보호 위치\"}}")
                .andExpect(status().isConflict());
        assertThat(locationText("exact_location_ciphertext")).isEqualTo(eventCipher);
        patch(owner, "{\"version\":0,\"currentLocation\":{\"exactLocation\":null}}")
                .andExpect(status().isBadRequest());
        var result =
                patch(
                                owner,
                                "{\"version\":0,\"currentLocation\":{\"exactLocation\":\"새 보호 위치\",\"disclosurePolicyVersion\":\"exact-location-v1\"}}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.version").value(1))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("새 보호 위치", "latitude", "ciphertext", "enc:v1");
    }

    @Test
    void staleConsentRefreshDoesNotAdvanceContentVersion() throws Exception {
        jdbc.update(
                "update animal_case_location set exact_location_visible=true,disclosure_policy_version='exact-location-v0' where animal_case_id=?",
                post);
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"disclosurePolicyVersion\":\"exact-location-v1\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        assertThat(locationText("disclosure_policy_version")).isEqualTo("exact-location-v1");
    }

    @Test
    void concurrentContentUpdatesUsingSameVersionHaveOneWinner() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return patch(owner, "{\"version\":0,\"name\":\"첫째\"}")
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return patch(owner, "{\"version\":0,\"name\":\"둘째\"}")
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void duplicateOversizedAndEncodedJsonFailSafely() throws Exception {
        patch(owner, "{\"version\":0,\"version\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
        patch(owner, "{\"version\":0,\"name\":\"" + "a".repeat(65536) + "\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/posts/" + post)
                                .header("Authorization", bearer(owner))
                                .header("Content-Encoding", "gzip")
                                .contentType("application/json")
                                .content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/posts/" + post)
                                .contentType("application/json")
                                .content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contentUpdatePreservesRunsCandidatesAndPublicationMetadata() throws Exception {
        long run =
                jdbc.queryForObject(
                        "insert into match_run(query_case_id,query_case_version,status,model_id,model_version,candidate_count,created_at,started_at,completed_at) values(?,0,'SUCCEEDED','fixture','v1',1,now(),now(),now()) returning id",
                        Long.class,
                        post);
        long target = animal("SHELTERING", "PUBLIC");
        jdbc.update(
                "insert into match_candidate(match_run_id,target_case_id,rank,total_score,created_at) values(?,?,1,0.9,now())",
                run,
                target);
        var before =
                jdbc.queryForMap(
                        "select listed_at,created_at,case_type,source_type,is_matchable from animal_case where id=?",
                        post);
        var result =
                patch(
                                owner,
                                "{\"version\":0,\"species\":\"CAT\",\"sex\":\"FEMALE\",\"breedName\":\"코숏\",\"color\":\"검정\",\"eventTime\":\"20:30:00\",\"featureText\":\"목줄\"}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.version").value(1))
                        .andReturn();
        assertThat(
                        jdbc.queryForMap(
                                "select listed_at,created_at,case_type,source_type,is_matchable from animal_case where id=?",
                                post))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select query_case_version from match_run where id=?",
                                Long.class,
                                run))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Long.class,
                                post))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_candidate where match_run_id=?",
                                Long.class,
                                run))
                .isEqualTo(1);
        var json =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .readTree(result.getResponse().getContentAsString());
        assertThat(java.time.Instant.parse(json.get("data").get("updatedAt").asString()))
                .isEqualTo(
                        jdbc.queryForObject(
                                        "select updated_at from animal_case where id=?",
                                        java.sql.Timestamp.class,
                                        post)
                                .toInstant());
    }

    @Test
    void overflowAndCorruptLocationAreSanitizedWithoutMutation() throws Exception {
        jdbc.update("update animal_case set version=? where id=?", Long.MAX_VALUE, post);
        patch(owner, "{\"version\":9223372036854775807,\"name\":\"변경\"}")
                .andExpect(status().isConflict());
        jdbc.update("update animal_case set version=0 where id=?", post);
        jdbc.update(
                "update animal_case_location set exact_location_ciphertext='private-broken-envelope' where animal_case_id=?",
                post);
        var response =
                patch(owner, "{\"version\":0,\"eventLocation\":{\"exactLocation\":\"민감주소\"}}")
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).doesNotContain("private", "민감주소", "Exception");
        assertThat(
                        jdbc.queryForObject(
                                "select version from animal_case where id=?", Long.class, post))
                .isZero();
    }

    @Test
    void textPlainMetadataIsRejectedWithoutMutation() throws Exception {
        assertUnsupportedMetadataType("text/plain");
    }

    @Test
    void octetStreamMetadataIsRejectedWithoutMutation() throws Exception {
        assertUnsupportedMetadataType("application/octet-stream");
    }

    @Test
    void unrelatedNameChangePreservesVisibleLocationConsentAndCiphertext() throws Exception {
        patch(
                        owner,
                        "{\"version\":0,\"eventLocation\":{\"exactLocationVisible\":true,\"disclosurePolicyVersion\":\"exact-location-v1\"}}")
                .andExpect(status().isOk());
        var before =
                jdbc.queryForMap(
                        "select exact_location_ciphertext,exact_location_visible,disclosure_policy_version,disclosure_consented_at from animal_case_location where animal_case_id=? and location_type='EVENT'",
                        post);
        patch(owner, "{\"version\":0,\"name\":\"새 이름\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(
                        jdbc.queryForMap(
                                "select exact_location_ciphertext,exact_location_visible,disclosure_policy_version,disclosure_consented_at from animal_case_location where animal_case_id=? and location_type='EVENT'",
                                post))
                .isEqualTo(before);
    }

    private void assertUnsupportedMetadataType(String contentType) throws Exception {
        var before =
                jdbc.queryForMap(
                        "select name,version,updated_at from animal_case where id=?", post);
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/posts/" + post)
                                .header("Authorization", bearer(owner))
                                .contentType(contentType)
                                .content("{\"version\":0,\"name\":\"변경 금지\"}"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(
                        jdbc.queryForMap(
                                "select name,version,updated_at from animal_case where id=?", post))
                .isEqualTo(before);
    }

    private String locationText(String column) {
        return jdbc.queryForObject(
                "select "
                        + column
                        + "::text from animal_case_location where animal_case_id=? and location_type='EVENT'",
                String.class,
                post);
    }

    private ResultActions patch(long id, String body) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/posts/" + post)
                        .header("Authorization", bearer(id))
                        .contentType("application/json")
                        .content(body));
    }

    private long animal(String type, String source) {
        return jdbc.queryForObject(
                "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values(?,?,'ACTIVE',now(),'DOG','UNKNOWN',current_date,now(),now()) returning id",
                Long.class,
                type,
                source);
    }

    private void location(String role, String exact, boolean visible) {
        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,emd_code,public_location,exact_location_ciphertext,exact_location_visible,disclosure_policy_version,disclosure_consented_at,latitude,longitude) values(?,?,'11680','1168010100','테스트 시군구 테스트동',?,?,'exact-location-v1',now(),37.5,127.0)",
                post,
                role,
                protection.encrypt(exact),
                visible);
    }

    private void photo(String storageType, String uri, int order) {
        jdbc.update(
                "insert into animal_photo(animal_case_id,storage_type,storage_uri,sort_order,created_at,content_type,byte_size,width_px,height_px,checksum_sha256) values(?,?,?,?,now(),'image/jpeg',3,512,512,?)",
                post,
                storageType,
                "USER_UPLOAD".equals(storageType) ? "/data/user/images/" + post + "/1.jpg" : uri,
                order,
                "b".repeat(64));
    }

    private long member() {
        return jdbc.queryForObject(
                "insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at) values(?,'fixture','작성자','ACTIVE',now(),now(),'test-envelope',?,now(),true, 'privacy-collection-v1',now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private String bearer(long id) {
        return "Bearer " + sessions.create(id).accessToken();
    }

    private ResultActions detail(long id) throws Exception {
        return mvc.perform(get("/api/v1/posts/" + post).header("Authorization", bearer(id)));
    }
}
