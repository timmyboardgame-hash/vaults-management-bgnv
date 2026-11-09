package com.vault.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * รื้อตาราง vault_items สร้างใหม่ให้ vault_id ยอมค่าว่าง
 *
 * เทสนี้ใช้ไฟล์ SQLite จริง ไม่ใช่ mock เพราะสิ่งที่ต้องพิสูจน์คือพฤติกรรมของ SQLite เอง
 * (ถอด NOT NULL ไม่ได้ · DDL อยู่ใน transaction ได้ · index หายไปพร้อมตารางเก่า)
 *
 * migration นี้จะรันบนไฟล์ฐานข้อมูลจริงบน production ตอนแอปขึ้น ข้อมูลหายไม่ได้
 */
class VaultItemVaultNullableMigrationTest {

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private VaultItemVaultNullableMigration migration;

    /** ตารางหน้าตาเดียวกับของจริงก่อน migration — vault_id ยังบังคับว่าต้องมีค่า */
    private static final String OLD_SCHEMA = """
            CREATE TABLE vault_items (
                id varchar(255) not null,
                created_at timestamp not null,
                deleted_at timestamp,
                rfid_tag varchar(255),
                slot_id varchar(255),
                status varchar(255) not null,
                updated_at timestamp not null,
                item_id varchar(255) not null,
                vault_id varchar(255) not null, serial_number varchar(255), owner_agent_id varchar(255),
                primary key (id)
            )""";

    @BeforeEach
    void oldDatabaseWithRows() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tmp.resolve("test.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(ds);
        migration = new VaultItemVaultNullableMigration(jdbc);

        jdbc.execute(OLD_SCHEMA);
        jdbc.execute("CREATE INDEX idx_vault_items_serial ON vault_items (serial_number)");
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO vault_items (id, created_at, status, updated_at, item_id, vault_id, "
                    + "serial_number, rfid_tag) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?)",
                    "copy-" + i, "2026-11-01 10:00:00", "2026-11-01 10:00:00",
                    "item-db-1", "vault-db-1", "SN-" + i, "E2805641554C540000DE000" + i);
        }
    }

    private void migrate() {
        migration.run(null);
    }

    private boolean vaultIdAcceptsNull() {
        return jdbc.queryForList("PRAGMA table_info(vault_items)").stream()
                .filter(c -> "vault_id".equals(c.get("name")))
                .anyMatch(c -> "0".equals(String.valueOf(c.get("notnull"))));
    }

    private List<String> indexNames() {
        return jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='vault_items' "
                        + "AND name NOT LIKE 'sqlite_%'")
                .stream().map(r -> String.valueOf(r.get("name"))).toList();
    }

    @Test
    void keepsEveryRow() {
        migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_items", Integer.class)).isEqualTo(5);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, serial_number, rfid_tag, vault_id, item_id FROM vault_items ORDER BY id");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).containsEntry("serial_number", "SN-1")
                .containsEntry("rfid_tag", "E2805641554C540000DE0001")
                .containsEntry("vault_id", "vault-db-1")
                .containsEntry("item_id", "item-db-1");
    }

    /** เป้าหมายทั้งหมดของ migration — กล่องที่ยังไม่เข้าตู้ต้อง insert ได้ */
    @Test
    void copyWithoutVault_canBeStoredAfterwards() {
        migrate();

        assertThat(vaultIdAcceptsNull()).isTrue();
        jdbc.update("INSERT INTO vault_items (id, created_at, status, updated_at, item_id, vault_id, serial_number) "
                        + "VALUES ('copy-new', ?, 'ACTIVE', ?, 'item-db-1', NULL, 'SN-NEW')",
                "2026-11-10 10:00:00", "2026-11-10 10:00:00");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vault_items WHERE vault_id IS NULL", Integer.class)).isEqualTo(1);
    }

    /** index หายไปพร้อมตารางเก่า · ไม่สร้างคืนในก้อนเดียวกัน lookup จะช้าลงแบบไม่มีใครรู้ */
    @Test
    void recreatesBothIndexes() {
        migrate();

        assertThat(indexNames()).containsExactlyInAnyOrder(
                "idx_vault_items_serial", "idx_vault_items_rfid_tag");
    }

    @Test
    void leavesNoStagingTableBehind() {
        migrate();

        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE name='vault_items_migrating'")).isEmpty();
    }

    /** deploy ซ้ำ หรือแอป restart — ต้องไม่รื้อตารางอีกรอบ */
    @Test
    void runningAgainDoesNothing() {
        migrate();
        migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_items", Integer.class)).isEqualTo(5);
        assertThat(vaultIdAcceptsNull()).isTrue();
        assertThat(indexNames()).hasSize(2);
    }

    /** คอลัมน์ที่โค้ดไม่รู้จักแล้วก็ต้องย้ายมาด้วย — คัดจากตารางจริง ไม่ใช่จาก entity */
    @Test
    void carriesColumnsTheCodeNoLongerKnowsAbout() {
        jdbc.execute("ALTER TABLE vault_items ADD COLUMN legacy_note varchar(255)");
        jdbc.update("UPDATE vault_items SET legacy_note = 'เก็บไว้' WHERE id = 'copy-1'");

        migrate();

        assertThat(jdbc.queryForObject(
                "SELECT legacy_note FROM vault_items WHERE id = 'copy-1'", String.class)).isEqualTo("เก็บไว้");
    }

    /**
     * ถ้าล้มกลางทาง ตารางเดิมต้องอยู่ครบ
     *
     * บังคับให้ล้มด้วยการวาง view ชื่อเดียวกับตารางชั่วคราวไว้ — DROP TABLE ไม่ลบ view
     * แล้ว CREATE TABLE ชื่อซ้ำจะ error ตรงกลาง transaction พอดี
     */
    @Test
    void failureRollsBackAndKeepsTheOriginalTable() {
        jdbc.execute("CREATE VIEW vault_items_migrating AS SELECT 1 AS x");

        migrate();  // log error แล้วไปต่อ ไม่ล้ม startup ทั้งแอป

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_items", Integer.class)).isEqualTo(5);
        assertThat(vaultIdAcceptsNull()).isFalse();  // ยังเป็นตารางเดิม
        assertThat(indexNames()).containsExactly("idx_vault_items_serial");
    }

    /** ฐานข้อมูลที่ยังไม่มีตารางนี้ (deploy ใหม่หมด) — Hibernate สร้างให้เอง ไม่ต้องแตะ */
    @Test
    void missingTable_isLeftToHibernate() {
        jdbc.execute("DROP TABLE vault_items");

        migrate();

        assertThat(jdbc.queryForList("SELECT name FROM sqlite_master WHERE name='vault_items'")).isEmpty();
    }
}
