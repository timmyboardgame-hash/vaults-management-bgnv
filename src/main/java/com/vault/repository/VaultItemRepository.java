package com.vault.repository;

import com.vault.entity.VaultItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * กล่องเกมหนึ่งกล่อง = หนึ่งแถว
 *
 * ตั้งแต่มี API ลงทะเบียนจากแอปร้าน กล่องอยู่ได้โดยยังไม่มีตู้ (vault = null)
 * ทุก query ที่ fetch vault จึงต้องเป็น LEFT JOIN — inner join จะตัดกล่องกลุ่มนี้ทิ้งเงียบ ๆ
 */
public interface VaultItemRepository extends JpaRepository<VaultItem, String> {

    // serial unique ทั้งระบบ — item ซ้ำได้ (หลายกล่องต่อเกม) แต่ serial ห้ามซ้ำข้าม copy
    @Query("SELECT COUNT(vi) > 0 FROM VaultItem vi WHERE vi.serialNumber = :serialNumber AND vi.deletedAt IS NULL")
    boolean existsBySerial(@Param("serialNumber") String serialNumber);

    // lookup ด้วย serial อย่างเดียว — ใช้เป็นหลักตอน resolve booking (serial คือ identity ของกล่อง)
    @Query("SELECT vi FROM VaultItem vi LEFT JOIN FETCH vi.vault LEFT JOIN FETCH vi.ownerAgent JOIN FETCH vi.item " +
           "WHERE vi.serialNumber = :serialNumber AND vi.status = 'ACTIVE' AND vi.deletedAt IS NULL")
    Optional<VaultItem> findActiveBySerial(@Param("serialNumber") String serialNumber);

    /**
     * หากล่องจาก serial โดยไม่สนใจ status — ใช้ตอนลงทะเบียนซ้ำ
     * ต้องเห็นกล่อง INACTIVE ด้วย ไม่งั้นจะเผลอสร้างกล่อง serial ซ้ำขึ้นมาอีกใบ
     */
    @Query("SELECT vi FROM VaultItem vi LEFT JOIN FETCH vi.vault LEFT JOIN FETCH vi.ownerAgent JOIN FETCH vi.item " +
           "WHERE vi.serialNumber = :serialNumber AND vi.deletedAt IS NULL")
    Optional<VaultItem> findBySerial(@Param("serialNumber") String serialNumber);

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item WHERE vi.vault.vaultId = :vaultId AND vi.deletedAt IS NULL ORDER BY vi.createdAt")
    List<VaultItem> findByVaultId(@Param("vaultId") String vaultId);

    /** กล่องที่ใช้งานอยู่ทั้งหมด รวมกล่องที่ยังไม่ได้เข้าตู้ */
    @Query("SELECT vi FROM VaultItem vi LEFT JOIN FETCH vi.vault LEFT JOIN FETCH vi.ownerAgent JOIN FETCH vi.item " +
           "WHERE vi.deletedAt IS NULL AND vi.status = 'ACTIVE'")
    List<VaultItem> findAllActive();

    /**
     * เฉพาะกล่องที่อยู่ในตู้แล้ว — ใช้ตรงที่ความหมายคือ "ของที่พร้อมให้จอง"
     * เช่น ตัวเลือกในฟอร์มสร้าง booking และตัวกรอง bound/unbound ของหน้า item
     */
    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.vault LEFT JOIN FETCH vi.ownerAgent JOIN FETCH vi.item " +
           "WHERE vi.deletedAt IS NULL AND vi.status = 'ACTIVE'")
    List<VaultItem> findAllActiveInVault();

    // lookup copy จาก RFID tag (epc ใน IoT event) — ใช้ track ว่ากล่องไหนถูกหยิบ/คืนใน session booking
    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item " +
           "WHERE vi.rfidTag = :rfidTag AND vi.status = 'ACTIVE' AND vi.deletedAt IS NULL")
    Optional<VaultItem> findActiveByRfidTag(@Param("rfidTag") String rfidTag);

    /**
     * แท็กต้องไม่ซ้ำข้ามกล่อง — ถ้าซ้ำ ตอนลูกค้าแตะแท็กระบบจะชี้ผิดกล่อง
     * excludeId ใช้ตอนแก้กล่องเดิม จะได้ไม่ฟ้องว่าชนกับตัวเอง
     */
    @Query("SELECT COUNT(vi) > 0 FROM VaultItem vi " +
           "WHERE vi.rfidTag = :rfidTag AND vi.deletedAt IS NULL AND (:excludeId = '' OR vi.id <> :excludeId)")
    boolean existsByRfidTag(@Param("rfidTag") String rfidTag, @Param("excludeId") String excludeId);

    /** กล่องทั้งหมดของร้านหนึ่ง — ทั้งที่อยู่ในตู้แล้วและที่ยังไม่ได้เข้าตู้ */
    @Query("SELECT vi FROM VaultItem vi LEFT JOIN FETCH vi.vault LEFT JOIN FETCH vi.ownerAgent o JOIN FETCH vi.item " +
           "WHERE o.agentId = :agentId AND vi.deletedAt IS NULL ORDER BY vi.createdAt DESC")
    List<VaultItem> findByOwner(@Param("agentId") String agentId);

    /** กล่องที่ถือแท็กนี้อยู่ ไม่สนใจ status — ใช้บอกว่าแท็กซ้ำไปชนกับกล่องไหน */
    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item " +
           "WHERE vi.rfidTag = :rfidTag AND vi.deletedAt IS NULL")
    Optional<VaultItem> findByRfidTag(@Param("rfidTag") String rfidTag);

    /** กล่องที่ลงทะเบียนแล้วแต่ยังไม่ได้เข้าตู้ — หน้าหลังบ้านใช้ตามเก็บ */
    @Query("SELECT vi FROM VaultItem vi LEFT JOIN FETCH vi.ownerAgent JOIN FETCH vi.item " +
           "WHERE vi.vault IS NULL AND vi.deletedAt IS NULL ORDER BY vi.createdAt DESC")
    List<VaultItem> findUnassigned();
}
