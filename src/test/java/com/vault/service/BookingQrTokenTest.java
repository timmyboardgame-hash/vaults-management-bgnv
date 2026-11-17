package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.dto.BookingResponse;
import com.vault.dto.CreateBookingRequest;
import com.vault.entity.Agent;
import com.vault.entity.Binding;
import com.vault.entity.Booking;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * แอปส่ง qrToken มา → เราเก็บ ส่งต่อให้ตู้ใน booking/create และคืนกลับใน response
 *
 * ตู้เทียบค่าที่สแกนจาก QR กับ qr_token ที่เราส่งไป ถ้าค่าไม่ถึงตู้ หรือถึงแต่ถูกแก้ระหว่างทาง
 * ลูกค้าสแกนแล้วเปิดไม่ได้ และไม่มี error ที่ไหนบอกเลย
 */
class BookingQrTokenTest {

    private static final String VAULT  = "vault-screenless-002";
    private static final String AGENT  = "shop-001";
    private static final String SERIAL = "NFC-04332A7AD42A81";
    private static final String TOKEN  = "b1154b4e-379e-4326-a56f-9609296441a9";

    /** Mockito บน Java 25 mock คลาสธรรมดาไม่ได้ — เขียน spy เองเหมือน VaultCommandTest */
    private static final class IotSpy extends AwsIotService {
        final List<String> qrTokens = new ArrayList<>();
        int calls;

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override
        public void publishBookingCreate(String thingName, String bookingId, String requestId,
                                         String pin, String rfidTag,
                                         String gameId, String gameTitle, String serialNumber,
                                         LocalDateTime validFrom, LocalDateTime validUntil,
                                         boolean sessionMode, String qrToken) {
            calls++;
            qrTokens.add(qrToken);
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

    private void copyIsBookable() {
        Agent agent = new Agent();
        agent.setAgentId(AGENT);
        agent.setAgentName("ร้านทดสอบ");
        when(agents.findByAgentIdAndDeletedAtIsNull(AGENT)).thenReturn(Optional.of(agent));

        Item item = new Item();
        item.setId("db-item");
        item.setItemId("NOVA-EVENT-002");
        item.setItemNameEn("Nova");
        item.setItemNameTh("โนวา");

        Vault vault = new Vault();
        vault.setVaultId(VAULT);

        VaultItem copy = new VaultItem();
        copy.setId("copy-1");
        copy.setSerialNumber(SERIAL);
        copy.setStatus("ACTIVE");
        copy.setItem(item);
        copy.setVault(vault);
        when(copies.findActiveBySerial(SERIAL)).thenReturn(Optional.of(copy));
        when(bindings.findActiveByAgentAndVault(AGENT, VAULT)).thenReturn(Optional.of(new Binding()));
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId("db-new");
            return b;
        });
    }

    private static CreateBookingRequest request(String qrToken) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.ofHours(7)).plusDays(1)
                .withHour(13).withMinute(0).withSecond(0).withNano(0);
        return new CreateBookingRequest(null, "NOVA-EVENT-002", start, start.plusHours(2),
                "ทดสอบ", null, null, SERIAL, qrToken);
    }

    private Booking savedBooking() {
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ───────────────────────────── createBooking ─────────────────────────────

    /** เส้นหลัก: ค่าเดียวกับที่แอปส่งต้องไปถึงตู้ ถูกเก็บ และถูกคืนกลับ */
    @Test
    void forwardsTheTokenToTheLockerStoresItAndEchoesIt() {
        copyIsBookable();

        BookingResponse res = service.createBooking(AGENT, request(TOKEN));

        assertThat(iot.qrTokens).containsExactly(TOKEN);
        assertThat(savedBooking().getQrToken()).isEqualTo(TOKEN);
        // แอปใช้ตัวนี้ยืนยันว่าเราได้ค่าจริง — null แปลว่าชื่อ field ผิด
        assertThat(res.qrToken()).isEqualTo(TOKEN);
    }

    /** ตู้เทียบตรงตัว — ห้ามแปลงตัวพิมพ์ ไม่งั้นสแกนแล้วไม่ตรง */
    @Test
    void keepsTheTokenExactlyAsSentApartFromSurroundingSpaces() {
        copyIsBookable();

        service.createBooking(AGENT, request("  AbC-123-xYz \n"));

        assertThat(iot.qrTokens).containsExactly("AbC-123-xYz");
    }

    /** ใบที่ไม่มี QR (หน้า admin, แอปเวอร์ชันเก่า) ต้องสร้างได้เหมือนเดิม */
    @Test
    void worksWithoutAToken() {
        copyIsBookable();

        BookingResponse res = service.createBooking(AGENT, request(null));

        assertThat(iot.calls).isEqualTo(1);
        assertThat(iot.qrTokens).containsExactly((String) null);
        assertThat(res.qrToken()).isNull();
        verify(bookings, never()).findOpenByQrToken(anyString());
    }

    /** "" หรือช่องว่างล้วน = ไม่ได้ส่ง ไม่ใช่ token ว่าง ที่ตู้จะเอาไปเทียบกับ QR เปล่า */
    @Test
    void treatsABlankTokenAsNoToken() {
        copyIsBookable();

        service.createBooking(AGENT, request("   "));

        assertThat(iot.qrTokens).containsExactly((String) null);
        assertThat(savedBooking().getQrToken()).isNull();
    }

    /** สองใบที่ยังเปิดอยู่ใช้ QR เดียวกันไม่ได้ — ตู้ไม่รู้ว่าคนสแกนเป็นของใบไหน */
    @Test
    void rejectsATokenAlreadyUsedByAnOpenBooking() {
        copyIsBookable();
        Booking other = new Booking();
        other.setBookingId("BK-OTHER");
        when(bookings.findOpenByQrToken(TOKEN)).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.createBooking(AGENT, request(TOKEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QR token already in use by booking BK-OTHER");

        // ห้ามสั่งตู้ก่อนแล้วค่อยล้ม
        assertThat(iot.calls).isZero();
    }

    /** เช็คซ้ำด้วยค่าหลัง trim — ไม่งั้นเติมช่องว่างก็หลบด่านได้ */
    @Test
    void checksDuplicatesAgainstTheTrimmedToken() {
        copyIsBookable();

        service.createBooking(AGENT, request(" " + TOKEN + " "));

        verify(bookings).findOpenByQrToken(TOKEN);
    }

    // ───────────────────────────── MQTT payload ─────────────────────────────

    private static final LocalDateTime FROM  = LocalDateTime.of(2030, 1, 15, 13, 0);
    private static final LocalDateTime UNTIL = LocalDateTime.of(2030, 1, 15, 15, 0);

    private static Map<String, Object> gamePayload(String qrToken) {
        return AwsIotService.bookingCreatePayload("BK-1", "1234", "046C773CC82A81",
                "NOVA-EVENT-002", "Nova", SERIAL, FROM, UNTIL, false, qrToken);
    }

    private static Map<String, Object> sessionPayload(String qrToken) {
        return AwsIotService.bookingCreatePayload("BK-1", "1234", null,
                "SESSION-PASS", "Session", "SESSION-VT002", FROM, UNTIL, true, qrToken);
    }

    /** ตามที่ตกลงกับตู้: ระดับเดียวกับ booking_id / pin ไม่ได้ซ้อนอยู่ใต้ object อื่น */
    @Test
    void putsQrTokenBesideBookingIdAndPinInAGameBooking() {
        Map<String, Object> p = gamePayload(TOKEN);

        assertThat(p).containsEntry("qr_token", TOKEN);
        assertThat(p).containsEntry("booking_id", "BK-1").containsEntry("pin", "1234");
    }

    @Test
    void putsQrTokenInASessionBookingToo() {
        assertThat(sessionPayload(TOKEN)).containsEntry("qr_token", TOKEN)
                .containsEntry("booking_type", "event");
    }

    /**
     * ไม่ส่งมาก็ไม่ใส่ key เลย — ไม่ใช่ "qr_token": null
     * payload ของใบที่ไม่มี QR ต้องเหมือนก่อนแก้ทุก key ตู้ที่ยังไม่อัปเดตจะได้ไม่เจออะไรแปลก
     */
    @Test
    void leavesTheKeyOutEntirelyWithoutAToken() {
        assertThat(gamePayload(null)).doesNotContainKey("qr_token")
                .containsOnlyKeys("booking_id", "pin", "tags", "valid_from", "valid_until",
                        "game_id", "game_title", "serial_number", "locker_id");
        assertThat(sessionPayload(null)).doesNotContainKey("qr_token")
                .containsOnlyKeys("booking_id", "booking_type", "pin", "valid_from", "valid_until");
    }
}
