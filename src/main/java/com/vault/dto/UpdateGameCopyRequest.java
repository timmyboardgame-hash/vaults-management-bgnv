package com.vault.dto;

/**
 * แก้สิ่งที่เปลี่ยนได้หลังลงทะเบียน
 *
 * ไม่มี serialNumber ให้แก้โดยตั้งใจ — serial คือ identity ของกล่อง
 * และถูกเก็บเป็นสำเนาไว้ในใบจอง แก้แล้วการตรวจตอนคืนของจะไม่ตรงกัน
 *
 * ฟิลด์ที่ไม่ส่งมา = ไม่แตะ (null ไม่ได้แปลว่าล้างค่า)
 */
public record UpdateGameCopyRequest(
    String vaultId,
    String rfidTag
) {}
