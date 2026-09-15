package com.vault.dto;

import java.util.List;

/**
 * ข้อมูลหน้า Vault Analytics
 *
 * ใช้แค่ String / ตัวเลข ไม่มี LocalDate — ข้อมูลกราฟถูก serialize เป็น JSON ผ่าน Thymeleaf
 * ซึ่งสร้าง ObjectMapper ของตัวเอง ถ้าใส่ชนิดวันที่อาจออกมาเป็นก้อน object แทนข้อความ
 * ค่าที่ต้องตัดสินใจแสดงผล (สีของ delta, ข้อความ) คำนวณมาให้แล้ว template จะได้ไม่มี logic
 */
public class VaultAnalyticsDto {

    public record VaultOption(String vaultId, String vaultName) {}

    public record Filter(
        String vaultId,          // null = ทุกตู้
        int days,
        boolean compare,
        String fromLabel,
        String toLabel,
        String granularity,      // day / week / month
        String granularityLabel
    ) {}

    /** ตัวเลขหลัก 1 ใบ — deltaLabel null = ไม่ได้เปิดเทียบช่วงก่อน · deltaClass up = ดีขึ้น, down = แย่ลง */
    public record Kpi(
        String label,
        String value,
        String unit,
        String deltaClass,
        String deltaLabel,
        String sparkCsv
    ) {}

    public record TrendPoint(String label, long bookings, long eventBookings, long cycles) {}

    /** heatmap — 1 แถว = วันในสัปดาห์, cells เรียงตามชั่วโมงใน hours */
    public record HeatRow(String dayLabel, List<HeatCell> cells) {}
    public record HeatCell(int hour, long cycles, String alpha) {}

    public record BarRow(String label, String sub, long value, String valueLabel, double widthPct, boolean warn) {}

    public record VaultRow(
        String vaultId,
        String vaultName,
        long bookings,
        long cycles,
        double busyPct,
        long avgMinutes,
        String severity,         // ok / warn / bad — ตามเวลาที่ตู้ไม่ว่าง
        String sparkCsv
    ) {}

    public record Usage(
        List<Kpi> kpis,
        List<TrendPoint> trend,
        List<Integer> hours,
        List<HeatRow> heat,
        String heatPeak,
        List<BarRow> topBoxes,
        List<BarRow> durations,
        String shortCycleNote,
        List<BarRow> perSession,
        String perSessionNote,
        List<VaultRow> vaults
    ) {}

    // ── แท็บความผิดปกติ ──────────────────────────────────────────────────────
    public record ProblemPoint(String label, long anomalies, long noAck, long unpaired, long lateEvents) {}

    /** ปัญหาที่ยังค้างอยู่ตอนนี้ — ต้องมีคนไปแตะ ระบบปิดเองไม่ได้ */
    public record Issue(String kind, String severity, String bookingId, String vaultId, String age, String hint) {}

    public record VaultIssueRow(
        String vaultId,
        String vaultName,
        long bookings,
        String noAckPct,
        long unpaired,
        long lateEvents,
        long anomalies,
        String severity
    ) {}

    public record Problems(
        List<Kpi> kpis,
        List<ProblemPoint> trend,
        List<BarRow> anomalies,
        List<Issue> openIssues,
        int openIssueTotal,
        List<VaultIssueRow> vaults
    ) {}

    // ── แท็บ Booking ย้อนหลัง ─────────────────────────────────────────────────
    public record Flag(String label, String tone) {}   // tone: bad / warn / info

    public record BookingRow(
        String bookingId,
        String type,             // event / game
        String vaultId,
        String startLabel,
        String windowLabel,
        int cycles,
        long totalMinutes,
        List<Flag> flags,
        String status,
        boolean deleted
    ) {}

    public record BookingPage(
        String filter,           // all / event / game / flagged
        List<BookingRow> rows,
        long total,
        int page,
        int pageCount,
        String rangeLabel
    ) {}

    public record Page(
        String tab,              // use / problems / bookings
        Filter filter,
        List<VaultOption> vaultOptions,
        String scopeName,
        String scopeMeta,
        String computedAtLabel,  // สรุปล่าสุดเมื่อไหร่ — null = ยังไม่เคยคำนวณ
        boolean hasData,
        Usage usage,             // มีเฉพาะแท็บที่เปิดอยู่ — คำนวณแค่แท็บเดียวต่อครั้ง
        Problems problems,
        BookingPage bookings
    ) {}
}
