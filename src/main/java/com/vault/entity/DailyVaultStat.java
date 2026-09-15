package com.vault.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ยอดสรุปรายวันต่อตู้ — ตารางคำนวณ หน้า Vault Analytics อ่านจากตารางนี้
 *
 * 1 แถว = ตู้ × วัน (เวลาไทย) · 24 ตู้ × 365 วัน = 8,760 แถว/ปี
 * งานรายคืนลบของวันนั้นแล้วเขียนใหม่ทั้งแถว จึงรันซ้ำกี่รอบก็ได้ผลเท่าเดิม
 */
@Entity
@Table(name = "daily_vault_stats",
    uniqueConstraints = @UniqueConstraint(name = "uk_daily_vault", columnNames = {"stat_date", "vault_id"}),
    indexes = @Index(name = "idx_daily_vault_date", columnList = "vault_id, stat_date"))
public class DailyVaultStat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    // ── booking (นับตามวันที่เริ่มช่วงจอง) ──
    @Column(name = "bookings_total", nullable = false)     private int bookingsTotal;
    @Column(name = "bookings_event", nullable = false)     private int bookingsEvent;
    @Column(name = "bookings_game", nullable = false)      private int bookingsGame;
    @Column(name = "bookings_cancelled", nullable = false) private int bookingsCancelled;
    @Column(name = "bookings_no_ack", nullable = false)    private int bookingsNoAck;   // ไม่เคยได้ CONFIRMED

    // ── รอบหยิบ-คืนที่จบแล้ว (นับตามวันที่หยิบ) ──
    @Column(name = "cycles", nullable = false)       private int cycles;
    @Column(name = "busy_minutes", nullable = false) private long busyMinutes;
    @Column(name = "late_cycles", nullable = false)  private int lateCycles;

    // ช่วงเวลายืมต่อรอบ — เก็บเป็นช่องไว้เลย หน้าเว็บจะได้ไม่ต้องไล่อ่านทุกรอบย้อนหลังหลายปี
    @Column(name = "dur_under_5", nullable = false)   private int durUnder5;
    @Column(name = "dur_5_15", nullable = false)      private int dur5To15;
    @Column(name = "dur_15_30", nullable = false)     private int dur15To30;
    @Column(name = "dur_30_60", nullable = false)     private int dur30To60;
    @Column(name = "dur_60_120", nullable = false)    private int dur60To120;
    @Column(name = "dur_over_120", nullable = false)  private int durOver120;

    // ── ความผิดปกติ ──
    @Column(name = "anomaly_wrong_tag", nullable = false)          private int anomalyWrongTag;
    @Column(name = "anomaly_lockdown_triggered", nullable = false) private int anomalyLockdownTriggered;
    @Column(name = "anomaly_lockdown_cleared", nullable = false)   private int anomalyLockdownCleared;
    @Column(name = "anomaly_force_unlock", nullable = false)       private int anomalyForceUnlock;
    @Column(name = "anomaly_late_return", nullable = false)        private int anomalyLateReturn;
    @Column(name = "late_events", nullable = false)                private int lateEvents;
    @Column(name = "unpaired_moves", nullable = false)             private int unpairedMoves;  // หยิบแล้วไม่มีคืน ทั้งที่ booking จบ/เลยเวลา

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    public DailyVaultStat() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public int getBookingsTotal() { return bookingsTotal; }
    public void setBookingsTotal(int v) { this.bookingsTotal = v; }
    public int getBookingsEvent() { return bookingsEvent; }
    public void setBookingsEvent(int v) { this.bookingsEvent = v; }
    public int getBookingsGame() { return bookingsGame; }
    public void setBookingsGame(int v) { this.bookingsGame = v; }
    public int getBookingsCancelled() { return bookingsCancelled; }
    public void setBookingsCancelled(int v) { this.bookingsCancelled = v; }
    public int getBookingsNoAck() { return bookingsNoAck; }
    public void setBookingsNoAck(int v) { this.bookingsNoAck = v; }

    public int getCycles() { return cycles; }
    public void setCycles(int v) { this.cycles = v; }
    public long getBusyMinutes() { return busyMinutes; }
    public void setBusyMinutes(long v) { this.busyMinutes = v; }
    public int getLateCycles() { return lateCycles; }
    public void setLateCycles(int v) { this.lateCycles = v; }

    public int getDurUnder5() { return durUnder5; }
    public void setDurUnder5(int v) { this.durUnder5 = v; }
    public int getDur5To15() { return dur5To15; }
    public void setDur5To15(int v) { this.dur5To15 = v; }
    public int getDur15To30() { return dur15To30; }
    public void setDur15To30(int v) { this.dur15To30 = v; }
    public int getDur30To60() { return dur30To60; }
    public void setDur30To60(int v) { this.dur30To60 = v; }
    public int getDur60To120() { return dur60To120; }
    public void setDur60To120(int v) { this.dur60To120 = v; }
    public int getDurOver120() { return durOver120; }
    public void setDurOver120(int v) { this.durOver120 = v; }

    public int getAnomalyWrongTag() { return anomalyWrongTag; }
    public void setAnomalyWrongTag(int v) { this.anomalyWrongTag = v; }
    public int getAnomalyLockdownTriggered() { return anomalyLockdownTriggered; }
    public void setAnomalyLockdownTriggered(int v) { this.anomalyLockdownTriggered = v; }
    public int getAnomalyLockdownCleared() { return anomalyLockdownCleared; }
    public void setAnomalyLockdownCleared(int v) { this.anomalyLockdownCleared = v; }
    public int getAnomalyForceUnlock() { return anomalyForceUnlock; }
    public void setAnomalyForceUnlock(int v) { this.anomalyForceUnlock = v; }
    public int getAnomalyLateReturn() { return anomalyLateReturn; }
    public void setAnomalyLateReturn(int v) { this.anomalyLateReturn = v; }
    public int getLateEvents() { return lateEvents; }
    public void setLateEvents(int v) { this.lateEvents = v; }
    public int getUnpairedMoves() { return unpairedMoves; }
    public void setUnpairedMoves(int v) { this.unpairedMoves = v; }

    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
