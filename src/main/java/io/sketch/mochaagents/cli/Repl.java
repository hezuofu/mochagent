package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.LLM;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/** Interactive REPL — reads stdin line-by-line, runs agent, prints results. */
final class Repl implements CliCommand {

    private static final Logger log = LoggerFactory.getLogger(Repl.class);
    private static final String VERSION = "0.1.0";
    private final ModelConfig modelCfg;
    private ToolCallingAgent agent;
    private AgentBootstrap bootstrap;
    private LLM llm;

    Repl(ModelConfig modelCfg) { this.modelCfg = modelCfg; }

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        out.println("MochaAgents " + VERSION + " — AI agent CLI");
        out.println("Model: " + (modelCfg.hasModels() ? modelCfg.build().modelName() : "fallback (use --model flag)"));
        out.println("Type a task, 'exit' to quit, 'help' for commands.\n");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            for (;;) {
                out.print("> ");
                String line = in.readLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                switch (line.toLowerCase()) {
                    case "exit": case "quit": case "q": out.println("Goodbye."); return 0;
                    case "help": case "h": out.println("Commands: help, version, status, exit. Anything else runs the agent."); break;
                    case "version": out.println(VERSION); break;
                    case "status":
                        out.println(agent == null ? "No agent loaded."
                                : "Agent: " + agent.metadata().name() + ", Tools: "
                                + (bootstrap != null ? bootstrap.toolRegistry().size() : 0)); break;
                    default:
                        out.println("[Agent] " + truncate(line, 80));
                        try {
                            // Subscribe to events for real-time feedback
                            var a = agent();
                            var unsub = a.onEvent(e -> {
                                switch (e.type()) {
                                    case io.sketch.mochaagents.agent.AgentEvents.COST -> {
                                        double[] c = (double[]) e.data();
                                        out.printf("  [cost: $%.4f, %d in/%d out tokens]%n",
                                                c[0], (long) c[1], (long) c[2]);
                                    }
                                    case io.sketch.mochaagents.agent.AgentEvents.COMPLETED ->
                                        out.println("  [completed in " + e.elapsedMs() + "ms]");
                                }
                            });
                            String result = a.run(line);
                            unsub.run(); // unsubscribe
                            out.println(" => " + result);
                        } catch (Exception e) { log.error("Agent error", e); out.println("Error: " + e.getMessage()); }
                }
            }
        } catch (Exception e) { log.error("REPL error", e); }
        return 0;
    }

    private ToolCallingAgent agent() {
        if (agent == null) {
            bootstrap = AgentBootstrap.init();
            llm = modelCfg.build();
            String modelInfo = modelCfg.hasModels() ? llm.modelName() : "mock";
            agent = ToolCallingAgent.builder().name("repl-agent").llm(llm)
                    .toolRegistry(bootstrap.toolRegistry()).maxSteps(modelCfg.maxTokens() > 0 ? 20 : 10).build();
            log.info("REPL agent ready — model: {}, temperature: {}", modelInfo, modelCfg.temperature());
        }
        return agent;
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }
}
