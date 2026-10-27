package com.vault.dto;

public record ClearLockdownRequest(
    String reason   // optional ตามสัญญา §3.6 — kiosk เก็บไว้ใน log เพื่อ audit
) {}
