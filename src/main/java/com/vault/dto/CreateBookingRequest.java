package com.vault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record CreateBookingRequest(
    @NotBlank String bookingId,
    @NotBlank String itemId,
    @NotNull  OffsetDateTime bookingTimeStart,
    @NotNull  OffsetDateTime bookingTimeEnd,
    String bookingName,   // optional
    String bookingDate,   // optional
    String pin,           // optional — auto-generated if null
    @NotBlank String serialNumber   // เลข barcode กล่องบอร์ดเกม
) {}
