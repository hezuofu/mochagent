package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.BaseAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.concurrent.CompletableFuture;

/**
 * Code Review Agent — uses ToolCallingAgent with review-focused system prompt.
 * Demonstrates how to create a specialized agent with custom behavior.
 * @author lanxia39@163.com
 */
public class CodeReviewAgent extends BaseAgent<String, String> {

    private final ToolCallingAgent delegate;

    protected CodeReviewAgent(Builder builder) {
        super(builder);
        LLM llm = builder.llm != null ? builder.llm : MockLLM.create();
        ToolRegistry tools = builder.toolRegistry != null ? builder.toolRegistry : new ToolRegistry();
        this.delegate = ToolCallingAgent.builder().name(name).llm(llm)
                .toolRegistry(tools).maxSteps(10)
                .systemPromptTemplate(io.sketch.mochaagents.prompt.PromptTemplate.of(
                        "You are a code reviewer. Analyze code for bugs, security issues, "
                      + "and style problems. Be specific and actionable. Tools: {tools}"))
                .build();
    }

    @Override
    protected String doExecute(String input, AgentContext ctx) {
        return delegate.execute(input, ctx);
    }

    @Override
    public CompletableFuture<String> executeAsync(String input, AgentContext ctx) {
        return delegate.executeAsync(input, ctx);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder extends BaseAgent.Builder<String, String, Builder> {
        LLM llm;
        ToolRegistry toolRegistry;

        public Builder llm(LLM llm) { this.llm = llm; return this; }
        public Builder toolRegistry(ToolRegistry r) { this.toolRegistry = r; return this; }

        @Override
        public CodeReviewAgent build() { return new CodeReviewAgent(this); }
    }
}
