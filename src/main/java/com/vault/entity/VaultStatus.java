package com.vault.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * สถานะล่าสุดของตู้ จาก heartbeat ที่ตู้ปล่อยทุก 15 นาที (สัญญา MQTT §4.11 topic vault/{thing}/status)
 *
 * เก็บแถวเดียวต่อตู้ ทับของเดิมทุกครั้งที่มี heartbeat ใหม่ — ตารางนี้ตอบคำถามว่า "ตอนนี้ตู้เป็นยังไง"
 * ไม่ได้เก็บประวัติ ถ้าวันหลังอยากได้ uptime ย้อนหลังค่อยเพิ่มตารางประวัติแยก
 *
 * สัญญาผูกแค่ state กับ mode ที่เหลือเป็น diagnostic ที่อาจเปลี่ยน schema ได้ จึงยอมให้เป็น null ทั้งหมด
 */
@Entity
@Table(name = "vault_status",
    uniqueConstraints = @UniqueConstraint(name = "uk_vault_status_vault", columnNames = "vault_id"))
public class VaultStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    /** online — ค่าที่ตู้รายงานมาเอง ไม่ใช่สิ่งที่เราสรุปเอง */
    @Column(name = "state")
    private String state;

    /** normal / register / lockdown */
    @Column(name = "mode")
    private String mode;

    @Column(name = "total_slots")
    private Integer totalSlots;

    @Column(name = "items_in_locker")
    private Integer itemsInLocker;

    @Column(name = "items_rented_out")
    private Integer itemsRentedOut;

    @Column(name = "available_slots")
    private Integer availableSlots;

    /** เวลาที่ตู้สแกนของล่าสุด — ข้อความดิบจากตู้ รูปแบบอาจเปลี่ยนได้ */
    @Column(name = "last_scan_time")
    private String lastScanTime;

    /** เวลาที่ backend รับ heartbeat นี้ — ใช้ตัดสินว่าตู้ยังติดต่ออยู่ไหม */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Integer getTotalSlots() { return totalSlots; }
    public void setTotalSlots(Integer totalSlots) { this.totalSlots = totalSlots; }

    public Integer getItemsInLocker() { return itemsInLocker; }
    public void setItemsInLocker(Integer itemsInLocker) { this.itemsInLocker = itemsInLocker; }

    public Integer getItemsRentedOut() { return itemsRentedOut; }
    public void setItemsRentedOut(Integer itemsRentedOut) { this.itemsRentedOut = itemsRentedOut; }

    public Integer getAvailableSlots() { return availableSlots; }
    public void setAvailableSlots(Integer availableSlots) { this.availableSlots = availableSlots; }

    public String getLastScanTime() { return lastScanTime; }
    public void setLastScanTime(String lastScanTime) { this.lastScanTime = lastScanTime; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
