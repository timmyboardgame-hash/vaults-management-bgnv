package com.vault.repository;

import com.vault.entity.DailyItemStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DailyItemStatRepository extends JpaRepository<DailyItemStat, String> {

    @Modifying
    @Query("DELETE FROM DailyItemStat s WHERE s.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);

    // กล่องจริงระบุด้วย serial — [ชื่อเกม, serial, vaultId, ยืมกี่ครั้ง, นาทีรวม, คืนช้า]
    @Query("SELECT s.item.itemNameEn, s.serialNumber, s.vault.vaultId, " +
           "SUM(s.borrowCount), SUM(s.busyMinutes), SUM(s.lateCount) " +
           "FROM DailyItemStat s WHERE s.statDate >= :from AND s.statDate <= :to " +
           "GROUP BY s.item.itemNameEn, s.serialNumber, s.vault.vaultId")
    List<Object[]> sumByBox(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT s.item.itemNameEn, s.serialNumber, s.vault.vaultId, " +
           "SUM(s.borrowCount), SUM(s.busyMinutes), SUM(s.lateCount) " +
           "FROM DailyItemStat s WHERE s.vault.vaultId = :vaultId AND s.statDate >= :from AND s.statDate <= :to " +
           "GROUP BY s.item.itemNameEn, s.serialNumber, s.vault.vaultId")
    List<Object[]> sumByBoxForVault(@Param("vaultId") String vaultId,
                                    @Param("from") LocalDate from, @Param("to") LocalDate to);
}
