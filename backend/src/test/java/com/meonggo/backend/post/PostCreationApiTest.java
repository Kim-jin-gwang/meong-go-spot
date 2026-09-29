package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.post.location.LocationProtection;
import com.meonggo.backend.post.service.PostCreationService;
import com.meonggo.backend.post.service.PostInputPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class PostCreationApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @Autowired private PostCreationService posts;
    @Autowired private PostInputPolicy inputs;
    @Autowired private LocationProtection protection;
    @MockitoBean private LoginService login;
    @MockitoBean private PhotoStorage storage;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private long member;
    private String token;
    private byte[] photo;

    @BeforeEach
    void setup() throws Exception {
        member =
                jdbc.queryForObject(
                        """
            insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
              phone_ciphertext,phone_lookup_hash,phone_verified_at,
              privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture','작성자','ACTIVE',now(),now(),'private-phone',?,now(),
              true, 'privacy-collection-v1',now()) returning id
            """,
                        Long.class,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
        token = "Bearer " + sessions.create(member).accessToken();
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        photo = output.toByteArray();
        files.clear();
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
    void createsLostWithEncryptedPrivateLocationAndNoAutomaticMatching() throws Exception {
        var response =
                send(payload(UUID.randomUUID(), "LOST", ""))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.type").value("LOST"))
                        .andExpect(jsonPath("$.data.source").value("USER_POST"))
                        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.version").value(0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long id = mapper.readTree(response).path("data").path("postId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select exact_location_ciphertext from animal_case_location where animal_case_id=?",
                                String.class,
                                id))
                .startsWith("enc:v1:")
                .doesNotContain("역삼역");
        assertThat(
                        protection.decrypt(
                                jdbc.queryForObject(
                                        "select exact_location_ciphertext from animal_case_location where animal_case_id=?",
                                        String.class,
                                        id)))
                .isEqualTo("역삼역");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(files).hasSize(1);
        assertThat(response)
                .doesNotContain("역삼역", "latitude", "longitude", "private-phone", "/data/");
    }

    @Test
    void createsShelteringWithIndependentLocationDisclosure() throws Exception {
        String current =
                ",\"currentLocation\":{\"regionCode\":\"11680\",\"exactLocation\":\"보호 장소\",\"exactLocationVisible\":true,\"disclosurePolicyVersion\":\"exact-location-v1\"}";
        var result =
                send(payload(UUID.randomUUID(), "SHELTERING", current))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id =
                mapper.readTree(result.getResponse().getContentAsString())
                        .path("data")
                        .path("postId")
                        .asLong();
        assertThat(
                        jdbc.queryForList(
                                "select location_type from animal_case_location where animal_case_id=?",
                                String.class,
                                id))
                .containsExactlyInAnyOrder("EVENT", "CURRENT");
        assertThat(
                        jdbc.queryForObject(
                                "select disclosure_policy_version from animal_case_location where animal_case_id=? and location_type='CURRENT'",
                                String.class,
                                id))
                .isEqualTo("exact-location-v1");
        assertThat(
                        jdbc.queryForObject(
                                "select disclosure_policy_version from animal_case_location where animal_case_id=? and location_type='EVENT'",
                                String.class,
                                id))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from match_run where query_case_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @Test
    void replaysSameRequestAndRejectsChangedContentWithoutExtraFiles() throws Exception {
        UUID key = UUID.randomUUID();
        String body = payload(key, "LOST", "");
        String first =
                send(body)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replay =
                send(body)
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first));
        send(body.replace("DOG", "CAT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY-001"));
        assertThat(files).hasSize(1);
    }

    @Test
    void rejectsUnauthenticatedMissingRolesFutureDateAndStaleDisclosure() throws Exception {
        mvc.perform(multipart("/api/v1/posts")).andExpect(status().isUnauthorized());
        send(payload(UUID.randomUUID(), "SHELTERING", "")).andExpect(status().isBadRequest());
        send(payload(UUID.randomUUID(), "LOST", ",\"currentLocation\":{}"))
                .andExpect(status().isBadRequest());
        send(payload(UUID.randomUUID(), "LOST", "").replace("2020-01-01", "2999-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("eventDate"));
        send(payload(UUID.randomUUID(), "LOST", "")
                        .replace("\"exactLocationVisible\":false", "\"exactLocationVisible\":true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POST-007"));
        assertThat(files).isEmpty();
    }

    @Test
    void boundsPayloadBeforeParsingAndRejectsUnknownDuplicateAndInvalidTypes() throws Exception {
        send(" ".repeat(65537))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        send(payload(UUID.randomUUID(), "LOST", ",\"publicLocation\":\"arbitrary\""))
                .andExpect(status().isBadRequest());
        send(payload(UUID.randomUUID(), "LOST", ",\"type\":\"LOST\""))
                .andExpect(status().isBadRequest());
        send(payload(UUID.randomUUID(), "LOST", "").replace("false", "\"false\""))
                .andExpect(status().isBadRequest());
        assertThat(files).isEmpty();
    }

    @Test
    void rejectsExcessConcurrentUploadAndReleasesPermitAfterStorageFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new AssertionError("storage wait timed out");
                            throw new com.meonggo.backend.global.error.BusinessException(
                                    com.meonggo.backend.photo.exception.PhotoErrorCode
                                            .STORAGE_UNAVAILABLE);
                        })
                .when(storage)
                .write(anyString(), any(byte[].class));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    executor.submit(
                            () ->
                                    send(payload(UUID.randomUUID(), "LOST", ""))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus());
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                send(payload(UUID.randomUUID(), "LOST", ""))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("PHOTO-008"));
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(503);
        }
        // 저장 장애가 끝난 다음 요청은 실행권을 다시 얻어 입력 검증까지 진행한다.
        send("{}").andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_post where member_id=?",
                                Long.class,
                                member))
                .isZero();
    }

    @Test
    void checksMemberAgainAfterStorageAndCompensatesUnpublishedFiles() throws Exception {
        doAnswer(
                        i -> {
                            files.put(i.getArgument(0), i.getArgument(1));
                            jdbc.update(
                                    "update member set status='WITHDRAWN',deleted_at=now() where id=?",
                                    member);
                            return null;
                        })
                .when(storage)
                .write(anyString(), any(byte[].class));
        send(payload(UUID.randomUUID(), "LOST", ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
        assertThat(files).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_post where member_id=?",
                                Long.class,
                                member))
                .isZero();
    }

    @Test
    void concurrentSameKeyCommitsOnePostAndCompensatesLosingFiles() throws Exception {
        var results = concurrent(false);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(files).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_post where member_id=?",
                                Long.class,
                                member))
                .isEqualTo(1);
    }

    @Test
    void concurrentDifferentContentForSameKeyReturnsConflict() throws Exception {
        var results = concurrent(true);
        assertThat(results).contains("IDEMPOTENCY-001");
        assertThat(results.stream().filter(value -> value.startsWith("created:"))).hasSize(1);
        assertThat(files).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_post where member_id=?",
                                Long.class,
                                member))
                .isEqualTo(1);
    }

    @Test
    void canonicalReplayUsesNormalizedTextCoordinatesAndOriginalCreationState() throws Exception {
        String body =
                payload(UUID.randomUUID(), "LOST", ",\"name\":\" 가 \"")
                        .replace(
                                "\"exactLocationVisible\":false",
                                "\"exactLocationVisible\":false,\"latitude\":37.5,\"longitude\":127.0");
        var first =
                mapper.readTree(
                        send(body)
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        long id = first.path("data").path("postId").asLong();
        jdbc.update(
                "update animal_case set status='CLOSED',closed_at=now(),version=2 where id=?", id);
        String canonical =
                body.replace(" 가 ", "가")
                        .replace("37.5", "37.500000")
                        .replace("127.0", "127.000000");
        var replay =
                mapper.readTree(
                        send(canonical)
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(replay).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "select latitude from animal_case_location where animal_case_id=?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("37.500000");
    }

    private List<String> concurrent(boolean different) throws Exception {
        var barrier = new CyclicBarrier(2);
        doAnswer(
                        i -> {
                            files.put(i.getArgument(0), i.getArgument(1));
                            barrier.await(10, TimeUnit.SECONDS);
                            return null;
                        })
                .when(storage)
                .write(anyString(), any(byte[].class));
        String body = payload(UUID.randomUUID(), "LOST", "");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> directCreate(body));
            var second =
                    executor.submit(
                            () -> directCreate(different ? body.replace("DOG", "CAT") : body));
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }

    private String directCreate(String body) {
        try {
            return "created:"
                    + posts.create(
                                    member,
                                    inputs.validate(mapper.readTree(body)),
                                    List.of(
                                            new MockMultipartFile(
                                                    "photos", "fixture.jpg", "image/jpeg", photo)))
                            .postId();
        } catch (BusinessException ex) {
            return ex.errorCode().code();
        }
    }

    private ResultActions send(String body) throws Exception {
        var payload = new MockPart("payload", body.getBytes(StandardCharsets.UTF_8));
        payload.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var image = new MockPart("photos", "fixture.jpg", photo);
        image.getHeaders().setContentType(MediaType.IMAGE_JPEG);
        return mvc.perform(
                multipart("/api/v1/posts").part(payload, image).header("Authorization", token));
    }

    @Test
    void roundsExtremeSmallExponentsWithoutUnboundedDecimalArithmetic() throws Exception {
        String body =
                payload(UUID.randomUUID(), "LOST", "")
                        .replace(
                                "\"exactLocationVisible\":false",
                                "\"exactLocationVisible\":false,\"latitude\":1e-2147483647,\"longitude\":-1e-2147483647");
        var response = send(body).andExpect(status().isCreated()).andReturn().getResponse();
        long id =
                mapper.readTree(response.getContentAsString()).path("data").path("postId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select latitude from animal_case_location where animal_case_id=?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("0");
        assertThat(
                        jdbc.queryForObject(
                                "select longitude from animal_case_location where animal_case_id=?",
                                java.math.BigDecimal.class,
                                id))
                .isEqualByComparingTo("0");
    }

    @Test
    void rejectsInvalidCoordinatesCodesAndPhotosWithoutPersisting() throws Exception {
        String body = payload(UUID.randomUUID(), "LOST", "");
        for (String extra :
                new String[] {
                    ",\"latitude\":37.5",
                    ",\"latitude\":91,\"longitude\":127",
                    ",\"latitude\":37,\"longitude\":181",
                    ",\"latitude\":\"37\",\"longitude\":127",
                    ",\"emdCode\":\"1168010200\"",
                    ",\"emdCode\":\"9999910100\""
                }) {
            send(body.replace(
                            "\"exactLocationVisible\":false",
                            "\"exactLocationVisible\":false" + extra))
                    .andExpect(status().isBadRequest());
        }
        send(body.replace("11680", "99999")).andExpect(status().isBadRequest());
        var part = new MockPart("payload", body.getBytes(StandardCharsets.UTF_8));
        part.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        mvc.perform(multipart("/api/v1/posts").part(part).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO-001"));
        photo = new byte[] {1, 2, 3};
        send(body)
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("PHOTO-002"));
        assertThat(files).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from user_post where member_id=?",
                                Long.class,
                                member))
                .isZero();
    }

    private String payload(UUID id, String type, String extra) {
        return """
            {"clientRequestId":"%s","type":"%s","species":"DOG","sex":"UNKNOWN",
            "eventDate":"2020-01-01","eventLocation":{"regionCode":"11680",
            "exactLocation":"역삼역","exactLocationVisible":false}%s}
            """
                .formatted(id, type, extra);
    }
}
