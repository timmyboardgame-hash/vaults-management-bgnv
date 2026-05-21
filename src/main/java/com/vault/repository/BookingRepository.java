package com.vault.repository;

import com.vault.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByBookingIdAndDeletedAtIsNull(String bookingId);

    List<Booking> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByBookingIdAndDeletedAtIsNull(String bookingId);

    @Query("SELECT b FROM Booking b WHERE b.agent.agentId = :agentId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByAgentBusinessId(@Param("agentId") String agentId);

    @Query("SELECT b FROM Booking b WHERE b.item.itemId = :itemId AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByItemBusinessId(@Param("itemId") String itemId);

    @Query("SELECT b FROM Booking b WHERE b.bookingStatus = :status AND b.deletedAt IS NULL ORDER BY b.createdAt DESC")
    List<Booking> findByStatus(@Param("status") String status);
}
