package com.vault.repository;

import com.vault.entity.VaultStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface VaultStatusRepository extends JpaRepository<VaultStatus, String> {

    @Query("SELECT s FROM VaultStatus s WHERE s.vault.vaultId = :vaultId")
    Optional<VaultStatus> findByVaultId(@Param("vaultId") String vaultId);
}
