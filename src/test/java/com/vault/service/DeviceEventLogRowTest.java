package com.vault.service;

import com.vault.dto.VaultAnalyticsDto.DeviceEventRow;
import com.vault.entity.Vault;
import com.vault.entity.VaultEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * การแปลง vault_events หนึ่งแถวไปเป็นแถวในตารางบนหน้า analytics
 *
 * แผงยอดรวมบอกได้แค่ว่าเรื่องอะไรเกิดบ่อย ตารางนี้ต้องตอบ "เมื่อไหร่ ตู้ไหน ตู้ว่าอะไร"
 * ค่าที่ตู้ไม่ส่งมาต้องเป็น null ไม่ใช่ขีดกลาง — ให้หน้าเว็บตัดสินใจเองว่าจะโชว์อย่างไร
 */
class DeviceEventLogRowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    private static VaultEvent event(String severity, LocalDateTime at) {
        VaultEvent e = new VaultEvent();
        e.setThingName("vault-locker-001");
        e.setErrorType("rs485_timeout");
        e.setSeverity(severity);
        e.setOccurredAt(at);
        return e;
    }

    private static Vault vault(String vaultId) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        return v;
    }

    @Test
    void linkedEvent_showsVaultIdAndWhen() {
        VaultEvent e = event("error", NOW.minusHours(3));
        e.setVault(vault("vt-001"));
        e.setErrorMessage("no response from RS485 bus");

        DeviceEventRow r = VaultStatsService.logRow(e, NOW);

        assertThat(r.vaultId()).isEqualTo("vt-001");
        assertThat(r.thingName()).isEqualTo("vault-locker-001");
        assertThat(r.whenLabel()).isEqualTo("29 ต.ค. 12:00 น.");
        assertThat(r.age()).isEqualTo("3 ชม.");
        assertThat(r.message()).isEqualTo("no response from RS485 bus");
    }

    /** thing ที่ยังไม่ลงทะเบียน (VLT-6) — เก็บไว้แล้วต้องเห็นด้วย ไม่ใช่ซ่อนเพราะหาตู้ไม่เจอ */
    @Test
    void unlinkedEvent_keepsThingNameWithoutVaultId() {
        DeviceEventRow r = VaultStatsService.logRow(event("warning", NOW.minusMinutes(20)), NOW);

        assertThat(r.vaultId()).isNull();
        assertThat(r.thingName()).isEqualTo("vault-locker-001");
    }

    @Test
    void severity_decidesRowColor() {
        assertThat(VaultStatsService.logRow(event("error", NOW), NOW).tone()).isEqualTo("bad");
        assertThat(VaultStatsService.logRow(event("warning", NOW), NOW).tone()).isEqualTo("warn");
        assertThat(VaultStatsService.logRow(event("info", NOW), NOW).tone()).isEqualTo("ok");
    }

    /** severity แปลก ๆ หรือหายไป — เตือนไว้ก่อน ดีกว่าแสดงเป็นเรื่องปกติ */
    @Test
    void unknownSeverity_warnsRatherThanLooksFine() {
        assertThat(VaultStatsService.logRow(event(null, NOW), NOW).tone()).isEqualTo("warn");
        assertThat(VaultStatsService.logRow(event("catastrophic", NOW), NOW).tone()).isEqualTo("warn");
    }

    @Test
    void blankMessageAndRequestId_becomeNull() {
        VaultEvent e = event("error", NOW);
        e.setErrorMessage("   ");
        e.setRequestId("");

        DeviceEventRow r = VaultStatsService.logRow(e, NOW);

        assertThat(r.message()).isNull();
        assertThat(r.requestId()).isNull();
    }

    /** request id ที่มีมา = event นี้มาจากคำสั่งที่เราส่งไป ตามรอยกลับได้ */
    @Test
    void requestId_isKeptWhenPresent() {
        VaultEvent e = event("error", NOW);
        e.setRequestId("req-abc-123");

        assertThat(VaultStatsService.logRow(e, NOW).requestId()).isEqualTo("req-abc-123");
    }
}
