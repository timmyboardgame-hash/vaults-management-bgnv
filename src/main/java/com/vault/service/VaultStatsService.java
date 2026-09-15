package com.vault.service;

import com.vault.dto.VaultAnalyticsDto.BarRow;
import com.vault.dto.VaultAnalyticsDto.BookingPage;
import com.vault.dto.VaultAnalyticsDto.BookingRow;
import com.vault.dto.VaultAnalyticsDto.Filter;
import com.vault.dto.VaultAnalyticsDto.Flag;
import com.vault.dto.VaultAnalyticsDto.HeatCell;
import com.vault.dto.VaultAnalyticsDto.HeatRow;
import com.vault.dto.VaultAnalyticsDto.Issue;
import com.vault.dto.VaultAnalyticsDto.Kpi;
import com.vault.dto.VaultAnalyticsDto.Page;
import com.vault.dto.VaultAnalyticsDto.ProblemPoint;
import com.vault.dto.VaultAnalyticsDto.Problems;
import com.vault.dto.VaultAnalyticsDto.TrendPoint;
import com.vault.dto.VaultAnalyticsDto.Usage;
import com.vault.dto.VaultAnalyticsDto.VaultIssueRow;
import com.vault.dto.VaultAnalyticsDto.VaultOption;
import com.vault.dto.VaultAnalyticsDto.VaultRow;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.BorrowRecord;
import com.vault.entity.DailyItemStat;
import com.vault.entity.DailyVaultStat;
import com.vault.entity.Vault;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.BorrowRecordRepository;
import com.vault.repository.DailyItemStatRepository;
import com.vault.repository.DailyVaultStatRepository;
import com.vault.repository.VaultRepository;
import com.vault.service.BorrowCycleExtractor.RawCycle;
import com.vault.service.StatsRollupService.Computation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * หน้า Vault Analytics — แท็บการใช้งาน / ความผิดปกติ อ่านจากตารางสรุป ไม่ไล่ของดิบ
 *
 * ช่วงที่เลือก = วันก่อนหน้าจากตารางสรุป + วันนี้คำนวณสด (StatsRollupService.preview)
 * เพราะวันนี้ยังไม่จบวัน งานรายคืนจึงยังไม่ได้สรุป
 * แท็บ Booking ย้อนหลังเป็นข้อยกเว้นเดียวที่อ่านของดิบ — เป็นรายการทีละใบ ไม่ใช่ยอดรวม
 *
 * ตัวหารของ "เวลาที่ตู้ไม่ว่าง" คือจำนวนตู้ × ชั่วโมงเปิดร้าน ไม่ใช่จำนวนกล่อง
 * เพราะตู้หยิบได้ทีละกล่อง เพดานของตู้หนึ่งใบคือ 12 ชั่วโมงต่อวัน
 */
@Service
public class VaultStatsService {

    public static final List<Integer> PERIODS = List.of(30, 90, 365, 1095);
    public static final List<String> TABS = List.of("use", "problems", "bookings");
    public static final List<String> BOOKING_FILTERS = List.of("all", "event", "game", "flagged");

    private static final int OPEN_MINUTES_PER_DAY = 12 * 60;
    private static final int PAGE_SIZE = 50;
    private static final int STUCK_PENDING_MINUTES = 15;
    private static final int MAX_OPEN_ISSUES = 20;
    private static final Set<String> TERMINAL = Set.of("RETURNED", "CANCELLED", "FAILED");

    private static final String[] TH_MONTH = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
                                              "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};
    private static final String[] TH_DOW = {"จ", "อ", "พ", "พฤ", "ศ", "ส", "อา"};
    private static final String[] TH_DOW_FULL = {"จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์"};
    private static final String[] DURATION_LABELS = {"ต่ำกว่า 5 นาที", "5 – 15 นาที", "15 – 30 นาที",
                                                     "30 – 60 นาที", "1 – 2 ชั่วโมง", "เกิน 2 ชั่วโมง"};
    private static final String[] ANOMALY_TYPES = {"wrong_tag", "late_return", "lockdown_triggered",
                                                   "lockdown_cleared", "force_unlock"};

    /** ยอดรวมของ 1 ช่วง — ทั้งช่วง / ช่วงก่อนหน้า / ถังในกราฟ / ต่อตู้ */
    private static final class Agg {
        long bookings, eventBookings, cycles, busyMinutes, vaultDays;
        long noAck, lateCycles, unpaired, lateEvents;
        final long[] anomalies = new long[ANOMALY_TYPES.length];   // เรียงตาม ANOMALY_TYPES

        void add(DailyVaultStat s) {
            bookings += s.getBookingsTotal();
            eventBookings += s.getBookingsEvent();
            cycles += s.getCycles();
            busyMinutes += s.getBusyMinutes();
            vaultDays++;   // 1 แถว = ตู้ 1 ใบ × 1 วัน — ตู้ที่เพิ่งติดตั้งกลางช่วงจึงไม่ถ่วงค่าเฉลี่ย
            noAck += s.getBookingsNoAck();
            lateCycles += s.getLateCycles();
            unpaired += s.getUnpairedMoves();
            lateEvents += s.getLateEvents();
            anomalies[0] += s.getAnomalyWrongTag();
            anomalies[1] += s.getAnomalyLateReturn();
            anomalies[2] += s.getAnomalyLockdownTriggered();
            anomalies[3] += s.getAnomalyLockdownCleared();
            anomalies[4] += s.getAnomalyForceUnlock();
        }

        double busyPct() { return vaultDays == 0 ? 0 : busyMinutes * 100.0 / (vaultDays * OPEN_MINUTES_PER_DAY); }
        double avgMinutes() { return cycles == 0 ? 0 : (double) busyMinutes / cycles; }
        double noAckPct() { return bookings == 0 ? 0 : noAck * 100.0 / bookings; }
        double latePct() { return cycles == 0 ? 0 : lateCycles * 100.0 / cycles; }
        long anomalyTotal() { long t = 0; for (long a : anomalies) t += a; return t; }
    }

    private final VaultRepository vaultRepository;
    private final DailyVaultStatRepository dailyVaultStatRepository;
    private final DailyItemStatRepository dailyItemStatRepository;
    private final BorrowRecordRepository borrowRecordRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusEventRepository eventRepository;
    private final BorrowCycleExtractor cycleExtractor;
    private final StatsRollupService rollupService;
    private final List<String> sessionItemIds;

    public VaultStatsService(VaultRepository vaultRepository,
                             DailyVaultStatRepository dailyVaultStatRepository,
                             DailyItemStatRepository dailyItemStatRepository,
                             BorrowRecordRepository borrowRecordRepository,
                             BookingRepository bookingRepository,
                             BookingStatusEventRepository eventRepository,
                             BorrowCycleExtractor cycleExtractor,
                             StatsRollupService rollupService,
                             @Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.vaultRepository = vaultRepository;
        this.dailyVaultStatRepository = dailyVaultStatRepository;
        this.dailyItemStatRepository = dailyItemStatRepository;
        this.borrowRecordRepository = borrowRecordRepository;
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.cycleExtractor = cycleExtractor;
        this.rollupService = rollupService;
        // IN () ว่างเป็น SQL ที่ใช้ไม่ได้ — ใส่ค่าที่ไม่มีทางตรงไว้กันไว้
        this.sessionItemIds = sessionItemIds.isEmpty() ? List.of("__none__") : sessionItemIds;
    }

    public Page build(String vaultIdParam, int daysParam, boolean compare,
                      String tabParam, String bookingFilterParam, int pageParam) {
        String tab = TABS.contains(tabParam) ? tabParam : "use";
        int days = PERIODS.contains(daysParam) ? daysParam : 90;
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate from = today.minusDays(days - 1L);
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1L);
        String gran = days <= 45 ? "day" : days <= 200 ? "week" : "month";

        List<Vault> vaults = vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        Vault scope = (vaultIdParam == null || vaultIdParam.isBlank()) ? null
                : vaults.stream().filter(v -> v.getVaultId().equals(vaultIdParam)).findFirst().orElse(null);
        Predicate<Vault> inScope = v -> v != null && (scope == null || scope.getVaultId().equals(v.getVaultId()));

        String granLabel = switch (gran) { case "week" -> "รายสัปดาห์"; case "month" -> "รายเดือน"; default -> "รายวัน"; };
        Filter filter = new Filter(scope != null ? scope.getVaultId() : null, days, compare,
                dateLabel(from), "วันนี้ " + dateLabel(today), gran, granLabel);

        Usage usage = null;
        Problems problems = null;
        BookingPage bookings = null;
        boolean hasData;

        if ("bookings".equals(tab)) {
            String bf = BOOKING_FILTERS.contains(bookingFilterParam) ? bookingFilterParam : "all";
            bookings = bookingPage(scope, from, today, bf, Math.max(0, pageParam));
            hasData = bookings.total() > 0 || !"all".equals(bf);
        } else {
            // วันนี้ยังไม่อยู่ในตารางสรุป — คำนวณสดด้วย logic เดียวกับงานรายคืน แต่ไม่บันทึก
            Computation todayCalc = rollupService.preview(today);

            List<DailyVaultStat> allRows = new ArrayList<>(dailyVaultStatRepository.findRange(from, yesterday));
            allRows.addAll(todayCalc.vaultStats());
            List<DailyVaultStat> cur = allRows.stream().filter(s -> inScope.test(s.getVault())).toList();

            Agg total = new Agg();
            cur.forEach(total::add);

            Agg prev = new Agg();
            long prevDaysComputed = 0;
            if (compare) {
                List<DailyVaultStat> prevRows = scope == null
                        ? dailyVaultStatRepository.findRange(prevFrom, prevTo)
                        : dailyVaultStatRepository.findRangeForVault(scope.getVaultId(), prevFrom, prevTo);
                prevRows.forEach(prev::add);
                prevDaysComputed = prevRows.stream().map(DailyVaultStat::getStatDate).distinct().count();
            }
            // เทียบได้เฉพาะเมื่อช่วงก่อนหน้าถูกคำนวณเกือบครบทั้งช่วง — ถ้าเพิ่ง backfill แค่บางวัน
            // ฐานจะเล็กมากจนได้ % เพี้ยนระดับหลักหมื่นที่ไม่มีความหมาย
            boolean hasPrev = prevDaysComputed >= Math.ceil(days * 0.8) && prev.bookings + prev.cycles > 0;

            // ถังในกราฟ — สร้างครบทุกถังตั้งแต่วันแรก วันที่ไม่มีการใช้งานต้องเป็นศูนย์ ไม่ใช่หายไปจากกราฟ
            LinkedHashMap<LocalDate, Agg> buckets = new LinkedHashMap<>();
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                buckets.computeIfAbsent(bucketKey(d, gran), k -> new Agg());
            }
            for (DailyVaultStat s : cur) {
                Agg a = buckets.get(bucketKey(s.getStatDate(), gran));
                if (a != null) a.add(s);
            }

            if ("problems".equals(tab)) {
                problems = problems(inScope, vaults, allRows, total, prev, compare, hasPrev, buckets, gran);
            } else {
                List<BorrowRecord> todayRecords = todayCalc.records().stream()
                        .filter(r -> inScope.test(r.getVault())).toList();
                usage = usage(scope, inScope, from, yesterday, gran, buckets, todayCalc, todayRecords,
                        cur, allRows, vaults, total, prev, compare, hasPrev);
            }
            hasData = total.bookings + total.cycles > 0;
        }

        LocalDateTime latest = dailyVaultStatRepository.findLatestComputedAt();

        return new Page(
                tab,
                filter,
                vaults.stream().map(v -> new VaultOption(v.getVaultId(), v.getVaultName())).toList(),
                scope == null ? "ทุกตู้" : scope.getVaultId(),
                scope == null ? vaults.size() + " ตู้ที่ใช้งานอยู่"
                              : scope.getVaultName() + " · จุ " + scope.getVaultSlot() + " กล่อง",
                latest == null ? null : dateTimeLabel(latest),
                hasData,
                usage,
                problems,
                bookings
        );
    }

    // ── แท็บการใช้งาน ────────────────────────────────────────────────────────
    private Usage usage(Vault scope, Predicate<Vault> inScope, LocalDate from, LocalDate yesterday, String gran,
                        LinkedHashMap<LocalDate, Agg> buckets, Computation todayCalc,
                        List<BorrowRecord> todayRecords, List<DailyVaultStat> cur,
                        List<DailyVaultStat> allRows, List<Vault> vaults,
                        Agg total, Agg prev, boolean compare, boolean hasPrev) {
        String scopeId = scope != null ? scope.getVaultId() : null;
        List<Agg> series = new ArrayList<>(buckets.values());

        List<TrendPoint> trend = buckets.entrySet().stream()
                .map(e -> new TrendPoint(bucketLabel(e.getKey(), gran),
                        e.getValue().bookings, e.getValue().eventBookings, e.getValue().cycles))
                .toList();

        List<Kpi> kpis = List.of(
                kpi("Booking ทั้งหมด", n(total.bookings), "ใบ",
                        total.bookings, prev.bookings, compare, hasPrev, false, series, a -> a.bookings),
                kpi("รอบหยิบ-คืน", n(total.cycles), "รอบ",
                        total.cycles, prev.cycles, compare, hasPrev, false, series, a -> a.cycles),
                kpi("เวลาที่ตู้ไม่ว่าง", dec(total.busyPct()), "%",
                        total.busyPct(), prev.busyPct(), compare, hasPrev, false, series, Agg::busyPct),
                kpi("ยืมเฉลี่ยต่อรอบ", String.valueOf(Math.round(total.avgMinutes())), "นาที",
                        total.avgMinutes(), prev.avgMinutes(), compare, hasPrev, false, series, Agg::avgMinutes)
        );

        // heatmap — ชั่วโมงที่หยิบ
        long[][] grid = new long[7][24];
        for (Object[] r : scopeId == null ? borrowRecordRepository.countByHour(from, yesterday)
                                          : borrowRecordRepository.countByHourForVault(scopeId, from, yesterday)) {
            grid[num(r[0]) - 1][num(r[1])] += lng(r[2]);
        }
        for (BorrowRecord r : todayRecords) grid[r.getPickupDow() - 1][r.getPickupHour()]++;

        int minH = 10, maxH = 22;
        for (long[] day : grid) {
            for (int h = 0; h < 24; h++) {
                if (day[h] > 0) { minH = Math.min(minH, h); maxH = Math.max(maxH, h); }
            }
        }
        List<Integer> hours = IntStream.rangeClosed(minH, maxH).boxed().toList();
        long heatMax = 1;
        int peakDow = -1, peakHour = -1;
        for (int d = 0; d < 7; d++) {
            for (int h : hours) {
                if (grid[d][h] > heatMax || (peakDow < 0 && grid[d][h] > 0)) {
                    heatMax = Math.max(heatMax, grid[d][h]);
                    peakDow = d;
                    peakHour = h;
                }
            }
        }
        final long max = heatMax;
        List<HeatRow> heat = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            final int dow = d;
            heat.add(new HeatRow(TH_DOW[d], hours.stream()
                    .map(h -> new HeatCell(h, grid[dow][h],
                            String.format(Locale.ROOT, "%.3f", 0.06 + grid[dow][h] * 0.86 / max)))
                    .toList()));
        }
        String heatPeak = peakDow < 0 ? "ยังไม่มีข้อมูล"
                : "วัน" + TH_DOW_FULL[peakDow] + " " + peakHour + ":00 น. · " + n(grid[peakDow][peakHour]) + " รอบ";

        // กล่องไหนถูกหยิบบ่อย — ระบุด้วย serial ไม่ใช่เลขช่อง
        Map<String, long[]> boxTotals = new LinkedHashMap<>();
        Map<String, String[]> boxInfo = new HashMap<>();
        for (Object[] r : scopeId == null ? dailyItemStatRepository.sumByBox(from, yesterday)
                                          : dailyItemStatRepository.sumByBoxForVault(scopeId, from, yesterday)) {
            addBox(boxTotals, boxInfo, (String) r[0], (String) r[1], (String) r[2], lng(r[3]), lng(r[4]), lng(r[5]));
        }
        for (DailyItemStat s : todayCalc.itemStats()) {
            if (inScope.test(s.getVault())) {
                addBox(boxTotals, boxInfo, s.getItem().getItemNameEn(), s.getSerialNumber(),
                        s.getVault().getVaultId(), s.getBorrowCount(), s.getBusyMinutes(), s.getLateCount());
            }
        }
        List<Map.Entry<String, long[]>> topEntries = boxTotals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(8).toList();
        long boxMax = topEntries.isEmpty() ? 1 : Math.max(1, topEntries.get(0).getValue()[1]);
        List<BarRow> topBoxes = topEntries.stream().map(e -> {
            String[] info = boxInfo.get(e.getKey());
            long[] t = e.getValue();
            String sub = (info[1] != null ? info[1] : "—") + (scopeId == null ? " · " + info[2] : "");
            return new BarRow(info[0], sub, t[1], hoursLabel(t[1]) + " ชม. · " + n(t[0]) + " ครั้ง",
                    width(t[1], boxMax), false);
        }).toList();

        // แต่ละรอบยืมนานแค่ไหน
        long[] dur = new long[6];
        for (DailyVaultStat s : cur) {
            dur[0] += s.getDurUnder5();
            dur[1] += s.getDur5To15();
            dur[2] += s.getDur15To30();
            dur[3] += s.getDur30To60();
            dur[4] += s.getDur60To120();
            dur[5] += s.getDurOver120();
        }
        long durTotal = 0, durMax = 1;
        for (long v : dur) { durTotal += v; durMax = Math.max(durMax, v); }
        List<BarRow> durations = new ArrayList<>();
        for (int i = 0; i < dur.length; i++) {
            String share = durTotal == 0 ? "0%" : dec(dur[i] * 100.0 / durTotal) + "%";
            durations.add(new BarRow(DURATION_LABELS[i], null, dur[i], share, width(dur[i], durMax), i == 0));
        }
        String shortCycleNote = durTotal == 0 ? null
                : "รอบสั้นผิดปกติ " + n(dur[0]) + " รอบ (" + dec(dur[0] * 100.0 / durTotal) + "%) — "
                  + "สุ่มเปิด timeline ดูว่าเป็นการเปิดดูจริง หรือ MOVE ซ้ำจาก tag เดียว";

        // หนึ่ง session หยิบกี่กล่อง
        Map<String, Long> perBooking = new HashMap<>();
        for (Object[] r : scopeId == null ? borrowRecordRepository.cyclesPerEventBooking(from, yesterday)
                                          : borrowRecordRepository.cyclesPerEventBookingForVault(scopeId, from, yesterday)) {
            perBooking.merge((String) r[0], lng(r[1]), Long::sum);
        }
        for (BorrowRecord r : todayRecords) {
            if ("event".equals(r.getBookingType())) perBooking.merge(r.getBooking().getId(), 1L, Long::sum);
        }
        long[] hist = new long[6];
        long sessionCycles = 0;
        for (long c : perBooking.values()) {
            hist[(int) Math.min(c, 6) - 1]++;
            sessionCycles += c;
        }
        long histMax = 1;
        for (long v : hist) histMax = Math.max(histMax, v);
        List<BarRow> perSession = new ArrayList<>();
        for (int i = 0; i < hist.length; i++) {
            String label = (i + 1) + (i == hist.length - 1 ? " กล่องขึ้นไป" : " กล่อง");
            perSession.add(new BarRow(label, null, hist[i], n(hist[i]) + " session", width(hist[i], histMax), false));
        }
        String perSessionNote;
        if (perBooking.isEmpty()) {
            perSessionNote = "ยังไม่มี event booking ที่หยิบกล่องในช่วงนี้";
        } else {
            double avg = (double) sessionCycles / perBooking.size();
            // PENDING + CONFIRMED + ACTIVE + ปิด booking = 4 แถว บวก MOVE หยิบ/คืนรอบละ 2 แถว
            perSessionNote = "เฉลี่ย " + dec(avg) + " กล่องต่อ session → ประมาณ " + Math.round(avg * 2 + 4)
                    + " แถวใน booking_status_events ต่อ session";
        }

        // ตารางตู้ — แสดงทุกตู้เสมอ ใช้เป็นทางเข้าไปเจาะดูทีละตู้
        Map<String, List<DailyVaultStat>> byVault = allRows.stream()
                .collect(Collectors.groupingBy(s -> s.getVault().getVaultId()));
        List<LocalDate> keys = new ArrayList<>(buckets.keySet());
        List<VaultRow> vaultRows = vaults.stream().map(v -> {
            Agg a = new Agg();
            List<DailyVaultStat> rows = byVault.getOrDefault(v.getVaultId(), List.of());
            rows.forEach(a::add);
            Map<LocalDate, Long> perBucket = new HashMap<>();
            for (DailyVaultStat s : rows) perBucket.merge(bucketKey(s.getStatDate(), gran), (long) s.getCycles(), Long::sum);
            String spark = keys.stream().map(k -> String.valueOf(perBucket.getOrDefault(k, 0L)))
                    .collect(Collectors.joining(","));
            double busy = a.busyPct();
            String severity = busy >= 55 ? "ok" : busy >= 30 ? "warn" : "bad";
            return new VaultRow(v.getVaultId(), v.getVaultName(), a.bookings, a.cycles, busy,
                    Math.round(a.avgMinutes()), severity, spark);
        }).sorted(Comparator.comparingLong(VaultRow::cycles).reversed()).toList();

        return new Usage(kpis, trend, hours, heat, heatPeak, topBoxes, durations, shortCycleNote,
                perSession, perSessionNote, vaultRows);
    }

    // ── แท็บความผิดปกติ ──────────────────────────────────────────────────────
    private Problems problems(Predicate<Vault> inScope, List<Vault> vaults, List<DailyVaultStat> allRows,
                              Agg total, Agg prev, boolean compare, boolean hasPrev,
                              LinkedHashMap<LocalDate, Agg> buckets, String gran) {
        List<Agg> series = new ArrayList<>(buckets.values());

        // ทุกตัวในแท็บนี้ ยิ่งน้อยยิ่งดี — ลูกศรขึ้นจึงเป็นสีแดง
        List<Kpi> kpis = List.of(
                kpi("Device ไม่ตอบคำสั่ง", dec(total.noAckPct()), "%",
                        total.noAckPct(), prev.noAckPct(), compare, hasPrev, true, series, Agg::noAckPct),
                kpi("MOVE ที่ปิดไม่ลง", n(total.unpaired), "รอบ",
                        total.unpaired, prev.unpaired, compare, hasPrev, true, series, a -> a.unpaired),
                kpi("Event มาผิดลำดับ", n(total.lateEvents), "ครั้ง",
                        total.lateEvents, prev.lateEvents, compare, hasPrev, true, series, a -> a.lateEvents),
                kpi("คืนช้ากว่ากำหนด", dec(total.latePct()), "%",
                        total.latePct(), prev.latePct(), compare, hasPrev, true, series, Agg::latePct)
        );

        List<ProblemPoint> trend = buckets.entrySet().stream()
                .map(e -> new ProblemPoint(bucketLabel(e.getKey(), gran), e.getValue().anomalyTotal(),
                        e.getValue().noAck, e.getValue().unpaired, e.getValue().lateEvents))
                .toList();

        long anomalyMax = 1;
        for (long a : total.anomalies) anomalyMax = Math.max(anomalyMax, a);
        List<BarRow> anomalies = new ArrayList<>();
        for (int i = 0; i < ANOMALY_TYPES.length; i++) {
            String delta = null;
            if (compare && hasPrev && prev.anomalies[i] > 0) {
                double pct = (total.anomalies[i] - prev.anomalies[i]) * 100.0 / prev.anomalies[i];
                delta = (pct > 0 ? "▲ " : pct < 0 ? "▼ " : "") + dec(Math.abs(pct)) + "%";
            }
            anomalies.add(new BarRow(ANOMALY_TYPES[i], delta, total.anomalies[i], n(total.anomalies[i]),
                    width(total.anomalies[i], anomalyMax), "lockdown_triggered".equals(ANOMALY_TYPES[i])));
        }

        List<Issue> issues = openIssues(inScope);

        Map<String, List<DailyVaultStat>> byVault = allRows.stream()
                .collect(Collectors.groupingBy(s -> s.getVault().getVaultId()));
        List<VaultIssueRow> vaultRows = vaults.stream().map(v -> {
            Agg a = new Agg();
            byVault.getOrDefault(v.getVaultId(), List.of()).forEach(a::add);
            String severity = a.unpaired > 0 || a.noAckPct() >= 3 ? "bad"
                    : a.bookings > 0 && a.anomalyTotal() * 10 > a.bookings ? "warn" : "ok";
            return new VaultIssueRow(v.getVaultId(), v.getVaultName(), a.bookings, dec(a.noAckPct()) + "%",
                    a.unpaired, a.lateEvents, a.anomalyTotal(), severity);
        }).sorted(Comparator.comparingLong((VaultIssueRow r) -> r.unpaired() + r.lateEvents() + r.anomalies()).reversed())
          .toList();

        return new Problems(kpis, trend, anomalies,
                issues.stream().limit(MAX_OPEN_ISSUES).toList(), issues.size(), vaultRows);
    }

    /** ปัญหาที่ค้างอยู่ตอนนี้ — สถานะที่ระบบปิดเองไม่ได้แล้ว อ่านสดจาก bookings ไม่ใช่จากตารางสรุป */
    private List<Issue> openIssues(Predicate<Vault> inScope) {
        LocalDateTime now = LocalDateTime.now();
        record Found(Issue issue, LocalDateTime since) {}
        List<Found> found = new ArrayList<>();

        for (Booking b : bookingRepository.findOpenPastEnd(now)) {
            if (!inScope.test(b.getVault())) continue;
            String v = b.getVault().getVaultId();
            switch (b.getBookingStatus()) {
                case "OVERDUE" -> found.add(new Found(new Issue("ของยังไม่คืนหลังหมดเวลา", "bad", b.getBookingId(), v,
                        age(b.getBookingTimeEnd(), now), "ตามของกับร้าน แล้วเช็ค tag กับของจริงในตู้"), b.getBookingTimeEnd()));
                case "ACTIVE" -> found.add(new Found(new Issue("เลยเวลาแล้ว ยังไม่มี event คืน", "bad", b.getBookingId(), v,
                        age(b.getBookingTimeEnd(), now), "ตรวจว่าตู้ส่ง event คืนมาถึงหรือไม่"), b.getBookingTimeEnd()));
                case "CONFIRMED" -> found.add(new Found(new Issue("หมดเวลาแต่ไม่เคยมีการหยิบ", "warn", b.getBookingId(), v,
                        age(b.getBookingTimeEnd(), now), "booking ค้างสถานะ — ยกเลิกหรือปิดให้ถูกต้อง"), b.getBookingTimeEnd()));
                case "PENDING" -> found.add(new Found(new Issue("ตู้ไม่ตอบคำสั่ง", "bad", b.getBookingId(), v,
                        age(b.getCreatedAt(), now), "ping ตู้ แล้วสร้าง booking ใหม่"), b.getCreatedAt()));
                default -> { }
            }
        }

        // PENDING ที่ยังไม่หมดเวลา แต่ตู้เงียบเกิน 15 นาที — ลูกค้ายืนรอหน้าตู้อยู่
        LocalDateTime stuckBefore = now.minusMinutes(STUCK_PENDING_MINUTES);
        for (Booking b : bookingRepository.findByStatus("PENDING")) {
            if (!inScope.test(b.getVault()) || b.getCreatedAt() == null) continue;
            if (b.getBookingTimeEnd() != null && b.getBookingTimeEnd().isBefore(now)) continue;   // นับไปแล้วข้างบน
            if (!b.getCreatedAt().isBefore(stuckBefore)) continue;
            found.add(new Found(new Issue("ตู้ไม่ตอบคำสั่งเกิน " + STUCK_PENDING_MINUTES + " นาที", "bad",
                    b.getBookingId(), b.getVault().getVaultId(), age(b.getCreatedAt(), now),
                    "ping ตู้ — ลูกค้าอาจยืนรออยู่หน้าตู้"), b.getCreatedAt()));
        }

        // รุนแรงก่อน แล้วค้างนานสุดก่อน
        found.sort(Comparator.comparing((Found f) -> "bad".equals(f.issue().severity()) ? 0 : 1)
                .thenComparing(f -> f.since() == null ? now : f.since()));
        return found.stream().map(Found::issue).toList();
    }

    // ── แท็บ Booking ย้อนหลัง ────────────────────────────────────────────────
    private BookingPage bookingPage(Vault scope, LocalDate from, LocalDate today, String filter, int page) {
        String type = "event".equals(filter) || "game".equals(filter) ? filter : "all";
        boolean flagged = "flagged".equals(filter);

        org.springframework.data.domain.Page<Booking> result = bookingRepository.findHistory(
                scope != null ? scope.getVaultId() : "",
                from.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                type, sessionItemIds, flagged, PageRequest.of(page, PAGE_SIZE));

        List<Booking> list = result.getContent();
        Map<String, List<BookingStatusEvent>> eventsByBooking = list.isEmpty() ? Map.of()
                : eventRepository.findByBookingIds(list.stream().map(Booking::getId).toList()).stream()
                    .collect(Collectors.groupingBy(e -> e.getBooking().getId(), LinkedHashMap::new, Collectors.toList()));

        LocalDateTime now = LocalDateTime.now();
        List<BookingRow> rows = list.stream().map(b -> {
            List<BookingStatusEvent> events = eventsByBooking.getOrDefault(b.getId(), List.of());
            boolean isEvent = cycleExtractor.isEvent(b);
            List<RawCycle> cycles = cycleExtractor.cycles(b, events, now).stream().filter(c -> !c.pending()).toList();
            long minutes = cycles.stream().mapToLong(RawCycle::minutes).sum();
            boolean closedOrPast = TERMINAL.contains(b.getBookingStatus())
                    || (b.getBookingTimeEnd() != null && now.isAfter(b.getBookingTimeEnd()));

            List<Flag> flags = new ArrayList<>();
            boolean answered = events.stream()
                    .anyMatch(e -> "CONFIRMED".equals(e.getStatus()) || "FAILED".equals(e.getStatus()));
            if (!answered) flags.add(new Flag("ตู้ไม่ตอบ", "bad"));
            if (closedOrPast && cycles.stream().anyMatch(c -> c.returnedAt() == null)) flags.add(new Flag("MOVE ค้าง", "bad"));
            Set<String> anomalyTypes = new LinkedHashSet<>();
            boolean lateEvent = false;
            for (BookingStatusEvent e : events) {
                if (e.getStatus() == null) continue;
                if (e.getStatus().startsWith("ANOMALY:")) anomalyTypes.add(e.getStatus().substring("ANOMALY:".length()));
                if (e.getStatus().startsWith("LATE_EVENT:")) lateEvent = true;
            }
            anomalyTypes.forEach(t -> flags.add(new Flag(t, "lockdown_triggered".equals(t) ? "bad" : "warn")));
            if (lateEvent) flags.add(new Flag("late event", "info"));
            if (!anomalyTypes.contains("late_return") && cycles.stream().anyMatch(RawCycle::late)) {
                flags.add(new Flag("คืนช้า", "warn"));
            }

            return new BookingRow(
                    b.getBookingId(),
                    isEvent ? "event" : "game",
                    b.getVault() != null ? b.getVault().getVaultId() : "—",
                    b.getBookingTimeStart() != null ? dateTimeLabel(b.getBookingTimeStart()) : "—",
                    windowLabel(b),
                    cycles.size(),
                    minutes,
                    flags,
                    b.getBookingStatus(),
                    b.getDeletedAt() != null
            );
        }).toList();

        long total = result.getTotalElements();
        String rangeLabel = total == 0 ? "ไม่พบ booking ในช่วงนี้"
                : "แสดง " + n((long) page * PAGE_SIZE + 1) + "–" + n((long) page * PAGE_SIZE + rows.size())
                  + " จาก " + n(total) + " ใบ";
        return new BookingPage(filter, rows, total, page, result.getTotalPages(), rangeLabel);
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static Kpi kpi(String label, String value, String unit, double cur, double prev,
                           boolean compare, boolean hasPrev, boolean lowerIsBetter,
                           List<Agg> series, ToDoubleFunction<Agg> f) {
        String cls = null, text = null;
        if (compare) {
            if (!hasPrev || prev == 0) {
                cls = "flat";
                text = "ช่วงก่อนไม่มีข้อมูล";
            } else {
                double pct = (cur - prev) / prev * 100;
                boolean better = lowerIsBetter ? pct < 0 : pct > 0;
                cls = Math.abs(pct) < 1.5 ? "flat" : better ? "up" : "down";
                text = (pct > 0 ? "▲ " : pct < 0 ? "▼ " : "") + dec(Math.abs(pct)) + "% เทียบช่วงก่อน";
            }
        }
        String spark = series.stream()
                .map(a -> String.format(Locale.ROOT, "%.2f", f.applyAsDouble(a)))
                .collect(Collectors.joining(","));
        return new Kpi(label, value, unit, cls, text, spark);
    }

    private static void addBox(Map<String, long[]> totals, Map<String, String[]> info,
                               String name, String serial, String vaultId, long borrows, long minutes, long late) {
        String key = name + "|" + serial + "|" + vaultId;
        long[] t = totals.computeIfAbsent(key, k -> new long[3]);
        t[0] += borrows;
        t[1] += minutes;
        t[2] += late;
        info.putIfAbsent(key, new String[]{name, serial, vaultId});
    }

    private static LocalDate bucketKey(LocalDate d, String gran) {
        return switch (gran) {
            case "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> d.withDayOfMonth(1);
            default -> d;
        };
    }

    private static String bucketLabel(LocalDate d, String gran) {
        return "month".equals(gran)
                ? TH_MONTH[d.getMonthValue() - 1] + " " + String.format("%02d", (d.getYear() + 543) % 100)
                : d.getDayOfMonth() + " " + TH_MONTH[d.getMonthValue() - 1];
    }

    private static String dateLabel(LocalDate d) {
        return d.getDayOfMonth() + " " + TH_MONTH[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    private static String dateTimeLabel(LocalDateTime t) {
        return t.getDayOfMonth() + " " + TH_MONTH[t.getMonthValue() - 1] + " "
                + String.format("%02d:%02d", t.getHour(), t.getMinute()) + " น.";
    }

    private static String windowLabel(Booking b) {
        if (b.getBookingTimeStart() == null || b.getBookingTimeEnd() == null) return "—";
        long m = Duration.between(b.getBookingTimeStart(), b.getBookingTimeEnd()).toMinutes();
        return m % 60 == 0 ? (m / 60) + " ชม." : (m / 60) + " ชม. " + (m % 60) + " น.";
    }

    private static String age(LocalDateTime since, LocalDateTime now) {
        if (since == null) return "—";
        long m = Math.max(0, Duration.between(since, now).toMinutes());
        if (m < 60) return m + " นาที";
        if (m < 48 * 60) return (m / 60) + " ชม.";
        return (m / (24 * 60)) + " วัน";
    }

    private static String hoursLabel(long minutes) {
        double h = minutes / 60.0;
        return h >= 100 ? n(Math.round(h)) : dec(h);
    }

    private static double width(long v, long max) { return Math.round(v * 1000.0 / Math.max(1, max)) / 10.0; }
    private static String n(long v) { return String.format("%,d", v); }
    private static String dec(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static int num(Object o) { return ((Number) o).intValue(); }
    private static long lng(Object o) { return ((Number) o).longValue(); }
}
