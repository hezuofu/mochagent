package io.sketch.mochaagents.plugin;

/**
 * Extension point — a contract that plugins implement to inject or replace
 * framework components. Each extension point corresponds to a swappable module.
 *
 * <h2>Built-in extension points</h2>
 * <table>
 *   <tr><td>{@code TOOL}</td><td>Register a new tool</td></tr>
 *   <tr><td>{@code PERCEPTOR}</td><td>Replace environment perception</td></tr>
 *   <tr><td>{@code REASONER}</td><td>Replace reasoning strategy</td></tr>
 *   <tr><td>{@code PLANNER}</td><td>Replace planning strategy</td></tr>
 *   <tr><td>{@code EVALUATOR}</td><td>Replace quality evaluation</td></tr>
 *   <tr><td>{@code HOOK}</td><td>Intercept tool calls</td></tr>
 *   <tr><td>{@code SKILL}</td><td>Register a skill</td></tr>
 *   <tr><td>{@code LOOP}</td><td>Replace execution paradigm</td></tr>
 *   <tr><td>{@code MCP_SERVER}</td><td>Connect external MCP server</td></tr>
 * </table>
 *
 * @param <T> the type of component this extension provides
 * @author lanxia39@163.com
 */
public interface ExtensionPoint<T> {

    /** Unique identifier for this extension point type. */
    String type();

    /** Human-readable description. */
    String description();

    /** The component instance this extension provides. */
    T component();

    /** Priority — higher values win when multiple plugins provide the same extension. */
    default int priority() { return 0; }

    // ============ Factory methods for each type ============

    static ExtensionPoint<io.sketch.mochaagents.tool.Tool> tool(
            io.sketch.mochaagents.tool.Tool tool, int priority) {
        return new Simple<>("TOOL", tool.getName() + " tool", tool, priority);
    }

    static ExtensionPoint<io.sketch.mochaagents.perception.Perceptor<?, ?>> perceptor(
            io.sketch.mochaagents.perception.Perceptor<?, ?> p, int priority) {
        return new Simple<>("PERCEPTOR", "Perceptor: " + p.getClass().getSimpleName(), p, priority);
    }

    static ExtensionPoint<io.sketch.mochaagents.reasoning.Reasoner> reasoner(
            io.sketch.mochaagents.reasoning.Reasoner r, int priority) {
        return new Simple<>("REASONER", "Reasoner: " + r.getClass().getSimpleName(), r, priority);
    }

    static ExtensionPoint<io.sketch.mochaagents.plan.Planner<?>> planner(
            io.sketch.mochaagents.plan.Planner<?> p, int priority) {
        return new Simple<>("PLANNER", "Planner: " + p.getClass().getSimpleName(), p, priority);
    }

    static ExtensionPoint<io.sketch.mochaagents.evaluation.Evaluator> evaluator(
            io.sketch.mochaagents.evaluation.Evaluator e, int priority) {
        return new Simple<>("EVALUATOR", "Evaluator: " + e.getClass().getSimpleName(), e, priority);
    }

    static ExtensionPoint<io.sketch.mochaagents.agent.react.AgenticLoop<String, String>> loop(
            io.sketch.mochaagents.agent.react.AgenticLoop<String, String> l, int priority) {
        return new Simple<>("LOOP", "Loop: " + l.getClass().getSimpleName(), l, priority);
    }

    static ExtensionPoint<String> mcpServer(String command, int priority) {
        return new Simple<>("MCP_SERVER", "MCP: " + command, command, priority);
    }

    /** Simple immutable implementation. */
    record Simple<T>(String type, String description, T component, int priority)
            implements ExtensionPoint<T> {}
}
