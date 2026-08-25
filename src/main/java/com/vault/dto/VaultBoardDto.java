package com.vault.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * ข้อมูลหน้า Vault Board — booking ทุกใบของตู้ + สถานะกล่องในตู้ ควบคู่กัน
 * ทุกค่าคำนวณจาก booking_status_events ที่ระบบบันทึกอยู่แล้ว (ไม่มีตารางใหม่)
 */
public class VaultBoardDto {

    /** 1 รอบการหยิบ-คืน — event booking มีหลายรอบ, game booking มีรอบเดียว */
    public record Cycle(
        String itemName,
        String serialNumber,
        String matchKey,             // epc ถ้ามี ไม่งั้น serial — ใช้จับคู่กับกล่องในตู้ (serial อาจว่าง)
        OffsetDateTime pickedUpAt,
        OffsetDateTime returnedAt,   // null = ยังไม่คืน
        long minutes,                // ยืมไปแล้วกี่นาที (นับถึงตอนนี้ถ้ายังไม่คืน)
        boolean late,                // คืน/ค้างเกิน bookingTimeEnd
        boolean pending              // ยังไม่ถูกหยิบเลย
    ) {}

    public record TimelineEvent(
        String status,
        OffsetDateTime occurredAt,
        String note
    ) {}

    public record BoardBooking(
        String bookingId,
        String type,                 // "event" | "game"
        String status,
        String agentName,
        OffsetDateTime timeStart,
        OffsetDateTime timeEnd,
        String pin,
        boolean done,                // อยู่กลุ่ม "จบแล้ว"
        long minutesLeft,            // เหลือก่อนหมดเวลา (ติดลบ = เลยแล้ว)
        long minutesElapsed,         // ยืมมาแล้วกี่นาที (กล่องที่ยังถืออยู่นานสุด)
        int cycleCount,
        int holdingCount,            // ถืออยู่กี่กล่องตอนนี้
        long totalMinutes,           // เวลายืมรวมทุกรอบ
        List<Cycle> cycles,
        List<TimelineEvent> timeline
    ) {}

    /** กล่องในตู้ 1 ใบ */
    public record BoardItem(
        String itemId,
        String itemName,
        String serialNumber,
        boolean out,                 // ถูกหยิบออกไปอยู่
        boolean over,                // เกินเวลาที่จอง
        long minutesOut,             // ออกไปแล้วกี่นาที
        long limitMinutes,           // ช่วงเวลาที่จองไว้
        String heldBy,               // booking id ที่ถืออยู่ (null ถ้าอยู่ในตู้)
        String reservedBy,           // booking id ที่จองไว้แต่ยังไม่หยิบ
        boolean session              // เป็น session pass (ไม่ใช่กล่องเกมจริง)
    ) {}

    public record Board(
        String vaultId,
        String vaultName,
        boolean online,
        int openBookings,
        int openEvents,
        int openGames,
        int attentionCount,          // booking ที่ OVERDUE หรือมีกล่องเกินเวลา
        String attentionNote,
        int itemsOut,
        int itemsTotal,
        int utilisationPct,
        int doneToday,
        long avgMinutesToday,
        List<BoardBooking> active,
        List<BoardBooking> done,
        List<BoardItem> items
    ) {}
}
