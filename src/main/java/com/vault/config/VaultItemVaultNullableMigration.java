package com.vault.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ปล่อยให้ vault_items.vault_id เป็นค่าว่างได้ บน database ที่สร้างไว้ก่อนแล้ว
 *
 * ทำไมต้องมีไฟล์นี้: โปรเจกต์ใช้ ddl-auto=update ซึ่งเพิ่มคอลัมน์ให้ แต่ไม่เคยถอด
 * NOT NULL ที่ติดมากับตารางเดิม · และ SQLite ไม่มี ALTER COLUMN ให้แก้ทีหลัง
 * ตารางที่สร้างตอน vault_id ยังบังคับว่าต้องมีค่า จึง insert กล่องที่ยังไม่เข้าตู้ไม่ได้
 * — ขึ้น production แล้วจะเป็น 500 ทุกครั้งที่แอปร้านลงทะเบียนกล่อง
 *
 * วิธีแก้แบบ SQLite คือสร้างตารางใหม่แล้วย้ายข้อมูล · ที่นี่ใช้ DDL เดิมจาก sqlite_master
 * แล้วถอดคำว่า not null ออกจากคอลัมน์เดียว ไม่เขียน schema ซ้ำเอง จะได้ไม่หลุดกันคนละทาง
 *
 * รันซ้ำได้ — ถ้าคอลัมน์ยอม null อยู่แล้วจะข้ามไปเลย · ลบไฟล์นี้ได้เมื่อทุก environment
 * ผ่านรอบนี้ไปแล้ว หรือเมื่อย้ายไปใช้ migration tool จริง
 */
@Component
public class VaultItemVaultNullableMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VaultItemVaultNullableMigration.class);

    private static final Pattern VAULT_ID_NOT_NULL =
            Pattern.compile("(\\bvault_id\\b\\s+\\w+(?:\\s*\\(\\s*\\d+\\s*\\))?)\\s+not\\s+null",
                            Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;

    public VaultItemVaultNullableMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isSqlite()) return;          // Postgres แก้ด้วย ALTER COLUMN ได้ตรง ๆ ไม่ต้องรื้อตาราง
        if (!vaultIdIsNotNull()) return;  // ผ่านรอบนี้ไปแล้ว

        String ddl = jdbc.queryForObject(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='vault_items'", String.class);
        if (ddl == null) return;

        Matcher m = VAULT_ID_NOT_NULL.matcher(ddl);
        if (!m.find()) {
            log.warn("[MIGRATION] vault_items.vault_id is NOT NULL but the phrase was not found in the DDL — "
                    + "skipping. Copies without a vault cannot be stored until this is fixed by hand.");
            return;
        }
        String newDdl = ddl.replaceFirst("(?i)\\bvault_items\\b", "vault_items_migrating");
        newDdl = VAULT_ID_NOT_NULL.matcher(newDdl).replaceFirst(Matcher.quoteReplacement(m.group(1)));

        // คอลัมน์ต้องเรียงตามตารางจริง ไม่ใช่เดาจาก entity — ตารางเก่าอาจมีคอลัมน์ที่โค้ดไม่รู้จักแล้ว
        String columns = String.join(", ", columnNames());

        log.info("[MIGRATION] Rebuilding vault_items so vault_id accepts NULL (columns: {})", columns);
        jdbc.execute("PRAGMA foreign_keys=OFF");
        try {
            jdbc.execute("DROP TABLE IF EXISTS vault_items_migrating");
            jdbc.execute(newDdl);
            jdbc.execute("INSERT INTO vault_items_migrating (" + columns + ") SELECT " + columns + " FROM vault_items");
            jdbc.execute("DROP TABLE vault_items");
            jdbc.execute("ALTER TABLE vault_items_migrating RENAME TO vault_items");
            // index หายไปพร้อมตารางเก่า — ddl-auto=update จะไม่สร้างให้จนกว่าจะ restart รอบถัดไป
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vault_items_serial ON vault_items (serial_number)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vault_items_rfid_tag ON vault_items (rfid_tag)");
        } finally {
            jdbc.execute("PRAGMA foreign_keys=ON");
        }

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM vault_items", Integer.class);
        log.info("[MIGRATION] Done — vault_items now allows copies without a vault ({} rows kept)", rows);
    }

    private boolean isSqlite() {
        try {
            String url = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c ->
                    c.getMetaData().getURL());
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite");
        } catch (Exception e) {
            log.warn("[MIGRATION] Could not read the JDBC url — skipping the vault_items rebuild: {}", e.getMessage());
            return false;
        }
    }

    private boolean vaultIdIsNotNull() {
        List<Map<String, Object>> cols = jdbc.queryForList("PRAGMA table_info(vault_items)");
        return cols.stream()
                .filter(c -> "vault_id".equalsIgnoreCase(String.valueOf(c.get("name"))))
                .anyMatch(c -> "1".equals(String.valueOf(c.get("notnull"))));
    }

    private List<String> columnNames() {
        return jdbc.queryForList("PRAGMA table_info(vault_items)").stream()
                .map(c -> String.valueOf(c.get("name")))
                .toList();
    }
}
