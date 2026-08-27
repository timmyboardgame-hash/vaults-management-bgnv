package com.vault.service;

import com.vault.dto.VaultBoardDto.*;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vault Board — รวม booking ทุกใบของตู้ + สถานะกล่องในตู้ ไว้ในหน้าเดียว
 *
 * ทุกค่าคำนวณจาก booking_status_events ที่ระบบบันทึกอยู่แล้ว:
 *   event booking → จับคู่ MOVE:PICKED_UP กับ MOVE:RETURNED ที่ epc เดียวกัน = 1 รอบ
 *   game booking  → ACTIVE = หยิบออก, RETURNED = คืน (1 รอบ)
 */
@Service
public class VaultBoardService {

    private static final ZoneOffset BANGKOK = ZoneOffset.ofHours(7);
    private static final Set<String> TERMINAL = Set.of("RETURNED", "CANCELLED", "FAILED");

    private final VaultRepository vaultRepository;
    private final VaultItemRepository vaultItemRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusEventRepository eventRepository;
    private final List<String> sessionItemIds;

    public VaultBoardService(VaultRepository vaultRepository,
                             VaultItemRepository vaultItemRepository,
                             BookingRepository bookingRepository,
                             BookingStatusEventRepository eventRepository,
                             @Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.vaultRepository = vaultRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.sessionItemIds = sessionItemIds;
    }

    public Optional<Board> getBoard(String vaultId) {
        return vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId).map(this::buildBoard);
    }

    private Board buildBoard(Vault vault) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = LocalDate.now().atStartOfDay();   // "จบแล้ววันนี้"

        List<BoardBooking> all = bookingRepository.findBoardBookings(vault.getVaultId(), since)
                .stream().map(b -> toBoardBooking(b, now)).toList();

        List<BoardBooking> active = all.stream().filter(b -> !b.done()).toList();
        List<BoardBooking> done   = all.stream().filter(BoardBooking::done).toList();

        // กล่องที่ยังถืออยู่ / ถูกจองไว้ → จับคู่ด้วย matchKey (epc ถ้ามี ไม่งั้น serial)
        Map<String, String> heldByKey = new LinkedHashMap<>();
        Map<String, String> reservedByKey = new LinkedHashMap<>();
        for (BoardBooking b : active) {
            for (Cycle c : b.cycles()) {
                if (c.matchKey() == null) continue;
                if (c.pending()) reservedByKey.put(c.matchKey(), b.bookingId());
                else if (c.returnedAt() == null) heldByKey.put(c.matchKey(), b.bookingId());
            }
        }

        List<BoardItem> items = buildItems(vault, active, heldByKey, reservedByKey, now);

        List<BoardItem> realItems = items.stream().filter(i -> !i.session()).toList();
        int itemsOut = (int) realItems.stream().filter(BoardItem::out).count();
        int itemsTotal = realItems.size();

        int openEvents = (int) active.stream().filter(b -> "event".equals(b.type())).count();

        // ต้องดูด่วน: booking ที่ OVERDUE หรือมีกล่องเกินเวลา
        List<BoardBooking> attention = active.stream()
                .filter(b -> "OVERDUE".equals(b.status()) || b.cycles().stream().anyMatch(Cycle::late))
                .toList();
        String attentionNote = attention.isEmpty() ? "ไม่มีรายการค้าง"
                : attention.stream()
                    .flatMap(b -> b.cycles().stream().filter(Cycle::late))
                    .findFirst()
                    .map(c -> c.itemName() + " เกินกำหนด")
                    .orElse(attention.get(0).bookingId() + " เลยเวลา");

        long avgToday = done.stream().mapToLong(BoardBooking::totalMinutes).filter(v -> v > 0).average()
                .stream().mapToLong(Math::round).findFirst().orElse(0);

        return new Board(
                vault.getVaultId(),
                vault.getVaultName(),
                "ENABLE".equals(vault.getVaultStatus()),
                active.size(),
                openEvents,
                active.size() - openEvents,
                attention.size(),
                attentionNote,
                itemsOut,
                itemsTotal,
                itemsTotal == 0 ? 0 : (int) Math.round(itemsOut * 100.0 / itemsTotal),
                done.size(),
                avgToday,
                active,
                done,
                items
        );
    }

    /**
     * ประวัติของกล่อง 1 ใบ — รวมทุก booking ที่เคยยืมกล่องนี้
     *
     * สำคัญ: กล่องถูกยืมได้ 2 ทาง จึงต้องหาจากทั้งสองแหล่ง
     *   game booking  → booking.item ชี้กล่องนี้ตรงๆ
     *   event booking → booking.item เป็น session pass แต่มี MOVE event ที่ epc/serial ตรงกล่องนี้
     * (ของเดิมดูแค่ booking.item จึงไม่เห็นการยืมผ่าน event booking เลย)
     */
    public Optional<ItemHistory> getItemHistory(String vaultId, String itemId, int daysBack) {
        Optional<Vault> vaultOpt = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId);
        if (vaultOpt.isEmpty()) return Optional.empty();
        Vault vault = vaultOpt.get();

        VaultItem vi = vaultItemRepository.findByVaultId(vaultId).stream()
                .filter(x -> x.getItem().getItemId().equals(itemId))
                .findFirst().orElse(null);
        if (vi == null) return Optional.empty();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = LocalDate.now().minusDays(daysBack).atStartOfDay();

        List<BoardBooking> bookings = bookingRepository.findBoardBookings(vaultId, since)
                .stream().map(b -> toBoardBooking(b, now)).toList();

        // เก็บทุก cycle ที่อ้างถึงกล่องนี้ (จับคู่ด้วย epc ก่อน แล้ว serial)
        List<Borrow> borrows = new ArrayList<>();
        List<TimelineEvent> timeline = new ArrayList<>();
        for (BoardBooking b : bookings) {
            boolean ownsItem = false;
            for (Cycle c : b.cycles()) {
                if (c.pending()) continue;
                boolean isThisItem = c.matchKey() != null
                        && (c.matchKey().equals(vi.getRfidTag()) || c.matchKey().equals(vi.getSerialNumber()));
                if (!isThisItem) continue;
                ownsItem = true;
                borrows.add(new Borrow(b.bookingId(), b.type(), b.agentName(),
                        c.pickedUpAt(), c.returnedAt(), c.minutes(), c.late()));
            }
            if (!ownsItem) continue;

            // event booking แตะหลายกล่อง → เอาเฉพาะบรรทัดที่อ้างกล่องนี้
            // game booking ทั้งใบเป็นของกล่องนี้อยู่แล้ว → เอาทั้งหมด
            for (TimelineEvent e : b.timeline()) {
                boolean mentionsItem = e.note() != null
                        && ((vi.getRfidTag() != null && e.note().contains(vi.getRfidTag()))
                         || (vi.getSerialNumber() != null && e.note().contains(vi.getSerialNumber())));
                if ("game".equals(b.type()) || mentionsItem) {
                    timeline.add(new TimelineEvent(
                            b.bookingId() + " · " + e.status(), e.occurredAt(), e.note()));
                }
            }
        }
        timeline.sort((x, y) -> {
            if (x.occurredAt() == null || y.occurredAt() == null) return 0;
            return x.occurredAt().compareTo(y.occurredAt());
        });
        borrows.sort((x, y) -> {
            if (x.pickedUpAt() == null || y.pickedUpAt() == null) return 0;
            return y.pickedUpAt().compareTo(x.pickedUpAt());   // ล่าสุดขึ้นก่อน
        });

        Borrow open = borrows.stream().filter(x -> x.returnedAt() == null).findFirst().orElse(null);
        long total = borrows.stream().mapToLong(Borrow::minutes).sum();
        int lateCount = (int) borrows.stream().filter(Borrow::late).count();

        return Optional.of(new ItemHistory(
                vaultId,
                vault.getVaultName(),
                itemId,
                vi.getItem().getItemNameEn(),
                vi.getSerialNumber(),
                vi.getRfidTag(),
                open != null,
                open != null ? open.bookingId() : null,
                open != null ? open.minutes() : 0,
                borrows.size(),
                total,
                borrows.isEmpty() ? 0 : Math.round((double) total / borrows.size()),
                lateCount,
                borrows,
                timeline
        ));
    }

    // ── booking → board row (พร้อม cycles + timeline) ──────────────────────────
    private BoardBooking toBoardBooking(Booking b, LocalDateTime now) {
        boolean isEvent = b.getItem() != null && sessionItemIds.contains(b.getItem().getItemId());
        List<BookingStatusEvent> events = eventRepository.findByBookingIdOrderByOccurredAtAsc(b.getId());

        List<Cycle> cycles = isEvent
                ? eventCycles(events, b, now)
                : gameCycles(events, b, now);

        long total = cycles.stream().filter(c -> !c.pending()).mapToLong(Cycle::minutes).sum();
        int holding = (int) cycles.stream().filter(c -> c.returnedAt() == null && !c.pending()).count();
        long elapsed = cycles.stream().filter(c -> c.returnedAt() == null && !c.pending())
                .mapToLong(Cycle::minutes).max().orElse(0);

        return new BoardBooking(
                b.getBookingId(),
                isEvent ? "event" : "game",
                b.getBookingStatus(),
                b.getAgent() != null ? b.getAgent().getAgentName() : "—",
                toOffset(b.getBookingTimeStart()),
                toOffset(b.getBookingTimeEnd()),
                b.getPin(),
                TERMINAL.contains(b.getBookingStatus()),
                b.getBookingTimeEnd() == null ? 0 : Duration.between(now, b.getBookingTimeEnd()).toMinutes(),
                elapsed,
                (int) cycles.stream().filter(c -> !c.pending()).count(),
                holding,
                total,
                cycles,
                events.stream()
                        .map(e -> new TimelineEvent(e.getStatus(), toOffset(e.getOccurredAt()), e.getNote()))
                        .toList()
        );
    }

    /** event booking — จับคู่ MOVE:PICKED_UP / MOVE:RETURNED ที่ epc เดียวกันเป็นรอบ */
    private List<Cycle> eventCycles(List<BookingStatusEvent> events, Booking b, LocalDateTime now) {
        Map<String, BookingStatusEvent> openByEpc = new LinkedHashMap<>();
        List<Cycle> out = new ArrayList<>();

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
    private List<Cycle> gameCycles(List<BookingStatusEvent> events, Booking b, LocalDateTime now) {
        LocalDateTime pickedUp = events.stream().filter(e -> "ACTIVE".equals(e.getStatus()))
                .map(BookingStatusEvent::getOccurredAt).findFirst().orElse(null);
        LocalDateTime returned = events.stream().filter(e -> "RETURNED".equals(e.getStatus()))
                .map(BookingStatusEvent::getOccurredAt).findFirst().orElse(null);

        String name = b.getItem() != null ? b.getItem().getItemNameEn() : "—";
        String serial = b.getSerialNumber();

        if (pickedUp == null) {
            // ยังไม่ถูกหยิบ (PENDING / CONFIRMED / CANCELLED ก่อนรับ)
            return List.of(new Cycle(name, serial, serial, null, null, 0, false, true));
        }
        long mins = Duration.between(pickedUp, returned != null ? returned : now).toMinutes();
        boolean late = b.getBookingTimeEnd() != null
                && (returned != null ? returned : now).isAfter(b.getBookingTimeEnd());
        return List.of(new Cycle(name, serial, serial, toOffset(pickedUp), toOffset(returned), Math.max(0, mins), late, false));
    }

    private Cycle cycle(LocalDateTime pickedUp, LocalDateTime returned,
                        String note, String epc, Booking b, LocalDateTime now) {
        VaultItem vi = epc == null ? null
                : vaultItemRepository.findActiveByRfidTag(epc).orElse(null);
        String name   = vi != null ? vi.getItem().getItemNameEn() : itemNameFromNote(note);
        String serial = vi != null ? vi.getSerialNumber() : null;

        long mins = pickedUp == null ? 0
                : Duration.between(pickedUp, returned != null ? returned : now).toMinutes();
        boolean late = b.getBookingTimeEnd() != null
                && (returned != null ? returned : now).isAfter(b.getBookingTimeEnd());

        return new Cycle(name, serial, epc != null ? epc : serial,
                toOffset(pickedUp), toOffset(returned), Math.max(0, mins), late, false);
    }

    // ── กล่องในตู้ ─────────────────────────────────────────────────────────────
    private List<BoardItem> buildItems(Vault vault, List<BoardBooking> active,
                                       Map<String, String> heldByKey,
                                       Map<String, String> reservedByKey,
                                       LocalDateTime now) {
        List<BoardItem> items = new ArrayList<>();

        for (VaultItem vi : vaultItemRepository.findByVaultId(vault.getVaultId())) {
            boolean isSession = sessionItemIds.contains(vi.getItem().getItemId());
            String serial = vi.getSerialNumber();
            // กล่องเดียวกันอาจถูกอ้างด้วย epc (จาก MOVE event) หรือ serial (จาก game booking)
            String heldBy = firstNonNull(lookup(heldByKey, vi.getRfidTag()), lookup(heldByKey, serial));
            String reservedBy = firstNonNull(lookup(reservedByKey, vi.getRfidTag()), lookup(reservedByKey, serial));

            long minutesOut = 0, limit = 0;
            boolean late = false;
            if (heldBy != null) {
                BoardBooking owner = active.stream()
                        .filter(b -> b.bookingId().equals(heldBy)).findFirst().orElse(null);
                if (owner != null) {
                    Cycle c = owner.cycles().stream()
                            .filter(x -> x.returnedAt() == null && !x.pending() && x.matchKey() != null
                                    && (x.matchKey().equals(vi.getRfidTag()) || x.matchKey().equals(serial)))
                            .findFirst().orElse(null);
                    if (c != null) { minutesOut = c.minutes(); late = c.late(); }
                    if (owner.timeStart() != null && owner.timeEnd() != null) {
                        limit = Duration.between(owner.timeStart(), owner.timeEnd()).toMinutes();
                    }
                }
            }

            items.add(new BoardItem(
                    vi.getItem().getItemId(),
                    vi.getItem().getItemNameEn(),
                    serial,
                    heldBy != null,
                    late,
                    minutesOut,
                    limit,
                    heldBy,
                    reservedBy,
                    isSession
            ));
        }

        // ออกไปนานสุดขึ้นก่อน — สิ่งที่ต้องสนใจอยู่บนสุด, session pass ไว้ท้าย
        items.sort((a, b) -> {
            if (a.session() != b.session()) return a.session() ? 1 : -1;
            return Long.compare(b.out() ? b.minutesOut() : -1, a.out() ? a.minutesOut() : -1);
        });
        return items;
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String lookup(Map<String, String> m, String k) { return k == null ? null : m.get(k); }
    private static String firstNonNull(String a, String b) { return a != null ? a : b; }

    /** ดึง epc จาก note รูปแบบ "ชื่อเกม (serial) epc=XXXX" ที่ BookingService สร้าง */
    static String extractEpc(String note) {
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

    private OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK) : null;
    }
}
