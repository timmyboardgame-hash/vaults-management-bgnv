package com.vault.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Item;
import com.vault.entity.Vault;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * scheduler ต้องปิด booking ปกติที่หมดเวลาด้วย ไม่ใช่แค่ session
 *
 * ของเดิมข้ามใบปกติไปทั้งหมด ใบที่ไม่มีใครมารับจึงค้างเป็น CONFIRMED ตลอดกาล
 * และล็อก serial นั้นถาวร (เจอจริง: BK20261105143621146 ค้าง 8 วัน
 * ทีมแอปจองกล่อง 8530021 ไม่ได้ 7 ครั้งติด)
 */
class BookingExpiryTest {

    private static final String VAULT  = "vault-locker-001";
    private static final String THING  = "vault-locker-001-thing";
    private static final String EPC    = "046C773CC82A81";

    /** Mockito บน Java 25 mock คลาสธรรมดาไม่ได้ — เขียน spy เองเหมือน VaultCommandTest */
    private static final class IotSpy extends AwsIotService {
        final List<String> cancelled = new ArrayList<>();
        final List<String> cancelledThings = new ArrayList<>();

        IotSpy() { super(Optional.empty(), Optional.empty(), new ObjectMapper()); }

        @Override
        public void publishBookingCancel(String thingName, String bookingId, String requestId) {
            cancelledThings.add(thingName);
            cancelled.add(bookingId);
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

    private static Item item(String itemId) {
        Item i = new Item();
        i.setItemId(itemId);
        i.setItemNameEn(itemId);
        i.setItemNameTh(itemId);
        return i;
    }

    private static Vault vault() {
        Vault v = new Vault();
        v.setVaultId(VAULT);
        v.setThingName(THING);
        return v;
    }

    /** ใบที่เลย bookingTimeEnd มาแล้ว */
    private Booking expired(String bookingId, String itemId, String status, String serial) {
        Booking b = new Booking();
        b.setId("db-" + bookingId);
        b.setBookingId(bookingId);
        b.setItem(item(itemId));
        b.setVault(vault());
        b.setBookingStatus(status);
        b.setSerialNumber(serial);
        b.setBookingTimeEnd(LocalDateTime.now().minusDays(8));
        return b;
    }

    private void schedulerSees(Booking... open) {
        when(bookings.findOpenPastEnd(any())).thenReturn(List.of(open));
        when(bookings.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void timelineOf(Booking b, String... statusThenEpc) {
        List<BookingStatusEvent> history = new ArrayList<>();
        for (int i = 0; i + 1 < statusThenEpc.length; i += 2) {
            BookingStatusEvent e = new BookingStatusEvent();
            e.setBooking(b);
            e.setStatus(statusThenEpc[i]);
            e.setNote("Codenames (NFC-" + statusThenEpc[i + 1] + ") epc=" + statusThenEpc[i + 1]);
            history.add(e);
        }
        when(events.findByBookingIdOrderByOccurredAtAsc(b.getId())).thenReturn(history);
    }

    private List<BookingStatusEvent> recordedEvents() {
        ArgumentCaptor<BookingStatusEvent> captor = ArgumentCaptor.forClass(BookingStatusEvent.class);
        verify(events, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ──────────────────────── booking ปกติที่ไม่มีใครมารับ ────────────────────────

    /** เคสที่ทำให้ทีมแอปจองไม่ได้ 7 ครั้ง — CONFIRMED ค้างต้องถูกปิด */
    @Test
    void closesAConfirmedBookingNobodyCollected() {
        Booking b = expired("BK20261105143621146", "Cascadia", "CONFIRMED", "8530021");
        schedulerSees(b);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isEqualTo(1);
        assertThat(b.getBookingStatus()).isEqualTo("EXPIRED");
        assertThat(recordedEvents()).extracting(BookingStatusEvent::getStatus).containsExactly("EXPIRED");
    }

    /**
     * แก้ DB เฉยๆ ไม่พอ ต้องสั่งตู้ยกเลิกด้วย
     * ไม่งั้น PIN กับ QR ของใบที่ปิดไปแล้วยังเปิดตู้ได้อยู่
     */
    @Test
    void revokesAccessAtTheLockerWhenExpiringABooking() {
        Booking b = expired("BK-NOSHOW-1", "Cascadia", "CONFIRMED", "8530021");
        schedulerSees(b);

        service.closeExpiredBookings();

        assertThat(iot.cancelled).containsExactly("BK-NOSHOW-1");
        // ต้องใช้ thingName ไม่ใช่ vaultId — ไม่งั้นคำสั่งไปไม่ถึงตู้
        assertThat(iot.cancelledThings).containsExactly(THING);
    }

    /** ใบที่ตู้ยังไม่ตอบรับ ก็ไม่มีใครมารับได้เหมือนกัน */
    @Test
    void closesAPendingBookingToo() {
        Booking b = expired("BK-PENDING-1", "Splendor", "PENDING", "3558380021537");
        schedulerSees(b);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isEqualTo(1);
        assertThat(b.getBookingStatus()).isEqualTo("EXPIRED");
    }

    /**
     * หยิบไปแล้วไม่คืน = ของอยู่กับลูกค้าจริง ปิดใบไม่ได้
     * ถ้าปิด กล่องจะว่างในระบบแล้วถูกจองซ้อนทั้งที่ไม่ได้อยู่ในตู้
     */
    @Test
    void flagsAnActiveBookingAsOverdueInsteadOfClosingIt() {
        Booking b = expired("BK-ACTIVE-1", "Cascadia", "ACTIVE", "8530021");
        schedulerSees(b);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isZero();
        assertThat(b.getBookingStatus()).isEqualTo("OVERDUE");
        assertThat(iot.cancelled).isEmpty();
        assertThat(recordedEvents()).extracting(BookingStatusEvent::getStatus).containsExactly("OVERDUE");
    }

    /** ยกธงไปแล้ว scheduler วนทุกนาที ห้ามเขียน event ซ้ำจนกลบ timeline */
    @Test
    void leavesAnAlreadyOverdueBookingAlone() {
        Booking b = expired("BK-OVERDUE-1", "Cascadia", "OVERDUE", "8530021");
        schedulerSees(b);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isZero();
        assertThat(b.getBookingStatus()).isEqualTo("OVERDUE");
        verify(bookings, never()).save(any(Booking.class));
        verify(events, never()).save(any(BookingStatusEvent.class));
    }

    /** ใบเก่าที่ไม่มี vault ผูกอยู่ ต้องปิดได้ ไม่ใช่ NPE แล้วค้างทั้งรอบ */
    @Test
    void expiresABookingWithNoVaultWithoutFailingTheWholeRun() {
        Booking orphan = expired("BK-NOVAULT-1", "Cascadia", "CONFIRMED", "8530021");
        orphan.setVault(null);
        Booking next = expired("BK-NOSHOW-2", "Splendor", "CONFIRMED", "3558380021537");
        schedulerSees(orphan, next);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isEqualTo(2);
        assertThat(orphan.getBookingStatus()).isEqualTo("EXPIRED");
        // ใบถัดไปต้องยังถูกประมวลผล และต้องไม่สั่งตู้แทนใบที่ไม่มี vault
        assertThat(iot.cancelled).containsExactly("BK-NOSHOW-2");
    }

    // ──────────────────────────── session ไม่เปลี่ยนพฤติกรรม ────────────────────────────

    /** รอบ session ที่คืนของครบแล้ว ปิดได้ตามเดิม */
    @Test
    void stillClosesASessionWithEverythingReturned() {
        Booking s = expired("BK-SESSION-1", "SESSION-PASS", "ACTIVE", "SESSION-VT002");
        timelineOf(s, "MOVE:PICKED_UP", EPC, "MOVE:RETURNED", EPC);
        schedulerSees(s);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isEqualTo(1);
        assertThat(s.getBookingStatus()).isEqualTo("RETURNED");
        // session ไม่ใช้ booking_cancel — ของคืนครบแล้ว ไม่มีสิทธิ์ค้างให้ถอน
        assertThat(iot.cancelled).isEmpty();
    }

    /** ของยังไม่ครบ = OVERDUE ไม่ใช่ EXPIRED — ต้องยังรับ event คืนของได้ */
    @Test
    void marksASessionWithOutstandingCopiesAsOverdue() {
        Booking s = expired("BK-SESSION-2", "SESSION-PASS", "ACTIVE", "SESSION-VT002");
        timelineOf(s, "MOVE:PICKED_UP", EPC);
        schedulerSees(s);

        int closed = service.closeExpiredBookings();

        assertThat(closed).isZero();
        assertThat(s.getBookingStatus()).isEqualTo("OVERDUE");
        assertThat(recordedEvents()).singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo("OVERDUE");
                    assertThat(e.getNote()).contains(EPC);
                });
    }

    /** session ที่เป็น OVERDUE อยู่แล้วและของยังไม่ครบ ห้ามเขียนซ้ำ */
    @Test
    void leavesAnAlreadyOverdueSessionAlone() {
        Booking s = expired("BK-SESSION-3", "SESSION-PASS", "OVERDUE", "SESSION-VT002");
        timelineOf(s, "MOVE:PICKED_UP", EPC);
        schedulerSees(s);

        assertThat(service.closeExpiredBookings()).isZero();
        verify(events, never()).save(any(BookingStatusEvent.class));
    }

    // ──────────────────────────── EXPIRED ต้องปล่อยกล่องจริง ────────────────────────────

    /**
     * ปิดใบเป็น EXPIRED แล้วถ้า query ที่กันจองซ้อนยังนับ EXPIRED ว่า "ยังไม่จบ"
     * กล่องก็ยังถูกล็อกอยู่ดี — บั๊กเดิมจะกลับมาทั้งดุ้นโดยไม่มีใครรู้
     */
    @Test
    void treatsExpiredAsAFinishedStatusInEveryOpenBookingQuery() {
        List<String> mustExclude = List.of(
                "findActiveByVaultAndItemBusinessId",
                "existsActiveByItemAndSerial",
                "findOpenByVaultAndItemIds",
                "findOpenByVaultExcludingItemIds");

        for (String name : mustExclude) {
            String jpql = queryOf(name);
            assertThat(jpql)
                    .as("%s ต้องไม่นับ EXPIRED ว่าเป็นใบที่ยังไม่จบ", name)
                    .contains("'EXPIRED'");
        }
    }

    /** ใบที่ปิดแล้วห้ามถูกหยิบกลับมาสั่งตู้ยกเลิกซ้ำทุกนาที */
    @Test
    void doesNotPickExpiredBookingsBackUp() {
        assertThat(queryOf("findOpenPastEnd")).doesNotContain("EXPIRED");
    }

    private static String queryOf(String methodName) {
        for (Method m : BookingRepository.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                Query q = m.getAnnotation(Query.class);
                assertThat(q).as("%s ต้องมี @Query", methodName).isNotNull();
                return q.value();
            }
        }
        throw new AssertionError("ไม่พบเมธอด " + methodName + " ใน BookingRepository");
    }
}
