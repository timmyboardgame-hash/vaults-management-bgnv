package com.vault.repository;

import com.vault.entity.BorrowRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, String> {

    // งานสรุปรายวันลบของวันนั้นก่อนเขียนใหม่ — ทำให้รันซ้ำได้ผลเท่าเดิม
    @Modifying
    @Query("DELETE FROM BorrowRecord r WHERE r.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);

    // heatmap — [วันในสัปดาห์ 1-7, ชั่วโมง 0-23, จำนวนรอบ] · ใช้คอลัมน์ที่เก็บแยกไว้ ไม่ต้องทำ date math บน epoch millis
    @Query("SELECT r.pickupDow, r.pickupHour, COUNT(r) FROM BorrowRecord r " +
           "WHERE r.statDate >= :from AND r.statDate <= :to GROUP BY r.pickupDow, r.pickupHour")
    List<Object[]> countByHour(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT r.pickupDow, r.pickupHour, COUNT(r) FROM BorrowRecord r " +
           "WHERE r.vault.vaultId = :vaultId AND r.statDate >= :from AND r.statDate <= :to " +
           "GROUP BY r.pickupDow, r.pickupHour")
    List<Object[]> countByHourForVault(@Param("vaultId") String vaultId,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    // หนึ่ง session หยิบกี่กล่อง — [booking db id, จำนวนรอบ] · คืน id ด้วยเพื่อรวมกับของวันนี้ได้ไม่นับซ้ำ
    @Query("SELECT r.booking.id, COUNT(r) FROM BorrowRecord r " +
           "WHERE r.bookingType = 'event' AND r.statDate >= :from AND r.statDate <= :to GROUP BY r.booking.id")
    List<Object[]> cyclesPerEventBooking(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT r.booking.id, COUNT(r) FROM BorrowRecord r " +
           "WHERE r.bookingType = 'event' AND r.vault.vaultId = :vaultId " +
           "AND r.statDate >= :from AND r.statDate <= :to GROUP BY r.booking.id")
    List<Object[]> cyclesPerEventBookingForVault(@Param("vaultId") String vaultId,
                                                 @Param("from") LocalDate from, @Param("to") LocalDate to);
}
