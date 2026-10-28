package com.vault.service;

import com.vault.dto.StatusReportRequest;
import com.vault.dto.VaultStatusView;
import com.vault.entity.Vault;
import com.vault.entity.VaultStatus;
import com.vault.repository.VaultRepository;
import com.vault.repository.VaultStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * สถานะตู้จาก heartbeat (สัญญา §4.11)
 *
 * ตู้ปล่อย vault/{thing}/status ทุก 15 นาที + ตอนเปลี่ยน mode + เมื่อถูกสั่ง request_status
 * ของเดิมไม่มี rule ไหนรับ topic นี้ ข้อมูลจึงตกพื้นทั้งหมด
 */
@Service
public class VaultStatusService {

    private static final Logger log = LoggerFactory.getLogger(VaultStatusService.class);

    /**
     * เงียบนานเกินเท่านี้ถือว่าขาดการติดต่อ
     *
     * สัญญา §4.11 เขียนว่าตู้ส่งทุก 15 นาที แต่วัดจาก log จริง 2 วัน (68 ครั้ง) ได้ค่ากลาง 45 นาที
     * ห่างสุด 53.8 นาที — ตั้ง 100 นาทีจึงเท่ากับพลาดราว 2 รอบ ยังเผื่อให้สายหลุดรอบเดียวได้
     * ถ้าวันหลัง firmware เปลี่ยนจังหวะ แก้ที่ application.yml ได้โดยไม่ต้องแก้โค้ด
     */
    private final Duration offlineAfter;

    private static final DateTimeFormatter EXACT = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private final VaultStatusRepository statusRepository;
    private final VaultRepository vaultRepository;

    public VaultStatusService(VaultStatusRepository statusRepository,
                              VaultRepository vaultRepository,
                              @org.springframework.beans.factory.annotation.Value(
                                  "${vault.status.offline-after-minutes:100}") long offlineAfterMinutes) {
        this.statusRepository = statusRepository;
        this.vaultRepository = vaultRepository;
        this.offlineAfter = Duration.ofMinutes(offlineAfterMinutes);
    }

    /** รับ heartbeat — ทับแถวเดิมของตู้นั้นเสมอ */
    @Transactional
    public boolean record(String thingName, StatusReportRequest req) {
        Vault vault = vaultRepository.findByThingName(thingName).orElse(null);
        if (vault == null) {
            log.warn("[STATUS] ไม่รู้จัก thing={} — ข้าม heartbeat นี้", thingName);
            return false;
        }

        VaultStatus s = statusRepository.findByVaultId(vault.getVaultId()).orElseGet(VaultStatus::new);
        s.setVault(vault);
        s.setState(req.state());
        s.setMode(req.mode());
        s.setTotalSlots(req.totalSlots());
        s.setItemsInLocker(req.itemsInLocker());
        s.setItemsRentedOut(req.itemsRentedOut());
        s.setAvailableSlots(req.availableSlots());
        s.setLastScanTime(req.lastScanTime());
        s.setReceivedAt(LocalDateTime.now());
        statusRepository.save(s);

        log.debug("[STATUS] vault={} state={} mode={}", vault.getVaultId(), req.state(), req.mode());
        return true;
    }

    /** สถานะที่จะเอาไปแสดงบนหน้า vault */
    public VaultStatusView view(String vaultId) {
        return view(statusRepository.findByVaultId(vaultId), LocalDateTime.now(), offlineAfter);
    }

    static VaultStatusView view(Optional<VaultStatus> maybe, LocalDateTime now, Duration offlineAfter) {
        if (maybe.isEmpty()) {
            return new VaultStatusView(false, false, false, "ยังไม่เคยรายงาน", "warn",
                    null, "—", false, null, null, null, null, null, "—", "—");
        }
        VaultStatus s = maybe.get();
        Duration since = Duration.between(s.getReceivedAt(), now);
        boolean online = since.compareTo(offlineAfter) <= 0;
        boolean lockdown = "lockdown".equals(s.getMode());

        String tone = !online ? "bad" : lockdown ? "warn" : "ok";
        String stateLabel = online ? "ติดต่อได้" : "ขาดการติดต่อ";
        String modeLabel = switch (s.getMode() == null ? "" : s.getMode()) {
            case "normal" -> "ปกติ";
            case "register" -> "โหมดลงทะเบียนของ";
            case "lockdown" -> "ล็อกตัวเอง (แตะ tag ผิดครบ 3 ครั้ง)";
            default -> s.getMode() != null ? s.getMode() : "—";
        };

        return new VaultStatusView(true, online, !online, stateLabel, tone, s.getMode(), modeLabel, lockdown,
                s.getItemsInLocker(), s.getItemsRentedOut(), s.getAvailableSlots(), s.getTotalSlots(),
                s.getLastScanTime() != null ? s.getLastScanTime() : "—",
                ago(since), s.getReceivedAt().format(EXACT));
    }

    private static String ago(Duration d) {
        long m = Math.max(0, d.toMinutes());
        if (m < 1) return "เมื่อครู่";
        if (m < 60) return m + " นาทีที่แล้ว";
        long h = d.toHours();
        if (h < 24) return h + " ชม. ที่แล้ว";
        return d.toDays() + " วันที่แล้ว";
    }
}
