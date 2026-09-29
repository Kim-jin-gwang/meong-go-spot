package com.meonggo.backend.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
class AdoptionFavoriteApiTest {
    private static final Instant NOW = Instant.parse("2026-09-16T15:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
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
        token = token(memberId);
        regionCode = String.format("%05d", Math.floorMod(memberId, 90000) + 10000);
    }

    @AfterEach
    void cleanup() {
        caseIds.forEach(id -> jdbc.update("delete from animal_case where id=?", id));
        memberIds.forEach(id -> jdbc.update("delete from member where id=?", id));
        shelterIds.forEach(id -> jdbc.update("delete from shelter where id=?", id));
    }

    @Test
    void addsListsAndRemovesAFavoriteIdempotentlyWithoutPrivateFields() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(10));

        putFavorite(postId, token).andExpect(status().isNoContent());
        putFavorite(postId, token).andExpect(status().isNoContent());
        assertThat(favoriteCount(memberId, postId)).isOne();

        JsonNode response = favorites(token, null);
        JsonNode item = response.path("data").path("items").get(0);
        assertThat(item.path("postId").asLong()).isEqualTo(postId);
        assertThat(item.path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(item.path("daysSinceNoticeEnd").asLong()).isEqualTo(10);
        assertThat(item.path("favoritedAt").asString()).isNotBlank();
        assertThat(response.toString())
                .doesNotContain(
                        "비공개 보호소",
                        "02-0000-0000",
                        "비공개 상세주소",
                        "현재 보호지역",
                        "private-location",
                        "processStateRaw",
                        "latitude",
                        "longitude");

        deleteFavorite(postId, token).andExpect(status().isNoContent());
        deleteFavorite(postId, token).andExpect(status().isNoContent());
        assertThat(favoriteCount(memberId, postId)).isZero();
        assertThat(favorites(token, null).path("data").path("items")).isEmpty();
    }

    @Test
    void rejectsNewIneligibleFavoritesButKeepsExistingOnesUnavailable() throws Exception {
        long eligible = candidate("보호중", TODAY.minusDays(2));
        long ineligible = candidate("종료(입양)", TODAY.minusDays(2));
        Instant latestSync = NOW.plusSeconds(90);

        putFavorite(ineligible, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADOPTION-001"));
        putFavorite(Long.MAX_VALUE, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADOPTION-001"));

        putFavorite(eligible, token).andExpect(status().isNoContent());
        jdbc.update(
                """
                update shelter_animal
                set process_state_raw='종료(입양)',last_synced_at=?
                where animal_case_id=?
                """,
                Timestamp.from(latestSync),
                eligible);
        putFavorite(eligible, token).andExpect(status().isNoContent());

        mvc.perform(
                        get("/api/v1/adoptions")
                                .param("regionCode", regionCode)
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
        JsonNode item = favorites(token, null).path("data").path("items").get(0);
        assertThat(item.path("postId").asLong()).isEqualTo(eligible);
        assertThat(item.path("availability").asString()).isEqualTo("UNAVAILABLE");
        assertThat(item.path("lastSyncedAt").asString()).isEqualTo(latestSync.toString());
        deleteFavorite(eligible, token).andExpect(status().isNoContent());
    }

    @Test
    void isolatesMembersAndRejectsAnonymousOrWithdrawnAccess() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(2));
        long otherMember = member();
        String otherToken = token(otherMember);
        putFavorite(postId, token).andExpect(status().isNoContent());

        assertThat(favorites(otherToken, null).path("data").path("items")).isEmpty();
        deleteFavorite(postId, otherToken).andExpect(status().isNoContent());
        assertThat(favoriteCount(memberId, postId)).isOne();

        mvc.perform(get("/api/v1/members/me/adoption-favorites"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/members/me/adoption-favorites/{postId}", postId))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/members/me/adoption-favorites/{postId}", postId))
                .andExpect(status().isUnauthorized());

        jdbc.update(
                "update member set status='WITHDRAWN',deleted_at=now() where id=?", otherMember);
        mvc.perform(
                        get("/api/v1/members/me/adoption-favorites")
                                .header("Authorization", otherToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paginatesNewestFavoritesByTimestampAndPostIdWithMemberScopedCursor() throws Exception {
        List<Long> ascending = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            long postId = candidate("보호중", TODAY.minusDays(index + 1L));
            ascending.add(postId);
            jdbc.update(
                    "insert into adoption_favorite(member_id,animal_case_id,created_at) values(?,?,?)",
                    memberId,
                    postId,
                    Timestamp.from(NOW));
        }

        JsonNode first = favorites(token, null);
        assertThat(first.path("data").path("items")).hasSize(10);
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        JsonNode second = favorites(token, cursor);
        assertThat(second.path("data").path("items")).hasSize(3);

        List<Long> actual = new ArrayList<>();
        first.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        second.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        assertThat(actual).containsExactlyElementsOf(ascending.reversed());

        long otherMember = member();
        mvc.perform(
                        get("/api/v1/members/me/adoption-favorites")
                                .header("Authorization", token(otherMember))
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        mvc.perform(
                        get("/api/v1/members/me/adoption-favorites")
                                .header("Authorization", token)
                                .param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void concurrentDuplicatePutCreatesOneFavorite() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(2));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentPut(postId, ready, start));
            var second = executor.submit(() -> concurrentPut(postId, ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get()).isEqualTo(204);
            assertThat(second.get()).isEqualTo(204);
        }
        assertThat(favoriteCount(memberId, postId)).isOne();
    }

    private int concurrentPut(long postId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        start.await();
        return mvc.perform(
                        put("/api/v1/members/me/adoption-favorites/{postId}", postId)
                                .header("Authorization", token))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private org.springframework.test.web.servlet.ResultActions putFavorite(
            long postId, String authorization) throws Exception {
        return mvc.perform(
                put("/api/v1/members/me/adoption-favorites/{postId}", postId)
                        .header("Authorization", authorization));
    }

    private org.springframework.test.web.servlet.ResultActions deleteFavorite(
            long postId, String authorization) throws Exception {
        return mvc.perform(
                delete("/api/v1/members/me/adoption-favorites/{postId}", postId)
                        .header("Authorization", authorization));
    }

    private JsonNode favorites(String authorization, String cursor) throws Exception {
        var request =
                get("/api/v1/members/me/adoption-favorites").header("Authorization", authorization);
        if (cursor != null) request.param("cursor", cursor);
        return mapper.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.message").value("입양 찜 목록을 조회했습니다."))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private int favoriteCount(long ownerId, long postId) {
        return jdbc.queryForObject(
                "select count(*) from adoption_favorite where member_id=? and animal_case_id=?",
                Integer.class,
                ownerId,
                postId);
    }

    private long candidate(String processState, LocalDate noticeEndDate) {
        long postId =
                jdbc.queryForObject(
                        """
            insert into animal_case(
              case_type,source_type,status,listed_at,species,breed_name,sex,color,event_date,
              created_at,updated_at)
            values('SHELTERING','PUBLIC','ACTIVE',now(),'DOG','믹스견','FEMALE','갈색',
              '2026-01-01',now(),now()) returning id
                        """,
                        Long.class);
        caseIds.add(postId);
        jdbc.update(
                """
            insert into animal_case_location(
              animal_case_id,location_type,region_code,public_location,latitude,longitude)
            values(?,'EVENT',?,'사건 공개지역',37.5,127.0)
            """,
                postId,
                regionCode);
        jdbc.update(
                """
            insert into animal_case_location(
              animal_case_id,location_type,region_code,public_location,exact_location_ciphertext,
              latitude,longitude)
            values(?,'CURRENT',?,'현재 보호지역','private-location',37.6,127.1)
            """,
                postId,
                regionCode);
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
        jdbc.update(
                """
            insert into animal_photo(
              animal_case_id,storage_type,storage_uri,content_type,byte_size,width_px,height_px,
              sort_order,checksum_sha256,created_at)
            values(?,'PUBLIC_URL',?,'image/jpeg',3,512,512,0,?,now())
            """,
                postId,
                "https://images.example.com/" + postId + ".jpg",
                "a".repeat(64));
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
            values(?,'fixture','입양 찜 회원','ACTIVE',now(),now(),'private-phone',?,now(),
              true, 'privacy-collection-v1',now()) returning id
            """,
                        Long.class,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
        memberIds.add(id);
        return id;
    }

    private String token(long ownerId) {
        return "Bearer " + sessions.create(ownerId).accessToken();
    }
}
