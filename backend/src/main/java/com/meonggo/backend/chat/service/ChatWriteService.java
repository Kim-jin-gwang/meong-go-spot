package com.meonggo.backend.chat.service;

import com.meonggo.backend.chat.dto.ChatWriteResponse;
import com.meonggo.backend.chat.exception.ChatErrorCode;
import com.meonggo.backend.chat.repository.ChatAccessRepository;
import com.meonggo.backend.chat.repository.ChatWriteRepository;
import com.meonggo.backend.chat.web.ChatMessageInput;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatWriteService {
    private final ChatAccessRepository access;
    private final ChatWriteRepository writes;
    private final ProfanityMasker masker;
    private final TransactionTemplate transaction;

    public ChatWriteService(
            ChatAccessRepository access,
            ChatWriteRepository writes,
            ProfanityMasker masker,
            PlatformTransactionManager manager) {
        this.access = access;
        this.writes = writes;
        this.masker = masker;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
    }

    public ChatWriteResponse.Room createRoom(long postId, long requesterId) {
        try {
            return transaction.execute(status -> createLocked(postId, requesterId));
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public ChatWriteResponse.Message send(long roomId, long senderId, ChatMessageInput input) {
        String requestHash = hash(input.content());
        try {
            return transaction.execute(status -> sendLocked(roomId, senderId, input, requestHash));
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private ChatWriteResponse.Room createLocked(long postId, long requesterId) {
        access.requireActiveViewer(requesterId);
        var initial = post(postId, false);
        if ("PUBLIC".equals(initial.source())) {
            throw new BusinessException(ChatErrorCode.PUBLIC_POST);
        }
        long ownerId = initial.ownerId();
        writes.lockMembers(ownerId, requesterId);
        access.requireActiveViewer(requesterId);
        var target = post(postId, true);
        if ("PUBLIC".equals(target.source())) {
            throw new BusinessException(ChatErrorCode.PUBLIC_POST);
        }
        if (ownerId != target.ownerId()) {
            throw new BusinessException(PostErrorCode.NOT_FOUND);
        }
        if (ownerId == requesterId) {
            throw new BusinessException(ChatErrorCode.OWN_POST);
        }
        if (!"ACTIVE".equals(target.status())) {
            throw new BusinessException(ChatErrorCode.READ_ONLY);
        }
        return writes.findRoom(postId, requesterId)
                .orElseGet(
                        () ->
                                writes.insertRoom(
                                        postId, ownerId, requesterId, target.ownerNickname()));
    }

    private ChatWriteResponse.Message sendLocked(
            long roomId, long senderId, ChatMessageInput input, String requestHash) {
        access.requireActiveViewer(senderId);
        var initial =
                access.findRoom(roomId, senderId, false)
                        .orElseThrow(() -> new BusinessException(ChatErrorCode.ROOM_NOT_FOUND));
        writes.lockMembers(initial.ownerId(), initial.requesterId());
        access.requireActiveViewer(senderId);
        writes.lockCase(initial.postId());
        var room =
                access.findRoom(roomId, senderId, true)
                        .orElseThrow(() -> new BusinessException(ChatErrorCode.ROOM_NOT_FOUND));
        var existing = writes.findMessage(senderId, input.clientMessageId());
        if (existing.isPresent()) {
            var message = existing.get();
            if (message.roomId() != roomId || !message.requestHash().equals(requestHash)) {
                throw new BusinessException(PostErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return message.response();
        }
        if (room.readOnly()) {
            throw new BusinessException(ChatErrorCode.READ_ONLY);
        }
        // 욕설은 저장 전에 가린다 — 조회·푸시 어디에도 원문이 남지 않는다. 멱등 해시는 원문(requestHash)으로 이미 계산했다.
        var response =
                writes.insertMessage(
                        roomId,
                        senderId,
                        input.clientMessageId(),
                        requestHash,
                        masker.mask(input.content()));
        long recipientId = senderId == room.ownerId() ? room.requesterId() : room.ownerId();
        writes.insertNotification(response.messageId(), recipientId, response.createdAt());
        writes.touchRoom(roomId, response.createdAt());
        return response;
    }

    private ChatWriteRepository.PostTarget post(long postId, boolean lock) {
        return writes.findPost(postId, lock)
                .orElseThrow(() -> new BusinessException(PostErrorCode.NOT_FOUND));
    }

    private String hash(String content) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
