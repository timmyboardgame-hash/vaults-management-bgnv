package com.vault.dto;

import java.time.OffsetDateTime;

/**
 * ไม่มี DB id (bookings.id) โดยตั้งใจ — เป็น key ภายในที่ไม่มี endpoint ไหนรับ
 * เคยส่งออกไปแล้วเป็น UUID เหมือน bookingId ที่แอปส่งมาเอง สองค่าหน้าตาเหมือนกันในก้อนเดียว
 * หยิบผิดตัวไปทำ QR หรือเรียก endpoint ต่อจะพังเงียบ ๆ · อ้างอิงด้วย bookingId เท่านั้น
 */
public record BookingResponse(
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
