package com.vault.dto;

/**
 * heartbeat ที่ตู้ปล่อยทุก 15 นาที (สัญญา §4.11) ส่งต่อมาจาก Lambda
 * สัญญาผูกแค่ state กับ mode — ฟิลด์ที่เหลือเป็น diagnostic อาจหายหรือเปลี่ยนได้
 */
public record StatusReportRequest(
    String state,             // online
    String mode,              // normal | register | lockdown
    Integer totalSlots,
    Integer itemsInLocker,
    Integer itemsRentedOut,
    Integer availableSlots,
    String lastScanTime
) {}
