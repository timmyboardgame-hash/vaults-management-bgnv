package com.vault.controller.web;

import com.vault.service.AnalyticsSeedService;
import com.vault.service.StatsRollupService;
import com.vault.service.StatsRollupService.DayResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * เครื่องมือ dev ของหน้า Vault Analytics — มีอยู่เฉพาะเมื่อ analytics.dev-tools=true
 * ปิดอยู่ bean นี้ไม่ถูกสร้างเลย endpoint จึงไม่มีให้เรียกบน production
 */
@Controller
@RequestMapping("/vault-analytics/dev")
@ConditionalOnProperty(name = "analytics.dev-tools", havingValue = "true")
public class AnalyticsDevController {

    private final AnalyticsSeedService seedService;
    private final StatsRollupService rollupService;

    public AnalyticsDevController(AnalyticsSeedService seedService, StatsRollupService rollupService) {
        this.seedService = seedService;
        this.rollupService = rollupService;
    }

    /** สร้างข้อมูลดิบย้อนหลัง แล้วคำนวณสถิติช่วงเดียวกันต่อทันที — ข้อมูลวิ่งผ่านงานสรุปจริงทุกขั้น */
    @PostMapping("/seed")
    public String seed(@RequestParam(defaultValue = "90") int days, RedirectAttributes ra) {
        int d = Math.max(7, Math.min(days, 400));
        AnalyticsSeedService.SeedResult seed = seedService.seed(d);

        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<DayResult> results = rollupService.rollupRange(yesterday.minusDays(d - 1L), yesterday);
        int cycles = results.stream().mapToInt(DayResult::cycles).sum();

        ra.addFlashAttribute("devMessage", String.format(
                "สร้างข้อมูลทดสอบ %d วัน — %,d booking · %,d event · คำนวณสถิติ %d วัน ได้ %,d รอบหยิบ-คืน",
                d, seed.bookings(), seed.events(), results.size(), cycles));
        return "redirect:/vault-analytics";
    }

    @PostMapping("/clear")
    public String clear(RedirectAttributes ra) {
        seedService.clear();
        ra.addFlashAttribute("devMessage", "ล้างข้อมูลทดสอบ (SEED-) และสถิติที่คำนวณจากมันแล้ว");
        return "redirect:/vault-analytics";
    }
}
