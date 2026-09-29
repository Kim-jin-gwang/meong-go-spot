package com.meonggo.backend.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.exception.PhotoErrorCode;
import com.meonggo.backend.photo.storage.PhotoStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @MockitoBean private LoginService login;
    @MockitoBean private PhotoStorage storage;
    private long owner;
    private long postId;
    private long photoId;

    @BeforeEach
    void setup() {
        owner = member();
        postId =
                jdbc.queryForObject(
                        """
                insert into animal_case(case_type,source_type,status,listed_at,species,sex,
                  event_date,created_at,updated_at)
                values('LOST','USER','ACTIVE',now(),'DOG','UNKNOWN',current_date,now(),now()) returning id
                """,
                        Long.class);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                postId,
                owner,
                UUID.randomUUID(),
                "a".repeat(64));
        photoId =
                jdbc.queryForObject(
                        "select nextval(pg_get_serial_sequence('animal_photo','id'))", Long.class);
        jdbc.update(
                """
                insert into animal_photo(id,animal_case_id,storage_type,storage_uri,content_type,
                  byte_size,width_px,height_px,sort_order,checksum_sha256,created_at)
                values(?,?,'USER_UPLOAD',?,'image/jpeg',3,512,512,0,?,now())
                """,
                photoId,
                postId,
                "/data/user/images/" + postId + "/" + photoId + ".jpg",
                "b".repeat(64));
        when(storage.open(anyString()))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1, 2, 3}));
    }

    @Test
    void activeAnonymousGetsBytesAndSafeHeaders() throws Exception {
        mvc.perform(get(path()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"" + "b".repeat(64) + "\""))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void closedOnlyAllowsAuthenticatedOwnerWithinRetention() throws Exception {
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", postId);
        mvc.perform(get(path()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PHOTO-007"));
        mvc.perform(get(path()).header("Authorization", bearer(member())))
                .andExpect(status().isNotFound());
        mvc.perform(get(path()).header("Authorization", bearer(owner))).andExpect(status().isOk());
        jdbc.update("update animal_case set closed_at=now()-interval '90 days' where id=?", postId);
        mvc.perform(get(path()).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedUnreferencedPublicAndWithdrawnAreIndistinguishable() throws Exception {
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", owner);
        mvc.perform(get(path())).andExpect(status().isNotFound());
        jdbc.update("update member set status='ACTIVE',deleted_at=null where id=?", owner);
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", postId);
        mvc.perform(get(path())).andExpect(status().isNotFound());
        jdbc.update("update animal_case set status='ACTIVE',deleted_at=null where id=?", postId);
        jdbc.update("update animal_photo set storage_type='PUBLIC_URL' where id=?", photoId);
        mvc.perform(get(path())).andExpect(status().isNotFound());
        jdbc.update("delete from animal_photo where id=?", photoId);
        mvc.perform(get(path())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/photos/0")).andExpect(status().isNotFound());
    }

    @Test
    void storageFailureIsSafe503AndInvalidTokenRemains401() throws Exception {
        when(storage.open(anyString()))
                .thenThrow(new BusinessException(PhotoErrorCode.STORAGE_UNAVAILABLE));
        var result =
                mvc.perform(get(path()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("PHOTO-006"))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("/data/", "hdfs", "Exception");
        mvc.perform(get(path()).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readFailureBeforeResponseCommitResetsBinaryHeadersForSafeJson() throws Exception {
        when(storage.open(anyString()))
                .thenReturn(
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("private-storage-path");
                            }
                        });
        mvc.perform(get(path()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("PHOTO-006"))
                .andExpect(header().doesNotExist("ETag"));
    }

    private long member() {
        return jdbc.queryForObject(
                """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                  phone_ciphertext,phone_lookup_hash,phone_verified_at,
                  privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values(?,'fixture','작성자','ACTIVE',now(),now(),'test-envelope',?,now(),true, 'privacy-collection-v1',now()) returning id
                """,
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private String bearer(long id) {
        return "Bearer " + sessions.create(id).accessToken();
    }

    private String path() {
        return "/api/v1/photos/" + photoId;
    }
}
