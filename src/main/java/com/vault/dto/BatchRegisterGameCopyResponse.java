package com.vault.dto;

import java.util.List;

/**
 * ผลการลงทะเบียนเป็นชุด — รายตัว ไม่ล้มทั้งชุดเพราะกล่องเดียวผิด
 *
 * ร้านยกของเข้าทีละชั้น ถ้าตีกลับทั้งชุดเพราะแท็กซ้ำใบเดียว
 * คนหน้าร้านต้องมานั่งไล่เองว่าใบไหนเข้าไปแล้ว
 */
public record BatchRegisterGameCopyResponse(
    int registered,
    List<GameCopyResponse> copies,
    List<Failure> failed
) {
    public record Failure(String serialNumber, String code, String message) {}
}
