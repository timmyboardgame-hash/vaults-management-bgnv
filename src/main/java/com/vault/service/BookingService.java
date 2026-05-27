package com.vault.service;

import com.vault.dto.BookingResponse;
import com.vault.dto.CreateBookingRequest;
import com.vault.dto.IotEventRequest;
import com.vault.entity.Agent;
import com.vault.entity.Booking;
import com.vault.entity.Item;
import com.vault.entity.VaultItem;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.ItemRepository;
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

    private final BookingRepository    bookingRepository;
    private final AgentRepository      agentRepository;
    private final ItemRepository       itemRepository;
    private final VaultItemRepository  vaultItemRepository;
    private final BindingRepository    bindingRepository;
    private final AwsIotService        awsIotService;

    public BookingService(BookingRepository   bookingRepository,
                          AgentRepository     agentRepository,
                          ItemRepository      itemRepository,
                          VaultItemRepository vaultItemRepository,
                          BindingRepository   bindingRepository,
                          AwsIotService       awsIotService) {
        this.bookingRepository   = bookingRepository;
        this.agentRepository     = agentRepository;
        this.itemRepository      = itemRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bindingRepository   = bindingRepository;
        this.awsIotService       = awsIotService;
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

        Item item = itemRepository.findByItemIdAndDeletedAtIsNull(req.itemId())
            .orElseThrow(() -> new IllegalArgumentException("Item not found: " + req.itemId()));

        VaultItem vaultItem = vaultItemRepository.findActiveByItemId(item.getId())
            .orElseThrow(() -> new IllegalArgumentException("Item not bound to any vault: " + req.itemId()));

        String vaultId = vaultItem.getVault().getVaultId();
        bindingRepository.findActiveByAgentAndVault(agentId, vaultId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Agent " + agentId + " is not bound to vault " + vaultId));

        String pin       = req.pin() != null ? req.pin() : generatePin();
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
            timeStart,
            timeEnd
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
        booking.setBookingDate(req.bookingDate());
        booking.setPin(pin);

        Booking saved = bookingRepository.saveAndFlush(booking);
        log.info("[BOOKING] Created booking={} vault={} slot={} status=PENDING", saved.getBookingId(), vaultId, saved.getSlotId());
        // Reload เพื่อให้ได้ @CreationTimestamp ที่ Hibernate set ใน DB
        return bookingRepository.findById(saved.getId())
            .map(this::toResponse)
            .orElse(toResponse(saved));
    }

    @Transactional
    public BookingResponse cancelBooking(String bookingId) {
        log.info("[BOOKING] Cancelling booking={}", bookingId);
        Booking booking = bookingRepository.findByBookingIdAndDeletedAtIsNull(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        // Publish MQTT cancel ไปยัง kiosk — DB update ก่อนเสมอ ไม่รอ device ตอบ
        String requestId = UUID.randomUUID().toString();
        if (booking.getVault() != null) {
            awsIotService.publishBookingCancel(
                booking.getVault().getVaultId(), bookingId, requestId);
        } else {
            log.warn("[BOOKING] No vault on booking={} — skipping MQTT cancel", bookingId);
        }

        booking.setBookingStatus("CANCELLED");
        booking.setDeletedAt(LocalDateTime.now());
        log.info("[BOOKING] Cancelled booking={}", bookingId);
        return toResponse(bookingRepository.save(booking));
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

        String newStatus = switch (req.event()) {
            case "booking_created"   -> "success".equals(req.result()) ? "CONFIRMED" : "FAILED";
            case "booking_picked_up" -> "ACTIVE";
            case "booking_returned"  -> "RETURNED";
            case "booking_cancelled" -> "CANCELLED";  // informational — device ack cancel
            default -> booking.getBookingStatus();
        };

        if (!newStatus.equals(booking.getBookingStatus())) {
            log.info("[IoT-EVENT] booking={} status {} → {}", bookingId, booking.getBookingStatus(), newStatus);
            booking.setBookingStatus(newStatus);
            bookingRepository.save(booking);
        }
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
            toOffset(b.getCreatedAt())
        );
    }

    /** LocalDateTime (ถือว่าเป็น Bangkok time) → OffsetDateTime +07:00 */
    private java.time.OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK_OFFSET) : null;
    }
}
