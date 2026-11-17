package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ตัว service ส่งช่วงเวลาที่ถูกต้องเข้า query และตอบ error ที่ไล่ต่อได้
 * ส่วนตัวกติกาทับกันทดสอบกับ SQLite จริงใน BookingConflictQueryTest
 */
class BookingTimeWindowTest {

    private static final String VAULT  = "vault-screenless-002";
    private static final String AGENT  = "shop-001";
    private static final String SERIAL = "NFC-04332A7AD42A81";
    private static final ZoneOffset BKK = ZoneOffset.ofHours(7);

    /** Mockito บน Java 25 mock คลาสธรรมดาไม่ได้ — เขียน spy เองเหมือน VaultCommandTest */
    private static final class IotSpy extends AwsIotService {
        final List<String> created = new ArrayList<>();
        final List<String> extended = new ArrayList<>();

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override
        public void publishBookingCreate(String thingName, String bookingId, String requestId,
                                         String pin, String rfidTag,
                                         String gameId, String gameTitle, String serialNumber,
                                         LocalDateTime validFrom, LocalDateTime validUntil,
                                         boolean sessionMode) {
            created.add(bookingId);
        }

        @Override
        public void publishBookingExtend(String thingName, String bookingId, String requestId,
                                         LocalDateTime validUntil) {
            extended.add(bookingId);
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

    private static Item item() {
        Item i = new Item();
        i.setId("db-item");
        i.setItemId("NOVA-EVENT-002");
        i.setItemNameEn("Nova");
        i.setItemNameTh("โนวา");
        return i;
    }

    private static Vault vault() {
        Vault v = new Vault();
        v.setVaultId(VAULT);
        return v;
    }

    private void copyIsBookable() {
        Agent agent = new Agent();
        agent.setAgentId(AGENT);
        agent.setAgentName("ร้านทดสอบ");
        when(agents.findByAgentIdAndDeletedAtIsNull(AGENT)).thenReturn(Optional.of(agent));

        VaultItem copy = new VaultItem();
        copy.setId("copy-1");
        copy.setSerialNumber(SERIAL);
        copy.setStatus("ACTIVE");
        copy.setItem(item());
        copy.setVault(vault());
        when(copies.findActiveBySerial(SERIAL)).thenReturn(Optional.of(copy));
        when(bindings.findActiveByAgentAndVault(AGENT, VAULT)).thenReturn(Optional.of(new Binding()));
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static OffsetDateTime tomorrowAt(int hour) {
        return OffsetDateTime.now(BKK).plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private static CreateBookingRequest request(OffsetDateTime start, OffsetDateTime end) {
        return new CreateBookingRequest(null, "NOVA-EVENT-002", start, end, "ทดสอบ", null, null, SERIAL);
    }

    private static Booking existing(String bookingId, String status, LocalDateTime start, LocalDateTime end) {
        Booking b = new Booking();
        b.setId("db-" + bookingId);
        b.setBookingId(bookingId);
        b.setBookingStatus(status);
        b.setBookingTimeStart(start);
        b.setBookingTimeEnd(end);
        b.setItem(item());
        b.setVault(vault());
        b.setSerialNumber(SERIAL);
        return b;
    }

    // ───────────────────────────── createBooking ─────────────────────────────

    /** เคสที่ถูกแจ้งมา — ไม่มีใบทับเวลา จองได้แม้มีใบอื่นของกล่องเดียวกันในวันเดียวกัน */
    @Test
    void createsWhenNothingOverlaps() {
        copyIsBookable();

        service.createBooking(AGENT, request(tomorrowAt(16), tomorrowAt(18)));

        assertThat(iot.created).hasSize(1);
    }

    /**
     * แอปส่งเวลามาโซนไหนก็ได้ แต่ DB เก็บเป็นเวลาไทย — ต้องแปลงก่อนเข้า query
     * ไม่งั้น 09:00Z (=16:00 ไทย) จะถูกเทียบเป็น 09:00 แล้วไปชนใบผิดช่วง
     */
    @Test
    void checksOverlapInBangkokTimeWhateverOffsetTheCallerUses() {
        copyIsBookable();
        OffsetDateTime start = tomorrowAt(16).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime end   = tomorrowAt(18).withOffsetSameInstant(ZoneOffset.UTC);

        service.createBooking(AGENT, request(start, end));

        verify(bookings).findConflicting(eq("db-item"), eq(SERIAL),
                eq(tomorrowAt(16).toLocalDateTime()), eq(tomorrowAt(18).toLocalDateTime()));
    }

    /** error ต้องบอกว่าใบไหนขวาง ช่วงไหน — ของเดิมไม่บอก ทีมแอปไล่ 8 วันไม่เจอ */
    @Test
    void namesTheBlockingBookingAndItsWindow() {
        copyIsBookable();
        Booking blocking = existing("99767087-7f10-4d7b-b7fb-2a01940a6f53", "PENDING",
                tomorrowAt(15).toLocalDateTime(), tomorrowAt(17).toLocalDateTime());
        when(bookings.findConflicting(any(), any(), any(), any())).thenReturn(List.of(blocking));

        assertThatThrownBy(() -> service.createBooking(AGENT, request(tomorrowAt(16), tomorrowAt(18))))
                .isInstanceOf(IllegalArgumentException.class)
                // ขึ้นต้นเหมือนเดิม เผื่อฝั่งแอปจับข้อความนี้อยู่
                .hasMessageStartingWith("Copy already booked: serial=" + SERIAL)
                .hasMessageContaining("99767087-7f10-4d7b-b7fb-2a01940a6f53")
                .hasMessageContaining(tomorrowAt(15).toLocalDateTime().toString());
        assertThat(iot.created).isEmpty();
    }

    /** ใบ OVERDUE ขวางเพราะของไม่อยู่ในตู้ ไม่ใช่เพราะเวลา — ต้องบอกให้ตรง */
    @Test
    void explainsAnOverdueBlockAsNotReturned() {
        copyIsBookable();
        Booking overdue = existing("BK-OVERDUE", "OVERDUE",
                LocalDateTime.now().minusHours(5), LocalDateTime.now().minusHours(3));
        when(bookings.findConflicting(any(), any(), any(), any())).thenReturn(List.of(overdue));

        assertThatThrownBy(() -> service.createBooking(AGENT, request(tomorrowAt(16), tomorrowAt(18))))
                .hasMessageContaining("BK-OVERDUE")
                .hasMessageContaining("copy not returned yet");
    }

    /** ช่วงติดลบไม่ทับกับอะไรเลย ถ้าไม่ดักจะผ่านทุกด่านแล้วได้ใบที่ใช้ไม่ได้ */
    @Test
    void rejectsAnEndBeforeTheStart() {
        copyIsBookable();

        assertThatThrownBy(() -> service.createBooking(AGENT, request(tomorrowAt(18), tomorrowAt(16))))
                .hasMessageContaining("bookingTimeEnd must be after bookingTimeStart");
        assertThat(iot.created).isEmpty();
    }

    @Test
    void rejectsAZeroLengthWindow() {
        copyIsBookable();

        assertThatThrownBy(() -> service.createBooking(AGENT, request(tomorrowAt(16), tomorrowAt(16))))
                .hasMessageContaining("bookingTimeEnd must be after bookingTimeStart");
    }

    /** จบไปแล้ว scheduler จะปิดทิ้งรอบถัดไป ลูกค้าได้ PIN ที่ใช้ไม่ได้ */
    @Test
    void rejectsAWindowThatHasAlreadyEnded() {
        copyIsBookable();
        OffsetDateTime yesterday = tomorrowAt(16).minusDays(2);

        assertThatThrownBy(() -> service.createBooking(AGENT, request(yesterday, yesterday.plusHours(2))))
                .hasMessageContaining("bookingTimeEnd is already in the past");
        assertThat(iot.created).isEmpty();
    }

    /** เวลาเริ่มเป็น "ตอนนี้" แล้ว request มาช้าไปไม่กี่วินาที ต้องไม่ถูกปัด */
    @Test
    void acceptsAStartThatJustPassed() {
        copyIsBookable();
        OffsetDateTime aMinuteAgo = OffsetDateTime.now(BKK).minusMinutes(1);

        service.createBooking(AGENT, request(aMinuteAgo, aMinuteAgo.plusHours(2)));

        assertThat(iot.created).hasSize(1);
    }

    // ───────────────────────────── extendBooking ─────────────────────────────

    /** กล่องหนึ่งมีหลายใบได้แล้ว ขยายจนชนใบถัดไปไม่ได้ ไม่งั้นลูกค้าใบถัดไปมาแล้วไม่มีของ */
    @Test
    void refusesToExtendIntoTheNextBooking() {
        Booking mine = existing("BK-MINE", "ACTIVE", tomorrowAt(14).toLocalDateTime(), tomorrowAt(16).toLocalDateTime());
        Booking next = existing("BK-NEXT", "CONFIRMED", tomorrowAt(17).toLocalDateTime(), tomorrowAt(19).toLocalDateTime());
        when(bookings.findByBookingIdAndDeletedAtIsNull("BK-MINE")).thenReturn(Optional.of(mine));
        when(bookings.findConflicting(any(), any(), any(), any())).thenReturn(List.of(mine, next));

        assertThatThrownBy(() -> service.extendBooking("BK-MINE", tomorrowAt(18)))
                .hasMessageContaining("Cannot extend into another booking")
                .hasMessageContaining("BK-NEXT");

        // ห้ามเปลี่ยนเวลาใน DB หรือสั่งตู้ก่อนแล้วค่อยล้ม
        assertThat(mine.getBookingTimeEnd()).isEqualTo(tomorrowAt(16).toLocalDateTime());
        assertThat(iot.extended).isEmpty();
        verify(bookings, never()).save(any(Booking.class));
    }

    /** query คืนตัวเองมาด้วยเสมอ (ช่วงเวลาตัวเองทับตัวเอง) — ต้องไม่ขวางตัวเอง */
    @Test
    void extendsWhenTheOnlyOverlapIsItself() {
        Booking mine = existing("BK-MINE", "ACTIVE", tomorrowAt(14).toLocalDateTime(), tomorrowAt(16).toLocalDateTime());
        when(bookings.findByBookingIdAndDeletedAtIsNull("BK-MINE")).thenReturn(Optional.of(mine));
        when(bookings.findConflicting(any(), any(), any(), any())).thenReturn(List.of(mine));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        mine.setAgent(new Agent());

        service.extendBooking("BK-MINE", tomorrowAt(18));

        assertThat(mine.getBookingTimeEnd()).isEqualTo(tomorrowAt(18).toLocalDateTime());
        assertThat(iot.extended).containsExactly("BK-MINE");
    }

    /** ใบที่ปิดเป็น EXPIRED แล้ว ตู้ถอนสิทธิ์ไปแล้ว — ขยายไม่ได้ ไม่งั้นได้ใบที่ดูเปิดอยู่แต่ใช้ไม่ได้ */
    @Test
    void refusesToExtendAnExpiredBooking() {
        Booking expired = existing("BK-EXPIRED", "EXPIRED",
                LocalDateTime.now().minusHours(5), LocalDateTime.now().minusHours(3));
        when(bookings.findByBookingIdAndDeletedAtIsNull("BK-EXPIRED")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.extendBooking("BK-EXPIRED", tomorrowAt(18)))
                .hasMessageContaining("Cannot extend booking in status: EXPIRED");
        assertThat(iot.extended).isEmpty();
    }

    @Test
    void refusesToExtendToBeforeTheStart() {
        Booking mine = existing("BK-MINE", "CONFIRMED", tomorrowAt(14).toLocalDateTime(), tomorrowAt(16).toLocalDateTime());
        when(bookings.findByBookingIdAndDeletedAtIsNull("BK-MINE")).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> service.extendBooking("BK-MINE", tomorrowAt(13)))
                .hasMessageContaining("bookingTimeEnd must be after bookingTimeStart");
    }
}
