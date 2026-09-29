package com.meonggo.backend.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** AD5·AD6 과 AD1 의 넘김 제외 (docs/api-spec.md 9장, 2026-09-25 개정). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdoptionSwipeApiTest {
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
    void recordsASwipeOnceAndKeepsTheFirstTime() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(10));

        putSwipe(postId, token).andExpect(status().isNoContent());
        Instant first = swipedAt(memberId, postId);
        assertThat(first).isNotNull();

        when(clock.instant()).thenReturn(NOW.plusSeconds(600));
        putSwipe(postId, token).andExpect(status().isNoContent());

        assertThat(swipeCount(memberId, postId)).isOne();
        assertThat(swipedAt(memberId, postId)).isEqualTo(first);
    }

    /** AD3 과 달리 후보 자격을 보지 않는다. 넘기는 사이 상태가 바뀌어 거절되면 그 동물이 다시 올라온다. */
    @Test
    void recordsASwipeWithoutCheckingCandidateEligibility() throws Exception {
        long ended = candidate("종료(입양)", TODAY.minusDays(2));
        long stillOnNotice = candidate("보호중", TODAY.plusDays(3));

        putSwipe(ended, token).andExpect(status().isNoContent());
        putSwipe(stillOnNotice, token).andExpect(status().isNoContent());

        assertThat(swipeCount(memberId, ended)).isOne();
        assertThat(swipeCount(memberId, stillOnNotice)).isOne();

        // 같은 동물을 찜하려 하면 AD3 은 자격을 보고 거절한다 — 두 API 의 판정 기준이 다르다.
        mvc.perform(
                        put("/api/v1/members/me/adoption-favorites/{postId}", ended)
                                .header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADOPTION-001"));
    }

    @Test
    void rejectsSwipesOnAnythingButAPublicShelteringCase() throws Exception {
        long userPost = userPostCase();

        putSwipe(userPost, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADOPTION-001"));
        putSwipe(Long.MAX_VALUE, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADOPTION-001"));
        assertThat(swipeCount(memberId, userPost)).isZero();
    }

    @Test
    void keepsSwipedAnimalsOutOfTheOwnersDeckOnly() throws Exception {
        long swiped = candidate("보호중", TODAY.minusDays(9));
        long untouched = candidate("보호중", TODAY.minusDays(8));
        long otherMember = member();
        String otherToken = token(otherMember);

        putSwipe(swiped, token).andExpect(status().isNoContent());

        assertThat(candidateIds(token)).containsExactly(untouched);
        assertThat(candidateIds(otherToken)).containsExactly(swiped, untouched);
    }

    /** 찜을 해제해도 넘김 행은 남으므로 그 동물은 후보로 돌아오지 않는다. */
    @Test
    void keepsASwipedAnimalOutOfTheDeckAfterItsFavouriteIsRemoved() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(7));

        mvc.perform(
                        put("/api/v1/members/me/adoption-favorites/{postId}", postId)
                                .header("Authorization", token))
                .andExpect(status().isNoContent());
        putSwipe(postId, token).andExpect(status().isNoContent());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                        "/api/v1/members/me/adoption-favorites/{postId}", postId)
                                .header("Authorization", token))
                .andExpect(status().isNoContent());

        assertThat(candidateIds(token)).isEmpty();
        assertThat(swipeCount(memberId, postId)).isOne();
    }

    @Test
    void listsSwipesNewestFirstWithCurrentFavouriteAndAvailability() throws Exception {
        long available = candidate("보호중", TODAY.minusDays(10));
        long lost = candidate("보호중", TODAY.minusDays(11));
        putSwipe(lost, token).andExpect(status().isNoContent());
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        putSwipe(available, token).andExpect(status().isNoContent());
        mvc.perform(
                        put("/api/v1/members/me/adoption-favorites/{postId}", available)
                                .header("Authorization", token))
                .andExpect(status().isNoContent());
        jdbc.update(
                "update shelter_animal set process_state_raw='종료(입양)' where animal_case_id=?",
                lost);

        JsonNode response = swipes(token, null);
        assertThat(postIds(response)).containsExactly(available, lost);

        JsonNode newest = response.path("data").path("items").get(0);
        assertThat(newest.path("postId").asLong()).isEqualTo(available);
        assertThat(newest.path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(newest.path("favorited").asBoolean()).isTrue();
        assertThat(newest.path("daysSinceNoticeEnd").asLong()).isEqualTo(10);
        assertThat(newest.path("swipedAt").asString()).isNotBlank();

        JsonNode oldest = response.path("data").path("items").get(1);
        assertThat(oldest.path("availability").asString()).isEqualTo("UNAVAILABLE");
        assertThat(oldest.path("favorited").asBoolean()).isFalse();

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
    }

    @Test
    void paginatesSwipesWithAMemberScopedCursor() throws Exception {
        var ascending = new ArrayList<Long>();
        for (int index = 0; index < 13; index++) {
            long postId = candidate("보호중", TODAY.minusDays(index + 1L));
            ascending.add(postId);
            when(clock.instant()).thenReturn(NOW.plusSeconds(index));
            putSwipe(postId, token).andExpect(status().isNoContent());
        }
        when(clock.instant()).thenReturn(NOW);

        JsonNode first = swipes(token, null);
        assertThat(first.path("data").path("items")).hasSize(10);
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        JsonNode second = swipes(token, cursor);
        assertThat(second.path("data").path("items")).hasSize(3);

        var actual = new ArrayList<Long>();
        actual.addAll(postIds(first));
        actual.addAll(postIds(second));
        assertThat(actual).containsExactlyElementsOf(ascending.reversed());

        mvc.perform(
                        get("/api/v1/members/me/adoption-swipes")
                                .header("Authorization", token(member()))
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        mvc.perform(
                        get("/api/v1/members/me/adoption-swipes")
                                .header("Authorization", token)
                                .param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void rejectsAnonymousAndWithdrawnAccess() throws Exception {
        long postId = candidate("보호중", TODAY.minusDays(2));

        mvc.perform(put("/api/v1/members/me/adoption-swipes/{postId}", postId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/members/me/adoption-swipes")).andExpect(status().isUnauthorized());

        long withdrawn = member();
        String withdrawnToken = token(withdrawn);
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", withdrawn);
        mvc.perform(
                        get("/api/v1/members/me/adoption-swipes")
                                .header("Authorization", withdrawnToken))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions putSwipe(long postId, String authorization) throws Exception {
        return mvc.perform(
                put("/api/v1/members/me/adoption-swipes/{postId}", postId)
                        .header("Authorization", authorization));
    }

    private JsonNode swipes(String authorization, String cursor) throws Exception {
        var request =
                get("/api/v1/members/me/adoption-swipes").header("Authorization", authorization);
        if (cursor != null) request.param("cursor", cursor);
        return mapper.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.message").value("넘긴 동물 목록을 조회했습니다."))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private List<Long> candidateIds(String authorization) throws Exception {
        JsonNode response =
                mapper.readTree(
                        mvc.perform(
                                        get("/api/v1/adoptions")
                                                .param("regionCode", regionCode)
                                                .header("Authorization", authorization))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        return postIds(response);
    }

    private List<Long> postIds(JsonNode response) {
        var ids = new ArrayList<Long>();
        response.path("data").path("items").forEach(item -> ids.add(item.path("postId").asLong()));
        return ids;
    }

    private int swipeCount(long ownerId, long postId) {
        return jdbc.queryForObject(
                "select count(*) from adoption_swipe where member_id=? and animal_case_id=?",
                Integer.class,
                ownerId,
                postId);
    }

    private Instant swipedAt(long ownerId, long postId) {
        return jdbc
                .query(
                        "select swiped_at from adoption_swipe where member_id=? and animal_case_id=?",
                        (row, index) -> row.getTimestamp("swiped_at").toInstant(),
                        ownerId,
                        postId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long userPostCase() {
        long postId =
                jdbc.queryForObject(
                        """
            insert into animal_case(
              case_type,source_type,status,listed_at,species,breed_name,sex,color,event_date,
              created_at,updated_at)
            values('LOST','USER','ACTIVE',now(),'DOG','믹스견','FEMALE','갈색','2026-01-01',
              now(),now()) returning id
            """,
                        Long.class);
        caseIds.add(postId);
        return postId;
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
              phone_lookup_hash,phone_verified_at,privacy_collection_agreed,
              privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture','넘김 회원','ACTIVE',now(),now(),'private-phone',?,now(),
              true,'privacy-collection-v1',now()) returning id
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
