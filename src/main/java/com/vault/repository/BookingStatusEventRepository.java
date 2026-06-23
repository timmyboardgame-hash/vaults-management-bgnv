package com.vault.repository;

import com.vault.entity.BookingStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookingStatusEventRepository extends JpaRepository<BookingStatusEvent, String> {

    @Query("SELECT e FROM BookingStatusEvent e WHERE e.booking.id = :bookingId ORDER BY e.occurredAt ASC")
    List<BookingStatusEvent> findByBookingIdOrderByOccurredAtAsc(@Param("bookingId") String bookingId);
}
