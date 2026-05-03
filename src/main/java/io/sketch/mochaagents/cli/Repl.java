package io.sketch.mochaagents.cli;

import io.sketch.mochaagents.AgentBootstrap;
import io.sketch.mochaagents.agent.impl.ToolCallingAgent;
import io.sketch.mochaagents.llm.provider.MockLLM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/** Interactive REPL — reads stdin line-by-line, runs agent, prints results. */
final class Repl implements CliCommand {

    private static final Logger log = LoggerFactory.getLogger(Repl.class);
    private static final String VERSION = "0.1.0";
    private ToolCallingAgent agent;
    private AgentBootstrap bootstrap;

    @Override
    public int run(String[] args, PrintStream out, PrintStream err) {
        out.println("MochaAgents " + VERSION + " — type a task, 'exit' to quit, 'help' for commands.\n");
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
                        try { out.println(" => " + agent().run(line)); }
                        catch (Exception e) { log.error("Agent error", e); out.println("Error: " + e.getMessage()); }
                }
            }
        } catch (Exception e) { log.error("REPL error", e); }
        return 0;
    }

    private ToolCallingAgent agent() {
        if (agent == null) {
            bootstrap = AgentBootstrap.init();
            agent = ToolCallingAgent.builder().name("repl-agent").llm(MockLLM.create())
                    .toolRegistry(bootstrap.toolRegistry()).maxSteps(10).build();
            log.info("REPL agent ready");
        }
        return agent;
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "..."; }
}
