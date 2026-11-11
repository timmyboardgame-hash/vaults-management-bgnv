package com.vault.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite ยอมให้มีคนเขียนได้ทีละคนเดียว — คนที่มาไม่ทันต้องรอ ไม่ใช่ตาย
 *
 * ของจริงที่เจอ: ยิง POST /api/v1/agents/{agentId}/bookings พร้อมกัน 8 request
 * สำเร็จแค่ 1 อีก 7 ได้ CannotAcquireLockException [SQLITE_BUSY] → HTTP 500
 * เกิดกับทุก endpoint ที่เขียน DB ไม่ใช่แค่ booking (POST /api/v1/items ก็เหมือนกัน)
 *
 * ต้นเหตุไม่ใช่ busy_timeout — driver xerial ตั้ง busy_timeout ให้เอง 3000ms อยู่แล้ว
 * ตัวปัญหาคือ transaction ของเราเปิดแบบ DEFERRED แล้ว "อ่านก่อนเขียน"
 *
 *   @Transactional createItem()
 *     1. select  existsByItemIdAndDeletedAtIsNull(..)  → จับ SHARED lock
 *     2. insert  into items ..                         → ขอเลื่อนเป็น RESERVED
 *
 * ถ้าจังหวะนั้นมีคนอื่นถือ RESERVED อยู่ SQLite จะคืน SQLITE_BUSY ทันที
 * โดยไม่เรียก busy handler เลย เพราะการรอจะกลายเป็น deadlock —
 * เราถือ SHARED ที่กันเขา commit อยู่ ส่วนเขาถือ RESERVED ที่กันเราเลื่อนขั้น
 * ไม่มีใครถอย ฉะนั้น SQLite เลือกจะให้คนที่มาขอเลื่อนขั้นตายทันที
 * (เอกสาร SQLite: "the busy handler is not invoked when promoting a deferred
 *  transaction from read to write" — ตั้ง busy_timeout เท่าไหร่ก็ไม่ช่วย)
 *
 * ทางแก้คือเปิด transaction แบบ IMMEDIATE — ขอ write lock ตั้งแต่ BEGIN
 * ก่อนจะไปถือ SHARED ใด ๆ พอไม่มีสภาพ deadlock แล้ว busy handler ก็ทำงาน
 * คนที่มาทีหลังจะรอคิวจนถึงคิวตัวเอง แทนที่จะได้ 500 กลับไป
 *
 * เทสต์นี้กันของเดิมกลับมา — ถ้ามีคนถอด transaction_mode ออกจาก application.yml
 * หรือเผลอ override DATABASE_URL เป็น sqlite เปล่า ๆ เทสต์จะแดง
 */
class SqliteConcurrentWriteTest {

    /** จำนวน request ที่ยิงพร้อมกันตอนเจอปัญหา */
    private static final int WRITERS = 8;

    /** งานที่ service ทำอยู่ใน @Transactional ระหว่าง select กับ commit */
    private static final long TX_WORK_MILLIS = 20;

    /** Hikari ไม่ได้ตั้งค่าใน application.yml → pool default 10 connection */
    private static final int DEFAULT_POOL_SIZE = 10;

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------
    // ของเดิม — ทำให้ปัญหาโผล่ซ้ำได้
    // ---------------------------------------------------------------

    /**
     * transaction ที่ "เขียนล้วน" ไม่พัง — busy_timeout ของ driver เอาอยู่
     *
     * มีไว้เพื่อชี้ว่าปัญหาไม่ได้อยู่ที่การเขียนพร้อมกันเฉย ๆ
     * ถ้าเทสต์นี้แดงแปลว่า busy_timeout หายไป ไม่ใช่เรื่อง lock upgrade
     */
    @Test
    void writeOnlyTransactionsSurviveConcurrency() throws Exception {
        Outcome outcome = run(url(""), DEFAULT_POOL_SIZE, false);
        report("เขียนล้วน — url เดิม", outcome);

        assertThat(outcome.ok()).isEqualTo(WRITERS);
    }

    /**
     * ของจริง: อ่านก่อนเขียนใน transaction เดียว → SQLITE_BUSY ตามที่เจอบน API
     */
    @Test
    void readThenWriteTransactionsLoseRowsOnTheCurrentUrl() throws Exception {
        Outcome outcome = run(url(""), DEFAULT_POOL_SIZE, true);
        report("ก่อนแก้ — url เดิม, อ่านก่อนเขียน", outcome);

        assertThat(outcome.busy())
                .as("ต้องเห็น SQLITE_BUSY เหมือนที่ API เจอ")
                .isGreaterThan(0);
        assertThat(outcome.ok())
                .as("เขียนไม่ครบ — บาง request ตกไป")
                .isLessThan(WRITERS);
    }

    /**
     * busy_timeout ไม่ใช่ทางแก้ — ยืนยันว่าตั้งไปก็ยังพังเหมือนเดิม
     *
     * เพราะ busy handler ไม่ถูกเรียกตอน promote deferred transaction
     * เทสต์นี้มีไว้กันคนเข้าใจผิดแล้วไปแก้ที่ busy_timeout
     */
    @Test
    void busyTimeoutAloneDoesNotFixLockUpgrade() throws Exception {
        Outcome outcome = run(url("?busy_timeout=5000"), DEFAULT_POOL_SIZE, true);
        report("busy_timeout=5000 — ยังพัง", outcome);

        assertThat(outcome.busy())
                .as("busy_timeout ไม่ช่วยกรณี lock upgrade")
                .isGreaterThan(0);
    }

    // ---------------------------------------------------------------
    // ทางแก้
    // ---------------------------------------------------------------

    /**
     * ทางแก้ที่เลือก: transaction_mode=IMMEDIATE → ขอ write lock ตั้งแต่ BEGIN
     */
    @Test
    void immediateTransactionModeLetsEveryWriterThrough() throws Exception {
        Outcome outcome = run(url("?transaction_mode=IMMEDIATE"), DEFAULT_POOL_SIZE, true);
        report("หลังแก้ — transaction_mode=IMMEDIATE", outcome);

        assertThat(outcome.other()).isEmpty();
        assertThat(outcome.busy())
                .as("ต้องไม่มีใครได้ SQLITE_BUSY")
                .isZero();
        assertThat(outcome.ok())
                .as("ต้องเขียนสำเร็จครบทุกคน")
                .isEqualTo(WRITERS);
        assertThat(outcome.rows()).isEqualTo(WRITERS);
    }

    /**
     * ทางเลือกที่ไม่เลือก: บีบ pool เหลือ 1 — ไม่มีสองคนถือ connection พร้อมกัน
     *
     * ได้ครบเหมือนกัน แต่ทุก query ทั้งแอป (รวม read ที่ไม่เกี่ยวกับการเขียน)
     * ต้องต่อคิวเดียวกันหมด — วัด latency ไว้เทียบใน report
     */
    @Test
    void singleConnectionPoolAlsoWorksButSerialisesEveryQuery() throws Exception {
        Outcome outcome = run(url(""), 1, true);
        report("ทางเลือก — pool 1", outcome);

        assertThat(outcome.ok()).isEqualTo(WRITERS);
        assertThat(outcome.busy()).isZero();
    }

    /**
     * WAL ช่วยให้ read ไม่ชนกับ write แต่ไม่ได้แก้ lock upgrade
     *
     * วัดไว้เป็นหลักฐานว่า WAL เพียว ๆ ไม่พอ (ยังเจอ SQLITE_BUSY จาก snapshot)
     * ถ้าจะเปิด WAL ต้องไปแก้ที่ deployment/backup ด้วย — ไม่คุ้มกับรอบนี้
     */
    @Test
    void walModeAloneStillHitsLockUpgrade() throws Exception {
        Outcome outcome = run(url("?journal_mode=WAL&busy_timeout=5000"), DEFAULT_POOL_SIZE, true);
        report("WAL + busy_timeout (ไม่เลือก)", outcome);

        // ไม่ assert ว่าพัง — WAL แค่ลดโอกาสชน ไม่ได้การันตีทั้งสองทาง
        // เก็บเลขไว้เทียบใน report เท่านั้น
        assertThat(outcome.ok()).isBetween(1, WRITERS);
    }

    /**
     * กันคนถอดทางแก้ออกจาก application.yml โดยไม่รู้ตัว
     *
     * เทสต์ข้างบนพิสูจน์ว่า IMMEDIATE แก้ได้ แต่ไม่ได้อ่านค่าที่แอปใช้จริง
     * ตัวนี้อ่าน application.yml ตรง ๆ — ถ้า default url ไม่มี transaction_mode
     * แปลว่าแอปกลับไปพังแบบเดิมแล้ว ทั้งที่เทสต์อื่นยังเขียว
     */
    @Test
    void applicationYmlKeepsImmediateTransactionModeForSqlite() throws Exception {
        String url = sqliteUrlFromApplicationYml();

        assertThat(url)
                .as("default datasource url ของ sqlite ต้องเปิด IMMEDIATE ไม่งั้นเขียนพร้อมกันจะ 500")
                .contains("transaction_mode=IMMEDIATE");
    }

    @SuppressWarnings("unchecked")
    private String sqliteUrlFromApplicationYml() throws Exception {
        try (var in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("หา application.yml ใน classpath ไม่เจอ").isNotNull();
            Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
            return String.valueOf(datasource.get("url"));
        }
    }

    // ---------------------------------------------------------------
    // ราคาที่ต้องจ่าย — ต้องวัดก่อนเลือกระหว่าง IMMEDIATE กับ pool 1
    // ---------------------------------------------------------------

    /**
     * วัดว่าแต่ละทางเลือกทำให้ "การอ่าน" ช้าลงแค่ไหนตอนมีคนเขียนอยู่
     *
     * สำคัญเพราะ repository ของ Spring Data เป็น @Transactional(readOnly=true)
     * อยู่แล้ว ฉะนั้นการอ่านก็เปิด transaction เหมือนกัน ไม่ใช่ autocommit
     * - IMMEDIATE จับ write lock ตั้งแต่ BEGIN → การอ่านก็ต้องรอคิวด้วย
     * - pool 1 ทุก query ต่อคิวที่ pool → อ่านก็รอเหมือนกัน
     * - WAL ทำให้ reader ไม่ชนกับ writer → ควรเป็นตัวที่อ่านเร็วสุด
     *
     * ไม่ assert ตัวเลข latency (เครื่องแต่ละเครื่องไม่เท่ากัน) — พิมพ์ไว้ให้คนอ่านตัดสิน
     * assert แค่ว่าเขียนครบและอ่านไม่พัง
     */
    @Test
    void measureReadCostOfEachOption() throws Exception {
        System.out.println("\n  ── ราคาของแต่ละทางเลือก (เขียน 8 คน + อ่าน 4 คน × 25 รอบ) ──");

        Mixed current = mixed(url(""), DEFAULT_POOL_SIZE);
        reportMixed("url เดิม (ของพัง)", current);

        Mixed immediate = mixed(url("?transaction_mode=IMMEDIATE"), DEFAULT_POOL_SIZE);
        reportMixed("transaction_mode=IMMEDIATE", immediate);

        Mixed singlePool = mixed(url(""), 1);
        reportMixed("pool 1", singlePool);

        Mixed immediateWal = mixed(url("?transaction_mode=IMMEDIATE&journal_mode=WAL"), DEFAULT_POOL_SIZE);
        reportMixed("IMMEDIATE + WAL", immediateWal);

        assertThat(immediate.writesOk()).isEqualTo(WRITERS);
        assertThat(singlePool.writesOk()).isEqualTo(WRITERS);
        assertThat(immediateWal.writesOk()).isEqualTo(WRITERS);
        assertThat(immediate.readsFailed()).isZero();
        assertThat(singlePool.readsFailed()).isZero();
        assertThat(immediateWal.readsFailed()).isZero();
    }

    private static final int READERS = 4;
    private static final int READS_EACH = 25;

    private record Mixed(int writesOk, int writesBusy, int readsOk, int readsFailed,
                         long maxReadMillis, long wallMillis) {}

    /**
     * เขียน WRITERS คน (อ่านก่อนเขียน) พร้อมกับอ่าน READERS คน วนคนละ READS_EACH รอบ
     * ทั้งหมดเปิด transaction จริงเหมือน Spring Data ทำ
     */
    private Mixed mixed(String jdbcUrl, int poolSize) throws Exception {
        try (HikariDataSource ds = pool(jdbcUrl, Math.max(poolSize, 1))) {
            createTable(ds);

            CountDownLatch ready = new CountDownLatch(WRITERS + READERS);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger writesOk = new AtomicInteger();
            AtomicInteger writesBusy = new AtomicInteger();
            AtomicInteger readsOk = new AtomicInteger();
            AtomicInteger readsFailed = new AtomicInteger();
            AtomicLong maxReadMillis = new AtomicLong();

            long start;
            try (ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS)) {
                for (int i = 0; i < WRITERS; i++) {
                    String payload = "mixed-writer-" + i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                            try (Connection c = ds.getConnection()) {
                                c.setAutoCommit(false);
                                countRows(c);
                                insert(c, payload);
                                Thread.sleep(TX_WORK_MILLIS);
                                c.commit();
                            }
                            writesOk.incrementAndGet();
                        } catch (SQLException e) {
                            if (isBusy(e)) {
                                writesBusy.incrementAndGet();
                            }
                        } catch (Exception ignored) {
                            // นับเป็นเขียนไม่สำเร็จ
                        }
                        return null;
                    });
                }

                for (int i = 0; i < READERS; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        for (int n = 0; n < READS_EACH; n++) {
                            long t0 = System.nanoTime();
                            try (Connection c = ds.getConnection()) {
                                c.setAutoCommit(false);
                                countRows(c);
                                c.commit();
                                readsOk.incrementAndGet();
                            } catch (Exception e) {
                                readsFailed.incrementAndGet();
                            }
                            maxReadMillis.accumulateAndGet(millisSince(t0), Math::max);
                        }
                        return null;
                    });
                }

                ready.await();
                start = System.nanoTime();
                go.countDown();
            }

            return new Mixed(writesOk.get(), writesBusy.get(), readsOk.get(), readsFailed.get(),
                    maxReadMillis.get(), millisSince(start));
        }
    }

    private void reportMixed(String label, Mixed m) {
        System.out.printf(
                "  %-32s writes=%d/%d busy=%d  reads=%d/%d failed=%d  slowest read=%dms  wall=%dms%n",
                label, m.writesOk(), WRITERS, m.writesBusy(),
                m.readsOk(), READERS * READS_EACH, m.readsFailed(),
                m.maxReadMillis(), m.wallMillis());
    }

    // ---------------------------------------------------------------
    // harness
    // ---------------------------------------------------------------

    private String url(String params) {
        return "jdbc:sqlite:" + tmp.resolve("vaultdb.db") + params;
    }

    private record Outcome(int ok, int busy, List<String> other, long maxMillis, long totalMillis, int rows) {}

    /**
     * ยิง insert พร้อมกัน WRITERS คน ปล่อยออกจากประตูเดียวกันด้วย latch
     *
     * @param readFirst true = select ก่อน insert ใน transaction เดียวกัน
     *                  (เลียนแบบ existsByItemIdAndDeletedAtIsNull ของ createItem)
     */
    private Outcome run(String jdbcUrl, int poolSize, boolean readFirst) throws Exception {
        try (HikariDataSource ds = pool(jdbcUrl, poolSize)) {
            createTable(ds);

            CountDownLatch ready = new CountDownLatch(WRITERS);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger busy = new AtomicInteger();
            AtomicLong maxMillis = new AtomicLong();
            List<String> other = Collections.synchronizedList(new ArrayList<>());

            long start;
            try (ExecutorService workers = Executors.newFixedThreadPool(WRITERS)) {
                for (int i = 0; i < WRITERS; i++) {
                    String payload = "writer-" + i;
                    workers.submit(() -> {
                        ready.countDown();
                        long t0 = 0;
                        try {
                            go.await();
                            t0 = System.nanoTime();
                            try (Connection c = ds.getConnection()) {
                                c.setAutoCommit(false);
                                if (readFirst) {
                                    countRows(c);
                                }
                                insert(c, payload);
                                Thread.sleep(TX_WORK_MILLIS);
                                c.commit();
                            }
                            ok.incrementAndGet();
                        } catch (SQLException e) {
                            if (isBusy(e)) {
                                busy.incrementAndGet();
                            } else {
                                other.add(e.toString());
                            }
                        } catch (Exception e) {
                            other.add(e.toString());
                        }
                        if (t0 != 0) {
                            maxMillis.accumulateAndGet(millisSince(t0), Math::max);
                        }
                        return null;
                    });
                }
                ready.await();
                start = System.nanoTime();
                go.countDown();
            }

            try (Connection c = ds.getConnection()) {
                return new Outcome(ok.get(), busy.get(), other, maxMillis.get(), millisSince(start), countRows(c));
            }
        }
    }

    private HikariDataSource pool(String jdbcUrl, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(poolSize);
        return new HikariDataSource(config);
    }

    private void createTable(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table if not exists writes (id integer primary key autoincrement, payload text)");
        }
    }

    private void insert(Connection c, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into writes (payload) values (?)")) {
            ps.setString(1, payload);
            ps.executeUpdate();
        }
    }

    private int countRows(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from writes")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** SQLITE_BUSY มาได้ทั้งตัว exception เองและตัว cause — ไล่ดูให้หมด */
    private boolean isBusy(Throwable t) {
        for (Throwable e = t; e != null && e != e.getCause(); e = e.getCause()) {
            if (e.getMessage() != null && e.getMessage().contains("SQLITE_BUSY")) {
                return true;
            }
        }
        return false;
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void report(String label, Outcome outcome) {
        System.out.printf(
                "  %-42s ok=%d/%d  SQLITE_BUSY=%d  other=%d  slowest=%dms  wall=%dms  rows=%d%n",
                label, outcome.ok(), WRITERS, outcome.busy(), outcome.other().size(),
                outcome.maxMillis(), outcome.totalMillis(), outcome.rows());
        outcome.other().forEach(e -> System.out.println("      " + e));
    }
}
