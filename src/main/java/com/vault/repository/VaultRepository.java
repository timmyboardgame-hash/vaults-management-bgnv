package com.vault.repository;

import com.vault.entity.Vault;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VaultRepository extends JpaRepository<Vault, String> {

    Optional<Vault> findByVaultIdAndDeletedAtIsNull(String vaultId);

    // callback จาก Lambda ส่งชื่อ thing มา ไม่ใช่รหัสตู้ — ตู้เก่าที่ยังไม่ได้ตั้ง thing_name ต้องหาด้วย vaultId ได้ด้วย
    @org.springframework.data.jpa.repository.Query(
        "SELECT v FROM Vault v WHERE v.deletedAt IS NULL AND (v.thingName = :name OR (v.thingName IS NULL AND v.vaultId = :name))")
    Optional<Vault> findByThingName(@org.springframework.data.repository.query.Param("name") String name);

    List<Vault> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByVaultIdAndDeletedAtIsNull(String vaultId);
}
