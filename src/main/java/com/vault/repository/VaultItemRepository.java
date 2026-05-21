package com.vault.repository;

import com.vault.entity.VaultItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VaultItemRepository extends JpaRepository<VaultItem, String> {

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.vault WHERE vi.item.id = :itemId AND vi.status = 'ACTIVE' AND vi.deletedAt IS NULL")
    Optional<VaultItem> findActiveByItemId(@Param("itemId") String itemId);

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.item WHERE vi.vault.vaultId = :vaultId AND vi.deletedAt IS NULL ORDER BY vi.slotId")
    List<VaultItem> findByVaultId(@Param("vaultId") String vaultId);

    @Query("SELECT vi FROM VaultItem vi WHERE vi.vault.vaultId = :vaultId AND vi.slotId = :slotId AND vi.deletedAt IS NULL")
    Optional<VaultItem> findByVaultIdAndSlotId(@Param("vaultId") String vaultId, @Param("slotId") String slotId);

    @Query("SELECT vi FROM VaultItem vi JOIN FETCH vi.vault JOIN FETCH vi.item WHERE vi.deletedAt IS NULL AND vi.status = 'ACTIVE'")
    List<VaultItem> findAllActive();
}
