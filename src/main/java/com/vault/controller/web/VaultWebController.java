package com.vault.controller.web;

import com.vault.dto.CreateVaultRequest;
import com.vault.service.BindingService;
import com.vault.service.ItemService;
import com.vault.service.VaultItemService;
import com.vault.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/vaults")
public class VaultWebController {

    private final VaultService vaultService;
    private final VaultItemService vaultItemService;
    private final ItemService itemService;
    private final BindingService bindingService;

    public VaultWebController(VaultService vaultService, VaultItemService vaultItemService,
                              ItemService itemService, BindingService bindingService) {
        this.vaultService = vaultService;
        this.vaultItemService = vaultItemService;
        this.itemService = itemService;
        this.bindingService = bindingService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String binding,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        var boundVaultIds = bindingService.listAll().stream()
                .map(b -> b.vaultId())
                .collect(java.util.stream.Collectors.toSet());

        var vaults = vaultService.searchVaults(search).stream()
                .filter(v -> switch (binding == null ? "" : binding) {
                    case "AVAILABLE"   -> !boundVaultIds.contains(v.vaultId());
                    case "UNAVAILABLE" ->  boundVaultIds.contains(v.vaultId());
                    default            -> true;
                }).toList();

        model.addAttribute("vaults", vaults);
        model.addAttribute("currentBinding", binding != null ? binding : "");
        return htmx != null ? "vaults/list :: vaults-tbody" : "vaults/list";
    }

    @GetMapping("/{vaultId}")
    public String detail(@PathVariable String vaultId, Model model) {
        return vaultService.getVault(vaultId).map(vault -> {
            var vaultItems = vaultItemService.listByVault(vaultId);
            model.addAttribute("vault", vault);
            model.addAttribute("vaultItems", vaultItems);
            model.addAttribute("boundAgents", bindingService.listByVault(vaultId));

            // เฉพาะ item ที่ยังไม่มี active VaultItem (ไม่ว่าจะอยู่ใน vault ไหนก็ตาม)
            var allBoundItemIds = vaultItemService.listAllActive().stream()
                    .map(vi -> vi.itemId())
                    .collect(java.util.stream.Collectors.toSet());
            var availableItems = itemService.listItems().stream()
                    .filter(i -> !allBoundItemIds.contains(i.itemId()))
                    .toList();
            model.addAttribute("allItems", availableItems);
            return "vaults/detail";
        }).orElse("redirect:/vaults");
    }

    // ── VaultItem endpoints ──────────────────────────────────────────────────

    @PostMapping("/{vaultId}/items")
    @ResponseBody
    public ResponseEntity<String> bindItem(
            @PathVariable String vaultId,
            @RequestParam String itemId,
            @RequestParam String slotId) {
        try {
            vaultItemService.bindItemToVault(vaultId, itemId, slotId);
            return ResponseEntity.ok().header("HX-Redirect", "/vaults/" + vaultId).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{vaultId}/items/{vaultItemId}")
    @ResponseBody
    public ResponseEntity<Void> unbindItem(
            @PathVariable String vaultId,
            @PathVariable String vaultItemId) {
        try {
            vaultItemService.unbindItem(vaultItemId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/vaults/" + vaultId).build();
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> create(
            @RequestParam String vaultId,
            @RequestParam String vaultName,
            @RequestParam Integer vaultSlot,
            @RequestParam(required = false) String description) {
        try {
            vaultService.createVault(new CreateVaultRequest(vaultId, vaultName, vaultSlot, description));
            return ResponseEntity.ok().header("HX-Redirect", "/vaults").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/{vaultId}")
    @ResponseBody
    public ResponseEntity<String> update(
            @PathVariable String vaultId,
            @RequestParam(required = false) String vaultName,
            @RequestParam(required = false) String vaultStatus,
            @RequestParam(required = false) String description) {
        try {
            vaultService.updateVault(vaultId, vaultName, vaultStatus, description);
            return ResponseEntity.ok().header("HX-Redirect", "/vaults/" + vaultId).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{vaultId}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable String vaultId) {
        try {
            vaultService.deleteVault(vaultId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/vaults").build();
    }
}
