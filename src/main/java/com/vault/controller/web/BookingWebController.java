package com.vault.controller.web;

import com.vault.dto.CreateBookingRequest;
import com.vault.service.AgentService;
import com.vault.service.BookingService;
import com.vault.service.VaultItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/bookings")
public class BookingWebController {

    private static final Logger log = LoggerFactory.getLogger(BookingWebController.class);

    private final BookingService bookingService;
    private final AgentService agentService;
    private final VaultItemService vaultItemService;

    public BookingWebController(BookingService bookingService,
                                AgentService agentService,
                                VaultItemService vaultItemService) {
        this.bookingService = bookingService;
        this.agentService = agentService;
        this.vaultItemService = vaultItemService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String status,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        model.addAttribute("bookings", bookingService.searchBookings(search, status));
        model.addAttribute("currentStatus", status != null ? status : "");
        if (htmx != null) return "bookings/list :: bookings-tbody";

        // โหลด dropdown data สำหรับ create modal
        model.addAttribute("agents",     agentService.listAgents());
        model.addAttribute("vaultItems", vaultItemService.listAllActive()); // items ที่อยู่ใน vault แล้ว
        return "bookings/list";
    }

    @GetMapping("/{bookingId}")
    public String detail(@PathVariable String bookingId, Model model) {
        return bookingService.getBooking(bookingId).map(b -> {
            model.addAttribute("booking", b);
            return "bookings/detail";
        }).orElse("redirect:/bookings");
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> create(
            @RequestParam String bookingId,
            @RequestParam String bookingName,
            @RequestParam String bookingTimeStart,
            @RequestParam String bookingTimeEnd,
            @RequestParam String agentId,
            @RequestParam String itemId,
            @RequestParam(required = false) String pin) {
        try {
            LocalDateTime timeStart = LocalDateTime.parse(bookingTimeStart);
            // derive bookingDate จาก bookingTimeStart — ไม่ต้องส่งแยก
            String bookingDate = timeStart.toLocalDate().toString();

            CreateBookingRequest req = new CreateBookingRequest(
                    bookingId,
                    bookingName,
                    bookingDate,
                    timeStart,
                    LocalDateTime.parse(bookingTimeEnd),
                    "PENDING",
                    itemId,
                    pin
            );

            // vaultId หาใน service ผ่าน VaultItem + ตรวจ binding agent↔vault ด้วย
            bookingService.createBooking(agentId, req);
            return ResponseEntity.ok().header("HX-Redirect", "/bookings").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{bookingId}")
    @ResponseBody
    public ResponseEntity<String> cancel(@PathVariable String bookingId) {
        try {
            bookingService.cancelBooking(bookingId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[BOOKING] Cancel failed bookingId={} reason={}", bookingId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
