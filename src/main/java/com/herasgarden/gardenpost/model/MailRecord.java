package com.herasgarden.gardenpost.model;

import java.util.UUID;

public record MailRecord(
        UUID id,
        UUID orderId,
        UUID senderId,
        String senderName,
        UUID recipientId,
        String recipientName,
        UUID propertyId,
        UUID mailboxWorldId,
        String mailboxWorldName,
        int mailboxX,
        int mailboxY,
        int mailboxZ,
        String address,
        String message,
        String attachmentData,
        int attachmentCount,
        MailStatus status,
        UUID assignedMailman,
        long createdAt,
        Long assignedAt,
        Long deliveredAt
) {
    public boolean parcel() {
        return attachmentCount > 0;
    }
}
