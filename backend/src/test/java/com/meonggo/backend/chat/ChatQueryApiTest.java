package com.meonggo.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatQueryApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private LoginService login;
    private long owner;
    private long requester;
    private long outsider;
    private long post;
    private long room;
    private String ownerToken;
    private String requesterToken;

    @BeforeEach
    void setup() {
        owner = member("게시물 작성자");
        requester = member("대화 요청자");
        outsider = member("외부 회원");
        ownerToken = bearer(owner);
        requesterToken = bearer(requester);
        post = animal(owner);
        room = room(post, owner, requester);
    }

    @Test
    void onlyParticipantsSeeRoomsAndEmptyRoomsHaveNoLastMessage() throws Exception {
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].chatRoomId").value(room))
                .andExpect(jsonPath("$.data.items[0].otherMember.memberId").value(requester))
                .andExpect(jsonPath("$.data.items[0].otherMember.nickname").value("대화 요청자"))
                .andExpect(jsonPath("$.data.items[0].lastMessage").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].unreadCount").value(0))
                .andExpect(jsonPath("$.data.items[0].hasUnread").value(false))
                .andExpect(jsonPath("$.data.items[0].readOnly").value(false));
        rooms(requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].otherMember.memberId").value(owner));
        rooms(bearer(outsider), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void roomsAndMessagesTellWhichPostTheConversationIsAbout() throws Exception {
        jdbc.update(
                "update animal_case set name=?,breed_name=?,sex='MALE' where id=?",
                "콩이",
                "말티즈",
                post);
        photo(post);

        // 같은 상대와 게시물이 둘이면 목록에서 방이 두 줄이다. 어느 게시물인지 이름으로 갈린다.
        long secondPost = animal(owner);
        jdbc.update("update animal_case set name=? where id=?", "보리", secondPost);
        room(secondPost, owner, requester);

        JsonNode rooms = data(rooms(ownerToken, null).andExpect(status().isOk()));
        assertThat(ids(rooms, "chatRoomId")).hasSize(2);
        assertThat(names(rooms)).containsExactlyInAnyOrder("콩이", "보리");

        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.postId").value(post))
                .andExpect(jsonPath("$.data.post.type").value("LOST"))
                .andExpect(jsonPath("$.data.post.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.post.name").value("콩이"))
                .andExpect(jsonPath("$.data.post.species").value("DOG"))
                .andExpect(jsonPath("$.data.post.breedName").value("말티즈"))
                .andExpect(jsonPath("$.data.post.sex").value("MALE"))
                .andExpect(jsonPath("$.data.post.thumbnailUrl").exists())
                .andExpect(jsonPath("$.data.otherMember.memberId").value(owner))
                .andExpect(jsonPath("$.data.otherMember.nickname").value("게시물 작성자"));

        // 이름과 품종은 비워 둘 수 있다. 그때는 아예 내려보내지 않는다.
        jdbc.update("update animal_case set name=null,breed_name=null where id=?", post);
        messages(room, ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.name").doesNotExist())
                .andExpect(jsonPath("$.data.post.breedName").doesNotExist())
                .andExpect(jsonPath("$.data.post.species").value("DOG"))
                .andExpect(jsonPath("$.data.otherMember.nickname").value("대화 요청자"));
    }

    private List<String> names(JsonNode data) {
        List<String> values = new ArrayList<>();
        data.path("items").forEach(item -> values.add(item.path("post").path("name").asString()));
        return values;
    }

    @Test
    void roomUnreadCountIncludesOnlyUnreadMessagesFromOtherParticipant() throws Exception {
        message(room, owner, "내가 보낸 메시지");
        long firstFromRequester = message(room, requester, "상대 메시지 1");
        long secondFromRequester = message(room, requester, "상대 메시지 2");

        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.items[0].lastMessage.messageId")
                                .value(secondFromRequester))
                .andExpect(jsonPath("$.data.items[0].unreadCount").value(2))
                .andExpect(jsonPath("$.data.items[0].hasUnread").value(true));
        rooms(requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unreadCount").value(1));

        jdbc.update(
                "update chat_room set owner_last_read_message_id=? where id=?",
                firstFromRequester,
                room);
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].unreadCount").value(1))
                .andExpect(jsonPath("$.data.items[0].hasUnread").value(true));
    }

    @Test
    void roomCursorUsesUpdatedTimeAndIdWithTenItemsAndPrivateScope() throws Exception {
        List<Long> expected = new ArrayList<>();
        expected.add(room);
        for (int i = 0; i < 11; i++) expected.add(room(post, owner, member("상대")));
        jdbc.update(
                "update chat_room set updated_at='2026-09-01T00:00:00Z' where owner_member_id=?",
                owner);
        expected.sort(java.util.Comparator.reverseOrder());
        JsonNode first =
                data(
                        rooms(ownerToken, null)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(10))
                                .andExpect(jsonPath("$.data.page.size").value(10))
                                .andExpect(jsonPath("$.data.page.hasNext").value(true)));
        String cursor = first.path("page").path("nextCursor").asString();
        JsonNode second =
                data(
                        rooms(ownerToken, cursor)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(2))
                                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist()));
        List<Long> actual = ids(first, "chatRoomId");
        actual.addAll(ids(second, "chatRoomId"));
        assertThat(actual).containsExactlyElementsOf(expected);
        rooms(requesterToken, cursor)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR-001"));
    }

    @Test
    void messagesHaveTwentyItemCursorAndOnlyRoomParticipantsCanRead() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 23; i++)
            expected.add(message(room, i % 2 == 0 ? owner : requester, "message-" + i));
        jdbc.update(
                "update chat_message set created_at='2026-09-01T00:00:00Z' where chat_room_id=?",
                room);
        expected.sort(java.util.Comparator.reverseOrder());
        JsonNode first =
                data(
                        messages(room, ownerToken, null)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.chatRoomId").value(room))
                                .andExpect(jsonPath("$.data.items.length()").value(20))
                                .andExpect(jsonPath("$.data.pollAfterMs").value(3000))
                                .andExpect(jsonPath("$.data.page.size").value(20))
                                .andExpect(jsonPath("$.data.readOnly").value(false)));
        String cursor = first.path("page").path("nextCursor").asString();
        JsonNode second =
                data(
                        messages(room, ownerToken, cursor)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(3))
                                .andExpect(jsonPath("$.data.page.hasNext").value(false)));
        List<Long> actual = ids(first, "messageId");
        actual.addAll(ids(second, "messageId"));
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(first.path("items").get(0).path("sender").path("memberId").asLong())
                .isEqualTo(owner);
        messages(room, bearer(outsider), "invalid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"));
        messages(Long.MAX_VALUE, ownerToken, "invalid")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"));
        messages(room, requesterToken, cursor).andExpect(status().isBadRequest());
        long otherRoom = room(animal(owner), owner, requester);
        messages(otherRoom, ownerToken, cursor).andExpect(status().isBadRequest());
        rooms(ownerToken, cursor).andExpect(status().isBadRequest());
    }

    @Test
    void incrementalMessagesReturnEveryNewMessageInOldestFirstPages() throws Exception {
        long afterMessageId = message(room, owner, "이미 받은 메시지");
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            expected.add(message(room, i % 2 == 0 ? requester : owner, "new-message-" + i));
        }

        JsonNode first =
                data(
                        messagesAfter(room, ownerToken, afterMessageId)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(20))
                                .andExpect(jsonPath("$.data.page.hasNext").value(true))
                                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist()));
        long secondPosition = first.path("page").path("nextAfterMessageId").asLong();
        JsonNode second =
                data(
                        messagesAfter(room, ownerToken, secondPosition)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(20))
                                .andExpect(jsonPath("$.data.page.hasNext").value(true)));
        long thirdPosition = second.path("page").path("nextAfterMessageId").asLong();
        JsonNode third =
                data(
                        messagesAfter(room, ownerToken, thirdPosition)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(5))
                                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                                .andExpect(
                                        jsonPath("$.data.page.nextAfterMessageId").doesNotExist()));

        List<Long> actual = ids(first, "messageId");
        actual.addAll(ids(second, "messageId"));
        actual.addAll(ids(third, "messageId"));
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    void incrementalPositionMustBePositiveUniqueAndBelongToRoom() throws Exception {
        long otherRoom = room(animal(owner), owner, requester);
        long otherMessage = message(otherRoom, owner, "다른 방 메시지");

        messagesAfter(room, ownerToken, otherMessage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT-005"));
        messagesAfter(room, ownerToken, Long.MAX_VALUE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAT-005"));
        for (String invalid : new String[] {"", "0", "-1", "not-a-number"}) {
            mvc.perform(
                            get("/api/v1/chat-rooms/" + room + "/messages")
                                    .header("Authorization", ownerToken)
                                    .param("afterMessageId", invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON-001"));
        }
        mvc.perform(
                        get("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", ownerToken)
                                .param("cursor", "invalid")
                                .param("afterMessageId", String.valueOf(otherMessage)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mvc.perform(
                        get("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", ownerToken)
                                .param("afterMessageId", "1", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mvc.perform(
                        get("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", bearer(outsider))
                                .param("afterMessageId", "0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT-003"));
    }

    @Test
    void emptyRoomStillReturnsPollingAndNoNextCursor() throws Exception {
        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.pollAfterMs").value(3000))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
    }

    @Test
    void messageQueryReturnsBothReadPositionsWithoutChangingThem() throws Exception {
        long ownerRead = message(room, requester, "작성자가 읽은 위치");
        long requesterRead = message(room, owner, "요청자가 읽은 위치");
        jdbc.update(
                "update chat_room set owner_last_read_message_id=?,requester_last_read_message_id=? where id=?",
                ownerRead,
                requesterRead,
                room);

        messages(room, ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myLastReadMessageId").value(ownerRead))
                .andExpect(jsonPath("$.data.otherLastReadMessageId").value(requesterRead));
        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myLastReadMessageId").value(requesterRead))
                .andExpect(jsonPath("$.data.otherLastReadMessageId").value(ownerRead));

        assertThat(
                        jdbc.queryForMap(
                                "select owner_last_read_message_id,requester_last_read_message_id from chat_room where id=?",
                                room))
                .containsEntry("owner_last_read_message_id", ownerRead)
                .containsEntry("requester_last_read_message_id", requesterRead);
    }

    @Test
    void closedAndDeletedPostsKeepHistoryReadOnlyAndProtectThumbnails() throws Exception {
        message(room, owner, "보관되는 대화");
        photo(post);
        rooms(requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").exists());
        jdbc.update("update animal_case set status='CLOSED',closed_at=now() where id=?", post);
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").exists())
                .andExpect(jsonPath("$.data.items[0].readOnly").value(true));
        rooms(requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").doesNotExist());
        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readOnly").value(true))
                .andExpect(jsonPath("$.data.items[0].content").value("보관되는 대화"));
        jdbc.update("update animal_case set closed_at=now()-interval '90 days' where id=?", post);
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").doesNotExist());
        jdbc.update("update animal_case set status='DELETED',deleted_at=now() where id=?", post);
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].post.status").value("DELETED"))
                .andExpect(jsonPath("$.data.items[0].readOnly").value(true))
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").doesNotExist());
        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readOnly").value(true));
    }

    @Test
    void withdrawnParticipantNameIsMaskedAndRetainedConversationIsReadOnly() throws Exception {
        message(room, owner, "기존 메시지");
        photo(post);
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", owner);
        rooms(requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].otherMember.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.data.items[0].readOnly").value(true))
                .andExpect(jsonPath("$.data.items[0].post.thumbnailUrl").doesNotExist());
        messages(room, requesterToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sender.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.data.readOnly").value(true));
        rooms(ownerToken, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
    }

    @Test
    void malformedAndRepeatedParametersAreRejectedWithoutReflectingValues() throws Exception {
        for (String cursor :
                new String[] {"", "private-invalid", "a".repeat(200), "=".repeat(71)}) {
            String response =
                    rooms(ownerToken, cursor)
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.code").value("CURSOR-001"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(response).doesNotContain("private-invalid");
            messages(room, ownerToken, cursor).andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/v1/chat-rooms")
                                .header("Authorization", ownerToken)
                                .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mvc.perform(
                        get("/api/v1/chat-rooms")
                                .header("Authorization", ownerToken)
                                .param("cursor", "a", "b"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", ownerToken)
                                .param("sort", "asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseExcludesPrivateAccountPostAndIdempotencyFields() throws Exception {
        message(room, owner, "허용된 대화");
        String rooms =
                rooms(requesterToken, null)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].lastMessage.content").value("허용된 대화"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String messages =
                messages(room, requesterToken, null)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(rooms + messages)
                .doesNotContain(
                        "phone",
                        "password",
                        "token",
                        "exactLocation",
                        "latitude",
                        "longitude",
                        "requestHash",
                        "clientMessageId",
                        "/data/user");
    }

    @Test
    void bothQueryRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/chat-rooms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/chat-rooms/" + room + "/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actualMessageSendMovesRoomToTopAndUpdatesLastMessage() throws Exception {
        long newerRoom = room(animal(owner), owner, requester);
        jdbc.update("update chat_room set updated_at='2026-09-01T00:00:00Z' where id=?", room);
        jdbc.update("update chat_room set updated_at='2026-09-02T00:00:00Z' where id=?", newerRoom);
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chatRoomId").value(newerRoom));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1/chat-rooms/" + room + "/messages")
                                .header("Authorization", requesterToken)
                                .contentType("application/json")
                                .content(
                                        "{\"clientMessageId\":\""
                                                + UUID.randomUUID()
                                                + "\",\"content\":\"새 대화\"}"))
                .andExpect(status().isCreated());
        rooms(ownerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].chatRoomId").value(room))
                .andExpect(jsonPath("$.data.items[0].lastMessage.content").value("새 대화"));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private List<Long> ids(JsonNode data, String field) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : data.path("items")) ids.add(item.path(field).asLong());
        return ids;
    }

    private ResultActions rooms(String token, String cursor) throws Exception {
        var request = get("/api/v1/chat-rooms").header("Authorization", token);
        if (cursor != null) request.param("cursor", cursor);
        return mvc.perform(request);
    }

    private ResultActions messages(long id, String token, String cursor) throws Exception {
        var request = get("/api/v1/chat-rooms/" + id + "/messages").header("Authorization", token);
        if (cursor != null) request.param("cursor", cursor);
        return mvc.perform(request);
    }

    private ResultActions messagesAfter(long id, String token, long afterMessageId)
            throws Exception {
        return mvc.perform(
                get("/api/v1/chat-rooms/" + id + "/messages")
                        .header("Authorization", token)
                        .param("afterMessageId", String.valueOf(afterMessageId)));
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                "insert into member(login_id,password_hash,nickname,status,created_at,updated_at,phone_ciphertext,phone_lookup_hash,phone_verified_at,privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at) values(?,'fixture',?,'ACTIVE',now(),now(),'private phone',?,now(),true, 'privacy-collection-v1',now()) returning id",
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private String bearer(long member) {
        return "Bearer " + sessions.create(member).accessToken();
    }

    private long animal(long member) {
        long id =
                jdbc.queryForObject(
                        "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values('LOST','USER','ACTIVE',now(),'DOG','UNKNOWN',current_date,now(),now()) returning id",
                        Long.class);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                id,
                member,
                UUID.randomUUID(),
                "a".repeat(64));
        return id;
    }

    private long room(long post, long owner, long requester) {
        return jdbc.queryForObject(
                "insert into chat_room(animal_case_id,owner_member_id,requester_member_id,created_at,updated_at) values(?,?,?,clock_timestamp(),clock_timestamp()) returning id",
                Long.class,
                post,
                owner,
                requester);
    }

    private long message(long room, long sender, String content) {
        return jdbc.queryForObject(
                "insert into chat_message(chat_room_id,sender_member_id,client_message_id,request_hash,content,created_at) values(?,?,?,?,?,clock_timestamp()) returning id",
                Long.class,
                room,
                sender,
                UUID.randomUUID(),
                "b".repeat(64),
                content);
    }

    private void photo(long post) {
        jdbc.update(
                "insert into animal_photo(animal_case_id,storage_type,storage_uri,sort_order,created_at,content_type,byte_size,width_px,height_px,checksum_sha256) values(?,'USER_UPLOAD',?,0,now(),'image/jpeg',3,512,512,?)",
                post,
                "/data/user/images/" + post + "/1.jpg",
                "b".repeat(64));
    }
}
