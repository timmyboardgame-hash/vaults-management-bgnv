package com.vault.repository;

import com.vault.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, String> {

    Optional<Item> findByItemIdAndDeletedAtIsNull(String itemId);

    List<Item> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByItemIdAndDeletedAtIsNull(String itemId);
}
