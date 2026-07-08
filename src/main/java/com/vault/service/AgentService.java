package com.vault.service;

import com.vault.dto.AgentResponse;
import com.vault.dto.CreateAgentRequest;
import com.vault.entity.Agent;
import com.vault.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agentRepository;

    public AgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public List<AgentResponse> listAgents() {
        return agentRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
            .stream().map(this::toResponse).toList();
    }

    public List<AgentResponse> searchAgents(String search) {
        List<Agent> all = agentRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();
        if (search == null || search.isBlank()) return all.stream().map(this::toResponse).toList();
        String q = search.toLowerCase();
        return all.stream().filter(a ->
            a.getAgentId().toLowerCase().contains(q) ||
            (a.getAgentName() != null && a.getAgentName().toLowerCase().contains(q))
        ).map(this::toResponse).toList();
    }

    public Optional<AgentResponse> getAgent(String agentId) {
        return agentRepository.findByAgentIdAndDeletedAtIsNull(agentId)
            .map(this::toResponse);
    }

    @Transactional
    public AgentResponse createAgent(CreateAgentRequest req) {
        log.info("[AGENT] Creating agent {}", req.agentId());
        if (agentRepository.existsByAgentIdAndDeletedAtIsNull(req.agentId())) {
            throw new IllegalArgumentException("Agent ID already exists: " + req.agentId());
        }
        Agent agent = new Agent();
        agent.setAgentId(req.agentId());
        agent.setAgentName(req.agentName());
        agent.setAgentStatus(req.agentStatus() != null ? req.agentStatus() : "ENABLE");
        agent.setPhone(req.phone());
        agent.setAddress(req.address());
        agent.setProvinceCode(req.provinceCode());
        agent.setMapUrl(req.mapUrl());
        agent.setLatitude(req.latitude());
        agent.setLongitude(req.longitude());
        agent.setPromotion(req.promotion());
        agent.setRemark1(req.remark1());
        agent.setRemark2(req.remark2());
        agent.setRemark3(req.remark3());
        agent.setRemark4(req.remark4());
        agent.setRemark5(req.remark5());
        return toResponse(agentRepository.save(agent));
    }

    @Transactional
    public AgentResponse updateAgent(String agentId, String newAgentId,
                                     String agentName, String agentStatus,
                                     String phone, String address, String mapUrl,
                                     String promotion, String remark1) {
        log.info("[AGENT] Updating agent {}", agentId);
        Agent agent = agentRepository.findByAgentIdAndDeletedAtIsNull(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        // เปลี่ยน agentId ได้ — FK อ้าง DB id (UUID) อยู่แล้ว booking/binding เดิมไม่กระทบ
        if (newAgentId != null && !newAgentId.isBlank() && !newAgentId.equals(agentId)) {
            if (agentRepository.existsByAgentIdAndDeletedAtIsNull(newAgentId)) {
                throw new IllegalArgumentException("Agent ID already exists: " + newAgentId);
            }
            agent.setAgentId(newAgentId);
        }
        if (agentName  != null) agent.setAgentName(agentName);
        if (agentStatus != null) agent.setAgentStatus(agentStatus);
        agent.setPhone(phone);
        agent.setAddress(address);
        agent.setMapUrl(mapUrl);
        agent.setPromotion(promotion);
        agent.setRemark1(remark1);
        return toResponse(agentRepository.save(agent));
    }

    @Transactional
    public AgentResponse deleteAgent(String agentId) {
        log.info("[AGENT] Deleting agent {}", agentId);
        Agent agent = agentRepository.findByAgentIdAndDeletedAtIsNull(agentId)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        agent.setDeletedAt(LocalDateTime.now());
        return toResponse(agentRepository.save(agent));
    }

    private AgentResponse toResponse(Agent a) {
        return new AgentResponse(
            a.getId(), a.getAgentId(), a.getAgentName(), a.getAgentStatus(),
            a.getPhone(), a.getAddress(), a.getProvinceCode(), a.getMapUrl(),
            a.getLatitude(), a.getLongitude(), a.getPromotion(),
            a.getRemark1(), a.getRemark2(), a.getRemark3(), a.getRemark4(), a.getRemark5(),
            a.getCreatedAt()
        );
    }
}
