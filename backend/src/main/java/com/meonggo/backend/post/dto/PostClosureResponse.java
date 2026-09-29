package com.meonggo.backend.post.dto;

import com.meonggo.backend.post.entity.CloseReason;
import java.time.Instant;

public record PostClosureResponse(
        long postId, String status, long version, CloseReason closeReason, Instant closedAt) {}
