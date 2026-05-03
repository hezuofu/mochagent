package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.BaseAgent;

import java.util.concurrent.CompletableFuture;

/**
 * 全栈 Agent — 示例：集编码、审查、部署能力于一体的综合性 Agent.
 * @author lanxia39@163.com
 */
public class FullStackAgent extends BaseAgent<String, String> {

    protected FullStackAgent(Builder builder) {
        super(builder);
    }

    @Override
    protected String doExecute(String input, AgentContext ctx) {
        return "[FullStackAgent] Full-stack task completed: " + input;
    }

    @Override
    public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
        return CompletableFuture.completedFuture(doExecute(input, ctx));
    }

    public static FullStackAgent create(String name) {
        return new Builder().name(name)
                .description("Full-Stack Agent - combines coding, review, testing and deployment")
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override
        public FullStackAgent build() {
            return new FullStackAgent(this);
        }
    }
}
