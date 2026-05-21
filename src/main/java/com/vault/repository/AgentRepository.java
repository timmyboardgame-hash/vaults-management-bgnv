package com.vault.repository;

import com.vault.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, String> {

    Optional<Agent> findByAgentIdAndDeletedAtIsNull(String agentId);

    List<Agent> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    boolean existsByAgentIdAndDeletedAtIsNull(String agentId);
}
