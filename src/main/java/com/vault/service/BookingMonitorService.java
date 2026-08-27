package com.vault.service;

import com.vault.dto.BookingMonitorDto.*;
import com.vault.entity.Booking;
import com.vault.entity.BookingStatusEvent;
import com.vault.entity.Vault;
import com.vault.entity.VaultItem;
import com.vault.repository.BookingRepository;
import com.vault.repository.BookingStatusEventRepository;
import com.vault.repository.VaultItemRepository;
import com.vault.repository.VaultRepository;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class BookingMonitorService {

    private static final ZoneOffset BANGKOK_OFFSET = ZoneOffset.ofHours(7);
    private static final Set<String> TERMINAL_STATUSES = Set.of("RETURNED", "CANCELLED", "FAILED");

    private final VaultRepository vaultRepository;
    private final VaultItemRepository vaultItemRepository;
    private final BookingRepository bookingRepository;
    private final BookingStatusEventRepository bookingStatusEventRepository;
    private final VaultBoardService vaultBoardService;

    // itemId ที่เป็น session pass — แยกออกจาก grid กล่องเกม แสดงเป็นการ์ดของตัวเอง
    private final List<String> sessionItemIds;

    public BookingMonitorService(VaultRepository vaultRepository,
                                 VaultItemRepository vaultItemRepository,
                                 BookingRepository bookingRepository,
                                 BookingStatusEventRepository bookingStatusEventRepository,
                                 VaultBoardService vaultBoardService,
                                 @org.springframework.beans.factory.annotation.Value("${session.item-ids:}") List<String> sessionItemIds) {
        this.vaultRepository = vaultRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bookingRepository = bookingRepository;
        this.bookingStatusEventRepository = bookingStatusEventRepository;
        this.vaultBoardService = vaultBoardService;
        this.sessionItemIds = sessionItemIds;
    }

    /**
     * Grid view — ทุก vault พร้อมสถานะกล่อง
     *
     * ใช้ข้อมูลจาก VaultBoardService เพราะกล่องถูกหยิบได้ 2 ทาง:
     * game booking (booking.item ชี้กล่องตรงๆ) และ event booking (ผูกกับ session pass
     * แต่มี MOVE event ที่ epc/serial ของกล่อง) — การดูแค่ booking.item ทำให้กล่องที่
     * ถูกหยิบไปกับ event booking แสดงว่า "ว่าง" ทั้งที่ไม่อยู่ในตู้
     */
    public List<MonitorVault> getMonitorGrid() {
        List<Vault> vaults = vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        return vaults.stream().map(vault -> {
            var board = vaultBoardService.getBoard(vault.getVaultId()).orElse(null);
            if (board == null) {
                return new MonitorVault(vault.getVaultId(), vault.getVaultName(),
                        "ENABLE".equals(vault.getVaultStatus()), vault.getVaultSlot(),
                        List.of(), null);
            }

            List<MonitorSlot> gameSlots = board.items().stream()
                    .filter(i -> !i.session())
                    .map(this::toSlot)
                    .toList();

            // session pass ไม่เคยถูก "หยิบ" (ไม่มี MOVE event ของตัวเอง) จึงต้องดูจาก
            // event booking ที่ยังไม่จบของตู้นี้แทน ไม่งั้นการ์ดจะขึ้นว่างทั้งที่มี session เปิดอยู่
            var openEvent = board.active().stream()
                    .filter(bk -> "event".equals(bk.type()))
                    .findFirst().orElse(null);
            MonitorSlot sessionSlot = board.items().stream()
                    .filter(com.vault.dto.VaultBoardDto.BoardItem::session)
                    .findFirst()
                    .map(i -> new MonitorSlot(
                            i.serialNumber(), i.itemId(), i.itemName(),
                            openEvent != null,
                            openEvent != null ? openEvent.bookingId() : null,
                            openEvent != null ? openEvent.status() : null))
                    .orElse(null);

            return new MonitorVault(
                    vault.getVaultId(),
                    vault.getVaultName(),
                    board.online(),
                    vault.getVaultSlot(),
                    gameSlots,
                    sessionSlot
            );
        }).toList();
    }

    /** BoardItem → MonitorSlot (occupied = ถูกหยิบอยู่ หรือถูกจองไว้รอรับ) */
    private MonitorSlot toSlot(com.vault.dto.VaultBoardDto.BoardItem i) {
        String bookingId = i.heldBy() != null ? i.heldBy() : i.reservedBy();
        String status = i.heldBy() != null ? (i.over() ? "OVERDUE" : "ACTIVE")
                      : i.reservedBy() != null ? "CONFIRMED" : null;
        return new MonitorSlot(
                i.serialNumber(),
                i.itemId(),
                i.itemName(),
                bookingId != null,
                bookingId,
                status
        );
    }

    private OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK_OFFSET) : null;
    }
}
