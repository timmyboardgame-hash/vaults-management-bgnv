package com.vault.controller.web;

import com.vault.service.ItemService;
import com.vault.service.VaultItemService;
import com.vault.service.VaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/vault-items")
public class VaultItemWebController {

    private static final Logger log = LoggerFactory.getLogger(VaultItemWebController.class);

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
    public String list(Model model, @RequestParam(required = false) String placement) {
        var all = vaultItemService.listAllActive();

        // กล่องที่แอปร้านลงทะเบียนเข้ามาแต่ยังไม่ได้เอาเข้าตู้ — ต้องมีที่ให้คนหลังบ้านตามเก็บ
        long unassignedCount = all.stream().filter(vi -> !vi.inVault()).count();
        var shown = switch (placement == null ? "" : placement) {
            case "UNASSIGNED" -> all.stream().filter(vi -> !vi.inVault()).toList();
            case "IN_VAULT"   -> all.stream().filter(vi ->  vi.inVault()).toList();
            default           -> all;
        };

        model.addAttribute("vaultItems", shown);
        model.addAttribute("unassignedCount", unassignedCount);
        model.addAttribute("currentPlacement", placement != null ? placement : "");
        model.addAttribute("vaults", vaultService.listVaults());
        // item เดียวกัน bind ได้หลายกล่อง (ต่าง serial) — แสดงทุก item เสมอ
        model.addAttribute("items", itemService.listItems());
        return "vault-items/list";
    }

    // ระบุตู้ให้กล่องที่ยังไม่มีที่อยู่ หรือย้ายข้ามตู้ · vaultId ว่าง = ถอดออกจากตู้
    @PatchMapping("/{vaultItemId}/vault")
    @ResponseBody
    public ResponseEntity<String> assignVault(@PathVariable String vaultItemId,
                                              @RequestParam(required = false) String vaultId) {
        try {
            vaultItemService.assignVault(vaultItemId, vaultId);
            return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
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
        } catch (Exception e) {
            log.warn("[VAULT_ITEM] Operation failed vaultItemId={} reason={}", vaultItemId, e.getMessage());
        }
        return ResponseEntity.ok().header("HX-Redirect", "/vault-items").build();
    }
}
