package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class PostDetailApiTest {
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
    void ownerGetsPrivateManagementLocationWithoutCoordinatesOrStorage() throws Exception {
        var result =
                detail(owner)
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Cache-Control",
                                                org.hamcrest.Matchers.containsString("no-store")))
                        .andExpect(jsonPath("$.data.source").value("USER_POST"))
                        .andExpect(jsonPath("$.data.version").value(0))
                        .andExpect(jsonPath("$.data.owner").value(true))
                        .andExpect(jsonPath("$.data.author.memberId").value(owner))
                        .andExpect(jsonPath("$.data.eventLocation.exactLocation").value("사건 비밀 위치"))
                        .andExpect(
                                jsonPath("$.data.eventLocation.exactLocationVisible").value(false))
                        .andExpect(jsonPath("$.data.currentLocation").doesNotExist())
                        .andExpect(jsonPath("$.data.chat.reason").value("OWN_POST"))
                        .andExpect(
                                jsonPath("$.data.photos[0].url")
                                        .value(org.hamcrest.Matchers.startsWith("/api/v1/photos/")))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "latitude",
                        "longitude",
                        "ciphertext",
                        "enc:v1",
                        "/data/",
                        "phone",
                        "disclosure",
                        "requestHash");
    }

    @Test
    void anonymousReadsDetailButNeverExactLocationEvenWhenDisclosed() throws Exception {
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", post);
        location("CURRENT", "현재 공개 위치", true);
        var result =
                mvc.perform(get("/api/v1/posts/" + post))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.source").value("USER_POST"))
                        .andExpect(jsonPath("$.data.owner").value(false))
                        .andExpect(jsonPath("$.data.eventLocation.publicLocation").value("테스트 시군구"))
                        .andExpect(jsonPath("$.data.eventLocation.exactLocation").doesNotExist())
                        // 공개 동의한 위치도 비로그인 상세에는 싣지 않는다 (post-date-location-policy.md).
                        .andExpect(jsonPath("$.data.currentLocation.exactLocation").doesNotExist())
                        .andExpect(jsonPath("$.data.chat.available").value(true))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("사건 비밀 위치", "현재 공개 위치", "exactLocationVisible");
    }

    @Test
    void invalidIdsUseDocumentedErrors() throws Exception {
        for (long id : new long[] {0, -1, Long.MAX_VALUE}) {
            mvc.perform(get("/api/v1/posts/" + id).header("Authorization", bearer(viewer)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("POST-001"));
            mvc.perform(get("/api/v1/posts/" + id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("POST-001"));
        }
    }

    @Test
    void roleDisclosureRequiresCurrentConsentAndNonblankText() throws Exception {
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", post);
        location("CURRENT", "현재 공개 위치", true);
        detail(viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventLocation.exactLocation").doesNotExist())
                .andExpect(jsonPath("$.data.currentLocation.exactLocation").value("현재 공개 위치"))
                .andExpect(jsonPath("$.data.currentLocation.exactLocationVisible").doesNotExist())
                .andExpect(jsonPath("$.data.chat.available").value(true))
                .andExpect(jsonPath("$.data.chat.reason").doesNotExist());
        jdbc.update(
                "update animal_case_location set disclosure_policy_version='exact-location-v0' where animal_case_id=? and location_type='CURRENT'",
                post);
        detail(viewer).andExpect(jsonPath("$.data.currentLocation.exactLocation").doesNotExist());
        jdbc.update(
                "update animal_case_location set disclosure_policy_version='exact-location-v1',exact_location_ciphertext=? where animal_case_id=? and location_type='CURRENT'",
                protection.encrypt("   "),
                post);
        detail(viewer).andExpect(jsonPath("$.data.currentLocation.exactLocation").doesNotExist());
    }

    @Test
    void closedRetentionSeparatesOwnerPhotosAndLocationFromOtherViewers() throws Exception {
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", post);
        detail(viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos").isEmpty())
                .andExpect(jsonPath("$.data.eventLocation.exactLocation").doesNotExist())
                .andExpect(jsonPath("$.data.chat.reason").value("POST_NOT_ACTIVE"));
        detail(owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos.length()").value(1))
                .andExpect(jsonPath("$.data.eventLocation.exactLocation").value("사건 비밀 위치"))
                .andExpect(jsonPath("$.data.chat.reason").value("POST_NOT_ACTIVE"));
        jdbc.update("update animal_case set closed_at=now()-interval '90 days' where id=?", post);
        detail(owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
        detail(viewer).andExpect(status().isNotFound());
    }

    @Test
    void deletedWithdrawnAndMissingRoleAreInvisible() throws Exception {
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", post);
        detail(viewer).andExpect(status().isNotFound());
        jdbc.update("update animal_case set status='ACTIVE',deleted_at=null where id=?", post);
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", owner);
        detail(viewer).andExpect(status().isNotFound());
        jdbc.update("update member set status='ACTIVE',deleted_at=null where id=?", owner);
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", post);
        detail(viewer).andExpect(status().isNotFound());
    }

    @Test
    void publicDetailKeepsNullPhoneAndOnlySafeOrderedPublicPhotos() throws Exception {
        post = animal("SHELTERING", "PUBLIC");
        location("EVENT", "공공 비밀", false);
        location("CURRENT", "공공 보호 장소", false);
        long shelter =
                jdbc.queryForObject(
                        "insert into shelter(care_reg_no,name,created_at,updated_at) values(?,'공식 센터',now(),now()) returning id",
                        Long.class,
                        UUID.randomUUID().toString());
        jdbc.update(
                "insert into shelter_animal(animal_case_id,desertion_no,shelter_id,neuter_status,last_synced_at) values(?,?,?,'UNKNOWN',now())",
                post,
                UUID.randomUUID().toString(),
                shelter);
        photo("PUBLIC_URL", "https://example.org/second.jpg", 5);
        photo("PUBLIC_URL", "https://example.org/first.jpg", 0);
        photo("PUBLIC_URL", "https://private:secret@example.org/a.jpg", 1);
        photo("PUBLIC_URL", "file:///data/private.jpg", 2);
        photo("PUBLIC_URL", "//example.org/a.jpg", 3);
        photo("USER_UPLOAD", "/data/private.jpg", 4);
        var result =
                detail(viewer)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.source").value("SHELTER"))
                        .andExpect(jsonPath("$.data.shelter.name").value("공식 센터"))
                        .andExpect(jsonPath("$.data.version").doesNotExist())
                        .andExpect(jsonPath("$.data.owner").doesNotExist())
                        .andExpect(jsonPath("$.data.author").doesNotExist())
                        .andExpect(jsonPath("$.data.chat").doesNotExist())
                        .andExpect(jsonPath("$.data.photos.length()").value(2))
                        .andExpect(
                                jsonPath("$.data.photos[0].url")
                                        .value("https://example.org/first.jpg"))
                        .andExpect(jsonPath("$.data.photos[1].sortOrder").value(5))
                        .andExpect(jsonPath("$.data.currentLocation.exactLocation").doesNotExist())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .contains("\"phone\":null")
                .doesNotContain("공공 비밀", "공공 보호 장소", "latitude", "longitude", "enc:v1", "/data/");
        jdbc.update(
                "update animal_case set status='CLOSED',closed_at=now()-interval '100 days' where id=?",
                post);
        detail(viewer).andExpect(status().isOk());
    }

    @Test
    void publicLostReportExposesOrgAndNoticeButNoContactShelterOrChat() throws Exception {
        post = animal("LOST", "PUBLIC");
        location("EVENT", "신고 비밀 주소", false);
        jdbc.update(
                "insert into lost_report(animal_case_id,lost_key,rfid_code,org_name,happen_place,first_seen_date,last_seen_date,last_synced_at) values(?,?,?,?,?,current_date,current_date,now())",
                post,
                "a".repeat(64),
                "410100129",
                "대전광역시 유성구",
                "유성고등학교 골목 사이");
        photo("PUBLIC_URL", "https://example.org/lost.jpg", 0);
        var result =
                detail(viewer)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.type").value("LOST"))
                        .andExpect(jsonPath("$.data.source").value("PUBLIC_LOST"))
                        .andExpect(jsonPath("$.data.report.orgName").value("대전광역시 유성구"))
                        .andExpect(jsonPath("$.data.report.happenPlace").value("유성고등학교 골목 사이"))
                        .andExpect(jsonPath("$.data.report.rfidCode").value("410100129"))
                        .andExpect(jsonPath("$.data.report.contactNotice").isNotEmpty())
                        .andExpect(
                                jsonPath("$.data.report.portalUrl")
                                        .value(
                                                "https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000"
                                                        + "&searchSDate="
                                                        + java.time.LocalDate.now()
                                                        + "&searchEDate="
                                                        + java.time.LocalDate.now()
                                                        + "&searchUpKindCd=417000"))
                        .andExpect(jsonPath("$.data.eventLocation.publicLocation").value("테스트 시군구"))
                        .andExpect(jsonPath("$.data.photos.length()").value(1))
                        .andExpect(jsonPath("$.data.shelter").doesNotExist())
                        .andExpect(jsonPath("$.data.author").doesNotExist())
                        .andExpect(jsonPath("$.data.chat").doesNotExist())
                        .andExpect(jsonPath("$.data.version").doesNotExist())
                        .andExpect(jsonPath("$.data.currentLocation").doesNotExist())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("신고 비밀 주소", "callTel", "callName", "latitude", "longitude");
    }

    @Test
    void missingEventOrSourceRelationUsesPostNotFound() throws Exception {
        jdbc.update("delete from animal_case_location where animal_case_id=?", post);
        detail(viewer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
        post = animal("LOST", "USER");
        location("EVENT", "비밀", false);
        detail(viewer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
        post = animal("SHELTERING", "PUBLIC");
        location("EVENT", "비밀", false);
        location("CURRENT", "비밀", false);
        detail(viewer)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
    }

    @Test
    void ownerManagesBothPrivateRolesAndUserPhotosAreOrdered() throws Exception {
        jdbc.update("update animal_case set case_type='SHELTERING' where id=?", post);
        location("CURRENT", "현재 비밀 위치", false);
        photo("USER_UPLOAD", "/data/ignored.jpg", 0);
        detail(owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLocation.exactLocation").value("현재 비밀 위치"))
                .andExpect(jsonPath("$.data.currentLocation.exactLocationVisible").value(false))
                .andExpect(jsonPath("$.data.photos[0].sortOrder").value(0))
                .andExpect(jsonPath("$.data.photos[1].sortOrder").value(1));
        jdbc.update(
                "update animal_case_location set disclosure_consented_at=null where animal_case_id=?",
                post);
        detail(viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLocation.exactLocation").doesNotExist())
                .andExpect(jsonPath("$.data.eventLocation.exactLocation").doesNotExist());
    }

    @Test
    void corruptDisclosedCiphertextFailsSafely() throws Exception {
        jdbc.update(
                "update animal_case_location set exact_location_ciphertext='sensitive-broken-envelope' where animal_case_id=?",
                post);
        var result =
                detail(owner)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-500"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("sensitive", "Exception");
        detail(viewer).andExpect(status().isOk());
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
                "insert into animal_case_location(animal_case_id,location_type,region_code,public_location,exact_location_ciphertext,exact_location_visible,disclosure_policy_version,disclosure_consented_at,latitude,longitude) values(?,?,'11680','테스트 시군구',?,?,'exact-location-v1',now(),37.5,127.0)",
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
