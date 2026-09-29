package com.vault.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * ข้อมูลทดสอบสำหรับหน้า Vault Analytics — เปิดเฉพาะเมื่อ analytics.dev-tools=true
 *
 * สร้างที่ชั้นของดิบ (bookings + booking_status_events) ไม่ใช่ตารางสถิติ
 * เพื่อให้ข้อมูลวิ่งผ่านงานสรุปจริงทุกขั้น ถ้ายัดตัวเลขลงตารางสถิติตรงๆ จะไม่ได้ทดสอบอะไรเลย
 *
 * เขียนด้วย JDBC เพราะ created_at / occurred_at เป็น @CreationTimestamp — JPA จะทับเป็นเวลาปัจจุบันเสมอ
 * เวลาเก็บเป็น epoch millis ด้วยเขตเวลาของ JVM แบบเดียวกับที่ Hibernate เขียน
 *
 * ทุกแถวมีรหัสขึ้นต้น SEED- ลบทิ้งได้ทั้งชุดด้วย clear()
 * รูปแบบ note ของ MOVE ต้องตรงกับ BookingService.describeCopy() ไม่งั้น extractor หา epc ไม่เจอ
 */
@Service
@ConditionalOnProperty(name = "analytics.dev-tools", havingValue = "true")
public class AnalyticsSeedService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSeedService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    public record SeedResult(int vaults, int boxes, int bookings, int events) {}

    private record Game(String dbId, String name, int playMin, int playMax, boolean quitsEarly) {}
    private record Box(Game game, String serial, String tag) {}
    private record VaultSeed(String dbId, String agentDbId, double busy, List<Box> boxes, String sessionSerial) {}

    // ชื่อ, ชื่อไทย, เวลาเล่นต่ำสุด-สูงสุด, ผู้เล่นต่ำสุด-สูงสุด, ความยาก, มักเล่นไม่จบ
    private static final Object[][] GAMES = {
        {"Cascadia",       "คาสคาเดีย",   30,  45, 1,  4, 1.8, false},
        {"Splendor",       "สเปลนดอร์",   30,  30, 2,  4, 1.8, false},
        {"Avalon",         "อวาลอน",      30,  30, 5, 10, 1.8, false},
        {"Phi Thuay Kaew", "ผีถ้วยแก้ว",   20,  40, 3,  8, 1.2, false},
        {"Wingspan",       "วิงสแปน",     40,  70, 1,  5, 2.4, true},
        {"Thrill Bomb",    "ทริลบอมบ์",   10,  20, 2,  6, 1.1, false},
        {"Codenames",      "โค้ดเนม",     15,  15, 2,  8, 1.3, false},
        {"Dixit",          "ดิกซิท",      30,  30, 3,  6, 1.2, false},
        {"Azul",           "อาซูล",       30,  45, 2,  4, 1.8, false},
        {"Ouija",          "วิญญาณ",      15,  30, 2,  6, 1.1, false},
    };

    private final JdbcTemplate jdbc;
    private final List<String> sessionItemIds;
    private final ZoneId zone = ZoneId.systemDefault();

    public AnalyticsSeedService(JdbcTemplate jdbc,
                                @Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.jdbc = jdbc;
        this.sessionItemIds = sessionItemIds;
    }

    public boolean hasSeed() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vaults WHERE vault_id LIKE 'SEED-%'", Integer.class);
        return n != null && n > 0;
    }

    /** ลบข้อมูลทดสอบทั้งชุด รวมแถวสถิติที่คำนวณจากมัน — เรียงลบลูกก่อนแม่ */
    @Transactional
    public void clear() {
        String seedVaults = "(SELECT id FROM vaults WHERE vault_id LIKE 'SEED-%')";
        jdbc.update("DELETE FROM booking_status_events WHERE booking_id IN (SELECT id FROM bookings WHERE booking_id LIKE 'SEED-%')");
        jdbc.update("DELETE FROM borrow_records WHERE vault_id IN " + seedVaults);
        jdbc.update("DELETE FROM daily_item_stats WHERE vault_id IN " + seedVaults);
        jdbc.update("DELETE FROM daily_vault_stats WHERE vault_id IN " + seedVaults);
        jdbc.update("DELETE FROM bookings WHERE booking_id LIKE 'SEED-%'");
        jdbc.update("DELETE FROM vault_items WHERE vault_id IN " + seedVaults);
        jdbc.update("DELETE FROM bindings WHERE vault_id IN " + seedVaults);
        jdbc.update("DELETE FROM vaults WHERE vault_id LIKE 'SEED-%'");
        jdbc.update("DELETE FROM items WHERE item_id LIKE 'SEED-%'");
        jdbc.update("DELETE FROM agents WHERE agent_id LIKE 'SEED-%'");
        log.info("[SEED] cleared analytics test data");
    }

    /** สร้างข้อมูลทดสอบย้อนหลัง :days วัน (ไม่รวมวันนี้) — ล้างชุดเก่าก่อนเสมอ */
    @Transactional
    public SeedResult seed(int days) {
        clear();
        Random rnd = new Random(20260915L);   // seed คงที่ — สร้างซ้ำได้ตัวเลขเดิม เทียบผลก่อน/หลังแก้โค้ดได้
        LocalDate today = LocalDate.now();
        long created = millis(today.minusDays(days + 30L).atTime(9, 0));

        String sessionItem = sessionItemDbId(created);

        String ag1 = insertAgent("SEED-AG-01", "BGNV สยาม (ทดสอบ)", "10", created);
        String ag2 = insertAgent("SEED-AG-02", "Meeple House (ทดสอบ)", "50", created);

        List<Game> games = new ArrayList<>();
        for (int i = 0; i < GAMES.length; i++) {
            Object[] g = GAMES[i];
            String id = uuid();
            jdbc.update("INSERT INTO items (id, item_id, item_name_en, item_name_th, item_image_url, item_status, game_code, " +
                        "player_count_min, player_count_max, difficulty_rating, play_time_min, play_time_max, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, String.format("SEED-GAME-%02d", i + 1), g[0], g[1], "https://placehold.co/400", "ENABLE",
                    String.format("SEED-G%02d", i + 1), g[4], g[5], g[6], g[2], g[3], created, created);
            games.add(new Game(id, (String) g[0], (Integer) g[2], (Integer) g[3], (Boolean) g[7]));
        }

        // ตู้ใช้งานไม่เท่ากัน — ตารางตู้จะได้เรียงแล้วมีความหมาย
        Object[][] vaultDefs = {
            {"SEED-VT-01", "ตู้ทดสอบ สยาม 1", ag1, 1.00},
            {"SEED-VT-02", "ตู้ทดสอบ สยาม 2", ag1, 0.70},
            {"SEED-VT-03", "ตู้ทดสอบ Meeple",  ag2, 0.45},
        };
        List<VaultSeed> vaults = new ArrayList<>();
        int boxCount = 0;
        for (int v = 0; v < vaultDefs.length; v++) {
            String vaultDbId = uuid();
            jdbc.update("INSERT INTO vaults (id, vault_id, vault_name, vault_status, vault_slot, description, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                    vaultDbId, vaultDefs[v][0], vaultDefs[v][1], "ENABLE", 8, "ข้อมูลทดสอบ Vault Analytics", created, created);
            jdbc.update("INSERT INTO bindings (id, agent_id, vault_id, status, created_at, updated_at) VALUES (?,?,?,?,?,?)",
                    uuid(), vaultDefs[v][2], vaultDbId, "ACTIVE", created, created);

            List<Box> boxes = new ArrayList<>();
            for (int k = 0; k < 7; k++) {
                Game game = games.get((v * 3 + k) % games.size());
                String serial = String.format("SEED-SN-%02d-%02d", v + 1, k + 1);
                String tag = String.format("E2800000%04d%012d", v + 1, k + 1);   // 24 hex ตามรูปแบบ tag จริง
                jdbc.update("INSERT INTO vault_items (id, vault_id, item_id, rfid_tag, serial_number, status, created_at, updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?)",
                        uuid(), vaultDbId, game.dbId(), tag, serial, "ACTIVE", created, created);
                boxes.add(new Box(game, serial, tag));
                boxCount++;
            }
            String sessionSerial = String.format("SEED-SESSION-%02d", v + 1);
            jdbc.update("INSERT INTO vault_items (id, vault_id, item_id, rfid_tag, serial_number, status, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                    uuid(), vaultDbId, sessionItem, null, sessionSerial, "ACTIVE", created, created);
            vaults.add(new VaultSeed(vaultDbId, (String) vaultDefs[v][2], (Double) vaultDefs[v][3], boxes, sessionSerial));
        }

        Batch batch = new Batch();
        for (int back = days; back >= 1; back--) {
            LocalDate day = today.minusDays(back);
            double weekday = weekdayFactor(day.getDayOfWeek());
            double eventShare = back > 60 ? 0.08 : 0.35;   // event booking เพิ่งเริ่มติดตลาดช่วง 60 วันหลัง

            for (VaultSeed v : vaults) {
                // ตู้หยิบได้ทีละกล่อง — วาง booking เรียงต่อกันตลอดวัน ไม่ให้ช่วงที่มีของออกซ้อนกัน
                // เริ่มวันไม่พร้อมกันทุกวัน — ถ้าเริ่ม 10:00 ตรงทุกวัน heatmap จะมีแถบสว่างปลอมที่ชั่วโมงนั้น
                LocalDateTime cursor = day.atTime(10, 0).plusMinutes(rnd.nextInt(150));
                LocalDateTime close = day.atTime(21, 30);
                int target = (int) Math.round(4.2 * v.busy() * weekday * (0.7 + rnd.nextDouble() * 0.6));
                for (int i = 0; i < target && cursor.isBefore(close); i++) {
                    cursor = roll(rnd, eventShare)
                            ? seedEventBooking(batch, rnd, v, sessionItem, day, cursor)
                            : seedGameBooking(batch, rnd, v, day, cursor);
                }
            }
        }

        jdbc.batchUpdate("INSERT INTO bookings (id, booking_date, booking_id, booking_name, booking_status, booking_time_end, " +
                         "booking_time_start, created_at, deleted_at, pin, request_id, updated_at, agent_id, item_id, vault_id, serial_number) " +
                         "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", batch.bookings);
        jdbc.batchUpdate("INSERT INTO booking_status_events (id, note, occurred_at, status, booking_id) VALUES (?,?,?,?,?)",
                batch.events);

        SeedResult r = new SeedResult(vaults.size(), boxCount, batch.bookings.size(), batch.events.size());
        log.info("[SEED] vaults={} boxes={} bookings={} events={} days={}", r.vaults(), r.boxes(), r.bookings(), r.events(), days);
        return r;
    }

    // ── game booking: จองรายกล่อง หยิบครั้งเดียวคืนครั้งเดียว ───────────────────
    private LocalDateTime seedGameBooking(Batch batch, Random rnd, VaultSeed v, LocalDate day, LocalDateTime cursor) {
        Box box = v.boxes().get(rnd.nextInt(v.boxes().size()));
        LocalDateTime start = cursor.plusMinutes(5 + rnd.nextInt(36));
        LocalDateTime end = start.plusMinutes(120);
        LocalDateTime created = start.minusMinutes(10 + rnd.nextInt(50));
        String id = uuid();
        String pin = String.format("%06d", rnd.nextInt(1_000_000));
        String status;
        LocalDateTime deleted = null;
        LocalDateTime last = start;

        batch.event(id, "PENDING", "PIN: " + pin, created);
        if (roll(rnd, 0.02)) {
            status = "PENDING";   // คำสั่งไปไม่ถึงตู้ — ไม่มี CONFIRMED ค้างอยู่อย่างนั้น
        } else {
            batch.event(id, "CONFIRMED", null, created.plusSeconds(2));
            if (roll(rnd, 0.04)) {
                LocalDateTime at = start.plusMinutes(rnd.nextInt(30));
                batch.event(id, "CANCELLED", null, at);
                status = "CANCELLED";
                deleted = at;     // BookingService soft-delete ตอนยกเลิก — จำลองให้เหมือน
                last = at;
            } else {
                LocalDateTime pick = start.plusMinutes(1 + rnd.nextInt(15));
                LocalDateTime ret = pick.plusMinutes(gameDuration(rnd, box.game()));
                if (roll(rnd, 0.05)) batch.event(id, "ANOMALY:wrong_tag", "wrong tag detected", pick.minusMinutes(1));
                batch.event(id, "ACTIVE", null, pick);
                maybeLockdown(batch, rnd, id, pick);
                if (ret.isAfter(end.plusMinutes(15))) batch.event(id, "ANOMALY:late_return", null, end.plusMinutes(15));
                batch.event(id, "RETURNED", null, ret);
                status = "RETURNED";
                last = ret;
                maybeLateEvent(batch, rnd, id, ret);
            }
        }
        batch.booking(id, day, "SEED-BK-" + day.format(DAY) + "-" + batch.nextSeq(), box.game().name(), status,
                start, end, created, deleted, pin, v, box.game().dbId(), box.serial(), last);
        return later(last, start).plusMinutes(10 + rnd.nextInt(36));
    }

    // ── event booking: เหมาช่วงเวลา หยิบ-คืนได้หลายรอบ ทีละกล่อง ─────────────
    private LocalDateTime seedEventBooking(Batch batch, Random rnd, VaultSeed v, String sessionItem,
                                           LocalDate day, LocalDateTime cursor) {
        LocalDateTime start = cursor.plusMinutes(5 + rnd.nextInt(26));
        LocalDateTime end = start.plusMinutes(120 + rnd.nextInt(3) * 30L);
        LocalDateTime created = start.minusMinutes(10 + rnd.nextInt(50));
        String id = uuid();
        String pin = String.format("%06d", rnd.nextInt(1_000_000));
        String status;
        LocalDateTime deleted = null;
        LocalDateTime last = start;

        batch.event(id, "PENDING", "PIN: " + pin, created);
        if (roll(rnd, 0.02)) {
            status = "PENDING";
        } else {
            batch.event(id, "CONFIRMED", null, created.plusSeconds(2));
            if (roll(rnd, 0.03)) {
                LocalDateTime at = start.plusMinutes(rnd.nextInt(20));
                batch.event(id, "CANCELLED", "Session ended by admin (booking/end sent)", at);
                status = "CANCELLED";
                deleted = at;
                last = at;
            } else {
                LocalDateTime t = start.plusMinutes(2 + rnd.nextInt(9));
                boolean started = false;
                String openTag = null;
                int maxCycles = 2 + rnd.nextInt(5);
                for (int c = 0; c < maxCycles && t.isBefore(end); c++) {
                    Box box = v.boxes().get(rnd.nextInt(v.boxes().size()));
                    String note = box.game().name() + " (" + box.serial() + ") epc=" + box.tag();
                    batch.event(id, "MOVE:PICKED_UP", note, t);
                    if (!started) {
                        batch.event(id, "ACTIVE", "Session started", t);
                        started = true;
                    }
                    if (c == maxCycles - 1 && roll(rnd, 0.04)) {
                        openTag = box.tag();   // หยิบแล้วไม่มี event คืน — MOVE ที่ปิดไม่ลง
                        break;
                    }
                    long dur = roll(rnd, 0.04) ? 1 + rnd.nextInt(4) : 12 + rnd.nextInt(45);
                    LocalDateTime ret = t.plusMinutes(dur);
                    batch.event(id, "MOVE:RETURNED", note, ret);
                    last = ret;
                    t = ret.plusMinutes(2 + rnd.nextInt(11));
                }
                if (roll(rnd, 0.05)) batch.event(id, "ANOMALY:wrong_tag", "wrong tag detected", start.plusMinutes(3 + rnd.nextInt(60)));
                maybeLockdown(batch, rnd, id, start.plusMinutes(10));

                if (openTag != null) {
                    batch.event(id, "OVERDUE", "หมดเวลาแล้วยังไม่คืน 1 กล่อง: " + openTag, end);
                    status = "OVERDUE";
                    last = end;
                } else {
                    if (last.isAfter(end.plusMinutes(15))) batch.event(id, "ANOMALY:late_return", null, end.plusMinutes(15));
                    LocalDateTime closed = later(last, end);
                    batch.event(id, "RETURNED", last.isAfter(end)
                            ? "All items returned (after time end)" : "Session expired — all items returned", closed);
                    status = "RETURNED";
                    maybeLateEvent(batch, rnd, id, closed);
                    last = closed;
                }
            }
        }
        batch.booking(id, day, "SEED-EV-" + day.format(DAY) + "-" + batch.nextSeq(), "Event session", status,
                start, end, created, deleted, pin, v, sessionItem, v.sessionSerial(), last);
        return later(last, end).plusMinutes(10 + rnd.nextInt(31));
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static long gameDuration(Random rnd, Game g) {
        if (roll(rnd, 0.04)) return 1 + rnd.nextInt(4);                     // เปิดดูแล้วคืน
        int play = g.playMin() + rnd.nextInt(g.playMax() - g.playMin() + 1);
        if (g.quitsEarly() && roll(rnd, 0.45)) {
            return Math.max(6, Math.round(play * (0.25 + rnd.nextDouble() * 0.25)));   // หยิบไปแล้วเล่นไม่จบ
        }
        return Math.round(play * (0.9 + rnd.nextDouble() * 0.9)) + 10;      // + เวลาอ่านกฎ/จัดเกม
    }

    private void maybeLockdown(Batch batch, Random rnd, String bookingId, LocalDateTime around) {
        if (roll(rnd, 0.015)) {
            LocalDateTime at = around.plusMinutes(rnd.nextInt(20));
            batch.event(bookingId, "ANOMALY:lockdown_triggered", "lockdown", at);
            batch.event(bookingId, "ANOMALY:lockdown_cleared", "lockdown cleared", at.plusMinutes(8 + rnd.nextInt(13)));
        }
        if (roll(rnd, 0.007)) {
            batch.event(bookingId, "ANOMALY:force_unlock", "force unlock by admin", around.plusMinutes(5));
        }
    }

    private void maybeLateEvent(Batch batch, Random rnd, String bookingId, LocalDateTime closedAt) {
        if (roll(rnd, 0.025)) {
            batch.event(bookingId, "LATE_EVENT:booking_returned", "Event received after booking closed",
                    closedAt.plusMinutes(20 + rnd.nextInt(580)));
        }
    }

    private String sessionItemDbId(long created) {
        String sessionItemId = sessionItemIds.isEmpty() ? "SESSION-PASS" : sessionItemIds.get(0);
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM items WHERE item_id = ? AND deleted_at IS NULL", String.class, sessionItemId);
        if (!ids.isEmpty()) return ids.get(0);
        // ไม่มี session pass — สร้างด้วย item_id จริงตาม config ไม่งั้น isEvent() จะไม่รู้จัก booking เหล่านี้
        String id = uuid();
        jdbc.update("INSERT INTO items (id, item_id, item_name_en, item_name_th, item_image_url, item_status, remark_1, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                id, sessionItemId, "Session Pass", "บัตรเหมาช่วงเวลา", "https://placehold.co/400", "ENABLE", "SEED", created, created);
        return id;
    }

    private String insertAgent(String agentId, String name, String province, long created) {
        String id = uuid();
        jdbc.update("INSERT INTO agents (id, agent_id, agent_name, agent_status, province_code, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?)", id, agentId, name, "ENABLE", province, created, created);
        return id;
    }

    private static double weekdayFactor(DayOfWeek d) {
        return switch (d) {
            case SATURDAY -> 1.55;
            case SUNDAY -> 1.40;
            case FRIDAY -> 1.15;
            default -> 0.80;
        };
    }

    private static boolean roll(Random rnd, double p) { return rnd.nextDouble() < p; }
    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private long millis(LocalDateTime t) { return t.atZone(zone).toInstant().toEpochMilli(); }

    /** แถวที่รอเขียนแบบ batch */
    private final class Batch {
        final List<Object[]> bookings = new ArrayList<>();
        final List<Object[]> events = new ArrayList<>();
        private int seq = 0;

        String nextSeq() { return String.format("%04d", ++seq); }

        void event(String bookingDbId, String status, String note, LocalDateTime at) {
            events.add(new Object[]{uuid(), note, millis(at), status, bookingDbId});
        }

        void booking(String id, LocalDate day, String bookingId, String name, String status,
                     LocalDateTime start, LocalDateTime end, LocalDateTime created, LocalDateTime deleted,
                     String pin, VaultSeed v, String itemDbId, String serial, LocalDateTime updated) {
            bookings.add(new Object[]{
                id, day.toString(), bookingId, name, status, millis(end), millis(start), millis(created),
                deleted != null ? millis(deleted) : null, pin, uuid(), millis(updated),
                v.agentDbId(), itemDbId, v.dbId(), serial
            });
        }
    }
}
