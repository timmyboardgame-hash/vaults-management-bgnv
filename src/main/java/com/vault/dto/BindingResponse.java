package com.vault.dto;

import java.time.LocalDateTime;

public record BindingResponse(
    String id,
    String agentId,
    String agentName,
    String vaultId,
    String vaultName,
    String status,
    LocalDateTime createdAt
) {}
