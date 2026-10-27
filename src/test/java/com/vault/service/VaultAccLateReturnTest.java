package com.vault.service;

import com.vault.entity.DailyVaultStat;
import com.vault.entity.Vault;
import com.vault.service.StatsRollupService.VaultAcc;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * การนับ late_return — ตู้ปล่อย event นี้ซ้ำทุก 15 นาทีตลอดเวลาที่ของยังไม่ถูกคืน (สัญญา MQTT §4.6)
 * ถ้านับทุกแถว ของค้างข้ามคืนใบเดียวจะดูเหมือนตู้มีปัญหาหลายสิบครั้ง
 */
class VaultAccLateReturnTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 27);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 0, 5);

    private static VaultAcc acc() {
        Vault v = new Vault();
        v.setId("vault-db-id");
        v.setVaultId("vt-001");
        return new VaultAcc(v);
    }

    private static DailyVaultStat stat(VaultAcc a) {
        return a.toEntity(DAY, NOW);
    }

    @Test
    void lateReturn_repeatedForSameBooking_countsOnce() {
        VaultAcc a = acc();
        for (int i = 0; i < 12; i++) {
            a.countEvent("ANOMALY:late_return", "bk-1");
        }

        assertThat(stat(a).getAnomalyLateReturn()).isEqualTo(1);
    }

    @Test
    void lateReturn_differentBookings_countSeparately() {
        VaultAcc a = acc();
        a.countEvent("ANOMALY:late_return", "bk-1");
        a.countEvent("ANOMALY:late_return", "bk-1");
        a.countEvent("ANOMALY:late_return", "bk-2");
        a.countEvent("ANOMALY:late_return", "bk-3");

        assertThat(stat(a).getAnomalyLateReturn()).isEqualTo(3);
    }

    @Test
    void lateReturn_noneReported_isZero() {
        VaultAcc a = acc();
        a.countEvent("ANOMALY:wrong_tag", "bk-1");

        assertThat(stat(a).getAnomalyLateReturn()).isZero();
    }

    /** ตัวสะสมเป็นของวันต่อวันอยู่แล้ว — ของค้างข้ามคืนจึงถูกนับใหม่ในวันถัดไป */
    @Test
    void lateReturn_overnight_countsOncePerDay() {
        VaultAcc day1 = acc();
        day1.countEvent("ANOMALY:late_return", "bk-1");
        day1.countEvent("ANOMALY:late_return", "bk-1");

        VaultAcc day2 = acc();
        day2.countEvent("ANOMALY:late_return", "bk-1");

        assertThat(stat(day1).getAnomalyLateReturn()).isEqualTo(1);
        assertThat(stat(day2).getAnomalyLateReturn()).isEqualTo(1);
    }

    /** anomaly ชนิดอื่นต้องนับทุกครั้งเหมือนเดิม — ตู้ไม่ได้ปล่อยซ้ำแบบ late_return */
    @Test
    void otherAnomalies_stillCountEveryEvent() {
        VaultAcc a = acc();
        a.countEvent("ANOMALY:wrong_tag", "bk-1");
        a.countEvent("ANOMALY:wrong_tag", "bk-1");
        a.countEvent("ANOMALY:wrong_tag", "bk-1");
        a.countEvent("LATE_EVENT:booking_returned", "bk-1");
        a.countEvent("LATE_EVENT:booking_returned", "bk-1");

        DailyVaultStat s = stat(a);
        assertThat(s.getAnomalyWrongTag()).isEqualTo(3);
        assertThat(s.getLateEvents()).isEqualTo(2);
    }

    /** booking id หายไปไม่ควรทำให้พัง — นับรวมเป็นก้อนเดียว ดีกว่าโยน exception กลางงานสรุป */
    @Test
    void lateReturn_nullBookingId_doesNotThrow() {
        VaultAcc a = acc();
        a.countEvent("ANOMALY:late_return", null);
        a.countEvent("ANOMALY:late_return", null);

        assertThat(stat(a).getAnomalyLateReturn()).isEqualTo(1);
    }
}
