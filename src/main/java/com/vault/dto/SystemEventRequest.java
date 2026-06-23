package com.vault.dto;

public record SystemEventRequest(
    String errorType,      // schema_version_mismatch | unknown_command | hardware_error | db_error | ...
    String severity,       // info | warning | error
    String errorMessage,
    String requestId       // request_id_echo จาก command ที่ trigger (nullable)
) {}
