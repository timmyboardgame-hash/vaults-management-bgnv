package com.vault.dto;

import java.time.LocalDateTime;

public record VaultItemResponse(
    String id,
    String vaultId,
    String vaultName,
    String itemId,
    String itemNameEn,
    String itemNameTh,
    String serialNumber,
    String rfidTag,
    String defaultPin,
    String status,
    LocalDateTime createdAt
) {}
