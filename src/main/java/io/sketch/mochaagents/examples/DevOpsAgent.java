package io.sketch.mochaagents.examples;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.impl.BaseAgent;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.LLM;
import io.sketch.mochaagents.llm.provider.MockLLM;
import io.sketch.mochaagents.tool.ToolRegistry;

import java.util.concurrent.CompletableFuture;

/**
 * DevOps Agent — uses ToolCallingAgent with terminal/CLI tools.
 * Demonstrates specialized agent composition for infrastructure tasks.
 * @author lanxia39@163.com
 */
public class DevOpsAgent extends BaseAgent<String, String> {

    private final ToolCallingAgent delegate;

    protected DevOpsAgent(Builder builder) {
        super(builder);
        LLM llm = builder.llm != null ? builder.llm : MockLLM.create();
        ToolRegistry tools = builder.toolRegistry != null ? builder.toolRegistry : new ToolRegistry();
        this.delegate = ToolCallingAgent.builder().name(name).llm(llm)
                .toolRegistry(tools).maxSteps(15)
                .systemPromptTemplate(io.sketch.mochaagents.prompt.PromptTemplate.of(
                        "You are a DevOps engineer. Execute terminal commands, manage files, "
                      + "and handle deployments. Verify each step before proceeding. Tools: {tools}"))
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
        public DevOpsAgent build() { return new DevOpsAgent(this); }
    }
}
