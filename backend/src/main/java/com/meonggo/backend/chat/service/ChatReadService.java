package com.meonggo.backend.chat.service;

import com.meonggo.backend.chat.dto.ChatReadResponse;
import com.meonggo.backend.chat.exception.ChatErrorCode;
import com.meonggo.backend.chat.repository.ChatAccessRepository;
import com.meonggo.backend.chat.repository.ChatReadRepository;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatReadService {
    private final ChatAccessRepository access;
    private final ChatReadRepository reads;
    private final TransactionTemplate transaction;

    public ChatReadService(
            ChatAccessRepository access,
            ChatReadRepository reads,
            PlatformTransactionManager manager) {
        this.access = access;
        this.reads = reads;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
    }

    public ChatReadResponse update(long roomId, long memberId, long lastReadMessageId) {
        try {
            return transaction.execute(status -> updateLocked(roomId, memberId, lastReadMessageId));
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private ChatReadResponse updateLocked(long roomId, long memberId, long requestedMessageId) {
        access.requireActiveViewer(memberId);
        var room =
                access.findRoom(roomId, memberId, true)
                        .orElseThrow(() -> new BusinessException(ChatErrorCode.ROOM_NOT_FOUND));
        if (!reads.messageBelongsToRoom(roomId, requestedMessageId)) {
            throw new BusinessException(ChatErrorCode.INVALID_MESSAGE_POSITION);
        }
        boolean owner = memberId == room.ownerId();
        Long current = owner ? room.ownerLastReadMessageId() : room.requesterLastReadMessageId();
        long result = current == null ? requestedMessageId : Math.max(current, requestedMessageId);
        if (current == null || result > current) reads.updatePosition(roomId, owner, result);
        return new ChatReadResponse(roomId, result);
    }
}
