package com.vault.repository;

import com.vault.entity.DailyVaultStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface DailyVaultStatRepository extends JpaRepository<DailyVaultStat, String> {

    @Modifying
    @Query("DELETE FROM DailyVaultStat s WHERE s.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);

    // โหลดทั้งแถวแล้วรวมใน Java — 24 ตู้ × 3 ปี ยังไม่ถึง 27,000 แถว และต้องรวมกับผลของวันนี้ที่คำนวณสด
    @Query("SELECT s FROM DailyVaultStat s JOIN FETCH s.vault WHERE s.statDate >= :from AND s.statDate <= :to")
    List<DailyVaultStat> findRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT s FROM DailyVaultStat s JOIN FETCH s.vault v " +
           "WHERE v.vaultId = :vaultId AND s.statDate >= :from AND s.statDate <= :to")
    List<DailyVaultStat> findRangeForVault(@Param("vaultId") String vaultId,
                                           @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT MAX(s.computedAt) FROM DailyVaultStat s")
    LocalDateTime findLatestComputedAt();
}
