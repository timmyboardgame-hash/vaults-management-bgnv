package com.vault.service;

import com.vault.dto.BindingResponse;
import com.vault.entity.Agent;
import com.vault.entity.Binding;
import com.vault.entity.Vault;
import com.vault.repository.AgentRepository;
import com.vault.repository.BindingRepository;
import com.vault.repository.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class BindingService {

    private static final Logger log = LoggerFactory.getLogger(BindingService.class);

    private final BindingRepository bindingRepository;
    private final AgentRepository agentRepository;
    private final VaultRepository vaultRepository;

    public BindingService(BindingRepository bindingRepository,
                          AgentRepository agentRepository,
                          VaultRepository vaultRepository) {
        this.bindingRepository = bindingRepository;
        this.agentRepository = agentRepository;
        this.vaultRepository = vaultRepository;
    }

    public List<BindingResponse> listByAgent(String agentId) {
        return bindingRepository.findActiveByAgentId(agentId)
                .stream().map(this::toResponse).toList();
    }

    public List<BindingResponse> listByVault(String vaultId) {
        return bindingRepository.findActiveByVaultId(vaultId)
                .stream().map(this::toResponse).toList();
    }

    public List<BindingResponse> listAll() {
        return bindingRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public BindingResponse bindAgentToVault(String agentId, String vaultId) {
        log.info("[BINDING] Binding agent={} to vault={}", agentId, vaultId);

        Agent agent = agentRepository.findByAgentIdAndDeletedAtIsNull(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        Vault vault = vaultRepository.findByVaultIdAndDeletedAtIsNull(vaultId)
                .orElseThrow(() -> new IllegalArgumentException("Vault not found: " + vaultId));

        // ป้องกัน duplicate binding
        if (bindingRepository.findActiveByAgentAndVault(agentId, vaultId).isPresent()) {
            throw new IllegalArgumentException("Agent " + agentId + " is already bound to vault " + vaultId);
        }

        Binding binding = new Binding();
        binding.setAgent(agent);
        binding.setVault(vault);
        binding.setStatus("ACTIVE");

        // TODO: ส่ง BIND_VAULT command ไปยัง device ผ่าน AWS IoT Jobs
        // awsIotService.bindVault(vault.getVaultId(), agent.getAgentId());

        return toResponse(bindingRepository.save(binding));
    }

    @Transactional
    public void unbindAgentFromVault(String bindingId) {
        log.info("[BINDING] Unbinding {}", bindingId);

        Binding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new IllegalArgumentException("Binding not found: " + bindingId));

        // TODO: ส่ง UNBIND_VAULT command ไปยัง device ผ่าน AWS IoT Jobs

        binding.setStatus("INACTIVE");
        binding.setDeletedAt(LocalDateTime.now());
        bindingRepository.save(binding);
    }

    private BindingResponse toResponse(Binding b) {
        return new BindingResponse(
                b.getId(),
                b.getAgent().getAgentId(),
                b.getAgent().getAgentName(),
                b.getVault().getVaultId(),
                b.getVault().getVaultName(),
                b.getStatus(),
                b.getCreatedAt()
        );
    }
}
