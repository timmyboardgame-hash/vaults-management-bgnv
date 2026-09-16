package com.vault.service;

import com.vault.dto.VaultBoardDto.*;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.BookingRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import com.vault.service.BorrowCycleExtractor.RawCycle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vault Board — รวม booking ทุกใบของตู้ + สถานะกล่องในตู้ ไว้ในหน้าเดียว
 *
 * ทุกค่าคำนวณจาก booking_status_events ที่ระบบบันทึกอยู่แล้ว
 * การแตก booking เป็นรอบหยิบ-คืนอยู่ที่ BorrowCycleExtractor — ใช้ตัวเดียวกับงานสรุปสถิติ
 */
@Service
public class VaultBoardService {

    private static final ZoneOffset BANGKOK = ZoneOffset.ofHours(7);
    private static final Set<String> TERMINAL = Set.of("RETURNED", "CANCELLED", "FAILED");

    private final VaultRepository vaultRepository;
    private final VaultItemRepository vaultItemRepository;
    private final BookingRepository bookingRepository;
    private final BorrowCycleExtractor cycleExtractor;
    private final List<String> sessionItemIds;

    public VaultBoardService(VaultRepository vaultRepository,
                             VaultItemRepository vaultItemRepository,
                             BookingRepository bookingRepository,
                             BorrowCycleExtractor cycleExtractor,
                             @Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.vaultRepository = vaultRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bookingRepository = bookingRepository;
        this.cycleExtractor = cycleExtractor;
        this.sessionItemIds = sessionItemIds;
    }

    public Optional<Board> getBoard(String vaultId) {
        return vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId).map(this::buildBoard);
    }

    private Board buildBoard(Vault vault) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = LocalDate.now().atStartOfDay();   // "จบแล้ววันนี้"

        List<Booking> raw = bookingRepository.findBoardBookings(vault.getVaultId(), since);
        List<BoardBooking> all = raw.stream().map(b -> toBoardBooking(b, now)).toList();

        List<BoardBooking> active = all.stream().filter(b -> !b.done()).toList();
        List<BoardBooking> done   = all.stream().filter(BoardBooking::done).toList();

        // กล่อง session ไม่มีรอบหยิบ-คืนของตัวเอง (MOVE ทุกตัวชี้ไปที่กล่องเกม)
        // ถ้าไม่ผูกกับ booking ตรงๆ การ์ดจะขึ้นว่า "พร้อมเปิดรอบ" ทั้งที่ยังมีรอบค้างอยู่
        // ใช้เกณฑ์เดียวกับด่านกันจองซ้ำตอนสร้าง booking: ยังไม่จบ = ยังจองใหม่ไม่ได้
        Map<String, String> sessionHoldBySerial = new LinkedHashMap<>();
        for (Booking b : raw) {
            if (!cycleExtractor.isEvent(b) || TERMINAL.contains(b.getBookingStatus())) continue;
            if (b.getSerialNumber() != null) {
                sessionHoldBySerial.putIfAbsent(b.getSerialNumber(), b.getBookingId());
            }
        }

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

        List<BoardItem> items = buildItems(vault, active, heldByKey, reservedByKey, sessionHoldBySerial, now);

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

    /**
     * booking 1 ใบในรูปแบบของหน้า board — ใช้เปิด timeline จากหน้าอื่น (Vault Analytics)
     * รวมใบที่ถูก soft-delete ด้วย เพราะ booking ที่ยกเลิกถูกตั้ง deleted_at แต่ยังต้องเปิดดูได้
     */
    public Optional<BoardBooking> getBookingIncludingDeleted(String bookingId) {
        return bookingRepository.findAllByBookingIdIncludingDeleted(bookingId).stream().findFirst()
                .map(b -> toBoardBooking(b, LocalDateTime.now()));
    }

    // ── booking → board row (พร้อม cycles + timeline) ──────────────────────────
    private BoardBooking toBoardBooking(Booking b, LocalDateTime now) {
        boolean isEvent = cycleExtractor.isEvent(b);
        List<BookingStatusEvent> events = cycleExtractor.events(b);

        List<Cycle> cycles = cycleExtractor.cycles(b, events, now).stream()
                .map(this::toCycle)
                .toList();

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

    private Cycle toCycle(RawCycle rc) {
        return new Cycle(rc.itemName(), rc.serialNumber(), rc.matchKey(),
                toOffset(rc.pickedUpAt()), toOffset(rc.returnedAt()),
                rc.minutes(), rc.late(), rc.pending());
    }

    // ── กล่องในตู้ ─────────────────────────────────────────────────────────────
    private List<BoardItem> buildItems(Vault vault, List<BoardBooking> active,
                                       Map<String, String> heldByKey,
                                       Map<String, String> reservedByKey,
                                       Map<String, String> sessionHoldBySerial,
                                       LocalDateTime now) {
        List<BoardItem> items = new ArrayList<>();

        for (VaultItem vi : vaultItemRepository.findByVaultId(vault.getVaultId())) {
            boolean isSession = sessionItemIds.contains(vi.getItem().getItemId());
            String serial = vi.getSerialNumber();
            // กล่องเดียวกันอาจถูกอ้างด้วย epc (จาก MOVE event) หรือ serial (จาก game booking)
            String heldByCycle = firstNonNull(lookup(heldByKey, vi.getRfidTag()), lookup(heldByKey, serial));
            // กล่อง session ผูกกับ booking ที่ยังไม่จบโดยตรง ไม่ได้มาจากรอบหยิบ-คืน
            final String heldBy = isSession && heldByCycle == null
                    ? lookup(sessionHoldBySerial, serial) : heldByCycle;
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
                    // กล่อง session วัดจากช่วงเวลาของ booking เพราะไม่มีรอบหยิบ-คืนให้วัด
                    if (isSession && owner.timeStart() != null) {
                        minutesOut = Duration.between(owner.timeStart().toLocalDateTime(), now).toMinutes();
                        late = owner.timeEnd() != null && now.isAfter(owner.timeEnd().toLocalDateTime());
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

    private OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK) : null;
    }
}
