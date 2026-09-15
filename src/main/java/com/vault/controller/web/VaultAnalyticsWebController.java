package com.vault.controller.web;

import com.vault.service.StatsRollupService;
import com.vault.service.StatsRollupService.DayResult;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/vault-analytics")
public class VaultAnalyticsWebController {

    private static final int MAX_BACKFILL_DAYS = 1100;   // ~3 ปี

    private final StatsRollupService rollupService;

    public VaultAnalyticsWebController(StatsRollupService rollupService) {
        this.rollupService = rollupService;
    }

    /**
     * คำนวณสถิติย้อนหลังใหม่ — ใช้หลังแก้ logic การนับ หรือเมื่อกล่องคืนช้าเกินรอบ 3 วันของงานรายคืน
     * รันซ้ำได้ปลอดภัย: แต่ละวันลบของเดิมแล้วเขียนใหม่
     */
    @PostMapping("/backfill")
    public String backfill(@RequestParam(defaultValue = "90") int days, RedirectAttributes ra) {
        int d = Math.max(1, Math.min(days, MAX_BACKFILL_DAYS));
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<DayResult> results = rollupService.rollupRange(yesterday.minusDays(d - 1L), yesterday);

        int bookings = results.stream().mapToInt(DayResult::bookings).sum();
        int cycles = results.stream().mapToInt(DayResult::cycles).sum();
        ra.addFlashAttribute("devMessage", String.format(
                "คำนวณสถิติย้อนหลัง %d วันแล้ว — %,d booking · %,d รอบหยิบ-คืน", d, bookings, cycles));
        return "redirect:/vault-analytics";
    }
}
