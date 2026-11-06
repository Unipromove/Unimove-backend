package com.unimove.domain.chat.dto;

import com.unimove.domain.chat.ChatMessage;
import com.unimove.domain.user.Role;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(
        UUID id,
        Long seq,
        UUID rideId,
        UUID senderId,
        Role senderRole,
        String senderName,
        String body,
        Instant createdAt
) {
    /** @param senderName primeiro nome de quem enviou (null se a conta sumiu). */
    public static ChatMessageResponse from(ChatMessage m, String senderName) {
        return new ChatMessageResponse(
                m.getId(),
                m.getSeq(),
                m.getRideId(),
                m.getSenderId(),
                m.getSenderRole(),
                senderName,
                m.getBody(),
                m.getCreatedAt()
        );
    }
}
