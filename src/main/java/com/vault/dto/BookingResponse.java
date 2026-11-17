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
    String serialNumber,
    // ส่งกลับให้แอปเทียบได้ทันทีว่าเราได้ค่าเดียวกับที่เขาเอาไปทำ QR
    // ถ้าแอปส่งมาแล้วตรงนี้เป็น null = ชื่อ field ผิด ค่าไม่ถึงตู้
    String qrToken,
    OffsetDateTime createdAt
) {}
