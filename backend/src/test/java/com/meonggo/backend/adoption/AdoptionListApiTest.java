package com.meonggo.backend.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdoptionListApiTest {
    private static final Instant NOW = Instant.parse("2026-09-16T15:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private SessionService sessions;
    @MockitoBean private LoginService login;

    @MockitoBean(name = "dataSourceClock")
    private Clock clock;

    private long memberId;
    private String token;
    private String regionCode;
    private final List<Long> caseIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> shelterIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        when(clock.instant()).thenReturn(NOW);
        memberId = member();
        token = "Bearer " + sessions.create(memberId).accessToken();
        regionCode = String.format("%05d", Math.floorMod(memberId, 90000) + 10000);
    }

    @AfterEach
    void cleanup() {
        caseIds.forEach(id -> jdbc.update("delete from animal_case where id=?", id));
        memberIds.forEach(id -> jdbc.update("delete from member where id=?", id));
        shelterIds.forEach(id -> jdbc.update("delete from shelter where id=?", id));
    }

    @Test
    void exposesOnlyTheAdoptionContractAndKeepsAnonymousClientsCompatible() throws Exception {
        long postId =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        LocalDate.of(2026, 3, 20),
                        "https://images.example.com/animal.jpg",
                        null,
                        null);
        jdbc.update(
                "insert into adoption_favorite(member_id,animal_case_id,created_at) values(?,?,?)",
                memberId,
                postId,
                Timestamp.from(NOW));

        JsonNode owned = list(regionCode, null, null, token);
        JsonNode item = owned.path("data").path("items").get(0);
        assertThat(item.path("postId").asLong()).isEqualTo(postId);
        assertThat(item.path("species").asString()).isEqualTo("DOG");
        assertThat(item.path("breedName").isNull()).isTrue();
        assertThat(item.path("color").isNull()).isTrue();
        assertThat(item.path("sex").asString()).isEqualTo("FEMALE");
        assertThat(item.path("publicLocation").asString()).isEqualTo("사건 공개지역");
        assertThat(item.path("thumbnailUrl").asString())
                .isEqualTo("https://images.example.com/animal.jpg");
        assertThat(item.path("noticeEndDate").asString()).isEqualTo("2026-03-20");
        assertThat(item.path("daysSinceNoticeEnd").asLong()).isEqualTo(181);
        assertThat(item.path("lastSyncedAt").asString()).isEqualTo(NOW.toString());
        assertThat(item.path("favorited").asBoolean()).isTrue();
        assertThat(item.propertyNames())
                .doesNotContain(
                        "shelter",
                        "shelterName",
                        "phone",
                        "address",
                        "currentLocation",
                        "exactLocation",
                        "latitude",
                        "longitude",
                        "processStateRaw",
                        "featureText");

        // 찜은 회원별이다 — 같은 카드라도 찜하지 않은 회원에게는 꺼져 있다.
        String otherToken = "Bearer " + sessions.create(member()).accessToken();
        JsonNode otherMember = list(regionCode, null, null, otherToken);
        assertThat(otherMember.path("data").path("items").get(0).path("favorited").asBoolean())
                .isFalse();

        // 1.5 앱은 비로그인 목록 조회를 지원한다. 1.6 backend를 먼저 배포해도 이 계약은 유지한다.
        JsonNode anonymous = list(regionCode, null, null, null);
        assertThat(anonymous.path("data").path("items").get(0).path("favorited").asBoolean())
                .isFalse();
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void narrowsBySexAndDropsAnimalsWhoseSourceSexIsUnknown() throws Exception {
        long female =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        TODAY.minusDays(5),
                        "https://images.example.com/female.jpg",
                        null,
                        null);
        long male =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        TODAY.minusDays(4),
                        "https://images.example.com/male.jpg",
                        null,
                        null);
        long unknown =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        TODAY.minusDays(3),
                        "https://images.example.com/unknown.jpg",
                        null,
                        null);
        jdbc.update("update animal_case set sex='MALE' where id=?", male);
        jdbc.update("update animal_case set sex='UNKNOWN' where id=?", unknown);

        assertThat(postIds(list(regionCode, null, null, token)))
                .containsExactly(female, male, unknown);
        assertThat(postIds(sexed("FEMALE"))).containsExactly(female);
        assertThat(postIds(sexed("MALE"))).containsExactly(male);

        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .param("sex", "UNKNOWN")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
    }

    private JsonNode sexed(String sex) throws Exception {
        return mapper.readTree(
                mvc.perform(
                                get("/api/v1/adoptions")
                                        .param("regionCode", regionCode)
                                        .param("sex", sex)
                                        .header("Authorization", token))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void includesOnlyCurrentlyEligiblePublicShelterAnimalsWithSafePhotos() throws Exception {
        long eligible =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        TODAY.minusDays(1),
                        "https://images.example.com/eligible.jpg",
                        "믹스견",
                        "갈색");
        candidate(
                regionCode,
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "보호중",
                TODAY,
                "https://images.example.com/today.jpg",
                null,
                null);
        candidate(
                regionCode,
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "종료(입양)",
                TODAY.minusDays(2),
                "https://images.example.com/adopted.jpg",
                null,
                null);
        candidate(
                regionCode,
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "보호종료",
                TODAY.minusDays(2),
                "https://images.example.com/unknown-state.jpg",
                null,
                null);
        candidate(
                regionCode,
                "DOG",
                "CLOSED",
                "PUBLIC",
                "보호중",
                TODAY.minusDays(2),
                "https://images.example.com/closed.jpg",
                null,
                null);
        candidate(
                regionCode,
                "DOG",
                "ACTIVE",
                "USER",
                "보호중",
                TODAY.minusDays(2),
                "https://images.example.com/user.jpg",
                null,
                null);
        candidate(
                regionCode, "DOG", "ACTIVE", "PUBLIC", "보호중", TODAY.minusDays(2), null, null, null);
        candidate(
                regionCode,
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "보호중",
                TODAY.minusDays(2),
                "https://user:password@example.com/unsafe.jpg",
                null,
                null);
        candidate(
                otherRegion(),
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "보호중",
                TODAY.minusDays(2),
                "https://images.example.com/region.jpg",
                null,
                null);
        candidate(
                regionCode,
                "CAT",
                "ACTIVE",
                "PUBLIC",
                "보호중",
                TODAY.minusDays(2),
                "https://images.example.com/cat.jpg",
                null,
                null);

        JsonNode items = list(regionCode, "DOG", null, token).path("data").path("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("postId").asLong()).isEqualTo(eligible);
    }

    @Test
    void waitsForAConcurrentStatusChangeAndDoesNotExposeTheEndedCandidate() throws Exception {
        long postId =
                candidate(
                        regionCode,
                        "DOG",
                        "ACTIVE",
                        "PUBLIC",
                        "보호중",
                        TODAY.minusDays(2),
                        "https://images.example.com/concurrent.jpg",
                        null,
                        null);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection ingestion = dataSource.getConnection()) {
            ingestion.setAutoCommit(false);
            int ingestionPid;
            try (var pid = ingestion.prepareStatement("select pg_backend_pid()")) {
                try (var result = pid.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    ingestionPid = result.getInt(1);
                }
            }
            try (var update =
                    ingestion.prepareStatement(
                            "update shelter_animal set process_state_raw='종료(입양)' where animal_case_id=?")) {
                update.setLong(1, postId);
                assertThat(update.executeUpdate()).isOne();
            }

            var requestStarted = new CountDownLatch(1);
            var response =
                    executor.submit(
                            () -> {
                                requestStarted.countDown();
                                return list(regionCode, null, null, token);
                            });
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilBlockedBy(ingestionPid, response)).isTrue();

            ingestion.commit();
            assertThat(response.get(5, TimeUnit.SECONDS).path("data").path("items")).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean waitUntilBlockedBy(int blockerPid, java.util.concurrent.Future<?> response)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!response.isDone() && System.nanoTime() < deadline) {
            boolean blocked =
                    Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    """
                                    select exists(
                                      select 1 from pg_stat_activity
                                      where ?=any(pg_blocking_pids(pid))
                                    )
                                    """,
                                    Boolean.class,
                                    blockerPid));
            if (blocked) return true;
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void paginatesTiedNoticeDatesWithoutDuplicatesAndBindsCursorScope() throws Exception {
        List<Long> expected = new ArrayList<>();
        LocalDate noticeEndDate = TODAY.minusDays(30);
        for (int index = 0; index < 13; index++) {
            expected.add(
                    candidate(
                            regionCode,
                            "DOG",
                            "ACTIVE",
                            "PUBLIC",
                            "보호중",
                            noticeEndDate,
                            "https://images.example.com/" + index + ".jpg",
                            null,
                            null));
        }

        JsonNode first = list(regionCode, "DOG", null, token);
        assertThat(first.path("data").path("items")).hasSize(10);
        assertThat(first.path("data").path("page").path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        JsonNode second = list(regionCode, "DOG", cursor, token);
        assertThat(second.path("data").path("items")).hasSize(3);
        assertThat(second.path("data").path("page").path("hasNext").asBoolean()).isFalse();

        List<Long> actual = new ArrayList<>();
        first.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        second.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        assertThat(actual).containsExactlyElementsOf(expected);

        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .param("species", "CAT")
                                .param("cursor", cursor)
                                .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(1)));
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .param("species", "DOG")
                                .param("cursor", cursor)
                                .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void validatesQueriesAndReturnsAnExplicitEmptyPage() throws Exception {
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", "1")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", "111")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", "1234a")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .param("species", "OTHER")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode, otherRegion())
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .param("unexpected", "value")
                                .header("Authorization", token))
                .andExpect(status().isBadRequest());

        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("입양 후보 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.asOfDate").value(TODAY.toString()))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page.size").value(10))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
    }

    @Test
    void widensToTheProvinceAndNationwideLikeThePostList() throws Exception {
        String province = regionCode.substring(0, 2);
        String sameProvince = province + (regionCode.endsWith("999") ? "998" : "999");
        String otherProvince = (province.equals("99") ? "98" : "99") + "000";
        long district = eligibleCandidate(regionCode, TODAY.minusDays(3));
        long neighbour = eligibleCandidate(sameProvince, TODAY.minusDays(2));
        long farAway = eligibleCandidate(otherProvince, TODAY.minusDays(1));

        assertThat(postIds(list(regionCode, null, null, token))).containsExactly(district);
        assertThat(postIds(list(province, null, null, token))).containsExactly(district, neighbour);
        assertThat(postIds(list(null, null, null, token))).contains(district, neighbour, farAway);
    }

    private long eligibleCandidate(String region, LocalDate noticeEndDate) {
        return candidate(
                region,
                "DOG",
                "ACTIVE",
                "PUBLIC",
                "보호중",
                noticeEndDate,
                "https://images.example.com/" + region + ".jpg",
                "믹스견",
                "갈색");
    }

    private List<Long> postIds(JsonNode response) {
        var ids = new ArrayList<Long>();
        response.path("data").path("items").forEach(item -> ids.add(item.path("postId").asLong()));
        return ids;
    }

    private JsonNode list(String region, String species, String cursor, String authorization)
            throws Exception {
        var request = get("/api/v1/adoptions");
        if (region != null) request.param("regionCode", region);
        if (species != null) request.param("species", species);
        if (cursor != null) request.param("cursor", cursor);
        if (authorization != null) request.header("Authorization", authorization);
        return mapper.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private long candidate(
            String region,
            String species,
            String status,
            String source,
            String processState,
            LocalDate noticeEndDate,
            String photoUrl,
            String breedName,
            String color) {
        long postId =
                jdbc.queryForObject(
                        """
            insert into animal_case(
              case_type,source_type,status,listed_at,species,breed_name,sex,color,event_date,
              closed_at,created_at,updated_at)
            values('SHELTERING',?,?,now(),?,?,'FEMALE',?,'2026-01-01',
              case when ?='CLOSED' then now() end,now(),now()) returning id
            """,
                        Long.class,
                        source,
                        status,
                        species,
                        breedName,
                        color,
                        status);
        caseIds.add(postId);
        jdbc.update(
                """
            insert into animal_case_location(
              animal_case_id,location_type,region_code,public_location,latitude,longitude)
            values(?,'EVENT',?,'사건 공개지역',37.5,127.0)
            """,
                postId,
                region);
        jdbc.update(
                """
            insert into animal_case_location(
              animal_case_id,location_type,region_code,public_location,exact_location_ciphertext,
              latitude,longitude)
            values(?,'CURRENT',?,'현재 보호지역','private-location',37.6,127.1)
            """,
                postId,
                region);
        long shelterId =
                jdbc.queryForObject(
                        """
            insert into shelter(care_reg_no,name,phone,address,created_at,updated_at)
            values(?,'비공개 보호소','02-0000-0000','비공개 상세주소',now(),now()) returning id
            """,
                        Long.class,
                        UUID.randomUUID().toString());
        shelterIds.add(shelterId);
        jdbc.update(
                """
            insert into shelter_animal(
              animal_case_id,desertion_no,shelter_id,notice_start_date,notice_end_date,
              process_state_raw,neuter_status,last_synced_at)
            values(?,?,?,'2026-01-01',?,?,'UNKNOWN',?)
            """,
                postId,
                UUID.randomUUID().toString(),
                shelterId,
                noticeEndDate,
                processState,
                Timestamp.from(NOW));
        if (photoUrl != null) {
            jdbc.update(
                    """
                insert into animal_photo(
                  animal_case_id,storage_type,storage_uri,content_type,byte_size,width_px,
                  height_px,sort_order,checksum_sha256,created_at)
                values(?,'PUBLIC_URL',?,'image/jpeg',3,512,512,0,?,now())
                """,
                    postId,
                    photoUrl,
                    "a".repeat(64));
        }
        return postId;
    }

    private long member() {
        long id =
                jdbc.queryForObject(
                        """
            insert into member(
              login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,
              phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,
              privacy_collection_consented_at)
            values(?,'fixture','입양 조회자','ACTIVE',now(),now(),'private-phone',?,now(),
              true, 'privacy-collection-v1',now()) returning id
            """,
                        Long.class,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
        memberIds.add(id);
        return id;
    }

    private String otherRegion() {
        return regionCode.equals("99999") ? "99998" : "99999";
    }
}
