package com.vault.repository;

import com.vault.entity.VaultItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VaultItemRepository extends JpaRepository<VaultItem, String> {

    // serial unique ทั้งระบบ — item ซ้ำได้ (หลายกล่องต่อเกม) แต่ serial ห้ามซ้ำข้าม copy
    @Query("SELECT COUNT(vi) > 0 FROM VaultItem vi WHERE vi.serialNumber = :serialNumber AND vi.deletedAt IS NULL")
    boolean existsBySerial(@Param("serialNumber") String serialNumber);

    // lookup ด้วย serial อย่างเดียว — ใช้เป็นหลักตอน resolve booking (serial คือ identity ของกล่อง)
    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.vault JOIN FETCH vi.item " +
           "WHERE vi.serialNumber = :serialNumber AND vi.status = 'ACTIVE' AND vi.deletedAt IS NULL")
    Optional<VaultItem> findActiveBySerial(@Param("serialNumber") String serialNumber);

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item WHERE vi.vault.vaultId = :vaultId AND vi.deletedAt IS NULL ORDER BY vi.createdAt")
    List<VaultItem> findByVaultId(@Param("vaultId") String vaultId);

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.vault JOIN FETCH vi.item WHERE vi.deletedAt IS NULL AND vi.status = 'ACTIVE'")
    List<VaultItem> findAllActive();

    // lookup copy จาก RFID tag (epc ใน IoT event) — ใช้ track ว่ากล่องไหนถูกหยิบ/คืนใน session booking
    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item " +
           "WHERE vi.rfidTag = :rfidTag AND vi.status = 'ACTIVE' AND vi.deletedAt IS NULL")
    Optional<VaultItem> findActiveByRfidTag(@Param("rfidTag") String rfidTag);
}
