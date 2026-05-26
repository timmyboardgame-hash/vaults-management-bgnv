package com.vault.controller;

import com.vault.dto.BookingResponse;
import com.vault.dto.CreateBookingRequest;
import com.vault.dto.IotEventRequest;
import com.vault.service.BookingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private static final Logger log = LoggerFactory.getLogger(BookingController.class);

    private final BookingService bookingService;

    @Value("${iot.callback.secret:dev-iot-secret}")
    private String iotCallbackSecret;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // GET /api/v1/bookings?agentId=xxx
    @GetMapping("/bookings")
    public ResponseEntity<List<BookingResponse>> getAllBookings(
        @RequestParam(required = false) String agentId
    ) {
        if (agentId != null) {
            return ResponseEntity.ok(bookingService.getBookingsByAgent(agentId));
        }
        return ResponseEntity.ok(bookingService.getAllBookings());
    }

    // GET /api/v1/bookings/{bookingId}
    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<?> getBooking(@PathVariable String bookingId) {
        return bookingService.getBooking(bookingId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.status(404).body(Map.of("error", "Booking not found: " + bookingId)));
    }

    // POST /api/v1/agents/{agentId}/bookings
    @PostMapping("/agents/{agentId}/bookings")
    public ResponseEntity<BookingResponse> createBooking(
        @PathVariable String agentId,
        @Valid @RequestBody CreateBookingRequest request
    ) {
        return ResponseEntity.status(201).body(bookingService.createBooking(agentId, request));
    }

    // DELETE /api/v1/bookings/{bookingId}  (soft delete + CANCELLED)
    @DeleteMapping("/bookings/{bookingId}")
    public ResponseEntity<BookingResponse> cancelBooking(@PathVariable String bookingId) {
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId));
    }

    // PATCH /api/v1/bookings/{bookingId}/iot-event — Lambda callback จาก IoT Rules
    @PatchMapping("/bookings/{bookingId}/iot-event")
    public ResponseEntity<Void> handleIotEvent(
        @PathVariable String bookingId,
        @RequestBody IotEventRequest request,
        @RequestHeader(value = "x-iot-secret", required = false) String secret
    ) {
        if (!iotCallbackSecret.equals(secret)) {
            log.warn("[IoT] Unauthorized iot-event callback for booking={}", bookingId);
            return ResponseEntity.status(401).build();
        }
        bookingService.handleIotEvent(bookingId, request);
        return ResponseEntity.ok().build();
    }
}
