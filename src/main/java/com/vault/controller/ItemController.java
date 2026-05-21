package com.vault.controller;

import com.vault.dto.CreateItemRequest;
import com.vault.dto.ItemResponse;
import com.vault.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public ResponseEntity<List<ItemResponse>> list() {
        return ResponseEntity.ok(itemService.listItems());
    }

    @GetMapping("/{itemId}")
    public ResponseEntity<ItemResponse> get(@PathVariable String itemId) {
        return itemService.getItem(itemId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody CreateItemRequest req) {
        return ResponseEntity.ok(itemService.createItem(req));
    }

    @DeleteMapping("/{itemId}")
    public ResponseEntity<ItemResponse> delete(@PathVariable String itemId) {
        return ResponseEntity.ok(itemService.deleteItem(itemId));
    }
}
