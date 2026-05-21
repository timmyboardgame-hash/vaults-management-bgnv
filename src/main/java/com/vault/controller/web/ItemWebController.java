package com.vault.controller.web;

import com.vault.dto.CreateItemRequest;
import com.vault.service.ItemService;
import com.vault.service.VaultItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/items")
public class ItemWebController {

    private final ItemService itemService;
    private final VaultItemService vaultItemService;

    public ItemWebController(ItemService itemService, VaultItemService vaultItemService) {
        this.itemService = itemService;
        this.vaultItemService = vaultItemService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String binding,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        var boundItemIds = vaultItemService.listAllActive().stream()
                .map(vi -> vi.itemId())
                .collect(java.util.stream.Collectors.toSet());

        var items = itemService.searchItems(search).stream()
                .filter(i -> switch (binding == null ? "" : binding) {
                    case "BOUND"   ->  boundItemIds.contains(i.itemId());
                    case "UNBOUND" -> !boundItemIds.contains(i.itemId());
                    default        -> true;
                }).toList();

        model.addAttribute("items", items);
        model.addAttribute("currentBinding", binding != null ? binding : "");
        return htmx != null ? "items/list :: items-tbody" : "items/list";
    }

    @GetMapping("/{itemId}")
    public String detail(@PathVariable String itemId, Model model) {
        return itemService.getItem(itemId).map(item -> {
            model.addAttribute("item", item);
            return "items/detail";
        }).orElse("redirect:/items");
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> create(
            @RequestParam String itemId,
            @RequestParam String itemNameEn,
            @RequestParam String itemNameTh,
            @RequestParam(defaultValue = "ENABLE") String itemStatus,
            @RequestParam(required = false) String itemImageUrl,
            @RequestParam(required = false) String gameCode,
            @RequestParam(required = false) Integer playerCountMin,
            @RequestParam(required = false) Integer playerCountMax,
            @RequestParam(required = false) Integer playTimeMin,
            @RequestParam(required = false) Integer playTimeMax,
            @RequestParam(required = false) Double difficultyRating,
            @RequestParam(required = false) String linkBoardgamegeek,
            @RequestParam(required = false) String linkVideo,
            @RequestParam(required = false) String remark1) {
        try {
            itemService.createItem(new CreateItemRequest(
                itemId, itemNameEn, itemNameTh, itemStatus, itemImageUrl,
                gameCode, playerCountMin, playerCountMax, difficultyRating,
                playTimeMin, playTimeMax, linkBoardgamegeek, null, linkVideo,
                remark1, null, null, null, null));
            return ResponseEntity.ok().header("HX-Redirect", "/items").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/{itemId}")
    @ResponseBody
    public ResponseEntity<String> update(
            @PathVariable String itemId,
            @RequestParam(required = false) String itemNameEn,
            @RequestParam(required = false) String itemNameTh,
            @RequestParam(required = false) String itemStatus,
            @RequestParam(required = false) String gameCode,
            @RequestParam(required = false) Integer playerCountMin,
            @RequestParam(required = false) Integer playerCountMax,
            @RequestParam(required = false) Double difficultyRating,
            @RequestParam(required = false) Integer playTimeMin,
            @RequestParam(required = false) Integer playTimeMax,
            @RequestParam(required = false) String linkBoardgamegeek,
            @RequestParam(required = false) String linkVideo,
            @RequestParam(required = false) String remark1) {
        try {
            itemService.updateItem(itemId, itemNameEn, itemNameTh, itemStatus, gameCode,
                    playerCountMin, playerCountMax, difficultyRating,
                    playTimeMin, playTimeMax, linkBoardgamegeek, linkVideo, remark1);
            return ResponseEntity.ok().header("HX-Redirect", "/items/" + itemId).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{itemId}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable String itemId) {
        try {
            itemService.deleteItem(itemId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/items").build();
    }
}
