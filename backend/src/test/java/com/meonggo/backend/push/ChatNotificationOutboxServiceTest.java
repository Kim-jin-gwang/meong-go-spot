package com.meonggo.backend.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.meonggo.backend.push.notification.ChatNotification;
import com.meonggo.backend.push.notification.ChatNotificationPublishException;
import com.meonggo.backend.push.notification.ChatNotificationPublisher;
import com.meonggo.backend.push.repository.ChatNotificationOutboxRepository;
import com.meonggo.backend.push.security.PushTokenProtection;
import com.meonggo.backend.push.service.ChatNotificationOutboxService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class ChatNotificationOutboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChatNotificationOutboxRepository outbox;
    @Autowired private PushTokenProtection protection;
    @Autowired private PlatformTransactionManager transactions;
    private RecordingPublisher publisher;
    private ChatNotificationOutboxService service;

    @BeforeEach
    void setup() {
        publisher = new RecordingPublisher();
        service = service(publisher);
    }

    @Test
    void sendsDataOnlyPayloadAndCompletesOutbox() {
        Fixture fixture = fixture(true, "device-token");

        assertThat(service.publishNext()).isTrue();

        assertThat(publisher.tokens).containsExactly("device-token");
        assertThat(publisher.notifications).containsExactly(fixture.notification());
        assertThat(fixture.notification().data())
                .containsOnlyKeys("type", "chatRoomId", "messageId", "postId")
                .doesNotContainKey("content");
        assertThat(status(fixture.outboxId())).isEqualTo("SENT");
        assertThat(service.publishNext()).isFalse();
    }

    @Test
    void sendsToEveryEligibleSession() {
        Fixture fixture = fixture(true, "first-device-token");
        session(fixture.recipientId(), "second-device-token");

        assertThat(service.publishNext()).isTrue();

        assertThat(publisher.tokens).containsExactly("first-device-token", "second-device-token");
        assertThat(status(fixture.outboxId())).isEqualTo("SENT");
    }

    @Test
    void skipsWhenRecipientHasNoEligibleDevice() {
        Fixture fixture = fixture(false, null);

        assertThat(service.publishNext()).isTrue();

        assertThat(publisher.tokens).isEmpty();
        assertThat(status(fixture.outboxId())).isEqualTo("SKIPPED");
        assertThat(error(fixture.outboxId())).isEqualTo("NO_ACTIVE_DEVICE");
    }

    @Test
    void retriesTransientFailureAndDoesNotCompleteEvent() {
        Fixture fixture = fixture(true, "temporary-token");
        publisher.failure = new ChatNotificationPublishException(false, "UNAVAILABLE");

        assertThat(service.publishNext()).isTrue();

        assertThat(status(fixture.outboxId())).isEqualTo("PENDING");
        assertThat(error(fixture.outboxId())).isEqualTo("UNAVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                        "select next_attempt_at from chat_notification_outbox where id=?",
                                        Timestamp.class,
                                        fixture.outboxId())
                                .toInstant())
                .isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void stopsRetryingAtMaximumAttemptCount() {
        Fixture fixture = fixture(true, "unavailable-token");
        jdbc.update(
                "update chat_notification_outbox set attempt_count=9 where id=?",
                fixture.outboxId());
        publisher.failure = new ChatNotificationPublishException(false, "UNAVAILABLE");

        assertThat(service.publishNext()).isTrue();

        assertThat(status(fixture.outboxId())).isEqualTo("SKIPPED");
        assertThat(error(fixture.outboxId())).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    void reclaimsExpiredProcessingLease() {
        Fixture fixture = fixture(true, "recovered-token");
        jdbc.update(
                """
                update chat_notification_outbox
                set status='PROCESSING',lease_until=?,attempt_count=1
                where id=?
                """,
                Timestamp.from(NOW.minusSeconds(1)),
                fixture.outboxId());

        assertThat(service.publishNext()).isTrue();

        assertThat(publisher.tokens).containsExactly("recovered-token");
        assertThat(status(fixture.outboxId())).isEqualTo("SENT");
    }

    @Test
    void clearsPermanentFailureTokenAndSkipsEvent() {
        Fixture fixture = fixture(true, "expired-token");
        publisher.failure = new ChatNotificationPublishException(true, "PERMANENT_TOKEN");

        assertThat(service.publishNext()).isTrue();

        assertThat(status(fixture.outboxId())).isEqualTo("SKIPPED");
        assertThat(
                        jdbc.queryForObject(
                                "select push_installation_id from auth_session where member_id=?",
                                UUID.class,
                                fixture.recipientId()))
                .isNull();
    }

    @Test
    void concurrentWorkersClaimEventOnlyOnce() throws Exception {
        Fixture fixture = fixture(true, "single-delivery-token");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        publisher.entered = entered;
        publisher.release = release;
        ChatNotificationOutboxService second = service(publisher);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(service::publishNext);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var other = executor.submit(second::publishNext);
            assertThat(other.get(10, TimeUnit.SECONDS)).isFalse();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(publisher.tokens).containsExactly("single-delivery-token");
        assertThat(status(fixture.outboxId())).isEqualTo("SENT");
    }

    private ChatNotificationOutboxService service(ChatNotificationPublisher delivery) {
        return new ChatNotificationOutboxService(
                outbox,
                protection,
                delivery,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                transactions);
    }

    private Fixture fixture(boolean registerDevice, String token) {
        long owner = member("발신자");
        long recipient = member("수신자");
        if (registerDevice) session(recipient, token);
        long post =
                jdbc.queryForObject(
                        """
                        insert into animal_case(case_type,source_type,status,listed_at,species,sex,
                            event_date,created_at,updated_at)
                        values('LOST','USER','ACTIVE',?,'DOG','UNKNOWN','2020-01-01',?,?)
                        returning id
                        """,
                        Long.class,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW),
                        Timestamp.from(NOW));
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                post,
                owner,
                UUID.randomUUID(),
                "a".repeat(64));
        long room =
                jdbc.queryForObject(
                        """
                        insert into chat_room(animal_case_id,owner_member_id,requester_member_id,
                            created_at,updated_at) values(?,?,?,?,?) returning id
                        """,
                        Long.class,
                        post,
                        owner,
                        recipient,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW));
        long message =
                jdbc.queryForObject(
                        """
                        insert into chat_message(chat_room_id,sender_member_id,client_message_id,
                            request_hash,content,created_at) values(?,?,?,?,?,?) returning id
                        """,
                        Long.class,
                        room,
                        owner,
                        UUID.randomUUID(),
                        "b".repeat(64),
                        "private message",
                        Timestamp.from(NOW));
        long event =
                jdbc.queryForObject(
                        """
                        insert into chat_notification_outbox(message_id,recipient_member_id,
                            next_attempt_at,created_at) values(?,?,?,?) returning id
                        """,
                        Long.class,
                        message,
                        recipient,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW));
        return new Fixture(event, recipient, new ChatNotification(room, message, post));
    }

    private void session(long memberId, String token) {
        var protectedToken = protection.protect(token);
        jdbc.update(
                """
                insert into auth_session(member_id,refresh_token_selector,refresh_token_hash,
                    push_installation_id,push_platform,push_token_ciphertext,
                    push_token_lookup_hash,push_last_seen_at,expires_at,created_at)
                values(?,?,?,?,?,?,?,?,?,?)
                """,
                memberId,
                selector(),
                hash(),
                UUID.randomUUID(),
                "ANDROID",
                protectedToken.ciphertext(),
                protectedToken.lookupHash(),
                Timestamp.from(NOW),
                Timestamp.from(NOW.plus(Duration.ofDays(1))),
                Timestamp.from(NOW));
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                    phone_ciphertext,phone_lookup_hash,phone_verified_at,
                    privacy_collection_agreed,privacy_collection_policy_version,
                    privacy_collection_consented_at)
                values(?,'fixture',?,'ACTIVE',?,?, 'private-phone',?,?,
                    true,'privacy-collection-v1',?) returning id
                """,
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                Timestamp.from(NOW),
                Timestamp.from(NOW),
                hash(),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
    }

    private String status(long outboxId) {
        return jdbc.queryForObject(
                "select status from chat_notification_outbox where id=?", String.class, outboxId);
    }

    private String error(long outboxId) {
        return jdbc.queryForObject(
                "select last_error_code from chat_notification_outbox where id=?",
                String.class,
                outboxId);
    }

    private static String selector() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private record Fixture(long outboxId, long recipientId, ChatNotification notification) {}

    private static final class RecordingPublisher implements ChatNotificationPublisher {
        private final List<String> tokens = new ArrayList<>();
        private final List<ChatNotification> notifications = new ArrayList<>();
        private RuntimeException failure;
        private CountDownLatch entered;
        private CountDownLatch release;

        @Override
        public synchronized void publish(String token, ChatNotification notification) {
            tokens.add(token);
            notifications.add(notification);
            if (entered != null) entered.countDown();
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException();
                }
            }
            if (failure != null) throw failure;
        }
    }
}
