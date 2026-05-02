package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.*;
import io.sketch.mochaagents.core.impl.BaseAgent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 编程 Agent — 示例：专注于代码生成与修改的 Agent.
 */
public class CodingAgent extends BaseAgent<String, String> {

    protected CodingAgent(Builder builder) {
        super(builder);
    }

    @Override
    protected String doExecute(String input) {
        return "[CodingAgent] Generated code for: " + input;
    }

    @Override
    public CompletableFuture<String> executeAsync(String input) {
        return CompletableFuture.completedFuture(doExecute(input));
    }

    /** 工厂方法 */
    public static CodingAgent create(String name) {
        return new Builder().name(name)
                .description("Coding Agent - generates and modifies code")
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override
        public CodingAgent build() {
            return new CodingAgent(this);
        }
    }
}
