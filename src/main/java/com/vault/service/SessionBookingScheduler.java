package com.vault.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ปิด booking ที่หมดเวลาอัตโนมัติ — เช็คทุก 1 นาที
 *
 * Session booking (item พิเศษตาม session.item-ids) ไม่จบเมื่อคืนของ จบเมื่อเลย bookingTimeEnd
 * booking ปกติที่ไม่มีใครมารับก็ปิดที่นี่เหมือนกัน ไม่งั้นค้างเป็น CONFIRMED แล้วล็อก serial ถาวร
 */
@Component
public class SessionBookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionBookingScheduler.class);

    private final BookingService bookingService;

    public SessionBookingScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeExpiredSessions() {
        try {
            int closed = bookingService.closeExpiredBookings();
            if (closed > 0) {
                log.info("[BOOKING] Scheduler closed {} expired booking(s)", closed);
            }
        } catch (Exception e) {
            log.error("[BOOKING] Scheduler failed", e);
        }
    }
}
