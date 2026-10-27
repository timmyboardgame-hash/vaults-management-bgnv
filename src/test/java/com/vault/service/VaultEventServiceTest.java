package com.vault.service;

import com.vault.dto.SystemEventRequest;
import com.vault.entity.Vault;
import com.vault.entity.VaultEvent;
import com.vault.repository.VaultEventRepository;
import com.vault.repository.VaultRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * การเก็บ system event จากตู้ — ของเดิม log ทิ้งอย่างเดียว ย้อนดูไม่ได้ว่าตู้ไหนพังเรื่องอะไร
 */
class VaultEventServiceTest {

    private final VaultEventRepository events = mock(VaultEventRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final VaultEventService service = new VaultEventService(events, vaults);

    private static SystemEventRequest req(String errorType, String severity, String requestId) {
        return new SystemEventRequest(errorType, severity, "ข้อความทดสอบ", requestId);
    }

    private static Vault vault(String vaultId) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        return v;
    }

    private VaultEvent captureSaved() {
        ArgumentCaptor<VaultEvent> c = ArgumentCaptor.forClass(VaultEvent.class);
        verify(events).save(c.capture());
        return c.getValue();
    }

    @Test
    void storesEventAndLinksVaultFoundByThingName() {
        when(vaults.findByThingName("vault-locker-001")).thenReturn(Optional.of(vault("vt-001")));

        VaultEventService.Result r = service.record("vault-locker-001", req("rs485_timeout", "error", "req-1"));

        assertThat(r.stored()).isTrue();
        assertThat(r.vaultId()).isEqualTo("vt-001");
        VaultEvent saved = captureSaved();
        assertThat(saved.getErrorType()).isEqualTo("rs485_timeout");
        assertThat(saved.getSeverity()).isEqualTo("error");
        assertThat(saved.getThingName()).isEqualTo("vault-locker-001");
        assertThat(saved.getVault().getVaultId()).isEqualTo("vt-001");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    /** ตู้ที่ยังไม่ได้ลงทะเบียน (เช่น vault-screenless-001) — เก็บไว้ก่อนดีกว่าทิ้ง */
    @Test
    void unknownThing_stillStoresEventWithoutVault() {
        when(vaults.findByThingName(anyString())).thenReturn(Optional.empty());

        VaultEventService.Result r = service.record("vault-screenless-001", req("door_timeout", "warning", null));

        assertThat(r.stored()).isTrue();
        assertThat(r.vaultId()).isNull();
        VaultEvent saved = captureSaved();
        assertThat(saved.getVault()).isNull();
        assertThat(saved.getThingName()).isEqualTo("vault-screenless-001");
    }

    @Test
    void devToolEvents_areSkipped() {
        VaultEventService.Result r = service.record("vault-locker-001", req("dev_inventory_list", "info", null));

        assertThat(r.stored()).isFalse();
        assertThat(r.reason()).isEqualTo("dev-tool");
        verify(events, never()).save(any());
    }

    @Test
    void duplicateRequestId_isSkipped() {
        when(events.existsByRequestIdAndErrorType("req-1", "hardware_error")).thenReturn(true);

        VaultEventService.Result r = service.record("vault-locker-001", req("hardware_error", "error", "req-1"));

        assertThat(r.stored()).isFalse();
        assertThat(r.reason()).isEqualTo("duplicate");
        verify(events, never()).save(any());
    }

    /** event ที่ไม่ได้มาจากคำสั่งจะไม่มี request id — ห้ามถือว่าซ้ำกันเอง */
    @Test
    void nullRequestId_isNeverTreatedAsDuplicate() {
        when(vaults.findByThingName(anyString())).thenReturn(Optional.empty());

        assertThat(service.record("vault-locker-001", req("rfid_disconnect", "error", null)).stored()).isTrue();
        verify(events, never()).existsByRequestIdAndErrorType(any(), anyString());
    }

    @Test
    void missingFields_fallBackToSafeDefaults() {
        when(vaults.findByThingName(anyString())).thenReturn(Optional.empty());

        service.record("vault-locker-001", req(null, null, null));

        VaultEvent saved = captureSaved();
        assertThat(saved.getErrorType()).isEqualTo("unknown");
        assertThat(saved.getSeverity()).isEqualTo("error");
    }
}
