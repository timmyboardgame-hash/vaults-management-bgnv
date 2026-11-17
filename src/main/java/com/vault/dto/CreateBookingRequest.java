package com.vault.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateBookingRequest(
    // optional — ไม่ส่งมา ระบบออกเลขให้เป็น BK + yyyyMMddHHmmssSSS (ดู BookingIdGenerator)
    // ของที่ส่งมาเองยังใช้ได้เหมือนเดิม เพราะเป็น external ID ของฝั่งที่เรียก
    String bookingId,
    @NotBlank String itemId,
    @NotNull  OffsetDateTime bookingTimeStart,
    @NotNull  OffsetDateTime bookingTimeEnd,
    String bookingName,   // optional
    String bookingDate,   // optional
    String pin,           // optional — auto-generated if null
    @NotBlank String serialNumber,  // เลข barcode กล่องบอร์ดเกม

    // optional — ค่าที่แอปเอาไป gen เป็น QR ให้ลูกค้าสแกนเปิดตู้ เราไม่ตีความ ส่งต่อให้ตู้ตรงตัว
    // ตู้เทียบค่าที่สแกนได้กับค่านี้ ฉะนั้นห้ามแปลงตัวพิมพ์หรือแก้ค่าใต้มือแอป — ทำแค่ตัดช่องว่างหัวท้าย
    // รับทั้ง qrToken และ qr_token — field อื่นใน API เป็น camelCase แต่ payload MQTT เป็น snake_case
    // แอปจะสับสนชื่อได้ง่าย และถ้าชื่อผิด Jackson ทิ้งเงียบ ไม่มี error ให้เห็นเลย
    @JsonAlias("qr_token")
    @Size(max = 128) String qrToken
) {}
