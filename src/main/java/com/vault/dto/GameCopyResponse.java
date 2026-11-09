package com.vault.dto;

import java.time.OffsetDateTime;

/**
 * กล่องเกมหนึ่งกล่องในมุมที่แอปร้านต้องใช้
 *
 * ฟิลด์ที่แอปควรตัดสินใจจากมันคือ placement กับ bookable
 * — ไม่ต้องไปอนุมานจาก vaultId ว่า null หรือไม่
 */
public record GameCopyResponse(
    String serialNumber,
    String rfidTag,
    String shopId,
    String placement,
    String vaultId,
    String vaultName,
    boolean bookable,
    ItemRef item,
    OffsetDateTime registeredAt
) {
    /** created = รอบนี้ระบบสร้าง item ให้ใหม่ หรือใช้ของที่มีอยู่แล้ว */
    public record ItemRef(String itemId, String nameEn, String nameTh, boolean created) {}
}
