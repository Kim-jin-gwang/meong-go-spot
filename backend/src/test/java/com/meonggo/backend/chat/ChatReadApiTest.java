package com.meonggo.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.chat.service.ChatReadService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatReadApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ChatReadService reads;
    @MockitoBean private LoginService login;
    private long owner;
    private long requester;
    private long outsider;
    private long post;
    private long room;
    private String ownerToken;

    @BeforeEach
    void setup() {
        owner = member("게시물 작성자");
        requester = member("대화 요청자");
        outsider = member("외부 회원");
        ownerToken = bearer(owner);
        post = animal(owner);
        room = room(post, owner, requester);
    }

    @Test
    void updatesOnlyParticipantPositionAndNeverMovesBackward() throws Exception {
        long first = message(room, requester, "첫 메시지");
        long second = message(room, requester, "둘째 메시지");

        read(room, ownerToken, second)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatRoomId").value(room))
                .andExpect(jsonPath("$.data.lastReadMessageId").value(second));
        read(room, ownerToken, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadMessageId").value(second));

        assertThat(
                        jdbc.queryForObject(
                                "select owner_last_read_message_id from chat_room where id=?",
                                Long.class,
                                room))
                .isEqualTo(second);
        assertThat(
                        jdbc.queryForObject(
                                "select requester_last_read_message_id from chat_room where id=?",
                                Long.class,
                                room))
                .isNull();
    }

    @Test
    void allowsReadUpdatesForClosedConversation() throws Exception {
        long target = message(room, owner, "보존 메시지");
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", post);

        read(room, bearer(requester), target)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadMessageId").value(target));
    }

    @Test
    void hidesRoomAndRejectsMessageOutsideConversation() throws Exception {
        long target = message(room, requester, "대상 메시지");
        long otherRoom = room(animal(owner), owner, requester);
        long otherMessage = message(otherRoom, requester, "다른 방 메시지");

        read(room, bearer(outsider), target)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"));
        read(Long.MAX_VALUE, ownerToken, target)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"));
        read(room, ownerToken, otherMessage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT-005"));
        read(room, ownerToken, Long.MAX_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT-005"));
    }

    @Test
    void validatesBodyAndRequiresAuthentication() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    "{\"lastReadMessageId\":0}",
                    "{\"lastReadMessageId\":-1}",
                    "{\"lastReadMessageId\":\"1\"}",
                    "{\"lastReadMessageId\":1,\"extra\":1}",
                    "{\"lastReadMessageId\":1,\"lastReadMessageId\":2}",
                    "{\"lastReadMessageId\":1} {}"
                }) {
            readJson(room, ownerToken, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("COMMON-")));
        }
        readJson(room, "", "{\"lastReadMessageId\":1}").andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentUpdatesKeepGreatestReadPosition() throws Exception {
        List<Long> messageIds = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            messageIds.add(message(room, requester, "동시 메시지 " + index));
        }
        var ready = new CountDownLatch(messageIds.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(messageIds.size())) {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (long messageId : messageIds) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "read update did not start");
                                    }
                                    return reads.update(room, owner, messageId).lastReadMessageId();
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }

        assertThat(
                        jdbc.queryForObject(
                                "select owner_last_read_message_id from chat_room where id=?",
                                Long.class,
                                room))
                .isEqualTo(messageIds.getLast());
    }

    @Test
    void databaseRejectsNonPositiveReadPosition() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "update chat_room set owner_last_read_message_id=0 where id=?",
                                        room))
                .satisfies(
                        exception ->
                                assertThat(postgresCause(exception).getSQLState())
                                        .isEqualTo("23514"));
    }

    private ResultActions read(long roomId, String token, long messageId) throws Exception {
        return readJson(roomId, token, "{\"lastReadMessageId\":" + messageId + "}");
    }

    private ResultActions readJson(long roomId, String token, String body) throws Exception {
        var request =
                put("/api/v1/chat-rooms/" + roomId + "/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
        if (!token.isEmpty()) request.header("Authorization", token);
        return mvc.perform(request);
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                "insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at) values(?,'fixture',?,'ACTIVE',now(),now(),'private phone',?,now(),true, 'privacy-collection-v1',now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private String bearer(long memberId) {
        return "Bearer " + sessions.create(memberId).accessToken();
    }

    private long animal(long memberId) {
        long id =
                jdbc.queryForObject(
                        "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values('LOST','USER','ACTIVE',now(),'DOG','UNKNOWN',current_date,now(),now()) returning id",
                        Long.class);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                id,
                memberId,
                UUID.randomUUID(),
                "a".repeat(64));
        return id;
    }

    private long room(long postId, long ownerId, long requesterId) {
        return jdbc.queryForObject(
                "insert into chat_room(animal_case_id,owner_member_id,requester_member_id,created_at,updated_at) values(?,?,?,clock_timestamp(),clock_timestamp()) returning id",
                Long.class,
                postId,
                ownerId,
                requesterId);
    }

    private long message(long roomId, long senderId, String content) {
        return jdbc.queryForObject(
                "insert into chat_message(chat_room_id,sender_member_id,client_message_id,request_hash,content,created_at) values(?,?,?,?,?,clock_timestamp()) returning id",
                Long.class,
                roomId,
                senderId,
                UUID.randomUUID(),
                "b".repeat(64),
                content);
    }

    private SQLException postgresCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException;
            current = current.getCause();
        }
        throw new AssertionError("Expected PostgreSQL failure", throwable);
    }
}
