package com.vault.dto;

import java.time.LocalDateTime;

public record BookingResponse(
    String id,
    String bookingId,
    String agentId,
    String agentName,
    String itemId,
    String itemNameEn,
    String itemNameTh,
    String slotId,
    String bookingStatus,
    LocalDateTime bookingTimeStart,
    LocalDateTime bookingTimeEnd,
    String bookingName,
    String bookingDate,
    String pin,
    LocalDateTime createdAt
) {}
