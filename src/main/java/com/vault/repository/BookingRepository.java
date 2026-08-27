package com.vault.repository;

import com.vault.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByBookingIdAndDeletedAtIsNull(String bookingId);

    List<Booking> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByBookingIdAndDeletedAtIsNull(String bookingId);

    @Query("SELECT b FROM Booking b WHERE b.agent.agentId = :agentId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByAgentBusinessId(@Param("agentId") String agentId);

    @Query("SELECT b FROM Booking b WHERE b.item.itemId = :itemId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByItemBusinessId(@Param("itemId") String itemId);

    @Query("SELECT b FROM Booking b WHERE b.bookingStatus = :status AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByStatus(@Param("status") String status);

    // ไม่กรอง deletedAt — booking ที่ CANCELLED ถูก soft-delete แต่ต้องยังโชว์ใน history
    // sort ตาม updatedAt เพื่อให้ booking ที่มี activity (IoT event) ล่าสุดขึ้นก่อน
    @Query("SELECT b FROM Booking b WHERE b.vault.vaultId = :vaultId AND b.item.itemId = :itemId " +
           "ORDER BY b.updatedAt DESC")
    List<Booking> findByVaultAndItemBusinessId(@Param("vaultId") String vaultId, @Param("itemId") String itemId);

    @Query("SELECT b FROM Booking b WHERE b.vault.vaultId = :vaultId AND b.item.itemId = :itemId " +
           "AND b.bookingStatus NOT IN ('RETURNED','CANCELLED','FAILED') AND b.deletedAt IS NULL " +
           "ORDER BY b.updatedAt DESC")
    List<Booking> findActiveByVaultAndItemBusinessId(@Param("vaultId") String vaultId, @Param("itemId") String itemId);

    // กัน double-booking กล่องเดียวกัน (item DB id + serial) — :itemId คือ items.id (UUID) ไม่ใช่ business ID
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.item.id = :itemId AND b.serialNumber = :serialNumber " +
           "AND b.bookingStatus NOT IN ('RETURNED','CANCELLED','FAILED') AND b.deletedAt IS NULL")
    boolean existsActiveByItemAndSerial(@Param("itemId") String itemId, @Param("serialNumber") String serialNumber);

    // Vault Board — booking ทุกใบของตู้: ที่ยังไม่จบ + ที่จบแล้วตั้งแต่ :since
    // ไม่กรอง deletedAt เพราะ game booking ที่ CANCELLED ถูก soft-delete แต่ยังต้องแสดงในประวัติ
    @Query("SELECT b FROM Booking b JOIN FETCH b.item JOIN FETCH b.agent WHERE b.vault.vaultId = :vaultId " +
           "AND (b.bookingStatus NOT IN ('RETURNED','CANCELLED','FAILED') OR b.updatedAt >= :since) " +
           "ORDER BY b.updatedAt DESC")
    List<Booking> findBoardBookings(@Param("vaultId") String vaultId,
                                    @Param("since") java.time.LocalDateTime since);

    // booking ที่ยังไม่จบแต่เลยเวลาสิ้นสุดแล้ว — scheduler ใช้ปิด session booking ที่หมดเวลา
    // รวม OVERDUE ด้วย: เช็คซ้ำทุกรอบเผื่อของครบแล้ว (กันเคส event คืนชิ้นสุดท้ายประมวลผลพลาด)
    @Query("SELECT b FROM Booking b JOIN FETCH b.item WHERE b.bookingTimeEnd < :now " +
           "AND b.bookingStatus IN ('PENDING','CONFIRMED','ACTIVE','OVERDUE') AND b.deletedAt IS NULL")
    List<Booking> findOpenPastEnd(@Param("now") java.time.LocalDateTime now);
}
