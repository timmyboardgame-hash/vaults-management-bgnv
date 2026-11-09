package com.vault.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * ทุกคำสั่งวิ่งบน connection เดียวใน transaction เดียว — SQLite รองรับ DDL ใน transaction
 * ถ้าแยกคำสั่งกันแบบ autocommit แล้วแอปตายกลางทางระหว่าง DROP กับ RENAME
 * ข้อมูลจะไปค้างอยู่ในตารางชื่อ vault_items_migrating แล้วต้องตาม rename คืนด้วยมือ
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

    /** ตารางชั่วคราวระหว่างย้ายข้อมูล — มีอยู่แค่ภายใน transaction เดียว */
    private static final String STAGING = "vault_items_migrating";

    private static final int SKIPPED = -1;

    private final JdbcTemplate jdbc;

    public VaultItemVaultNullableMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isSqlite()) return;  // Postgres แก้ด้วย ALTER COLUMN ได้ตรง ๆ ไม่ต้องรื้อตาราง

        try {
            int rows = jdbc.execute((ConnectionCallback<Integer>) this::rebuildIfNeeded);
            if (rows != SKIPPED) {
                log.info("[MIGRATION] Done — vault_items now allows copies without a vault ({} rows kept)", rows);
            }
        } catch (Exception e) {
            // ไม่ล้ม startup ทั้งแอป — booking กับหน้าอื่นยังทำงานได้ปกติ
            // ที่เสียคือลงทะเบียนกล่องที่ยังไม่เข้าตู้ ซึ่งจะ error ให้เห็นชัดเวลาเรียกจริง
            // transaction ถูก rollback แล้ว ตารางเดิมยังอยู่ครบ
            log.error("[MIGRATION] Rebuilding vault_items FAILED — rolled back, the old table is untouched. "
                    + "Registering a copy that is not in a vault yet will keep failing until this is fixed: {}",
                    e.getMessage(), e);
        }
    }

    /**
     * ย้ายข้อมูลทั้งหมดใน transaction เดียว — สำเร็จทั้งก้อน หรือไม่เกิดอะไรเลย
     *
     * @return จำนวนแถวที่ย้ายมา หรือ SKIPPED ถ้าไม่ต้องทำอะไร
     */
    private int rebuildIfNeeded(Connection con) throws java.sql.SQLException {
        try (Statement st = con.createStatement()) {
            if (!vaultIdIsNotNull(st)) return SKIPPED;  // ผ่านรอบนี้ไปแล้ว

            String ddl = tableDdl(st);
            if (ddl == null) return SKIPPED;

            Matcher m = VAULT_ID_NOT_NULL.matcher(ddl);
            if (!m.find()) {
                log.warn("[MIGRATION] vault_items.vault_id is NOT NULL but the phrase was not found in the DDL — "
                        + "skipping. Copies without a vault cannot be stored until this is fixed by hand.");
                return SKIPPED;
            }
            String newDdl = ddl.replaceFirst("(?i)\\bvault_items\\b", STAGING);
            newDdl = VAULT_ID_NOT_NULL.matcher(newDdl).replaceFirst(Matcher.quoteReplacement(m.group(1)));

            // คอลัมน์ต้องเรียงตามตารางจริง ไม่ใช่เดาจาก entity — ตารางเก่าอาจมีคอลัมน์ที่โค้ดไม่รู้จักแล้ว
            String columns = String.join(", ", columnNames(st));

            log.info("[MIGRATION] Rebuilding vault_items so vault_id accepts NULL (columns: {})", columns);

            // PRAGMA นี้ต้องสั่งก่อนเปิด transaction — สั่งข้างในจะกลายเป็น no-op เงียบ ๆ
            st.execute("PRAGMA foreign_keys=OFF");

            boolean autoCommitBefore = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                st.executeUpdate("DROP TABLE IF EXISTS " + STAGING);
                st.execute(newDdl);
                st.executeUpdate("INSERT INTO " + STAGING + " (" + columns + ") "
                        + "SELECT " + columns + " FROM vault_items");
                st.executeUpdate("DROP TABLE vault_items");
                st.executeUpdate("ALTER TABLE " + STAGING + " RENAME TO vault_items");
                // index หายไปพร้อมตารางเก่า ต้องสร้างคืนในก้อนเดียวกัน
                st.execute("CREATE INDEX IF NOT EXISTS idx_vault_items_serial ON vault_items (serial_number)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_vault_items_rfid_tag ON vault_items (rfid_tag)");

                int rows = countRows(st);
                con.commit();
                return rows;
            } catch (java.sql.SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(autoCommitBefore);
                // คืนค่าให้ connection ก่อนปล่อยกลับ pool — ตัวถัดไปที่หยิบไปใช้ต้องได้ค่าปกติ
                st.execute("PRAGMA foreign_keys=ON");
            }
        }
    }

    private boolean isSqlite() {
        try {
            String url = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite");
        } catch (Exception e) {
            log.warn("[MIGRATION] Could not read the JDBC url — skipping the vault_items rebuild: {}", e.getMessage());
            return false;
        }
    }

    private static boolean vaultIdIsNotNull(Statement st) throws java.sql.SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(vault_items)")) {
            while (rs.next()) {
                if ("vault_id".equalsIgnoreCase(rs.getString("name"))) {
                    return rs.getInt("notnull") == 1;
                }
            }
        }
        return false;  // ไม่มีตาราง หรือไม่มีคอลัมน์นี้ — ปล่อยให้ Hibernate สร้างเอง
    }

    private static String tableDdl(Statement st) throws java.sql.SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='vault_items'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> columnNames(Statement st) throws java.sql.SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(vault_items)")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private static int countRows(Statement st) throws java.sql.SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM vault_items")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
