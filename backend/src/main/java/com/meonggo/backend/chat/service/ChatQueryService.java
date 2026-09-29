package com.meonggo.backend.chat.service;

import com.meonggo.backend.chat.dto.ChatQueryResponse.Messages;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Page;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Rooms;
import com.meonggo.backend.chat.exception.ChatErrorCode;
import com.meonggo.backend.chat.query.ChatCursorCodec;
import com.meonggo.backend.chat.query.ChatCursorCodec.Position;
import com.meonggo.backend.chat.repository.ChatAccessRepository;
import com.meonggo.backend.chat.repository.ChatQueryRepository;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

@Service
public class ChatQueryService {
    private static final int MESSAGE_PAGE_SIZE = 20;
    private static final Set<String> MESSAGE_PARAMETERS = Set.of("cursor", "afterMessageId");

    private final ChatAccessRepository access;
    private final ChatQueryRepository queries;
    private final ChatCursorCodec cursors;
    private final TransactionTemplate reads;

    public ChatQueryService(
            ChatAccessRepository access,
            ChatQueryRepository queries,
            ChatCursorCodec cursors,
            PlatformTransactionManager transactions) {
        this.access = access;
        this.queries = queries;
        this.cursors = cursors;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
    }

    public Rooms rooms(long memberId, MultiValueMap<String, String> parameters) {
        return read(
                () -> {
                    access.requireActiveViewer(memberId);
                    var position = cursors.decode(parameters, memberId, 0);
                    var rows = queries.rooms(memberId, position);
                    String next = null;
                    if (rows.size() > 10) {
                        var last = rows.get(9);
                        next =
                                cursors.encode(
                                        new Position(last.updatedAt(), last.item().chatRoomId()),
                                        memberId,
                                        0);
                    }
                    return new Rooms(
                            rows.stream().limit(10).map(ChatQueryRepository.RoomRow::item).toList(),
                            new Page(10, rows.size() > 10, next, null));
                });
    }

    public Messages messages(long roomId, long memberId, MultiValueMap<String, String> parameters) {
        return read(
                () -> {
                    access.requireActiveViewer(memberId);
                    var room =
                            access.findRoom(roomId, memberId, false)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ChatErrorCode.ROOM_NOT_FOUND));
                    Long afterMessageId = afterMessageId(parameters);
                    if (afterMessageId != null
                            && !queries.messageBelongsToRoom(roomId, afterMessageId)) {
                        throw new BusinessException(ChatErrorCode.INVALID_MESSAGE_POSITION);
                    }
                    var position =
                            afterMessageId == null
                                    ? cursors.decode(parameters, memberId, roomId)
                                    : null;
                    var rows =
                            afterMessageId == null
                                    ? queries.messages(roomId, memberId, position)
                                    : queries.messagesAfter(roomId, memberId, afterMessageId);
                    String next = null;
                    Long nextAfterMessageId = null;
                    if (rows.size() > MESSAGE_PAGE_SIZE) {
                        var last = rows.get(MESSAGE_PAGE_SIZE - 1);
                        if (afterMessageId != null) {
                            nextAfterMessageId = last.messageId();
                        } else {
                            next =
                                    cursors.encode(
                                            new Position(last.createdAt(), last.messageId()),
                                            memberId,
                                            roomId);
                        }
                    }
                    var header =
                            queries.roomHeader(roomId, memberId)
                                    .orElseThrow(
                                            () ->
                                                    new BusinessException(
                                                            ChatErrorCode.ROOM_NOT_FOUND));
                    return new Messages(
                            roomId,
                            header.post(),
                            header.otherMember(),
                            room.readOnly(),
                            memberId == room.ownerId()
                                    ? room.ownerLastReadMessageId()
                                    : room.requesterLastReadMessageId(),
                            memberId == room.ownerId()
                                    ? room.requesterLastReadMessageId()
                                    : room.ownerLastReadMessageId(),
                            rows.stream().limit(MESSAGE_PAGE_SIZE).toList(),
                            new Page(
                                    MESSAGE_PAGE_SIZE,
                                    rows.size() > MESSAGE_PAGE_SIZE,
                                    next,
                                    nextAfterMessageId),
                            3000);
                });
    }

    private Long afterMessageId(MultiValueMap<String, String> parameters) {
        if (parameters.entrySet().stream()
                .anyMatch(
                        entry ->
                                !MESSAGE_PARAMETERS.contains(entry.getKey())
                                        || entry.getValue().size() != 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String rawAfterMessageId = parameters.getFirst("afterMessageId");
        if (rawAfterMessageId == null) return null;
        if (parameters.containsKey("cursor")) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        try {
            long value = Long.parseLong(rawAfterMessageId);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    private <T> T read(Supplier<T> action) {
        try {
            return reads.execute(transaction -> action.get());
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
