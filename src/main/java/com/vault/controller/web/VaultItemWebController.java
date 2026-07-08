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
        // item เดียวกัน bind ได้หลายกล่อง (ต่าง serial) — แสดงทุก item เสมอ
        model.addAttribute("items", itemService.listItems());
        return "vault-items/list";
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> bind(
            @RequestParam String vaultId,
            @RequestParam String itemId,
            @RequestParam String serialNumber,
            @RequestParam(required = false) String rfidTag) {
        try {
            vaultItemService.bindItemToVault(vaultId, itemId, serialNumber, rfidTag);
            return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // แก้ RFID tag + serial ของ copy — serial เปลี่ยนไม่ได้ถ้ามี booking ค้าง
    @PatchMapping("/{vaultItemId}/copy")
    @ResponseBody
    public ResponseEntity<String> updateCopy(
            @PathVariable String vaultItemId,
            @RequestParam(required = false) String rfidTag,
            @RequestParam(required = false) String serialNumber) {
        try {
            vaultItemService.updateCopy(vaultItemId, rfidTag, serialNumber);
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
