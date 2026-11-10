package com.vault.service;

import com.vault.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ออกเลข booking ให้เองเมื่อคนเรียกไม่ได้ส่งมา
 *
 * รูปแบบ: BK + yyyyMMddHHmmssSSS เช่น BK20261105143621146 (19 ตัวอักษรเสมอ)
 * อ่านแล้วรู้เวลาที่จองทันทีโดยไม่ต้องเปิดดูในฐานข้อมูล และเรียงตามตัวอักษรก็ได้ลำดับเวลา
 *
 * ใช้เวลาไทยเสมอ — ทั้งระบบตีความเวลาเป็น Asia/Bangkok อยู่แล้ว (สัญญา MQTT ข้อ 8)
 * ถ้าปล่อยให้ใช้โซนของเครื่อง เลขจะเปลี่ยนความหมายเวลาย้าย server
 */
@Component
public class BookingIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(BookingIdGenerator.class);

    public static final String PREFIX = "BK";

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * ความละเอียดของเลขคือมิลลิวินาที สองใบที่สร้างพร้อมกันจริง ๆ จะได้เลขเดียวกัน
     * (เจอได้ตอนยิงเป็นชุดหรือตอนคนกดรัว) · รอให้มิลลิวินาทีเดินแล้วลองใหม่
     * 100 รอบ = รอไม่เกินหนึ่งในสิบวินาที ถ้ายังไม่ได้แปลว่ามีอย่างอื่นผิด ไม่ใช่เรื่องชนกันเฉย ๆ
     */
    private static final int MAX_ATTEMPTS = 100;

    private final BookingRepository bookingRepository;
    private final Clock clock;

    // ระบุให้ชัดว่า Spring ใช้ตัวนี้ — มีสอง constructor AOT เลือกเองไม่ได้
    @org.springframework.beans.factory.annotation.Autowired
    public BookingIdGenerator(BookingRepository bookingRepository) {
        this(bookingRepository, Clock.system(BANGKOK));
    }

    /** ใช้ในเทส — ส่ง clock ที่คุมได้เข้ามา */
    BookingIdGenerator(BookingRepository bookingRepository, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.clock = clock;
    }

    /**
     * เลขใหม่ที่ยังไม่มีใครใช้
     *
     * เช็คกับทุกแถวรวมที่ถูก soft delete ไปแล้ว — booking ที่ยกเลิกยังอยู่ในตาราง
     * ออกเลขซ้ำกับใบที่ยกเลิกไปจะทำให้ตามประวัติไม่ได้ว่าใบไหนคือใบไหน
     */
    public String next() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String candidate = PREFIX + LocalDateTime.now(clock).format(STAMP);
            if (!bookingRepository.existsByBookingId(candidate)) {
                return candidate;
            }
            log.warn("[BOOKING] Generated id {} is already taken — waiting a millisecond (attempt {})",
                    candidate, attempt);
            waitOneMillisecond();
        }
        throw new IllegalStateException(
                "Could not generate a free booking id after " + MAX_ATTEMPTS + " attempts");
    }

    private static void waitOneMillisecond() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating a booking id", e);
        }
    }
}
