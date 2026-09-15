package com.vault.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ยอดสรุปรายวันต่อกล่อง — ตารางคำนวณ
 *
 * กล่องจริงระบุด้วย serial_number ไม่ใช่ item_id — เกมเดียวกันมีได้หลายกล่อง (คนละ serial)
 * item_id เก็บไว้ join เอาชื่อเกม · slot_id ไม่ได้ใช้เพราะเลิกกรอกตั้งแต่ย้ายไปผูกด้วย RFID
 */
@Entity
@Table(name = "daily_item_stats",
    uniqueConstraints = @UniqueConstraint(name = "uk_daily_item",
        columnNames = {"stat_date", "vault_id", "item_id", "serial_number"}),
    indexes = {
        @Index(name = "idx_daily_item_date",  columnList = "item_id, stat_date"),
        @Index(name = "idx_daily_item_vault", columnList = "vault_id, stat_date")
    })
public class DailyItemStat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "borrow_count", nullable = false)
    private int borrowCount;

    @Column(name = "busy_minutes", nullable = false)
    private long busyMinutes;

    @Column(name = "late_count", nullable = false)
    private int lateCount;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    public DailyItemStat() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }
    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
    public int getBorrowCount() { return borrowCount; }
    public void setBorrowCount(int borrowCount) { this.borrowCount = borrowCount; }
    public long getBusyMinutes() { return busyMinutes; }
    public void setBusyMinutes(long busyMinutes) { this.busyMinutes = busyMinutes; }
    public int getLateCount() { return lateCount; }
    public void setLateCount(int lateCount) { this.lateCount = lateCount; }
    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
