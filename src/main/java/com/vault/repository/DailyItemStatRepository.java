package com.vault.repository;

import com.vault.entity.DailyItemStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface DailyItemStatRepository extends JpaRepository<DailyItemStat, String> {

    @Modifying
    @Query("DELETE FROM DailyItemStat s WHERE s.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);
}
