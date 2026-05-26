package com.vault.dto;

import java.time.OffsetDateTime;

public record BookingResponse(
    String id,
    String bookingId,
    String agentId,
    String agentName,
    String itemId,
    String itemNameEn,
    String itemNameTh,
    String slotId,
    String vaultId,
    String bookingStatus,
    OffsetDateTime bookingTimeStart,
    OffsetDateTime bookingTimeEnd,
    String bookingName,
    String bookingDate,
    String pin,
    OffsetDateTime createdAt
) {}
