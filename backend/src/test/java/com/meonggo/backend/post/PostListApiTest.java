package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostListApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private LoginService login;
    private long member;
    private String token;
    private String region;

    @BeforeEach
    void setup() {
        member = member();
        token = "Bearer " + sessions.create(member).accessToken();
        // 다른 테스트의 데이터를 지우지 않고 조회 범위를 분리한다.
        region = String.format("%05d", Math.floorMod(member, 90000) + 10000);
    }

    @Test
    void publicLostUsesDeterministicCursorWithoutLeakingPrivateColumns() throws Exception {
        List<Long> ids = new ArrayList<>();
        Instant time = Instant.parse("2026-01-01T00:00:00.123456Z");
        for (int i = 0; i < 13; i++) ids.add(post("LOST", "USER", member, "ACTIVE", time));
        post("LOST", "USER", member, "CLOSED", time.plusSeconds(1));
        post("LOST", "USER", member, "DELETED", time.plusSeconds(2));
        var first = list("LOST", region, null);
        assertThat(first.path("data").path("items")).hasSize(10);
        assertThat(first.path("data").path("page").path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        long newlyInserted = post("LOST", "USER", member, "ACTIVE", time.plusSeconds(3));
        var second = list("LOST", region, cursor);
        assertThat(second.path("data").path("items")).hasSize(3);
        var actual = new ArrayList<Long>();
        first.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        second.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        assertThat(actual).containsExactlyElementsOf(ids.reversed()).doesNotContain(newlyInserted);
        assertThat(second.path("data").path("page").has("nextCursor")).isFalse();
        for (JsonNode item : first.path("data").path("items")) {
            assertThat(item.propertyNames())
                    .doesNotContain(
                            "author",
                            "shelter",
                            "featureText",
                            "currentLocation",
                            "exactLocation",
                            "latitude",
                            "longitude",
                            "status",
                            "version");
            assertThat(item.path("publicLocation").asString()).isEqualTo("사건 공개지역");
            assertThat(item.path("thumbnailUrl").asString()).startsWith("/api/v1/photos/");
        }
    }

    @Test
    void oldestSortUsesAscendingTieBreakerAndBindsCursorToSort() throws Exception {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 13; i++) ids.add(post("LOST", "USER", member, "ACTIVE", time));

        JsonNode first = list("LOST", region, null, "OLDEST");
        assertThat(first.path("data").path("items")).hasSize(10);
        assertThat(first.path("data").path("page").path("hasNext").asBoolean()).isTrue();
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        JsonNode second = list("LOST", region, cursor, "OLDEST");

        List<Long> actual = new ArrayList<>();
        first.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        second.path("data").path("items").forEach(item -> actual.add(item.path("postId").asLong()));
        assertThat(actual).containsExactlyElementsOf(ids);
        assertThat(second.path("data").path("page").path("hasNext").asBoolean()).isFalse();

        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "LOST")
                                .param("regionCode", region)
                                .param("sort", "LATEST")
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));

        String latestCursor =
                list("LOST", region, null, "LATEST")
                        .path("data")
                        .path("page")
                        .path("nextCursor")
                        .asString();
        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "LOST")
                                .param("regionCode", region)
                                .param("sort", "OLDEST")
                                .param("cursor", latestCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void mixesPublicAndUserShelteringUsingEventCodeAndStoredListingTime() throws Exception {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        long user = post("SHELTERING", "USER", member, "ACTIVE", time);
        long shelter = post("SHELTERING", "PUBLIC", member, "ACTIVE", time.plusSeconds(1));
        long excluded = post("SHELTERING", "USER", member, "ACTIVE", time.plusSeconds(2));
        jdbc.update(
                "update animal_case_location set region_code='99999' where animal_case_id=? and location_type='EVENT'",
                excluded);
        var result = list("SHELTERING", region, null);
        var items = result.path("data").path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("postId").asLong()).isEqualTo(shelter);
        assertThat(items.get(1).path("postId").asLong()).isEqualTo(user);
        assertThat(items.get(0).path("source").asString()).isEqualTo("SHELTER");
        var oldestIds = new ArrayList<Long>();
        list("SHELTERING", region, null, "OLDEST")
                .path("data")
                .path("items")
                .forEach(item -> oldestIds.add(item.path("postId").asLong()));
        assertThat(oldestIds).containsExactly(user, shelter);
        assertThat(items.get(0).path("thumbnailUrl").asString())
                .isEqualTo("https://images.example.com/animal.jpg");
        assertThat(result.toString())
                .doesNotContain(
                        "공식 보호소", "02-1234", "보호소 전체주소", "currentLocation", "private-phone");
        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "SHELTERING")
                                .param("regionCode", region)
                                .param("source", "USER_POST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        // 시·도 전체(2자리 접두)는 같은 두 건을 포함하고, 다른 시·도 접두에서는 둘 다 빠진다.
        // (다른 테스트가 남긴 공개 게시물이 같은 DB 에 있을 수 있어 건수 대신 포함 여부로 본다.)
        String prefix = region.substring(0, 2);
        var provinceIds = new java.util.ArrayList<Long>();
        list("SHELTERING", prefix, null)
                .path("data")
                .path("items")
                .forEach(item -> provinceIds.add(item.path("postId").asLong()));
        assertThat(provinceIds).contains(shelter, user);
        var otherIds = new java.util.ArrayList<Long>();
        list("SHELTERING", prefix.equals("99") ? "98" : "99", null)
                .path("data")
                .path("items")
                .forEach(item -> otherIds.add(item.path("postId").asLong()));
        assertThat(otherIds).doesNotContain(shelter, user);
    }

    @Test
    void ownListIncludesRetainedClosedButExcludesOthersAndPublicAnimals() throws Exception {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        post("LOST", "USER", member, "ACTIVE", time);
        long closed = post("SHELTERING", "USER", member, "CLOSED", time);
        long expired = post("LOST", "USER", member, "CLOSED", time);
        jdbc.update(
                "update animal_case set closed_at=now()-interval '90 days' where id=?", expired);
        post("LOST", "USER", member(), "ACTIVE", time);
        post("SHELTERING", "PUBLIC", member, "ACTIVE", time);
        post("LOST", "USER", member, "DELETED", time);
        var response =
                mvc.perform(get("/api/v1/members/me/posts").header("Authorization", token))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items.length()").value(2))
                        .andReturn()
                        .getResponse();
        assertThat(response.getContentAsString())
                .doesNotContain("private-phone", "ciphertext", "exactLocation", "currentLocation");
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .param("status", "CLOSED")
                                .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].postId").value(closed))
                .andExpect(jsonPath("$.data.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.data.items[0].version").value(0));
        mvc.perform(get("/api/v1/members/me/posts")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidFilterCombinationsMalformedCursorAndRepeatedQuery() throws Exception {
        mvc.perform(get("/api/v1/posts")).andExpect(status().isBadRequest());
        // 지역이 없으면 전국 목록이다 (2026-09-17). 형식이 틀린 코드만 거부한다.
        mvc.perform(get("/api/v1/posts").param("type", "SHELTERING")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/posts").param("type", "SHELTERING").param("regionCode", "1144"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/posts").param("type", "SHELTERING").param("regionCode", "11a40"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("source", "USER_POST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST-006"));
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        mvc.perform(get("/api/v1/posts").param("type", "LOST", "SHELTERING"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("region", "임의지역"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("sort", "NEWEST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("sort", "LATEST", "OLDEST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .header("Authorization", token)
                                .param("sort", "OLDEST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
    }

    @Test
    void filtersCombineNormalizedLiteralTextSpeciesSexAndSource() throws Exception {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        long match = post("SHELTERING", "PUBLIC", member, "ACTIVE", time);
        long decoy = post("SHELTERING", "PUBLIC", member, "ACTIVE", time.plusSeconds(1));
        jdbc.update(
                "update animal_case set breed_name=?,color=?,sex='FEMALE' where id=?",
                "Café%_\\믹스",
                "검정_흰색",
                match);
        jdbc.update(
                "update animal_case set breed_name=?,color=?,sex='FEMALE' where id=?",
                "CafeXX믹스",
                "검정X흰색",
                decoy);
        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "SHELTERING")
                                .param("regionCode", region)
                                .param("source", "SHELTER")
                                .param("species", "DOG")
                                .param("sex", "FEMALE")
                                .param("breedName", "  Cafe\u0301%_\\  ")
                                .param("color", "정_흰"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].postId").value(match));
        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "SHELTERING")
                                .param("regionCode", region)
                                .param("species", "CAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page.size").value(10))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
        for (String value : List.of("a".repeat(101), "\u200b", "\n", "\ud800")) {
            mvc.perform(get("/api/v1/posts").param("type", "LOST").param("color", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON-001"));
        }
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .header("Authorization", token)
                                .param("status", "DELETED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownPaginationBindsCursorToViewerAndFilters() throws Exception {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) ids.add(post("LOST", "USER", member, "ACTIVE", time));
        assertThat(list("LOST", region, null).path("data").path("page").path("hasNext").asBoolean())
                .isFalse();
        ids.add(post("LOST", "USER", member, "ACTIVE", time));
        var first =
                mapper.readTree(
                        mvc.perform(get("/api/v1/members/me/posts").header("Authorization", token))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String cursor = first.path("data").path("page").path("nextCursor").asString();
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .header("Authorization", token)
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].postId").value(ids.getFirst()));
        String otherToken = "Bearer " + sessions.create(member()).accessToken();
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .header("Authorization", otherToken)
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        mvc.perform(
                        get("/api/v1/members/me/posts")
                                .header("Authorization", token)
                                .param("type", "LOST")
                                .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        mvc.perform(get("/api/v1/posts").param("type", "LOST").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
        String publicCursor =
                list("LOST", region, null).path("data").path("page").path("nextCursor").asString();
        mvc.perform(
                        get("/api/v1/posts")
                                .param("type", "LOST")
                                .param("regionCode", region)
                                .param("sex", "UNKNOWN")
                                .param("cursor", publicCursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void withdrawnAuthorsAndUnsafePublicThumbnailsStayHidden() throws Exception {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        long withdrawn = member();
        post("LOST", "USER", withdrawn, "ACTIVE", time);
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", withdrawn);
        assertThat(list("LOST", region, null).path("data").path("items")).isEmpty();
        long publicPost = post("SHELTERING", "PUBLIC", member, "ACTIVE", time);
        for (String uri :
                List.of(
                        "file:///etc/passwd",
                        "https://user:password@example.com/photo.jpg",
                        "https://example.com:0/a",
                        "https://example.com:65536/a")) {
            jdbc.update(
                    "update animal_photo set storage_uri=? where animal_case_id=?",
                    uri,
                    publicPost);
            assertThat(
                            list("SHELTERING", region, null)
                                    .path("data")
                                    .path("items")
                                    .get(0)
                                    .path("thumbnailUrl")
                                    .isNull())
                    .isTrue();
        }
    }

    @Test
    void publicOrderingUsesIngestionStoredPriorityWithoutReadTimeRecalculation() throws Exception {
        long notice =
                post(
                        "SHELTERING",
                        "PUBLIC",
                        member,
                        "ACTIVE",
                        Instant.parse("2026-01-03T00:00:00Z"));
        jdbc.update(
                "update shelter_animal set notice_start_date='2026-01-03' where animal_case_id=?",
                notice);
        long event =
                post(
                        "SHELTERING",
                        "PUBLIC",
                        member,
                        "ACTIVE",
                        Instant.parse("2026-01-02T00:00:00Z"));
        jdbc.update("update animal_case set event_date='2026-01-02' where id=?", event);
        long fallback =
                post(
                        "SHELTERING",
                        "PUBLIC",
                        member,
                        "ACTIVE",
                        Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update(
                "update animal_case set event_date='2026-01-01',created_at='2026-01-01T00:00:00Z' where id=?",
                fallback);
        long user =
                post("SHELTERING", "USER", member, "ACTIVE", Instant.parse("2026-01-02T12:00:00Z"));
        // 원천 날짜 보정은 수집기 책임이다. 읽기는 최초 저장된 정렬 키를 유지한다.
        jdbc.update(
                "update shelter_animal set notice_start_date='2026-02-01' where animal_case_id=?",
                fallback);
        List<Long> ids = new ArrayList<>();
        list("SHELTERING", region, null)
                .path("data")
                .path("items")
                .forEach(item -> ids.add(item.path("postId").asLong()));
        assertThat(ids).containsExactly(notice, user, event, fallback);
    }

    private JsonNode list(String type, String region, String cursor) throws Exception {
        return list(type, region, cursor, null);
    }

    private JsonNode list(String type, String region, String cursor, String sort) throws Exception {
        var request = get("/api/v1/posts").param("type", type).param("regionCode", region);
        if (cursor != null) request.param("cursor", cursor);
        if (sort != null) request.param("sort", sort);
        return mapper.readTree(
                mvc.perform(request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void lostListMixesUserPostsAndPublicLostReportsWithDistinctBadges() throws Exception {
        Instant time = Instant.parse("2026-02-01T00:00:00Z");
        long userPost = post("LOST", "USER", member, "ACTIVE", time);
        long publicLost = publicLost(time.plusSeconds(1));
        long shelterCase = post("SHELTERING", "PUBLIC", member, "ACTIVE", time.plusSeconds(2));
        var items = list("LOST", region, null).path("data").path("items");
        var byId = new java.util.HashMap<Long, String>();
        items.forEach(
                item -> byId.put(item.path("postId").asLong(), item.path("source").asString()));
        assertThat(byId)
                .containsEntry(userPost, "USER_POST")
                .containsEntry(publicLost, "PUBLIC_LOST")
                .doesNotContainKey(shelterCase);
        var shelterItems = list("SHELTERING", region, null).path("data").path("items");
        var shelterIds = new ArrayList<Long>();
        shelterItems.forEach(item -> shelterIds.add(item.path("postId").asLong()));
        assertThat(shelterIds).contains(shelterCase).doesNotContain(publicLost);
    }

    /** 공공 분실 신고 — shelter_animal 대신 lost_report, EVENT 위치만, 사진 1장. */
    private long publicLost(Instant listedAt) {
        long id =
                jdbc.queryForObject(
                        """
            insert into animal_case(case_type,source_type,status,is_matchable,listed_at,species,sex,event_date,feature_text,created_at,updated_at)
            values('LOST','PUBLIC','ACTIVE',false,?,'CAT','FEMALE','2026-01-31','겁이 많다',now(),now()) returning id
            """,
                        Long.class,
                        java.sql.Timestamp.from(listedAt));
        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,public_location) values(?,'EVENT',?,'사건 공개지역')",
                id,
                region);
        jdbc.update(
                "insert into lost_report(animal_case_id,lost_key,org_name,first_seen_date,last_seen_date,last_synced_at) values(?,?,'대전광역시 유성구',current_date,current_date,now())",
                id,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        jdbc.update(
                """
            insert into animal_photo(animal_case_id,storage_type,storage_uri,content_type,byte_size,width_px,height_px,sort_order,checksum_sha256,created_at)
            values(?,'PUBLIC_URL','https://images.example.com/lost.jpg','image/jpeg',3,512,512,0,?,now())
            """,
                id,
                "a".repeat(64));
        return id;
    }

    private long member() {
        return jdbc.queryForObject(
                """
            insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
              phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture','목록 작성자','ACTIVE',now(),now(),'private-phone',?,now(),true, 'privacy-collection-v1',now()) returning id
            """,
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private long post(String type, String source, long owner, String status, Instant listedAt) {
        long id =
                jdbc.queryForObject(
                        """
            insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,feature_text,
              closed_at,deleted_at,created_at,updated_at)
            values(?,?,?,?,'DOG','UNKNOWN','2020-01-01','private feature',
              case when ?='CLOSED' then now() end,case when ?='DELETED' then now() end,now(),now()) returning id
            """,
                        Long.class,
                        type,
                        source,
                        status,
                        java.sql.Timestamp.from(listedAt),
                        status,
                        status);
        for (String role : type.equals("LOST") ? List.of("EVENT") : List.of("EVENT", "CURRENT")) {
            jdbc.update(
                    """
                insert into animal_case_location(animal_case_id,location_type,region_code,public_location,latitude,longitude)
                values(?,?,?,?,37.5,127.0)
                """,
                    id,
                    role,
                    region,
                    role.equals("EVENT") ? "사건 공개지역" : "현재 보호지역");
        }
        if (source.equals("USER")) {
            jdbc.update(
                    "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                    id,
                    owner,
                    UUID.randomUUID(),
                    "a".repeat(64));
        } else {
            long shelter =
                    jdbc.queryForObject(
                            "insert into shelter(care_reg_no,name,phone,address,created_at,updated_at) values(?,'공식 보호소','02-1234','보호소 전체주소',now(),now()) returning id",
                            Long.class,
                            UUID.randomUUID().toString());
            jdbc.update(
                    "insert into shelter_animal(animal_case_id,desertion_no,shelter_id,neuter_status,last_synced_at) values(?,?,?,'UNKNOWN',now())",
                    id,
                    UUID.randomUUID().toString(),
                    shelter);
        }
        jdbc.update(
                """
            insert into animal_photo(animal_case_id,storage_type,storage_uri,content_type,byte_size,width_px,height_px,sort_order,checksum_sha256,created_at)
            values(?,?,?,'image/jpeg',3,512,512,0,?,now())
            """,
                id,
                source.equals("USER") ? "USER_UPLOAD" : "PUBLIC_URL",
                source.equals("USER")
                        ? "/data/user/images/" + id + "/1.jpg"
                        : "https://images.example.com/animal.jpg",
                "a".repeat(64));
        return id;
    }
}
