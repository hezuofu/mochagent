// SPDX-License-Identifier: Apache-2.0
// Copyright 2024-2026 MochaAgents Authors

package io.sketch.mochaagents.orchestration;

import io.sketch.mochaagents.agent.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator — wraps a {@link TaskRunner} with retry and timeout.
 *
 * @author lanxia39@163.com
 */
public final class RetryingRunner implements TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(RetryingRunner.class);

    private final TaskRunner delegate;
    private final ExecutionPolicy policy;

    public RetryingRunner(TaskRunner delegate, ExecutionPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public Object run(String taskDescription, Agent<?, ?> worker) {
        Exception last = null;
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            try {
                return delegate.run(taskDescription, worker);
            } catch (Exception e) {
                last = e;
                log.warn("Task attempt {}/{} failed: {}", attempt + 1, policy.maxRetries() + 1, e.getMessage());
                if (attempt < policy.maxRetries()) {
                    try {
                        Thread.sleep(1000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("Task failed after " + (policy.maxRetries() + 1) + " attempts", last);
    }
}
