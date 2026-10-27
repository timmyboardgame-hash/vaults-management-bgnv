package com.vault.service;

import com.vault.dto.SystemEventRequest;
import com.vault.entity.Vault;
import com.vault.entity.VaultEvent;
import com.vault.repository.VaultEventRepository;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * บันทึก system event ที่ตู้รายงานเข้ามา (สัญญา MQTT §4.10)
 *
 * ท่อมีอยู่แล้วตั้งแต่ตู้ → IoT rule → Lambda → endpoint นี้ ก่อนหน้านี้ปลายทาง log ทิ้งอย่างเดียว
 */
@Service
public class VaultEventService {

    private static final Logger log = LoggerFactory.getLogger(VaultEventService.class);

    /** error ที่ออกมาจากเครื่องมือ dev ของ kiosk — สัญญาระบุให้ backend มองข้าม */
    private static final String DEV_PREFIX = "dev_";

    /** ผลการบันทึก — stored=false แปลว่าตั้งใจไม่เก็บ ไม่ใช่ error */
    public record Result(boolean stored, String reason, String vaultId) {}

    private final VaultEventRepository eventRepository;
    private final VaultRepository vaultRepository;

    public VaultEventService(VaultEventRepository eventRepository, VaultRepository vaultRepository) {
        this.eventRepository = eventRepository;
        this.vaultRepository = vaultRepository;
    }

    /** true = เป็น event ของเครื่องมือ dev ไม่ต้องเก็บ */
    static boolean isDevOnly(String errorType) {
        return errorType != null && errorType.startsWith(DEV_PREFIX);
    }

    @Transactional
    public Result record(String thingName, SystemEventRequest req) {
        String errorType = req.errorType() != null && !req.errorType().isBlank()
                ? req.errorType().trim() : "unknown";

        if (isDevOnly(errorType)) {
            log.debug("[IoT-SYSTEM] ข้าม event ของเครื่องมือ dev thing={} errorType={}", thingName, errorType);
            return new Result(false, "dev-tool", null);
        }

        // retry จาก Lambda ส่ง request_id เดิมมา — สัญญา §7 ให้ถือเป็น event เดียวกัน
        if (req.requestId() != null && !req.requestId().isBlank()
                && eventRepository.existsByRequestIdAndErrorType(req.requestId(), errorType)) {
            log.info("[IoT-SYSTEM] event ซ้ำ ข้ามไป thing={} errorType={} requestId={}",
                    thingName, errorType, req.requestId());
            return new Result(false, "duplicate", null);
        }

        Vault vault = vaultRepository.findByThingName(thingName).orElse(null);
        if (vault == null) {
            log.warn("[IoT-SYSTEM] ไม่รู้จัก thing={} — เก็บ event ไว้โดยยังไม่ผูกกับตู้ไหน", thingName);
        }

        VaultEvent e = new VaultEvent();
        e.setVault(vault);
        e.setThingName(thingName);
        e.setErrorType(errorType);
        e.setSeverity(req.severity() != null && !req.severity().isBlank() ? req.severity().trim() : "error");
        e.setErrorMessage(req.errorMessage());
        e.setRequestId(req.requestId());
        e.setOccurredAt(LocalDateTime.now());
        eventRepository.save(e);

        return new Result(true, "stored", vault != null ? vault.getVaultId() : null);
    }
}
