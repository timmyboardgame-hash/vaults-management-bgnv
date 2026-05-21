package com.vault.repository;

import com.vault.entity.Vault;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VaultRepository extends JpaRepository<Vault, String> {

    Optional<Vault> findByVaultIdAndDeletedAtIsNull(String vaultId);

    List<Vault> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByVaultIdAndDeletedAtIsNull(String vaultId);
}
