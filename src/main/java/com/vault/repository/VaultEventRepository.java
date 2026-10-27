package com.vault.repository;

import com.vault.entity.VaultEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VaultEventRepository extends JpaRepository<VaultEvent, String> {

    /** กัน event ซ้ำจากการ retry — สัญญา §7 ให้ dedupe ด้วย request_id */
    boolean existsByRequestIdAndErrorType(String requestId, String errorType);

    /** นับแยกชนิด: [errorType, severity, จำนวน] — ทุกตู้ */
    @Query("SELECT e.errorType, e.severity, COUNT(e) FROM VaultEvent e " +
           "WHERE e.occurredAt >= :from AND e.occurredAt < :to " +
           "GROUP BY e.errorType, e.severity ORDER BY COUNT(e) DESC")
    List<Object[]> countByType(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** นับแยกชนิดเฉพาะตู้เดียว */
    @Query("SELECT e.errorType, e.severity, COUNT(e) FROM VaultEvent e " +
           "WHERE e.vault.vaultId = :vaultId AND e.occurredAt >= :from AND e.occurredAt < :to " +
           "GROUP BY e.errorType, e.severity ORDER BY COUNT(e) DESC")
    List<Object[]> countByTypeForVault(@Param("vaultId") String vaultId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    /** รายการล่าสุด ใช้โชว์ว่าเพิ่งเกิดอะไรขึ้นบ้าง */
    @Query("SELECT e FROM VaultEvent e LEFT JOIN FETCH e.vault " +
           "WHERE e.occurredAt >= :from AND e.occurredAt < :to " +
           "AND (:vaultId = '' OR e.vault.vaultId = :vaultId) " +
           "ORDER BY e.occurredAt DESC")
    List<VaultEvent> findRecent(@Param("vaultId") String vaultId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);
}
