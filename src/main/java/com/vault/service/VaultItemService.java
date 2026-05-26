package com.vault.service;

import com.vault.dto.VaultItemResponse;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.ItemRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VaultItemService {

    private static final Logger log = LoggerFactory.getLogger(VaultItemService.class);

    private final VaultItemRepository vaultItemRepository;
    private final VaultRepository vaultRepository;
    private final ItemRepository itemRepository;

    public VaultItemService(VaultItemRepository vaultItemRepository,
                            VaultRepository vaultRepository,
                            ItemRepository itemRepository) {
        this.vaultItemRepository = vaultItemRepository;
        this.vaultRepository = vaultRepository;
        this.itemRepository = itemRepository;
    }

    public List<VaultItemResponse> listByVault(String vaultId) {
        return vaultItemRepository.findByVaultId(vaultId)
                .stream().map(this::toResponse).toList();
    }

    public List<VaultItemResponse> listAllActive() {
        return vaultItemRepository.findAllActive()
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public VaultItemResponse bindItemToVault(String vaultId, String itemId, String rfidTag) {
        log.info("[VAULT_ITEM] Binding item={} to vault={}", itemId, vaultId);

        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        Item item = itemRepository.findByItemIdAndDeletedAtIsNull(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

        // ป้องกัน item ถูก bind ซ้ำ
        if (vaultItemRepository.findActiveByItemId(item.getId()).isPresent()) {
            throw new IllegalArgumentException("Item " + itemId + " is already bound to a vault");
        }

        VaultItem vaultItem = new VaultItem();
        vaultItem.setVault(vault);
        vaultItem.setItem(item);
        vaultItem.setRfidTag(rfidTag != null && !rfidTag.isBlank() ? rfidTag.trim() : null);
        vaultItem.setStatus("ACTIVE");

        return toResponse(vaultItemRepository.save(vaultItem));
    }

    @Transactional
    public void unbindItem(String vaultItemId) {
        log.info("[VAULT_ITEM] Unbinding vaultItem={}", vaultItemId);
        VaultItem vi = vaultItemRepository.findById(vaultItemId)
                .orElseThrow(() -> new IllegalArgumentException("VaultItem not found: " + vaultItemId));
        vi.setStatus("INACTIVE");
        vi.setDeletedAt(LocalDateTime.now());
        vaultItemRepository.save(vi);
    }

    @Transactional
    public VaultItemResponse updateRfidTag(String vaultItemId, String rfidTag) {
        VaultItem vi = vaultItemRepository.findById(vaultItemId)
                .orElseThrow(() -> new IllegalArgumentException("VaultItem not found: " + vaultItemId));
        vi.setRfidTag(rfidTag != null ? rfidTag.trim() : null);
        return toResponse(vaultItemRepository.save(vi));
    }

    private VaultItemResponse toResponse(VaultItem vi) {
        return new VaultItemResponse(
                vi.getId(),
                vi.getVault().getVaultId(),
                vi.getVault().getVaultName(),
                vi.getItem().getItemId(),
                vi.getItem().getItemNameEn(),
                vi.getItem().getItemNameTh(),
                vi.getRfidTag(),
                vi.getStatus(),
                vi.getCreatedAt()
        );
    }
}
