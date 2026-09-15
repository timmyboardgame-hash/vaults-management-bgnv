package com.vault.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * สรุปสถิติทุกคืน 00:05 น. เวลาไทย
 *
 * คำนวณย้อน 3 วัน (ไม่ใช่แค่เมื่อวาน) เพื่อเก็บของที่มาถึงช้า —
 * event ที่ Lambda retry ข้ามเที่ยงคืน หรือกล่องที่คืนหลังวันที่หยิบ
 * วันนี้ไม่คำนวณเพราะยังไม่จบวัน หน้าเว็บคำนวณวันนี้สดเอง
 */
@Component
public class StatsRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StatsRollupScheduler.class);
    static final int LOOKBACK_DAYS = 3;

    private final StatsRollupService rollupService;

    public StatsRollupScheduler(StatsRollupService rollupService) {
        this.rollupService = rollupService;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Bangkok")
    public void nightly() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            rollupService.rollupRange(yesterday.minusDays(LOOKBACK_DAYS - 1), yesterday);
        } catch (Exception e) {
            log.error("[STATS] Nightly rollup failed", e);
        }
    }
}
