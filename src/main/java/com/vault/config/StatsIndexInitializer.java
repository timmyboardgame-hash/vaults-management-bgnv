package com.vault.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * สร้าง unique index ของตารางสรุปรายวันตอนแอปเริ่มทำงาน
 *
 * entity ประกาศ uniqueConstraints ไว้แล้ว แต่ ddl-auto: update บน SQLite ไม่ได้สร้างให้
 * ถ้าไม่มี index นี้ เมื่องานสรุปรายคืนกับปุ่ม backfill ทำงานวันเดียวกันพร้อมกัน
 * จะได้แถวซ้ำโดยไม่มีอะไรฟ้อง แล้วยอดบนหน้าจะกลายเป็นสองเท่าแบบเงียบ ๆ
 *
 * ทำงานทุกครั้งที่สตาร์ต แต่ IF NOT EXISTS ทำให้รันซ้ำไม่มีผลอะไร
 * ล้างแถวซ้ำที่อาจค้างอยู่ก่อนเสมอ ไม่งั้นคำสั่งสร้าง index จะไม่ผ่าน — เก็บแถวที่คำนวณล่าสุดไว้
 *
 * ข้อจำกัดของ SQLite: NULL ถือว่าไม่ซ้ำกันเอง แถว daily_item_stats ที่ serial_number เป็น NULL
 * จึงไม่ถูก index นี้คุ้มครอง (ของจริงกล่องทุกใบมี serial)
 */
@Component
public class StatsIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StatsIndexInitializer.class);

    private record Index(String table, String name, String columns) {}

    private static final List<Index> INDEXES = List.of(
        new Index("daily_vault_stats", "uk_daily_vault", "stat_date, vault_id"),
        new Index("daily_item_stats", "uk_daily_item", "stat_date, vault_id, item_id, serial_number")
    );

    private final JdbcTemplate jdbc;

    public StatsIndexInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Index ix : INDEXES) {
            try {
                int removed = jdbc.update("DELETE FROM " + ix.table() + " WHERE rowid NOT IN " +
                        "(SELECT MAX(rowid) FROM " + ix.table() + " GROUP BY " + ix.columns() + ")");
                if (removed > 0) {
                    log.warn("[STATS] {} มีแถวซ้ำ {} แถว — ลบแถวเก่าออกแล้ว เหลือแถวที่คำนวณล่าสุด",
                            ix.table(), removed);
                }
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + ix.name() +
                        " ON " + ix.table() + " (" + ix.columns() + ")");
            } catch (Exception e) {
                // ไม่ให้แอปล้ม — หน้าสถิติยังใช้ได้ แค่ไม่มีตัวกันแถวซ้ำ
                log.error("[STATS] สร้าง {} ไม่สำเร็จ: {}", ix.name(), e.getMessage());
            }
        }
    }
}
