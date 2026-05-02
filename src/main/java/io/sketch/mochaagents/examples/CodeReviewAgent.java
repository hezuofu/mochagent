package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.core.*;
import io.sketch.mochaagents.core.impl.BaseAgent;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 代码审查 Agent — 示例：专注于代码审查与质量分析的 Agent.
 */
public class CodeReviewAgent extends BaseAgent<String, String> {

    protected CodeReviewAgent(Builder builder) {
        super(builder);
    }

    @Override
    protected String doExecute(String input) {
        return "[CodeReviewAgent] Review completed for: " + input;
    }

    @Override
    public CompletableFuture<String> executeAsync(String input) {
        return CompletableFuture.completedFuture(doExecute(input));
    }

    public static CodeReviewAgent create(String name) {
        return new Builder().name(name)
                .description("Code Review Agent - reviews and analyzes code quality")
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        @Override
        public CodeReviewAgent build() {
            return new CodeReviewAgent(this);
        }
    }
}
