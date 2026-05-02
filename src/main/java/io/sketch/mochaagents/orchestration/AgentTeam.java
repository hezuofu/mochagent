package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 团队 — 管理一组协作 Agent，按角色组织.
 */
public class AgentTeam {

    private static final Logger log = LoggerFactory.getLogger(AgentTeam.class);

    private final String teamId;
    private final String name;
    private final Map<String, Agent<?, ?>> agents = new ConcurrentHashMap<>();
    private final Map<String, Role> roles = new ConcurrentHashMap<>();
    private final List<String> leaderIds = new ArrayList<>();

    public AgentTeam(String name) {
        this.teamId = UUID.randomUUID().toString();
        this.name = name;
        log.info("AgentTeam '{}' created, id={}", name, teamId);
    }

    /** 添加成员 */
    public AgentTeam addMember(Agent<?, ?> agent, Role role) {
        String id = agent.metadata().name();
        agents.put(id, agent);
        roles.put(id, role);
        if (role.type() == RoleType.LEADER) {
            leaderIds.add(id);
        }
        log.debug("AgentTeam '{}' added member: {} as {}", name, id, role.type());
        return this;
    }

    /** 移除成员 */
    public void removeMember(String agentId) {
        agents.remove(agentId);
        roles.remove(agentId);
        leaderIds.remove(agentId);
        log.debug("AgentTeam '{}' removed member: {}", name, agentId);
    }

    /** 获取所有 Agent */
    public Collection<Agent<?, ?>> getAgents() {
        return agents.values();
    }

    /** 按角色获取 */
    @SuppressWarnings("unchecked")
    public <T extends Agent<?, ?>> List<T> getByRole(RoleType roleType) {
        return (List<T>) agents.entrySet().stream()
                .filter(e -> roles.get(e.getKey()).type() == roleType)
                .map(Map.Entry::getValue)
                .toList();
    }

    /** 获取所有 Agent 列表 */
    @SuppressWarnings("unchecked")
    public <T extends Agent<?, ?>> List<T> getAgentList() {
        return (List<T>) List.copyOf(agents.values());
    }

    /** 获取领导者 */
    @SuppressWarnings("unchecked")
    public <T extends Agent<?, ?>> List<T> getLeaders() {
        return (List<T>) leaderIds.stream().map(agents::get).filter(Objects::nonNull).toList();
    }

    /** 获取 Agent 的角色 */
    public Optional<Role> getRole(String agentId) {
        return Optional.ofNullable(roles.get(agentId));
    }

    public String teamId() { return teamId; }
    public String name() { return name; }
    public int size() { return agents.size(); }

    /** 团队规模 */
    public int memberCount() { return agents.size(); }
}
