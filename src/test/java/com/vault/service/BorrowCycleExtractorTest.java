package com.vault.service;

import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Item;
import com.vault.entity.VaultItem;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.service.BorrowCycleExtractor.RawCycle;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * การจับคู่รอบหยิบ-คืน — ถ้าผิด ทั้งหน้า board และสถิติทุกตัวผิดตามโดยไม่มีอะไรฟ้อง
 */
class BorrowCycleExtractorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 14, 0);

    private final VaultItemRepository vaultItems = mock(VaultItemRepository.class);
    private final BorrowCycleExtractor extractor = new BorrowCycleExtractor(
            mock(BookingStatusEventRepository.class), vaultItems, List.of("SESSION-PASS"));

    @Test
    void eventBooking_pairsPickupAndReturnByEpc() {
        registerTag("E1", "Cascadia", "SN-1");
        registerTag("E2", "Splendor", "SN-2");
        Booking b = booking("SESSION-PASS", T0, T0.plusHours(2));

        // ตู้หยิบได้ทีละกล่อง — คืนกล่องแรกก่อนแล้วค่อยหยิบกล่องที่สอง
        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("MOVE:PICKED_UP", "Cascadia (SN-1) epc=E1", T0.plusMinutes(5)),
                event("MOVE:RETURNED",  "Cascadia (SN-1) epc=E1", T0.plusMinutes(40)),
                event("MOVE:PICKED_UP", "Splendor (SN-2) epc=E2", T0.plusMinutes(45)),
                event("MOVE:RETURNED",  "Splendor (SN-2) epc=E2", T0.plusMinutes(75))
        ), T0.plusHours(3));

        assertThat(cycles).hasSize(2);
        assertThat(cycles.get(0).epc()).isEqualTo("E1");
        assertThat(cycles.get(0).minutes()).isEqualTo(35);
        assertThat(cycles.get(0).serialNumber()).isEqualTo("SN-1");
        assertThat(cycles.get(0).item().getItemNameEn()).isEqualTo("Cascadia");
        assertThat(cycles.get(1).epc()).isEqualTo("E2");
        assertThat(cycles.get(1).minutes()).isEqualTo(30);
        assertThat(cycles).allMatch(c -> c.returnedAt() != null && !c.late() && !c.pending());
    }

    @Test
    void eventBooking_pickupWithoutReturn_staysOpen() {
        registerTag("E1", "Cascadia", "SN-1");
        Booking b = booking("SESSION-PASS", T0, T0.plusHours(1));

        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("MOVE:PICKED_UP", "Cascadia (SN-1) epc=E1", T0.plusMinutes(10))
        ), T0.plusHours(2));

        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.returnedAt()).isNull();
            assertThat(c.minutes()).isEqualTo(110);   // นับถึง now
            assertThat(c.late()).isTrue();             // เลย bookingTimeEnd แล้ว
        });
    }

    @Test
    void eventBooking_unregisteredTag_keepsNameFromNoteWithoutItem() {
        when(vaultItems.findActiveByRfidTag(anyString())).thenReturn(Optional.empty());
        Booking b = booking("SESSION-PASS", T0, T0.plusHours(2));

        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("MOVE:PICKED_UP", "unregistered tag epc=EX", T0.plusMinutes(5)),
                event("MOVE:RETURNED",  "unregistered tag epc=EX", T0.plusMinutes(20))
        ), T0.plusHours(3));

        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.item()).isNull();
            assertThat(c.matchKey()).isEqualTo("EX");
        });
    }

    @Test
    void gameBooking_activeThenReturned_isOneClosedCycle() {
        Booking b = booking("GAME-1", T0, T0.plusHours(2));
        b.setSerialNumber("SN-9");

        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("PENDING", "PIN: 111111", T0.minusMinutes(10)),
                event("CONFIRMED", null, T0.minusMinutes(9)),
                event("ACTIVE", null, T0.plusMinutes(3)),
                event("RETURNED", null, T0.plusMinutes(93))
        ), T0.plusHours(5));

        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.minutes()).isEqualTo(90);
            assertThat(c.late()).isFalse();
            assertThat(c.epc()).isNull();
            assertThat(c.matchKey()).isEqualTo("SN-9");
            assertThat(c.item().getItemId()).isEqualTo("GAME-1");
        });
    }

    @Test
    void gameBooking_returnedAfterWindow_isLate() {
        Booking b = booking("GAME-1", T0, T0.plusHours(1));

        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("ACTIVE", null, T0.plusMinutes(5)),
                event("RETURNED", null, T0.plusMinutes(80))
        ), T0.plusHours(5));

        assertThat(cycles).singleElement().satisfies(c -> assertThat(c.late()).isTrue());
    }

    @Test
    void gameBooking_neverPickedUp_isPending() {
        Booking b = booking("GAME-1", T0, T0.plusHours(1));

        List<RawCycle> cycles = extractor.cycles(b, List.of(
                event("PENDING", "PIN: 111111", T0.minusMinutes(10)),
                event("CANCELLED", null, T0.plusMinutes(2))
        ), T0.plusHours(5));

        assertThat(cycles).singleElement().satisfies(c -> {
            assertThat(c.pending()).isTrue();
            assertThat(c.pickedUpAt()).isNull();
        });
    }

    @Test
    void extractEpc_readsLastTagFromNote() {
        assertThat(BorrowCycleExtractor.extractEpc("Cascadia (SN-1) epc=E2801170")).isEqualTo("E2801170");
        assertThat(BorrowCycleExtractor.extractEpc("unregistered tag epc=ABC more")).isEqualTo("ABC");
        assertThat(BorrowCycleExtractor.extractEpc("PIN: 123456")).isNull();
        assertThat(BorrowCycleExtractor.extractEpc(null)).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private void registerTag(String epc, String name, String serial) {
        Item item = new Item();
        item.setItemId("GAME-" + serial);
        item.setItemNameEn(name);
        VaultItem vi = new VaultItem();
        vi.setItem(item);
        vi.setRfidTag(epc);
        vi.setSerialNumber(serial);
        when(vaultItems.findActiveByRfidTag(epc)).thenReturn(Optional.of(vi));
    }

    private static Booking booking(String itemId, LocalDateTime start, LocalDateTime end) {
        Item item = new Item();
        item.setItemId(itemId);
        item.setItemNameEn(itemId);
        Booking b = new Booking();
        b.setItem(item);
        b.setBookingTimeStart(start);
        b.setBookingTimeEnd(end);
        b.setBookingStatus("CONFIRMED");
        return b;
    }

    private static BookingStatusEvent event(String status, String note, LocalDateTime at) {
        BookingStatusEvent e = new BookingStatusEvent();
        e.setStatus(status);
        e.setNote(note);
        // occurredAt เป็น @CreationTimestamp ไม่มี setter — ตั้งผ่าน reflection เพื่อจำลองเวลาที่ event เกิด
        ReflectionTestUtils.setField(e, "occurredAt", at);
        return e;
    }
}
