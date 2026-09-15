package com.vault.repository;

import com.vault.entity.BorrowRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface BorrowRecordRepository extends JpaRepository<BorrowRecord, String> {

    // งานสรุปรายวันลบของวันนั้นก่อนเขียนใหม่ — ทำให้รันซ้ำได้ผลเท่าเดิม
    @Modifying
    @Query("DELETE FROM BorrowRecord r WHERE r.statDate = :date")
    int deleteByStatDate(@Param("date") LocalDate date);
}
