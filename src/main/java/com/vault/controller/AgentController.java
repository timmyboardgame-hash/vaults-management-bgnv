package com.vault.controller;

import com.vault.dto.AgentResponse;
import com.vault.dto.CreateAgentRequest;
import com.vault.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> list() {
        return ResponseEntity.ok(agentService.listAgents());
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<AgentResponse> get(@PathVariable String agentId) {
        return agentService.getAgent(agentId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AgentResponse> create(@Valid @RequestBody CreateAgentRequest req) {
        return ResponseEntity.ok(agentService.createAgent(req));
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<AgentResponse> delete(@PathVariable String agentId) {
        return ResponseEntity.ok(agentService.deleteAgent(agentId));
    }
}
