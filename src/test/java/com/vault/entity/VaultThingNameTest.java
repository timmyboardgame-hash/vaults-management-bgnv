package com.vault.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ชื่อ thing ฝั่ง AWS ไม่ตรงกับรหัสตู้ในระบบ (ของจริงคือ vault-locker-001 กับ vt-001)
 * ถ้าเลือกชื่อผิด คำสั่งจะ publish ขึ้น topic ที่ไม่มีตู้ไหนฟังอยู่ และหายไปเงียบๆ
 */
class VaultThingNameTest {

    private static Vault vault(String vaultId, String thingName) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        v.setThingName(thingName);
        return v;
    }

    @Test
    void thingNameSet_isUsed() {
        assertThat(vault("vt-001", "vault-locker-001").effectiveThingName())
                .isEqualTo("vault-locker-001");
    }

    @Test
    void thingNameNull_fallsBackToVaultId() {
        assertThat(vault("vt-001", null).effectiveThingName()).isEqualTo("vt-001");
    }

    @Test
    void thingNameBlank_fallsBackToVaultId() {
        assertThat(vault("vt-001", "   ").effectiveThingName()).isEqualTo("vt-001");
    }
}
