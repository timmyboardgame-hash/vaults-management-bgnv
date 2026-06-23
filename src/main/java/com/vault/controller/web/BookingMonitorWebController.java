package com.vault.controller.web;

import com.vault.service.BookingMonitorService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/booking-monitor")
public class BookingMonitorWebController {

    private final BookingMonitorService bookingMonitorService;

    public BookingMonitorWebController(BookingMonitorService bookingMonitorService) {
        this.bookingMonitorService = bookingMonitorService;
    }

    @GetMapping
    public String grid(Model model) {
        model.addAttribute("vaults", bookingMonitorService.getMonitorGrid());
        return "booking-monitor/list";
    }

    @GetMapping("/{vaultId}/{itemId}")
    public String detail(@PathVariable String vaultId, @PathVariable String itemId, Model model) {
        model.addAttribute("data", bookingMonitorService.getItemHistory(vaultId, itemId));
        return "booking-monitor/detail";
    }
}
