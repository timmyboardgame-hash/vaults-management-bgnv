package com.vault.dto;

import java.time.LocalDateTime;

public record VaultResponse(
    String id,
    String vaultId,
    String vaultName,
    String vaultStatus,
    Integer vaultSlot,
    String description,
    LocalDateTime createdAt
) {}
