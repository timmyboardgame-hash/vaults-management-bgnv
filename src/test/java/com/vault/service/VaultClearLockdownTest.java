package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.entity.Vault;
import com.vault.repository.VaultRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ปลด lockdown — ตู้เข้า lockdown เองจากการแตะ tag ผิด 3 ครั้ง แล้วออกเองไม่ได้
 * ถ้าคำสั่งนี้ส่งไปผิดชื่อ thing ตู้จะไม่ได้รับ และไม่มีอะไรฟ้องเพราะ publish เป็นแบบไม่รอคำตอบ
 */
class VaultClearLockdownTest {

    /**
     * ดักคำสั่งที่ส่งออกแทนการ mock — Mockito บน Java 25 mock คลาสจริงไม่ได้ (mock ได้เฉพาะ interface)
     * สืบทอดแล้ว override เอาเองจึงตรงไปตรงมากว่า
     */
    private static final class IotSpy extends AwsIotService {
        final List<String> things = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override
        public void publishClearLockdown(String thingName, String requestId, String reason) {
            things.add(thingName);
            reasons.add(reason);
        }
    }

    private final VaultRepository vaults = mock(VaultRepository.class);
    private final IotSpy iot = new IotSpy();
    private final VaultService service = new VaultService(vaults, iot);

    private static Vault vault(String vaultId, String thingName) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        v.setThingName(thingName);
        return v;
    }

    @Test
    void publishesToTheThingName() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-001"))
                .thenReturn(Optional.of(vault("vt-001", "vault-locker-001")));

        Map<String, String> result = service.clearLockdown("vt-001", "ลูกค้าแตะผิดเอง");

        assertThat(iot.things).containsExactly("vault-locker-001");
        assertThat(iot.reasons).containsExactly("ลูกค้าแตะผิดเอง");
        assertThat(result.get("status")).isEqualTo("command_sent");
    }

    /** ตู้ที่ยังไม่ได้ตั้งชื่อ thing ใช้รหัสตู้แทน เหมือนคำสั่งอื่นทุกตัว */
    @Test
    void fallsBackToVaultIdWhenThingNameMissing() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-002"))
                .thenReturn(Optional.of(vault("vt-002", null)));

        service.clearLockdown("vt-002", null);

        assertThat(iot.things).containsExactly("vt-002");
    }

    @Test
    void unknownVault_sendsNothing() {
        when(vaults.findByVaultIdAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.clearLockdown("ไม่มีตู้นี้", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vault not found");
        assertThat(iot.things).isEmpty();
    }
}
