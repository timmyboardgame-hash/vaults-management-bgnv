package com.vault.dto;

import java.time.LocalDateTime;

public record AgentResponse(
    String id,
    String agentId,
    String agentName,
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
    String remark5,
    LocalDateTime createdAt
) {}
