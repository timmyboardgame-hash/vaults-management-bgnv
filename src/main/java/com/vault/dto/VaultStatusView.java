package com.vault.dto;

/**
 * สถานะตู้ที่คำนวณมาให้หน้าเว็บแล้ว — template จะได้ไม่มี logic
 *
 * online เป็นสิ่งที่เราสรุปเอง ไม่ใช่ค่าที่ตู้ส่งมา: ตู้บอกได้แค่ตอนที่มันยังพูดได้
 * ถ้าเน็ตหลุดหรือเครื่องดับ จะไม่มีใครมาบอกเรา เราจึงดูจากเวลาที่ heartbeat ล่าสุดเข้ามาแทน
 */
public record VaultStatusView(
    boolean reported,        // false = ยังไม่เคยมี heartbeat เข้ามาเลย
    boolean online,
    boolean stale,           // เคยรายงานแต่ตอนนี้เงียบ — ค่าที่เหลือเป็นของเก่า ไม่ใช่สถานะตอนนี้
    String stateLabel,       // ติดต่อได้ / ขาดการติดต่อ / ยังไม่เคยรายงาน
    String tone,             // ok / warn / bad
    String mode,
    String modeLabel,
    boolean lockdown,
    Integer itemsInLocker,
    Integer itemsRentedOut,
    Integer availableSlots,
    Integer totalSlots,
    String lastScanTime,
    String lastSeenLabel,    // "3 นาทีที่แล้ว"
    String lastSeenExact
) {}
