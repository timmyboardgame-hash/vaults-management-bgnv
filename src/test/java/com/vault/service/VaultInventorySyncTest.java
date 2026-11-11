package com.vault.service;

import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.event.VaultInventoryChanged;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ส่งรายการ "แท็กไหนคือเกมอะไร" ไปให้ตู้ (สัญญา §3.7)
 *
 * สองข้อที่พลาดไม่ได้:
 * 1. แถวเดียวผิดรูปแบบ ตู้ปฏิเสธทั้ง batch — ของเสียต้องถูกกรองออกก่อนส่ง ไม่ใช่ส่งไปแล้วหวังดี
 * 2. ส่งทั้งชุดแบบ replace เสมอ เพราะสัญญาไม่มีคำสั่งลบแท็กเดี่ยว
 *    ถ้า upsert อย่างเดียว แท็กของกล่องที่ย้ายออกไปแล้วจะค้างในตู้ตลอดไป
 */
class VaultInventorySyncTest {

    /**
     * ดักคำสั่งที่ส่งออกแทนการ mock — Mockito บน Java 25 mock คลาสจริงไม่ได้
     * (วิธีเดียวกับ VaultCommandTest)
     */
    private static final class IotSpy extends AwsIotService {
        final List<String> things = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        final List<List<Map<String, String>>> batches = new ArrayList<>();
        boolean configured = true;

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override public boolean isConfigured() { return configured; }

        @Override
        public void publishInventorySync(String thingName, String requestId, String mode,
                                         List<Map<String, String>> mappings) {
            things.add(thingName);
            modes.add(mode);
            batches.add(mappings);
        }
    }

    private final VaultItemRepository copies = mock(VaultItemRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final IotSpy iot = new IotSpy();
    private final VaultInventoryService service = new VaultInventoryService(copies, vaults, iot);

    private static Vault vault(String vaultId, String thingName) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        v.setThingName(thingName);
        return v;
    }

    private static VaultItem copy(String serial, String tag, String itemId, String nameEn, String status) {
        Item item = new Item();
        item.setItemId(itemId);
        item.setItemNameEn(nameEn);
        item.setItemNameTh("ชื่อไทย");

        VaultItem vi = new VaultItem();
        vi.setSerialNumber(serial);
        vi.setRfidTag(tag);
        vi.setItem(item);
        vi.setStatus(status);
        return vi;
    }

    private void vaultExists(String vaultId) {
        when(vaults.findByVaultIdAndDeletedAtIsNull(vaultId))
                .thenReturn(Optional.of(vault(vaultId, "vault-locker-001")));
    }

    /** รายการที่ถูกส่งออกไปจริง — ต้องส่ง replace ไปที่ thing name ของตู้นั้นเสมอ */
    private List<Map<String, String>> sentMappings() {
        assertThat(iot.things).containsExactly("vault-locker-001");
        assertThat(iot.modes).containsExactly("replace");
        return iot.batches.get(0);
    }

    @Test
    void sendsOneRowPerTaggedCopy() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE"),
                copy("SN-2", "E2805641554C540000DE0002", "GAME-WING", "Wingspan", "ACTIVE")));

        var result = service.syncVault("vt-001");

        assertThat(sentMappings()).containsExactly(
                Map.of("epc", "E2805641554C540000DE0001", "game_id", "GAME-CATAN", "game_title", "Catan"),
                Map.of("epc", "E2805641554C540000DE0002", "game_id", "GAME-WING", "game_title", "Wingspan"));
        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.thingName()).isEqualTo("vault-locker-001");
    }

    /** ตู้ไม่มีคำสั่งลบแท็กเดี่ยว · ส่งชุดว่างคือวิธีบอกว่าตู้นี้ไม่มีของแล้ว */
    @Test
    void emptyVault_sendsAnEmptyListNotNothing() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of());

        var result = service.syncVault("vt-001");

        assertThat(sentMappings()).isEmpty();
        assertThat(result.sent()).isZero();
    }

    /** กล่องที่ยังไม่ได้แปะแท็ก ตู้ชี้ไม่ได้อยู่แล้ว — ข้ามไป แต่ต้องนับให้คนเห็น */
    @Test
    void copyWithoutTag_isSkippedAndCounted() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE"),
                copy("SN-2", null, "GAME-WING", "Wingspan", "ACTIVE"),
                copy("SN-3", "   ", "GAME-WING", "Wingspan", "ACTIVE")));

        var result = service.syncVault("vt-001");

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skippedNoTag()).isEqualTo(2);
    }

    /** ข้อมูลเก่าที่เข้ามาก่อนจะมีการตรวจรูปแบบ · ถ้าส่งไป ตู้จะตีกลับทั้งชุดแล้วไม่ได้อะไรเลย */
    @Test
    void malformedTag_isDroppedSoTheRestStillReachesTheLocker() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE"),
                copy("SN-BAD", "123", "GAME-WING", "Wingspan", "ACTIVE"),
                copy("SN-BAD2", "ZZ805641554C540000DE0001", "GAME-WING", "Wingspan", "ACTIVE")));

        var result = service.syncVault("vt-001");

        assertThat(sentMappings()).hasSize(1);
        assertThat(result.skippedBadTag()).isEqualTo(2);
    }

    @Test
    void inactiveCopy_isNotSent() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "INACTIVE")));

        assertThat(service.syncVault("vt-001").sent()).isZero();
    }

    /** ตู้ต้องการ game_title ที่ไม่ว่าง — ของที่ไม่มีชื่ออังกฤษใช้รหัสเกมไปก่อน ดีกว่าถูกตีกลับทั้งชุด */
    @Test
    void itemWithoutEnglishName_fallsBackToItsId() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "  ", "ACTIVE")));

        service.syncVault("vt-001");

        assertThat(sentMappings().get(0)).containsEntry("game_title", "GAME-CATAN");
    }

    /** ตู้รับ EPC ตัวพิมพ์ใหญ่ — คนกรอกพิมพ์เล็กมาก็ต้องใช้ได้ */
    @Test
    void lowercaseTag_isNormalisedToUppercase() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "e2805641554c540000de0001", "GAME-CATAN", "Catan", "ACTIVE")));

        service.syncVault("vt-001");

        assertThat(sentMappings().get(0)).containsEntry("epc", "E2805641554C540000DE0001");
    }

    @Test
    void unknownVault_failsLoudly() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.syncVault("vt-404"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vt-404");
        assertThat(iot.batches).isEmpty();
    }

    /** sync ที่ล้มต้องไม่ย้อนไปทำให้คำสั่งที่ commit ไปแล้วพัง — ซ่อมด้วยปุ่มที่หน้า vault ได้ */
    @Test
    void failingSyncAfterCommit_doesNotPropagate() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-001")).thenReturn(Optional.empty());

        service.onInventoryChanged(new VaultInventoryChanged("vt-001", "copy-registered"));
    }

    @Test
    void eventWithoutVault_isIgnored() {
        service.onInventoryChanged(new VaultInventoryChanged(null, "copy-registered"));

        verify(vaults, never()).findByVaultIdAndDeletedAtIsNull(anyString());
    }

    // ───────────────────────── NFC (ตู้ screenless) ─────────────────────────

    /**
     * ตู้ screenless อ่าน NFC 14 hex ไม่ใช่ UHF 24 — ต้องส่งไปพร้อม tag_type
     *
     * เดิมตัวกรองรับแค่ 24 ตัว แท็ก NFC จึงถูกข้ามหมด ตู้ได้ mappings ว่าง
     * ผลคือตู้ไม่รู้จักกล่องในตู้ จอง Session Pass หยิบกล่องไม่ได้
     * และถ้าลูกค้าแตะกล่องผิดสามครั้งตู้จะ lockdown
     */
    @Test
    void nfcTag_isSentWithTagType() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("NOVA-EVENT-001", "046C773CC82A81", "GAME-NOVA", "Nova", "ACTIVE")));

        var result = service.syncVault("vt-001");

        assertThat(sentMappings()).containsExactly(Map.of(
                "epc", "046C773CC82A81",
                "tag_type", "nfc",
                "game_id", "GAME-NOVA",
                "game_title", "Nova"));
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skippedBadTag()).isZero();
    }

    /** ลำดับคีย์ตามที่ตกลงกับฝั่งตู้ — epc, tag_type, game_id, game_title */
    @Test
    void nfcRow_keepsTheAgreedKeyOrder() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("NOVA-EVENT-001", "046C773CC82A81", "GAME-NOVA", "Nova", "ACTIVE")));

        service.syncVault("vt-001");

        assertThat(sentMappings().get(0).keySet())
                .containsExactly("epc", "tag_type", "game_id", "game_title");
    }

    /** UHF ต้องไม่มี tag_type — payload เดิมที่ตู้ใช้งานอยู่ ห้ามเปลี่ยนไปพร้อมกัน */
    @Test
    void uhfTag_isStillSentWithoutTagType() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE")));

        service.syncVault("vt-001");

        assertThat(sentMappings().get(0)).doesNotContainKey("tag_type");
    }

    /** ตู้เดียวมีทั้งสองชนิดได้ — ต้องออกไปทั้งคู่ ไม่ใช่เลือกข้าง */
    @Test
    void nfcAndUhfInTheSameVault_bothGoOut() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE"),
                copy("NOVA-EVENT-001", "046C773CC82A81", "GAME-NOVA", "Nova", "ACTIVE")));

        var result = service.syncVault("vt-001");

        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.skippedBadTag()).isZero();
        assertThat(sentMappings()).extracting(r -> r.get("epc"))
                .containsExactly("E2805641554C540000DE0001", "046C773CC82A81");
    }

    /** คนกรอกแท็ก NFC พิมพ์เล็กมาก็ต้องส่งเป็นตัวพิมพ์ใหญ่เหมือน UHF */
    @Test
    void lowercaseNfcTag_isNormalisedToUppercase() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("NOVA-EVENT-001", "046c773cc82a81", "GAME-NOVA", "Nova", "ACTIVE")));

        service.syncVault("vt-001");

        assertThat(sentMappings().get(0)).containsEntry("epc", "046C773CC82A81");
    }

    /**
     * ตัวเลข "ตู้นี้ควรรู้จัก N แท็ก" ที่หน้า vault ต้องนับ NFC ด้วย
     *
     * นี่คือตัวที่ทำให้ขึ้น 0 ทั้งที่ผูกกล่องไว้ครบ — มันใช้ตัวกรองเดียวกับตอนส่ง
     */
    @Test
    void countSyncable_countsNfcAndUhfButNotUntaggedOrMalformed() {
        vaultExists("vt-001");
        when(copies.findByVaultId("vt-001")).thenReturn(List.of(
                copy("SN-1", "E2805641554C540000DE0001", "GAME-CATAN", "Catan", "ACTIVE"),
                copy("NOVA-EVENT-001", "046C773CC82A81", "GAME-NOVA", "Nova", "ACTIVE"),
                copy("NOVA-EVENT-002", "046C773CC82A82", "GAME-NOVA", "Nova", "ACTIVE"),
                copy("SN-NOTAG", null, "GAME-WING", "Wingspan", "ACTIVE"),
                copy("SN-BAD", "123", "GAME-WING", "Wingspan", "ACTIVE"),
                copy("SN-OFF", "046C773CC82A83", "GAME-NOVA", "Nova", "INACTIVE")));

        assertThat(service.countSyncable("vt-001")).contains(3);
    }

    /** ขอบของรูปแบบ — คี่ สั้นเกิน หรือยาวคาบระหว่าง NFC กับ UHF ต้องไม่ผ่าน */
    @Test
    void tagTypeOf_classifiesByFormat() {
        assertThat(VaultInventoryService.tagTypeOf("E2805641554C540000DE0001")).isEqualTo("uhf");

        assertThat(VaultInventoryService.tagTypeOf("046C773C")).isEqualTo("nfc");           // 8
        assertThat(VaultInventoryService.tagTypeOf("046C773CC82A81")).isEqualTo("nfc");     // 14 ปกติ
        assertThat(VaultInventoryService.tagTypeOf("046C773CC82A8100")).isEqualTo("nfc");   // 16
        assertThat(VaultInventoryService.tagTypeOf("  046C773CC82A81  ")).isEqualTo("nfc"); // trim ให้

        assertThat(VaultInventoryService.tagTypeOf("046C773")).isNull();                    // 7 คี่
        assertThat(VaultInventoryService.tagTypeOf("046C77")).isNull();                     // 6 สั้นเกิน
        assertThat(VaultInventoryService.tagTypeOf("046C773CC82A8100AB")).isNull();         // 18 ไม่เข้าทั้งคู่
        assertThat(VaultInventoryService.tagTypeOf("ZZ6C773CC82A81")).isNull();             // ไม่ใช่ hex
        assertThat(VaultInventoryService.tagTypeOf(null)).isNull();
    }
}
