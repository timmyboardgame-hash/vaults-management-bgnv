package com.vault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateVaultRequest(
    @NotBlank String vaultId,
    @NotBlank String vaultName,
    @NotNull Integer vaultSlot,    // 8 | 16 | 24 | 32
    String description
) {}
