package com.meonggo.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.chat.service.ChatWriteService;
import com.meonggo.backend.chat.web.ChatMessageInput;
import com.meonggo.backend.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ChatWriteApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @Autowired private DataSource dataSource;
    @Autowired private ChatWriteService chatWrites;
    @MockitoBean private LoginService login;
    private long ownerId;
    private long requesterId;
    private long postId;
    private String ownerToken;
    private String requesterToken;

    @BeforeEach
    void setup() {
        ownerId = member("게시자");
        requesterId = member("요청자");
        ownerToken = bearer(ownerId);
        requesterToken = bearer(requesterId);
        postId = userPost(ownerId, "ACTIVE");
    }

    @Test
    void createsOrReturnsOneRoomUnderEightParallelRequests() throws Exception {
        int count = 8;
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var ids = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < count; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "test synchronization timeout");
                                    }
                                    return roomId(createRoom(postId, requesterToken));
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(ids).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_room where animal_case_id=? and requester_member_id=?",
                                Integer.class,
                                postId,
                                requesterId))
                .isEqualTo(1);
        createRoom(postId, requesterToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatRoomId").value(ids.iterator().next()))
                .andExpect(jsonPath("$.data.otherMember.memberId").value(ownerId))
                .andExpect(jsonPath("$.data.otherMember.nickname").value("게시자"))
                .andExpect(jsonPath("$.data.lastMessageAt").doesNotExist());
    }

    @Test
    void roomCreationAppliesPostGuardsWithoutLeakingExistingRoom() throws Exception {
        long existing = insertRoom(postId, ownerId, requesterId);
        createRoom(postId, ownerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT-002"));
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", postId);
        var closed =
                createRoom(postId, requesterToken)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("CHAT-004"))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(closed).doesNotContain("\"chatRoomId\":" + existing);

        long publicPost = animal("PUBLIC", "ACTIVE");
        createRoom(publicPost, requesterToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT-001"));
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", postId);
        createRoom(postId, requesterToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST-001"));
    }

    @Test
    void sendsNormalizedMessageAndTouchesRoomAtomically() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        Instant before =
                jdbc.queryForObject(
                                "select updated_at from chat_room where id=?",
                                java.sql.Timestamp.class,
                                room)
                        .toInstant();
        UUID key = UUID.randomUUID();
        send(room, requesterToken, json(key, "  e\u0301 안녕하세요  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("메시지를 전송했습니다."))
                .andExpect(jsonPath("$.data.chatRoomId").value(room))
                .andExpect(jsonPath("$.data.sender.memberId").value(requesterId))
                .andExpect(jsonPath("$.data.sender.nickname").value("요청자"))
                .andExpect(jsonPath("$.data.content").value("é 안녕하세요"));
        var message = jdbc.queryForMap("select * from chat_message where client_message_id=?", key);
        assertThat(message.get("content")).isEqualTo("é 안녕하세요");
        assertThat(message.get("request_hash")).isEqualTo(sha256("é 안녕하세요"));
        var roomTimes =
                jdbc.queryForMap(
                        "select last_message_at,updated_at from chat_room where id=?", room);
        assertThat(roomTimes.get("last_message_at")).isEqualTo(message.get("created_at"));
        assertThat(((java.sql.Timestamp) roomTimes.get("updated_at")).toInstant()).isAfter(before);
    }

    @Test
    void masksProfanityBeforeStoringAndKeepsIdempotencyOnTheOriginal() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        UUID key = UUID.randomUUID();
        String original = "이 씨발 소리 어디야";
        send(room, requesterToken, json(key, original))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value("이 ** 소리 어디야"));
        var message = jdbc.queryForMap("select * from chat_message where client_message_id=?", key);
        assertThat(message.get("content")).isEqualTo("이 ** 소리 어디야");
        // 같은 원문 재요청은 여전히 최초 메시지를 돌려준다 — 해시가 원문 기준이라 마스킹과 무관하다
        assertThat(message.get("request_hash")).isEqualTo(sha256(original));
        send(room, requesterToken, json(key, original))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageId").value(message.get("id")));
    }

    @Test
    void successfulSendDoesNotLogMessageContentOrMemberPhone(CapturedOutput output)
            throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        String privateContent = "success-private-chat-content";
        String privatePhone = "encrypted-phone-010-0000-0065";
        jdbc.update("update member set phone_ciphertext=? where id=?", privatePhone, requesterId);

        send(room, requesterToken, json(UUID.randomUUID(), privateContent))
                .andExpect(status().isCreated());

        assertThat(output.getAll()).doesNotContain(privateContent, privatePhone, "010-0000-0065");
    }

    @Test
    void rejectsInvalidJsonUuidContentAndForbiddenCodepoints() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        String key = UUID.randomUUID().toString();
        for (String body :
                new String[] {
                    "{}",
                    "{\"clientMessageId\":\"" + key + "\"}",
                    "{\"clientMessageId\":\"bad\",\"content\":\"hi\"}",
                    "{\"clientMessageId\":12,\"content\":\"hi\"}",
                    "{\"clientMessageId\":\"" + key + "\",\"content\":null}",
                    "{\"clientMessageId\":\"" + key + "\",\"content\":\"\"}",
                    "{\"clientMessageId\":\"" + key + "\",\"content\":\"hi\",\"extra\":1}",
                    "{\"clientMessageId\":\""
                            + key
                            + "\",\"clientMessageId\":\""
                            + key
                            + "\",\"content\":\"hi\"}",
                    "{\"clientMessageId\":\"" + key + "\",\"content\":\"hi\"} {}",
                    json(UUID.fromString(key), "a".repeat(1001)),
                    json(UUID.fromString(key), "line\nbreak"),
                    json(UUID.fromString(key), "hidden\u200btext")
                }) {
            send(room, requesterToken, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("COMMON-")));
        }
        sendBytes(room, requesterToken, " ".repeat(65537).getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_message where chat_room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    @Test
    void requiresJsonContentTypeBeforeReadingMessageBody() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        String body = json(UUID.randomUUID(), "미디어 타입 검사");
        mvc.perform(
                        post("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", requesterToken)
                                .content(body))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON-415"));
        mvc.perform(
                        post("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", requesterToken)
                                .contentType(MediaType.TEXT_PLAIN)
                                .content(body))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("COMMON-415"));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_message where chat_room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    @Test
    void concurrentSameKeyCreatesOneMessageAndDoesNotRetouchOnReplay() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        UUID key = UUID.randomUUID();
        int count = 8;
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var ids = new HashSet<Long>();
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < count; i++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException();
                                    return messageId(
                                            send(room, requesterToken, json(key, "동일 메시지")));
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) ids.add(future.get(20, TimeUnit.SECONDS));
        }
        assertThat(ids).hasSize(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_message where chat_room_id=?",
                                Integer.class,
                                room))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from chat_notification_outbox outbox
                                join chat_message message on message.id=outbox.message_id
                                where message.chat_room_id=?
                                """,
                                Integer.class,
                                room))
                .isEqualTo(1);
        Instant updated =
                jdbc.queryForObject(
                                "select updated_at from chat_room where id=?",
                                java.sql.Timestamp.class,
                                room)
                        .toInstant();
        send(room, requesterToken, json(key, "동일 메시지")).andExpect(status().isCreated());
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from chat_notification_outbox outbox
                                join chat_message message on message.id=outbox.message_id
                                where message.chat_room_id=?
                                """,
                                Integer.class,
                                room))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                        "select updated_at from chat_room where id=?",
                                        java.sql.Timestamp.class,
                                        room)
                                .toInstant())
                .isEqualTo(updated);
    }

    @Test
    void senderScopedKeyRejectsCrossRoomAndContentButReplaySurvivesClosure() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        long otherPost = userPost(ownerId, "ACTIVE");
        long otherRoom = insertRoom(otherPost, ownerId, requesterId);
        UUID key = UUID.randomUUID();
        long original = messageId(send(room, requesterToken, json(key, "원본")));
        send(room, requesterToken, json(key, "변경"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY-001"));
        send(otherRoom, requesterToken, json(key, "원본"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY-001"));

        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", postId);
        send(room, requesterToken, json(key, "원본"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.messageId").value(original));
        send(room, requesterToken, json(UUID.randomUUID(), "새 메시지"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT-004"));
    }

    @Test
    void hidesRoomsAndMessageContentFromNonparticipantsAndAnonymousUsers() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        String secret = "private-chat-content";
        send(room, bearer(member("외부인")), json(UUID.randomUUID(), secret))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"))
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getContentAsString())
                                        .doesNotContain(secret));
        createRoom(postId, "").andExpect(status().isUnauthorized());
        send(room, "", json(UUID.randomUUID(), secret)).andExpect(status().isUnauthorized());
    }

    @Test
    void roomTouchFailureRollsBackMessageWithoutReflectingPrivateContent(CapturedOutput output)
            throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        Instant before = timestamp("select updated_at from chat_room where id=?", room);
        String privatePhone = "encrypted-phone-010-0000-1065";
        jdbc.update("update member set phone_ciphertext=? where id=?", privatePhone, requesterId);
        jdbc.execute(
                "create or replace function chat_write_test_fail_touch() returns trigger language plpgsql as 'begin raise exception ''forced room touch failure''; end'");
        jdbc.execute(
                "create trigger chat_write_test_fail_touch before update on chat_room for each row execute function chat_write_test_fail_touch()");
        String privateContent = "rollback-private-content";
        try {
            send(room, requesterToken, json(UUID.randomUUID(), privateContent))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-500"))
                    .andExpect(
                            result ->
                                    assertThat(result.getResponse().getContentAsString())
                                            .doesNotContain(privateContent, "forced room touch"));
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from chat_message where chat_room_id=?",
                                    Integer.class,
                                    room))
                    .isZero();
            assertThat(timestamp("select updated_at from chat_room where id=?", room))
                    .isEqualTo(before);
            assertThat(output.getAll())
                    .contains("Business error: COMMON-500")
                    .doesNotContain(privateContent, privatePhone, "010-0000-1065");
        } finally {
            jdbc.execute("drop trigger if exists chat_write_test_fail_touch on chat_room");
            jdbc.execute("drop function if exists chat_write_test_fail_touch()");
        }
    }

    @Test
    void closureWhileSendWaitsForParticipantLockRejectsNewMessage() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement("select id from member where id=? for update")) {
                lock.setLong(1, requesterId);
                lock.executeQuery();
            }
            var started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future =
                        executor.submit(
                                () -> {
                                    started.countDown();
                                    try {
                                        chatWrites.send(
                                                room,
                                                requesterId,
                                                new ChatMessageInput(
                                                        UUID.randomUUID(), "종료 경합 메시지"));
                                        return "SUCCESS";
                                    } catch (BusinessException exception) {
                                        return exception.errorCode().code();
                                    }
                                });
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    awaitParticipantLockWait();
                    try (var close =
                            connection.prepareStatement(
                                    "update animal_case set status='CLOSED',closed_at=clock_timestamp() where id=?")) {
                        close.setLong(1, postId);
                        close.executeUpdate();
                    }
                    connection.commit();
                } finally {
                    connection.rollback();
                }
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("CHAT-004");
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_message where chat_room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    @Test
    void otherParticipantWithdrawalWhileSendWaitsMakesRoomReadOnly() throws Exception {
        long room = insertRoom(postId, ownerId, requesterId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement("select id from member where id=? for update")) {
                lock.setLong(1, ownerId);
                lock.executeQuery();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future =
                        executor.submit(
                                () -> {
                                    try {
                                        chatWrites.send(
                                                room,
                                                requesterId,
                                                new ChatMessageInput(
                                                        UUID.randomUUID(), "탈퇴 경합 메시지"));
                                        return "SUCCESS";
                                    } catch (BusinessException exception) {
                                        return exception.errorCode().code();
                                    }
                                });
                try {
                    awaitParticipantLockWait();
                    try (var withdraw =
                            connection.prepareStatement(
                                    "update member set status='WITHDRAWN',deleted_at=clock_timestamp() where id=?")) {
                        withdraw.setLong(1, ownerId);
                        withdraw.executeUpdate();
                    }
                    connection.commit();
                } finally {
                    connection.rollback();
                }
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("CHAT-004");
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from chat_message where chat_room_id=?",
                                Integer.class,
                                room))
                .isZero();
    }

    private ResultActions createRoom(long id, String token) throws Exception {
        var request = post("/api/v1/posts/" + id + "/chat-room");
        if (!token.isEmpty()) request.header("Authorization", token);
        return mvc.perform(request);
    }

    private ResultActions send(long room, String token, String json) throws Exception {
        return sendBytes(room, token, json.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions sendBytes(long room, String token, byte[] bytes) throws Exception {
        var request =
                post("/api/v1/chat-rooms/" + room + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes);
        if (!token.isEmpty()) request.header("Authorization", token);
        return mvc.perform(request);
    }

    private long roomId(ResultActions action) throws Exception {
        var response = action.andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString())
                .path("data")
                .path("chatRoomId")
                .asLong();
    }

    private long messageId(ResultActions action) throws Exception {
        var response = action.andExpect(status().isCreated()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString())
                .path("data")
                .path("messageId")
                .asLong();
    }

    private String json(UUID key, String content) throws Exception {
        return mapper.writeValueAsString(
                java.util.Map.of("clientMessageId", key.toString(), "content", content));
    }

    private String sha256(String content) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private Instant timestamp(String sql, long id) {
        return jdbc.queryForObject(sql, java.sql.Timestamp.class, id).toInstant();
    }

    private void awaitParticipantLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean waiting =
                    jdbc.queryForObject(
                            """
                select exists(
                    select 1 from pg_stat_activity
                    where datname=current_database()
                      and cardinality(pg_blocking_pids(pid)) > 0
                      and query like 'select id from member where id in%for update%')
                """,
                            Boolean.class);
            if (waiting) return;
            Thread.sleep(20);
        }
        throw new AssertionError("message send did not wait for the participant member lock");
    }

    private long insertRoom(long post, long owner, long requester) {
        return jdbc.queryForObject(
                "insert into chat_room(animal_case_id,owner_member_id,requester_member_id,created_at,updated_at) values(?,?,?,clock_timestamp(),clock_timestamp()) returning id",
                Long.class,
                post,
                owner,
                requester);
    }

    private String bearer(long member) {
        return "Bearer " + sessions.create(member).accessToken();
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                """
            insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
            values(?,'fixture',?,'ACTIVE',now(),now(),'private-phone',?,now(),true, 'privacy-collection-v1',now()) returning id
            """,
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private long userPost(long owner, String status) {
        long id = animal("USER", status);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                id,
                owner,
                UUID.randomUUID(),
                "a".repeat(64));
        return id;
    }

    private long animal(String source, String status) {
        return jdbc.queryForObject(
                "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,closed_at,created_at,updated_at) values(?,?,?,now(),'DOG','UNKNOWN','2020-01-01',case when ?='CLOSED' then now() end,now(),now()) returning id",
                Long.class,
                "PUBLIC".equals(source) ? "SHELTERING" : "LOST",
                source,
                status,
                status);
    }
}
