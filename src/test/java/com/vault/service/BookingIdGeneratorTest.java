package com.vault.service;

import com.vault.repository.BookingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * เลข booking ที่ระบบออกให้เอง — BK + yyyyMMddHHmmssSSS
 *
 * เลขนี้ไปอยู่ในใบจอง ใน QR ที่ตู้ และในประวัติการยืม ออกซ้ำไม่ได้
 * และต้องเป็นเวลาไทยเสมอ ไม่ใช่โซนของเครื่องที่รันอยู่ ไม่งั้นย้าย server แล้วเลขเปลี่ยนความหมาย
 */
class BookingIdGeneratorTest {

    private final BookingRepository bookings = mock(BookingRepository.class);

    /** 5 พ.ย. 2569 14:36:21.146 เวลาไทย = 07:36:21.146 UTC */
    private static final Instant MOMENT = Instant.parse("2026-11-05T07:36:21.146Z");

    private BookingIdGenerator at(Instant instant, ZoneId zone) {
        return new BookingIdGenerator(bookings, Clock.fixed(instant, zone));
    }

    @Test
    void formatIsPrefixPlusTimestampToTheMillisecond() {
        when(bookings.existsByBookingId(anyString())).thenReturn(false);

        String id = at(MOMENT, ZoneOffset.ofHours(7)).next();

        assertThat(id).isEqualTo("BK20261105143621146");
        assertThat(id).hasSize(19);
    }

    /**
     * นาฬิกาเครื่องเป็น UTC แต่เลขต้องเป็นเวลาไทย
     *
     * ตัว generator ของจริงใช้ Clock.system(Asia/Bangkok) — เทสนี้คุมโซนเองได้
     * จึงยืนยันได้ว่าถ้าใช้โซนผิด เลขจะเพี้ยนไป 7 ชั่วโมงแบบที่ไม่มีใครทันสังเกต
     */
    @Test
    void sevenHoursApart_ifTheZoneWereWrong() {
        when(bookings.existsByBookingId(anyString())).thenReturn(false);

        assertThat(at(MOMENT, ZoneOffset.UTC).next()).isEqualTo("BK20261105073621146");
        assertThat(at(MOMENT, ZoneId.of("Asia/Bangkok")).next()).isEqualTo("BK20261105143621146");
    }

    /** เลขเดียวกันต้องไม่ถูกออกซ้ำ — รอให้มิลลิวินาทีเดินแล้วลองใหม่ */
    @Test
    void retriesWhenTheIdIsAlreadyTaken() {
        when(bookings.existsByBookingId(anyString())).thenReturn(true, true, false);

        // นาฬิกาเดินจริง เลขที่ได้จึงเป็นของรอบที่สาม
        String id = new BookingIdGenerator(bookings).next();

        assertThat(id).startsWith("BK").hasSize(19);
    }

    /** ใบที่ยกเลิกไปแล้วยังอยู่ในตาราง — ออกเลขซ้ำกับใบนั้นจะตามประวัติไม่ได้ */
    @Test
    void checksSoftDeletedBookingsToo() {
        when(bookings.existsByBookingId(anyString())).thenReturn(false);

        new BookingIdGenerator(bookings).next();

        // ใช้ตัวที่ไม่กรอง deleted_at — ไม่ใช่ existsByBookingIdAndDeletedAtIsNull
        org.mockito.Mockito.verify(bookings).existsByBookingId(anyString());
        org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never())
                .existsByBookingIdAndDeletedAtIsNull(anyString());
    }

    /** ชนทุกรอบ = มีอย่างอื่นผิด ไม่ใช่เรื่องความละเอียดของเวลา — ต้องฟ้อง ไม่ใช่วนไม่จบ */
    @Test
    void givesUpLoudlyRatherThanLoopingForever() {
        when(bookings.existsByBookingId(anyString())).thenReturn(true);

        assertThatThrownBy(() -> new BookingIdGenerator(bookings).next())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not generate a free booking id");
    }

    @Test
    void generatedIdsSortByTime() throws InterruptedException {
        when(bookings.existsByBookingId(anyString())).thenReturn(false);
        BookingIdGenerator gen = new BookingIdGenerator(bookings);

        String first = gen.next();
        Thread.sleep(2);
        String second = gen.next();

        assertThat(first).isLessThan(second);
    }
}
