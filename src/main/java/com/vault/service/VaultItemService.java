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
                .stream().map(VaultItemService::toResponse).toList();
    }

    /** กล่องทั้งหมด รวมที่ยังไม่ได้เข้าตู้ — หน้าจัดการกล่องต้องเห็นครบ */
    public List<VaultItemResponse> listAllActive() {
        return vaultItemRepository.findAllActive()
                .stream().map(VaultItemService::toResponse).toList();
    }

    /** เฉพาะกล่องที่อยู่ในตู้ — ใช้ตรงที่ความหมายคือ "ของที่จองได้" */
    public List<VaultItemResponse> listInVault() {
        return vaultItemRepository.findAllActiveInVault()
                .stream().map(VaultItemService::toResponse).toList();
    }

    /** กล่องที่ลงทะเบียนแล้วแต่ยังไม่ได้เข้าตู้ */
    public List<VaultItemResponse> listUnassigned() {
        return vaultItemRepository.findUnassigned()
                .stream().map(VaultItemService::toResponse).toList();
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

        String tag = rfidTag != null && !rfidTag.isBlank() ? rfidTag.trim() : null;
        requireTagFree(tag, null);

        VaultItem vaultItem = new VaultItem();
        vaultItem.setVault(vault);
        vaultItem.setItem(item);
        vaultItem.setSerialNumber(serialNumber.trim());
        vaultItem.setRfidTag(tag);
        vaultItem.setStatus("ACTIVE");

        return toResponse(vaultItemRepository.save(vaultItem));
    }

    /**
     * ระบุตู้ให้กล่องที่แอปลงทะเบียนเข้ามาแต่ยังไม่มีที่อยู่ (หรือย้ายข้ามตู้)
     *
     * ไม่เช็ค binding ร้าน-ตู้ที่ชั้นนี้ — หน้าหลังบ้านเป็นที่ที่คนมาแก้ของที่ผิด
     * ถ้าร้านยังไม่ผูกกับตู้ที่เลือก ตอนสร้าง booking จะฟ้องเองว่า agent ไม่ได้ผูกกับตู้
     */
    @Transactional
    public VaultItemResponse assignVault(String vaultItemId, String vaultId) {
        VaultItem vi = vaultItemRepository.findById(vaultItemId)
                .orElseThrow(() -> new IllegalArgumentException("VaultItem not found: " + vaultItemId));

        if (vaultId == null || vaultId.isBlank()) {
            // ถอดออกจากตู้แต่ยังไม่ลบกล่อง — กล่องกลับไปอยู่สถานะรอเข้าตู้
            vi.setVault(null);
            vi.setSlotId(null);
        } else {
            Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                    .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));
            vi.setVault(vault);
        }

        log.info("[VAULT_ITEM] Placement vaultItem={} vault={}", vaultItemId,
                vaultId != null && !vaultId.isBlank() ? vaultId : "UNASSIGNED");
        return toResponse(vaultItemRepository.save(vi));
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
        // ค่าว่าง = เคลียร์ serial ออก (copy จะจองไม่ได้จนกว่าจะ set ใหม่)
        String newSerial = serialNumber != null && !serialNumber.isBlank() ? serialNumber.trim() : null;
        if (!java.util.Objects.equals(newSerial, vi.getSerialNumber())) {
            if (vi.getSerialNumber() != null
                    && bookingRepository.existsActiveByItemAndSerial(vi.getItem().getId(), vi.getSerialNumber())) {
                throw new IllegalArgumentException(
                    "Cannot change serial — copy has an active booking");
            }
            if (newSerial != null && vaultItemRepository.existsBySerial(newSerial)) {
                throw new IllegalArgumentException("Serial already exists: " + newSerial);
            }
            vi.setSerialNumber(newSerial);
        }

        String newTag = rfidTag != null && !rfidTag.isBlank() ? rfidTag.trim() : null;
        requireTagFree(newTag, vi.getId());
        vi.setRfidTag(newTag);
        return toResponse(vaultItemRepository.save(vi));
    }

    /**
     * แท็กเดียวต้องชี้กล่องเดียว — ถ้าซ้ำ ตอนลูกค้าแตะแท็กที่ตู้ ระบบจะแยกกล่องไม่ออก
     * ก่อนหน้านี้ไม่มีอะไรกันเลย แท็กม้วนเดียวกันบางล็อตมาพร้อมเลขซ้ำ
     */
    private void requireTagFree(String rfidTag, String excludeCopyId) {
        if (rfidTag == null) return;
        if (vaultItemRepository.existsByRfidTag(rfidTag, excludeCopyId == null ? "" : excludeCopyId)) {
            String clash = vaultItemRepository.findByRfidTag(rfidTag)
                    .map(VaultItem::getSerialNumber).orElse("กล่องอื่น");
            throw new IllegalArgumentException("RFID tag already used by copy " + clash + ": " + rfidTag);
        }
    }

    /**
     * กล่องที่ยังไม่มีตู้ต้องแปลงได้ ไม่ใช่ NPE — vaultId/vaultName คืน null
     * ให้หน้าเว็บกับ API ตัดสินใจเองว่าจะแสดงอย่างไร
     */
    static VaultItemResponse toResponse(VaultItem vi) {
        Vault vault = vi.getVault();
        return new VaultItemResponse(
                vi.getId(),
                vault != null ? vault.getVaultId() : null,
                vault != null ? vault.getVaultName() : null,
                vi.getItem().getItemId(),
                vi.getItem().getItemNameEn(),
                vi.getItem().getItemNameTh(),
                vi.getSerialNumber(),
                vi.getRfidTag(),
                vi.getItem().getDefaultPin(),
                vi.getStatus(),
                vi.getCreatedAt(),
                vi.ownerAgentId(),
                vault != null ? VaultItemResponse.IN_VAULT : VaultItemResponse.UNASSIGNED
        );
    }
}
