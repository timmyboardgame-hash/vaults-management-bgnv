package com.vault.dto;

/**
 * Request body สำหรับ PATCH /api/v1/bookings/{id}/iot-event
 * ส่งมาจาก Lambda BookingEventHandler หลังรับ MQTT event จาก kiosk
 */
public record IotEventRequest(
    String event,        // booking_created | booking_picked_up | booking_returned | booking_cancelled | booking_extended | booking_anomaly
    String result,       // success | failed | noop | null
    String error,        // error message จาก device | null
    String requestId,    // request_id_echo จาก device | null
    String anomalyType,  // booking_anomaly events only: wrong_tag | lockdown_triggered | lockdown_cleared | force_unlock | late_return
    String epc           // RFID tag ของกล่องที่ถูกหยิบ/คืน — ใช้ track movement ใน session booking
) {}
