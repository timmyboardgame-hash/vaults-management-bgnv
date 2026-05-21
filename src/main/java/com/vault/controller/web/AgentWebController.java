package com.vault.controller.web;

import com.vault.dto.BoundVaultView;
import com.vault.service.AgentService;
import com.vault.service.BindingService;
import com.vault.service.VaultItemService;
import com.vault.service.VaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/agents")
public class AgentWebController {

    private final AgentService agentService;
    private final BindingService bindingService;
    private final VaultItemService vaultItemService;
    private final VaultService vaultService;

    public AgentWebController(AgentService agentService,
                               BindingService bindingService,
                               VaultItemService vaultItemService,
                               VaultService vaultService) {
        this.agentService = agentService;
        this.bindingService = bindingService;
        this.vaultItemService = vaultItemService;
        this.vaultService = vaultService;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(required = false) String search,
                       @RequestParam(required = false) String binding,
                       @RequestHeader(value = "HX-Request", required = false) String htmx) {
        var boundAgentIds = bindingService.listAll().stream()
                .map(b -> b.agentId())
                .collect(java.util.stream.Collectors.toSet());

        var agents = agentService.searchAgents(search).stream()
                .filter(a -> switch (binding == null ? "" : binding) {
                    case "HAS_VAULTS" ->  boundAgentIds.contains(a.agentId());
                    case "NO_VAULTS"  -> !boundAgentIds.contains(a.agentId());
                    default           -> true;
                }).toList();

        model.addAttribute("agents", agents);
        model.addAttribute("currentBinding", binding != null ? binding : "");
        return htmx != null ? "agents/list :: agents-tbody" : "agents/list";
    }

    @GetMapping("/{agentId}")
    public String detail(@PathVariable String agentId, Model model) {
        return agentService.getAgent(agentId).map(agent -> {
            model.addAttribute("agent", agent);

            // bound vaults — สร้าง BoundVaultView แต่ละ vault พร้อม items และ capacity
            List<BoundVaultView> boundVaults = bindingService.listByAgent(agentId).stream()
                .map(b -> vaultService.getVault(b.vaultId()).map(v -> new BoundVaultView(
                        v.vaultId(),
                        v.vaultName(),
                        v.vaultStatus(),
                        v.vaultSlot() != null ? v.vaultSlot() : 0,
                        vaultItemService.listByVault(v.vaultId())
                )).orElse(null))
                .filter(v -> v != null)
                .toList();
            model.addAttribute("boundVaults", boundVaults);

            // เฉพาะ vault ที่ยังไม่ถูก bind กับ agent นี้
            var alreadyBoundVaultIds = boundVaults.stream()
                    .map(bv -> bv.vaultId())
                    .collect(java.util.stream.Collectors.toSet());
            var availableVaults = vaultService.listVaults().stream()
                    .filter(v -> !alreadyBoundVaultIds.contains(v.vaultId()))
                    .toList();
            model.addAttribute("allVaults", availableVaults);

            return "agents/detail";
        }).orElse("redirect:/agents");
    }

    @PostMapping
    @ResponseBody
    public ResponseEntity<String> create(
            @RequestParam String agentId,
            @RequestParam String agentName,
            @RequestParam(defaultValue = "ENABLE") String agentStatus,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String mapUrl,
            @RequestParam(required = false) String promotion,
            @RequestParam(required = false) String remark1) {
        try {
            var req = new com.vault.dto.CreateAgentRequest(
                agentId, agentName, agentStatus, phone, address, null,
                mapUrl, null, null, promotion, remark1, null, null, null, null);
            agentService.createAgent(req);
            return ResponseEntity.ok().header("HX-Redirect", "/agents").build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("/{agentId}")
    @ResponseBody
    public ResponseEntity<String> update(
            @PathVariable String agentId,
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String agentStatus,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) String mapUrl,
            @RequestParam(required = false) String promotion,
            @RequestParam(required = false) String remark1) {
        try {
            agentService.updateAgent(agentId, agentName, agentStatus, phone, address, mapUrl, promotion, remark1);
            return ResponseEntity.ok().header("HX-Redirect", "/agents/" + agentId).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{agentId}")
    @ResponseBody
    public ResponseEntity<Void> delete(@PathVariable String agentId) {
        try {
            agentService.deleteAgent(agentId);
        } catch (Exception ignored) {}
        return ResponseEntity.ok().header("HX-Redirect", "/agents").build();
    }
}
