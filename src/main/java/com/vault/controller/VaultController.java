package com.vault.controller;

import com.vault.dto.CreateVaultRequest;
import com.vault.dto.VaultResponse;
import com.vault.service.VaultService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vaults")
public class VaultController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @GetMapping
    public ResponseEntity<List<VaultResponse>> list() {
        return ResponseEntity.ok(vaultService.listVaults());
    }

    @GetMapping("/{vaultId}")
    public ResponseEntity<VaultResponse> get(@PathVariable String vaultId) {
        return vaultService.getVault(vaultId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<VaultResponse> create(@Valid @RequestBody CreateVaultRequest req) {
        return ResponseEntity.ok(vaultService.createVault(req));
    }

    @DeleteMapping("/{vaultId}")
    public ResponseEntity<VaultResponse> delete(@PathVariable String vaultId) {
        return ResponseEntity.ok(vaultService.deleteVault(vaultId));
    }
}
