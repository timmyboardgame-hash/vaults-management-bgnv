package com.vault.repository;

import com.vault.entity.Binding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BindingRepository extends JpaRepository<Binding, String> {

    @Query("SELECT b FROM Binding b WHERE b.agent.agentId = :agentId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Binding> findActiveByAgentId(@Param("agentId") String agentId);

    @Query("SELECT b FROM Binding b WHERE b.vault.vaultId = :vaultId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Binding> findActiveByVaultId(@Param("vaultId") String vaultId);

    @Query("SELECT b FROM Binding b WHERE b.agent.agentId = :agentId AND b.vault.vaultId = :vaultId AND b.deletedAt IS NULL")
    Optional<Binding> findActiveByAgentAndVault(@Param("agentId") String agentId, @Param("vaultId") String vaultId);

    List<Binding> findAllByDeletedAtIsNullOrderByCreatedAtDesc();
}
