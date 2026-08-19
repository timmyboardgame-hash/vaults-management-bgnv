package com.vault.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ปิด session booking ที่หมดเวลาอัตโนมัติ
 *
 * Session booking (item พิเศษตาม session.item-ids) ไม่จบเมื่อคืนของ —
 * จบเมื่อเลย bookingTimeEnd เท่านั้น scheduler นี้เช็คทุก 1 นาที
 * booking ปกติไม่โดนแตะ (late return flow เดิม)
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
            int closed = bookingService.closeExpiredSessionBookings();
            if (closed > 0) {
                log.info("[SESSION] Scheduler closed {} expired session booking(s)", closed);
            }
        } catch (Exception e) {
            log.error("[SESSION] Scheduler failed", e);
        }
    }
}
