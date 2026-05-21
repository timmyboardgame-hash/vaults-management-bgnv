package com.vault.controller.web;

import com.vault.service.AgentService;
import com.vault.service.BindingService;
import com.vault.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/bindings")
public class BindingWebController {

    private final BindingService bindingService;
    private final AgentService agentService;
    private final VaultService vaultService;

    public BindingWebController(BindingService bindingService,
                                AgentService agentService,
                                VaultService vaultService) {
        this.bindingService = bindingService;
        this.agentService = agentService;
        this.vaultService = vaultService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("bindings", bindingService.listAll());
        model.addAttribute("agents",  agentService.listAgents());
        model.addAttribute("vaults",  vaultService.listVaults());
        return "bindings/list";
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> bind(
            @RequestParam String agentId,
            @RequestParam String vaultId) {
        try {
            bindingService.bindAgentToVault(agentId, vaultId);
            return ResponseEntity.ok().header("HX-Redirect", "/bindings").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{bindingId}")
    @ResponseBody
    public ResponseEntity<Void> unbind(@PathVariable String bindingId) {
        try {
            bindingService.unbindAgentFromVault(bindingId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/bindings").build();
    }
}
