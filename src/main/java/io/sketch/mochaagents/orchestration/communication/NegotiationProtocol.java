package io.sketch.mochaagents.orchestration.communication;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协商协议 — Agent 之间的任务协商与冲突解决协议.
 * @author lanxia39@163.com
 */
public class NegotiationProtocol {

    private final Map<String, NegotiationSession> sessions = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public NegotiationProtocol(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public NegotiationProtocol() {
        this(30000);
    }

    /** 发起协商 */
    public NegotiationSession startNegotiation(String initiatorId, String targetId, String topic) {
        String sessionId = UUID.randomUUID().toString();
        NegotiationSession session = new NegotiationSession(sessionId, initiatorId, targetId, topic, timeoutMs);
        sessions.put(sessionId, session);
        return session;
    }

    /** 提出方案 */
    public void propose(String sessionId, String agentId, Object proposal) {
        NegotiationSession session = sessions.get(sessionId);
        if (session != null && !session.isExpired()) {
            session.addProposal(agentId, proposal);
        }
    }

    /** 接受方案 */
    public boolean accept(String sessionId, String agentId) {
        NegotiationSession session = sessions.get(sessionId);
        if (session != null) {
            session.accept(agentId);
            return true;
        }
        return false;
    }

    /** 拒绝方案 */
    public void reject(String sessionId, String agentId, String reason) {
        NegotiationSession session = sessions.get(sessionId);
        if (session != null) {
            session.reject(agentId, reason);
        }
    }

    /** 获取协商结果 */
    public Optional<NegotiationResult> getResult(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId))
                .filter(NegotiationSession::isResolved)
                .map(s -> new NegotiationResult(s.sessionId, s.resolution, s.acceptedBy));
    }

    /** 协商会话 */
    public static class NegotiationSession {
        private final String sessionId;
        private final String initiatorId;
        private final String targetId;
        private final String topic;
        private final long createdAt;
        private final long timeoutMs;
        private final List<Proposal> proposals = new ArrayList<>();
        private String resolution;
        private String acceptedBy;

        NegotiationSession(String sessionId, String initiatorId, String targetId, String topic, long timeoutMs) {
            this.sessionId = sessionId;
            this.initiatorId = initiatorId;
            this.targetId = targetId;
            this.topic = topic;
            this.timeoutMs = timeoutMs;
            this.createdAt = System.currentTimeMillis();
        }

        void addProposal(String agentId, Object proposal) {
            proposals.add(new Proposal(agentId, proposal));
        }

        void accept(String agentId) {
            this.acceptedBy = agentId;
            this.resolution = proposals.isEmpty() ? "accepted" : proposals.get(proposals.size() - 1).content().toString();
        }

        void reject(String agentId, String reason) {
            this.resolution = "rejected: " + reason;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > timeoutMs;
        }

        boolean isResolved() {
            return resolution != null && !isExpired();
        }

        private record Proposal(String agentId, Object content) {}
    }

    /** 协商结果 */
    public record NegotiationResult(String sessionId, String resolution, String acceptedBy) {}
}
