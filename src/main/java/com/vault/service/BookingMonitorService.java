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

    public BookingMonitorService(VaultRepository vaultRepository,
                                 VaultItemRepository vaultItemRepository,
                                 BookingRepository bookingRepository,
                                 BookingStatusEventRepository bookingStatusEventRepository) {
        this.vaultRepository = vaultRepository;
        this.vaultItemRepository = vaultItemRepository;
        this.bookingRepository = bookingRepository;
        this.bookingStatusEventRepository = bookingStatusEventRepository;
    }

    /** Grid view — ทุก vault พร้อม slot (VaultItem) และ current booking ของแต่ละ slot */
    public List<MonitorVault> getMonitorGrid() {
        List<Vault> vaults = vaultRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        return vaults.stream().map(vault -> {
            List<VaultItem> vaultItems = vaultItemRepository.findByVaultId(vault.getVaultId());

            List<MonitorSlot> slots = vaultItems.stream().map(vi -> {
                List<Booking> active = bookingRepository.findActiveByVaultAndItemBusinessId(
                        vault.getVaultId(), vi.getItem().getItemId());
                Booking current = active.isEmpty() ? null : active.get(0);

                return new MonitorSlot(
                        vi.getSlotId(),
                        vi.getItem().getItemId(),
                        vi.getItem().getItemNameEn(),
                        current != null,
                        current != null ? current.getBookingId() : null,
                        current != null ? current.getBookingStatus() : null
                );
            }).toList();

            return new MonitorVault(
                    vault.getVaultId(),
                    vault.getVaultName(),
                    "ENABLE".equals(vault.getVaultStatus()),
                    vault.getVaultSlot(),
                    slots
            );
        }).toList();
    }

    /** Detail view — current booking + history (พร้อม timeline) ของ vault+item ที่ระบุ */
    public ItemHistoryDto getItemHistory(String vaultId, String itemId) {
        List<Booking> all = bookingRepository.findByVaultAndItemBusinessId(vaultId, itemId);

        Booking current = all.stream()
                .filter(b -> !TERMINAL_STATUSES.contains(b.getBookingStatus()))
                .findFirst().orElse(null);

        List<Booking> historyBookings = all.stream()
                .filter(b -> TERMINAL_STATUSES.contains(b.getBookingStatus()))
                .toList();

        List<StatusEventDto> currentEvents = current != null
                ? bookingStatusEventRepository.findByBookingIdOrderByOccurredAtAsc(current.getId())
                        .stream().map(e -> new StatusEventDto(e.getStatus(), toOffset(e.getOccurredAt()), e.getNote()))
                        .toList()
                : List.of();

        CurrentBookingDto currentDto = current != null ? new CurrentBookingDto(
                current.getBookingId(),
                current.getAgent().getAgentId(),
                current.getAgent().getAgentName(),
                current.getItem().getItemNameEn(),
                current.getBookingStatus(),
                toOffset(current.getBookingTimeStart()),
                toOffset(current.getBookingTimeEnd()),
                current.getPin(),
                currentEvents
        ) : null;

        List<HistoryBookingDto> historyDtos = historyBookings.stream().map(b -> {
            List<BookingStatusEvent> events = bookingStatusEventRepository.findByBookingIdOrderByOccurredAtAsc(b.getId());
            List<StatusEventDto> eventDtos = events.stream()
                    .map(e -> new StatusEventDto(e.getStatus(), toOffset(e.getOccurredAt()), e.getNote()))
                    .toList();

            return new HistoryBookingDto(
                    b.getBookingId(),
                    b.getAgent().getAgentId(),
                    b.getAgent().getAgentName(),
                    b.getItem().getItemNameEn(),
                    toOffset(b.getBookingTimeStart()),
                    toOffset(b.getBookingTimeEnd()),
                    b.getBookingStatus(),
                    eventDtos
            );
        }).toList();

        String vaultName = all.isEmpty() ? vaultId : all.get(0).getVault().getVaultName();
        String itemNameEn = all.isEmpty() ? itemId : all.get(0).getItem().getItemNameEn();

        return new ItemHistoryDto(vaultId, vaultName, itemId, itemNameEn, currentDto, historyDtos);
    }

    private OffsetDateTime toOffset(LocalDateTime ldt) {
        return ldt != null ? ldt.atOffset(BANGKOK_OFFSET) : null;
    }
}
