package com.vault.service;

import com.vault.dto.VaultItemResponse;
import com.vault.entity.Agent;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * กล่องที่ยังไม่มีตู้ต้องแปลงเป็น response ได้
 *
 * ของเดิมสมมติว่ากล่องมีตู้เสมอ (vault_items บังคับ vault_id)
 * ตั้งแต่แอปร้านลงทะเบียนกล่องตอนยังไม่เข้าตู้ สมมติฐานนั้นใช้ไม่ได้แล้ว
 * ถ้าไม่กัน จะระเบิดเป็น NPE กลางหน้า Vault Items
 */
class VaultItemPlacementTest {

    private static VaultItem copy(Vault vault, Agent owner) {
        Item item = new Item();
        item.setItemId("GAME-CATAN");
        item.setItemNameEn("Catan");
        item.setItemNameTh("คาทาน");

        VaultItem vi = new VaultItem();
        vi.setId("copy-1");
        vi.setItem(item);
        vi.setVault(vault);
        vi.setOwnerAgent(owner);
        vi.setSerialNumber("SN-1");
        vi.setStatus("ACTIVE");
        return vi;
    }

    private static Vault vault() {
        Vault v = new Vault();
        v.setVaultId("vt-001");
        v.setVaultName("ตู้สาขาลาดพร้าว");
        return v;
    }

    private static Agent shop() {
        Agent a = new Agent();
        a.setAgentId("shop-001");
        return a;
    }

    @Test
    void copyWithoutVault_mapsToUnassignedInsteadOfThrowing() {
        VaultItemResponse r = VaultItemService.toResponse(copy(null, shop()));

        assertThat(r.placement()).isEqualTo("UNASSIGNED");
        assertThat(r.inVault()).isFalse();
        assertThat(r.vaultId()).isNull();
        assertThat(r.vaultName()).isNull();
        assertThat(r.ownerAgentId()).isEqualTo("shop-001");
    }

    @Test
    void copyInVault_keepsVaultDetails() {
        VaultItemResponse r = VaultItemService.toResponse(copy(vault(), shop()));

        assertThat(r.placement()).isEqualTo("IN_VAULT");
        assertThat(r.inVault()).isTrue();
        assertThat(r.vaultId()).isEqualTo("vt-001");
        assertThat(r.vaultName()).isEqualTo("ตู้สาขาลาดพร้าว");
    }

    /** กล่องที่ผูกจากหน้าหลังบ้านก่อนจะมี API ลงทะเบียน ยังไม่มีเจ้าของ — ต้องไม่พัง */
    @Test
    void legacyCopyWithoutOwner_stillMaps() {
        VaultItemResponse r = VaultItemService.toResponse(copy(vault(), null));

        assertThat(r.ownerAgentId()).isNull();
        assertThat(r.inVault()).isTrue();
    }
}
