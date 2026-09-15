package com.vault.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 1 แถว = 1 รอบหยิบ-คืนที่จบแล้ว — ตารางคำนวณ สร้างใหม่ได้จาก booking_status_events
 *
 * งานสรุปรายคืนเขียนตารางนี้ (ไม่ใช่ตอน runtime) จึงไม่แตะ flow รับ event จากตู้
 * เก็บเฉพาะรอบที่คืนแล้ว — รอบที่ยังค้างนับเป็น unpaired ใน daily_vault_stats แทน
 *
 * pickup_hour / pickup_dow เก็บแยกไว้เพราะ SQLite เก็บเวลาเป็น epoch millis
 * การ GROUP BY ชั่วโมงด้วย date function ใน SQL จะเจอปัญหาเขตเวลาซ้ำอีกชั้น
 */
@Entity
@Table(name = "borrow_records",
    indexes = {
        @Index(name = "idx_borrow_vault_date", columnList = "vault_id, stat_date"),
        @Index(name = "idx_borrow_item_date",  columnList = "item_id, stat_date"),
        @Index(name = "idx_borrow_booking",    columnList = "booking_id")
    })
public class BorrowRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    // กล่องที่ถูกหยิบจริง — event booking ชี้ session pass จึงต้อง resolve จาก epc · null = tag ไม่ได้ลงทะเบียน
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @Column(name = "booking_type", nullable = false)
    private String bookingType;  // event / game

    @Column(name = "match_key")
    private String matchKey;     // epc ถ้ามี ไม่งั้น serial

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "epc")
    private String epc;

    @Column(name = "picked_up_at", nullable = false)
    private LocalDateTime pickedUpAt;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    @Column(name = "minutes", nullable = false)
    private long minutes;

    @Column(name = "late", nullable = false)
    private boolean late;

    @Column(name = "pickup_hour", nullable = false)
    private int pickupHour;      // 0–23 เวลาไทย

    @Column(name = "pickup_dow", nullable = false)
    private int pickupDow;       // 1 = จันทร์ … 7 = อาทิตย์

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;  // วันที่หยิบ (เวลาไทย) — รอบข้ามเที่ยงคืนนับเป็นวันที่หยิบ

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    public BorrowRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public String getBookingType() { return bookingType; }
    public void setBookingType(String bookingType) { this.bookingType = bookingType; }

    public String getMatchKey() { return matchKey; }
    public void setMatchKey(String matchKey) { this.matchKey = matchKey; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getEpc() { return epc; }
    public void setEpc(String epc) { this.epc = epc; }

    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }

    public LocalDateTime getReturnedAt() { return returnedAt; }
    public void setReturnedAt(LocalDateTime returnedAt) { this.returnedAt = returnedAt; }

    public long getMinutes() { return minutes; }
    public void setMinutes(long minutes) { this.minutes = minutes; }

    public boolean isLate() { return late; }
    public void setLate(boolean late) { this.late = late; }

    public int getPickupHour() { return pickupHour; }
    public void setPickupHour(int pickupHour) { this.pickupHour = pickupHour; }

    public int getPickupDow() { return pickupDow; }
    public void setPickupDow(int pickupDow) { this.pickupDow = pickupDow; }

    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }

    public LocalDateTime getComputedAt() { return computedAt; }
    public void setComputedAt(LocalDateTime computedAt) { this.computedAt = computedAt; }
}
