package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "vault_items", indexes = {
    // สอง lookup นี้วิ่งทุกครั้งที่ลงทะเบียนกล่องและทุกครั้งที่สร้าง booking
    // ไม่ใช่ unique constraint เพราะความซ้ำต้องตรวจแบบรู้จัก soft delete — แถวที่ลบแล้วใช้เลขเดิมซ้ำได้
    @Index(name = "idx_vault_items_serial", columnList = "serial_number"),
    @Index(name = "idx_vault_items_rfid_tag", columnList = "rfid_tag")
})
public class VaultItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * ที่อยู่ของกล่อง — ตู้ที่กล่องนี้ถูกวางไว้
     *
     * null ได้ตั้งแต่มี API ลงทะเบียนจากแอปร้าน เพราะร้านลงทะเบียนตอนกล่องยังอยู่ในมือ
     * ยังไม่ได้เอาเข้าตู้ · กล่องที่ยังไม่มีตู้จะจองไม่ได้จนกว่าจะมีคนระบุตู้ให้
     *
     * query ที่ดึง copy ต้องใช้ LEFT JOIN กับ vault เสมอ ไม่งั้นกล่องกลุ่มนี้หายเงียบ ๆ
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id")
    private Vault vault;

    /**
     * เจ้าของกล่อง — ร้านที่ลงทะเบียนกล่องนี้เข้ามา
     *
     * คนละเรื่องกับตู้: เจ้าของแทบไม่เปลี่ยน ส่วนตู้ย้ายได้เรื่อย ๆ และว่างได้
     * null = กล่องที่ผูกจากหน้าหลังบ้านก่อนจะมี API ลงทะเบียน — เจ้าของอนุมานจากตู้เหมือนเดิม
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_agent_id")
    private Agent ownerAgent;

    // item_id FK → items.id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "slot_id")
    private String slotId;  // nullable — ไม่ได้กรอกตอน bind แล้ว

    @Column(name = "rfid_tag")
    private String rfidTag;  // RFID tag ID — รับทุกรูปแบบ · ส่งใน MQTT cmd/booking/create tags:[rfidTag]

    /**
     * แท็กเก็บเป็นตัวพิมพ์ใหญ่เสมอ — ทุกทางเทียบแบบ exact match ไม่ใช่ ignore case
     *
     * ที่เทียบตรง ๆ มีสี่ทาง: รายการที่ส่งเข้าตู้ (inventory/sync), tags ใน booking/create,
     * lookup ขาเข้าตอนตู้แจ้ง epc มา (findActiveByRfidTag) และตัวกันแท็กซ้ำ (existsByRfidTag)
     * ถ้าเก็บคนละเคสกับที่ส่ง ตู้จะหาไม่เจอ → ลูกค้าแตะแล้วนับเป็นกล่องผิด สามครั้งตู้ lockdown
     * และตัวกันซ้ำจะมองแท็กเดียวกันคนละเคสเป็นสองแท็ก
     */
    public static String normaliseTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        return t.isEmpty() ? null : t.toUpperCase();
    }

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

    public Agent getOwnerAgent() { return ownerAgent; }
    public void setOwnerAgent(Agent ownerAgent) { this.ownerAgent = ownerAgent; }

    /** กล่องอยู่ในตู้แล้วหรือยัง — ใช้ตัดสินว่าจองได้ไหม */
    public boolean isInVault() { return vault != null; }

    /** รหัสร้านเจ้าของ · กล่องเก่าที่ยังไม่มีเจ้าของจะคืน null */
    public String ownerAgentId() { return ownerAgent != null ? ownerAgent.getAgentId() : null; }

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
