package com.vault.controller.web;

import com.vault.service.BookingMonitorService;
import com.vault.service.VaultBoardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/booking-monitor")
public class BookingMonitorWebController {

    private final BookingMonitorService bookingMonitorService;
    private final VaultBoardService vaultBoardService;

    public BookingMonitorWebController(BookingMonitorService bookingMonitorService,
                                       VaultBoardService vaultBoardService) {
        this.bookingMonitorService = bookingMonitorService;
        this.vaultBoardService = vaultBoardService;
    }

    @GetMapping
    public String grid(Model model,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        model.addAttribute("vaults", bookingMonitorService.getMonitorGrid());
        // HTMX polling — ส่งเฉพาะ grid fragment
        if (htmx != null) return "booking-monitor/list :: bm-grid";
        return "booking-monitor/list";
    }

    /** Vault Board — booking ทุกใบของตู้ + สถานะกล่อง ควบคู่กัน */
    @GetMapping("/{vaultId}")
    public String board(@PathVariable String vaultId, Model model,
                        @RequestHeader(value = "HX-Request", required = false) String htmx) {
        return vaultBoardService.getBoard(vaultId).map(board -> {
            model.addAttribute("b", board);
            // HTMX polling — ส่งเฉพาะเนื้อ board
            return htmx != null ? "booking-monitor/board :: board-body" : "booking-monitor/board";
        }).orElse("redirect:/booking-monitor");
    }

    @GetMapping("/{vaultId}/{itemId}")
    public String detail(@PathVariable String vaultId, @PathVariable String itemId, Model model) {
        model.addAttribute("data", bookingMonitorService.getItemHistory(vaultId, itemId));
        return "booking-monitor/detail";
    }
}
