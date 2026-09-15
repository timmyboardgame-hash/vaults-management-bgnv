package com.vault.controller.web;

import com.vault.service.StatsRollupService;
import com.vault.service.StatsRollupService.DayResult;
import com.vault.service.VaultBoardService;
import com.vault.service.VaultStatsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final VaultBoardService vaultBoardService;
    private final boolean devTools;

    public VaultAnalyticsWebController(StatsRollupService rollupService,
                                       VaultStatsService statsService,
                                       VaultBoardService vaultBoardService,
                                       @Value("${analytics.dev-tools:false}") boolean devTools) {
        this.rollupService = rollupService;
        this.statsService = statsService;
        this.vaultBoardService = vaultBoardService;
        this.devTools = devTools;
    }

    /** filter และแท็บอยู่ใน query string ทั้งหมด — ลิงก์ที่ส่งต่อให้คนอื่นเปิดแล้วเห็นหน้าเดียวกัน */
    @GetMapping
    public String page(@RequestParam(required = false) String vault,
                       @RequestParam(defaultValue = "90") int days,
                       @RequestParam(defaultValue = "true") boolean compare,
                       @RequestParam(defaultValue = "use") String tab,
                       @RequestParam(defaultValue = "all") String bk,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("p", statsService.build(vault, days, compare, tab, bk, page));
        model.addAttribute("periods", VaultStatsService.PERIODS);
        model.addAttribute("bookingFilters", VaultStatsService.BOOKING_FILTERS);
        model.addAttribute("devTools", devTools);
        return "vault-analytics/index";
    }

    /**
     * timeline ของ booking 1 ใบ — ใช้ fragment เดียวกับหน้า Vault Board ผ่าน template ชั้นบนสุด
     * (เรียก fragment ที่มี parameter ซ้อนใน fragment อีกชั้นเคยพังด้วย synthetic parameters)
     */
    @GetMapping("/booking/{bookingId}/sheet")
    public String bookingSheet(@PathVariable String bookingId, Model model) {
        return vaultBoardService.getBookingIncludingDeleted(bookingId).map(bk -> {
            model.addAttribute("bk", bk);
            return "vault-analytics/booking-sheet";
        }).orElse("vault-analytics/booking-sheet-empty");
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
