package com.vault.controller.web;

import com.vault.service.ItemService;
import com.vault.service.VaultItemService;
import com.vault.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/vault-items")
public class VaultItemWebController {

    private final VaultItemService vaultItemService;
    private final VaultService vaultService;
    private final ItemService itemService;

    public VaultItemWebController(VaultItemService vaultItemService,
                                  VaultService vaultService,
                                  ItemService itemService) {
        this.vaultItemService = vaultItemService;
        this.vaultService = vaultService;
        this.itemService = itemService;
    }

    @GetMapping
    public String list(Model model) {
        var activeVaultItems = vaultItemService.listAllActive();
        model.addAttribute("vaultItems", activeVaultItems);
        model.addAttribute("vaults", vaultService.listVaults());

        // เฉพาะ item ที่ยังไม่มี active VaultItem
        var boundItemIds = activeVaultItems.stream()
                .map(vi -> vi.itemId())
                .collect(java.util.stream.Collectors.toSet());
        var availableItems = itemService.listItems().stream()
                .filter(i -> !boundItemIds.contains(i.itemId()))
                .toList();
        model.addAttribute("items", availableItems);
        return "vault-items/list";
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> bind(
            @RequestParam String vaultId,
            @RequestParam String itemId,
            @RequestParam String slotId) {
        try {
            vaultItemService.bindItemToVault(vaultId, itemId, slotId);
            return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/{vaultItemId}/rfid-tag")
    @ResponseBody
    public ResponseEntity<String> updateRfidTag(
            @PathVariable String vaultItemId,
            @RequestParam String rfidTag) {
        try {
            vaultItemService.updateRfidTag(vaultItemId, rfidTag);
            return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{vaultItemId}")
    @ResponseBody
    public ResponseEntity<Void> unbind(@PathVariable String vaultItemId) {
        try {
            vaultItemService.unbindItem(vaultItemId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
    }
}
