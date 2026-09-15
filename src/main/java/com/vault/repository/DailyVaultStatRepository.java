package com.vault.repository;

import com.vault.entity.DailyVaultStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface DailyVaultStatRepository extends JpaRepository<DailyVaultStat, String> {

    @Modifying
    @Query("DELETE FROM DailyVaultStat s WHERE s.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);
}
