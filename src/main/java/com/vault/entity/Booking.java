package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "booking_id", nullable = false)
    private String bookingId;

    // agent_id FK → agents.id
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    // item_id FK → items.id
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "slot_id")
    private String slotId;  // format: "sl-0001", resolved from VaultItem

    // vault FK → vaults.id — ใช้ vault.vaultId เป็น thingName สำหรับ MQTT
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vault_id")
    private Vault vault;

    @Column(name = "request_id")
    private String requestId;  // UUID ส่งใน MQTT envelope — kiosk dedup ด้วย field นี้

    @Column(name = "booking_status", nullable = false)
    private String bookingStatus;  // PENDING / CONFIRMED / ACTIVE / RETURNED / CANCELLED / FAILED

    @Column(name = "booking_time_start", nullable = false)
    private LocalDateTime bookingTimeStart;

    @Column(name = "booking_time_end", nullable = false)
    private LocalDateTime bookingTimeEnd;

    @Column(name = "booking_name")
    private String bookingName;

    @Column(name = "booking_date")
    private String bookingDate;

    @Column(name = "pin")
    private String pin;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Booking() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }

    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }

    public Vault getVault() { return vault; }
    public void setVault(Vault vault) { this.vault = vault; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }

    public LocalDateTime getBookingTimeStart() { return bookingTimeStart; }
    public void setBookingTimeStart(LocalDateTime bookingTimeStart) { this.bookingTimeStart = bookingTimeStart; }

    public LocalDateTime getBookingTimeEnd() { return bookingTimeEnd; }
    public void setBookingTimeEnd(LocalDateTime bookingTimeEnd) { this.bookingTimeEnd = bookingTimeEnd; }

    public String getBookingName() { return bookingName; }
    public void setBookingName(String bookingName) { this.bookingName = bookingName; }

    public String getBookingDate() { return bookingDate; }
    public void setBookingDate(String bookingDate) { this.bookingDate = bookingDate; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
