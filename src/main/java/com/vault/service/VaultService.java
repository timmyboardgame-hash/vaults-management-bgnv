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

    /** Admin override — เปิดตู้ฉุกเฉิน โดยไม่อ้างอิง booking */
    public Map<String, String> forceUnlock(String vaultId, String reason) {
        log.warn("[VAULT] Force unlock requested vault={} reason={}", vaultId, reason);
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required for force_unlock");
        }
        awsIotService.publishForceUnlock(vault.getVaultId(), UUID.randomUUID().toString(), reason);
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
    public VaultResponse updateVault(String vaultId, String vaultName, String vaultStatus, String description) {
        log.info("[VAULT] Updating vault {}", vaultId);
        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
            .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
        if (vaultName   != null) vault.setVaultName(vaultName);
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
        return new VaultResponse(v.getId(), v.getVaultId(), v.getVaultName(), v.getVaultStatus(), v.getVaultSlot(), v.getDescription(), v.getCreatedAt());
    }
}
