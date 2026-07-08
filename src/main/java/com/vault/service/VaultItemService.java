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
    private final com.vault.repository.BookingRepository bookingRepository;

    public VaultItemService(VaultItemRepository vaultItemRepository,
                            VaultRepository vaultRepository,
                            ItemRepository itemRepository,
                            com.vault.repository.BookingRepository bookingRepository) {
        this.vaultItemRepository = vaultItemRepository;
        this.vaultRepository = vaultRepository;
        this.itemRepository = itemRepository;
        this.bookingRepository = bookingRepository;
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
    public VaultItemResponse bindItemToVault(String vaultId, String itemId, String serialNumber, String rfidTag) {
        log.info("[VAULT_ITEM] Binding item={} serial={} to vault={}", itemId, serialNumber, vaultId);

        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        Item item = itemRepository.findByItemIdAndDeletedAtIsNull(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found: " + itemId));

        if (serialNumber == null || serialNumber.isBlank()) {
            throw new IllegalArgumentException("serialNumber is required — 1 copy ต่อ 1 serial");
        }
        // item เดียวกัน bind ได้หลายกล่อง แต่ serial unique ทั้งระบบ (identity ของกล่อง)
        if (vaultItemRepository.existsBySerial(serialNumber.trim())) {
            throw new IllegalArgumentException("Serial already exists: " + serialNumber);
        }

        VaultItem vaultItem = new VaultItem();
        vaultItem.setVault(vault);
        vaultItem.setItem(item);
        vaultItem.setSerialNumber(serialNumber.trim());
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
    public VaultItemResponse updateCopy(String vaultItemId, String rfidTag, String serialNumber) {
        VaultItem vi = vaultItemRepository.findById(vaultItemId)
                .orElseThrow(() -> new IllegalArgumentException("VaultItem not found: " + vaultItemId));

        // เปลี่ยน serial ได้เฉพาะตอนไม่มี booking ค้าง — booking เก็บ serial เป็น snapshot
        // ถ้าแก้ระหว่างลูกค้ายืมอยู่ การ validate ตอนคืนจะ mismatch
        if (serialNumber != null && !serialNumber.isBlank()
                && !serialNumber.trim().equals(vi.getSerialNumber())) {
            if (bookingRepository.existsActiveByItemAndSerial(vi.getItem().getId(), vi.getSerialNumber())) {
                throw new IllegalArgumentException(
                    "Cannot change serial — copy has an active booking");
            }
            if (vaultItemRepository.existsBySerial(serialNumber.trim())) {
                throw new IllegalArgumentException("Serial already exists: " + serialNumber);
            }
            vi.setSerialNumber(serialNumber.trim());
        }

        vi.setRfidTag(rfidTag != null && !rfidTag.isBlank() ? rfidTag.trim() : null);
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
                vi.getSerialNumber(),
                vi.getRfidTag(),
                vi.getItem().getDefaultPin(),
                vi.getStatus(),
                vi.getCreatedAt()
        );
    }
}
