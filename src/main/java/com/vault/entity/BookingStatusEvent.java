package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "booking_status_events")
public class BookingStatusEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // booking_id FK → bookings.id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "status", nullable = false)
    private String status;  // PENDING / CONFIRMED / ACTIVE / RETURNED / CANCELLED / FAILED

    @Column(name = "note")
    private String note;  // เช่น "PIN: 1234" — context เพิ่มเติม (optional)

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    public BookingStatusEvent() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
}
