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

/**
 * ผูก / ถอด ร้านกับตู้ — เป็นเรื่องฝั่ง backend ล้วน ตู้ไม่ต้องรู้ว่าตัวเองสังกัดร้านไหน
 *
 * สัญญา MQTT (doc/mqtt_backend_contract.md) มีคำสั่งถึงตู้ 9 ตัว คือ booking create/cancel/extend,
 * system force_unlock/request_status/clear_lockdown และ inventory sync/write_tag/enqueue
 * ไม่มีคำสั่ง bind หรือ unbind และทั้งเอกสารไม่มีคำว่า agent เลย — payload ของ booking ก็ไม่มีข้อมูลร้าน
 * ถ้าดันส่งไป ตู้จะตอบกลับเป็น error unknown_command (§4.10) โดยไม่ได้อะไรเพิ่ม
 *
 * สิทธิ์การจองถูกบังคับที่ชั้น API อยู่แล้ว: BookingService เช็ก findActiveByAgentAndVault ก่อนทุกครั้ง
 * ร้านที่ไม่ได้ผูกกับตู้จึงจองไม่ผ่านตั้งแต่ต้น ไม่ต้องให้ตู้ช่วยตรวจซ้ำ
 *
 * (เดิมมีหมายเหตุค้างไว้ว่าให้ส่ง BIND_VAULT ซึ่งเป็นของตกค้างจากดีไซน์เดิมที่ใช้ Huawei IoT
 *  ยืนยันแล้วเมื่อ 28 ต.ค. 2569 ว่าตู้ไม่จำเป็นต้องรู้ จึงเอาออก)
 */
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

        // ไม่ต้องแจ้งตู้ — binding เป็นเรื่องฝั่ง backend ล้วน (ดูหมายเหตุหัวคลาส)
        return toResponse(bindingRepository.save(binding));
    }

    @Transactional
    public void unbindAgentFromVault(String bindingId) {
        log.info("[BINDING] Unbinding {}", bindingId);

        Binding binding = bindingRepository.findById(bindingId)
                .orElseThrow(() -> new IllegalArgumentException("Binding not found: " + bindingId));

        // ไม่ต้องแจ้งตู้เช่นกัน — ร้านที่ถูกถอดจะจองไม่ผ่านตั้งแต่ชั้น API
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
