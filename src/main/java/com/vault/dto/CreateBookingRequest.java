package com.vault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateBookingRequest(
    @NotBlank String bookingId,
    @NotBlank String bookingName,
    @NotBlank String bookingDate,
    @NotNull  LocalDateTime bookingTimeStart,
    @NotNull  LocalDateTime bookingTimeEnd,
    @NotBlank String bookingStatus,  // ENABLE / CANCEL / PENDING
    @NotBlank String itemId,
    String pin  // optional — auto-generated if null
) {}
