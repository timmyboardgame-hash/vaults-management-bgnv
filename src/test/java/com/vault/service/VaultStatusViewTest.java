package com.vault.service;

import com.vault.dto.VaultStatusView;
import com.vault.entity.VaultStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * การสรุปสถานะตู้จาก heartbeat
 *
 * ตู้บอกเราได้แค่ตอนที่มันยังพูดได้ — ถ้าเน็ตหลุดหรือเครื่องดับจะไม่มีใครมาบอก
 * "ขาดการติดต่อ" จึงต้องสรุปจากเวลาที่ heartbeat ล่าสุดเข้ามา ไม่ใช่เชื่อค่า state ที่ตู้ส่ง
 */
class VaultStatusViewTest {

    private static final java.time.Duration OFFLINE_AFTER = java.time.Duration.ofMinutes(100);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 12, 0);

    private static VaultStatus status(String mode, LocalDateTime receivedAt) {
        VaultStatus s = new VaultStatus();
        s.setState("online");
        s.setMode(mode);
        s.setItemsInLocker(2);
        s.setItemsRentedOut(1);
        s.setReceivedAt(receivedAt);
        return s;
    }

    @Test
    void freshHeartbeat_isOnline() {
        VaultStatusView v = VaultStatusService.view(Optional.of(status("normal", NOW.minusMinutes(5))), NOW, OFFLINE_AFTER);

        assertThat(v.online()).isTrue();
        assertThat(v.stale()).isFalse();
        assertThat(v.tone()).isEqualTo("ok");
        assertThat(v.stateLabel()).isEqualTo("ติดต่อได้");
        assertThat(v.lastSeenLabel()).isEqualTo("5 นาทีที่แล้ว");
    }

    /** ตู้บอกว่า online แต่เงียบไป 4 ชม. — ต้องเชื่อความเงียบ ไม่ใช่เชื่อข้อความเก่า */
    @Test
    void staleHeartbeat_isOfflineEvenWhenStateSaysOnline() {
        VaultStatusView v = VaultStatusService.view(Optional.of(status("normal", NOW.minusHours(4))), NOW, OFFLINE_AFTER);

        assertThat(v.online()).isFalse();
        assertThat(v.tone()).isEqualTo("bad");
        assertThat(v.stateLabel()).isEqualTo("ขาดการติดต่อ");
        assertThat(v.lastSeenLabel()).isEqualTo("4 ชม. ที่แล้ว");
        // โหมดที่อ่านได้เป็นของเก่า ไม่ใช่สถานะตอนนี้ — หน้าเว็บต้องรู้เพื่อหรี่ให้เห็นว่าค้าง
        assertThat(v.stale()).isTrue();
    }

    /** หายรอบเดียว (ตู้ส่งจริงทุก ~45 นาที) ยังไม่ถือว่าขาดการติดต่อ */
    @Test
    void oneMissedBeat_isStillOnline() {
        assertThat(VaultStatusService.view(Optional.of(status("normal", NOW.minusMinutes(50))), NOW, OFFLINE_AFTER).online())
                .isTrue();
    }

    @Test
    void lockdownMode_isFlaggedEvenWhenOnline() {
        VaultStatusView v = VaultStatusService.view(Optional.of(status("lockdown", NOW.minusMinutes(3))), NOW, OFFLINE_AFTER);

        assertThat(v.online()).isTrue();
        assertThat(v.lockdown()).isTrue();
        assertThat(v.tone()).isEqualTo("warn");
        assertThat(v.modeLabel()).contains("ล็อกตัวเอง");
    }

    @Test
    void noHeartbeatYet_saysSoInsteadOfClaimingOffline() {
        VaultStatusView v = VaultStatusService.view(Optional.empty(), NOW, OFFLINE_AFTER);

        assertThat(v.reported()).isFalse();
        assertThat(v.stateLabel()).isEqualTo("ยังไม่เคยรายงาน");
        assertThat(v.tone()).isEqualTo("warn");
    }
}
