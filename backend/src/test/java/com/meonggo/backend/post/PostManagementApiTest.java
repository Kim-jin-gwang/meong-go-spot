package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.post.entity.CloseReason;
import com.meonggo.backend.post.service.PostClosureService;
import com.meonggo.backend.post.service.PostPhotoReplacementService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostManagementApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @Autowired private PostClosureService closures;
    @Autowired private PostPhotoReplacementService replacements;
    @MockitoBean private LoginService login;
    @MockitoBean private PhotoStorage storage;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private long member;
    private long postId;
    private long oldPhoto;
    private String token;
    private byte[] jpeg;

    @BeforeEach
    void setup() throws Exception {
        member = member();
        token = "Bearer " + sessions.create(member).accessToken();
        postId = animal("LOST", "USER");
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                postId,
                member,
                UUID.randomUUID(),
                "a".repeat(64));
        location("EVENT");
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        jpeg = output.toByteArray();
        oldPhoto =
                jdbc.queryForObject(
                        "select nextval(pg_get_serial_sequence('animal_photo','id'))", Long.class);
        jdbc.update(
                """
            insert into animal_photo(id,animal_case_id,storage_type,storage_uri,content_type,byte_size,width_px,height_px,sort_order,checksum_sha256,created_at)
            values(?,?,'USER_UPLOAD',?,'image/jpeg',?,512,512,0,?,now())
            """,
                oldPhoto,
                postId,
                oldPath(),
                jpeg.length,
                "a".repeat(64));
        files.clear();
        files.put(oldPath(), jpeg);
        doAnswer(
                        i -> {
                            files.put(i.getArgument(0), i.getArgument(1));
                            return null;
                        })
                .when(storage)
                .write(anyString(), any(byte[].class));
        doAnswer(
                        i -> {
                            files.put(i.getArgument(1), files.remove(i.getArgument(0)));
                            return null;
                        })
                .when(storage)
                .move(anyString(), anyString());
        doAnswer(
                        i -> {
                            files.remove(i.getArgument(0));
                            return null;
                        })
                .when(storage)
                .delete(anyString());
    }

    @Test
    void closureAtomicallyRemovesPublicAndMatchingEligibilityButKeepsOwnerHistory()
            throws Exception {
        close("{\"version\":0,\"reason\":\"RETURNED\"}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closeReason").value("RETURNED"))
                .andExpect(jsonPath("$.data.closedAt").exists());
        var row =
                jdbc.queryForMap(
                        "select c.status,c.is_matchable,c.closed_at,u.close_reason,c.version from animal_case c join user_post u on c.id=u.animal_case_id where c.id=?",
                        postId);
        assertThat(row.get("status")).isEqualTo("CLOSED");
        assertThat(row.get("is_matchable")).isEqualTo(false);
        assertThat(row.get("close_reason")).isEqualTo("RETURNED");
        assertThat(row.get("closed_at")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select exists(select 1 from animal_case where id=? and status='ACTIVE' and is_matchable=true)",
                                Boolean.class,
                                postId))
                .isFalse();
        mvc.perform(get("/api/v1/photos/" + oldPhoto)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/posts/" + postId).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos.length()").value(1))
                .andExpect(jsonPath("$.data.chat.reason").value("POST_NOT_ACTIVE"));
        mvc.perform(get("/api/v1/members/me/posts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("CLOSED"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from animal_photo where id=?",
                                Integer.class,
                                oldPhoto))
                .isEqualTo(1);
        assertThat(files).containsKey(oldPath());
        assertNoNewRun();
    }

    @Test
    void photoReplacementPublishesOrderedNewIdsAndAdvancesVersionOnlyOnce() throws Exception {
        var result =
                replace("{\"version\":0}", token, 2)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.version").value(1))
                        .andExpect(jsonPath("$.data.photos.length()").value(2))
                        .andExpect(jsonPath("$.data.photos[0].sortOrder").value(0))
                        .andExpect(jsonPath("$.data.photos[1].sortOrder").value(1))
                        .andReturn()
                        .getResponse();
        var ids =
                jdbc.queryForList(
                        "select id from animal_photo where animal_case_id=? order by sort_order",
                        Long.class,
                        postId);
        assertThat(ids).hasSize(2).doesNotContain(oldPhoto);
        var data = mapper.readTree(result.getContentAsString()).path("data");
        assertThat(data.path("photos").get(0).path("photoId").asLong()).isEqualTo(ids.getFirst());
        assertThat(result.getContentAsString())
                .doesNotContain("/data/", "checksum", "private-phone");
        assertThat(files).hasSize(2).doesNotContainKey(oldPath());
        mvc.perform(get("/api/v1/photos/" + oldPhoto).header("Authorization", token))
                .andExpect(status().isNotFound());
        assertNoNewRun();
    }

    @Test
    void rejectsNonownerStaleVersionClosedAndPublicMutations() throws Exception {
        String other = "Bearer " + sessions.create(member()).accessToken();
        close("{\"version\":0,\"reason\":\"OTHER\"}", other)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-002"));
        replace("{\"version\":0}", other, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-002"));
        replace("{\"version\":1}", token, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-004"));
        close("{\"version\":1,\"reason\":\"OTHER\"}", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-004"));
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", postId);
        close("{\"version\":0,\"reason\":\"OTHER\"}", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-003"));
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-003"));
        postId = animal("SHELTERING", "PUBLIC");
        close("{\"version\":0,\"reason\":\"OTHER\"}", token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-005"));
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST-005"));
    }

    @Test
    void rejectsMalformedVersionReasonAndOversizedOrDuplicateJson() throws Exception {
        for (String value : List.of("null", "-1", "1.0", "\"0\"", "9223372036854775808", "true")) {
            close("{\"version\":" + value + ",\"reason\":\"OTHER\"}", token)
                    .andExpect(status().isBadRequest());
            replace("{\"version\":" + value + "}", token, 1).andExpect(status().isBadRequest());
        }
        for (String value :
                List.of(
                        "{}",
                        "{\"version\":0}",
                        "{\"version\":0,\"reason\":\"FOUND\"}",
                        "{\"version\":0,\"reason\":null}",
                        "{\"version\":0,\"reason\":\"OTHER\",\"status\":\"ACTIVE\"}",
                        "{\"version\":0,\"version\":0,\"reason\":\"OTHER\"}",
                        "{\"version\":0,\"reason\":\"OTHER\"} {}",
                        " ".repeat(65537))) {
            close(value, token).andExpect(status().isBadRequest());
        }
        replace("{\"version\":0,\"extra\":true}", token, 1).andExpect(status().isBadRequest());
        replace("{\"version\":0,\"version\":0}", token, 1).andExpect(status().isBadRequest());
        jdbc.update("update animal_case set version=? where id=?", Long.MAX_VALUE, postId);
        close("{\"version\":9223372036854775807,\"reason\":\"OTHER\"}", token)
                .andExpect(status().isConflict());
        replace("{\"version\":9223372036854775807}", token, 1).andExpect(status().isConflict());
        assertThat(files).hasSize(1);
    }

    @Test
    void photoValidationKeepsOriginalFilesAndRows() throws Exception {
        replace("{\"version\":0}", token, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO-001"));
        replace("{\"version\":0}", token, 11)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO-001"));
        jpeg = new byte[] {1, 2, 3};
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("PHOTO-002"));
        jpeg = new byte[] {(byte) 255, (byte) 216, (byte) 255};
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO-003"));
        assertOriginal(0);
    }

    @Test
    void laterFileFailureRollsBackNewPublicationWithoutTouchingOriginals() throws Exception {
        var writes = new AtomicInteger();
        doAnswer(
                        i -> {
                            if (writes.incrementAndGet() == 2)
                                throw new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE);
                            files.put(i.getArgument(0), i.getArgument(1));
                            return null;
                        })
                .when(storage)
                .write(anyString(), any(byte[].class));
        replace("{\"version\":0}", token, 2)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PHOTO-006"));
        assertOriginal(0);
        assertNoNewRun();
    }

    @Test
    void staleVersionAfterFilePreparationCompensatesOnlyNewFiles() throws Exception {
        doAnswer(
                        i -> {
                            files.put(i.getArgument(1), files.remove(i.getArgument(0)));
                            jdbc.update(
                                    "update animal_case set version=version+1 where id=?", postId);
                            return null;
                        })
                .when(storage)
                .move(anyString(), anyString());
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-004"));
        assertOriginal(1);
    }

    @Test
    void withdrawalDuringPublicationRechecksMembershipAndCompensates() throws Exception {
        doAnswer(
                        i -> {
                            files.put(i.getArgument(1), files.remove(i.getArgument(0)));
                            jdbc.update(
                                    "update member set status='WITHDRAWN',deleted_at=now() where id=?",
                                    member);
                            return null;
                        })
                .when(storage)
                .move(anyString(), anyString());
        replace("{\"version\":0}", token, 1).andExpect(status().isForbidden());
        assertOriginal(0);
    }

    @Test
    void closureWinsWhileReplacementPreparesFiles() throws Exception {
        var prepared = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            files.put(i.getArgument(1), files.remove(i.getArgument(0)));
                            prepared.countDown();
                            if (!resume.await(15, TimeUnit.SECONDS))
                                throw new IllegalStateException("test synchronization timeout");
                            return null;
                        })
                .when(storage)
                .move(anyString(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () -> {
                                try {
                                    replacements.replace(
                                            postId,
                                            member,
                                            0,
                                            List.of(
                                                    new MockMultipartFile(
                                                            "photos",
                                                            "photo.jpg",
                                                            "image/jpeg",
                                                            jpeg)));
                                    return "SUCCESS";
                                } catch (BusinessException ex) {
                                    return ex.errorCode().code();
                                }
                            });
            try {
                assertThat(prepared.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(closures.close(postId, member, 0, CloseReason.TRANSFERRED).version())
                        .isEqualTo(1);
            } finally {
                resume.countDown();
            }
            assertThat(future.get(15, TimeUnit.SECONDS)).isEqualTo("POST-003");
        }
        assertOriginal(1);
        assertThat(
                        jdbc.queryForObject(
                                "select status from animal_case where id=?", String.class, postId))
                .isEqualTo("CLOSED");
    }

    @Test
    void shelteringReplacementAndClosurePreserveHistoricalMatchesAndChat() throws Exception {
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", postId);
        location("CURRENT");
        long query = animal("LOST", "USER");
        long run =
                jdbc.queryForObject(
                        "insert into match_run(query_case_id,query_case_version,status,model_id,model_version,candidate_count,started_at,completed_at,created_at) values(?,0,'SUCCEEDED','fixture','v1',1,now(),now(),now()) returning id",
                        Long.class,
                        query);
        jdbc.update(
                "insert into match_candidate(match_run_id,target_case_id,rank,total_score,created_at) values(?,?,1,0.9,now())",
                run,
                postId);
        long requester = member();
        long room =
                jdbc.queryForObject(
                        "insert into chat_room(animal_case_id,owner_member_id,requester_member_id,created_at,updated_at) values(?,?,?,now(),now()) returning id",
                        Long.class,
                        postId,
                        member,
                        requester);
        replace("{\"version\":0}", token, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        close("{\"version\":1,\"reason\":\"TRANSFERRED\"}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_candidate where match_run_id=?",
                                Integer.class,
                                run))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_room where id=?", Integer.class, room))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select exists(select 1 from match_candidate m join animal_case c on c.id=m.target_case_id where m.match_run_id=? and c.is_matchable=true)",
                                Boolean.class,
                                run))
                .isFalse();
        mvc.perform(get("/api/v1/posts").param("type", "SHELTERING").param("regionCode", "11680"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.postId == " + postId + ")]").isEmpty());
    }

    @Test
    void lostPhotoChangeLeavesPriorSuccessAvailableForStaleComparison() throws Exception {
        long run =
                jdbc.queryForObject(
                        "insert into match_run(query_case_id,query_case_version,status,model_id,model_version,candidate_count,started_at,completed_at,created_at) values(?,0,'SUCCEEDED','fixture','v1',0,now(),now(),now()) returning id",
                        Long.class,
                        postId);
        replace("{\"version\":0}", token, 1).andExpect(status().isOk());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Integer.class,
                                postId))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select c.version>r.query_case_version from match_run r join animal_case c on c.id=r.query_case_id where r.id=? and r.status='SUCCEEDED'",
                                Boolean.class,
                                run))
                .isTrue();
    }

    @Test
    void anonymousDeletedAndMissingRequestsCannotMutate() throws Exception {
        mvc.perform(
                        post("/api/v1/posts/" + postId + "/closure")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"version\":0,\"reason\":\"OTHER\"}"))
                .andExpect(status().isUnauthorized());
        replace("{\"version\":0}", "", 1).andExpect(status().isUnauthorized());
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", postId);
        close("{\"version\":0,\"reason\":\"OTHER\"}", token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
        replace("{\"version\":0}", token, 1).andExpect(status().isNotFound());
        assertOriginal(0);
        postId = Long.MAX_VALUE;
        close("{\"version\":0,\"reason\":\"OTHER\"}", token).andExpect(status().isNotFound());
    }

    private void assertOriginal(long version) {
        assertThat(
                        jdbc.queryForList(
                                "select id from animal_photo where animal_case_id=?",
                                Long.class,
                                postId))
                .containsExactly(oldPhoto);
        assertThat(
                        jdbc.queryForObject(
                                "select version from animal_case where id=?", Long.class, postId))
                .isEqualTo(version);
        assertThat(files).hasSize(1).containsKey(oldPath());
    }

    private ResultActions close(String json, String authorization) throws Exception {
        return mvc.perform(
                post("/api/v1/posts/" + postId + "/closure")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    private ResultActions replace(String json, String authorization, int count) throws Exception {
        var payload = new MockPart("payload", json.getBytes(StandardCharsets.UTF_8));
        payload.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var request =
                multipart(HttpMethod.PUT, "/api/v1/posts/" + postId + "/photos")
                        .part(payload)
                        .header("Authorization", authorization);
        for (int i = 0; i < count; i++) {
            var photo = new MockPart("photos", "photo.jpg", jpeg);
            photo.getHeaders().setContentType(MediaType.IMAGE_JPEG);
            request.part(photo);
        }
        return mvc.perform(request);
    }

    private String oldPath() {
        return "/data/user/images/" + postId + "/" + oldPhoto + ".jpg";
    }

    private void assertNoNewRun() {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Integer.class,
                                postId))
                .isZero();
    }

    private long member() {
        return jdbc.queryForObject(
                """
            insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture','작성자','ACTIVE',now(),now(),'private-phone',?,now(),true, 'privacy-collection-v1',now()) returning id
            """,
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private long animal(String type, String source) {
        return jdbc.queryForObject(
                "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values(?,?,'ACTIVE',now(),'DOG','UNKNOWN','2020-01-01',now(),now()) returning id",
                Long.class,
                type,
                source);
    }

    private void location(String role) {
        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,public_location) values(?,?,'11680','테스트 시군구')",
                postId,
                role);
    }
}
