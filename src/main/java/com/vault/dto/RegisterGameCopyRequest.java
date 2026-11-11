package com.vault.dto;

/**
 * แอปร้านส่งมาตอนลงทะเบียนกล่องเกมหนึ่งกล่อง
 *
 * ไม่ใช้ bean validation annotation เพราะต้องตอบเป็นรหัส error ตามสัญญา
 * (SERIAL_REQUIRED / RFID_TAG_REQUIRED) ไม่ใช่ก้อน "Validation failed"
 *
 * @param serialNumber identity ของกล่อง — บังคับ
 * @param rfidTag      EPC ของแท็กที่คนลงทะเบียนแปะไว้แล้ว — บังคับ แต่ไม่จำกัดรูปแบบ
 *                     (แท็กมีหลายฟอร์แมต รับเป็น string ตามที่แอปร้านส่งมา)
 * @param vaultId      ตู้ที่จะเอากล่องไปวาง — ไม่บังคับ ไม่ส่งมาก็ลงทะเบียนไว้ก่อนได้
 * @param item         ข้อมูลเกม — ถ้า itemId ยังไม่มีในระบบจะสร้างให้จากชื่อที่แนบมา
 */
public record RegisterGameCopyRequest(
    String serialNumber,
    String rfidTag,
    String vaultId,
    ItemPayload item
) {
    /**
     * catalog เกมอยู่ที่ฝั่งแอป — ส่งมาเท่าที่มี
     * nameEn/nameTh บังคับเฉพาะตอนที่ itemId นี้ยังไม่มีในระบบ (ต้องมีชื่อไปสร้าง)
     */
    public record ItemPayload(
        String itemId,
        String nameEn,
        String nameTh,
        String imageUrl,
        Integer playerCountMin,
        Integer playerCountMax,
        Integer playTimeMin,
        Integer playTimeMax,
        Double difficultyRating,
        String defaultPin
    ) {}
}
