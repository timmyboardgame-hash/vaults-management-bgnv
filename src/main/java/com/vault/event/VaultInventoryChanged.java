package com.vault.event;

/**
 * ของในตู้นี้เปลี่ยน — ต้อง sync รายการแท็กไปให้ตู้ใหม่
 *
 * ยิงตอนกล่องเข้าตู้ ออกจากตู้ ย้ายตู้ หรือเปลี่ยนแท็ก
 * ตัว sync รอให้ transaction commit ก่อนเสมอ (ดู VaultInventoryService)
 * ไม่งั้นถ้า commit ล้มทีหลัง ตู้จะถือรายการที่ฐานข้อมูลไม่มี
 *
 * ย้ายข้ามตู้ต้องยิงสองใบ — ตู้เดิมต้องรู้ว่าของหายไปแล้วด้วย
 */
public record VaultInventoryChanged(String vaultId, String reason) {}
