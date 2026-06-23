package com.vault.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class BookingMonitorDto {

    public record MonitorSlot(
        String slotId,       // แสดงผลใน grid เท่านั้น
        String itemId,       // ใช้ ref หน้า detail
        String itemNameEn,
        boolean occupied,
        String currentBookingId,
        String currentStatus
    ) {}

    public record MonitorVault(
        String vaultId,
        String vaultName,
        boolean online,
        Integer totalSlots,
        List<MonitorSlot> slots
    ) {}

    public record StatusEventDto(
        String status,
        OffsetDateTime occurredAt,
        String note
    ) {}

    public record CurrentBookingDto(
        String bookingId,
        String agentId,
        String agentName,
        String itemNameEn,
        String status,
        OffsetDateTime bookingTimeStart,
        OffsetDateTime bookingTimeEnd,
        String pin
    ) {}

    public record HistoryBookingDto(
        String bookingId,
        String agentId,
        String agentName,
        String itemNameEn,
        OffsetDateTime bookingTimeStart,
        OffsetDateTime bookingTimeEnd,
        String finalStatus,
        List<StatusEventDto> events
    ) {}

    public record ItemHistoryDto(
        String vaultId,
        String vaultName,
        String itemId,
        String itemNameEn,
        CurrentBookingDto currentBooking,
        List<HistoryBookingDto> history
    ) {}
}
