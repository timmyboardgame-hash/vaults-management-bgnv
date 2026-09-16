package com.vault.service;

import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.BorrowRecord;
import com.vault.entity.DailyItemStat;
import com.vault.entity.DailyVaultStat;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.BorrowRecordRepository;
import com.vault.repository.DailyItemStatRepository;
import com.vault.repository.DailyVaultStatRepository;
import com.vault.repository.VaultRepository;
import com.vault.service.BorrowCycleExtractor.RawCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * สรุปสถิติรายวัน — แปลงของดิบ (bookings + booking_status_events) เป็นตารางคำนวณ
 *
 * รันตอนเที่ยงคืนผ่าน StatsRollupScheduler หรือสั่ง backfill เอง
 * แต่ละวัน: ลบของวันนั้นทั้ง 3 ตาราง แล้วคำนวณเขียนใหม่ในทรานแซกชันเดียว → รันซ้ำได้ผลเท่าเดิม
 * วันนี้ที่ยังไม่จบวันใช้ preview() — คำนวณแบบเดียวกันแต่ไม่บันทึก
 *
 * การนับวัน (เวลาไทย):
 *   booking        → วันที่เริ่มช่วงจอง
 *   รอบหยิบ-คืน     → วันที่หยิบ (รอบข้ามเที่ยงคืนนับเป็นวันที่หยิบ)
 *   anomaly / late → วันที่ event เกิด
 *
 * ไม่กรอง deleted_at — game booking ที่ CANCELLED ถูก soft-delete แต่ต้องนับอยู่
 */
@Service
public class StatsRollupService {

    private static final Logger log = LoggerFactory.getLogger(StatsRollupService.class);
    private static final Set<String> TERMINAL = Set.of("RETURNED", "CANCELLED", "FAILED");

    public record DayResult(LocalDate date, int bookings, int cycles, int unpaired, int vaultRows, int itemRows) {}

    /** ผลคำนวณของ 1 วันในรูป entity ที่ยังไม่บันทึก — ใช้ได้ทั้งเขียนลง DB และแสดงสดบนหน้าเว็บ */
    public record Computation(LocalDate date, int bookings,
                              List<DailyVaultStat> vaultStats,
                              List<DailyItemStat> itemStats,
                              List<BorrowRecord> records) {
        public int cycles() { return records.size(); }
        public int unpaired() { return vaultStats.stream().mapToInt(DailyVaultStat::getUnpairedMoves).sum(); }
    }

    private final BookingRepository bookingRepository;
    private final BookingStatusEventRepository eventRepository;
    private final VaultRepository vaultRepository;
    private final BorrowCycleExtractor cycleExtractor;
    private final BorrowRecordRepository borrowRecordRepository;
    private final DailyVaultStatRepository dailyVaultStatRepository;
    private final DailyItemStatRepository dailyItemStatRepository;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    /** กันงานรายคืนกับ backfill เขียนวันเดียวกันพร้อมกัน — ทั้งสองทางเรียกผ่าน bean ตัวนี้ตัวเดียว */
    private final ReentrantLock rollupLock = new ReentrantLock();

    public StatsRollupService(BookingRepository bookingRepository,
                              BookingStatusEventRepository eventRepository,
                              VaultRepository vaultRepository,
                              BorrowCycleExtractor cycleExtractor,
                              BorrowRecordRepository borrowRecordRepository,
                              DailyVaultStatRepository dailyVaultStatRepository,
                              DailyItemStatRepository dailyItemStatRepository,
                              PlatformTransactionManager txManager) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.vaultRepository = vaultRepository;
        this.cycleExtractor = cycleExtractor;
        this.borrowRecordRepository = borrowRecordRepository;
        this.dailyVaultStatRepository = dailyVaultStatRepository;
        this.dailyItemStatRepository = dailyItemStatRepository;
        // ใช้ TransactionTemplate แทน @Transactional — rollupRange เรียก rollupDay ในคลาสเดียวกัน
        // ซึ่ง @Transactional จะไม่ทำงานเพราะไม่ผ่าน proxy
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** คำนวณช่วงวันที่ (รวมทั้งสองปลาย) ทีละวัน — แต่ละวันเป็นทรานแซกชันของตัวเอง พังวันไหนไม่ลากวันอื่น */
    public List<DayResult> rollupRange(LocalDate from, LocalDate to) {
        List<DayResult> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(rollupDay(d));
        }
        return out;
    }

    /**
     * คำนวณ 1 วัน — ทีละคิวเท่านั้น
     *
     * งานรายคืนกับปุ่ม backfill อยู่คนละเธรดแต่ process เดียวกัน ถ้าชนกันที่วันเดียวกัน
     * ทั้งคู่จะลบเสร็จแล้วต่างคนต่างเขียน กลายเป็นแถวซ้ำ · ล็อกให้เข้าทีละตัว
     */
    public DayResult rollupDay(LocalDate date) {
        rollupLock.lock();
        try {
            return rollupDayLocked(date);
        } finally {
            rollupLock.unlock();
        }
    }

    private DayResult rollupDayLocked(LocalDate date) {
        DayResult r = tx.execute(status -> {
            borrowRecordRepository.deleteByStatDate(date);
            dailyVaultStatRepository.deleteByStatDate(date);
            dailyItemStatRepository.deleteByStatDate(date);

            Computation c = computeDay(date, LocalDateTime.now());
            borrowRecordRepository.saveAll(c.records());
            dailyVaultStatRepository.saveAll(c.vaultStats());
            dailyItemStatRepository.saveAll(c.itemStats());
            return new DayResult(date, c.bookings(), c.cycles(), c.unpaired(), c.vaultStats().size(), c.itemStats().size());
        });
        log.info("[STATS] {} bookings={} cycles={} unpaired={} vaultRows={} itemRows={}",
                r.date(), r.bookings(), r.cycles(), r.unpaired(), r.vaultRows(), r.itemRows());
        return r;
    }

    /** คำนวณโดยไม่บันทึก — หน้าเว็บใช้กับวันนี้ที่ยังไม่จบวันและยังไม่อยู่ในตารางสรุป */
    public Computation preview(LocalDate date) {
        return readOnlyTx.execute(status -> computeDay(date, LocalDateTime.now()));
    }

    private Computation computeDay(LocalDate date, LocalDateTime now) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay();

        Map<String, VaultAcc> vaults = new LinkedHashMap<>();
        Map<String, ItemAcc> items = new LinkedHashMap<>();
        List<BorrowRecord> records = new ArrayList<>();

        // ตู้ที่มีอยู่แล้ววันนั้นได้แถวเสมอแม้ไม่มีการใช้งาน — วันว่างต้องเห็นเป็นศูนย์ ไม่ใช่หายไปจากกราฟ
        for (Vault v : vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()) {
            if (v.getCreatedAt() == null || v.getCreatedAt().isBefore(to)) {
                vault(vaults, v);
            }
        }

        int bookingCount = 0;
        // เผื่อ 1 วันทั้งสองข้าง — รอบที่หยิบวันนั้นอาจมาจาก booking ที่เริ่มก่อนเที่ยงคืน
        for (Booking b : bookingRepository.findForRollup(from.minusDays(1), to.plusDays(1))) {
            if (b.getVault() == null) continue;

            List<BookingStatusEvent> events = cycleExtractor.events(b);
            boolean isEvent = cycleExtractor.isEvent(b);

            if (within(b.getBookingTimeStart(), from, to)) {
                VaultAcc acc = vault(vaults, b.getVault());
                acc.bookingsTotal++;
                bookingCount++;
                if (isEvent) acc.bookingsEvent++; else acc.bookingsGame++;
                if ("CANCELLED".equals(b.getBookingStatus())) acc.bookingsCancelled++;
                // ตู้ตอบกลับ = ได้ CONFIRMED หรือ FAILED · ไม่มีทั้งคู่แปลว่าคำสั่งไปไม่ถึงหรือตู้ไม่ตอบ
                boolean deviceAnswered = events.stream()
                        .anyMatch(e -> "CONFIRMED".equals(e.getStatus()) || "FAILED".equals(e.getStatus()));
                if (!deviceAnswered) acc.bookingsNoAck++;
            }

            boolean closedOrPast = TERMINAL.contains(b.getBookingStatus())
                    || (b.getBookingTimeEnd() != null && now.isAfter(b.getBookingTimeEnd()));

            for (RawCycle c : cycleExtractor.cycles(b, events, now)) {
                if (c.pending() || !within(c.pickedUpAt(), from, to)) continue;
                VaultAcc acc = vault(vaults, b.getVault());

                if (c.returnedAt() == null) {
                    // ยังไม่คืนทั้งที่ booking จบหรือเลยเวลาแล้ว = MOVE ที่ปิดไม่ลง · ที่เหลือคือกำลังยืมอยู่ ยังไม่นับ
                    if (closedOrPast) acc.unpairedMoves++;
                    continue;
                }

                acc.addCycle(c.minutes(), c.late());
                records.add(toRecord(b, isEvent, c, date, now));

                if (c.item() != null) {
                    String key = b.getVault().getId() + "|" + c.item().getId() + "|" + c.serialNumber();
                    items.computeIfAbsent(key, k -> new ItemAcc(b.getVault(), c.item(), c.serialNumber()))
                         .add(c.minutes(), c.late());
                }
            }
        }

        // anomaly / late event นับตามเวลาที่เกิด ไม่ผูกกับช่วงจอง — LATE_EVENT มาถึงหลัง booking ปิดไปนานได้
        for (BookingStatusEvent e : eventRepository.findOccurredBetween(from, to)) {
            Vault v = e.getBooking().getVault();
            if (v != null) vault(vaults, v).countEvent(e.getStatus());
        }

        return new Computation(date, bookingCount,
                vaults.values().stream().map(a -> a.toEntity(date, now)).toList(),
                items.values().stream().map(a -> a.toEntity(date, now)).toList(),
                records);
    }

    private static VaultAcc vault(Map<String, VaultAcc> vaults, Vault v) {
        return vaults.computeIfAbsent(v.getId(), k -> new VaultAcc(v));
    }

    private static boolean within(LocalDateTime t, LocalDateTime from, LocalDateTime to) {
        return t != null && !t.isBefore(from) && t.isBefore(to);
    }

    private static BorrowRecord toRecord(Booking b, boolean isEvent, RawCycle c, LocalDate date, LocalDateTime now) {
        BorrowRecord r = new BorrowRecord();
        r.setBooking(b);
        r.setVault(b.getVault());
        r.setAgent(b.getAgent());
        r.setItem(c.item());
        r.setBookingType(isEvent ? "event" : "game");
        r.setMatchKey(c.matchKey());
        r.setSerialNumber(c.serialNumber());
        r.setEpc(c.epc());
        r.setPickedUpAt(c.pickedUpAt());
        r.setReturnedAt(c.returnedAt());
        r.setMinutes(c.minutes());
        r.setLate(c.late());
        r.setPickupHour(c.pickedUpAt().getHour());
        r.setPickupDow(c.pickedUpAt().getDayOfWeek().getValue());
        r.setStatDate(date);
        r.setComputedAt(now);
        return r;
    }

    // ── ตัวสะสมยอดระหว่างคำนวณ ─────────────────────────────────────────────────
    private static final class VaultAcc {
        final Vault vault;
        int bookingsTotal, bookingsEvent, bookingsGame, bookingsCancelled, bookingsNoAck;
        int cycles, lateCycles;
        long busyMinutes;
        int durUnder5, dur5To15, dur15To30, dur30To60, dur60To120, durOver120;
        int wrongTag, lockdownTriggered, lockdownCleared, forceUnlock, lateReturn, lateEvents, unpairedMoves;

        VaultAcc(Vault vault) { this.vault = vault; }

        void addCycle(long minutes, boolean late) {
            cycles++;
            busyMinutes += minutes;
            if (late) lateCycles++;
            if (minutes < 5) durUnder5++;
            else if (minutes < 15) dur5To15++;
            else if (minutes < 30) dur15To30++;
            else if (minutes < 60) dur30To60++;
            else if (minutes < 120) dur60To120++;
            else durOver120++;
        }

        void countEvent(String status) {
            if (status == null) return;
            if (status.startsWith("LATE_EVENT:")) { lateEvents++; return; }
            switch (status) {
                case "ANOMALY:wrong_tag"          -> wrongTag++;
                case "ANOMALY:lockdown_triggered" -> lockdownTriggered++;
                case "ANOMALY:lockdown_cleared"   -> lockdownCleared++;
                case "ANOMALY:force_unlock"       -> forceUnlock++;
                case "ANOMALY:late_return"        -> lateReturn++;
                default -> { }
            }
        }

        DailyVaultStat toEntity(LocalDate date, LocalDateTime now) {
            DailyVaultStat s = new DailyVaultStat();
            s.setStatDate(date);
            s.setVault(vault);
            s.setBookingsTotal(bookingsTotal);
            s.setBookingsEvent(bookingsEvent);
            s.setBookingsGame(bookingsGame);
            s.setBookingsCancelled(bookingsCancelled);
            s.setBookingsNoAck(bookingsNoAck);
            s.setCycles(cycles);
            s.setBusyMinutes(busyMinutes);
            s.setLateCycles(lateCycles);
            s.setDurUnder5(durUnder5);
            s.setDur5To15(dur5To15);
            s.setDur15To30(dur15To30);
            s.setDur30To60(dur30To60);
            s.setDur60To120(dur60To120);
            s.setDurOver120(durOver120);
            s.setAnomalyWrongTag(wrongTag);
            s.setAnomalyLockdownTriggered(lockdownTriggered);
            s.setAnomalyLockdownCleared(lockdownCleared);
            s.setAnomalyForceUnlock(forceUnlock);
            s.setAnomalyLateReturn(lateReturn);
            s.setLateEvents(lateEvents);
            s.setUnpairedMoves(unpairedMoves);
            s.setComputedAt(now);
            return s;
        }
    }

    private static final class ItemAcc {
        final Vault vault;
        final Item item;
        final String serialNumber;
        int borrowCount, lateCount;
        long busyMinutes;

        ItemAcc(Vault vault, Item item, String serialNumber) {
            this.vault = vault;
            this.item = item;
            this.serialNumber = serialNumber;
        }

        void add(long minutes, boolean late) {
            borrowCount++;
            busyMinutes += minutes;
            if (late) lateCount++;
        }

        DailyItemStat toEntity(LocalDate date, LocalDateTime now) {
            DailyItemStat s = new DailyItemStat();
            s.setStatDate(date);
            s.setVault(vault);
            s.setItem(item);
            s.setSerialNumber(serialNumber);
            s.setBorrowCount(borrowCount);
            s.setBusyMinutes(busyMinutes);
            s.setLateCount(lateCount);
            s.setComputedAt(now);
            return s;
        }
    }
}
