package com.vault.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "vaults")
public class Vault {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "vault_id", unique = true, nullable = false)
    private String vaultId;

    @Column(name = "vault_name", nullable = false)
    private String vaultName;

    @Column(name = "vault_status", nullable = false)
    private String vaultStatus;

    @Column(name = "vault_slot", nullable = false)
    private Integer vaultSlot;

    @Column(name = "description")
    private String description;

    // ชื่อ thing ฝั่ง AWS IoT — ตู้จริงชื่อคนละแบบกับรหัสตู้ (เช่น vault-locker-001 กับ vt-001)
    // ว่างไว้ = ใช้ vaultId เหมือนพฤติกรรมเดิม ตู้ที่ตั้งชื่อตรงกันอยู่แล้วจึงไม่ต้องแก้อะไร
    @Column(name = "thing_name")
    private String thingName;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "vault", fetch = FetchType.LAZY)
    private List<VaultItem> vaultItems;

    public Vault() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVaultId() { return vaultId; }
    public void setVaultId(String vaultId) { this.vaultId = vaultId; }

    public String getVaultName() { return vaultName; }
    public void setVaultName(String vaultName) { this.vaultName = vaultName; }

    public String getVaultStatus() { return vaultStatus; }
    public void setVaultStatus(String vaultStatus) { this.vaultStatus = vaultStatus; }

    public Integer getVaultSlot() { return vaultSlot; }
    public void setVaultSlot(Integer vaultSlot) { this.vaultSlot = vaultSlot; }

    public String getThingName() { return thingName; }
    public void setThingName(String thingName) { this.thingName = thingName; }

    /** ชื่อที่ใช้คุยกับ AWS IoT จริง — ต้องเรียกตัวนี้เสมอ อย่าใช้ vaultId ตรงๆ */
    public String effectiveThingName() {
        return thingName != null && !thingName.isBlank() ? thingName : vaultId;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public List<VaultItem> getVaultItems() { return vaultItems; }
    public void setVaultItems(List<VaultItem> vaultItems) { this.vaultItems = vaultItems; }
}
