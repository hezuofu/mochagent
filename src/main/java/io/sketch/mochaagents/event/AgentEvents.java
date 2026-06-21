// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.event;

import java.time.Instant;
import java.util.Map;

/**
 * Typed agent event records — Guava EventBus dispatch by class.
 *
 * <p>Replace the old string-typed {@code AgentEvent} with specific records.
 * Each event type is its own class → automatic dispatch via {@link EventBus}.
 *
 * @author lanxia39@163.com
 */
public final class AgentEvents {

    private AgentEvents() {}

    /** Agent execution started. */
    public record Started(String agentName, String task, long elapsedMs, Instant timestamp) {
        public Started(String agentName, String task, long elapsedMs) {
            this(agentName, task, elapsedMs, Instant.now());
        }
    }

    /** Single ReAct step completed. */
    public record StepCompleted(String agentName, int stepNumber, String modelOutput,
                                 String observation, String action, long elapsedMs, Instant timestamp) {
        public StepCompleted(String agentName, int stepNumber, String modelOutput,
                             String observation, String action, long elapsedMs) {
            this(agentName, stepNumber, modelOutput, observation, action, elapsedMs, Instant.now());
        }
        public boolean isFinalAnswer() { return "final_answer".equals(action); }
    }

    /** A tool was executed. */
    public record ToolCalled(String toolName, String agentName, Map<String, Object> arguments,
                              Object result, long elapsedMs, Instant timestamp) {
        public ToolCalled(String toolName, String agentName, Map<String, Object> arguments,
                          Object result, long elapsedMs) {
            this(toolName, agentName, arguments, result, elapsedMs, Instant.now());
        }
        /** Extract file path for file-modifying tools. */
        public String file() { return (String) arguments.get("file_path"); }
    }

    /** A file was modified on disk. */
    public record FileModified(String filePath, String toolName, String oldContent,
                                String newContent, int stepNumber, Instant timestamp) {
        public FileModified(String filePath, String toolName, String oldContent, String newContent, int stepNumber) {
            this(filePath, toolName, oldContent, newContent, stepNumber, Instant.now());
        }
    }

    /** Agent execution completed. */
    public record Completed(String agentName, String result, int totalSteps, long elapsedMs,
                             double estimatedCost, long inputTokens, long outputTokens, Instant timestamp) {
        public Completed(String agentName, String result, int totalSteps, long elapsedMs,
                         double estimatedCost, long inputTokens, long outputTokens) {
            this(agentName, result, totalSteps, elapsedMs, estimatedCost, inputTokens, outputTokens, Instant.now());
        }
    }

    /** Agent encountered an error. */
    public record Error(String agentName, String message, Throwable exception, Instant timestamp) {
        public Error(String agentName, String message, Throwable exception) {
            this(agentName, message, exception, Instant.now());
        }
    }

    /** Session lifecycle event. */
    public record SessionStarted(String sessionId, String userId, String cwd, Instant timestamp) {
        public SessionStarted(String sessionId, String userId, String cwd) {
            this(sessionId, userId, cwd, Instant.now());
        }
    }

    public record SessionEnded(String sessionId, int messageCount, long inputTokens,
                                long outputTokens, double estimatedCost, Instant timestamp) {
        public SessionEnded(String sessionId, int messageCount, long inputTokens,
                            long outputTokens, double estimatedCost) {
            this(sessionId, messageCount, inputTokens, outputTokens, estimatedCost, Instant.now());
        }
    }

    /** A memory record was persisted. */
    public record MemorySaved(String memoryId, String type, String content, double importance) {}

    /** Task lifecycle event (orchestration). */
    public record TaskStatusChanged(String taskId, String status, Object data, long elapsedMs, Instant timestamp) {
        public TaskStatusChanged(String taskId, String status, Object data, long elapsedMs) {
            this(taskId, status, data, elapsedMs, Instant.now());
        }
    }
}
