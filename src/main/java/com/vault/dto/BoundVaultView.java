package com.vault.dto;

import java.util.List;

/**
 * View model สำหรับแสดง vault + items ที่ bound กับ agent
 * ใช้ใน agents/detail.html เท่านั้น
 */
public record BoundVaultView(
        String vaultId,
        String vaultName,
        String vaultStatus,
        int vaultSlot,
        List<VaultItemResponse> items
) {
    public int usedSlots() {
        return items.size();
    }

    public int usagePercent() {
        if (vaultSlot == 0) return 0;
        return (int) Math.round((double) usedSlots() / vaultSlot * 100);
    }

    public String progressColor() {
        int pct = usagePercent();
        if (pct >= 90) return "danger";
        if (pct >= 75) return "warning";
        return "success";
    }
}
