package com.vault.service;

import com.vault.dto.CreateVaultRequest;
import com.vault.dto.VaultResponse;
import com.vault.entity.Vault;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);
    private static final Set<Integer> VALID_SLOTS = Set.of(8, 16, 24, 32);

    private final VaultRepository vaultRepository;
    private final AwsIotService   awsIotService;

    public VaultService(VaultRepository vaultRepository, AwsIotService awsIotService) {
        this.vaultRepository = vaultRepository;
        this.awsIotService   = awsIotService;
    }

    /**
     * ขอให้ตู้รายงานสถานะเดี๋ยวนี้ (สัญญา §3.5)
     *
     * ปกติตู้ส่ง heartbeat เองทุก 15 นาที คำสั่งนี้ไว้ใช้ตอนอยากรู้เดี๋ยวนั้น เช่นเพิ่งไปแตะตู้มา
     * หรือสงสัยว่าตู้ยังมีชีวิตอยู่ไหม — ตู้จะตอบกลับทาง topic status ตามปกติ ไม่ได้ตอบกลับมาตรงๆ
     * ฝั่งเราจึงได้แค่ "ส่งคำสั่งแล้ว" สถานะจริงจะมาอัปเดตอีกทีในไม่กี่วินาที
     */
    public Map<String, String> requestStatus(String vaultId) {
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        log.info("[VAULT] Status requested vault={} by={}", vaultId, currentUser());
        awsIotService.publishRequestStatus(vault.effectiveThingName(), UUID.randomUUID().toString());
        return Map.of("vaultId", vaultId, "status", "command_sent");
    }

    /**
     * ปลด lockdown ของตู้ (สัญญา §3.6)
     *
     * ตู้เข้า lockdown เองเมื่อแตะ tag ผิดครบ 3 ครั้ง แล้วออกเองไม่ได้ — ถ้าไม่มีปุ่มนี้คือทางตัน
     * ตอนนี้ระบบมีผู้ใช้เดียวคือ admin จึงให้ admin กดได้เลย ไม่ต้องยืนยันสองชั้น
     * บันทึกไว้ใน log ว่าใครกดและเพราะอะไร เผื่อย้อนดูภายหลัง
     */
    public Map<String, String> clearLockdown(String vaultId, String reason) {
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        String actor = currentUser();
        log.warn("[VAULT] Clear lockdown requested vault={} by={} reason={}", vaultId, actor, reason);

        awsIotService.publishClearLockdown(vault.effectiveThingName(), UUID.randomUUID().toString(), reason);
        return Map.of("vaultId", vaultId, "status", "command_sent",
                "reason", reason != null ? reason : "", "by", actor);
    }

    /** ชื่อผู้ใช้ที่ล็อกอินอยู่ — เรียกผ่าน REST ด้วย api key จะไม่มีชื่อคน */
    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getName() != null ? auth.getName() : "unknown";
    }

    /** Admin override — เปิดตู้ฉุกเฉิน โดยไม่อ้างอิง booking */
    public Map<String, String> forceUnlock(String vaultId, String reason) {
        log.warn("[VAULT] Force unlock requested vault={} reason={}", vaultId, reason);
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required for force_unlock");
        }
        awsIotService.publishForceUnlock(vault.effectiveThingName(), UUID.randomUUID().toString(), reason);
        return Map.of("vaultId", vaultId, "status", "command_sent", "reason", reason);
    }

    public List<VaultResponse> listVaults() {
        return vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .stream().map(this::toResponse).toList();
    }

    public List<VaultResponse> searchVaults(String search) {
        List<Vault> all = vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        if (search == null || search.isBlank()) return all.stream().map(this::toResponse).toList();
        String q = search.toLowerCase();
        return all.stream().filter(v ->
            v.getVaultId().toLowerCase().contains(q) ||
            (v.getVaultName() != null && v.getVaultName().toLowerCase().contains(q))
        ).map(this::toResponse).toList();
    }

    public Optional<VaultResponse> getVault(String vaultId) {
        return vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .map(this::toResponse);
    }

    @Transactional
    /** หาตู้จากชื่อ thing ที่ AWS ส่งมา — ตู้ที่ยังไม่ได้ตั้ง thing_name จะหาด้วยรหัสตู้ได้เหมือนเดิม */
    public Optional<VaultResponse> getVaultByThing(String thingName) {
        return vaultRepository.findByThingName(thingName).map(this::toResponse);
    }

    public VaultResponse createVault(CreateVaultRequest req) {
        log.info("[VAULT] Creating vault {}", req.vaultId());
        if (!VALID_SLOTS.contains(req.vaultSlot())) {
            throw new IllegalArgumentException("vault_slot must be 8, 16, 24, or 32");
        }
        if (vaultRepository.existsByVaultIdAndDeletedAtIsNull(req.vaultId())) {
            throw new IllegalArgumentException("Vault ID already exists: " + req.vaultId());
        }
        Vault vault = new Vault();
        vault.setVaultId(req.vaultId());
        vault.setVaultName(req.vaultName());
        vault.setVaultSlot(req.vaultSlot());
        vault.setVaultStatus("ENABLE");
        vault.setDescription(req.description());
        return toResponse(vaultRepository.save(vault));
    }

    @Transactional
    public VaultResponse updateVault(String vaultId, String vaultName, String vaultStatus, String description,
                                     String thingName) {
        log.info("[VAULT] Updating vault {}", vaultId);
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
        if (vaultName   != null) vault.setVaultName(vaultName);
        // ว่าง = ล้างค่ากลับไปใช้ vaultId เป็นชื่อ thing
        if (thingName   != null) vault.setThingName(thingName.isBlank() ? null : thingName.trim());
        if (vaultStatus != null) vault.setVaultStatus(vaultStatus);
        vault.setDescription(description);
        return toResponse(vaultRepository.save(vault));
    }

    @Transactional
    public VaultResponse deleteVault(String vaultId) {
        log.info("[VAULT] Deleting vault {}", vaultId);
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
        vault.setDeletedAt(LocalDateTime.now());
        return toResponse(vaultRepository.save(vault));
    }

    private VaultResponse toResponse(Vault v) {
        return new VaultResponse(v.getId(), v.getVaultId(), v.getThingName(), v.getVaultName(), v.getVaultStatus(), v.getVaultSlot(), v.getDescription(), v.getCreatedAt());
    }
}
