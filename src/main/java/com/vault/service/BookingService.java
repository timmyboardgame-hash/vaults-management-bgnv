package com.vault.service;

import com.vault.dto.BookingResponse;
import com.vault.dto.CreateBookingRequest;
import com.vault.dto.IotEventRequest;
import com.vault.entity.Agent;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Item;
import com.vault.entity.VaultItem;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;



@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);

    private final BookingRepository            bookingRepository;
    private final BookingStatusEventRepository bookingStatusEventRepository;
    private final AgentRepository      agentRepository;
    private final VaultItemRepository  vaultItemRepository;
    private final BindingRepository    bindingRepository;
    private final AwsIotService        awsIotService;

    // itemId พิเศษสำหรับ session booking — จองช่วงเวลา หยิบ/เปลี่ยนเกมไหนก็ได้จนหมดเวลา
    // booking ที่ชี้ item เหล่านี้จะไม่จบเมื่อคืนของ — จบเมื่อหมดเวลา (SessionBookingScheduler) หรือ cancel
    private final List<String> sessionItemIds;

    public BookingService(BookingRepository   bookingRepository,
                          BookingStatusEventRepository bookingStatusEventRepository,
                          AgentRepository     agentRepository,
                          VaultItemRepository vaultItemRepository,
                          BindingRepository   bindingRepository,
                          AwsIotService       awsIotService,
                          @org.springframework.beans.factory.annotation.Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.bookingRepository   = bookingRepository;
        this.bookingStatusEventRepository = bookingStatusEventRepository;
        this.agentRepository     = agentRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bindingRepository   = bindingRepository;
        this.awsIotService       = awsIotService;
        this.sessionItemIds      = sessionItemIds;
    }

    /** booking นี้เป็น session booking (จองช่วงเวลา) หรือไม่ — ดูจาก itemId ที่ config ไว้ */
    public boolean isSessionBooking(Booking booking) {
        return booking.getItem() != null && sessionItemIds.contains(booking.getItem().getItemId());
    }

    /** บันทึก timeline event ของการเปลี่ยนสถานะ booking — ใช้สำหรับ booking-monitor detail page */
    private void recordStatusEvent(Booking booking, String status, String note) {
        BookingStatusEvent event = new BookingStatusEvent();
        event.setBooking(booking);
        event.setStatus(status);
        event.setNote(note);
        bookingStatusEventRepository.save(event);
    }

    public List<BookingResponse> getAllBookings() {
        return bookingRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .stream().map(this::toResponse).toList();
    }

    public List<BookingResponse> getBookingsByAgent(String agentId) {
        return bookingRepository.findByAgentBusinessId(agentId)
            .stream().map(this::toResponse).toList();
    }

    public List<BookingResponse> searchBookings(String search, String status) {
        List<Booking> all = bookingRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        return all.stream().filter(b -> {
            boolean matchStatus = (status == null || status.isBlank()) || b.getBookingStatus().equals(status);
            if (!matchStatus) return false;
            if (search == null || search.isBlank()) return true;
            String q = search.toLowerCase();
            return b.getBookingId().toLowerCase().contains(q) ||
                   (b.getAgent() != null && b.getAgent().getAgentName() != null &&
                    b.getAgent().getAgentName().toLowerCase().contains(q));
        }).map(this::toResponse).toList();
    }

    public Optional<BookingResponse> getBooking(String bookingId) {
        return bookingRepository.findByBookingIdAndDeletedAtIsNull(bookingId)
            .map(this::toResponse);
    }

    @Transactional
    public BookingResponse createBooking(String agentId, CreateBookingRequest req) {
        log.info("[BOOKING] Creating booking={} agent={}", req.bookingId(), agentId);
        if (bookingRepository.existsByBookingIdAndDeletedAtIsNull(req.bookingId())) {
            throw new IllegalArgumentException("Booking ID already exists: " + req.bookingId());
        }

        Agent agent = agentRepository.findByAgentIdAndDeletedAtIsNull(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        // serial คือ identity ของกล่อง — resolve ด้วย serial เป็นหลัก
        // itemId เป็น soft check: ถ้าไม่ตรงกับ item จริงของกล่อง แค่ log warning แล้วใช้ item จริงแทน
        String serial = req.serialNumber().trim();
        VaultItem vaultItem = vaultItemRepository.findActiveBySerial(serial)
            .orElseThrow(() -> new IllegalArgumentException(
                "Item copy not found in any vault: serial=" + serial));

        Item item = vaultItem.getItem();
        if (!item.getItemId().equals(req.itemId())) {
            log.warn("[BOOKING] itemId mismatch booking={} requested={} actual={} serial={} — using copy's item",
                req.bookingId(), req.itemId(), item.getItemId(), serial);
        }

        // กัน double-booking กล่องเดียวกัน
        if (bookingRepository.existsActiveByItemAndSerial(item.getId(), serial)) {
            throw new IllegalArgumentException("Copy already booked: serial=" + serial);
        }

        String vaultId = vaultItem.getVault().getVaultId();
        bindingRepository.findActiveByAgentAndVault(agentId, vaultId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Agent " + agentId + " is not bound to vault " + vaultId));

        // PIN priority: request → item defaultPin → random
        String pin = req.pin() != null && !req.pin().isBlank()
                ? req.pin()
                : (item.getDefaultPin() != null ? item.getDefaultPin() : generatePin());
        String requestId = UUID.randomUUID().toString();

        // แปลง OffsetDateTime → LocalDateTime (เก็บเป็น Bangkok time ใน DB)
        LocalDateTime timeStart = req.bookingTimeStart().atZoneSameInstant(BANGKOK).toLocalDateTime();
        LocalDateTime timeEnd   = req.bookingTimeEnd().atZoneSameInstant(BANGKOK).toLocalDateTime();

        // Publish MQTT command ไปยัง kiosk (fire-and-forget)
        // Device ตอบกลับผ่าน events/booking/created → Lambda → PATCH /api/v1/bookings/{id}/iot-event
        awsIotService.publishBookingCreate(
            vaultId,
            req.bookingId(),
            requestId,
            pin,
            vaultItem.getRfidTag(),
            item.getItemId(),
            item.getItemNameEn(),
            serial,
            timeStart,
            timeEnd,
            sessionItemIds.contains(item.getItemId())  // session → tags:[] + booking_mode=session
        );

        Booking booking = new Booking();
        booking.setBookingId(req.bookingId());
        booking.setAgent(agent);
        booking.setItem(item);
        booking.setVault(vaultItem.getVault());
        booking.setSlotId(vaultItem.getSlotId());
        booking.setRequestId(requestId);
        booking.setBookingStatus("PENDING");
        booking.setBookingTimeStart(timeStart);
        booking.setBookingTimeEnd(timeEnd);
        booking.setBookingName(req.bookingName());
        // bookingDate: ใช้ค่าที่ส่งมา หรือ derive จาก bookingTimeStart ถ้าไม่ได้ส่ง
        booking.setBookingDate(req.bookingDate() != null && !req.bookingDate().isBlank()
                ? req.bookingDate()
                : timeStart.toLocalDate().toString());
        booking.setPin(pin);
        booking.setSerialNumber(serial);

        Booking saved = bookingRepository.saveAndFlush(booking);
        log.info("[BOOKING] Created booking={} vault={} slot={} status=PENDING", saved.getBookingId(), vaultId, saved.getSlotId());
        recordStatusEvent(saved, "PENDING", pin != null ? "PIN: " + pin : null);
        // Reload เพื่อให้ได้ @CreationTimestamp ที่ Hibernate set ใน DB
        return bookingRepository.findById(saved.getId())
            .map(this::toResponse)
            .orElse(toResponse(saved));
    }

    @Transactional
    public BookingResponse extendBooking(String bookingId, java.time.OffsetDateTime validUntil) {
        log.info("[BOOKING] Extending booking={} until={}", bookingId, validUntil);
        Booking booking = bookingRepository.findByBookingIdAndDeletedAtIsNull(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        String status = booking.getBookingStatus();
        if ("RETURNED".equals(status) || "CANCELLED".equals(status) || "FAILED".equals(status)) {
            throw new IllegalArgumentException("Cannot extend booking in status: " + status);
        }

        LocalDateTime newEnd = validUntil.atZoneSameInstant(BANGKOK).toLocalDateTime();
        booking.setBookingTimeEnd(newEnd);
        Booking saved = bookingRepository.save(booking);

        String requestId = UUID.randomUUID().toString();
        if (booking.getVault() != null) {
            awsIotService.publishBookingExtend(
                booking.getVault().getVaultId(), bookingId, requestId, newEnd);
        } else {
            log.warn("[BOOKING] No vault on booking={} — skipping MQTT extend", bookingId);
        }

        recordStatusEvent(saved, status, "Extended until: " + validUntil);
        log.info("[BOOKING] Extended booking={} until={}", bookingId, newEnd);
        return toResponse(saved);
    }

    @Transactional
    public BookingResponse cancelBooking(String bookingId) {
        log.info("[BOOKING] Cancelling booking={}", bookingId);
        Booking booking = bookingRepository.findByBookingIdAndDeletedAtIsNull(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Publish MQTT cancel ไปยัง kiosk — DB update ก่อนเสมอ ไม่รอ device ตอบ
        String requestId = UUID.randomUUID().toString();
        boolean session = isSessionBooking(booking);
        if (booking.getVault() != null) {
            if (session) {
                // Event booking ใช้ cmd/booking/end ตาม device contract —
                // device จะรับคืนของที่ค้างอยู่ต่อได้ (closing flag) แล้วค่อย archive
                awsIotService.publishBookingEnd(
                    booking.getVault().getVaultId(), bookingId, requestId);
            } else {
                awsIotService.publishBookingCancel(
                    booking.getVault().getVaultId(), bookingId, requestId);
            }
        } else {
            log.warn("[BOOKING] No vault on booking={} — skipping MQTT cancel", bookingId);
        }

        booking.setBookingStatus("CANCELLED");
        // session: ไม่ soft-delete — เก็บ record ไว้ให้ event คืนของที่ตามมา (LATE_EVENT) ยัง trace ได้
        if (!session) {
            booking.setDeletedAt(LocalDateTime.now());
        }
        Booking saved = bookingRepository.save(booking);
        recordStatusEvent(saved, "CANCELLED", session ? "Session ended by admin (booking/end sent)" : null);
        log.info("[BOOKING] Cancelled booking={} session={}", bookingId, session);
        return toResponse(saved);
    }

    /**
     * Lambda callback — อัปเดต booking status จาก MQTT event ที่ device ส่งมา
     * เรียกจาก PATCH /api/v1/bookings/{id}/iot-event
     */
    @Transactional
    public void handleIotEvent(String bookingId, IotEventRequest req) {
        log.info("[IoT-EVENT] booking={} event={} result={}", bookingId, req.event(), req.result());
        Booking booking = bookingRepository.findByBookingIdAndDeletedAtIsNull(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if ("booking_anomaly".equals(req.event())) {
            String anomalyType = req.anomalyType() != null ? req.anomalyType() : "unknown";
            String level = "lockdown_triggered".equals(anomalyType) ? "CRITICAL" : "WARNING";
            log.warn("[IoT-EVENT] ANOMALY booking={} type={} level={}", bookingId, anomalyType, level);
            recordStatusEvent(booking, "ANOMALY:" + anomalyType, req.error());
            return;
        }

        // booking ที่จบแล้ว (RETURNED/CANCELLED/FAILED) ห้ามถูก event ที่มาช้าเปลี่ยน status ย้อน
        String current = booking.getBookingStatus();
        if ("RETURNED".equals(current) || "CANCELLED".equals(current) || "FAILED".equals(current)) {
            log.warn("[IoT-EVENT] booking={} already terminal ({}) — ignoring late event {}",
                bookingId, current, req.event());
            recordStatusEvent(booking, "LATE_EVENT:" + req.event(), "ignored — booking already " + current);
            return;
        }

        // Session booking: หยิบ/คืนไม่จบ booking — บันทึกเป็น movement ใน timeline แล้วคง status
        // จบเมื่อหมดเวลา+ของครบ (scheduler / คืนชิ้นสุดท้ายตอน OVERDUE) หรือ cancel
        if (isSessionBooking(booking)
                && ("booking_picked_up".equals(req.event()) || "booking_returned".equals(req.event()))) {
            String action = "booking_picked_up".equals(req.event()) ? "PICKED_UP" : "RETURNED";
            recordStatusEvent(booking, "MOVE:" + action, describeCopy(req.epc()));
            log.info("[IoT-EVENT] SESSION movement booking={} action={} epc={}", bookingId, action, req.epc());

            // tap แรกเปลี่ยน CONFIRMED → ACTIVE เพื่อให้ monitor เห็นว่า session เริ่มใช้งานแล้ว
            if ("PICKED_UP".equals(action) && "CONFIRMED".equals(booking.getBookingStatus())) {
                booking.setBookingStatus("ACTIVE");
                recordStatusEvent(bookingRepository.save(booking), "ACTIVE", "Session started");
            }

            // OVERDUE (หมดเวลาแล้วของค้าง) + คืนชิ้นสุดท้าย → ปิดทันที ไม่ต้องรอ scheduler
            if ("RETURNED".equals(action) && "OVERDUE".equals(booking.getBookingStatus())) {
                java.util.Set<String> outstanding = outstandingEpcs(
                    bookingStatusEventRepository.findByBookingIdOrderByOccurredAtAsc(booking.getId()));
                if (outstanding.isEmpty()) {
                    booking.setBookingStatus("RETURNED");
                    recordStatusEvent(bookingRepository.save(booking), "RETURNED",
                        "All items returned (after time end)");
                    log.info("[SESSION] Overdue booking={} closed — all items returned", bookingId);
                }
            }
            return;
        }

        String newStatus = switch (req.event()) {
            case "booking_created"   -> "success".equals(req.result()) ? "CONFIRMED" : "FAILED";
            case "booking_picked_up" -> "ACTIVE";
            case "booking_returned"  -> "RETURNED";
            case "booking_cancelled" -> "CANCELLED";  // informational — device ack cancel
            case "booking_extended"  -> booking.getBookingStatus(); // informational — DB already updated
            default -> booking.getBookingStatus();
        };

        if (!newStatus.equals(booking.getBookingStatus())) {
            log.info("[IoT-EVENT] booking={} status {} → {}", bookingId, booking.getBookingStatus(), newStatus);
            booking.setBookingStatus(newStatus);
            Booking saved = bookingRepository.save(booking);
            recordStatusEvent(saved, newStatus, req.error());
        }
    }

    /** แปลง epc → คำอธิบายกล่อง "Catan (SN-001) epc=E280..." สำหรับ movement note */
    private String describeCopy(String epc) {
        if (epc == null || epc.isBlank()) return "unknown tag";
        return vaultItemRepository.findActiveByRfidTag(epc)
            .map(vi -> vi.getItem().getItemNameEn() + " (" + vi.getSerialNumber() + ") epc=" + epc)
            .orElse("unregistered tag epc=" + epc);
    }

    /**
     * ปิด session booking ที่เลย bookingTimeEnd — เรียกจาก SessionBookingScheduler
     *
     * ของครบ (ทุกกล่องที่หยิบถูกคืนแล้ว) → ปิดเป็น RETURNED
     * ของไม่ครบ → status = OVERDUE (ยังไม่จบ — event คืนของยังบันทึกเป็น MOVE ได้
     *             และยังบล็อค session ใหม่บนตู้เดิม) เมื่อคืนครบจึงปิดเป็น RETURNED
     *             (ปิดทันทีตอนรับ event คืนชิ้นสุดท้าย หรือรอบ scheduler ถัดไป)
     * @return จำนวน booking ที่ถูกปิด
     */
    @Transactional
    public int closeExpiredSessionBookings() {
        List<Booking> open = bookingRepository.findOpenPastEnd(LocalDateTime.now());
        int closed = 0;
        for (Booking b : open) {
            if (!isSessionBooking(b)) continue;  // booking ปกติเลยเวลา = late return flow เดิม ไม่ยุ่ง

            List<BookingStatusEvent> events =
                bookingStatusEventRepository.findByBookingIdOrderByOccurredAtAsc(b.getId());
            java.util.Set<String> outstanding = outstandingEpcs(events);

            if (outstanding.isEmpty()) {
                b.setBookingStatus("RETURNED");
                Booking saved = bookingRepository.save(b);
                recordStatusEvent(saved, "RETURNED", "Session expired — all items returned");
                log.info("[SESSION] Closed expired session booking={}", b.getBookingId());
                closed++;
            } else if (!"OVERDUE".equals(b.getBookingStatus())) {
                b.setBookingStatus("OVERDUE");
                Booking saved = bookingRepository.save(b);
                recordStatusEvent(saved, "OVERDUE",
                    "หมดเวลาแล้วยังไม่คืน " + outstanding.size() + " กล่อง: " + String.join(", ", outstanding));
                log.warn("[SESSION] Overdue booking={} outstanding={}", b.getBookingId(), outstanding);
            }
        }
        return closed;
    }

    /** หา epc ของกล่องที่หยิบไปแล้วยังไม่คืน จาก movement events ใน timeline */
    private java.util.Set<String> outstandingEpcs(List<BookingStatusEvent> events) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (BookingStatusEvent e : events) {
            String epc = extractEpc(e.getNote());
            if (epc == null) continue;
            if ("MOVE:PICKED_UP".equals(e.getStatus()))      out.add(epc);
            else if ("MOVE:RETURNED".equals(e.getStatus()))  out.remove(epc);
        }
        return out;
    }

    /** ดึงค่า epc จาก note รูปแบบ "... epc=XXXX" ที่ describeCopy() สร้าง */
    private String extractEpc(String note) {
        if (note == null) return null;
        int i = note.lastIndexOf("epc=");
        return i < 0 ? null : note.substring(i + 4).trim();
    }

    private String generatePin() {
        return String.valueOf(100000 + new SecureRandom().nextInt(900000));
    }

    private BookingResponse toResponse(Booking b) {
        return new BookingResponse(
            b.getId(),
            b.getBookingId(),
            b.getAgent().getAgentId(),
            b.getAgent().getAgentName(),
            b.getItem().getItemId(),
            b.getItem().getItemNameEn(),
            b.getItem().getItemNameTh(),
            b.getSlotId(),
            b.getVault() != null ? b.getVault().getVaultId() : null,
            b.getBookingStatus(),
            toOffset(b.getBookingTimeStart()),
            toOffset(b.getBookingTimeEnd()),
            b.getBookingName(),
            b.getBookingDate(),
            b.getPin(),
            b.getSerialNumber(),
            toOffset(b.getCreatedAt())
        );
    }

    /** LocalDateTime (ถือว่าเป็น Bangkok time) → OffsetDateTime +07:00 */
    private java.time.OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK_OFFSET) : null;
    }
}
