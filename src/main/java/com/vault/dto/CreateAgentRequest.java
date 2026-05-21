package com.vault.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentRequest(
    @NotBlank String agentId,
    @NotBlank String agentName,
    String agentStatus,
    String phone,
    String address,
    String provinceCode,
    String mapUrl,
    Double latitude,
    Double longitude,
    String promotion,
    String remark1,
    String remark2,
    String remark3,
    String remark4,
    String remark5
) {}
