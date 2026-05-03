package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.impl.BaseAgent;

import java.util.concurrent.CompletableFuture;

/**
 * DevOps Agent — 示例：专注于 CI/CD、部署与运维的 Agent.
 * @author lanxia39@163.com
 */
public class DevOpsAgent extends BaseAgent<String, String> {

    protected DevOpsAgent(Builder builder) {
        super(builder);
    }

    @Override
    protected String doExecute(String input) {
        return "[DevOpsAgent] DevOps operation executed: " + input;
    }

    @Override
    public CompletableFuture<String> executeAsync(String input) {
        return CompletableFuture.completedFuture(doExecute(input));
    }

    public static DevOpsAgent create(String name) {
        return new Builder().name(name)
                .description("DevOps Agent - handles CI/CD, deployment and operations")
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override
        public DevOpsAgent build() {
            return new DevOpsAgent(this);
        }
    }
}
