package com.meonggo.backend.chat.repository;

import com.meonggo.backend.chat.dto.ChatQueryResponse.LastMessage;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Member;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Message;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Post;
import com.meonggo.backend.chat.dto.ChatQueryResponse.RoomItem;
import com.meonggo.backend.chat.query.ChatCursorCodec.Position;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatQueryRepository {
    private static final String ROOMS =
            """
            select r.id,r.updated_at,c.id as post_id,c.case_type,c.status as post_status,c.name as post_name,
              c.species,c.breed_name,c.sex,
              other.id as other_id,
              case when other.status='ACTIVE' and other.deleted_at is null then other.nickname else '탈퇴한 회원' end as other_nickname,
              (c.status<>'ACTIVE' or c.deleted_at is not null
                or owner.status<>'ACTIVE' or owner.deleted_at is not null
                or requester.status<>'ACTIVE' or requester.deleted_at is not null) as read_only,
              recent.id as last_message_id,recent.content as last_content,
              recent.created_at as last_created_at,p.id as photo_id,
              unread.unread_count
            from chat_room r
            join animal_case c on c.id=r.animal_case_id and c.source_type='USER'
            join user_post u on u.animal_case_id=c.id and u.member_id=r.owner_member_id
            join member owner on owner.id=r.owner_member_id
            join member requester on requester.id=r.requester_member_id
            join member other on other.id=case when r.owner_member_id=:member then r.requester_member_id else r.owner_member_id end
            left join lateral (
              select m.id,m.content,m.created_at from chat_message m
              where m.chat_room_id=r.id and m.sender_member_id in(r.owner_member_id,r.requester_member_id)
              order by m.created_at desc,m.id desc limit 1
            ) recent on true
            left join lateral (
              select count(*) as unread_count from chat_message m
              where m.chat_room_id=r.id and m.sender_member_id=other.id
                and m.id>coalesce(
                  case when r.owner_member_id=:member then r.owner_last_read_message_id
                       else r.requester_last_read_message_id end,0)
            ) unread on true
            left join lateral (
              select ap.id from animal_photo ap
              where ap.animal_case_id=c.id and ap.storage_type='USER_UPLOAD'
                and owner.status='ACTIVE' and owner.deleted_at is null and c.deleted_at is null
                and (c.status='ACTIVE' or (c.status='CLOSED' and r.owner_member_id=:member
                  and c.closed_at + interval '90 days' > CURRENT_TIMESTAMP))
              order by ap.sort_order,ap.id limit 1
            ) p on true
            where (r.owner_member_id=:member or r.requester_member_id=:member)
            """;

    private static final String ROOM_HEADER =
            """
            select c.id as post_id,c.case_type,c.status as post_status,c.name as post_name,
              c.species,c.breed_name,c.sex,
              other.id as other_id,
              case when other.status='ACTIVE' and other.deleted_at is null then other.nickname else '탈퇴한 회원' end as other_nickname,
              p.id as photo_id
            from chat_room r
            join animal_case c on c.id=r.animal_case_id and c.source_type='USER'
            join user_post u on u.animal_case_id=c.id and u.member_id=r.owner_member_id
            join member owner on owner.id=r.owner_member_id
            join member other on other.id=case when r.owner_member_id=:member then r.requester_member_id else r.owner_member_id end
            left join lateral (
              select ap.id from animal_photo ap
              where ap.animal_case_id=c.id and ap.storage_type='USER_UPLOAD'
                and owner.status='ACTIVE' and owner.deleted_at is null and c.deleted_at is null
                and (c.status='ACTIVE' or (c.status='CLOSED' and r.owner_member_id=:member
                  and c.closed_at + interval '90 days' > CURRENT_TIMESTAMP))
              order by ap.sort_order,ap.id limit 1
            ) p on true
            where r.id=:room and (r.owner_member_id=:member or r.requester_member_id=:member)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ChatQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoomRow> rooms(long memberId, Position position) {
        var parameters = new MapSqlParameterSource("member", memberId);
        String sql = ROOMS;
        if (position != null) {
            sql += " and (r.updated_at,r.id)<(:timestamp,:id)";
            position(parameters, position);
        }
        return jdbc.query(
                sql + " order by r.updated_at desc,r.id desc limit 11", parameters, this::room);
    }

    /**
     * 방 하나의 머리글입니다 — 어느 게시물의 대화이고 상대가 누구인지.
     *
     * <p>C3 가 메시지만 주던 때는 화면이 받은 메시지에서 상대 이름을 찾아야 했고, 상대가 아직 아무 말도 하지 않았으면 끝내 알 수 없었다. 사진 공개 조건은 목록과
     * 같은 규칙을 쓴다.
     */
    public Optional<RoomHeader> roomHeader(long roomId, long memberId) {
        var parameters = new MapSqlParameterSource("room", roomId).addValue("member", memberId);
        return jdbc.query(ROOM_HEADER, parameters, this::header).stream().findFirst();
    }

    private RoomHeader header(ResultSet row, int index) throws SQLException {
        Long photoId = row.getObject("photo_id", Long.class);
        return new RoomHeader(
                post(row, photoId),
                new Member(row.getLong("other_id"), row.getString("other_nickname")));
    }

    private Post post(ResultSet row, Long photoId) throws SQLException {
        return new Post(
                row.getLong("post_id"),
                row.getString("case_type"),
                row.getString("post_status"),
                row.getString("post_name"),
                row.getString("species"),
                row.getString("breed_name"),
                row.getString("sex"),
                photoId == null ? null : "/api/v1/photos/" + photoId);
    }

    public record RoomHeader(Post post, Member otherMember) {}

    public List<Message> messages(long roomId, long memberId, Position position) {
        var parameters = new MapSqlParameterSource("room", roomId).addValue("member", memberId);
        String sql = messageQuery();
        if (position != null) {
            sql += " and (m.created_at,m.id)<(:timestamp,:id)";
            position(parameters, position);
        }
        return jdbc.query(
                sql + " order by m.created_at desc,m.id desc limit 21", parameters, this::message);
    }

    public boolean messageBelongsToRoom(long roomId, long messageId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from chat_message where id=:message and chat_room_id=:room)",
                        new MapSqlParameterSource("message", messageId).addValue("room", roomId),
                        Boolean.class));
    }

    public List<Message> messagesAfter(long roomId, long memberId, long afterMessageId) {
        var parameters =
                new MapSqlParameterSource("room", roomId)
                        .addValue("member", memberId)
                        .addValue("after", afterMessageId);
        return jdbc.query(
                messageQuery() + " and m.id>:after order by m.id asc limit 21",
                parameters,
                this::message);
    }

    private RoomRow room(ResultSet row, int index) throws SQLException {
        Long photoId = row.getObject("photo_id", Long.class);
        Timestamp lastCreated = row.getTimestamp("last_created_at");
        var item =
                new RoomItem(
                        row.getLong("id"),
                        post(row, photoId),
                        new Member(row.getLong("other_id"), row.getString("other_nickname")),
                        lastCreated == null
                                ? null
                                : new LastMessage(
                                        row.getLong("last_message_id"),
                                        row.getString("last_content"),
                                        lastCreated.toInstant()),
                        row.getLong("unread_count"),
                        row.getLong("unread_count") > 0,
                        row.getBoolean("read_only"));
        return new RoomRow(item, row.getTimestamp("updated_at").toInstant());
    }

    private void position(MapSqlParameterSource parameters, Position position) {
        parameters
                .addValue("timestamp", Timestamp.from(position.timestamp()))
                .addValue("id", position.id());
    }

    private String messageQuery() {
        return """
                select m.id,m.sender_member_id,m.content,m.created_at,
                  case when sender.status='ACTIVE' and sender.deleted_at is null then sender.nickname else '탈퇴한 회원' end as nickname
                from chat_message m
                join chat_room r on r.id=m.chat_room_id
                join member sender on sender.id=m.sender_member_id
                where r.id=:room and (r.owner_member_id=:member or r.requester_member_id=:member)
                  and m.sender_member_id in(r.owner_member_id,r.requester_member_id)
                """;
    }

    private Message message(ResultSet row, int index) throws SQLException {
        return new Message(
                row.getLong("id"),
                new Member(row.getLong("sender_member_id"), row.getString("nickname")),
                row.getString("content"),
                row.getTimestamp("created_at").toInstant());
    }

    public record RoomRow(RoomItem item, Instant updatedAt) {}
}
