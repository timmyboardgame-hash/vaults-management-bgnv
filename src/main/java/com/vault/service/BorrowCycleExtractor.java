package com.vault.service;

import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Item;
import com.vault.entity.VaultItem;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * แตก booking 1 ใบออกเป็นรอบหยิบ-คืน — ใช้ร่วมกันระหว่างหน้า Vault Board กับงานสรุปสถิติ
 *
 * แยกออกมาเพื่อให้ทั้งสองที่นับรอบด้วย logic เดียวกันเสมอ ถ้าเขียนซ้ำ วันหนึ่งตัวเลขจะไม่ตรงกัน
 *   event booking → จับคู่ MOVE:PICKED_UP กับ MOVE:RETURNED ที่ epc เดียวกัน = 1 รอบ
 *   game booking  → ACTIVE = หยิบออก, RETURNED = คืน (1 รอบ)
 */
@Component
public class BorrowCycleExtractor {

    /** 1 รอบหยิบ-คืน — เวลาเป็น LocalDateTime (เวลาไทย) ผู้เรียกแปลงเป็นรูปแบบที่ต้องการเอง */
    public record RawCycle(
        String itemName,
        String serialNumber,
        String matchKey,             // epc ถ้ามี ไม่งั้น serial — ใช้จับคู่กับกล่องในตู้
        String epc,                  // null สำหรับ game booking
        Item item,                   // กล่องที่ถูกหยิบจริง — null ถ้า tag ไม่ได้ลงทะเบียน
        LocalDateTime pickedUpAt,
        LocalDateTime returnedAt,    // null = ยังไม่คืน
        long minutes,                // นับถึง now ถ้ายังไม่คืน
        boolean late,                // คืน/ค้างเกิน bookingTimeEnd
        boolean pending              // ยังไม่ถูกหยิบเลย
    ) {}

    private final BookingStatusEventRepository eventRepository;
    private final VaultItemRepository vaultItemRepository;
    private final List<String> sessionItemIds;

    public BorrowCycleExtractor(BookingStatusEventRepository eventRepository,
                                VaultItemRepository vaultItemRepository,
                                @Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.eventRepository = eventRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.sessionItemIds = sessionItemIds;
    }

    public boolean isEvent(Booking b) {
        return b.getItem() != null && sessionItemIds.contains(b.getItem().getItemId());
    }

    public List<BookingStatusEvent> events(Booking b) {
        return eventRepository.findByBookingIdOrderByOccurredAtAsc(b.getId());
    }

    public List<RawCycle> cycles(Booking b, List<BookingStatusEvent> events, LocalDateTime now) {
        return isEvent(b) ? eventCycles(events, b, now) : gameCycles(events, b, now);
    }

    /** event booking — จับคู่ MOVE:PICKED_UP / MOVE:RETURNED ที่ epc เดียวกันเป็นรอบ */
    private List<RawCycle> eventCycles(List<BookingStatusEvent> events, Booking b, LocalDateTime now) {
        Map<String, BookingStatusEvent> openByEpc = new LinkedHashMap<>();
        List<RawCycle> out = new ArrayList<>();

        for (BookingStatusEvent e : events) {
            String epc = extractEpc(e.getNote());
            if (epc == null) continue;

            if ("MOVE:PICKED_UP".equals(e.getStatus())) {
                openByEpc.put(epc, e);
            } else if ("MOVE:RETURNED".equals(e.getStatus())) {
                BookingStatusEvent pick = openByEpc.remove(epc);
                out.add(cycle(pick != null ? pick.getOccurredAt() : null, e.getOccurredAt(),
                        e.getNote(), epc, b, now));
            }
        }
        // กล่องที่หยิบแล้วยังไม่คืน
        for (BookingStatusEvent pick : openByEpc.values()) {
            out.add(cycle(pick.getOccurredAt(), null, pick.getNote(), extractEpc(pick.getNote()), b, now));
        }
        out.sort((x, y) -> {
            if (x.pickedUpAt() == null || y.pickedUpAt() == null) return 0;
            return x.pickedUpAt().compareTo(y.pickedUpAt());
        });
        return out;
    }

    /** game booking — ACTIVE = หยิบออก, RETURNED = คืน (กล่องเดียวจบ) */
    private List<RawCycle> gameCycles(List<BookingStatusEvent> events, Booking b, LocalDateTime now) {
        LocalDateTime pickedUp = events.stream().filter(e -> "ACTIVE".equals(e.getStatus()))
                .map(BookingStatusEvent::getOccurredAt).findFirst().orElse(null);
        LocalDateTime returned = events.stream().filter(e -> "RETURNED".equals(e.getStatus()))
                .map(BookingStatusEvent::getOccurredAt).findFirst().orElse(null);

        Item item = b.getItem();
        String name = item != null ? item.getItemNameEn() : "—";
        String serial = b.getSerialNumber();

        if (pickedUp == null) {
            // ยังไม่ถูกหยิบ (PENDING / CONFIRMED / CANCELLED ก่อนรับ)
            return List.of(new RawCycle(name, serial, serial, null, item, null, null, 0, false, true));
        }
        long mins = Duration.between(pickedUp, returned != null ? returned : now).toMinutes();
        boolean late = b.getBookingTimeEnd() != null
                && (returned != null ? returned : now).isAfter(b.getBookingTimeEnd());
        return List.of(new RawCycle(name, serial, serial, null, item, pickedUp, returned,
                Math.max(0, mins), late, false));
    }

    private RawCycle cycle(LocalDateTime pickedUp, LocalDateTime returned,
                           String note, String epc, Booking b, LocalDateTime now) {
        VaultItem vi = epc == null ? null
                : vaultItemRepository.findActiveByRfidTag(epc).orElse(null);
        String name   = vi != null ? vi.getItem().getItemNameEn() : itemNameFromNote(note);
        String serial = vi != null ? vi.getSerialNumber() : null;

        long mins = pickedUp == null ? 0
                : Duration.between(pickedUp, returned != null ? returned : now).toMinutes();
        boolean late = b.getBookingTimeEnd() != null
                && (returned != null ? returned : now).isAfter(b.getBookingTimeEnd());

        return new RawCycle(name, serial, epc != null ? epc : serial, epc,
                vi != null ? vi.getItem() : null,
                pickedUp, returned, Math.max(0, mins), late, false);
    }

    /** ดึง epc จาก note รูปแบบ "ชื่อเกม (serial) epc=XXXX" ที่ BookingService.describeCopy() สร้าง */
    public static String extractEpc(String note) {
        if (note == null) return null;
        int i = note.lastIndexOf("epc=");
        if (i < 0) return null;
        String s = note.substring(i + 4).trim();
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    /** เผื่อ tag ที่ไม่ได้ลงทะเบียน — ใช้ชื่อจาก note เท่าที่มี */
    private static String itemNameFromNote(String note) {
        if (note == null) return "—";
        int i = note.indexOf(" (");
        if (i > 0) return note.substring(0, i);
        int j = note.indexOf(" epc=");
        return j > 0 ? note.substring(0, j) : "ไม่ทราบกล่อง";
    }
}
