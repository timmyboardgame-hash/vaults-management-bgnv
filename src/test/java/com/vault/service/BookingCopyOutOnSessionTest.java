package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.dto.CreateBookingRequest;
import com.vault.entity.Agent;
import com.vault.entity.Binding;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * กล่องที่ถูกหยิบออกไประหว่างรอบ session ต้องจองซ้ำไม่ได้
 *
 * การหยิบระหว่าง session ไม่ได้ผูกกับ serial ของกล่อง แต่เป็น event MOVE:PICKED_UP
 * ที่แขวนอยู่กับใบของ session pass — ตัวกันจองซ้อนเดิมจึงมองไม่เห็น
 * ถ้าหลุด ระบบจะออก PIN ให้ลูกค้าอีกคนสำหรับกล่องที่ไม่ได้อยู่ในตู้แล้ว
 */
class BookingCopyOutOnSessionTest {

    private static final String VAULT   = "vault-screenless-002";
    private static final String AGENT   = "shop-001";
    private static final String EPC     = "046C773CC82A81";
    private static final String SERIAL  = "NFC-046C773CC82A81";

    /** Mockito บน Java 25 mock คลาสธรรมดาไม่ได้ — เขียน spy เองเหมือน VaultCommandTest */
    private static final class IotSpy extends AwsIotService {
        final List<String> created = new ArrayList<>();

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override
        public void publishBookingCreate(String thingName, String bookingId, String requestId,
                                         String pin, String rfidTag,
                                         String gameId, String gameTitle, String serialNumber,
                                         LocalDateTime validFrom, LocalDateTime validUntil,
                                         boolean sessionMode) {
            created.add(bookingId);
        }
    }

    private final BookingRepository            bookings = mock(BookingRepository.class);
    private final BookingStatusEventRepository events   = mock(BookingStatusEventRepository.class);
    private final AgentRepository              agents   = mock(AgentRepository.class);
    private final VaultItemRepository          copies   = mock(VaultItemRepository.class);
    private final BindingRepository            bindings = mock(BindingRepository.class);
    private final IotSpy                       iot      = new IotSpy();

    private final BookingService service = new BookingService(
            bookings, events, agents, copies, bindings, iot,
            new BookingIdGenerator(bookings), List.of("SESSION-PASS"));

    // ───────────────────────────── fixtures ─────────────────────────────

    private static Vault vault() {
        Vault v = new Vault();
        v.setVaultId(VAULT);
        return v;
    }

    private static Item item(String itemId) {
        Item i = new Item();
        i.setItemId(itemId);
        i.setItemNameEn(itemId);
        i.setItemNameTh(itemId);
        return i;
    }

    private static VaultItem copy(String serial, String tag) {
        VaultItem vi = new VaultItem();
        vi.setId("copy-" + serial);
        vi.setSerialNumber(serial);
        vi.setRfidTag(tag);
        vi.setStatus("ACTIVE");
        vi.setItem(item("NOVA-EVENT-002"));
        vi.setVault(vault());
        return vi;
    }

    /** ใบ session ที่ยังไม่จบ พร้อม timeline ของการหยิบ/คืน */
    private Booking openSession(String bookingId, String status, String... timeline) {
        Booking b = new Booking();
        b.setId("db-" + bookingId);
        b.setBookingId(bookingId);
        b.setItem(item("SESSION-PASS"));
        b.setVault(vault());
        b.setBookingStatus(status);

        List<BookingStatusEvent> history = new ArrayList<>();
        for (int i = 0; i + 1 < timeline.length; i += 2) {
            BookingStatusEvent e = new BookingStatusEvent();
            e.setBooking(b);
            e.setStatus(timeline[i]);
            // describeCopy() เขียน note เป็น "ชื่อเกม (serial) epc=XXXX" — extractEpc อ่านท้ายบรรทัด
            e.setNote("Codenames (" + SERIAL + ") epc=" + timeline[i + 1]);
            history.add(e);
        }
        when(events.findByBookingIdOrderByOccurredAtAsc(b.getId())).thenReturn(history);
        return b;
    }

    private void vaultHasOpenSessions(Booking... sessions) {
        when(bookings.findOpenByVaultAndItemIds(VAULT, List.of("SESSION-PASS")))
                .thenReturn(List.of(sessions));
    }

    private CreateBookingRequest request(String itemId, String serial) {
        return new CreateBookingRequest(
                null, itemId,
                OffsetDateTime.of(2026, 11, 12, 13, 0, 0, 0, ZoneOffset.ofHours(7)),
                OffsetDateTime.of(2026, 11, 12, 15, 0, 0, 0, ZoneOffset.ofHours(7)),
                "ทดสอบ", null, null, serial);
    }

    /** stub เส้นทางปกติของ createBooking ให้ผ่านจนถึงจุดที่เราสนใจ */
    private void happyPath(VaultItem vaultItem) {
        Agent agent = new Agent();
        agent.setAgentId(AGENT);
        agent.setAgentName("ร้านทดสอบ");
        when(agents.findByAgentIdAndDeletedAtIsNull(AGENT)).thenReturn(Optional.of(agent));
        when(copies.findActiveBySerial(vaultItem.getSerialNumber())).thenReturn(Optional.of(vaultItem));
        when(bindings.findActiveByAgentAndVault(AGENT, VAULT)).thenReturn(Optional.of(new Binding()));
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    void rejectsACopyThatIsStillOutOnAnOpenSession() {
        VaultItem out = copy(SERIAL, EPC);
        happyPath(out);
        vaultHasOpenSessions(openSession("BK-SESSION-1", "ACTIVE", "MOVE:PICKED_UP", EPC));

        assertThatThrownBy(() -> service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Copy is currently out on session BK-SESSION-1")
                .hasMessageContaining(SERIAL);

        // ห้ามหลุดไปสั่งตู้ก่อนแล้วค่อยล้ม — ตู้จะกันช่องไว้ให้ใบที่ไม่มีอยู่จริง
        assertThat(iot.created).isEmpty();
    }

    /** หยิบแล้วคืนในรอบเดียวกัน = กล่องกลับเข้าตู้แล้ว จองได้ตามปกติ */
    @Test
    void allowsACopyThatWasAlreadyReturnedWithinTheSession() {
        VaultItem back = copy(SERIAL, EPC);
        happyPath(back);
        vaultHasOpenSessions(openSession("BK-SESSION-1", "ACTIVE",
                "MOVE:PICKED_UP", EPC,
                "MOVE:RETURNED", EPC));

        service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL));

        assertThat(iot.created).hasSize(1);
    }

    /** session หมดเวลาแล้วของยังค้าง — ใบยังไม่จบ กล่องก็ยังอยู่ข้างนอก */
    @Test
    void rejectsWhenTheHoldingSessionIsOverdue() {
        VaultItem out = copy(SERIAL, EPC);
        happyPath(out);
        vaultHasOpenSessions(openSession("BK-SESSION-2", "OVERDUE", "MOVE:PICKED_UP", EPC));

        assertThatThrownBy(() -> service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL)))
                .hasMessageContaining("BK-SESSION-2");
    }

    /** กล่องอื่นในตู้เดียวกันไม่เกี่ยว — ห้ามบล็อคทั้งตู้เพราะมีกล่องหนึ่งถูกหยิบ */
    @Test
    void allowsAnotherCopyInTheSameVault() {
        VaultItem other = copy("NFC-04BA223BC82A81", "04BA223BC82A81");
        happyPath(other);
        vaultHasOpenSessions(openSession("BK-SESSION-1", "ACTIVE", "MOVE:PICKED_UP", EPC));

        service.createBooking(AGENT, request("NOVA-EVENT-002", "NFC-04BA223BC82A81"));

        assertThat(iot.created).hasSize(1);
    }

    /** ตู้เก็บแท็กเป็นตัวใหญ่ แต่ event จากตู้มาเคสไหนก็ได้ — ต้องเทียบให้ตรงกันอยู่ดี */
    @Test
    void matchesTheTagRegardlessOfCase() {
        VaultItem out = copy(SERIAL, EPC.toLowerCase());
        happyPath(out);
        vaultHasOpenSessions(openSession("BK-SESSION-1", "ACTIVE", "MOVE:PICKED_UP", EPC));

        assertThatThrownBy(() -> service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL)))
                .hasMessageContaining("Copy is currently out on session");
    }

    /** ไม่มีแท็ก = ตู้ตามการหยิบของกล่องนี้ไม่ได้ ไม่มีข้อมูลให้ตรวจ จึงต้องไม่ไปขวางการจอง */
    @Test
    void skipsTheCheckForACopyWithoutATag() {
        VaultItem untagged = copy("SN-NO-TAG", null);
        happyPath(untagged);
        vaultHasOpenSessions(openSession("BK-SESSION-1", "ACTIVE", "MOVE:PICKED_UP", EPC));

        service.createBooking(AGENT, request("NOVA-EVENT-002", "SN-NO-TAG"));

        assertThat(iot.created).hasSize(1);
    }

    /** ไม่มี session เปิดอยู่เลย — ไม่ต้องไปอ่าน timeline ของใคร */
    @Test
    void allowsWhenTheVaultHasNoOpenSession() {
        VaultItem free = copy(SERIAL, EPC);
        happyPath(free);
        vaultHasOpenSessions();

        service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL));

        assertThat(iot.created).hasSize(1);
    }

    /**
     * เคสกลับด้าน — เปิด session ทั้งที่มีกล่องถูกยืมปกติค้างอยู่ ต้องยังเปิดได้
     * ตู้ยังมีกล่องอื่นให้หยิบ การบล็อคจะทำให้ขายรอบไม่ได้เพราะกล่องเดียวค้าง
     */
    @Test
    void allowsASessionToStartWhileACopyIsOutOnItsOwnBooking() {
        VaultItem pass = copy("SESSION-VT002", null);
        pass.setItem(item("SESSION-PASS"));
        happyPath(pass);

        Booking openGame = new Booking();
        openGame.setBookingId("BK-GAME-1");
        openGame.setSerialNumber(SERIAL);
        when(bookings.findOpenByVaultExcludingItemIds(VAULT, List.of("SESSION-PASS")))
                .thenReturn(List.of(openGame));

        service.createBooking(AGENT, request("SESSION-PASS", "SESSION-VT002"));

        assertThat(iot.created).hasSize(1);
    }

    /** ใบที่ชนกันตรง ๆ ยังต้องถูกกันด้วยกฎเดิม ไม่ใช่ถูกกฎใหม่กลืนไป */
    @Test
    void stillBlocksAPlainDoubleBooking() {
        VaultItem booked = copy(SERIAL, EPC);
        happyPath(booked);
        when(bookings.existsActiveByItemAndSerial(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.createBooking(AGENT, request("NOVA-EVENT-002", SERIAL)))
                .hasMessageContaining("Copy already booked");
    }
}
