package com.vault.dto;

import java.time.OffsetDateTime;

public record ExtendBookingRequest(
    OffsetDateTime validUntil   // ส่งเป็น +07:00 — backend แปลงเป็น Bangkok LocalDateTime ก่อน publish MQTT
) {}
