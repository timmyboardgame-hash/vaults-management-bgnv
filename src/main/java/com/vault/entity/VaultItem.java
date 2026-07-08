package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "vault_items")
public class VaultItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // vault_id FK → vaults.id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    // item_id FK → items.id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "slot_id")
    private String slotId;  // nullable — ไม่ได้กรอกตอน bind แล้ว

    @Column(name = "rfid_tag")
    private String rfidTag;  // RFID tag ID (24 hex chars) — ส่งใน MQTT cmd/booking/create tags:[rfidTag]

    // เลข barcode กล่อง — 1 row = 1 กล่องจริง; item เดียวกันมีหลายกล่องได้ (ต่าง serial)
    // serial คือ identity ของกล่อง: unique ทั้งระบบ validate ที่ app layer (soft-delete aware)
    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "status", nullable = false)
    private String status;  // ACTIVE / INACTIVE

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public VaultItem() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }

    public String getRfidTag() { return rfidTag; }
    public void setRfidTag(String rfidTag) { this.rfidTag = rfidTag; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
