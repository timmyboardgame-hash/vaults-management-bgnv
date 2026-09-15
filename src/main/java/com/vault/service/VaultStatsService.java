package com.vault.service;

import com.vault.dto.VaultAnalyticsDto.BarRow;
import com.vault.dto.VaultAnalyticsDto.Filter;
import com.vault.dto.VaultAnalyticsDto.HeatCell;
import com.vault.dto.VaultAnalyticsDto.HeatRow;
import com.vault.dto.VaultAnalyticsDto.Kpi;
import com.vault.dto.VaultAnalyticsDto.Page;
import com.vault.dto.VaultAnalyticsDto.TrendPoint;
import com.vault.dto.VaultAnalyticsDto.Usage;
import com.vault.dto.VaultAnalyticsDto.VaultOption;
import com.vault.dto.VaultAnalyticsDto.VaultRow;
import com.vault.entity.BorrowRecord;
import com.vault.entity.DailyItemStat;
import com.vault.entity.DailyVaultStat;
import com.vault.entity.Vault;
import com.vault.repository.BorrowRecordRepository;
import com.vault.repository.DailyItemStatRepository;
import com.vault.repository.DailyVaultStatRepository;
import com.vault.repository.VaultRepository;
import com.vault.service.StatsRollupService.Computation;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * หน้า Vault Analytics — อ่านจากตารางสรุป ไม่ไล่ของดิบ
 *
 * ช่วงที่เลือก = วันก่อนหน้าจากตารางสรุป + วันนี้คำนวณสด (StatsRollupService.preview)
 * เพราะวันนี้ยังไม่จบวัน งานรายคืนจึงยังไม่ได้สรุป
 *
 * ตัวหารของ "เวลาที่ตู้ไม่ว่าง" คือจำนวนตู้ × ชั่วโมงเปิดร้าน ไม่ใช่จำนวนกล่อง
 * เพราะตู้หยิบได้ทีละกล่อง เพดานของตู้หนึ่งใบคือ 12 ชั่วโมงต่อวัน
 */
@Service
public class VaultStatsService {

    public static final List<Integer> PERIODS = List.of(30, 90, 365, 1095);
    private static final int OPEN_MINUTES_PER_DAY = 12 * 60;

    private static final String[] TH_MONTH = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
                                              "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};
    private static final String[] TH_DOW = {"จ", "อ", "พ", "พฤ", "ศ", "ส", "อา"};
    private static final String[] TH_DOW_FULL = {"จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์"};
    private static final String[] DURATION_LABELS = {"ต่ำกว่า 5 นาที", "5 – 15 นาที", "15 – 30 นาที",
                                                     "30 – 60 นาที", "1 – 2 ชั่วโมง", "เกิน 2 ชั่วโมง"};

    /** ยอดรวมของ 1 ช่วง — ทั้งช่วง / ช่วงก่อนหน้า / ถังในกราฟ / ต่อตู้ */
    private static final class Agg {
        long bookings, eventBookings, cycles, busyMinutes, vaultDays;

        void add(DailyVaultStat s) {
            bookings += s.getBookingsTotal();
            eventBookings += s.getBookingsEvent();
            cycles += s.getCycles();
            busyMinutes += s.getBusyMinutes();
            vaultDays++;   // 1 แถว = ตู้ 1 ใบ × 1 วัน — ตู้ที่เพิ่งติดตั้งกลางช่วงจึงไม่ถ่วงค่าเฉลี่ย
        }

        double busyPct() { return vaultDays == 0 ? 0 : busyMinutes * 100.0 / (vaultDays * OPEN_MINUTES_PER_DAY); }
        double avgMinutes() { return cycles == 0 ? 0 : (double) busyMinutes / cycles; }
    }

    private final VaultRepository vaultRepository;
    private final DailyVaultStatRepository dailyVaultStatRepository;
    private final DailyItemStatRepository dailyItemStatRepository;
    private final BorrowRecordRepository borrowRecordRepository;
    private final StatsRollupService rollupService;

    public VaultStatsService(VaultRepository vaultRepository,
                             DailyVaultStatRepository dailyVaultStatRepository,
                             DailyItemStatRepository dailyItemStatRepository,
                             BorrowRecordRepository borrowRecordRepository,
                             StatsRollupService rollupService) {
        this.vaultRepository = vaultRepository;
        this.dailyVaultStatRepository = dailyVaultStatRepository;
        this.dailyItemStatRepository = dailyItemStatRepository;
        this.borrowRecordRepository = borrowRecordRepository;
        this.rollupService = rollupService;
    }

    public Page build(String vaultIdParam, int daysParam, boolean compare) {
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
        Predicate<Vault> inScope = v -> scope == null || scope.getVaultId().equals(v.getVaultId());

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
        List<Agg> series = new ArrayList<>(buckets.values());
        List<TrendPoint> trend = buckets.entrySet().stream()
                .map(e -> new TrendPoint(bucketLabel(e.getKey(), gran),
                        e.getValue().bookings, e.getValue().eventBookings, e.getValue().cycles))
                .toList();

        List<Kpi> kpis = List.of(
                kpi("Booking ทั้งหมด", n(total.bookings), "ใบ",
                        total.bookings, prev.bookings, compare, hasPrev, series, a -> a.bookings),
                kpi("รอบหยิบ-คืน", n(total.cycles), "รอบ",
                        total.cycles, prev.cycles, compare, hasPrev, series, a -> a.cycles),
                kpi("เวลาที่ตู้ไม่ว่าง", dec(total.busyPct()), "%",
                        total.busyPct(), prev.busyPct(), compare, hasPrev, series, Agg::busyPct),
                kpi("ยืมเฉลี่ยต่อรอบ", String.valueOf(Math.round(total.avgMinutes())), "นาที",
                        total.avgMinutes(), prev.avgMinutes(), compare, hasPrev, series, Agg::avgMinutes)
        );

        List<BorrowRecord> todayRecords = todayCalc.records().stream()
                .filter(r -> inScope.test(r.getVault())).toList();

        Usage usage = heatAndRest(scope, inScope, from, yesterday, gran, buckets, todayCalc, todayRecords,
                cur, allRows, vaults, kpis, trend);

        String granLabel = switch (gran) { case "week" -> "รายสัปดาห์"; case "month" -> "รายเดือน"; default -> "รายวัน"; };
        Filter filter = new Filter(scope != null ? scope.getVaultId() : null, days, compare,
                dateLabel(from), "วันนี้ " + dateLabel(today), gran, granLabel);

        LocalDateTime latest = dailyVaultStatRepository.findLatestComputedAt();

        return new Page(
                filter,
                vaults.stream().map(v -> new VaultOption(v.getVaultId(), v.getVaultName())).toList(),
                scope == null ? "ทุกตู้" : scope.getVaultId(),
                scope == null ? vaults.size() + " ตู้ที่ใช้งานอยู่"
                              : scope.getVaultName() + " · จุ " + scope.getVaultSlot() + " กล่อง",
                latest == null ? null : dateTimeLabel(latest),
                total.bookings + total.cycles > 0,
                usage
        );
    }

    // ── การ์ดที่เหลือของแท็บการใช้งาน ────────────────────────────────────────
    private Usage heatAndRest(Vault scope, Predicate<Vault> inScope, LocalDate from, LocalDate yesterday, String gran,
                              LinkedHashMap<LocalDate, Agg> buckets, Computation todayCalc,
                              List<BorrowRecord> todayRecords, List<DailyVaultStat> cur,
                              List<DailyVaultStat> allRows, List<Vault> vaults,
                              List<Kpi> kpis, List<TrendPoint> trend) {
        String scopeId = scope != null ? scope.getVaultId() : null;

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

    // ── helpers ───────────────────────────────────────────────────────────────
    private static Kpi kpi(String label, String value, String unit, double cur, double prev,
                           boolean compare, boolean hasPrev, List<Agg> series, ToDoubleFunction<Agg> f) {
        String cls = null, text = null;
        if (compare) {
            if (!hasPrev || prev == 0) {
                cls = "flat";
                text = "ช่วงก่อนไม่มีข้อมูล";
            } else {
                double pct = (cur - prev) / prev * 100;
                cls = Math.abs(pct) < 1.5 ? "flat" : pct > 0 ? "up" : "down";
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
