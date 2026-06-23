package com.vault.controller;

import com.vault.dto.CreateVaultRequest;
import com.vault.dto.ForceUnlockRequest;
import com.vault.dto.SystemEventRequest;
import com.vault.dto.VaultResponse;
import com.vault.service.VaultService;
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

    @Value("${iot.callback.secret:dev-iot-secret}")
    private String iotCallbackSecret;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
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
        String level = "error".equals(request.severity()) ? "ERROR" : "WARN";
        log.atLevel("ERROR".equals(level)
                ? org.slf4j.event.Level.ERROR
                : org.slf4j.event.Level.WARN)
            .log("[IoT-SYSTEM] vault={} errorType={} severity={} msg={}",
                vaultId, request.errorType(), request.severity(), request.errorMessage());
        return ResponseEntity.ok().build();
    }
}
