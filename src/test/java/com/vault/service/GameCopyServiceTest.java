package com.vault.service;

import com.vault.dto.GameCopyResponse;
import com.vault.dto.RegisterGameCopyRequest;
import com.vault.dto.RegisterGameCopyRequest.ItemPayload;
import com.vault.dto.UpdateGameCopyRequest;
import com.vault.entity.Agent;
import com.vault.entity.Binding;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.event.VaultInventoryChanged;
import com.vault.exception.ApiCodeException;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.ItemRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ลงทะเบียนกล่องเกมจากแอปร้าน
 *
 * สองเรื่องที่เทสนี้คุมไว้เป็นหลัก:
 * 1. กล่องที่ยังไม่มีตู้ต้องเก็บได้ แต่ต้องบอกชัดว่าจองไม่ได้ (bookable=false)
 * 2. แท็กซ้ำต้องไม่หลุดเข้าไป — ถ้าหลุด ตอนลูกค้าแตะแท็กที่ตู้ ระบบจะชี้ผิดกล่อง
 */
class GameCopyServiceTest {

    private static final String TAG = "E2805641554C540000DE0001";

    private final VaultItemRepository copies = mock(VaultItemRepository.class);
    private final VaultRepository vaults = mock(VaultRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ItemRepository items = mock(ItemRepository.class);
    private final BindingRepository bindings = mock(BindingRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);

    private final org.springframework.context.ApplicationEventPublisher events =
            mock(org.springframework.context.ApplicationEventPublisher.class);

    // batch เรียกตัวเองผ่าน supplier นี้ — ในเทสชี้กลับมาที่ instance เดิมได้เลย
    private final GameCopyService service =
            new GameCopyService(copies, vaults, agents, items, bindings, bookings, events, () -> this.service);

    private static Agent shop(String agentId) {
        Agent a = new Agent();
        a.setAgentId(agentId);
        return a;
    }

    private static Vault vault(String vaultId) {
        Vault v = new Vault();
        v.setVaultId(vaultId);
        v.setVaultName("ตู้ " + vaultId);
        return v;
    }

    private static Item item(String itemId) {
        Item i = new Item();
        i.setId("db-" + itemId);
        i.setItemId(itemId);
        i.setItemNameEn("Catan");
        i.setItemNameTh("คาทาน");
        return i;
    }

    private static RegisterGameCopyRequest req(String serial, String tag, String vaultId) {
        return new RegisterGameCopyRequest(serial, tag, vaultId,
                new ItemPayload("GAME-CATAN", "Catan", "คาทาน", null, null, null, null, null, null, null));
    }

    @BeforeEach
    void shopExistsAndItemIsKnown() {
        when(agents.findByAgentIdAndDeletedAtIsNull("shop-001")).thenReturn(Optional.of(shop("shop-001")));
        when(items.findByItemIdAndDeletedAtIsNull("GAME-CATAN")).thenReturn(Optional.of(item("GAME-CATAN")));
        when(copies.findBySerial(anyString())).thenReturn(Optional.empty());
        when(copies.existsByRfidTag(anyString(), anyString())).thenReturn(false);
        when(copies.save(any(VaultItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(copies.saveAndFlush(any(VaultItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bindings.findActiveByAgentId(anyString())).thenReturn(List.of());
    }

    private static String code(Throwable t) {
        return ((ApiCodeException) t).getCode();
    }

    // ───────────────── ข้อมูลที่ต้องมี ─────────────────

    @Test
    void unknownShop_isNotFound() {
        assertThatThrownBy(() -> service.register("shop-999", req("SN-1", TAG, null)))
                .isInstanceOf(ApiCodeException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("SHOP_NOT_FOUND"));
    }

    @Test
    void missingSerial_isRejected() {
        assertThatThrownBy(() -> service.register("shop-001", req("  ", TAG, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SERIAL_REQUIRED"));
    }

    /** แท็กถูกแปะตั้งแต่ตอนลงทะเบียน จึงไม่ใช่ช่องที่ค่อยเติมทีหลัง */
    @Test
    void missingTag_isRejected() {
        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("RFID_TAG_REQUIRED"));
    }

    /** เจอบ่อยเวลาคนพิมพ์เลขแท็กเอง — ต้องแยกจาก "ไม่ส่งมา" ให้แอปบอกผู้ใช้ได้ถูก */
    @Test
    void shortOrNonHexTag_isAFormatError() {
        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", "E280", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("RFID_TAG_FORMAT"));
        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", "ZZ805641554C540000DE0001", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("RFID_TAG_FORMAT"));
    }

    /** กล่องสองใบถือแท็กเดียวกัน = ตู้แยกไม่ออกว่าใบไหนถูกหยิบ */
    @Test
    void tagAlreadyOnAnotherCopy_isRejected() {
        when(copies.existsByRfidTag(TAG, "")).thenReturn(true);
        VaultItem other = new VaultItem();
        other.setSerialNumber("SN-OTHER");
        other.setItem(item("GAME-CATAN"));
        when(copies.findByRfidTag(TAG)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", TAG, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("RFID_TAG_IN_USE"))
                .hasMessageContaining("SN-OTHER");  // บอกว่าไปซ้ำกับใบไหน ไม่ใช่แค่ว่าซ้ำ

        verify(copies, never()).save(any());
    }

    // ───────────────── ที่อยู่ของกล่อง ─────────────────

    /** ร้านยังไม่ผูกตู้ หรือผูกหลายตู้ — เก็บกล่องไว้ก่อน แล้วบอกให้ชัดว่ายังจองไม่ได้ */
    @Test
    void withoutVault_copyIsStoredButNotBookable() {
        GameCopyResponse c = service.register("shop-001", req("SN-1", TAG, null)).copy();

        assertThat(c.placement()).isEqualTo("UNASSIGNED");
        assertThat(c.vaultId()).isNull();
        assertThat(c.bookable()).isFalse();
        assertThat(c.shopId()).isEqualTo("shop-001");
    }

    /** ร้านผูกตู้เดียว ไม่ต้องให้แอปเดา — วางให้เลย */
    @Test
    void shopWithExactlyOneVault_getsItAutomatically() {
        Binding b = new Binding();
        b.setVault(vault("vt-001"));
        when(bindings.findActiveByAgentId("shop-001")).thenReturn(List.of(b));

        GameCopyResponse c = service.register("shop-001", req("SN-1", TAG, null)).copy();

        assertThat(c.placement()).isEqualTo("IN_VAULT");
        assertThat(c.vaultId()).isEqualTo("vt-001");
        assertThat(c.bookable()).isTrue();
    }

    /** ผูกหลายตู้ = เดาแทนไม่ได้ ของอาจไปอยู่ผิดสาขา */
    @Test
    void shopWithSeveralVaults_isLeftUnassigned() {
        Binding a = new Binding(); a.setVault(vault("vt-001"));
        Binding b = new Binding(); b.setVault(vault("vt-002"));
        when(bindings.findActiveByAgentId("shop-001")).thenReturn(List.of(a, b));

        assertThat(service.register("shop-001", req("SN-1", TAG, null)).copy().placement())
                .isEqualTo("UNASSIGNED");
    }

    @Test
    void unknownVaultId_isNotFound() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", TAG, "vt-404")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VAULT_NOT_FOUND"));
    }

    /** กันร้านหนึ่งยัดของเข้าตู้ของอีกร้าน — ทุกคนใช้ x-auth-code ตัวเดียวกัน */
    @Test
    void vaultOfAnotherShop_isRejected() {
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-009")).thenReturn(Optional.of(vault("vt-009")));
        when(bindings.findActiveByAgentAndVault("shop-001", "vt-009")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", TAG, "vt-009")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("VAULT_NOT_BOUND_TO_SHOP"));
        verify(copies, never()).save(any());
    }

    // ───────────────── บอกตู้ว่าของเปลี่ยน ─────────────────

    /** ตู้ต้องได้รายการแท็กชุดใหม่ ไม่งั้นแตะกล่องที่เพิ่งลงทะเบียนแล้วตู้มองเป็นของแปลกปลอม */
    @Test
    void copyPlacedInVault_asksForAnInventorySync() {
        Binding b = new Binding();
        b.setVault(vault("vt-001"));
        when(bindings.findActiveByAgentId("shop-001")).thenReturn(List.of(b));

        service.register("shop-001", req("SN-1", TAG, null));

        var e = org.mockito.ArgumentCaptor.forClass(VaultInventoryChanged.class);
        verify(events).publishEvent(e.capture());
        assertThat(e.getValue().vaultId()).isEqualTo("vt-001");
    }

    /** กล่องที่ยังไม่มีตู้ ยังไม่มีตู้ไหนต้องรู้จักแท็กนี้ — ไม่ต้องยิงอะไรออกไป */
    @Test
    void unassignedCopy_doesNotSyncAnyLocker() {
        service.register("shop-001", req("SN-1", TAG, null));

        verify(events, never()).publishEvent(any(VaultInventoryChanged.class));
    }

    // ───────────────── catalog เกม ─────────────────

    @Test
    void newItemId_withoutNames_cannotBeCreated() {
        when(items.findByItemIdAndDeletedAtIsNull("GAME-NEW")).thenReturn(Optional.empty());
        var payload = new ItemPayload("GAME-NEW", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.register("shop-001",
                new RegisterGameCopyRequest("SN-1", TAG, null, payload)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ITEM_FIELDS_REQUIRED"));
    }

    @Test
    void newItemId_withNames_isCreatedOnTheFly() {
        when(items.findByItemIdAndDeletedAtIsNull("GAME-NEW")).thenReturn(Optional.empty());
        when(items.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));
        var payload = new ItemPayload("GAME-NEW", "Wingspan", "วิงสแปน", null, 1, 5, 40, 70, 2.4, null);

        GameCopyResponse c = service.register("shop-001",
                new RegisterGameCopyRequest("SN-1", TAG, null, payload)).copy();

        assertThat(c.item().created()).isTrue();
        assertThat(c.item().itemId()).isEqualTo("GAME-NEW");
    }

    /** ของที่มีอยู่แล้วใช้ของเดิม ไม่เขียนทับชื่อที่หลังบ้านแก้ไว้ */
    @Test
    void knownItemId_isReusedNotOverwritten() {
        GameCopyResponse c = service.register("shop-001", req("SN-1", TAG, null)).copy();

        assertThat(c.item().created()).isFalse();
        verify(items, never()).save(any());
    }

    // ───────────────── ยิงซ้ำ ─────────────────

    private VaultItem registered(String serial, String tag, String ownerId) {
        VaultItem vi = new VaultItem();
        vi.setId("copy-1");
        vi.setSerialNumber(serial);
        vi.setRfidTag(tag);
        vi.setItem(item("GAME-CATAN"));
        vi.setOwnerAgent(shop(ownerId));
        vi.setStatus("ACTIVE");
        return vi;
    }

    /** แอป retry ได้โดยไม่ต้องจำ request id — ข้อมูลเดิมถือว่าสำเร็จ ไม่สร้างใบที่สอง */
    @Test
    void sameSerialSameData_returnsExistingCopyWithoutCreating() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));

        var result = service.register("shop-001", req("SN-1", TAG, null));

        assertThat(result.created()).isFalse();
        assertThat(result.copy().serialNumber()).isEqualTo("SN-1");
        verify(copies, never()).save(any());
    }

    @Test
    void sameSerialDifferentTag_conflicts() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));

        assertThatThrownBy(() -> service.register("shop-001",
                req("SN-1", "E2805641554C540000DE9999", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SERIAL_ALREADY_REGISTERED"));
    }

    @Test
    void sameSerialOtherShop_conflicts() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-002")));

        assertThatThrownBy(() -> service.register("shop-001", req("SN-1", TAG, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SERIAL_ALREADY_REGISTERED"));
    }

    // ───────────────── แก้ / ลบ ─────────────────

    /** เอากล่องที่ลงทะเบียนไว้เข้าตู้ — เส้นทางหลักหลังลงทะเบียน */
    @Test
    void patchingVault_movesCopyIntoVault() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));
        when(vaults.findByVaultIdAndDeletedAtIsNull("vt-001")).thenReturn(Optional.of(vault("vt-001")));
        Binding b = new Binding();
        b.setVault(vault("vt-001"));
        when(bindings.findActiveByAgentAndVault("shop-001", "vt-001")).thenReturn(Optional.of(b));

        GameCopyResponse c = service.update("SN-1", new UpdateGameCopyRequest("vt-001", null));

        assertThat(c.placement()).isEqualTo("IN_VAULT");
        assertThat(c.bookable()).isTrue();
    }

    /** แก้แท็กระหว่างลูกค้ายืมอยู่ = ตอนคืนตู้จะชี้ผิดใบ */
    @Test
    void changingTagWhileBooked_isRejected() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));
        when(bookings.existsActiveByItemAndSerial("db-GAME-CATAN", "SN-1")).thenReturn(true);

        assertThatThrownBy(() -> service.update("SN-1",
                new UpdateGameCopyRequest(null, "E2805641554C540000DE9999")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("COPY_HAS_ACTIVE_BOOKING"));
    }

    @Test
    void deletingBookedCopy_isRejected() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));
        when(bookings.existsActiveByItemAndSerial("db-GAME-CATAN", "SN-1")).thenReturn(true);

        assertThatThrownBy(() -> service.delete("SN-1"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("COPY_HAS_ACTIVE_BOOKING"));
        verify(copies, never()).save(any());
    }

    @Test
    void deleteIsSoft_historyStays() {
        when(copies.findBySerial("SN-1")).thenReturn(Optional.of(registered("SN-1", TAG, "shop-001")));
        when(bookings.existsActiveByItemAndSerial(anyString(), anyString())).thenReturn(false);

        service.delete("SN-1");

        var saved = org.mockito.ArgumentCaptor.forClass(VaultItem.class);
        verify(copies).save(saved.capture());
        assertThat(saved.getValue().getDeletedAt()).isNotNull();
        assertThat(saved.getValue().getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void unknownSerial_isNotFound() {
        assertThatThrownBy(() -> service.delete("SN-404"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("COPY_NOT_FOUND"));
    }
}
