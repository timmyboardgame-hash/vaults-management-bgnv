package com.vault.dto;

public record ForceUnlockRequest(
    String reason   // required non-empty — echo กลับมาใน events/booking/anomaly เพื่อ audit
) {}
