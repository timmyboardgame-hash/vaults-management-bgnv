package com.vault.dto;

/**
 * Request body สำหรับ PATCH /api/v1/bookings/{id}/iot-event
 * ส่งมาจาก Lambda BookingEventHandler หลังรับ MQTT event จาก kiosk
 */
public record IotEventRequest(
    String event,     // booking_created | booking_picked_up | booking_returned | booking_cancelled
    String result,    // success | failed | noop | null
    String error,     // error message จาก device | null
    String requestId  // request_id_echo จาก device | null
) {}
