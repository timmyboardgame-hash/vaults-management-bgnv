package com.vault.controller.web;

import com.vault.service.StatsRollupService;
import com.vault.service.StatsRollupService.DayResult;
import com.vault.service.VaultStatsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final VaultStatsService statsService;
    private final boolean devTools;

    public VaultAnalyticsWebController(StatsRollupService rollupService,
                                       VaultStatsService statsService,
                                       @Value("${analytics.dev-tools:false}") boolean devTools) {
        this.rollupService = rollupService;
        this.statsService = statsService;
        this.devTools = devTools;
    }

    /** filter อยู่ใน query string ทั้งหมด — ลิงก์ที่ส่งต่อให้คนอื่นเปิดแล้วเห็นหน้าเดียวกัน */
    @GetMapping
    public String page(@RequestParam(required = false) String vault,
                       @RequestParam(defaultValue = "90") int days,
                       @RequestParam(defaultValue = "true") boolean compare,
                       Model model) {
        model.addAttribute("p", statsService.build(vault, days, compare));
        model.addAttribute("periods", VaultStatsService.PERIODS);
        model.addAttribute("devTools", devTools);
        return "vault-analytics/index";
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
        ra.addAttribute("days", days);
        return "redirect:/vault-analytics";
    }
}
