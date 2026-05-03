package io.sketch.mochaagents.agent.impl;

import io.sketch.mochaagents.agent.AgentContext;
import io.sketch.mochaagents.agent.react.ReActAgent;
import io.sketch.mochaagents.tool.Tool;
import io.sketch.mochaagents.tool.ToolInput;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Managed agent wrapper — wraps a sub-agent as a callable Tool with
 * configurable task/report templates (smolagents pattern).
 * @author lanxia39@163.com
 */
public final class ManagedAgent implements Tool {

    private final ReActAgent agent;
    private final String taskTemplate;
    private final String reportTemplate;
    private final boolean includeRunSummary;

    public ManagedAgent(ReActAgent agent, String taskTemplate, String reportTemplate,
                         boolean includeRunSummary) {
        this.agent = agent;
        this.taskTemplate = taskTemplate;
        this.reportTemplate = reportTemplate;
        this.includeRunSummary = includeRunSummary;
    }

    public ManagedAgent(ReActAgent agent) {
        this(agent, "Task: {{task}}", "Result: {{answer}}", false);
    }

    @Override public String getName() { return agent.name; }
    @Override public String getDescription() { return agent.description != null ? agent.description : "Sub-agent"; }
    @Override public Map<String, ToolInput> getInputs() {
        Map<String, ToolInput> in = new LinkedHashMap<>();
        in.put("task", ToolInput.string("Task to delegate"));
        in.put("additional_args", ToolInput.any("Additional context (optional)"));
        return in;
    }
    @Override public String getOutputType() { return "string"; }
    @Override public SecurityLevel getSecurityLevel() { return SecurityLevel.MEDIUM; }

    @Override
    public Object call(Map<String, Object> arguments) {
        String task = (String) arguments.getOrDefault("task", "");
        String wrappedTask = taskTemplate.replace("{{task}}", task);

        // Inject additional args
        Object extra = arguments.get("additional_args");
        if (extra != null) wrappedTask += "\n\nAdditional context: " + extra;

        // Run sub-agent
        String answer = agent.run(AgentContext.of(wrappedTask));

        // Wrap result with report template
        String report = reportTemplate.replace("{{answer}}", answer);
        if (includeRunSummary) {
            report += "\n\n[Steps: " + agent.memory().steps().size() + "]";
        }
        return report;
    }
}
