package com.vault.dto;

import java.time.LocalDateTime;

/**
 * กล่องเกมหนึ่งกล่อง
 *
 * vaultId/vaultName เป็น null ได้ — กล่องที่ร้านลงทะเบียนเข้ามาแต่ยังไม่ได้เอาใส่ตู้
 * ใช้ placement ตัดสินใจแสดงผล ไม่ต้องเช็ค null เอง
 */
public record VaultItemResponse(
    String id,
    String vaultId,
    String vaultName,
    String itemId,
    String itemNameEn,
    String itemNameTh,
    String serialNumber,
    String rfidTag,
    String defaultPin,
    String status,
    LocalDateTime createdAt,
    String ownerAgentId,
    String placement
) {
    /** UNASSIGNED = ลงทะเบียนแล้วแต่ยังไม่เข้าตู้ จองไม่ได้ · IN_VAULT = อยู่ในตู้ พร้อมให้จอง */
    public static final String UNASSIGNED = "UNASSIGNED";
    public static final String IN_VAULT = "IN_VAULT";

    public boolean inVault() { return IN_VAULT.equals(placement); }
}
