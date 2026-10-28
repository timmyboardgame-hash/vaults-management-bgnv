package com.vault.controller;

import com.vault.dto.ClearLockdownRequest;
import com.vault.dto.CreateVaultRequest;
import com.vault.dto.ForceUnlockRequest;
import com.vault.dto.StatusReportRequest;
import com.vault.dto.SystemEventRequest;
import com.vault.dto.VaultResponse;
import com.vault.service.VaultEventService;
import com.vault.service.VaultService;
import com.vault.service.VaultStatusService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vaults")
public class VaultController {

    private static final Logger log = LoggerFactory.getLogger(VaultController.class);

    private final VaultService vaultService;
    private final VaultEventService vaultEventService;
    private final VaultStatusService vaultStatusService;

    @Value("${iot.callback.secret:dev-iot-secret}")
    private String iotCallbackSecret;

    public VaultController(VaultService vaultService, VaultEventService vaultEventService,
                           VaultStatusService vaultStatusService) {
        this.vaultService = vaultService;
        this.vaultEventService = vaultEventService;
        this.vaultStatusService = vaultStatusService;
    }

    @GetMapping
    public ResponseEntity<List<VaultResponse>> list() {
        return ResponseEntity.ok(vaultService.listVaults());
    }

    @GetMapping("/{vaultId}")
    public ResponseEntity<VaultResponse> get(@PathVariable String vaultId) {
        return vaultService.getVault(vaultId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<VaultResponse> create(@Valid @RequestBody CreateVaultRequest req) {
        return ResponseEntity.ok(vaultService.createVault(req));
    }

    @DeleteMapping("/{vaultId}")
    public ResponseEntity<VaultResponse> delete(@PathVariable String vaultId) {
        return ResponseEntity.ok(vaultService.deleteVault(vaultId));
    }

    // POST /api/v1/vaults/{vaultId}/force-unlock  — Admin override เปิดตู้ฉุกเฉิน
    @PostMapping("/{vaultId}/force-unlock")
    public ResponseEntity<Map<String, String>> forceUnlock(
        @PathVariable String vaultId,
        @RequestBody ForceUnlockRequest request
    ) {
        return ResponseEntity.ok(vaultService.forceUnlock(vaultId, request.reason()));
    }

    // POST /api/v1/vaults/{vaultId}/request-status  — ขอ snapshot ทันที แทนรอ heartbeat รอบ 15 นาที
    @PostMapping("/{vaultId}/request-status")
    public ResponseEntity<Map<String, String>> requestStatus(@PathVariable String vaultId) {
        return ResponseEntity.ok(vaultService.requestStatus(vaultId));
    }

    // POST /api/v1/vaults/{vaultId}/clear-lockdown  — ปลด lockdown ที่ตู้เข้าเองจาก wrong tag ครบ 3 ครั้ง
    @PostMapping("/{vaultId}/clear-lockdown")
    public ResponseEntity<Map<String, String>> clearLockdown(
        @PathVariable String vaultId,
        @RequestBody(required = false) ClearLockdownRequest request
    ) {
        return ResponseEntity.ok(vaultService.clearLockdown(vaultId, request != null ? request.reason() : null));
    }

    // POST /api/v1/vaults/{thingName}/iot-status  — Lambda callback สำหรับ heartbeat (topic vault/{thing}/status)
    @PostMapping("/{thingName}/iot-status")
    public ResponseEntity<Void> handleStatus(
        @PathVariable String thingName,
        @RequestBody StatusReportRequest request,
        @RequestHeader(value = "x-iot-secret", required = false) String secret
    ) {
        if (!iotCallbackSecret.equals(secret)) {
            log.warn("[STATUS] Unauthorized status callback thing={}", thingName);
            return ResponseEntity.status(401).build();
        }
        vaultStatusService.record(thingName, request);
        return ResponseEntity.ok().build();
    }

    // POST /api/v1/vaults/{vaultId}/iot-system-event  — Lambda callback สำหรับ events/system/error
    @PostMapping("/{vaultId}/iot-system-event")
    public ResponseEntity<Void> handleSystemEvent(
        @PathVariable String vaultId,
        @RequestBody SystemEventRequest request,
        @RequestHeader(value = "x-iot-secret", required = false) String secret
    ) {
        if (!iotCallbackSecret.equals(secret)) {
            log.warn("[IoT] Unauthorized system-event callback vault={}", vaultId);
            return ResponseEntity.status(401).build();
        }
        // เก็บลง vault_events ก่อน แล้วค่อย log — Lambda ส่งชื่อ thing มา ไม่ใช่รหัสตู้
        VaultEventService.Result result = vaultEventService.record(vaultId, request);

        String level = "error".equals(request.severity()) ? "ERROR" : "WARN";
        log.atLevel("ERROR".equals(level)
                ? org.slf4j.event.Level.ERROR
                : org.slf4j.event.Level.WARN)
            .log("[IoT-SYSTEM] thing={} vault={} errorType={} severity={} เก็บ={} msg={}",
                vaultId, result.vaultId() != null ? result.vaultId() : "ไม่พบ",
                request.errorType(), request.severity(), result.reason(), request.errorMessage());
        return ResponseEntity.ok().build();
    }
}
